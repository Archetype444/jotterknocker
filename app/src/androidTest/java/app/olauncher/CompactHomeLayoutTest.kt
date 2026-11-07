package app.olauncher

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.olauncher.data.Prefs
import app.olauncher.ui.HomeAppTextView
import app.olauncher.ui.MarkdownEditText
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/** Checks the usable edges as well as the pixels of a zero-gap bottom row. */
@RunWith(AndroidJUnit4::class)
class CompactHomeLayoutTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun launch() {
        Prefs(instrumentation.targetContext).apply {
            firstOpen = false
            firstSettingsOpen = false
            hideSetDefaultLauncher = true
            scratchpadText = "A compact note\n" + (1..100).joinToString("\n") { "Line $it" }
            scratchpadPages = 1
            scratchpadTextScale = 1f
            scratchpadLineSpacing = 1.3f
            appSpacing = 1f
            appTextScale = 1f
            textSizeScale = 1f
            homeAppsNum = 15
            homeColumns = 3
            homePages = 1
            homePage = 0
            recentAppsColumn = 0
            homeAlignment = Gravity.START
            homeBottomMargin = 0
            dateTimeTop = false
            dateTimeVisibility = 1
            clockMargin = 0
            dateMargin = 0
            showStatusBar = true
            showAppIcons = false
            appTheme = AppCompatDelegate.MODE_NIGHT_NO
            lockModeOn = false
            (1..15).forEach { n ->
                val name = when (n) { 5 -> "Photos"; 10 -> "Settings"; 15 -> "gyp"; else -> "App $n" }
                setHomeApp(n, name, "com.android.settings", "", null, null)
            }
        }
        scenario = ActivityScenario.launch(Intent(instrumentation.targetContext, MainActivity::class.java))
        settle()
    }

    @After fun close() { scenario.close() }

    private fun settle() {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(350)
        scenario.onActivity { activity ->
            activity.findViewById<View>(R.id.mainLayout).apply { setBackgroundColor(Color.WHITE); requestFocus() }
        }
    }

    private fun change(block: Prefs.() -> Unit) {
        Prefs(instrumentation.targetContext).apply(block)
        scenario.recreate()
        settle()
    }

    private fun rect(view: View): Rect = Rect().also { assertTrue(view.getGlobalVisibleRect(it)) }
    private fun dp(view: View, value: Float) = (value * view.resources.displayMetrics.density).roundToInt()
    private fun columns(activity: MainActivity) = activity.findViewById<LinearLayout>(R.id.homeAppsGrid)
        .children.filterIsInstance<LinearLayout>().toList()

    @Test fun noteClockDateAndAppsShareTheSmallLeftGutterAndUseTheRightEdge() {
        scenario.onActivity { activity ->
            val home = activity.findViewById<View>(R.id.homeColumns)
            val homeBounds = rect(home)
            val note = activity.findViewById<MarkdownEditText>(R.id.scratchpad)
            val noteBounds = rect(note)
            val controls = activity.findViewById<View>(R.id.homeAppsLayout)
            val grid = activity.findViewById<View>(R.id.homeAppsGrid)
            val clock = activity.findViewById<View>(R.id.clock)
            val date = activity.findViewById<View>(R.id.date)
            val textLeft = noteBounds.left + note.totalPaddingLeft
            assertEquals(homeBounds.left + home.paddingLeft + dp(note, 6f), textLeft)
            assertEquals(textLeft, rect(grid).left)
            assertEquals(textLeft, rect(clock).left + clock.paddingLeft)
            assertEquals(textLeft, rect(date).left + date.paddingLeft)
            assertEquals(0, note.paddingRight)
            assertEquals(0, controls.paddingRight)
            assertEquals(homeBounds.right - home.paddingRight, noteBounds.right)
            assertEquals(noteBounds.right, rect(grid).right)
            assertEquals(noteBounds.right, rect(date).right)
        }
    }

    @Test fun noteStartsImmediatelyBelowActualStatusBarInsets() {
        assertTopInsets()
        change { showStatusBar = false }
        assertTopInsets()
    }

    private fun assertTopInsets() {
        scenario.onActivity { activity ->
            val home = activity.findViewById<View>(R.id.homeColumns)
            val note = activity.findViewById<MarkdownEditText>(R.id.scratchpad)
            val insets = ViewCompat.getRootWindowInsets(home)!!
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top
            val status = if (Prefs(activity).showStatusBar) insets.getInsets(WindowInsetsCompat.Type.statusBars()).top else 0
            if (Prefs(activity).showStatusBar) assertTrue("Visible status bar has no usable inset", status > 0)
            assertEquals(maxOf(cutout, status), home.paddingTop)
            assertEquals(rect(home).top + home.paddingTop, rect(note).top)
            assertEquals(dp(note, 4f), note.paddingTop)
            assertFalse(note.includeFontPadding)
        }
    }

    @Test fun zeroBottomGapReachesTheLastVisiblePixelWithoutClippingDescenders() {
        val screenshot = instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/jotterknocker-compact-preview.png")
        ParcelFileDescriptor.AutoCloseInputStream(screenshot).use { it.readBytes() }
        scenario.onActivity { activity ->
            val appArea = activity.findViewById<View>(R.id.homeAppsLayout)
            val home = activity.findViewById<View>(R.id.homeColumns)
            val bars = ViewCompat.getRootWindowInsets(home)!!.getInsets(WindowInsetsCompat.Type.navigationBars())
            assertTrue("Visible emulator navigation bar has no usable inset", bars.bottom > 0)
            assertTrue("Apps overlap the navigation bar", home.paddingBottom >= bars.bottom)
            assertEquals(0, appArea.paddingBottom)
            assertEquals(rect(home).bottom - home.paddingBottom, rect(appArea).bottom)
            for (column in columns(activity)) {
                val last = column.children.filterIsInstance<HomeAppTextView>().last { it.visibility == View.VISIBLE }
                assertEquals(rect(appArea).bottom, rect(last).bottom)
                assertEquals(0, last.paddingBottom)
                assertFalse(last.includeFontPadding)
                assertTrue(last.alignInkToBottom)
                // Ignore the halo shadow: the glyph itself must reach the final row of pixels.
                last.setTextColor(Color.BLACK)
                last.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                fun alphaSum(bitmap: Bitmap): Long {
                    var sum = 0L
                    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) sum += bitmap.getPixel(x, y) ushr 24
                    return sum
                }
                last.alignInkToBottom = false
                val reference = Bitmap.createBitmap(last.width, last.height, Bitmap.Config.ARGB_8888)
                last.draw(Canvas(reference))
                last.alignInkToBottom = true
                val bitmap = Bitmap.createBitmap(last.width, last.height, Bitmap.Config.ARGB_8888)
                last.draw(Canvas(bitmap))
                var bottom = -1
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                    if ((bitmap.getPixel(x, y) ushr 24) > 16) bottom = y
                }
                assertEquals("Visible text leaves a bottom gap: ${last.text}", last.height - 1, bottom)
                assertEquals("Bottom alignment clipped text: ${last.text}", alphaSum(reference), alphaSum(bitmap))
                reference.recycle()
                bitmap.recycle()
            }
        }
    }

    @Test fun nonzeroBottomSpacingStillUsesTheSelectedValue() {
        change { homeBottomMargin = 24 }
        scenario.onActivity { activity ->
            val appArea = activity.findViewById<View>(R.id.homeAppsLayout)
            assertEquals(dp(appArea, 24f), appArea.paddingBottom)
            for (column in columns(activity)) {
                val last = column.children.filterIsInstance<HomeAppTextView>().last { it.visibility == View.VISIBLE }
                assertFalse(last.alignInkToBottom)
            }
        }
    }

    @Test fun movingTheClockAboveTheNoteKeepsTheSameEdges() {
        change { dateTimeTop = true }
        scenario.onActivity { activity ->
            val note = activity.findViewById<MarkdownEditText>(R.id.scratchpad)
            val date = activity.findViewById<View>(R.id.date)
            val clock = activity.findViewById<View>(R.id.clock)
            assertEquals(rect(note).left + note.paddingLeft, rect(clock).left)
            assertEquals(rect(clock).left, rect(date).left)
            assertEquals(rect(note).right, rect(date).right)
        }
    }

    @Test fun scrollbarAppearsAtTheEdgeAndRemainsInsideTheNote() {
        scenario.onActivity { activity ->
            val note = activity.findViewById<MarkdownEditText>(R.id.scratchpad)
            note.scrollTo(0, minOf(20, note.maximumNoteScroll))
            val bitmap = Bitmap.createBitmap(note.width, note.height, Bitmap.Config.ARGB_8888)
            note.draw(Canvas(bitmap))
            var pixels = 0
            // A full-opacity part of the 2.5dp bar, before the text's 6dp inset.
            val x = dp(note, 1f)
            for (y in 0 until bitmap.height) if ((bitmap.getPixel(x, y) ushr 24) > 16) {
                assertTrue(y >= note.totalPaddingTop && y < note.height - note.totalPaddingBottom)
                pixels++
            }
            assertTrue("No scrollbar at the left edge", pixels > 0)
            assertEquals(dp(note, 6f), note.paddingLeft)
            assertTrue(rect(note).bottom <= rect(activity.findViewById(R.id.homeControls)).top)
            bitmap.recycle()
        }
    }
}
