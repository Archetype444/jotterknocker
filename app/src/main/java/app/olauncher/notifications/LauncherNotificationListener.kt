package app.olauncher.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ShadeNotice(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val time: Long,
    val clearable: Boolean,
    val group: String,
    val summary: Boolean,
    val media: Boolean,
    val autoCancel: Boolean,
    val contentIntent: PendingIntent?,
    val actions: List<Notification.Action>,
) {
    companion object {
        fun from(context: Context, sbn: StatusBarNotification): ShadeNotice {
            val n = sbn.notification
            val label = runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
            }.getOrDefault(sbn.packageName)
            fun extra(name: String) = runCatching { n.extras.getCharSequence(name)?.toString() }.getOrNull()
            val messaging = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) }.getOrNull()
            val body = extra(Notification.EXTRA_BIG_TEXT)?.takeIf { it.isNotBlank() }
                ?: extra(Notification.EXTRA_TEXT)
                ?: messaging?.messages?.lastOrNull()?.text?.toString()
                ?: ""
            return ShadeNotice(sbn.key, sbn.packageName, label,
                (extra(Notification.EXTRA_TITLE) ?: label).take(500), body.take(4000), sbn.postTime,
                sbn.isClearable, sbn.groupKey, n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
                n.category == Notification.CATEGORY_TRANSPORT || n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION),
                n.flags and Notification.FLAG_AUTO_CANCEL != 0, n.contentIntent,
                n.actions?.filter { it.remoteInputs.isNullOrEmpty() && it.actionIntent != null } ?: emptyList())
        }
    }
}

/** Only current Android notifications, kept in memory. No message history or network access. */
object NoticeStore {
    private val _notices = MutableStateFlow<List<ShadeNotice>>(emptyList())
    val notices = _notices.asStateFlow()
    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()
    internal fun replace(items: List<ShadeNotice>) { _notices.value = items.sortedByDescending { it.time } }
    internal fun connected(value: Boolean) { _connected.value = value; if (!value) replace(emptyList()) }
    fun visible(items: List<ShadeNotice>): List<ShadeNotice> = items.filter { notice ->
        !notice.summary || items.none { !it.summary && it.packageName == notice.packageName && it.group == notice.group }
    }
}

/** Inspired by inkOS's notification listener/media-session separation (GPLv3). */
class LauncherNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        refresh()
        NoticeStore.connected(true)
        ShadeMedia.connect(applicationContext, ComponentName(this, LauncherNotificationListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification) = refresh()

    private fun refresh() {
        runCatching {
            NoticeStore.replace(activeNotifications.orEmpty().mapNotNull { sbn ->
                runCatching { ShadeNotice.from(this, sbn) }.getOrNull()
            })
        }
    }

    override fun onListenerDisconnected() {
        disconnect()
        super.onListenerDisconnected()
        if (NotificationAccess.granted(this)) runCatching {
            requestRebind(ComponentName(this, LauncherNotificationListener::class.java))
        }
    }
    override fun onDestroy() { disconnect(); super.onDestroy() }
    private fun disconnect() {
        if (instance === this) {
            instance = null
            NoticeStore.connected(false)
            ShadeMedia.disconnect()
        }
    }

    companion object {
        private var instance: LauncherNotificationListener? = null
        fun dismiss(keys: Collection<String>) {
            val service = instance ?: return
            runCatching {
                val allowed = service.activeNotifications.orEmpty().filter { it.key in keys && it.isClearable }.map { it.key }
                if (allowed.isNotEmpty()) service.cancelNotifications(allowed.toTypedArray())
            }
        }
    }
}
