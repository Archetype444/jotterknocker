package app.olauncher

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.olauncher.data.Prefs
import app.olauncher.helper.CheckboxSpan
import app.olauncher.helper.LinkSpan
import app.olauncher.ui.MarkdownEditText
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android touch dispatch through HomeFragment's listeners and the text widget. */
@RunWith(AndroidJUnit4::class)
class NoteInteractionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val appNames = listOf("Browser", "Files", "Calendar", "Messages", "Photos", "Music", "Maps", "Mail", "Notes", "Camera", "Contacts", "Clock", "Settings", "Reader", "Tasks")
    private val note = "# A quiet place to think\n\n- [ ] Keep the note still\n\n[Example](https://example.com)\n\n" +
        (1..400).joinToString("\n") { "Line $it — a long note for smooth scrolling." }

    @Before fun openNote() {
        val context = instrumentation.targetContext
        Prefs(context).apply {
            firstOpen = false
            firstSettingsOpen = false
            hideSetDefaultLauncher = false
            scratchpadText = note
            scratchpadPages = 1
            homePages = 1
            homePage = 0
            homeAppsNum = 15
            homeColumns = 3
            scratchpadTextScale = 1f
            appSpacing = 1f
            appTextScale = 1f
            homeBottomMargin = 56
            homeAlignment = Gravity.START
            clockMargin = 0
            dateMargin = 0
            appTheme = AppCompatDelegate.MODE_NIGHT_YES
            dateTimeVisibility = 1
            dateTimeTop = false
            recentAppsColumn = 0
            showStatusBar = true
            lockModeOn = false
            appNames.forEachIndexed { index, name -> setHomeApp(index + 1, name, "com.android.settings", "", null, null) }
        }
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        instrumentation.waitForIdleSync()
        // Let the first home layout transition finish before measuring touch motion.
        SystemClock.sleep(350)
        onNote { activity, pad -> activity.findViewById<View>(R.id.mainLayout).requestFocus(); pad.scrollTo(0, 0) }
    }

    @After fun closeNote() { scenario.close() }

    private fun onNote(action: (MainActivity, MarkdownEditText) -> Unit) {
        scenario.onActivity { action(it, it.findViewById(R.id.scratchpad)) }
    }

    private fun dispatch(pad: MarkdownEditText, action: Int, x: Float, y: Float, downTime: Long, eventTime: Long) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { pad.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun swipe(up: Boolean = true, pauseAtEnd: Boolean = false) {
        onNote { _, pad ->
            val x = pad.width * 0.6f
            val viewport = pad.height - pad.totalPaddingTop - pad.totalPaddingBottom
            val start = pad.totalPaddingTop + viewport * if (up) 0.85f else 0.15f
            val end = pad.totalPaddingTop + viewport * if (up) 0.15f else 0.85f
            val steps = 24
            val upTime = SystemClock.uptimeMillis()
            // Use device event times. Scheduling one UI call per event can invent long presses on CI.
            val down = upTime - steps * 18L - if (pauseAtEnd) 180L else 8L
            dispatch(pad, MotionEvent.ACTION_DOWN, x, start, down, down)
            for (i in 1..steps) dispatch(pad, MotionEvent.ACTION_MOVE, x, start + (end - start) * i / steps, down, down + 18L * i)
            dispatch(pad, MotionEvent.ACTION_UP, x, end, down, upTime)
            if (pauseAtEnd) assertFalse("Lifting after a pause started a fling", pad.isReadingScrollActive)
        }
    }

    private fun tap(x: Float, y: Float) {
        onNote { _, pad ->
            val now = SystemClock.uptimeMillis()
            dispatch(pad, MotionEvent.ACTION_DOWN, x, y, now - 40, now - 40)
            dispatch(pad, MotionEvent.ACTION_UP, x, y, now - 40, now)
        }
    }

    private fun assertReading() {
        onNote { _, pad ->
            assertFalse("Scrolling focused the editor", pad.isFocused)
            assertEquals(note, pad.text.toString())
            assertFalse("Scrolling opened the keyboard", ViewCompat.getRootWindowInsets(pad)?.isVisible(WindowInsetsCompat.Type.ime()) == true)
        }
    }

    @Test fun fifteenAppsStayInThreeColumnsOfFiveBelowTheNote() {
        val preview = instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/jotterknocker-preview.png")
        ParcelFileDescriptor.AutoCloseInputStream(preview).use { it.readBytes() }
        onNote { activity, pad ->
            val grid = activity.findViewById<LinearLayout>(R.id.homeAppsGrid)
            val controls = activity.findViewById<View>(R.id.homeControls)
            val setupPrompt = activity.findViewById<View>(R.id.setDefaultLauncher)
            val promptBounds = Rect().takeIf { setupPrompt.isShown && setupPrompt.getGlobalVisibleRect(it) }
            val rootBounds = Rect().also { pad.rootView.getGlobalVisibleRect(it) }
            val noteBounds = Rect().also { pad.getGlobalVisibleRect(it) }
            val controlBounds = Rect().also { controls.getGlobalVisibleRect(it) }
            assertEquals(3, grid.childCount)
            assertTrue("Note has no reading space: root=$rootBounds, note=$noteBounds, controls=$controlBounds, padding=${pad.totalPaddingTop}",
                pad.height > pad.totalPaddingTop + pad.totalPaddingBottom + pad.textSize * 2)
            assertTrue("Apps overlap the note", controlBounds.top >= noteBounds.bottom)
            repeat(3) { c ->
                val column = grid.getChildAt(c) as LinearLayout
                assertEquals(5, column.childCount)
                repeat(5) { row ->
                    val label = column.getChildAt(row) as TextView
                    assertEquals(appNames[c * 5 + row], label.text.toString())
                    val bounds = Rect().also { assertTrue(label.getGlobalVisibleRect(it)) }
                    assertEquals("App label was clipped", label.height, bounds.height())
                    assertTrue(bounds.bottom <= rootBounds.bottom)
                    if (promptBounds != null) assertTrue("Setup prompt overlaps an app label", bounds.bottom <= promptBounds.top)
                }
            }
        }
    }

    @Test fun slowDragAndReverseStayInReadingMode() {
        swipe(pauseAtEnd = true)
        var first = 0
        onNote { _, pad -> first = pad.scrollY; assertTrue(first > 0) }
        assertReading()
        swipe(up = false, pauseAtEnd = true)
        onNote { _, pad -> assertTrue(pad.scrollY < first) }
        assertReading()
    }

    private fun flickUp(pad: MarkdownEditText): Int {
        // Dispatch the flick in one UI call so emulator delays don't turn it into a paused drag.
        val x = pad.width * 0.6f
        val viewport = pad.height - pad.totalPaddingTop - pad.totalPaddingBottom
        assertTrue("Flick needs a visible note, viewport=$viewport", viewport > 0)
        val start = pad.totalPaddingTop + viewport * 0.85f
        val end = pad.totalPaddingTop + viewport * 0.15f
        val down = SystemClock.uptimeMillis() - 48L
        dispatch(pad, MotionEvent.ACTION_DOWN, x, start, down, down)
        for (i in 1..5) dispatch(pad, MotionEvent.ACTION_MOVE, x, start + (end - start) * i / 5, down, down + 8L * i)
        dispatch(pad, MotionEvent.ACTION_UP, x, end, down, down + 48L)
        assertTrue("Flick did not start a fling at ${pad.scrollY} of ${pad.maximumNoteScroll}", pad.isReadingScrollActive)
        return pad.scrollY
    }

    @Test fun fastFlingCoastsWithoutOpeningTheEditor() {
        var atRelease = 0
        onNote { _, pad -> atRelease = flickUp(pad) }
        SystemClock.sleep(250)
        onNote { _, pad -> assertTrue("No inertial scrolling after release: $atRelease to ${pad.scrollY}", pad.scrollY > atRelease + 30) }
        assertReading()
    }

    @Test fun touchingAnActiveFlingStopsItWithoutOpeningTheEditor() {
        var x = 0f; var y = 0f
        var stopped = 0
        onNote { _, pad ->
            stopped = flickUp(pad)
            x = pad.width * 0.6f
            y = (pad.height + pad.totalPaddingTop - pad.totalPaddingBottom) / 2f
            val now = SystemClock.uptimeMillis()
            dispatch(pad, MotionEvent.ACTION_DOWN, x, y, now, now)
            dispatch(pad, MotionEvent.ACTION_UP, x, y, now, now + 10L)
            assertFalse("Tap didn't stop the fling", pad.isReadingScrollActive)
            assertFalse("Tap during momentum entered the editor", pad.isFocused)
        }
        SystemClock.sleep(200)
        onNote { _, pad -> assertEquals(stopped, pad.scrollY) }
        assertReading()
        tap(x, y)
        onNote { _, pad -> assertTrue("A deliberate tap must still edit", pad.isFocused) }
    }

    @Test fun dragEndingOnACheckboxNeverTogglesIt() {
        onNote { _, pad ->
            val span = pad.text!!.getSpans(0, pad.length(), CheckboxSpan::class.java).first()
            val offset = pad.text!!.getSpanStart(span)
            val line = pad.layout.getLineForOffset(offset)
            val x = pad.totalPaddingLeft + pad.layout.getPrimaryHorizontal(offset) + pad.textSize / 2
            val y = pad.totalPaddingTop + (pad.layout.getLineTop(line) + pad.layout.getLineBottom(line)) / 2f
            val now = SystemClock.uptimeMillis()
            val down = now - 200
            dispatch(pad, MotionEvent.ACTION_DOWN, x, y + 120, down, down)
            dispatch(pad, MotionEvent.ACTION_MOVE, x, y, down, down + 20)
            dispatch(pad, MotionEvent.ACTION_UP, x, y, down, now)
        }
        assertReading()
    }

    @Test fun checkboxTapTogglesWithoutStartingEditing() {
        var x = 0f; var y = 0f
        onNote { _, pad ->
            val span = pad.text!!.getSpans(0, pad.length(), CheckboxSpan::class.java).first()
            val offset = pad.text!!.getSpanStart(span)
            val line = pad.layout.getLineForOffset(offset)
            x = pad.totalPaddingLeft + (pad.layout.getPrimaryHorizontal(offset) + pad.layout.getPrimaryHorizontal(pad.text!!.getSpanEnd(span))) / 2
            y = pad.totalPaddingTop + (pad.layout.getLineTop(line) + pad.layout.getLineBottom(line)) / 2f
        }
        tap(x, y)
        onNote { _, pad ->
            assertFalse(pad.isFocused)
            assertTrue(pad.text.toString().contains("- [x] Keep the note still"))
        }
    }

    @Test fun linkTapOpensOnceWithoutStartingEditing() {
        val filter = IntentFilter(Intent.ACTION_VIEW).apply { addCategory(Intent.CATEGORY_BROWSABLE); addDataScheme("https") }
        val monitor = instrumentation.addMonitor(filter, Instrumentation.ActivityResult(Activity.RESULT_OK, null), true)
        try {
            var x = 0f; var y = 0f
            onNote { _, pad ->
                val span = pad.text!!.getSpans(0, pad.length(), LinkSpan::class.java).first()
                val offset = pad.text!!.getSpanStart(span)
                val line = pad.layout.getLineForOffset(offset)
                x = pad.totalPaddingLeft + pad.layout.getPrimaryHorizontal(offset) + pad.textSize
                y = pad.totalPaddingTop + (pad.layout.getLineTop(line) + pad.layout.getLineBottom(line)) / 2f
            }
            tap(x, y)
            assertEquals(1, monitor.hits)
            onNote { _, pad -> assertFalse(pad.isFocused) }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun pinchNeverOpensTheEditorOrChangesTheNote() {
        onNote { _, pad ->
            val x = pad.width / 2f
            val y = (pad.totalPaddingTop + pad.height) / 2f
            val down = SystemClock.uptimeMillis() - 200
            dispatch(pad, MotionEvent.ACTION_DOWN, x - 90, y, down, down)
            fun multi(action: Int, distance: Float, elapsed: Long) {
                val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
                    MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER })
                val coordinates = arrayOf(MotionEvent.PointerCoords().apply { this.x = x - distance; this.y = y; pressure = 1f; size = 1f },
                    MotionEvent.PointerCoords().apply { this.x = x + distance; this.y = y; pressure = 1f; size = 1f })
                val event = MotionEvent.obtain(down, down + elapsed, action, 2, properties, coordinates,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                pad.dispatchTouchEvent(event)
                event.recycle()
            }
            multi(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 90f, 20)
            for (i in 1..8) multi(MotionEvent.ACTION_MOVE, 90f + i * 12, 20L + i * 18L)
            multi(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 186f, 180)
            dispatch(pad, MotionEvent.ACTION_UP, x - 186, y, down, down + 200)
        }
        assertReading()
    }

    @Test fun aLongPressStillOpensSettingsAndCancelledPressDoesNot() {
        var x = 0f; var y = 0f
        onNote { _, pad ->
            x = pad.width * 0.8f; y = pad.totalPaddingTop + 20f
            val now = SystemClock.uptimeMillis()
            dispatch(pad, MotionEvent.ACTION_DOWN, x, y, now, now)
            dispatch(pad, MotionEvent.ACTION_CANCEL, x, y, now, now)
        }
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 80)
        onNote { _, pad ->
            assertFalse(pad.isFocused)
            val now = SystemClock.uptimeMillis()
            dispatch(pad, MotionEvent.ACTION_DOWN, x, y, now, now)
        }
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150)
        instrumentation.waitForIdleSync()
        scenario.onActivity {
            val nav = androidx.navigation.Navigation.findNavController(it, R.id.nav_host_fragment)
            assertEquals(R.id.settingsFragment, nav.currentDestination?.id)
        }
    }

    @Test fun cancellationNeverEditsAndNeverMovesPastTheEnd() {
        swipe(pauseAtEnd = true)
        onNote { _, pad -> pad.scrollTo(0, pad.maximumNoteScroll) }
        swipe(pauseAtEnd = true)
        onNote { _, pad -> assertEquals(pad.maximumNoteScroll, pad.scrollY) }
        onNote { _, pad ->
            val now = SystemClock.uptimeMillis()
            dispatch(pad, MotionEvent.ACTION_DOWN, 150f, 200f, now, now)
            dispatch(pad, MotionEvent.ACTION_CANCEL, 150f, 200f, now, now)
        }
        assertReading()
    }

    @Test fun scrollbarFadesAndIsClippedInsideNote() {
        swipe(pauseAtEnd = true)
        fun barPixels(reveal: Boolean = false): Int {
            var count = 0
            onNote { _, pad ->
                if (reveal) pad.scrollTo(0, pad.scrollY + 10)
                val image = Bitmap.createBitmap(pad.width, pad.height, Bitmap.Config.ARGB_8888)
                pad.draw(Canvas(image))
                val x = (pad.resources.displayMetrics.density).toInt().coerceAtLeast(0)
                for (y in 0 until image.height) {
                    if ((image.getPixel(x, y) ushr 24) != 0) {
                        assertTrue("Scrollbar escaped the text viewport", y >= pad.totalPaddingTop && y < pad.height - pad.totalPaddingBottom)
                        count++
                    }
                }
                image.recycle()
            }
            return count
        }
        assertTrue("Scrollbar never appeared", barPixels(reveal = true) > 0)
        SystemClock.sleep(450)
        assertEquals("Scrollbar did not fade", 0, barPixels())
    }

    @Test fun largeNoteRemainsScrollableWhenTheHomeScreenIsRecreated() {
        var portraitLocked = false
        scenario.onActivity { portraitLocked = it.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        // Phones deliberately stay in portrait. Exercise the same lifecycle by recreating the
        // screen there; on a larger device verify the actual landscape layout.
        if (portraitLocked) scenario.recreate() else {
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            val deadline = SystemClock.uptimeMillis() + 8000
            var landscape = false
            while (!landscape && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(100)
                scenario.onActivity { landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            }
            assertTrue("Landscape did not become available on a device that supports rotation", landscape)
        }
        SystemClock.sleep(350)
        onNote { activity, pad -> activity.findViewById<View>(R.id.mainLayout).requestFocus(); pad.scrollTo(0, 0) }
        swipe(pauseAtEnd = true)
        onNote { _, pad -> assertTrue("The note did not scroll after recreation", pad.scrollY > 0) }
        assertReading()
        if (!portraitLocked) scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
    }
}
