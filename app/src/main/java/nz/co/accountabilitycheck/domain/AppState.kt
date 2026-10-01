package nz.co.accountabilitycheck.domain

enum class ProtectionMode { APPS, PHONE }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class SessionStatus { ACTIVE, EXPIRED, INTERRUPTED, REBOOT_INVALIDATED }

data class AppSettings(
    val consentAccepted: Boolean = false,
    val protectionEnabled: Boolean = false,
    val mode: ProtectionMode = ProtectionMode.APPS,
    val protectedPackages: Set<String> = emptySet(),
    val endPhoneSessionOnLock: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)

data class SessionRecord(
    val id: String,
    val packageName: String?,
    val appLabel: String,
    val intention: String,
    val durationMinutes: Int,
    val startedElapsed: Long,
    val deadlineElapsed: Long,
    val bootCount: Int,
    val startedWall: Long,
    val endedWall: Long? = null,
    val status: SessionStatus = SessionStatus.ACTIVE,
) {
    val scopeKey: String get() = packageName ?: "phone"

    fun remainingMillis(nowElapsed: Long): Long =
        if (status == SessionStatus.ACTIVE) (deadlineElapsed - nowElapsed).coerceAtLeast(0L) else 0L
}

data class AppState(
    val settings: AppSettings = AppSettings(),
    val sessions: List<SessionRecord> = emptyList(),
    val loaded: Boolean = false,
    val error: String? = null,
)
