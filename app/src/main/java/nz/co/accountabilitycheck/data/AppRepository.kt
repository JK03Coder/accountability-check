package nz.co.accountabilitycheck.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nz.co.accountabilitycheck.domain.AppSettings
import nz.co.accountabilitycheck.domain.AppState
import nz.co.accountabilitycheck.domain.ClockSnapshot
import nz.co.accountabilitycheck.domain.ProtectionMode
import nz.co.accountabilitycheck.domain.SessionRecord
import nz.co.accountabilitycheck.domain.SessionRules
import nz.co.accountabilitycheck.domain.SessionStatus
import nz.co.accountabilitycheck.domain.ThemeMode

private val Context.accountabilityDataStore by preferencesDataStore(name = "accountability_state")

class AppRepository(context: Context) {
    private val applicationContext = context.applicationContext
    private val dataStore = applicationContext.accountabilityDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val stateKey = stringPreferencesKey("state_v1")
    private val mutableState = MutableStateFlow(AppState())
    private var appliedRevision = -1L

    val state: StateFlow<AppState> = mutableState.asStateFlow()

    init {
        scope.launch {
            dataStore.data.catch { failure ->
                mutex.withLock { reportStorageFailure(failure) }
            }.collect { preferences ->
                mutex.withLock {
                    try {
                        val stored = StateCodec.decode(preferences[stateKey])
                        // An initial read queued before a write must not overwrite its newer state.
                        if (stored.revision >= appliedRevision) {
                            appliedRevision = stored.revision
                            mutableState.value = stored.state
                        }
                    } catch (failure: StateFormatException) {
                        reportStorageFailure(failure)
                    }
                }
            }
        }
    }

    suspend fun acceptDisclosure() = changeSettings { it.copy(consentAccepted = true) }

    suspend fun setProtectionEnabled(enabled: Boolean) =
        changeSettings { it.copy(protectionEnabled = enabled) }

    suspend fun setProtectedPackages(packages: Set<String>) {
        require(packages.none { it.isBlank() }) { "App package names cannot be empty." }
        changeSettings { it.copy(protectedPackages = packages.toSet()) }
    }

    suspend fun setMode(mode: ProtectionMode) = changeSettings { it.copy(mode = mode) }

    suspend fun setThemeMode(mode: ThemeMode) = changeSettings { it.copy(themeMode = mode) }

    suspend fun setEndPhoneSessionOnLock(enabled: Boolean) =
        changeSettings { it.copy(endPhoneSessionOnLock = enabled) }

    suspend fun startSession(
        packageName: String?,
        appLabel: String,
        intention: String,
        minutes: Int,
    ): SessionRecord = mutate { current ->
        val started = SessionRules.start(
            settings = current.settings,
            sessions = current.sessions,
            packageName = packageName,
            appLabel = appLabel,
            intention = intention,
            minutes = minutes,
            now = clock(),
            id = UUID.randomUUID().toString(),
        )
        current.copy(sessions = started.sessions) to started.session
    }

    suspend fun reconcile(): List<SessionRecord> = mutate { current ->
        val reconciled = SessionRules.reconcile(current.sessions, clock())
        current.copy(sessions = reconciled.sessions) to reconciled.newlyExpired
    }

    suspend fun endPhoneSessionOnLock() = mutate { current ->
        current.copy(sessions = SessionRules.interruptPhoneSession(current.sessions, clock())) to Unit
    }

    /** Clearing history leaves active sessions intact so it cannot reset their deadlines. */
    suspend fun clearHistory() = mutate { current ->
        val sessions = SessionRules.reconcile(current.sessions, clock()).sessions
        current.copy(sessions = sessions.filter { it.status == SessionStatus.ACTIVE }) to Unit
    }

    private suspend fun changeSettings(update: (AppSettings) -> AppSettings) = mutate { current ->
        SessionRules.changeSettings(current, update(current.settings), clock()) to Unit
    }

    private data class MutationResult<T>(val value: T)

    private suspend fun <T> mutate(transform: (AppState) -> Pair<AppState, T>): T = mutex.withLock {
        var result: MutationResult<T>? = null
        var committed: StoredState? = null
        try {
            dataStore.edit { preferences ->
                // Always decode the authoritative value; never replace unreadable data with defaults.
                val original = StateCodec.decode(preferences[stateKey])
                val (updated, value) = transform(original.state)
                val next = if (updated == original.state) original else {
                    StoredState(updated.copy(loaded = true, error = null), Math.addExact(original.revision, 1))
                        .also { preferences[stateKey] = StateCodec.encode(it) }
                }
                committed = next
                result = MutationResult(value)
            }
            // edit completes only after the write is durable. Publish after that point.
            val saved = checkNotNull(committed)
            appliedRevision = saved.revision
            mutableState.value = saved.state
            checkNotNull(result).value
        } catch (failure: IOException) {
            reportStorageFailure(failure)
            throw failure
        }
    }

    private fun clock(): ClockSnapshot = ClockSnapshot(
        elapsedRealtime = SystemClock.elapsedRealtime(),
        bootCount = try {
            Settings.Global.getInt(applicationContext.contentResolver, Settings.Global.BOOT_COUNT, -1)
        } catch (_: SecurityException) {
            -1
        },
        wallTimeMillis = System.currentTimeMillis(),
    )

    private fun reportStorageFailure(failure: Throwable) {
        mutableState.value = mutableState.value.copy(
            loaded = false,
            error = if (failure is StateFormatException) failure.message else
                "Saved data is unavailable. Protection is paused. Check available storage and reopen the app.",
        )
    }
}
