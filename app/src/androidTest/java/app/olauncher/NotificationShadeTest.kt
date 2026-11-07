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
    @Test fun topEdgePullOpensWhileAnOrdinaryNoteDragDoesNot() {
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
            val start = inset + 140f
            listOf(MotionEvent.ACTION_DOWN to start, MotionEvent.ACTION_MOVE to start + 90, MotionEvent.ACTION_UP to start + 90).forEachIndexed { i, (action, y) ->
                MotionEvent.obtain(now + 100, now + 100 + i * 20, action, x, y, 0).also { a.dispatchTouchEvent(it); it.recycle() }
            }
            assertFalse(a.notificationShade.isOpen)
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
        session("Episode", PlaybackState.STATE_PAUSED)
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
