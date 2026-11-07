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
import androidx.test.uiautomator.Configurator
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
        // Keep UiDevice and shell screenshots on the same connection. Switching flags
        // disconnects UiAutomation and invalidates UiObject2 handles between taps.
        Configurator.getInstance().setUiAutomationFlags(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        // Exercise the launcher as HOME, matching the installed phone rather than
        // leaving Quickstep as the default home behind a separately launched task.
        shell("cmd package set-home-activity ${context.packageName}/app.olauncher.MainActivity")
        // Google's API 35+ images can retain a Pixel Launcher ANR dialog over the
        // new HOME app after repeated ActivityScenario transitions. Stop only
        // that unrelated emulator app; never dismiss an ANR of the app under test.
        if (Build.VERSION.SDK_INT >= 35) shell("am force-stop com.google.android.apps.nexuslauncher")
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
        scenario.onActivity { it.findViewById<android.view.View>(R.id.mainLayout).requestFocus() }
        try { waitFor("Home did not become focused and laid out", timeout = 25_000) {
            var ready = false
            scenario.onActivity { activity ->
                ready = activity.isNotificationHomeVisible && activity.window.decorView.isLaidOut
            }
            ready
        } } catch (error: AssertionError) {
            scenario.onActivity { activity ->
                android.util.Log.e("ShadeTest", "home=${activity.isNotificationHomeVisible}, focus=${activity.hasWindowFocus()}, lifecycle=${activity.lifecycle.currentState}, laidOut=${activity.window.decorView.isLaidOut}, destination=${androidx.navigation.Navigation.findNavController(activity, R.id.nav_host_fragment).currentDestination?.id}")
            }
            android.util.Log.e("ShadeTest", shell("dumpsys window windows"))
            screenshot("jotterknocker-notification-preview.png")
            scenario.close()
            throw error
        }
        instrumentation.waitForIdleSync()
    }
    fun screenshot(name: String) { shell("screencap -p /data/local/tmp/$name") }
}
