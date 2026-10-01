package nz.co.accountabilitycheck.domain

/** Wall time is for history only. Enforcement always uses elapsed time from the same boot. */
data class ClockSnapshot(val elapsedRealtime: Long, val bootCount: Int, val wallTimeMillis: Long)

data class Reconciliation(val sessions: List<SessionRecord>, val newlyExpired: List<SessionRecord>)

data class SessionStart(val sessions: List<SessionRecord>, val session: SessionRecord)

object SessionRules {
    const val MAX_DURATION_MINUTES = 120
    const val MAX_INTENTION_LENGTH = 200
    const val MAX_RECORDS = 500
    const val HISTORY_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1_000

    fun reconcile(sessions: List<SessionRecord>, now: ClockSnapshot): Reconciliation {
        val expired = mutableListOf<SessionRecord>()
        val updated = sessions.map { session ->
            if (session.status != SessionStatus.ACTIVE) {
                session
            } else {
                val newStatus = when {
                    now.bootCount < 0 || session.bootCount < 0 || session.bootCount != now.bootCount ||
                        now.elapsedRealtime < session.startedElapsed -> SessionStatus.REBOOT_INVALIDATED
                    now.elapsedRealtime >= session.deadlineElapsed -> SessionStatus.EXPIRED
                    else -> SessionStatus.ACTIVE
                }
                if (newStatus == SessionStatus.ACTIVE) session else {
                    session.copy(status = newStatus, endedWall = now.wallTimeMillis).also {
                        if (newStatus == SessionStatus.EXPIRED) expired.add(it)
                    }
                }
            }
        }
        return Reconciliation(prune(updated, now.wallTimeMillis), expired)
    }

    fun start(
        settings: AppSettings,
        sessions: List<SessionRecord>,
        packageName: String?,
        appLabel: String,
        intention: String,
        minutes: Int,
        now: ClockSnapshot,
        id: String,
    ): SessionStart {
        check(settings.consentAccepted) { "Read and accept the disclosure before starting protection." }
        check(settings.protectionEnabled) { "Turn protection on before starting a session." }
        require(intention.trim().length in 1..MAX_INTENTION_LENGTH) {
            "Describe your intention using 1–$MAX_INTENTION_LENGTH characters."
        }
        require(minutes in 1..MAX_DURATION_MINUTES) { "Choose between 1 and $MAX_DURATION_MINUTES minutes." }
        require(appLabel.isNotBlank()) { "The session needs an app name." }
        require(id.isNotBlank()) { "The session needs an identifier." }
        check(now.bootCount >= 0 && now.elapsedRealtime >= 0) {
            "Android restart information is unavailable. Try restarting your phone."
        }
        when (settings.mode) {
            ProtectionMode.PHONE -> require(packageName == null) { "Phone mode needs a phone session." }
            ProtectionMode.APPS -> require(packageName != null && packageName in settings.protectedPackages) {
                "Choose an app that is included in protection."
            }
        }
        val current = reconcile(sessions, now).sessions
        val existing = current.firstOrNull {
            it.packageName == packageName && it.status == SessionStatus.ACTIVE
        }
        // Reopening or receiving a duplicate window event must never reset a live deadline.
        if (existing != null) return SessionStart(current, existing)
        check(current.count { it.status == SessionStatus.ACTIVE } < MAX_RECORDS) {
            "There are too many active sessions. End a session before starting another."
        }
        val session = SessionRecord(
            id = id,
            packageName = packageName,
            appLabel = appLabel.trim().take(200),
            intention = intention.trim(),
            durationMinutes = minutes,
            startedElapsed = now.elapsedRealtime,
            deadlineElapsed = Math.addExact(now.elapsedRealtime, minutes * 60_000L),
            bootCount = now.bootCount,
            startedWall = now.wallTimeMillis,
        )
        return SessionStart(prune(listOf(session) + current, now.wallTimeMillis), session)
    }

    fun changeSettings(state: AppState, settings: AppSettings, now: ClockSnapshot): AppState {
        require(!settings.protectionEnabled || settings.consentAccepted) {
            "Read and accept the disclosure before enabling protection."
        }
        val modeChanged = settings.mode != state.settings.mode
        val sessions = reconcile(state.sessions, now).sessions.map { session ->
            val removedApp = session.packageName != null && session.packageName !in settings.protectedPackages
            if (session.status == SessionStatus.ACTIVE &&
                (!settings.protectionEnabled || modeChanged || removedApp)
            ) session.copy(status = SessionStatus.INTERRUPTED, endedWall = now.wallTimeMillis) else session
        }
        return state.copy(settings = settings, sessions = prune(sessions, now.wallTimeMillis))
    }

    fun interruptPhoneSession(sessions: List<SessionRecord>, now: ClockSnapshot): List<SessionRecord> =
        reconcile(sessions, now).sessions.map {
            if (it.packageName == null && it.status == SessionStatus.ACTIVE) {
                it.copy(status = SessionStatus.INTERRUPTED, endedWall = now.wallTimeMillis)
            } else it
        }

    fun prune(sessions: List<SessionRecord>, nowWall: Long): List<SessionRecord> {
        val active = sessions.filter { it.status == SessionStatus.ACTIVE }
        val history = sessions.filter {
            it.status != SessionStatus.ACTIVE && (it.endedWall ?: it.startedWall) >= nowWall - HISTORY_RETENTION_MILLIS
        }.sortedByDescending { it.endedWall ?: it.startedWall }
        return (active + history.take((MAX_RECORDS - active.size).coerceAtLeast(0)))
            .sortedByDescending { it.startedWall }
    }
}
