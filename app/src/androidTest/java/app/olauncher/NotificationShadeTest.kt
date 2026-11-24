package app.olauncher

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.olauncher.notifications.*
import app.olauncher.ui.MarkdownEditText
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class NotificationShadeTest {
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val context get() = ShadeTestTools.context
    private val nm get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val sessions = mutableListOf<MediaSession>()
    private val calls = CopyOnWriteArrayList<String>()
    @Before fun setup() { ShadeTestTools.prepare(); scenario = ShadeTestTools.launch() }
    @After fun cleanup() {
        ShadeTestTools.instrumentation.runOnMainSync { sessions.forEach { it.release() } }
        nm.cancelAll(); if (::scenario.isInitialized) scenario.close()
    }
    private fun post(id: Int, title: String = "Calendar reminder", body: String = "Appointment at 14:00", configure: NotificationCompat.Builder.() -> Unit = {}) {
        val builder = NotificationCompat.Builder(context, ShadeTestTools.CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
        builder.configure(); nm.notify(id, builder.build())
        ShadeTestTools.waitFor("Posted notification missing") { NoticeStore.notices.value.any { it.title == title } }
    }
    private fun show() { scenario.onActivity { it.openNotificationShade() }; ShadeTestTools.instrumentation.waitForIdleSync() }
    private fun session(title: String, state: Int = PlaybackState.STATE_PLAYING, seek: Boolean = true): MediaSession {
        lateinit var session: MediaSession
        ShadeTestTools.instrumentation.runOnMainSync {
            session = MediaSession(context, title)
            session.setCallback(object : MediaSession.Callback() {
                override fun onSeekTo(pos: Long) { calls.add("seek:$pos"); setState(session, state, seek, pos) }
                override fun onSkipToNext() { calls.add("next") }
                override fun onSkipToPrevious() { calls.add("previous") }
                override fun onPause() { calls.add("pause"); setState(session, PlaybackState.STATE_PAUSED, seek, 30_000) }
                override fun onPlay() { calls.add("play"); setState(session, PlaybackState.STATE_PLAYING, seek, 30_000) }
            }, Handler(Looper.getMainLooper()))
            session.setFlags(MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS or MediaSession.FLAG_HANDLES_MEDIA_BUTTONS)
            session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title).putString(MediaMetadata.METADATA_KEY_ARTIST, "Podcast episode")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 120_000).build())
            setState(session, state, seek, 30_000)
            session.isActive = true
            sessions.add(session)
        }
        ShadeTestTools.waitFor("Media session not discovered") { ShadeMedia.playback.value?.title == title }
        return session
    }
    private fun setState(session: MediaSession, state: Int, seek: Boolean, position: Long) {
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (seek) actions = actions or PlaybackState.ACTION_SEEK_TO
        session.setPlaybackState(PlaybackState.Builder().setActions(actions).setState(state, position, 1f, SystemClock.elapsedRealtime()).build())
    }
    @Test fun openingAndClosingRestoresTheScratchpadWithoutEditingOrScrollingIt() {
        var position = 0
        var note = ""
        scenario.onActivity {
            val pad = it.findViewById<MarkdownEditText>(R.id.scratchpad)
            pad.scrollTo(0, 100); position = pad.scrollY; note = pad.text.toString()
            it.openNotificationShade(); assertTrue(it.notificationShade.isOpen)
            it.findViewById<View>(R.id.shadeClose).performClick()
            assertFalse(it.notificationShade.isOpen)
            assertEquals(position, pad.scrollY); assertEquals(note, pad.text.toString())
        }
    }
    @Test fun topEdgePullOpensWhileAScrolledNoteKeepsItsDrag() {
        scenario.onActivity { a ->
            val inset = ViewCompat.getRootWindowInsets(a.window.decorView)!!.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val x = a.window.decorView.width / 2f
            val now = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN to inset + 2f, MotionEvent.ACTION_MOVE to inset + 120f, MotionEvent.ACTION_UP to inset + 120f).forEachIndexed { i, (action, y) ->
                MotionEvent.obtain(now, now + i * 20, action, x, y, 0).also { a.dispatchTouchEvent(it); it.recycle() }
            }
            assertTrue(a.notificationShade.isOpen)
            a.notificationShade.close()
            MotionEvent.obtain(now + 60, now + 60, MotionEvent.ACTION_DOWN, x, inset + 2f, 0).also { a.dispatchTouchEvent(it); it.recycle() }
            MotionEvent.obtain(now + 60, now + 90, MotionEvent.ACTION_UP, x, inset + 120f, 0).also { a.dispatchTouchEvent(it); it.recycle() }
            assertTrue("A batched fast swipe must open on the up event", a.notificationShade.isOpen)
            a.notificationShade.close()
            val pad = a.findViewById<MarkdownEditText>(R.id.scratchpad)
            pad.scrollTo(0, 200)
            assertTrue("The fixture note must be scrolled", pad.scrollY > 0)
            val (noteX, start) = notePoint(a)
            listOf(MotionEvent.ACTION_DOWN to start, MotionEvent.ACTION_MOVE to start + 90, MotionEvent.ACTION_UP to start + 90).forEachIndexed { i, (action, y) ->
                MotionEvent.obtain(now + 100, now + 100 + i * 20, action, noteX, y, 0).also { a.dispatchTouchEvent(it); it.recycle() }
            }
            assertFalse(a.notificationShade.isOpen)
        }
    }
    private fun dispatch(a: MainActivity, action: Int, x: Float, y: Float, down: Long, time: Long) {
        val event = MotionEvent.obtain(down, time, action, x, y, 0)
        try { a.dispatchTouchEvent(event) } finally { event.recycle() }
    }
    private fun notePoint(a: MainActivity): Pair<Float, Float> {
        val pad = a.findViewById<MarkdownEditText>(R.id.scratchpad)
        val origin = IntArray(2).also { pad.getLocationInWindow(it) }
        return origin[0] + pad.width / 2f to origin[1] + pad.height / 3f
    }
    @Test fun repeatedHomePullsOpenWithoutTheAccessibilityServiceOrFlingVelocity() {
        scenario.onActivity { a ->
            val pad = a.findViewById<MarkdownEditText>(R.id.scratchpad)
            val original = pad.text.toString()
            val (x, y) = notePoint(a)
            val distance = 72f * a.resources.displayMetrics.density
            repeat(24) { attempt ->
                val now = SystemClock.uptimeMillis()
                pad.scrollTo(0, 0)
                // Cover fast batched input and slow deliberate pulls, without a velocity requirement.
                val duration = if (attempt % 2 == 0) 40L else 1_200L
                dispatch(a, MotionEvent.ACTION_DOWN, x, y, now, now)
                if (attempt % 3 != 0) dispatch(a, MotionEvent.ACTION_MOVE, x, y + distance / 2, now, now + duration / 2)
                dispatch(a, MotionEvent.ACTION_UP, x, y + distance, now, now + duration)
                assertTrue("Home pull $attempt did not open notifications", a.notificationShade.isOpen)
                assertFalse("A notification pull must not enter note editing", pad.isFocused)
                assertEquals(original, pad.text.toString())
                assertEquals(0, pad.scrollY)
                a.notificationShade.close()
            }
        }
    }
    @Test fun homePullCancelsAppPressWithoutLaunchingIt() {
        scenario.onActivity { a ->
            val target = a.findViewById<View>(R.id.homeApp1)
            var clicks = 0
            target.setOnTouchListener(null)
            target.setOnClickListener { clicks++ }
            val origin = IntArray(2).also { target.getLocationInWindow(it) }
            val x = origin[0] + target.width / 2f
            val y = origin[1] + target.height / 2f
            val now = SystemClock.uptimeMillis()
            dispatch(a, MotionEvent.ACTION_DOWN, x, y, now, now)
            dispatch(a, MotionEvent.ACTION_UP, x, y + 80 * a.resources.displayMetrics.density, now, now + 80)
            assertTrue(a.notificationShade.isOpen)
            assertFalse(target.isPressed)
            assertEquals(0, clicks)
        }
    }
    @Test fun scrolledAndEditedNotesKeepDownwardDrags() {
        scenario.onActivity { a ->
            val pad = a.findViewById<MarkdownEditText>(R.id.scratchpad)
            val (x, y) = notePoint(a)
            val distance = 60 * a.resources.displayMetrics.density
            val original = pad.text.toString()
            pad.scrollTo(0, 200)
            val before = pad.scrollY
            var now = SystemClock.uptimeMillis()
            dispatch(a, MotionEvent.ACTION_DOWN, x, y, now, now)
            dispatch(a, MotionEvent.ACTION_MOVE, x, y + distance, now, now + 100)
            dispatch(a, MotionEvent.ACTION_UP, x, y + distance, now, now + 200)
            assertFalse(a.notificationShade.isOpen)
            assertTrue("The scrolled note must still move towards its beginning", pad.scrollY < before)
            pad.stopReadingScroll()
            pad.scrollTo(0, 0)
            pad.requestFocus()
            now = SystemClock.uptimeMillis()
            dispatch(a, MotionEvent.ACTION_DOWN, x, y, now, now)
            dispatch(a, MotionEvent.ACTION_MOVE, x, y + distance, now, now + 100)
            dispatch(a, MotionEvent.ACTION_UP, x, y + distance, now, now + 200)
            assertFalse("Editing must retain Android's native selection and scrolling", a.notificationShade.isOpen)
            assertEquals(original, pad.text.toString())
        }
    }
    @Test fun aSecondFingerAndCancellationCannotOpenNotifications() {
        scenario.onActivity { a ->
            val (x, y) = notePoint(a)
            val now = SystemClock.uptimeMillis()
            dispatch(a, MotionEvent.ACTION_DOWN, x, y, now, now)
            val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
                MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER })
            val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f },
                MotionEvent.PointerCoords().apply { this.x = x + 40; this.y = y; pressure = 1f })
            val pointer = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { a.dispatchTouchEvent(pointer) } finally { pointer.recycle() }
            dispatch(a, MotionEvent.ACTION_CANCEL, x, y + 120, now, now + 40)
            assertFalse(a.notificationShade.isOpen)
            val next = now + 60
            dispatch(a, MotionEvent.ACTION_DOWN, x, y, next, next)
            dispatch(a, MotionEvent.ACTION_CANCEL, x, y, next, next + 10)
            dispatch(a, MotionEvent.ACTION_MOVE, x, y + 120, next, next + 20)
            assertFalse(a.notificationShade.isOpen)
        }
    }
    @Test fun notificationTitleAndBodyAreTwentyFivePercentLarger() {
        post(1)
        show()
        ShadeTestTools.waitFor("Notification text did not bind") {
            var ready = false
            scenario.onActivity { ready = it.findViewById<TextView>(R.id.shadeNoticeTitle) != null }
            ready
        }
        scenario.onActivity {
            val metrics = it.resources.displayMetrics
            assertEquals(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 22.5f, metrics),
                it.findViewById<TextView>(R.id.shadeNoticeTitle).textSize, 0.01f)
            assertEquals(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 21.25f, metrics),
                it.findViewById<TextView>(R.id.shadeNoticeText).textSize, 0.01f)
        }
    }
    @Test fun eventNotificationsKeepFullTextAndOriginalSnoozeActions() {
        val intent = Intent(context, ShadeFixtureReceiver::class.java).setAction("SNOOZE").putExtra("event", "event-42")
        val action = PendingIntent.getBroadcast(context, 42, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        context.getSharedPreferences("shade-test", 0).edit().clear().commit()
        val body = "14:00–15:00 · Calendar appointment\nBring the notes."
        post(42, body = body) { setCategory(Notification.CATEGORY_EVENT); addAction(0, "Snooze", action) }
        show()
        val notice = NoticeStore.notices.value.first { it.title == "Calendar reminder" }
        assertEquals(body, notice.text)
        assertEquals("Snooze", notice.actions.single().title.toString())
        ShadeTestTools.waitFor("Reminder row missing") {
            var ready = false
            scenario.onActivity {
                val list = it.findViewById<RecyclerView>(R.id.shadeList)
                val adapter = list.adapter as androidx.recyclerview.widget.ListAdapter<ShadeNotice, *>
                val position = adapter.currentList.indexOfFirst { item -> item.key == notice.key }
                if (position >= 0) { list.scrollToPosition(position); ready = list.findViewHolderForAdapterPosition(position) != null }
            }
            ready
        }
        scenario.onActivity {
            val list = it.findViewById<RecyclerView>(R.id.shadeList)
            val adapter = list.adapter as androidx.recyclerview.widget.ListAdapter<ShadeNotice, *>
            val position = adapter.currentList.indexOfFirst { item -> item.key == notice.key }
            val row = list.findViewHolderForAdapterPosition(position)!!.itemView
            val actions = row.findViewById<android.widget.LinearLayout>(R.id.shadeNoticeActions)
            actions.getChildAt(0).performClick()
        }
        ShadeTestTools.waitFor("Original calendar action was not delivered") { context.getSharedPreferences("shade-test", 0).getString("event", "") == "event-42" }
    }
    @Test fun aSingleDismissDoesNotClearSiblingNotifications() {
        post(1, "First event"); post(2, "Second event")
        val first = NoticeStore.notices.value.first { it.title == "First event" }
        LauncherNotificationListener.dismiss(listOf(first.key))
        ShadeTestTools.waitFor("Single dismissal did not remove the event") { NoticeStore.notices.value.none { it.key == first.key } }
        assertTrue(NoticeStore.notices.value.any { it.title == "Second event" })
    }
    @Test fun summariesAreHiddenOnlyWhenTheirChildrenExist() {
        post(1, "Group summary") { setGroup("calendar"); setGroupSummary(true) }
        assertTrue(NoticeStore.visible(NoticeStore.notices.value).any { it.title == "Group summary" })
        post(2, "Actual event") { setGroup("calendar") }
        val visible = NoticeStore.visible(NoticeStore.notices.value)
        assertFalse(visible.any { it.title == "Group summary" }); assertTrue(visible.any { it.title == "Actual event" })
    }
    @Test fun clearAllPreservesOngoingNotifications() {
        post(1, "Message"); post(2, "Ongoing service") { setOngoing(true) }
        show()
        scenario.onActivity { it.findViewById<View>(R.id.shadeClear).performClick() }
        ShadeTestTools.waitFor("Clear all left a dismissible notification") { NoticeStore.notices.value.none { it.title == "Message" } }
        assertTrue(NoticeStore.notices.value.any { it.title == "Ongoing service" })
    }
    @Test fun mediaSupportsSeparateTrackControlsAndAccurateFifteenSecondJumps() {
        val player = session("Episode", PlaybackState.STATE_PAUSED)
        show()
        scenario.onActivity {
            it.findViewById<View>(R.id.shadeSeekForward).performClick()
            it.findViewById<View>(R.id.shadeSeekForward).performClick()
            it.findViewById<View>(R.id.shadeSeekBack).performClick()
            it.findViewById<View>(R.id.shadePrevious).performClick()
            it.findViewById<View>(R.id.shadeNext).performClick()
        }
        ShadeTestTools.waitFor("Media commands not delivered") { calls.size >= 5 }
        assertEquals(listOf("seek:45000", "seek:60000", "seek:45000", "previous", "next"), calls.toList())
        var textUpdates = 0
        scenario.onActivity {
            it.findViewById<TextView>(R.id.shadeMediaTitle).addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { textUpdates++ }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        ShadeTestTools.instrumentation.runOnMainSync { setState(player, PlaybackState.STATE_PAUSED, true, 75_000) }
        ShadeTestTools.waitFor("Player position update not received") { ShadeMedia.playback.value?.position == 75_000L }
        ShadeTestTools.instrumentation.waitForIdleSync()
        scenario.onActivity { assertEquals("Position-only updates must not redraw the e-ink card", 0, textUpdates) }
    }
    @Test fun playingSessionWinsOverAnOlderPausedPlayerAndPausedCardRemains() {
        session("Old player", PlaybackState.STATE_PAUSED)
        val playing = session("Current player")
        show()
        assertEquals("Current player", ShadeMedia.playback.value?.title)
        scenario.onActivity { it.findViewById<View>(R.id.shadePlayPause).performClick() }
        ShadeTestTools.waitFor("Pause not reflected") { ShadeMedia.playback.value?.playing == false && ShadeMedia.playback.value?.title == "Current player" }
        scenario.onActivity { assertTrue(it.findViewById<View>(R.id.shadeMedia).isShown) }
        ShadeTestTools.instrumentation.runOnMainSync { playing.release() }
    }
    @Test fun liveOrUnseekableMediaDoesNotOfferFakeFifteenSecondButtons() {
        session("Live stream", seek = false); show()
        scenario.onActivity {
            assertFalse(it.findViewById<View>(R.id.shadeSeekBack).isShown)
            assertFalse(it.findViewById<View>(R.id.shadeSeekForward).isShown)
            assertTrue(it.findViewById<View>(R.id.shadeNext).isEnabled)
        }
    }
    @Test fun compactPanelIsMonochromeFitsSystemBarsAndHasUsableControls() {
        session("The afternoon podcast")
        post(1, "Calendar reminder", "14:00 · Appointment\nBring your notes.")
        post(2, "A new message", "See you this afternoon.")
        show()
        scenario.onActivity {
            val root = it.findViewById<View>(R.id.notificationShade)
            val background = root.background as ColorDrawable
            assertEquals(Color.WHITE, background.color)
            assertEquals("Shade must not fade over the note", 1f, root.alpha, 0f)
            val insets = ViewCompat.getRootWindowInsets(root)!!.getInsets(WindowInsetsCompat.Type.systemBars())
            assertTrue(root.paddingTop >= insets.top); assertTrue(root.paddingBottom >= insets.bottom)
            listOf(R.id.shadeSeekBack, R.id.shadeSeekForward, R.id.shadePlayPause, R.id.shadePrevious, R.id.shadeNext).forEach { id ->
                val button = it.findViewById<View>(id)
                assertTrue(button.height >= (48 * root.resources.displayMetrics.density).toInt())
                assertTrue(button.width > 0); assertTrue(button.right <= (button.parent as View).width)
            }
        }
        ShadeTestTools.instrumentation.waitForIdleSync()
        ShadeTestTools.screenshot("jotterknocker-notification-preview.png")
    }
}
