package app.olauncher.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Test acknowledgement receiver; never included in the release APK. */
class ShadeFixtureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.getSharedPreferences("shade-test", Context.MODE_PRIVATE).edit()
            .putString("received", intent.action).putString("event", intent.getStringExtra("event")).apply()
    }
}
