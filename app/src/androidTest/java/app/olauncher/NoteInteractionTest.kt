package app.olauncher

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import java.io.File

/** Real Android touch dispatch through HomeFragment's listeners and the text widget. */
@RunWith(AndroidJUnit4::class)
class NoteInteractionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private var gestureStart = 0L
    private val note = "# A quiet place to think\n\n- [ ] Keep the note still\n\n[Example](https://example.com)\n\n" +
        (1..400).joinToString("\n") { "Line $it — a long note for smooth scrolling." }

    @Before fun openNote() {
        val context = instrumentation.targetContext
        Prefs(context).apply {
            firstOpen = false
            firstSettingsOpen = false
            scratchpadText = note
            scratchpadPages = 1
            homePages = 1
            homeAppsNum = 15
            homeColumns = 3
            dateTimeVisibility = 0
            showStatusBar = true
            lockModeOn = false
        }
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        instrumentation.waitForIdleSync()
        onNote { activity, pad -> activity.findViewById<View>(R.id.mainLayout).requestFocus(); pad.scrollTo(0, 0) }
    }

    @After fun closeNote() { scenario.close() }

    private fun onNote(action: (MainActivity, MarkdownEditText) -> Unit) {
        scenario.onActivity { action(it, it.findViewById(R.id.scratchpad)) }
    }

    private fun send(action: Int, x: Float, y: Float) {
        if (action == MotionEvent.ACTION_DOWN) gestureStart = SystemClock.uptimeMillis()
        onNote { _, pad ->
            val event = MotionEvent.obtain(gestureStart, SystemClock.uptimeMillis(), action, x, y, 0)
            pad.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    private fun swipe(up: Boolean = true, fast: Boolean = false, pauseAtEnd: Boolean = false) {
        var x = 0f; var start = 0f; var end = 0f
        onNote { _, pad ->
            x = pad.width * 0.6f
            val viewport = pad.height - pad.totalPaddingTop - pad.totalPaddingBottom
            start = pad.totalPaddingTop + viewport * if (up) 0.85f else 0.15f
            end = pad.totalPaddingTop + viewport * if (up) 0.15f else 0.85f
        }
        send(MotionEvent.ACTION_DOWN, x, start)
        val steps = if (fast) 5 else 24
        for (i in 1..steps) {
            SystemClock.sleep(if (fast) 8 else 18)
            send(MotionEvent.ACTION_MOVE, x, start + (end - start) * i / steps)
        }
        if (pauseAtEnd) SystemClock.sleep(180)
        send(MotionEvent.ACTION_UP, x, end)
    }

    private fun tap(x: Float, y: Float) {
        send(MotionEvent.ACTION_DOWN, x, y)
        SystemClock.sleep(40)
        send(MotionEvent.ACTION_UP, x, y)
    }

    private fun assertReading() {
        onNote { _, pad ->
            assertFalse("Scrolling focused the editor", pad.isFocused)
            assertEquals(note, pad.text.toString())
            assertFalse("Scrolling opened the keyboard", ViewCompat.getRootWindowInsets(pad)?.isVisible(WindowInsetsCompat.Type.ime()) == true)
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

    @Test fun fastFlingCoastsAndTouchStopsItWithoutOpeningTheEditor() {
        swipe(fast = true)
        var atRelease = 0
        onNote { _, pad -> atRelease = pad.scrollY }
        SystemClock.sleep(250)
        onNote { _, pad -> assertTrue("No inertial scrolling after release", pad.scrollY > atRelease + 30) }
        assertReading()
        var x = 0f; var y = 0f
        onNote { _, pad -> x = pad.width * 0.6f; y = (pad.height + pad.totalPaddingTop) / 2f }
        tap(x, y)
        var stopped = 0
        onNote { _, pad -> stopped = pad.scrollY }
        SystemClock.sleep(200)
        onNote { _, pad -> assertEquals(stopped, pad.scrollY) }
        assertReading()
        tap(x, y)
        onNote { _, pad -> assertTrue("A deliberate tap must still edit", pad.isFocused) }
    }

    @Test fun dragEndingOnACheckboxNeverTogglesIt() {
        var x = 0f; var y = 0f
        onNote { _, pad ->
            val span = pad.text!!.getSpans(0, pad.length(), CheckboxSpan::class.java).first()
            val offset = pad.text!!.getSpanStart(span)
            val line = pad.layout.getLineForOffset(offset)
            x = pad.totalPaddingLeft + pad.layout.getPrimaryHorizontal(offset) + pad.textSize / 2
            y = pad.totalPaddingTop + (pad.layout.getLineTop(line) + pad.layout.getLineBottom(line)) / 2f
        }
        send(MotionEvent.ACTION_DOWN, x, y + 120)
        SystemClock.sleep(20)
        send(MotionEvent.ACTION_MOVE, x, y)
        SystemClock.sleep(180)
        send(MotionEvent.ACTION_UP, x, y)
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
        var x = 0f; var y = 0f
        onNote { _, pad -> x = pad.width / 2f; y = (pad.totalPaddingTop + pad.height) / 2f }
        send(MotionEvent.ACTION_DOWN, x - 90, y)
        fun multi(action: Int, distance: Float) {
            onNote { _, pad ->
                val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
                    MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER })
                val coordinates = arrayOf(MotionEvent.PointerCoords().apply { this.x = x - distance; this.y = y; pressure = 1f; size = 1f },
                    MotionEvent.PointerCoords().apply { this.x = x + distance; this.y = y; pressure = 1f; size = 1f })
                val event = MotionEvent.obtain(gestureStart, SystemClock.uptimeMillis(), action, 2, properties, coordinates,
                    0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                pad.dispatchTouchEvent(event)
                event.recycle()
            }
        }
        multi(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 90f)
        for (i in 1..8) { SystemClock.sleep(18); multi(MotionEvent.ACTION_MOVE, 90f + i * 12) }
        multi(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 186f)
        send(MotionEvent.ACTION_UP, x - 186, y)
        assertReading()
    }

    @Test fun aLongPressStillOpensSettingsAndCancelledPressDoesNot() {
        var x = 0f; var y = 0f
        onNote { _, pad -> x = pad.width * 0.8f; y = pad.totalPaddingTop + 20f }
        send(MotionEvent.ACTION_DOWN, x, y)
        send(MotionEvent.ACTION_CANCEL, x, y)
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 80)
        onNote { _, pad -> assertFalse(pad.isFocused) }
        send(MotionEvent.ACTION_DOWN, x, y)
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
        send(MotionEvent.ACTION_DOWN, 150f, 200f)
        send(MotionEvent.ACTION_CANCEL, 150f, 200f)
        assertReading()
    }

    @Test fun scrollbarFadesAndIsClippedInsideNote() {
        swipe(pauseAtEnd = true)
        fun barPixels(): Int {
            var count = 0
            onNote { _, pad ->
                val image = Bitmap.createBitmap(pad.width, pad.height, Bitmap.Config.ARGB_8888)
                pad.draw(Canvas(image))
                val x = (10 * pad.resources.displayMetrics.density).toInt()
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
        onNote { _, pad -> pad.scrollTo(0, pad.scrollY + 10) }
        assertTrue("Scrollbar never appeared", barPixels() > 0)
        SystemClock.sleep(450)
        assertEquals("Scrollbar did not fade", 0, barPixels())
    }

    @Test fun largeNoteRemainsScrollableAfterRotationAndProducesAPreview() {
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        val deadline = SystemClock.uptimeMillis() + 6000
        var landscape = false
        while (!landscape && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100)
            scenario.onActivity { landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        }
        assertTrue("Landscape did not become available", landscape)
        swipe(pauseAtEnd = true)
        assertReading()
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        SystemClock.sleep(350)
        onNote { _, pad ->
            val image = Bitmap.createBitmap(pad.rootView.width, pad.rootView.height, Bitmap.Config.ARGB_8888)
            pad.rootView.draw(Canvas(image))
            val path = File(instrumentation.targetContext.getExternalFilesDir(null), "jotterknocker-preview.png")
            path.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }
}
