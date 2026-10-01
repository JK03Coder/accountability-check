package nz.co.accountabilitycheck.platform

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager

data class DeviceApp(val packageName: String, val label: String, val icon: Drawable)

/** Only queries launcher activities and explicit system roles. No broad package access. */
class AppCatalog(private val context: Context) {
    private val pm = context.packageManager
    val launcherPackages: Set<String> get() = packagesFor(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    )
    val keyboardPackages: Set<String> get() = context.getSystemService(InputMethodManager::class.java)
        .inputMethodList.map { it.packageName }.toSet()

    fun excludedPackages(): Set<String> = buildSet {
        add(context.packageName)
        add("android")
        add("com.android.systemui")
        add("com.android.settings")
        add("com.android.phone")
        add("com.android.server.telecom")
        add("com.android.emergency")
        add("com.android.permissioncontroller")
        add("com.google.android.permissioncontroller")
        addAll(launcherPackages)
        addAll(keyboardPackages)
        addAll(packagesFor(Intent(Settings.ACTION_SETTINGS)))
        addAll(packagesFor(Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))))
        addAll(packagesFor(Intent(Intent.ACTION_VIEW).setType("application/vnd.android.package-archive")))
        addAll(packagesFor(Intent("android.intent.action.MANAGE_PERMISSIONS")))
        context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let(::add)
    }

    fun launchableApps(): List<DeviceApp> {
        val excluded = excludedPackages()
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName !in excluded }
            .distinctBy { it.activityInfo.packageName }
            .map { DeviceApp(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
            .sortedBy { it.label.lowercase() }
    }

    private fun packagesFor(intent: Intent): Set<String> =
        pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }.toSet()
}
