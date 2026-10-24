package nz.co.accountabilitycheck.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import nz.co.accountabilitycheck.MainActivity
import nz.co.accountabilitycheck.R
import nz.co.accountabilitycheck.domain.AppState
import nz.co.accountabilitycheck.domain.ClockSnapshot
import nz.co.accountabilitycheck.domain.SessionRecord
import nz.co.accountabilitycheck.domain.SessionRules
import nz.co.accountabilitycheck.domain.SessionStatus

/** Android renders the countdown; we only post when a session or its clock base changes. */
class SessionNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val preferences = context.getSharedPreferences("session_notifications", Context.MODE_PRIVATE)
    private val posted = mutableMapOf<String, SessionRecord>()

    init {
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "Active intentions", NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Your intention and remaining time while a session is running."
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
    }

    fun areEnabled(): Boolean = manager.areNotificationsEnabled() &&
        manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE

    fun update(state: AppState, protectionConnected: Boolean, clockChanged: Boolean = false) {
        if (!state.loaded) {
            cancelAll()
            return
        }
        val now = ClockSnapshot(
            SystemClock.elapsedRealtime(),
            try {
                Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
            } catch (_: SecurityException) { -1 },
            System.currentTimeMillis(),
        )
        val active = if (state.error == null && state.settings.consentAccepted && state.settings.protectionEnabled)
            SessionRules.reconcile(state.sessions, now).sessions.filter { it.status == SessionStatus.ACTIVE }
        else emptyList()
        val dismissed = preferences.getStringSet(DISMISSED_KEY, emptySet()).orEmpty()
        val retained = dismissed.intersect(active.map { it.id }.toSet())
        if (retained != dismissed) preferences.edit { putStringSet(DISMISSED_KEY, retained) }
        val visible = if (protectionConnected && areEnabled()) active.filter { it.id !in retained } else emptyList()
        val ids = visible.map { it.id }.toSet()
        manager.activeNotifications.filter { it.tag?.startsWith(TAG_PREFIX) == true && it.tag.removePrefix(TAG_PREFIX) !in ids }
            .forEach { manager.cancel(it.tag, NOTIFICATION_ID) }
        posted.keys.retainAll(ids)
        for (session in visible) {
            // A dismissed notification must stay dismissed, even after another app/window event.
            if (!clockChanged && posted[session.id] == session) continue
            try {
                manager.notify(TAG_PREFIX + session.id, NOTIFICATION_ID, buildNotification(session, now))
                posted[session.id] = session
            } catch (_: SecurityException) {
                // Notification access can be revoked between the check and posting. Prompts still work.
                posted.clear()
            }
        }
    }

    internal fun buildNotification(session: SessionRecord, now: ClockSnapshot): Notification {
        val remaining = session.remainingMillis(now.elapsedRealtime)
        require(remaining > 0) { "Only running sessions can have a countdown notification." }
        val openApp = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismiss = PendingIntent.getBroadcast(context, 0,
            Intent(context, SessionNotificationDismissReceiver::class.java)
                .setData(Uri.Builder().scheme("accountability").authority("session").appendPath(session.id).build())
                .putExtra("session_id", session.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_session_notification)
            .setContentTitle(session.appLabel)
            .setContentText(session.intention)
            .setStyle(NotificationCompat.BigTextStyle().bigText(session.intention))
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setWhen(now.wallTimeMillis + remaining)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setTimeoutAfter(remaining)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setRequestPromotedOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openApp)
            .setDeleteIntent(dismiss)
            .build()
    }

    private fun cancelAll() {
        manager.activeNotifications.filter { it.tag?.startsWith(TAG_PREFIX) == true }
            .forEach { manager.cancel(it.tag, NOTIFICATION_ID) }
        posted.clear()
    }

    companion object {
        const val CHANNEL_ID = "active_intentions"
        private const val TAG_PREFIX = "session:"
        private const val NOTIFICATION_ID = 1
        internal const val DISMISSED_KEY = "dismissed_sessions"
    }
}

class SessionNotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("session_id") ?: return
        val preferences = context.getSharedPreferences("session_notifications", Context.MODE_PRIVATE)
        val dismissed = preferences.getStringSet(SessionNotifications.DISMISSED_KEY, emptySet()).orEmpty() + id
        preferences.edit { putStringSet(SessionNotifications.DISMISSED_KEY, dismissed) }
    }
}
