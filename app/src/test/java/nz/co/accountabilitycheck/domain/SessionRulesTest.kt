package nz.co.accountabilitycheck.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRulesTest {
    private val settings = AppSettings(
        consentAccepted = true,
        protectionEnabled = true,
        protectedPackages = setOf("example.instagram", "example.browser"),
    )
    private val now = ClockSnapshot(elapsedRealtime = 100_000L, bootCount = 8, wallTimeMillis = 1_800_000_000_000L)

    private fun start(
        sessions: List<SessionRecord> = emptyList(),
        time: ClockSnapshot = now,
        id: String = "session-1",
        packageName: String? = "example.instagram",
        selectedSettings: AppSettings = settings,
        intention: String = "Upload to my story",
        minutes: Int = 1,
    ) = SessionRules.start(selectedSettings, sessions, packageName, "Instagram", intention, minutes, time, id)

    @Test fun `starting persists a trimmed intention and elapsed deadline`() {
        val result = start(intention = "  Upload to my story  ", minutes = 3)
        assertEquals("Upload to my story", result.session.intention)
        assertEquals(now.elapsedRealtime + 180_000L, result.session.deadlineElapsed)
        assertEquals(now.wallTimeMillis, result.session.startedWall)
        assertEquals(listOf(result.session), result.sessions)
    }

    @Test fun `duplicate starts and reopening never extend the original session`() {
        val first = start(minutes = 2)
        val duplicate = start(
            sessions = first.sessions,
            time = now.copy(elapsedRealtime = now.elapsedRealtime + 59_999),
            id = "duplicate",
            intention = "A different reason",
            minutes = 120,
        )
        assertEquals(first.session, duplicate.session)
        assertEquals(first.sessions, duplicate.sessions)
    }

    @Test fun `expiry occurs at the exact deadline and is only emitted once`() {
        val original = start().session
        val before = SessionRules.reconcile(listOf(original), now.copy(elapsedRealtime = original.deadlineElapsed - 1))
        assertTrue(before.newlyExpired.isEmpty())
        assertEquals(1L, before.sessions.single().remainingMillis(original.deadlineElapsed - 1))
        val expired = SessionRules.reconcile(before.sessions, now.copy(elapsedRealtime = original.deadlineElapsed))
        assertEquals(SessionStatus.EXPIRED, expired.newlyExpired.single().status)
        assertEquals(0L, expired.newlyExpired.single().remainingMillis(original.deadlineElapsed))
        val repeated = SessionRules.reconcile(expired.sessions, now.copy(elapsedRealtime = original.deadlineElapsed + 100))
        assertTrue(repeated.newlyExpired.isEmpty())
        assertEquals(expired.sessions, repeated.sessions)
    }

    @Test fun `a late reconciliation of an older expiry cannot end its replacement`() {
        val first = start()
        val afterFirstDeadline = now.copy(elapsedRealtime = first.session.deadlineElapsed + 1)
        val second = start(first.sessions, afterFirstDeadline, id = "replacement", minutes = 10)
        val result = SessionRules.reconcile(second.sessions, afterFirstDeadline.copy(elapsedRealtime = afterFirstDeadline.elapsedRealtime + 1))
        assertEquals(SessionStatus.ACTIVE, result.sessions.first { it.id == "replacement" }.status)
        assertEquals(SessionStatus.EXPIRED, result.sessions.first { it.id == "session-1" }.status)
        assertTrue(result.newlyExpired.isEmpty())
    }

    @Test fun `wall clock changes cannot shorten or extend an active timer`() {
        val original = start(minutes = 10).session
        for (shift in listOf(-100L, 100L)) {
            val adjusted = now.copy(elapsedRealtime = now.elapsedRealtime + 30_000, wallTimeMillis = now.wallTimeMillis + shift * SessionRules.HISTORY_RETENTION_MILLIS)
            val result = SessionRules.reconcile(listOf(original), adjusted)
            assertEquals(SessionStatus.ACTIVE, result.sessions.single().status)
            assertEquals(570_000L, result.sessions.single().remainingMillis(adjusted.elapsedRealtime))
        }
    }

    @Test fun `a new boot invalidates even when its uptime exceeds the old uptime`() {
        val original = start().session
        val result = SessionRules.reconcile(listOf(original), now.copy(bootCount = 9, elapsedRealtime = original.deadlineElapsed + 99_000))
        assertEquals(SessionStatus.REBOOT_INVALIDATED, result.sessions.single().status)
        assertTrue(result.newlyExpired.isEmpty())
    }

    @Test fun `unknown boot information and elapsed time rollback invalidate sessions`() {
        val original = start().session
        val invalidCurrentBoot = SessionRules.reconcile(listOf(original), now.copy(bootCount = -1))
        val invalidStoredBoot = SessionRules.reconcile(listOf(original.copy(bootCount = -1)), now)
        val elapsedRollback = SessionRules.reconcile(listOf(original), now.copy(elapsedRealtime = now.elapsedRealtime - 1))
        for (result in listOf(invalidCurrentBoot, invalidStoredBoot, elapsedRollback)) {
            assertEquals(SessionStatus.REBOOT_INVALIDATED, result.sessions.single().status)
            assertTrue(result.newlyExpired.isEmpty())
        }
        assertThrows(IllegalStateException::class.java) { start(time = now.copy(bootCount = -1)) }
    }

    @Test fun `starting requires both consent and enabled protection`() {
        assertThrows(IllegalStateException::class.java) { start(selectedSettings = settings.copy(consentAccepted = false)) }
        assertThrows(IllegalStateException::class.java) { start(selectedSettings = settings.copy(protectionEnabled = false)) }
        assertThrows(IllegalArgumentException::class.java) {
            SessionRules.changeSettings(AppState(), AppSettings(protectionEnabled = true), now)
        }
    }

    @Test fun `invalid duration and intention never create sessions`() {
        for (minutes in listOf(Int.MIN_VALUE, -1, 0, 121, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { start(minutes = minutes) }
        }
        for (intention in listOf("", "   \n ", "x".repeat(201))) {
            assertThrows(IllegalArgumentException::class.java) { start(intention = intention) }
        }
        assertEquals(120, start(minutes = 120).session.durationMinutes)
        assertEquals(200, start(intention = "x".repeat(200)).session.intention.length)
    }

    @Test fun `unselected apps and scopes inconsistent with the mode are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { start(packageName = "example.other") }
        assertThrows(IllegalArgumentException::class.java) { start(packageName = null) }
        assertThrows(IllegalArgumentException::class.java) { start(selectedSettings = settings.copy(mode = ProtectionMode.PHONE)) }
        val phone = start(packageName = null, selectedSettings = settings.copy(mode = ProtectionMode.PHONE)).session
        assertEquals("phone", phone.scopeKey)
    }

    @Test fun `switching protection modes interrupts previous timers`() {
        val app = start().session
        val switched = SessionRules.changeSettings(AppState(settings, listOf(app)), settings.copy(mode = ProtectionMode.PHONE), now)
        assertEquals(SessionStatus.INTERRUPTED, switched.sessions.single().status)
        val phone = start(switched.sessions, id = "phone", packageName = null, selectedSettings = switched.settings)
        val switchedBack = SessionRules.changeSettings(switched.copy(sessions = phone.sessions), settings, now)
        assertFalse(switchedBack.sessions.any { it.status == SessionStatus.ACTIVE })
    }

    @Test fun `disabling protection interrupts active timers and reenabling does not revive them`() {
        val state = AppState(settings, start().sessions)
        val disabled = SessionRules.changeSettings(state, settings.copy(protectionEnabled = false), now)
        assertEquals(SessionStatus.INTERRUPTED, disabled.sessions.single().status)
        val enabled = SessionRules.changeSettings(disabled, settings, now)
        assertEquals(disabled.sessions, enabled.sessions)
    }

    @Test fun `removing an app interrupts only that app`() {
        val first = start()
        val second = start(first.sessions, id = "browser", packageName = "example.browser")
        val changed = SessionRules.changeSettings(AppState(settings, second.sessions), settings.copy(protectedPackages = setOf("example.browser")), now)
        assertEquals(SessionStatus.INTERRUPTED, changed.sessions.first { it.id == "session-1" }.status)
        assertEquals(SessionStatus.ACTIVE, changed.sessions.first { it.id == "browser" }.status)
    }

    @Test fun `reapplying the same settings does not interrupt active timers`() {
        val state = AppState(settings, start().sessions)
        assertEquals(state, SessionRules.changeSettings(state, settings, now))
    }

    @Test fun `phone session retains its original deadline through screen off time`() {
        val phone = start(packageName = null, selectedSettings = settings.copy(mode = ProtectionMode.PHONE), id = "phone", minutes = 2).session
        val afterWake = SessionRules.reconcile(listOf(phone), now.copy(elapsedRealtime = now.elapsedRealtime + 30_000))
        assertEquals(phone, afterWake.sessions.single())
        val expired = SessionRules.reconcile(afterWake.sessions, now.copy(elapsedRealtime = phone.deadlineElapsed))
        assertEquals(SessionStatus.EXPIRED, expired.sessions.single().status)
    }

    @Test fun `optional end on lock interrupts only the phone session`() {
        val app = start().session
        val phone = start(packageName = null, selectedSettings = settings.copy(mode = ProtectionMode.PHONE), id = "phone").session
        val locked = SessionRules.interruptPhoneSession(listOf(app, phone), now)
        assertEquals(app, locked.first { it.id == app.id })
        assertEquals(SessionStatus.INTERRUPTED, locked.first { it.id == phone.id }.status)
    }

    @Test fun `history pruning keeps active deadlines and only thirty days of completed records`() {
        val active = start().session.copy(startedWall = 0)
        val retained = active.copy(id = "recent", status = SessionStatus.EXPIRED, endedWall = now.wallTimeMillis - SessionRules.HISTORY_RETENTION_MILLIS)
        val removed = retained.copy(id = "old", endedWall = retained.endedWall!! - 1)
        assertEquals(setOf("session-1", "recent"), SessionRules.prune(listOf(active, retained, removed), now.wallTimeMillis).map { it.id }.toSet())
    }

    @Test fun `history is bounded and most recent completed records survive`() {
        val active = start().session
        val completed = (1..600).map {
            active.copy(id = "history-$it", status = SessionStatus.EXPIRED, endedWall = now.wallTimeMillis - it)
        }
        val result = SessionRules.prune(completed + active, now.wallTimeMillis)
        assertEquals(500, result.size)
        assertTrue(active in result)
        assertTrue(result.any { it.id == "history-499" })
        assertFalse(result.any { it.id == "history-500" })
    }
}
