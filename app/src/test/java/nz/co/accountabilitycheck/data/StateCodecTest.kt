package nz.co.accountabilitycheck.data

import nz.co.accountabilitycheck.domain.AppSettings
import nz.co.accountabilitycheck.domain.AppState
import nz.co.accountabilitycheck.domain.ProtectionMode
import nz.co.accountabilitycheck.domain.SessionRecord
import nz.co.accountabilitycheck.domain.SessionStatus
import nz.co.accountabilitycheck.domain.ThemeMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StateCodecTest {
    private val active = SessionRecord(
        id = "session-1",
        packageName = "example.instagram",
        appLabel = "Instagram",
        intention = "Share a picture of whānau 🏡",
        durationMinutes = 2,
        startedElapsed = 9_000_000_000L,
        deadlineElapsed = 9_000_120_000L,
        bootCount = 8,
        startedWall = 1_800_000_000_000L,
    )
    private val stored = StoredState(
        AppState(
            settings = AppSettings(true, true, ProtectionMode.APPS, setOf("example.instagram")),
            sessions = listOf(active),
            loaded = true,
        ),
        revision = 23,
    )

    @Test fun `a missing value means a first install with protection off`() {
        assertEquals(StoredState(AppState(loaded = true), 0), StateCodec.decode(null))
    }

    @Test fun `serialization preserves unicode long deadlines nulls and all statuses`() {
        val source = stored.copy(state = stored.state.copy(sessions = listOf(active) +
            SessionStatus.entries.filter { it != SessionStatus.ACTIVE }.map { status ->
                active.copy(id = status.name, packageName = null, status = status, endedWall = active.startedWall - 10_000)
            }))
        assertEquals(source, StateCodec.decode(StateCodec.encode(source)))
    }

    @Test fun `phone mode and an active phone session round trip`() {
        val source = stored.copy(state = stored.state.copy(
            settings = stored.state.settings.copy(mode = ProtectionMode.PHONE),
            sessions = listOf(active.copy(packageName = null)),
        ))
        assertEquals(source, StateCodec.decode(StateCodec.encode(source)))
    }

    @Test fun `lock preference persists and older saved state uses keep running`() {
        val selected = stored.copy(state = stored.state.copy(
            settings = stored.state.settings.copy(endPhoneSessionOnLock = true),
        ))
        assertEquals(selected, StateCodec.decode(StateCodec.encode(selected)))
        val oldJson = JSONObject(StateCodec.encode(stored)).apply {
            getJSONObject("settings").remove("endPhoneSessionOnLock")
        }.toString()
        assertEquals(stored, StateCodec.decode(oldJson))
    }

    @Test fun `theme preference persists and older saved state follows the system`() {
        for (mode in ThemeMode.entries) {
            val selected = stored.copy(state = stored.state.copy(
                settings = stored.state.settings.copy(themeMode = mode),
            ))
            assertEquals(selected, StateCodec.decode(StateCodec.encode(selected)))
        }
        val oldJson = JSONObject(StateCodec.encode(stored)).apply {
            getJSONObject("settings").remove("themeMode")
        }.toString()
        assertEquals(ThemeMode.SYSTEM, StateCodec.decode(oldJson).state.settings.themeMode)
        assertMalformed { it.getJSONObject("settings").put("themeMode", "UNKNOWN") }
    }

    @Test fun `malformed and partial data fail instead of returning default settings`() {
        for (value in listOf("", "{", "{}", "null", "[]", StateCodec.encode(stored).dropLast(4))) {
            val error = assertThrows(StateFormatException::class.java) { StateCodec.decode(value) }
            assertTrue(error.message!!.contains("preserve your data"))
        }
    }

    @Test fun `unknown schema revisions and invalid revision numbers fail`() {
        assertMalformed { it.put("version", 2) }
        assertMalformed { it.put("revision", -1) }
        assertMalformed { it.put("revision", 1.5) }
        assertMalformed { it.put("revision", "23") }
    }

    @Test fun `enabled protection without disclosure fails`() {
        assertMalformed { it.getJSONObject("settings").put("consentAccepted", false) }
        assertMalformed { it.getJSONObject("settings").put("consentAccepted", "true") }
    }

    @Test fun `invalid session timing and truncated integers fail`() {
        assertMalformedSession { it.put("durationMinutes", 0) }
        assertMalformedSession { it.put("durationMinutes", 121) }
        assertMalformedSession { it.put("durationMinutes", 4_294_967_298L) }
        assertMalformedSession { it.put("startedElapsed", -1) }
        assertMalformedSession { it.put("deadlineElapsed", active.deadlineElapsed + 1) }
        assertMalformedSession { it.put("bootCount", -2) }
        assertMalformedSession { it.put("endedWall", active.startedWall) }
        assertMalformedSession { it.put("status", "EXPIRED") }
    }

    @Test fun `invalid purposes and unknown session statuses fail`() {
        assertMalformedSession { it.put("intention", " ") }
        assertMalformedSession { it.put("intention", "x".repeat(201)) }
        assertMalformedSession { it.put("status", "UNKNOWN") }
    }

    @Test fun `duplicate identifiers or active scopes fail`() {
        assertMalformed { root ->
            root.getJSONArray("sessions").put(JSONObject(root.getJSONArray("sessions").getJSONObject(0).toString()))
        }
        assertMalformed { root ->
            root.getJSONArray("sessions").put(JSONObject(root.getJSONArray("sessions").getJSONObject(0).toString()).put("id", "duplicate-scope"))
        }
    }

    @Test fun `active records inconsistent with settings fail`() {
        assertMalformed { it.getJSONObject("settings").put("protectionEnabled", false) }
        assertMalformed { it.getJSONObject("settings").put("mode", "PHONE") }
        assertMalformed { it.getJSONObject("settings").put("protectedPackages", JSONArray()) }
    }

    private fun assertMalformedSession(update: (JSONObject) -> Unit) = assertMalformed {
        update(it.getJSONArray("sessions").getJSONObject(0))
    }

    private fun assertMalformed(update: (JSONObject) -> Unit) {
        val value = JSONObject(StateCodec.encode(stored)).also(update).toString()
        assertThrows(StateFormatException::class.java) { StateCodec.decode(value) }
    }
}
