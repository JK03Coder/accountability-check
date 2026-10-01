package nz.co.accountabilitycheck.data

import java.io.IOException
import nz.co.accountabilitycheck.domain.AppSettings
import nz.co.accountabilitycheck.domain.AppState
import nz.co.accountabilitycheck.domain.ProtectionMode
import nz.co.accountabilitycheck.domain.SessionRecord
import nz.co.accountabilitycheck.domain.SessionRules
import nz.co.accountabilitycheck.domain.SessionStatus
import nz.co.accountabilitycheck.domain.ThemeMode
import org.json.JSONArray
import org.json.JSONObject

internal data class StoredState(val state: AppState, val revision: Long)

internal class StateFormatException(cause: Throwable? = null) : IOException(
    "Saved data could not be read. Protection is paused to preserve your data. Clear app storage in Android settings to reset it.",
    cause,
)

/** All settings and sessions live in one versioned value, so writes are atomic. */
internal object StateCodec {
    fun decode(value: String?): StoredState {
        if (value == null) return StoredState(AppState(loaded = true), 0)
        try {
            val root = JSONObject(value)
            require(root.long("version") == 1L)
            val revision = root.long("revision").also { require(it >= 0) }
            val settingsJson = root.getJSONObject("settings")
            val packageArray = settingsJson.getJSONArray("protectedPackages")
            val packages = (0 until packageArray.length()).map {
                (packageArray.get(it) as String).also { name -> require(name.isNotBlank()) }
            }.toSet()
            val settings = AppSettings(
                consentAccepted = settingsJson.get("consentAccepted") as Boolean,
                protectionEnabled = settingsJson.get("protectionEnabled") as Boolean,
                mode = ProtectionMode.valueOf(settingsJson.get("mode") as String),
                protectedPackages = packages,
                endPhoneSessionOnLock = if (settingsJson.has("endPhoneSessionOnLock"))
                    settingsJson.get("endPhoneSessionOnLock") as Boolean else false,
                themeMode = if (settingsJson.has("themeMode"))
                    ThemeMode.valueOf(settingsJson.get("themeMode") as String) else ThemeMode.SYSTEM,
            )
            require(!settings.protectionEnabled || settings.consentAccepted)
            val sessionArray = root.getJSONArray("sessions")
            require(sessionArray.length() <= SessionRules.MAX_RECORDS)
            val sessions = (0 until sessionArray.length()).map { index ->
                val item = sessionArray.getJSONObject(index)
                SessionRecord(
                    id = item.get("id") as String,
                    packageName = item.nullableString("packageName"),
                    appLabel = item.get("appLabel") as String,
                    intention = item.get("intention") as String,
                    durationMinutes = item.long("durationMinutes").toIntExact(),
                    startedElapsed = item.long("startedElapsed"),
                    deadlineElapsed = item.long("deadlineElapsed"),
                    bootCount = item.long("bootCount").toIntExact(),
                    startedWall = item.long("startedWall"),
                    endedWall = if (item.get("endedWall") == JSONObject.NULL) null else item.long("endedWall"),
                    status = SessionStatus.valueOf(item.get("status") as String),
                ).also { session ->
                    require(session.id.isNotBlank())
                    require(session.packageName == null || session.packageName.isNotBlank())
                    require(session.appLabel.isNotBlank())
                    require(session.intention.trim().length in 1..SessionRules.MAX_INTENTION_LENGTH)
                    require(session.durationMinutes in 1..SessionRules.MAX_DURATION_MINUTES)
                    require(session.startedElapsed >= 0)
                    require(session.deadlineElapsed == Math.addExact(session.startedElapsed, session.durationMinutes * 60_000L))
                    require(session.bootCount >= -1)
                    require((session.status == SessionStatus.ACTIVE) == (session.endedWall == null))
                    if (session.status == SessionStatus.ACTIVE) {
                        require(settings.protectionEnabled)
                        require(if (settings.mode == ProtectionMode.PHONE) session.packageName == null
                            else session.packageName != null && session.packageName in packages)
                    }
                }
            }
            require(sessions.map { it.id }.toSet().size == sessions.size)
            val activeScopes = sessions.filter { it.status == SessionStatus.ACTIVE }.map { it.scopeKey }
            require(activeScopes.toSet().size == activeScopes.size)
            return StoredState(AppState(settings, sessions, loaded = true), revision)
        } catch (exception: Exception) {
            throw StateFormatException(exception)
        }
    }

    fun encode(value: StoredState): String {
        val settings = value.state.settings
        return JSONObject().apply {
            put("version", 1)
            put("revision", value.revision)
            put("settings", JSONObject().apply {
                put("consentAccepted", settings.consentAccepted)
                put("protectionEnabled", settings.protectionEnabled)
                put("mode", settings.mode.name)
                put("protectedPackages", JSONArray(settings.protectedPackages.sorted()))
                put("endPhoneSessionOnLock", settings.endPhoneSessionOnLock)
                put("themeMode", settings.themeMode.name)
            })
            put("sessions", JSONArray().apply {
                value.state.sessions.forEach { session ->
                    put(JSONObject().apply {
                        put("id", session.id)
                        put("packageName", session.packageName ?: JSONObject.NULL)
                        put("appLabel", session.appLabel)
                        put("intention", session.intention)
                        put("durationMinutes", session.durationMinutes)
                        put("startedElapsed", session.startedElapsed)
                        put("deadlineElapsed", session.deadlineElapsed)
                        put("bootCount", session.bootCount)
                        put("startedWall", session.startedWall)
                        put("endedWall", session.endedWall ?: JSONObject.NULL)
                        put("status", session.status.name)
                    })
                }
            })
        }.toString()
    }

    private fun JSONObject.long(key: String): Long = when (val value = get(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> error("Expected integer")
    }

    private fun Long.toIntExact(): Int = Math.toIntExact(this)

    private fun JSONObject.nullableString(key: String): String? =
        if (get(key) == JSONObject.NULL) null else get(key) as String
}
