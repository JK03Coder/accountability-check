package nz.co.accountabilitycheck

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import nz.co.accountabilitycheck.data.AppRepository
import nz.co.accountabilitycheck.domain.AppSettings
import nz.co.accountabilitycheck.domain.ProtectionMode
import nz.co.accountabilitycheck.domain.SessionRecord
import nz.co.accountabilitycheck.domain.SessionStatus
import nz.co.accountabilitycheck.service.ProtectionStatus
import nz.co.accountabilitycheck.platform.AppCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cross-app smoke coverage for a dedicated test device. No production timing or permission bypasses.
 * Install :test-target:installDebug before running. These tests use synthetic intentions and leave
 * their completed records in the local history. Real-device/OEM acceptance remains a manual check.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilitySmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<AccountabilityApp>()
    private val repository: AppRepository get() = app.repository
    private lateinit var device: UiDevice
    private lateinit var automation: UiAutomation
    private lateinit var originalSettings: AppSettings
    private var originalServices: String? = null
    private var originalAccessibilityEnabled: String? = null
    private var originalAutomationFlags = 0
    private var originalIdleTimeout = 0L
    private var capturedSettings = false

    @Before
    fun prepareDedicatedTestDevice() {
        val configurator = Configurator.getInstance()
        originalAutomationFlags = configurator.uiAutomationFlags
        originalIdleTimeout = configurator.waitForIdleTimeout
        configurator.setUiAutomationFlags(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        configurator.setWaitForIdleTimeout(500)
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        device = UiDevice.getInstance(instrumentation)
        device.wakeUp()
        device.pressHome()

        originalServices = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        originalAccessibilityEnabled = Settings.Secure.getString(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        originalSettings = runBlocking {
            withTimeout(10_000) { repository.state.first { it.loaded }.settings }
        }
        capturedSettings = true
        runBlocking {
            repository.setProtectionEnabled(false)
            repository.acceptDisclosure()
            repository.setMode(ProtectionMode.APPS)
            repository.setEndPhoneSessionOnLock(false)
            repository.setProtectedPackages(setOf(TARGET_PACKAGE))
            repository.setProtectionEnabled(true)
        }

        val enabled = originalServices.orEmpty().split(':').filter { it.isNotBlank() }.toMutableSet()
        enabled += serviceComponent.flattenToString()
        withSecureSettingsPermission {
            assertTrue(Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, enabled.joinToString(":")))
            assertTrue(Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1))
        }
        val manager = app.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val start = SystemClock.elapsedRealtime()
        while (manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).none {
                val info = it.resolveInfo.serviceInfo
                ComponentName(info.packageName, info.name) == serviceComponent
            } && SystemClock.elapsedRealtime() - start < 10_000
        ) {
            SystemClock.sleep(100)
        }
        assertTrue("Accountability Accessibility service did not become enabled", manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                val info = it.resolveInfo.serviceInfo
                ComponentName(info.packageName, info.name) == serviceComponent
            })
        runBlocking { withTimeout(10_000) { ProtectionStatus.connection.first { it } } }
    }

    @After
    fun restoreTestDeviceSettings() {
        try {
            if (capturedSettings) {
                runBlocking {
                    repository.setProtectionEnabled(false)
                    repository.setProtectedPackages(originalSettings.protectedPackages)
                    repository.setMode(originalSettings.mode)
                    repository.setEndPhoneSessionOnLock(originalSettings.endPhoneSessionOnLock)
                    repository.setProtectionEnabled(originalSettings.protectionEnabled)
                }
                withSecureSettingsPermission {
                    Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, originalServices)
                    Settings.Secure.putString(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, originalAccessibilityEnabled)
                }
                device.pressHome()
            }
        } finally {
            Configurator.getInstance().setUiAutomationFlags(originalAutomationFlags)
            Configurator.getInstance().setWaitForIdleTimeout(originalIdleTimeout)
        }
    }

    @Test
    fun gatePreservesDestinationAndDeadline_thenExpiresAndGatesReentry() {
        launchTargetAndAwaitGate()
        gateElement("start_session").click()
        assertTrue("Blank intention must remain gated", isGateVisible())

        gateElement("intention_input").text = "Visit the synthetic story"
        gateElement("duration_input").text = "0"
        gateElement("start_session").click()
        assertTrue("Zero minutes must remain gated", isGateVisible())

        gateElement("duration_input").text = "1"
        gateElement("start_session").click()
        assertTrue("Accepted gate should disappear", awaitGateVisibility(false))
        assertTrue("Deep-link screen must survive the gate", device.wait(Until.hasObject(By.text(DESTINATION)), 5_000))
        val original = activeTargetSession()

        device.findObject(By.text("Open second screen")).click()
        assertTrue("Same-package navigation should work", device.wait(Until.hasObject(By.text("Second target screen")), 5_000))
        assertFalse("Same-package navigation must not prompt again", isGateVisible())
        device.findObject(By.text("Return to first screen")).click()
        assertTrue(device.wait(Until.hasObject(By.text(DESTINATION)), 5_000))

        device.pressHome()
        launchTarget()
        assertTrue("Returning early should reveal the target", device.wait(Until.hasObject(By.text(DESTINATION)), 5_000))
        assertFalse("Returning early must reuse the active session", isGateVisible())
        assertEquals(original.id, activeTargetSession().id)
        assertEquals(original.deadlineElapsed, activeTargetSession().deadlineElapsed)

        // Keep the target completely static: expiry must be driven by its deadline, not new events.
        val launcher = device.launcherPackageName
        assertNotNull("Test device must have a Home launcher", launcher)
        val timeout = (original.deadlineElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 10_000
        if (!device.wait(Until.hasObject(By.text("Time to wrap up")), timeout)) {
            failWithDiagnostics("Static foreground target should show its expiry reminder; session=${repository.state.value.sessions.firstOrNull { it.id == original.id }}")
        }
        assertEquals(SessionStatus.EXPIRED, repository.state.value.sessions.first { it.id == original.id }.status)
        SystemClock.sleep(4_000)
        if (!device.hasObject(By.text("Time to wrap up"))) failWithDiagnostics("Reminder should stay until dismissed")
        assertFalse("A new intention must not replace the reminder", device.hasObject(By.text("What are you here to do?")))
        device.findObject(By.text("Dismiss")).click()
        assertTrue("Dismissing the reminder should reveal Home", device.wait(Until.hasObject(By.pkg(launcher)), 5_000))
        launchTargetAndAwaitGate()
        assertFalse("Expired re-entry must not reuse the previous intention", gateElement("intention_input").text.contains("Visit the synthetic story"))
    }

    @Test
    fun cancelGrantsNoSession_andSettingsEscapesGate() {
        launchTargetAndAwaitGate()
        gateElement("go_home").click()
        assertTrue("Cancel should return Home", device.wait(Until.hasObject(By.pkg(device.launcherPackageName)), 5_000))
        assertFalse(repository.state.value.sessions.any { it.packageName == TARGET_PACKAGE && it.status == SessionStatus.ACTIVE })

        launchTargetAndAwaitGate()
        device.executeShellCommand("am start -W -a android.settings.SETTINGS")
        assertTrue("Settings must escape the blocking form", awaitGateVisibility(false))
        assertFalse(repository.state.value.sessions.any { it.packageName == TARGET_PACKAGE && it.status == SessionStatus.ACTIVE })
    }

    @Test
    fun phoneModePromptsOnceAfterWakeWithoutCoveringTheLockScreen() {
        runBlocking { repository.setMode(ProtectionMode.PHONE) }
        device.pressHome()
        device.sleep()
        assertTrue("Phone form must leave Android's lock screen unobstructed", awaitGateVisibility(false))
        device.wakeUp()
        device.pressHome()
        if (!awaitGateVisibility(true, 10_000)) failWithDiagnostics("Expected phone-purpose gate after wake")
        assertEquals("What are you using your phone for?", gateElement("gate_title").text)
        assertEquals(1, device.findObjects(By.res(APP_PACKAGE, "gate_title")).size)
    }

    @Test
    fun phoneSessionKeepsItsDeadlineAcrossLockAndWake() {
        runBlocking { repository.setMode(ProtectionMode.PHONE) }
        device.pressHome()
        if (!awaitGateVisibility(true, 10_000)) failWithDiagnostics("Expected phone-purpose gate")
        gateElement("intention_input").text = "Check the test story"
        gateElement("duration_input").text = "2"
        gateElement("start_session").click()
        assertTrue("Starting the phone session should dismiss the gate", awaitGateVisibility(false))
        val original = activePhoneSession()

        device.sleep()
        SystemClock.sleep(500)
        device.wakeUp()
        device.pressHome()
        SystemClock.sleep(1_000)
        assertFalse("A brief lock must not request a new intention", isGateVisible())
        assertEquals(original.id, activePhoneSession().id)
        assertEquals(original.deadlineElapsed, activePhoneSession().deadlineElapsed)
        launchTarget()
        assertTrue("Fixture should be usable during the same phone session",
            device.wait(Until.hasObject(By.text(DESTINATION)), 5_000))
        assertFalse("Opening an app must not restart an active phone session", isGateVisible())
    }

    @Test
    fun backingOutOfPhoneModeAsksAgainAtTheNextUnselectedApp() {
        runBlocking { repository.setMode(ProtectionMode.PHONE) }
        device.pressHome()
        if (!awaitGateVisibility(true, 10_000)) failWithDiagnostics("Expected phone-purpose gate")
        assertFalse("Phone mode should not offer a Go Home button", device.hasObject(By.res(APP_PACKAGE, "go_home")))
        device.pressBack()
        assertTrue("Back should reveal the launcher", device.wait(Until.hasObject(By.pkg(device.launcherPackageName)), 5_000))
        SystemClock.sleep(700)
        assertFalse("The launcher should remain usable", isGateVisible())

        runBlocking { repository.setProtectedPackages(emptySet()) }
        launchTarget()
        if (!device.wait(Until.hasObject(By.text("What are you using your phone for?")), 8_000)) {
            failWithDiagnostics("The next eligible app should show a phone-purpose gate even when unselected")
        }
        assertEquals(1, device.findObjects(By.res(APP_PACKAGE, "gate_title")).size)
    }

    @Test
    fun optionalEndOnLockRequestsAnotherPhoneIntention() {
        runBlocking {
            repository.setEndPhoneSessionOnLock(true)
            repository.setMode(ProtectionMode.PHONE)
        }
        device.pressHome()
        if (!awaitGateVisibility(true, 10_000)) failWithDiagnostics("Expected phone-purpose gate")
        gateElement("intention_input").text = "Check the test story"
        gateElement("duration_input").text = "2"
        gateElement("start_session").click()
        assertTrue(awaitGateVisibility(false))
        val original = activePhoneSession()

        device.sleep()
        assertTrue("Locking should interrupt the prior intention", device.wait(Until.gone(
            By.text("What are you using your phone for?")), 5_000))
        device.wakeUp()
        device.pressHome()
        if (!device.wait(Until.hasObject(By.text("What are you using your phone for?")), 10_000)) {
            failWithDiagnostics("The optional end-on-lock setting should prompt after waking")
        }
        assertEquals(SessionStatus.INTERRUPTED, repository.state.value.sessions.first { it.id == original.id }.status)
    }

    @Test
    fun selectedAppGateReturnsAfterWakingIntoTheSameScreen() {
        launchTargetAndAwaitGate()
        device.sleep()
        assertTrue("Gate should leave authentication unobstructed", awaitGateVisibility(false))
        device.wakeUp()
        val keyguard = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (keyguard.isKeyguardLocked) {
            device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5,
                device.displayWidth / 2, device.displayHeight / 5, 20)
        }
        if (!device.wait(Until.hasObject(By.text("What are you here to do?")), 8_000)) {
            failWithDiagnostics("Waking directly into the protected app should restore its gate")
        }
        assertEquals(1, device.findObjects(By.res(APP_PACKAGE, "gate_title")).size)
    }

    @Test
    fun reconnectingServiceGatesTheAlreadyOpenTarget() {
        launchTargetAndAwaitGate()
        val enabledWithoutThisService = Settings.Secure.getString(
            app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty().split(':').filter { it.isNotBlank() && it != serviceComponent.flattenToString() }
        withSecureSettingsPermission {
            assertTrue(Settings.Secure.putString(app.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, enabledWithoutThisService.joinToString(":")))
            assertTrue(Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED,
                if (enabledWithoutThisService.isEmpty()) 0 else 1))
        }
        assertTrue("Disabling the service must remove its overlay", awaitGateVisibility(false))
        withSecureSettingsPermission {
            assertTrue(Settings.Secure.putString(app.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                (enabledWithoutThisService + serviceComponent.flattenToString()).joinToString(":")))
            assertTrue(Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1))
        }
        if (!device.wait(Until.hasObject(By.text("What are you here to do?")), 10_000)) {
            failWithDiagnostics("Reconnecting the service should gate the already-open protected app")
        }
        assertEquals(1, device.findObjects(By.res(APP_PACKAGE, "gate_title")).size)
    }

    @Test
    fun expiryInSettingsLeavesSettingsOpenAndGatesReentry() {
        launchTargetAndAwaitGate()
        gateElement("intention_input").text = "Check the test story"
        gateElement("duration_input").text = "1"
        gateElement("start_session").click()
        assertTrue("Target should open for an accepted session", awaitGateVisibility(false))
        val session = activeTargetSession()
        device.executeShellCommand("am start -W -a android.settings.SETTINGS")
        assertTrue("Settings should be foreground", device.wait(Until.hasObject(By.pkg("com.android.settings")), 5_000))
        SystemClock.sleep((session.deadlineElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 1_500)
        assertEquals(SessionStatus.EXPIRED, repository.state.value.sessions.first { it.id == session.id }.status)
        assertTrue("Expiry must not send Settings Home", device.hasObject(By.pkg("com.android.settings")))
        launchTargetAndAwaitGate()
    }

    @Test
    fun hidingKeyboardPreservesThePartiallyCompletedGate() {
        launchTargetAndAwaitGate()
        gateElement("intention_input").click()
        gateElement("intention_input").text = "A synthetic intention"
        assertTrue("Test needs a visible keyboard", device.wait(Until.hasObject(By.pkg("com.google.android.inputmethod.latin")), 3_000))
        device.pressBack()
        assertTrue("Keyboard dismissal must keep the gate visible", isGateVisible())
        assertEquals("A synthetic intention", gateElement("intention_input").text)
        assertEquals(1, device.findObjects(By.res(APP_PACKAGE, "gate_title")).size)
    }

    @Test
    fun reversingTheRecentAppsGestureCannotLeaveAnUngatedTarget() {
        launchTargetAndAwaitGate()
        val x = device.displayWidth / 2
        val bottom = device.displayHeight - 4
        val midpoint = device.displayHeight * 2 / 5
        repeat(3) {
            val downTime = SystemClock.uptimeMillis()
            fun send(action: Int, y: Int) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    x.toFloat(), y.toFloat(), 0)
                try { automation.injectInputEvent(event, true) } finally { event.recycle() }
            }
            send(MotionEvent.ACTION_DOWN, bottom)
            for (step in 1..40) {
                SystemClock.sleep(12)
                send(MotionEvent.ACTION_MOVE, bottom + (midpoint - bottom) * step / 40)
            }
            SystemClock.sleep(500)
            val leftGateDuringGesture = !isGateVisible()
            for (step in 1..40) {
                SystemClock.sleep(12)
                send(MotionEvent.ACTION_MOVE, midpoint + (bottom - midpoint) * step / 40)
            }
            send(MotionEvent.ACTION_UP, bottom)
            assertTrue("Test gesture must put the gate into recent apps before reversing", leftGateDuringGesture)
            if (!device.wait(Until.hasObject(By.text("What are you here to do?")), 3_000)) {
                failWithDiagnostics("Returning from a partial recent-apps gesture left the target ungated")
            }
            val draft = "Return from recent apps $it"
            gateElement("intention_input").text = draft
            SystemClock.sleep(1_000)
            assertEquals("The restored form should not flash and erase its draft", draft,
                gateElement("intention_input").text)
        }
    }

    private fun launchTarget() {
        val result = device.executeShellCommand(
            "am start -W -a android.intent.action.VIEW -d $DESTINATION -n $TARGET_PACKAGE/.MainActivity",
        )
        assertFalse("Install :test-target:installDebug first. Launch result: $result", result.contains("Error:"))
    }

    private fun launchTargetAndAwaitGate() {
        launchTarget()
        // An expiry reminder may remain briefly over Home; wait for the new form, not any overlay.
        if (!device.wait(Until.hasObject(By.text("What are you here to do?")), 8_000)) {
            failWithDiagnostics("Expected a fresh intention gate over the fixture")
        }
        assertEquals("What are you here to do?", gateElement("gate_title").text)
        assertEquals(1, device.findObjects(By.res(APP_PACKAGE, "gate_title")).size)
    }

    private fun activeTargetSession(): SessionRecord = runBlocking {
        withTimeout(5_000) {
            repository.state.first { state ->
                state.sessions.any { it.packageName == TARGET_PACKAGE && it.status == SessionStatus.ACTIVE }
            }.sessions.first { it.packageName == TARGET_PACKAGE && it.status == SessionStatus.ACTIVE }
        }
    }

    private fun activePhoneSession(): SessionRecord = runBlocking {
        withTimeout(5_000) {
            repository.state.first { state ->
                state.sessions.any { it.packageName == null && it.status == SessionStatus.ACTIVE }
            }.sessions.first { it.packageName == null && it.status == SessionStatus.ACTIVE }
        }
    }

    private fun gateElement(id: String): UiObject2 {
        val selector = By.res(APP_PACKAGE, id)
        if (!device.hasObject(selector)) {
            try {
                val form = UiScrollable(UiSelector().packageName(APP_PACKAGE).scrollable(true))
                form.setAsVerticalList()
                form.setMaxSearchSwipes(10)
                form.scrollToBeginning(10)
                if (!form.scrollIntoView(UiSelector().resourceId("$APP_PACKAGE:id/$id"))) {
                    failWithDiagnostics("Gate control $id should be reachable by scrolling")
                }
            } catch (failure: Exception) {
                failWithDiagnostics("Could not locate gate control $id: ${failure.message}")
            }
        }
        return device.findObject(selector) ?: failWithDiagnostics("Gate control $id is absent")
    }

    private fun isGateVisible(): Boolean = GATE_CONTROL_IDS.any {
        device.hasObject(By.res(APP_PACKAGE, it))
    } || device.hasObject(By.text("What are you here to do?")) ||
        device.hasObject(By.text("Time to wrap up"))

    private fun awaitGateVisibility(visible: Boolean, timeout: Long = 5_000): Boolean {
        val until = SystemClock.elapsedRealtime() + timeout
        do {
            if (isGateVisible() == visible) return true
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < until)
        return isGateVisible() == visible
    }

    private fun failWithDiagnostics(message: String): Nothing {
        val directory = File(app.getExternalFilesDir(null), "smoke-failures").apply { mkdirs() }
        val prefix = "failure-${SystemClock.elapsedRealtime()}"
        runCatching { device.takeScreenshot(File(directory, "$prefix.png")) }
        runCatching { device.dumpWindowHierarchy(File(directory, "$prefix.xml")) }
        val details = "$message; serviceConnected=${ProtectionStatus.connection.value}; " +
            "serviceIssue=${ProtectionStatus.lastIssue.value}; " +
            "fixtureVisible=${AppCatalog(app).launchableApps().any { it.packageName == TARGET_PACKAGE }}; " +
            "settings=${repository.state.value.settings}; diagnostics=${directory.absolutePath}/$prefix"
        runCatching { File(directory, "$prefix.txt").writeText(details) }
        throw AssertionError(details)
    }

    private inline fun withSecureSettingsPermission(block: () -> Unit) {
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
        try { block() } finally { automation.dropShellPermissionIdentity() }
    }

    private val serviceComponent get() = ComponentName(APP_PACKAGE, "$APP_PACKAGE.service.AccountabilityAccessibilityService")

    private companion object {
        const val APP_PACKAGE = "nz.co.accountabilitycheck"
        const val TARGET_PACKAGE = "$APP_PACKAGE.testtarget"
        const val DESTINATION = "accountability-test://destination/story"
        val GATE_CONTROL_IDS = listOf("gate_title", "intention_input", "duration_input", "start_session", "go_home", "pause_protection")
    }
}
