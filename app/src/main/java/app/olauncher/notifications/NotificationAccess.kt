package app.olauncher.notifications

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import app.olauncher.R
import android.widget.Toast

object NotificationAccess {
    fun granted(context: Context): Boolean {
        val component = ComponentName(context, LauncherNotificationListener::class.java)
        return Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
    }
    fun edgeGranted(context: Context): Boolean {
        val component = ComponentName(context, ShadeEdgeService::class.java)
        return Settings.Secure.getString(context.contentResolver, "enabled_accessibility_services")
            .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
    }
    fun open(activity: Activity, edge: Boolean) {
        val intent = if (edge && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS).putExtra(Intent.EXTRA_COMPONENT_NAME,
                ComponentName(activity, ShadeEdgeService::class.java))
        } else Intent(if (edge) Settings.ACTION_ACCESSIBILITY_SETTINGS else Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        runCatching { activity.startActivity(intent) }.onFailure {
            runCatching { activity.startActivity(Intent(if (edge) Settings.ACTION_ACCESSIBILITY_SETTINGS else Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                .onFailure { Toast.makeText(activity, R.string.shade_settings_unavailable, Toast.LENGTH_LONG).show() }
        }
    }
}
