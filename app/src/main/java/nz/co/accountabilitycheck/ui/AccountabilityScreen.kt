package nz.co.accountabilitycheck.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.co.accountabilitycheck.data.AppRepository
import nz.co.accountabilitycheck.domain.AppState
import nz.co.accountabilitycheck.domain.AppSettings
import nz.co.accountabilitycheck.domain.ProtectionMode
import nz.co.accountabilitycheck.domain.SessionRecord
import nz.co.accountabilitycheck.domain.SessionStatus
import nz.co.accountabilitycheck.domain.ThemeMode
import nz.co.accountabilitycheck.platform.AppCatalog
import nz.co.accountabilitycheck.platform.DeviceApp
import nz.co.accountabilitycheck.service.ProtectionStatus

@Composable
fun AccountabilityScreen(repository: AppRepository) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val state by repository.state.collectAsStateWithLifecycle()
    val connected by ProtectionStatus.connection.collectAsStateWithLifecycle()
    val serviceIssue by ProtectionStatus.lastIssue.collectAsStateWithLifecycle()
    var systemEnabled by remember { mutableStateOf(ProtectionStatus.isEnabled(context)) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var screen by rememberSaveable { mutableStateOf("today") }
    var disclosureVisible by rememberSaveable { mutableStateOf(false) }
    var clearVisible by rememberSaveable { mutableStateOf(false) }
    var writing by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<DeviceApp>>(emptyList()) }
    var loadingApps by remember { mutableStateOf(true) }
    var appLoadError by remember { mutableStateOf<String?>(null) }
    var elapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    fun mutate(action: suspend () -> Unit) {
        if (writing) return
        scope.launch {
            writing = true
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                snackbar.showSnackbar(exception.message ?: "That change couldn't be saved. Please try again.")
            } finally {
                writing = false
            }
        }
    }

    fun openAccessibilitySettings() {
        try {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            scope.launch { snackbar.showSnackbar("Open Android Settings, then Accessibility, then Accountability Check.") }
        }
    }

    fun setUpAccessibility() {
        if (state.settings.consentAccepted) openAccessibilitySettings() else disclosureVisible = true
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                systemEnabled = ProtectionStatus.isEnabled(context)
                refreshKey++
                scope.launch {
                    try {
                        repository.reconcile()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        snackbar.showSnackbar("Couldn't refresh your sessions. Reopen the app to try again.")
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(refreshKey) {
        loadingApps = true
        appLoadError = null
        try {
            apps = withContext(Dispatchers.IO) { AppCatalog(context.applicationContext).launchableApps() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            appLoadError = "We couldn't load your apps. Leave this screen and return to try again."
        } finally {
            loadingApps = false
        }
    }

    val activeSessions = state.sessions.filter { it.status == SessionStatus.ACTIVE }
    LaunchedEffect(activeSessions, screen, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (activeSessions.isNotEmpty() && screen == "today") {
                elapsed = SystemClock.elapsedRealtime()
                if (activeSessions.any { it.remainingMillis(elapsed) == 0L }) {
                    try {
                        repository.reconcile()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        snackbar.showSnackbar("Couldn't update your sessions. Check available storage and reopen the app.")
                        break
                    }
                }
                delay(1_000)
            }
        }
    }
    BackHandler(enabled = screen != "today") { screen = "today" }

    Scaffold(
        containerColor = Cream,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = Cream, tonalElevation = 0.dp) {
                listOf("today" to "Today", "apps" to "Your apps", "history" to "History", "settings" to "Settings").forEach { (destination, label) ->
                    NavigationBarItem(
                        selected = screen == destination,
                        onClick = { screen = destination },
                        icon = { NavigationGlyph(destination) },
                        label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = PaleTeal),
                    )
                }
            }
        },
    ) { padding ->
        if (!state.loaded && state.error != null) {
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                PageTitle("Your data needs attention", "Prompts are paused while we can't read your saved settings.")
                NoticeCard("Saved data unavailable", state.error.orEmpty())
                Text("Check your phone's available storage, then try again. Your saved data won't be replaced.", color = MutedInk)
                Button(onClick = { mutate { repository.reconcile() } }, enabled = !writing) { Text("Try again") }
                if (state.settings.consentAccepted) {
                    OutlinedButton(onClick = ::openAccessibilitySettings) { Text("Open Accessibility settings") }
                }
            }
        } else if (!state.loaded) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 3.dp)
                    Text("Making room…", color = MutedInk)
                }
            }
        } else {
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (screen) {
                    "apps" -> AppsScreen(
                        apps = apps,
                        selected = state.settings.protectedPackages,
                        loading = loadingApps,
                        loadError = appLoadError,
                        writing = writing,
                        onToggle = { packageName, selected ->
                            mutate {
                                repository.setProtectedPackages(
                                    if (selected) state.settings.protectedPackages + packageName
                                    else state.settings.protectedPackages - packageName,
                                )
                            }
                        },
                    )
                    "history" -> HistoryScreen(
                        sessions = state.sessions.filter { it.status != SessionStatus.ACTIVE },
                        onClear = { clearVisible = true },
                        writing = writing,
                    )
                    "settings" -> SettingsScreen(
                        settings = state.settings,
                        writing = writing,
                        onThemeMode = { mode -> mutate { repository.setThemeMode(mode) } },
                        onMode = { mode -> mutate { repository.setMode(mode) } },
                        onEndPhoneSessionOnLock = { enabled -> mutate { repository.setEndPhoneSessionOnLock(enabled) } },
                        onSetup = ::setUpAccessibility,
                        onDisclosure = { disclosureVisible = true },
                    )
                    else -> TodayScreen(
                        state = state,
                        systemEnabled = systemEnabled,
                        connected = connected,
                        serviceIssue = serviceIssue,
                        elapsed = elapsed,
                        writing = writing,
                        onSetup = ::setUpAccessibility,
                        onApps = { screen = "apps" },
                        onPauseToggle = { enabled ->
                            if (enabled && !state.settings.consentAccepted) disclosureVisible = true
                            else mutate { repository.setProtectionEnabled(enabled) }
                        },
                    )
                }
            }
        }
    }

    if (disclosureVisible) {
        DisclosureDialog(
            writing = writing,
            onDismiss = { if (!writing) disclosureVisible = false },
            onAccept = {
                mutate {
                    repository.acceptDisclosure()
                    repository.setProtectionEnabled(true)
                    disclosureVisible = false
                    openAccessibilitySettings()
                }
            },
        )
    }

    if (clearVisible) {
        AlertDialog(
            onDismissRequest = { if (!writing) clearVisible = false },
            title = { Text("Clear your history?") },
            text = { Text("This removes completed intentions and sessions from this device. Active timers keep running. This can't be undone.") },
            confirmButton = {
                TextButton(enabled = !writing, onClick = { mutate { repository.clearHistory(); clearVisible = false } }) {
                    Text("Clear history")
                }
            },
            dismissButton = { TextButton(enabled = !writing, onClick = { clearVisible = false }) { Text("Keep history") } },
        )
    }
}

@Composable
private fun TodayScreen(
    state: AppState,
    systemEnabled: Boolean,
    connected: Boolean,
    serviceIssue: String?,
    elapsed: Long,
    writing: Boolean,
    onSetup: () -> Unit,
    onApps: () -> Unit,
    onPauseToggle: (Boolean) -> Unit,
) {
    val settings = state.settings
    val selectedCount = settings.protectedPackages.size
    val active = state.sessions.filter { it.status == SessionStatus.ACTIVE }.sortedBy { it.deadlineElapsed }
    val needsApps = settings.mode == ProtectionMode.APPS && selectedCount == 0
    val ready = settings.consentAccepted && systemEnabled && connected && settings.protectionEnabled && !needsApps && serviceIssue == null
    val statusTitle = when {
        !settings.consentAccepted -> "A small pause. A clear purpose."
        !systemEnabled -> "One step to get started"
        !settings.protectionEnabled -> "Taking a pause"
        !connected -> "Waiting for the connection"
        serviceIssue != null -> "Something needs your attention"
        needsApps -> "Choose where to be intentional"
        else -> "You're ready to be intentional"
    }
    val statusDetail = when {
        !settings.consentAccepted -> "Choose what you'll do and how long you'll need, before getting drawn in."
        !systemEnabled -> "Enable Accountability Check in Android Accessibility settings to show your prompts."
        !settings.protectionEnabled -> "Your prompts are paused. Resume whenever you're ready for a little accountability."
        !connected -> "Android hasn't connected the service yet. Prompts aren't available until it connects."
        serviceIssue != null -> serviceIssue
        needsApps -> "Select a few apps to begin. Calls, settings, and other essential phone features stay available."
        settings.mode == ProtectionMode.PHONE -> if (settings.endPhoneSessionOnLock)
            "Experimental phone mode is on. Locking ends your intention; unlocking asks again."
            else "Experimental phone mode is on. Your intention keeps its original deadline through a lock."
        else -> "Your $selectedCount chosen ${if (selectedCount == 1) "app will" else "apps will"} ask for a purpose and a time budget."
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BrandMark()
                Text("ACCOUNTABILITY CHECK", style = MaterialTheme.typography.labelMedium, letterSpacing = 1.5.sp, color = MutedInk)
            }
            Spacer(Modifier.height(24.dp))
            Text("Make room\nfor intention.", fontFamily = FontFamily.Serif, fontSize = 42.sp, lineHeight = 45.sp, color = Ink)
            Spacer(Modifier.height(12.dp))
            Text("A little less autopilot. A little more you.", style = MaterialTheme.typography.bodyLarge, color = MutedInk)
        }
        item {
            Surface(shape = RoundedCornerShape(28.dp), color = if (ready) ReadyCard else PaleTeal) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                    val textColor = if (ready) Color.White else Ink
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(8.dp).background(if (ready) Color(0xFFB5DEB7) else Teal, CircleShape))
                        Text(if (ready) "PROMPTS READY" else "YOUR CHECK-IN", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.8.sp, color = textColor)
                    }
                    Text(statusTitle, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium, color = textColor)
                    Text(statusDetail, style = MaterialTheme.typography.bodyMedium, color = textColor.copy(alpha = .85f))
                    if (!settings.consentAccepted || !systemEnabled || !connected) {
                        Button(onClick = onSetup, enabled = !writing, colors = ButtonDefaults.buttonColors(containerColor = Teal), modifier = Modifier.fillMaxWidth()) {
                            Text(if (!settings.consentAccepted) "Set up my prompts" else "Open Accessibility settings")
                        }
                    } else if (needsApps) {
                        Button(onClick = onApps, modifier = Modifier.fillMaxWidth()) { Text("Choose your apps") }
                    }
                }
            }
        }
        state.error?.let { error ->
            item { NoticeCard("Couldn't load everything", error.toString()) }
        }
        if (settings.consentAccepted) {
            item {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(if (settings.protectionEnabled) "Prompts enabled" else "Prompts paused", fontWeight = FontWeight.SemiBold)
                            Text(if (settings.protectionEnabled) "Pause whenever you need to." else "Resuming asks for a fresh intention.", style = MaterialTheme.typography.bodySmall, color = MutedInk)
                        }
                        Switch(
                            checked = settings.protectionEnabled,
                            onCheckedChange = onPauseToggle,
                            enabled = !writing,
                            modifier = Modifier.semantics { contentDescription = "Enable accountability prompts" },
                        )
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionHeading("In this moment", Modifier.weight(1f))
                Text("${active.size} active", style = MaterialTheme.typography.labelMedium, color = MutedInk)
            }
        }
        if (active.isEmpty()) {
            item {
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Nothing on the clock.", fontFamily = FontFamily.Serif, fontSize = 23.sp)
                        Text("Open a chosen app to set your next intention. Your session will appear here.", style = MaterialTheme.typography.bodyMedium, color = MutedInk)
                    }
                }
            }
        } else {
            items(active, key = { it.id }) { session -> SessionCard(session, elapsed) }
        }
        item {
            Surface(shape = RoundedCornerShape(22.dp), color = Sand, onClick = onApps) {
                Row(Modifier.fillMaxWidth().padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Your intentional apps", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text("$selectedCount ${if (selectedCount == 1) "app" else "apps"} selected · Tap to change", color = MutedInk, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("→", fontSize = 24.sp, color = Teal)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Made for your intention, on your terms.", fontFamily = FontFamily.Serif, fontSize = 21.sp)
                Text("Time keeps running while you're away or your screen is off. At the limit, a reminder takes you Home. Background uploads or audio may continue.", style = MaterialTheme.typography.bodySmall, color = MutedInk)
                Text("Your intentions and history stay on this device. No account needed.", style = MaterialTheme.typography.bodySmall, color = MutedInk)
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    settings: AppSettings,
    writing: Boolean,
    onThemeMode: (ThemeMode) -> Unit,
    onMode: (ProtectionMode) -> Unit,
    onEndPhoneSessionOnLock: (Boolean) -> Unit,
    onSetup: () -> Unit,
    onDisclosure: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item { PageTitle("Settings", "Make the app feel right for you.") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeading("Appearance")
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        ModeRow("Follow system", "Match your phone's light or dark setting.", settings.themeMode == ThemeMode.SYSTEM, !writing) { onThemeMode(ThemeMode.SYSTEM) }
                        HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = Sand)
                        ModeRow("Light", "Use the light appearance all the time.", settings.themeMode == ThemeMode.LIGHT, !writing) { onThemeMode(ThemeMode.LIGHT) }
                        HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = Sand)
                        ModeRow("Dark", "Use the dark appearance all the time.", settings.themeMode == ThemeMode.DARK, !writing) { onThemeMode(ThemeMode.DARK) }
                    }
                }
                Text("The intention form and time-up reminder follow this choice too.", style = MaterialTheme.typography.bodySmall, color = MutedInk)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeading("When to check in")
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        ModeRow("When I open a chosen app", "A separate intention and timer for each app.", settings.mode == ProtectionMode.APPS, !writing) { onMode(ProtectionMode.APPS) }
                        HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = Sand)
                        ModeRow("When I unlock my phone", "Experimental · One intention across apps. Choose below what locking does.", settings.mode == ProtectionMode.PHONE, !writing) { onMode(ProtectionMode.PHONE) }
                    }
                }
                if (settings.mode == ProtectionMode.PHONE) {
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                Text("End intention when I lock", fontWeight = FontWeight.SemiBold)
                                Text("Off: keep the same timer after unlocking. On: ask for a new intention after unlocking.", style = MaterialTheme.typography.bodySmall, color = MutedInk)
                            }
                            Switch(
                                checked = settings.endPhoneSessionOnLock,
                                onCheckedChange = onEndPhoneSessionOnLock,
                                enabled = !writing,
                                modifier = Modifier.semantics { contentDescription = "End phone intention when I lock" },
                            )
                        }
                    }
                }
                Text("Changing modes ends active sessions. Phone mode leaves essential apps usable. You can pause protection whenever needed.", style = MaterialTheme.typography.bodySmall, color = MutedInk)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionHeading("Access and privacy")
                TextButton(onClick = onSetup) { Text("Service settings") }
                TextButton(onClick = onDisclosure) { Text("How access works") }
            }
        }
    }
}

@Composable
private fun AppsScreen(
    apps: List<DeviceApp>,
    selected: Set<String>,
    loading: Boolean,
    loadError: String?,
    writing: Boolean,
    onToggle: (String, Boolean) -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    val filtered = remember(apps, search, selected) {
        apps.filter { it.label.contains(search, ignoreCase = true) || it.packageName.contains(search, ignoreCase = true) }
            .sortedWith(compareByDescending<DeviceApp> { it.packageName in selected }.thenBy { it.label.lowercase(Locale.getDefault()) })
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PageTitle("Choose your apps", "Where would a little intention help?")
            Text("${selected.size} selected · Changes save automatically", style = MaterialTheme.typography.labelMedium, color = Teal)
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Search apps") },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Calls, your keyboard, settings, and other essential apps are left out so you can always use your phone.", style = MaterialTheme.typography.bodySmall, color = MutedInk)
        }
        when {
            loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            loadError != null -> NoticeCard("Apps aren't available yet", loadError, Modifier.padding(24.dp))
            filtered.isEmpty() -> EmptyState("No apps found", if (search.isBlank()) "Installed apps you can choose will appear here." else "Try another app name.", Modifier.padding(24.dp))
            else -> LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filtered, key = { it.packageName }) { app ->
                    val isSelected = app.packageName in selected
                    Surface(shape = RoundedCornerShape(20.dp), color = if (isSelected) PaleTeal else MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(
                            modifier = Modifier.fillMaxWidth().toggleable(value = isSelected, enabled = !writing, role = Role.Switch) { onToggle(app.packageName, it) }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            AppIcon(app.icon)
                            Text(app.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Switch(checked = isSelected, onCheckedChange = null, enabled = !writing)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(sessions: List<SessionRecord>, onClear: () -> Unit, writing: Boolean) {
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            PageTitle("Your intentions", "A quiet record of what you made time for.")
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("${sessions.size} past ${if (sessions.size == 1) "session" else "sessions"}", color = MutedInk, modifier = Modifier.weight(1f))
                if (sessions.isNotEmpty()) TextButton(onClick = onClear, enabled = !writing) { Text("Clear history") }
            }
            Text("Your session ending doesn't tell us whether you finished your task. That's yours to reflect on.", style = MaterialTheme.typography.bodySmall, color = MutedInk)
        }
        if (sessions.isEmpty()) {
            item { EmptyState("A fresh page.", "Your past intentions will appear here after your first session. Only you can see them on this device.") }
        }
        items(sessions.sortedByDescending { it.startedWall }, key = { it.id }) { session ->
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(session.appLabel, color = Teal, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        Text("${session.durationMinutes} min", style = MaterialTheme.typography.labelMedium, color = MutedInk)
                    }
                    Text(session.intention, fontFamily = FontFamily.Serif, fontSize = 23.sp, lineHeight = 29.sp)
                    Text("${formatDate(session.startedWall)} · ${statusLabel(session.status)}", style = MaterialTheme.typography.bodySmall, color = MutedInk)
                }
            }
        }
    }
}

@Composable
private fun SessionCard(session: SessionRecord, elapsed: Long) {
    val remaining = session.remainingMillis(elapsed).coerceAtLeast(0)
    val total = session.durationMinutes * 60_000f
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(session.appLabel, style = MaterialTheme.typography.labelLarge, color = Teal, modifier = Modifier.weight(1f))
                Surface(shape = CircleShape, color = PaleTeal) {
                    Text(formatRemaining(remaining), Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = Ink)
                }
            }
            Text(session.intention, fontFamily = FontFamily.Serif, fontSize = 25.sp, lineHeight = 30.sp)
            LinearProgressIndicator(progress = { (remaining / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape), color = Teal, trackColor = Sand)
            Text("${session.durationMinutes} minutes set aside · Time keeps running while away", style = MaterialTheme.typography.bodySmall, color = MutedInk)
        }
    }
}

@Composable
private fun ModeRow(title: String, description: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(start = 8.dp, end = 20.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MutedInk)
        }
    }
}

@Composable
private fun DisclosureDialog(writing: Boolean, onDismiss: () -> Unit, onAccept: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("A clear purpose\nfor Accessibility.", fontFamily = FontFamily.Serif, fontSize = 28.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Accountability Check uses Android Accessibility to:", fontWeight = FontWeight.SemiBold)
                Text("• Notice which app window is active using app identifiers and window events.\n\n• Show a prompt for your intention and time budget.\n\n• Return you to Home when your chosen time expires.")
                Text("Android grants access to screen content so the app can confirm the active app when the service starts, your phone wakes, or a timer ends. This app reads only the active app identifier; it doesn't inspect screen text, messages, passwords, or screenshots. Your intentions and session history are saved on this device only.")
                Text("Android Accessibility grants powerful access. This app uses it for the features described here. You can pause prompts in this app or disable the service in Android Settings at any time.")
                Text("You'll enable the service on the next screen. Apps may appear briefly before a prompt; this is a voluntary aid for intentional use, and doesn't stop background uploads or audio.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = !writing, onClick = onAccept) { Text("Agree and continue") } },
        dismissButton = { TextButton(enabled = !writing, onClick = onDismiss) { Text("Not now") } },
    )
}

@Composable
private fun PageTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontFamily = FontFamily.Serif, fontSize = 36.sp, lineHeight = 40.sp)
        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MutedInk)
    }
}

@Composable
private fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun EmptyState(title: String, description: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = PaleTeal) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontFamily = FontFamily.Serif, fontSize = 26.sp)
            Text(description, color = MutedInk, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun NoticeCard(title: String, description: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun AppIcon(drawable: Drawable) {
    val image = remember(drawable) {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, 96, 96)
        drawable.draw(canvas)
        bitmap.asImageBitmap()
    }
    Image(image, contentDescription = null, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
}

@Composable
private fun BrandMark() {
    val brandColor = Teal
    Canvas(Modifier.size(28.dp)) {
        drawCircle(brandColor, style = Stroke(2.dp.toPx()))
        drawLine(brandColor, Offset(size.width * .35f, size.height * .5f), Offset(size.width * .46f, size.height * .62f), 2.dp.toPx())
        drawLine(brandColor, Offset(size.width * .46f, size.height * .62f), Offset(size.width * .68f, size.height * .36f), 2.dp.toPx())
    }
}

@Composable
private fun NavigationGlyph(destination: String) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(24.dp)) {
        val stroke = Stroke(1.8.dp.toPx())
        val u = size.width / 24f
        when (destination) {
            "today" -> {
                val path = Path().apply {
                    moveTo(3 * u, 10 * u); lineTo(12 * u, 3 * u); lineTo(21 * u, 10 * u)
                    moveTo(5 * u, 9 * u); lineTo(5 * u, 21 * u); lineTo(10 * u, 21 * u)
                    lineTo(10 * u, 14 * u); lineTo(14 * u, 14 * u); lineTo(14 * u, 21 * u)
                    lineTo(19 * u, 21 * u); lineTo(19 * u, 9 * u)
                }
                drawPath(path, color, style = stroke)
            }
            "apps" -> {
                for (x in listOf(3f, 14f)) for (y in listOf(3f, 14f)) {
                    drawRoundRect(color, Offset(x * u, y * u), Size(7 * u, 7 * u), CornerRadius(1.5f * u), style = stroke)
                }
            }
            "history" -> {
                drawCircle(color, 9 * u, Offset(12 * u, 12 * u), style = stroke)
                drawLine(color, Offset(12 * u, 6 * u), Offset(12 * u, 12 * u), stroke.width)
                drawLine(color, Offset(12 * u, 12 * u), Offset(16 * u, 14 * u), stroke.width)
            }
            else -> {
                listOf(5f, 12f, 19f).forEach { y ->
                    drawLine(color, Offset(3 * u, y * u), Offset(21 * u, y * u), stroke.width)
                }
                drawCircle(color, 2.3f * u, Offset(9 * u, 5 * u))
                drawCircle(color, 2.3f * u, Offset(16 * u, 12 * u))
                drawCircle(color, 2.3f * u, Offset(8 * u, 19 * u))
            }
        }
    }
}

private fun formatRemaining(milliseconds: Long): String {
    if (milliseconds <= 0) return "Time is up"
    val seconds = (milliseconds + 999) / 1_000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')} left"
}

private fun formatDate(timestamp: Long): String = SimpleDateFormat("d MMM · h:mm a", Locale.getDefault()).format(Date(timestamp))

private fun statusLabel(status: SessionStatus): String = when (status) {
    SessionStatus.ACTIVE -> "In progress"
    SessionStatus.EXPIRED -> "Time reached"
    SessionStatus.INTERRUPTED -> "Ended early"
    SessionStatus.REBOOT_INVALIDATED -> "Ended after restart"
}
