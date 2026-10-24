package nz.co.accountabilitycheck

import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import nz.co.accountabilitycheck.domain.ClockSnapshot
import nz.co.accountabilitycheck.domain.SessionRecord
import nz.co.accountabilitycheck.platform.SessionNotifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Only synthetic notifications under a separate tag; never changes protection or saved sessions. */
@RunWith(AndroidJUnit4::class)
class SessionNotificationTest {
    private val app = ApplicationProvider.getApplicationContext<AccountabilityApp>()
    private val notifications get() = app.sessionNotifications
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val now get() = ClockSnapshot(SystemClock.elapsedRealtime(), 1, System.currentTimeMillis())

    private fun session(clock: ClockSnapshot) = SessionRecord(
        id = "notification-test", packageName = "example.test", appLabel = "Focus test app",
        intention = "Read the saved article", durationMinutes = 2,
        startedElapsed = clock.elapsedRealtime, deadlineElapsed = clock.elapsedRealtime + 120_000,
        bootCount = clock.bootCount, startedWall = clock.wallTimeMillis,
    )

    @Test fun notificationContainsIntentionAndNativeCountdown() {
        val clock = now
        val notification = notifications.buildNotification(session(clock), clock)
        assertEquals("Focus test app", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Read the saved article", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("Read the saved article", notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        assertEquals(clock.wallTimeMillis + 120_000, notification.`when`)
        assertEquals(120_000L, notification.timeoutAfter)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertNotNull(notification.contentIntent)
        assertNotNull(notification.deleteIntent)
    }

    @Test fun clockChangesKeepTheOriginalElapsedBudget() {
        val started = now
        val original = session(started)
        val changed = started.copy(elapsedRealtime = started.elapsedRealtime + 30_000,
            wallTimeMillis = started.wallTimeMillis - 86_400_000)
        val notification = notifications.buildNotification(original, changed)
        assertEquals(90_000L, notification.`when` - changed.wallTimeMillis)
        assertEquals(90_000L, notification.timeoutAfter)
    }

    @Test fun expiredSessionsCannotCreateNegativeCountdowns() {
        val clock = now
        val original = session(clock)
        assertThrows(IllegalArgumentException::class.java) {
            notifications.buildNotification(original, clock.copy(elapsedRealtime = original.deadlineElapsed))
        }
    }

    @Test fun countdownRequestsLiveUpdatePromotion() {
        val clock = now
        val notification = notifications.buildNotification(session(clock), clock)
        assertTrue(NotificationCompat.isRequestPromotedOngoing(notification))
        if (Build.VERSION.SDK_INT >= 36) assertTrue(notification.hasPromotableCharacteristics())
    }

    @Test fun androidExpiresOneCountdownWithoutRemovingAnother() {
        assumeTrue("Enable notifications before running the posting test", notifications.areEnabled())
        val clock = now
        val tag = "notification-test-only"
        try {
            manager.notify(tag, 101, notifications.buildNotification(
                session(clock).copy(deadlineElapsed = clock.elapsedRealtime + 2_000), clock))
            manager.notify(tag, 102, notifications.buildNotification(session(clock), clock))
            val appearedBy = SystemClock.elapsedRealtime() + 1_500
            while (manager.activeNotifications.count { it.tag == tag } != 2 && SystemClock.elapsedRealtime() < appearedBy) {
                SystemClock.sleep(50)
            }
            assertEquals(2, manager.activeNotifications.count { it.tag == tag })
            val expiresBy = SystemClock.elapsedRealtime() + 8_000
            while (manager.activeNotifications.any { it.tag == tag && it.id == 101 } && SystemClock.elapsedRealtime() < expiresBy) {
                SystemClock.sleep(100)
            }
            assertEquals(listOf(102), manager.activeNotifications.filter { it.tag == tag }.map { it.id })
        } finally {
            manager.cancel(tag, 101)
            manager.cancel(tag, 102)
        }
    }
}
