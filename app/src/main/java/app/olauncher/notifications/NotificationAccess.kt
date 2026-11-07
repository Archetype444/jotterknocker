package app.olauncher.notifications

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import app.olauncher.R
import android.widget.Toast

object NotificationAccess {
    fun granted(context: Context): Boolean {
        val component = ComponentName(context, LauncherNotificationListener::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 27) return runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .isNotificationListenerAccessGranted(component)
        }.getOrDefault(false)
        return Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
    }
    fun edgeGranted(context: Context): Boolean {
        val component = ComponentName(context, ShadeEdgeService::class.java)
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        return manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            val info = it.resolveInfo.serviceInfo
            ComponentName(info.packageName, info.name) == component
        }
    }
    fun open(activity: Activity, edge: Boolean) {
        val intent = Intent(if (edge) Settings.ACTION_ACCESSIBILITY_SETTINGS else Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        runCatching { activity.startActivity(intent) }.onFailure {
            Toast.makeText(activity, R.string.shade_settings_unavailable, Toast.LENGTH_LONG).show()
        }
    }
}
