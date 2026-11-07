package app.olauncher

import android.app.UiAutomation
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.UiDevice
import app.olauncher.notifications.NotificationAccess
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Injects at y=1 through Android's input system, rather than dispatching to an app view. */
@RunWith(AndroidJUnit4::class)
class TopEdgeServiceTest {
    @Test fun physicalTopEdgeOpensHomeShadeAndLeavesOtherAppsAlone() {
        ShadeTestTools.prepare()
        val scenario = ShadeTestTools.launch()
        val instrumentation = ShadeTestTools.instrumentation
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }
        val oldServices = shell("settings get secure enabled_accessibility_services").trim().takeUnless { it == "null" }.orEmpty()
        val oldEnabled = shell("settings get secure accessibility_enabled").trim().takeUnless { it == "null" } ?: "0"
        val component = "${ShadeTestTools.context.packageName}/app.olauncher.notifications.ShadeEdgeService"
        var completed = false
        try {
            val services = (oldServices.split(':').filter { it.isNotBlank() } + component).distinct().joinToString(":")
            shell("settings put secure enabled_accessibility_services $services")
            shell("settings put secure accessibility_enabled 1")
            ShadeTestTools.waitFor("Top-edge service was not enabled") { NotificationAccess.edgeGranted(ShadeTestTools.context) }
            // Wait for the actual service bind, not just the settings write.
            ShadeTestTools.waitFor("Home edge strip was not attached", timeout = 25_000) { app.olauncher.notifications.ShadeEdgeService.edgeAttached }
            instrumentation.waitForIdleSync()
            var width = 0
            scenario.onActivity { width = it.window.decorView.width; assertFalse(it.notificationShade.isOpen) }
            fun pull() {
                val down = SystemClock.uptimeMillis()
                for (step in 0..12) {
                    val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 12 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, width * 0.75f, 1f + step * 12f, 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue("Could not inject edge touch", automation.injectInputEvent(event, true)) } finally { event.recycle() }
                    SystemClock.sleep(16) // A real timed input stream, including Android's batching.
                }
            }
            pull()
            ShadeTestTools.waitFor("Physical top-edge pull did not open notifications") {
                var open = false; scenario.onActivity { open = it.notificationShade.isOpen }; open
            }
            SystemClock.sleep(650) // Let any late SystemUI takeover and bounded recovery finish.
            ShadeTestTools.waitFor("Android's stock shade covered the launcher panel") {
                var visible = false; scenario.onActivity { visible = it.notificationShade.isOpen && it.hasWindowFocus() }; visible
            }
            scenario.onActivity {
                it.notificationShade.close()
                it.startActivity(Intent(Settings.ACTION_SETTINGS))
            }
            ShadeTestTools.waitFor("Home edge strip leaked into another app") { !app.olauncher.notifications.ShadeEdgeService.edgeAttached }
            val device = UiDevice.getInstance(instrumentation)
            ShadeTestTools.waitFor("Android Settings did not take the foreground") { device.currentPackageName == "com.android.settings" }
            device.waitForIdle(1000)
            pull()
            scenario.onActivity { assertFalse("Top-edge gesture outside home opened the launcher shade", it.notificationShade.isOpen) }
            completed = true
        } finally {
            if (!completed) {
                android.util.Log.e("ShadeTest", shell("dumpsys window windows"))
                ShadeTestTools.screenshot("jotterknocker-notification-preview.png")
            }
            if (oldServices.isBlank()) shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services $oldServices")
            shell("settings put secure accessibility_enabled $oldEnabled")
            scenario.close()
        }
    }
}
