package nz.co.accountabilitycheck.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.KeyguardManager
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.co.accountabilitycheck.AccountabilityApp
import nz.co.accountabilitycheck.domain.AppSettings
import nz.co.accountabilitycheck.domain.ProtectionMode
import nz.co.accountabilitycheck.domain.SessionRecord
import nz.co.accountabilitycheck.domain.SessionStatus
import nz.co.accountabilitycheck.platform.AppCatalog

/** Serialized event processing prevents duplicate gates and stale session callbacks. */
class AccountabilityAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val signals = Channel<Signal>(Channel.UNLIMITED)
    private val repository get() = (application as AccountabilityApp).repository
    private lateinit var overlay: OverlayController
    private lateinit var power: PowerManager
    private lateinit var keyguard: KeyguardManager
    private var deadlineJob: Job? = null
    private var wakeRefreshJob: Job? = null
    private var transitionCheckJob: Job? = null
    private var catalogReady = false
    private var appLabels = emptyMap<String, String>()
    private var launchers = emptySet<String>()
    private var keyboards = emptySet<String>()
    private var excluded = emptySet<String>()
    // Updated synchronously at callback receipt, even while an earlier signal awaits disk IO.
    private var observedPackage: String? = null
    private var foreground: String? = null
    private var priorSettings: AppSettings? = null
    private var phoneGateDue = false
    private var wakeHandled = false
    private var deferPhoneGateOnLauncher = false
    private var exitingUntil = 0L
    private var lastWindowProbeAt = 0L
    private var transitionQuietUntil = 0L
    private var receiversRegistered = false

    private sealed interface Signal {
        data class Window(val packageName: String) : Signal
        data object StateChanged : Signal
        data object Tick : Signal
        data object ScreenOff : Signal
        data object Wake : Signal
        data class Dismiss(val generation: Long) : Signal
        data class Cancel(val generation: Long) : Signal
        data class Pause(val generation: Long) : Signal
        data object RefreshCatalog : Signal
        data class Start(val key: String, val generation: Long, val intention: String, val minutes: Int) : Signal
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    observedPackage = null
                    overlay.dismiss()
                    signals.trySend(Signal.ScreenOff)
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> signals.trySend(Signal.Wake)
            }
        }
    }
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { signals.trySend(Signal.RefreshCatalog) }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = OverlayController(this)
        power = getSystemService(PowerManager::class.java)
        keyguard = getSystemService(KeyguardManager::class.java)
        ProtectionStatus.connected.value = true
        ProtectionStatus.issue.value = null
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(this, packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }, ContextCompat.RECEIVER_EXPORTED)
        receiversRegistered = true
        scope.launch {
            for (signal in signals) {
                try {
                    process(signal)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Never log event content, intentions, or a private package history.
                    ProtectionStatus.issue.value = "Prompts couldn't be updated. Open Accountability Check to review protection."
                    overlay.dismiss()
                    deadlineJob?.cancel()
                }
            }
        }
        signals.trySend(Signal.RefreshCatalog)
        scope.launch { repository.state.collect { signals.send(Signal.StateChanged) } }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::overlay.isInitialized || event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            // A cancelled recent-apps gesture can restore the old app without a new
            // TYPE_WINDOW_STATE_CHANGED event. Query the active window after that transition.
            val now = SystemClock.elapsedRealtime()
            if (!overlay.isShowing && transitionCheckJob?.isActive == true) {
                transitionQuietUntil = now + 550
            }
            if (!overlay.isShowing && now - lastWindowProbeAt >= 100) {
                lastWindowProbeAt = now
                signals.trySend(Signal.Tick)
            }
            return
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: run {
            observedPackage = null
            signals.trySend(Signal.Window(""))
            return
        }
        // Keyboard chrome can emit both SoftInputWindow and non-fullscreen child-window events.
        // Neither represents an app switch; a fullscreen keyboard settings Activity still does.
        if (pkg in keyboards && (event.className?.toString() == "android.inputmethodservice.SoftInputWindow" || !event.isFullScreen)) return
        if (pkg == packageName && overlay.isShowing && event.className?.toString() != "$packageName.MainActivity") return
        observedPackage = pkg
        signals.trySend(Signal.Window(pkg))
    }

    private suspend fun process(signal: Signal) {
        when (signal) {
            Signal.RefreshCatalog -> {
                val catalog = AppCatalog(this)
                withContext(Dispatchers.IO) {
                    val apps = catalog.launchableApps()
                    val homes = catalog.launcherPackages
                    val imes = catalog.keyboardPackages
                    val critical = catalog.excludedPackages()
                    CatalogSnapshot(apps.associate { it.packageName to it.label }, homes, imes, critical)
                }.also {
                    appLabels = it.labels; launchers = it.launchers; keyboards = it.keyboards; excluded = it.excluded
                    catalogReady = true
                }
                refreshForegroundFromActiveWindow()
            }
            is Signal.Window -> {
                // Ignore queued transitions already superseded by another real window callback.
                if (observedPackage.orEmpty() != signal.packageName) return
                if (transitionCheckJob?.isActive == true) {
                    transitionQuietUntil = SystemClock.elapsedRealtime() + 550
                }
                val wasAppPrompt = repository.state.value.settings.mode == ProtectionMode.APPS &&
                    overlay.kind == OverlayController.Kind.PROMPT
                foreground = signal.packageName.takeIf { it.isNotBlank() }
                if (!isUsableSurface() && !(foreground in launchers && overlay.kind == OverlayController.Kind.REMINDER)) {
                    overlay.dismiss()
                    val settings = repository.state.value.settings
                    if (wasAppPrompt || (settings.mode == ProtectionMode.APPS &&
                            settings.protectedPackages.isNotEmpty() && foreground in launchers)) {
                        scheduleTransitionChecks()
                    }
                }
            }
            Signal.ScreenOff -> {
                foreground = null
                wakeHandled = false
                phoneGateDue = true
                deferPhoneGateOnLauncher = false
                wakeRefreshJob?.cancel()
                transitionCheckJob?.cancel()
                val settings = repository.state.value.settings
                if (settings.mode == ProtectionMode.PHONE && settings.endPhoneSessionOnLock) {
                    repository.endPhoneSessionOnLock()
                }
            }
            Signal.Wake -> {
                if (isUnlocked() && !wakeHandled) { wakeHandled = true; phoneGateDue = true }
                refreshForegroundFromActiveWindow()
                // A wake broadcast can precede the resumed app's window becoming active.
                wakeRefreshJob?.cancel()
                wakeRefreshJob = scope.launch { delay(500); signals.send(Signal.Tick) }
            }
            is Signal.Dismiss -> {
                if (signal.generation != overlay.generation) return
                val target = foreground.takeIf { overlay.kind == OverlayController.Kind.REMINDER }
                overlay.dismiss()
                // If Android did not complete the first Home action, the explicit dismissal retries it.
                if (target != null) exitToHome(target)
            }
            is Signal.Cancel -> {
                if (signal.generation != overlay.generation) return
                val target = foreground
                overlay.dismiss()
                if (repository.state.value.settings.mode == ProtectionMode.PHONE) {
                    phoneGateDue = true
                    deferPhoneGateOnLauncher = true
                } else {
                    phoneGateDue = false
                }
                exitToHome(target)
                return
            }
            is Signal.Pause -> {
                if (signal.generation != overlay.generation) return
                overlay.dismiss()
                repository.setProtectionEnabled(false)
                return
            }
            is Signal.Start -> {
                if (signal.generation != overlay.generation || overlay.kind != OverlayController.Kind.PROMPT || overlay.key != signal.key || !isUsableSurface()) return
                val state = repository.state.value
                val phone = state.settings.mode == ProtectionMode.PHONE
                val target = if (phone) null else foreground
                if (!phone && target != signal.key) { overlay.dismiss(); return }
                try {
                    repository.startSession(target, if (phone) "Your phone" else appLabels[target] ?: "App", signal.intention, signal.minutes)
                    phoneGateDue = false
                    deferPhoneGateOnLauncher = false
                    overlay.dismiss()
                    transitionCheckJob?.cancel()
                    ProtectionStatus.issue.value = null
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { overlay.showError("Couldn't save your session. Check protection in the app and try again.") }
            }
            Signal.StateChanged -> Unit
            Signal.Tick -> refreshForegroundFromActiveWindow()
        }
        val state = repository.state.value
        if (!state.loaded || state.error != null || !state.settings.consentAccepted || !state.settings.protectionEnabled) {
            overlay.dismiss()
            deadlineJob?.cancel()
            transitionCheckJob?.cancel()
            priorSettings = state.settings
            return
        }
        if (priorSettings?.mode != state.settings.mode) {
            overlay.dismiss()
            transitionCheckJob?.cancel()
            phoneGateDue = state.settings.mode == ProtectionMode.PHONE
            deferPhoneGateOnLauncher = false
        }
        priorSettings = state.settings
        val expired = repository.reconcile()
        if (expired.isNotEmpty()) handleExpiry(expired)
        evaluateGate()
        scheduleDeadline()
    }

    private fun handleExpiry(expired: List<SessionRecord>) {
        if (!isUnlocked() || !isUsableSurface()) return
        val mode = repository.state.value.settings.mode
        val target = foreground
        val current = expired.firstOrNull { if (mode == ProtectionMode.PHONE) it.packageName == null else it.packageName == target } ?: return
        var reminderGeneration = 0L
        overlay.showReminder(current.id, current.intention, current.durationMinutes,
            repository.state.value.settings.themeMode) { signals.trySend(Signal.Dismiss(reminderGeneration)) }
        reminderGeneration = overlay.generation
        phoneGateDue = false
        exitToHome(target)
    }

    private fun evaluateGate() {
        // A reminder owns the screen until the user dismisses it. A real app switch to
        // Settings/calls and screen-off still dismiss it in their respective signal handlers.
        if (overlay.kind == OverlayController.Kind.REMINDER) return
        if (!catalogReady || !isUnlocked() || !isUsableSurface()) { overlay.dismiss(); return }
        if (SystemClock.elapsedRealtime() < exitingUntil) return
        val state = repository.state.value
        val target = foreground ?: return
        val phone = state.settings.mode == ProtectionMode.PHONE
        if (phone && deferPhoneGateOnLauncher) {
            if (target in launchers) { overlay.dismiss(); return }
            // Go Home grants access to Home, not a free pass to the next app.
            deferPhoneGateOnLauncher = false
        }
        val selected = target in state.settings.protectedPackages && target in appLabels
        val active = state.sessions.any {
            it.status == SessionStatus.ACTIVE && it.deadlineElapsed > SystemClock.elapsedRealtime() &&
                if (phone) it.packageName == null else it.packageName == target
        }
        if (active || (!selected && !(phone && phoneGateDue))) {
            overlay.dismiss()
            return
        }
        // Quickstep can report the selected app between launcher frames. Wait for a
        // quiet window interval so one final form appears instead of several flashes.
        if (transitionCheckJob?.isActive == true && SystemClock.elapsedRealtime() < transitionQuietUntil) return
        transitionCheckJob?.cancel()
        val key = if (phone) "phone" else target
        var promptGeneration = 0L
        overlay.showPrompt(key, appLabels[target] ?: "Your phone", phone, state.settings.themeMode,
            onStart = { intention, minutes -> signals.trySend(Signal.Start(key, promptGeneration, intention, minutes)) },
            onCancel = { signals.trySend(Signal.Cancel(promptGeneration)) },
            onPause = { signals.trySend(Signal.Pause(promptGeneration)) },
        )
        promptGeneration = overlay.generation
    }

    private fun isUnlocked(): Boolean = power.isInteractive && !keyguard.isKeyguardLocked

    /** Window-state callbacks can be absent on wake or arrive out of visual order. */
    private fun refreshForegroundFromActiveWindow() {
        if (overlay.isShowing || !isUnlocked()) return
        val activePackage = rootInActiveWindow?.packageName?.toString() ?: return
        // The keyboard is a window over the app being timed, not a new destination.
        if (activePackage == packageName || activePackage in keyboards) return
        observedPackage = activePackage
        foreground = activePackage
    }

    private fun isUsableSurface(): Boolean {
        val target = foreground ?: return false
        if (target != observedPackage || !isUnlocked()) return false
        if (target in launchers) return repository.state.value.settings.mode == ProtectionMode.PHONE ||
            overlay.kind == OverlayController.Kind.REMINDER
        return target in appLabels && target !in excluded
    }

    private fun exitToHome(target: String?) {
        if (target == null || target in launchers || target != observedPackage || !isUnlocked() || target !in appLabels || target in excluded) {
            return
        }
        exitingUntil = SystemClock.elapsedRealtime() + 400
        if (!performGlobalAction(GLOBAL_ACTION_HOME)) {
            ProtectionStatus.issue.value = "Android couldn't return Home. Use the Home gesture to leave the app."
        }
        scope.launch { delay(450); signals.send(Signal.Tick) }
    }

    private fun scheduleDeadline() {
        deadlineJob?.cancel()
        val deadline = repository.state.value.sessions.filter { it.status == SessionStatus.ACTIVE }
            .minOfOrNull { it.deadlineElapsed } ?: return
        deadlineJob = scope.launch {
            delay((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1))
            signals.send(Signal.Tick)
        }
    }

    private fun scheduleTransitionChecks() {
        transitionCheckJob?.cancel()
        transitionQuietUntil = SystemClock.elapsedRealtime() + 550
        transitionCheckJob = scope.launch {
            // Keep sampling while Quickstep settles. The event stream usually covers this,
            // but selecting or reversing a recent app may omit its app-window callback.
            repeat(20) {
                delay(250)
                signals.send(Signal.Tick)
            }
            // Do not leave a selected app ungated if system window events keep arriving
            // throughout the settling period and then stop just before its final check.
            transitionCheckJob = null
            signals.send(Signal.Tick)
        }
    }

    override fun onInterrupt() {
        if (::overlay.isInitialized) overlay.dismiss()
        observedPackage = null; foreground = null
        transitionCheckJob?.cancel()
    }

    override fun onDestroy() {
        ProtectionStatus.connected.value = false
        if (::overlay.isInitialized) overlay.dismiss()
        if (receiversRegistered) {
            unregisterReceiver(screenReceiver); unregisterReceiver(packageReceiver)
        }
        signals.close()
        scope.cancel()
        super.onDestroy()
    }

    private data class CatalogSnapshot(val labels: Map<String, String>, val launchers: Set<String>, val keyboards: Set<String>, val excluded: Set<String>)
}
