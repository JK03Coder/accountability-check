package nz.co.accountabilitycheck.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import nz.co.accountabilitycheck.AccountabilityApp

@Composable
internal fun SessionNotificationSettings(hideWhenEnabled: Boolean = false) {
    val context = LocalContext.current
    val app = context.applicationContext as AccountabilityApp
    val lifecycleOwner = LocalLifecycleOwner.current
    var enabled by remember { mutableStateOf(app.sessionNotifications.areEnabled()) }
    var requested by rememberSaveable { mutableStateOf(false) }
    var settingsUnavailable by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        enabled = app.sessionNotifications.areEnabled()
        app.refreshSessionNotifications()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = app.sessionNotifications.areEnabled()
                app.refreshSessionNotifications()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (hideWhenEnabled && enabled) return

    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Intention timer", fontWeight = FontWeight.SemiBold)
            Text(
                if (enabled) "Your intention and a live countdown appear in a notification while your session runs."
                else "Keep your intention and a live countdown in view while your session runs. Enable notifications to see them.",
                style = MaterialTheme.typography.bodyMedium, color = MutedInk,
            )
            if (Build.VERSION.SDK_INT >= 36) {
                Text("Allow Live Updates in notification settings to show the timer in your status bar when your phone supports it.",
                    style = MaterialTheme.typography.bodySmall, color = MutedInk)
            }
            OutlinedButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33 && !requested &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    requested = true
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    try {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    } catch (_: ActivityNotFoundException) {
                        settingsUnavailable = true
                    }
                }
            }) { Text(if (enabled) "Notification settings" else "Enable notifications") }
            if (!enabled) Text("Notifications are optional. Your intention prompts still work without them.",
                style = MaterialTheme.typography.bodySmall, color = MutedInk)
            if (settingsUnavailable) Text("Open Android Settings, then Apps, Accountability Check, Notifications.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
