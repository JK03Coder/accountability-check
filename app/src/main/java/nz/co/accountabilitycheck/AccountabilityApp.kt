package nz.co.accountabilitycheck

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import nz.co.accountabilitycheck.data.AppRepository
import nz.co.accountabilitycheck.platform.SessionNotifications
import nz.co.accountabilitycheck.service.ProtectionStatus

class AccountabilityApp : Application() {
    val repository: AppRepository by lazy { AppRepository(this) }
    val sessionNotifications: SessionNotifications by lazy { SessionNotifications(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            combine(repository.state, ProtectionStatus.connection) { state, connected -> state to connected }
                .collect { (state, connected) -> sessionNotifications.update(state, connected) }
        }
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                refreshSessionNotifications(clockChanged = true)
            }
        }, IntentFilter(Intent.ACTION_TIME_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun refreshSessionNotifications(clockChanged: Boolean = false) {
        sessionNotifications.update(repository.state.value, ProtectionStatus.connection.value, clockChanged)
    }
}
