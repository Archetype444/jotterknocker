package app.olauncher

import android.content.Intent
import android.os.SystemClock
import android.provider.CalendarContract
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.olauncher.notifications.NoticeStore
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the unmodified official Fossify Calendar APK installed by the dedicated CI job. */
@RunWith(AndroidJUnit4::class)
class FossifyCalendarIntegrationTest {
    @Test fun scheduledFossifyRemindersCanBeOpenedAndSnoozedFromOurShade() {
        val context = ShadeTestTools.context
        val installed = runCatching { context.packageManager.getPackageInfo("org.fossify.calendar", 0) }.isSuccess
        val required = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("requireFossify") == "true"
        if (required) assertTrue("Official Fossify Calendar APK was not installed", installed)
        assumeTrue("Dedicated Fossify Calendar integration job only", installed)
        ShadeTestTools.prepare()
        val scenario = ShadeTestTools.launch()
        val device = UiDevice.getInstance(ShadeTestTools.instrumentation)
        val pkg = "org.fossify.calendar"
        val openTitle = "Open calendar reminder"
        val snoozeTitle = "Snooze calendar reminder"
        try {
            val launch = context.packageManager.getLaunchIntentForPackage(pkg)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            assertTrue(device.wait(Until.hasObject(By.pkg(pkg).depth(0)), 15_000))
            // The default Fossify reminder is ten minutes before the event. Both real alarms
            // will fire about 90 seconds from now, without changing the emulator clock.
            val start = System.currentTimeMillis() + 690_000
            for (title in listOf(openTitle, snoozeTitle)) {
                context.startActivity(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).setPackage(pkg)
                    .putExtra(CalendarContract.Events.TITLE, title)
                    .putExtra(CalendarContract.Events.DESCRIPTION, "A reminder created by the launcher integration test.")
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 3_600_000)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val editor = device.wait(Until.findObject(By.res(pkg, "event_title")), 15_000)
                assertNotNull("Fossify event editor did not open", editor)
                assertEquals(title, editor.text)
                device.pressBack() // Hide the editor keyboard.
                val save = device.wait(Until.findObject(By.res(pkg, "save")), 10_000)
                    ?: device.wait(Until.findObject(By.desc("Save")), 3000)
                assertNotNull("Fossify save button missing", save)
                save.click()
                device.wait(Until.findObject(By.res("android", "button1")), 2500)?.click() // First-use reminder notice.
                assertTrue("Fossify did not save the event", device.wait(Until.gone(By.res(pkg, "event_title")), 10_000))
            }
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ShadeTestTools.waitFor("Fossify's scheduled notifications never arrived", 140_000) {
                val titles = NoticeStore.notices.value.filter { it.packageName == pkg }.map { it.title }
                openTitle in titles && snoozeTitle in titles
            }
            val reminder = NoticeStore.notices.value.first { it.packageName == pkg && it.title == snoozeTitle }
            assertNotNull(reminder.contentIntent)
            assertTrue("Fossify Snooze action was lost", reminder.actions.any { it.title.toString().contains("Snooze", true) })
            scenario.onActivity { it.openNotificationShade() }
            ShadeTestTools.instrumentation.waitForIdleSync()
            ShadeTestTools.screenshot("jotterknocker-fossify-preview.png")
            val open = device.wait(Until.findObject(By.res(context.packageName, "shadeNoticeTitle").text(openTitle)), 8000)
            assertNotNull("Calendar reminder not rendered", open)
            open.click()
            val eventTitle = device.wait(Until.findObject(By.res(pkg, "event_title")), 12_000)
            assertNotNull("Tapping the reminder did not open Fossify's event", eventTitle)
            assertEquals(openTitle, eventTitle.text)
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ShadeTestTools.waitFor("Home did not regain focus") { var focus = false; scenario.onActivity { focus = it.hasWindowFocus() }; focus }
            scenario.onActivity { it.openNotificationShade() }
            val snooze = device.wait(Until.findObject(By.text("Snooze")), 8000)
            assertNotNull("Snooze button not rendered", snooze)
            snooze.click()
            val fiveMinutes = device.wait(Until.findObject(By.text("5 minutes")), 8000)
            fiveMinutes?.click() // Depending on Calendar preferences, it can snooze immediately.
            ShadeTestTools.waitFor("Fossify did not clear its reminder after snoozing", 15_000) {
                NoticeStore.notices.value.none { it.key == reminder.key }
            }
        } finally {
            ShadeTestTools.screenshot("jotterknocker-fossify-final.png")
            scenario.close()
        }
    }
}
