package app.olauncher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.olauncher.data.Prefs
import app.olauncher.notifications.NoticeStore
import org.junit.Assert.assertTrue

object ShadeTestTools {
    val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val context get() = instrumentation.targetContext
    const val CHANNEL = "shade-fixtures"
    fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command)
    ).use { it.readBytes().toString(Charsets.UTF_8) }
    fun waitFor(message: String, timeout: Long = 12_000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) { if (condition()) return; SystemClock.sleep(80) }
        assertTrue(message, condition())
    }
    fun prepare() {
        Prefs(context).apply {
            firstOpen = false; firstSettingsOpen = false; hideSetDefaultLauncher = true
            appTheme = AppCompatDelegate.MODE_NIGHT_NO; showStatusBar = true; lockModeOn = false
            textSizeScale = 1f; scratchpadPages = 1; homePages = 1; homePage = 0
            scratchpadText = (1..200).joinToString("\n") { "Note line $it" }
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancelAll()
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Test notifications", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        grantListener()
    }
    fun grantListener() {
        val component = "${context.packageName}/app.olauncher.notifications.LauncherNotificationListener"
        shell("cmd notification allow_listener $component")
        val old = shell("settings get secure enabled_notification_listeners").trim().takeUnless { it == "null" }.orEmpty()
        val value = (old.split(':').map { it.trim('\'', '"') }.filter { it.isNotBlank() } + component).distinct().joinToString(":")
        shell("settings put secure enabled_notification_listeners $value")
        waitFor("Notification listener never connected") { NoticeStore.connected.value }
    }
    fun launch(): ActivityScenario<MainActivity> = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).also { scenario ->
        // RESUMED does not mean that WindowManager has completed the transition.
        // In particular Android 16 can deliver window focus well after the first idle.
        waitFor("Home did not become focused and laid out", timeout = 25_000) {
            var ready = false
            scenario.onActivity { activity ->
                ready = activity.isNotificationHomeVisible && activity.window.decorView.isLaidOut
            }
            ready
        }
        instrumentation.waitForIdleSync()
    }
    fun screenshot(name: String) { shell("screencap -p /data/local/tmp/$name") }
}
