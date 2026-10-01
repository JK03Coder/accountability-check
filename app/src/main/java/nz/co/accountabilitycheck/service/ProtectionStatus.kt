package nz.co.accountabilitycheck.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object ProtectionStatus {
    internal val connected = MutableStateFlow(false)
    internal val issue = MutableStateFlow<String?>(null)
    val connection: StateFlow<Boolean> = connected
    val lastIssue: StateFlow<String?> = issue

    fun isEnabled(context: Context): Boolean = context.getSystemService(AccessibilityManager::class.java)
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.resolveInfo.serviceInfo.packageName == context.packageName &&
            it.resolveInfo.serviceInfo.name == AccountabilityAccessibilityService::class.java.name }
}
