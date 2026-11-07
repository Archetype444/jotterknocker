package app.olauncher.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.session.PlaybackState
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.MainActivity
import app.olauncher.R
import app.olauncher.helper.hideKeyboard
import kotlinx.coroutines.launch

/** Native Android views, deliberately monochrome and without animation for the Kompakt panel. */
class NotificationShade(private val activity: MainActivity, parent: FrameLayout) {
    val view = LinearLayout(activity).apply {
        id = R.id.notificationShade
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.WHITE)
        isClickable = true
        isFocusableInTouchMode = true
        visibility = View.GONE
        elevation = dp(8).toFloat()
    }
    val isOpen get() = view.isVisible
    private val title = text(activity.getString(R.string.shade_title), 21f).apply { setTypeface(typeface, Typeface.BOLD) }
    private val clear = button(activity.getString(R.string.shade_clear)).apply {
        id = R.id.shadeClear
        setOnClickListener { LauncherNotificationListener.dismiss(NoticeStore.notices.value.filter { it.clearable && !it.media }.map { it.key }) }
    }
    private val media = LinearLayout(activity).apply {
        id = R.id.shadeMedia; orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)); visibility = View.GONE
    }
    private val mediaTitle = text("", 18f).apply { id = R.id.shadeMediaTitle; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val mediaArtist = text("", 15f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val previous = icon(R.drawable.shade_previous, R.string.shade_previous).apply { id = R.id.shadePrevious; setOnClickListener { mediaCommand { ShadeMedia.previous() } } }
    private val next = icon(R.drawable.shade_next, R.string.shade_next).apply { id = R.id.shadeNext; setOnClickListener { mediaCommand { ShadeMedia.next() } } }
    private val play = icon(R.drawable.shade_play, R.string.shade_play).apply { id = R.id.shadePlayPause; setOnClickListener { mediaCommand { ShadeMedia.playPause() } } }
    private val back = button(activity.getString(R.string.shade_minus_15)).apply {
        id = R.id.shadeSeekBack; contentDescription = activity.getString(R.string.shade_back_15)
        setOnClickListener { mediaCommand { ShadeMedia.seekBy(-15_000) } }
    }
    private val forward = button(activity.getString(R.string.shade_plus_15)).apply {
        id = R.id.shadeSeekForward; contentDescription = activity.getString(R.string.shade_forward_15)
        setOnClickListener { mediaCommand { ShadeMedia.seekBy(15_000) } }
    }
    private val empty = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(20), dp(16), dp(20), dp(16))
    }
    private val emptyText = text("", 18f).apply { id = R.id.shadeEmpty; gravity = Gravity.CENTER }
    private val setup = button(activity.getString(R.string.shade_access)).apply {
        id = R.id.shadeSetup; setOnClickListener { NotificationAccess.open(activity, false) }
    }
    private val adapter = NoticesAdapter()
    private val list = RecyclerView(activity).apply {
        id = R.id.shadeList
        layoutManager = LinearLayoutManager(activity)
        adapter = this@NotificationShade.adapter
        itemAnimator = null
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = true
        setBackgroundColor(Color.WHITE)
    }
    private var activeNotices = emptyList<ShadeNotice>()
    private var activeMedia: ShadePlayback? = null
    private var oldAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    private val homeContent = activity.findViewById<View>(R.id.nav_host_fragment)

    init {
        // The activity's legacy layout transition would fade this page over the note.
        parent.layoutTransition = null
        val header = LinearLayout(activity).apply {
            id = R.id.shadeHeader; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(4), 0)
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(icon(R.drawable.shade_close, R.string.shade_close).apply { id = R.id.shadeClose; background = android.graphics.drawable.ColorDrawable(Color.WHITE); setOnClickListener { close() } }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        var startY = 0f
        header.setOnClickListener { }
        header.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> startY = event.y
                MotionEvent.ACTION_MOVE -> if (startY - event.y > dp(24)) close()
                MotionEvent.ACTION_UP -> v.performClick()
            }
            true
        }
        view.addView(header)
        view.addView(rule())
        media.addView(mediaTitle)
        media.addView(mediaArtist)
        val controls = LinearLayout(activity).apply { setPadding(0, dp(8), 0, 0) }
        listOf(previous, back, play, forward, next).forEach { controls.addView(it, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(2) }) }
        media.addView(controls)
        media.addView(rule(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(8) })
        view.addView(media)
        val body = FrameLayout(activity)
        body.addView(list, FrameLayout.LayoutParams(-1, -1))
        empty.addView(emptyText)
        empty.addView(setup, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(16) })
        body.addView(empty, FrameLayout.LayoutParams(-1, -1))
        view.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        view.addView(rule())
        val footer = LinearLayout(activity).apply { setPadding(dp(8), 0, dp(8), 0) }
        footer.addView(button(activity.getString(R.string.shade_settings)).apply { background = android.graphics.drawable.ColorDrawable(Color.WHITE); setOnClickListener { showSettings() } }, LinearLayout.LayoutParams(0, dp(48), 1f))
        clear.background = android.graphics.drawable.ColorDrawable(Color.WHITE)
        footer.addView(clear, LinearLayout.LayoutParams(0, dp(48), 1f))
        view.addView(footer)
        parent.addView(view, FrameLayout.LayoutParams(-1, -1))
        ViewCompat.setAccessibilityPaneTitle(view, activity.getString(R.string.shade_title))
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { NoticeStore.notices.collect { activeNotices = it; if (isOpen) renderNotices() } }
                launch { NoticeStore.connected.collect { if (isOpen) renderNotices() } }
                launch { ShadeMedia.playback.collect { activeMedia = it; if (isOpen) { renderMedia(); renderNotices() } } }
            }
        }
    }

    fun show() {
        if (isOpen) return
        activity.currentFocus?.hideKeyboard()
        oldAccessibility = homeContent.importantForAccessibility
        homeContent.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        view.visibility = View.VISIBLE
        view.alpha = 1f
        view.requestFocus()
        ViewCompat.requestApplyInsets(view)
        refresh()
        title.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
        ShadeEdgeService.refresh()
    }
    fun close(): Boolean {
        if (!isOpen) return false
        view.visibility = View.GONE
        homeContent.importantForAccessibility = oldAccessibility
        ShadeEdgeService.refresh()
        return true
    }
    fun refresh() { if (isOpen) { activeNotices = NoticeStore.notices.value; activeMedia = ShadeMedia.playback.value; renderMedia(); renderNotices() } }

    fun showSettings() {
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(12)) }
        content.addView(text(activity.getString(R.string.shade_setup_body), 17f))
        val access = button(activity.getString(if (NotificationAccess.granted(activity)) R.string.shade_access_enabled else R.string.shade_access))
        val edge = button(activity.getString(if (NotificationAccess.edgeGranted(activity)) R.string.shade_edge_enabled else R.string.shade_edge))
        content.addView(access, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        content.addView(edge, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        content.addView(text(activity.getString(R.string.shade_calendar_help), 16f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        content.addView(text(activity.getString(R.string.restricted_settings_hint), 15f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val theme = android.view.ContextThemeWrapper(activity, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
        val dialog = AlertDialog.Builder(theme).setTitle(R.string.shade_title).setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton(android.R.string.ok, null).create()
        access.setOnClickListener { dialog.dismiss(); NotificationAccess.open(activity, false) }
        edge.setOnClickListener { dialog.dismiss(); NotificationAccess.open(activity, true) }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.BLACK)
    }

    private fun renderNotices() {
        val granted = NotificationAccess.granted(activity)
        val items = if (granted) NoticeStore.visible(activeNotices.filter { !(it.media && it.packageName == activeMedia?.packageName) }) else emptyList()
        adapter.expanded.retainAll(items.map { it.key }.toSet())
        adapter.submitList(items)
        list.isVisible = items.isNotEmpty()
        empty.isVisible = items.isEmpty()
        emptyText.text = activity.getString(if (!granted) R.string.shade_access_body else if (!NoticeStore.connected.value) R.string.shade_connecting else R.string.shade_empty)
        setup.isVisible = !granted
        clear.isEnabled = activeNotices.any { it.clearable && !it.media }
        clear.alpha = if (clear.isEnabled) 1f else 0.4f
    }
    private fun renderMedia() {
        val state = activeMedia
        media.isVisible = state != null
        if (state == null) return
        mediaTitle.text = state.title.ifBlank { appName(state.packageName) }
        mediaArtist.text = listOf(appName(state.packageName), state.artist).filter { it.isNotBlank() }.distinct().joinToString(" · ")
        play.setImageResource(if (state.playing) R.drawable.shade_pause else R.drawable.shade_play)
        play.contentDescription = activity.getString(if (state.playing) R.string.shade_pause else R.string.shade_play)
        fun enable(v: View, enabled: Boolean) { v.isEnabled = enabled; v.alpha = if (enabled) 1f else 0.3f }
        enable(play, state.supports(PlaybackState.ACTION_PLAY_PAUSE) || state.supports(if (state.playing) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY))
        enable(previous, state.supports(PlaybackState.ACTION_SKIP_TO_PREVIOUS))
        enable(next, state.supports(PlaybackState.ACTION_SKIP_TO_NEXT))
        back.isVisible = state.canSeek
        forward.isVisible = state.canSeek
    }
    private fun appName(pkg: String): String = runCatching { activity.packageManager.getApplicationLabel(activity.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    private fun mediaCommand(command: () -> Boolean) { if (!command()) Toast.makeText(activity, R.string.shade_media_failed, Toast.LENGTH_SHORT).show() }
    @android.annotation.SuppressLint("WrongConstant") // SDK 35's IntDef predates the documented API 36 value.
    private fun notificationLaunchOptions(): android.os.Bundle? {
        if (android.os.Build.VERSION.SDK_INT < 34) return null
        return android.app.ActivityOptions.makeBasic().apply {
            // MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE = 4, added in API 36.
            setPendingIntentBackgroundActivityStartMode(if (android.os.Build.VERSION.SDK_INT >= 36) 4
                else android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }.toBundle()
    }
    private fun send(intent: PendingIntent?, notice: ShadeNotice?, autoCancel: Boolean) {
        if (!isOpen || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (intent == null) { Toast.makeText(activity, R.string.shade_action_failed, Toast.LENGTH_SHORT).show(); return }
        runCatching {
            intent.send(activity, 0, null, null, null, null, notificationLaunchOptions())
            if (autoCancel && notice?.autoCancel == true) LauncherNotificationListener.dismiss(listOf(notice.key))
        }.onFailure { Toast.makeText(activity, R.string.shade_action_failed, Toast.LENGTH_SHORT).show() }
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + 0.5f).toInt()
    private fun text(value: String, size: Float) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(Color.BLACK); typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        includeFontPadding = false
    }
    private fun outline() = GradientDrawable().apply { setColor(Color.WHITE); setStroke(dp(1), Color.BLACK); cornerRadius = dp(3).toFloat() }
    private fun button(value: String) = Button(activity).apply {
        text = value; textSize = 16f; isAllCaps = false; setTextColor(Color.BLACK); typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        background = outline(); minHeight = dp(48); minimumWidth = 0; minWidth = 0; setPadding(dp(6), dp(6), dp(6), dp(6))
        stateListAnimator = null
    }
    private fun icon(drawable: Int, description: Int) = ImageButton(activity).apply {
        setImageResource(drawable); contentDescription = activity.getString(description); background = outline()
        setPadding(dp(12), dp(12), dp(12), dp(12)); minimumHeight = dp(48); minimumWidth = dp(48); stateListAnimator = null
    }
    private fun rule() = View(activity).apply { setBackgroundColor(Color.BLACK); layoutParams = ViewGroup.LayoutParams(-1, 1) }

    private inner class NoticesAdapter : ListAdapter<ShadeNotice, NoticeHolder>(object : DiffUtil.ItemCallback<ShadeNotice>() {
        override fun areItemsTheSame(old: ShadeNotice, new: ShadeNotice) = old.key == new.key
        override fun areContentsTheSame(old: ShadeNotice, new: ShadeNotice) = old == new
    }) {
        val expanded = mutableSetOf<String>()
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = NoticeHolder()
        override fun onBindViewHolder(holder: NoticeHolder, position: Int) = holder.bind(getItem(position), expanded)
    }
    private inner class NoticeHolder : RecyclerView.ViewHolder(LinearLayout(activity)) {
        private val root = itemView as LinearLayout
        private val source = text("", 14f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        private val name = text("", 18f).apply { id = R.id.shadeNoticeTitle; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setTypeface(typeface, Typeface.BOLD) }
        private val body = text("", 17f).apply { id = R.id.shadeNoticeText; ellipsize = TextUtils.TruncateAt.END }
        private val dismiss = icon(R.drawable.shade_close, R.string.shade_dismiss).apply { id = R.id.shadeNoticeDismiss; background = android.graphics.drawable.ColorDrawable(Color.WHITE) }
        private val more = button(activity.getString(R.string.shade_more))
        private val actions = LinearLayout(activity).apply { id = R.id.shadeNoticeActions }
        init {
            root.orientation = LinearLayout.VERTICAL
            root.setPadding(dp(12), dp(10), dp(12), dp(10))
            root.layoutParams = RecyclerView.LayoutParams(-1, -2)
            val heading = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
            val labels = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(source)
                addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            }
            heading.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            heading.addView(dismiss, LinearLayout.LayoutParams(dp(48), dp(48)))
            root.addView(heading)
            root.addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5); bottomMargin = dp(8) })
            root.addView(more, LinearLayout.LayoutParams(-1, -2))
            root.addView(HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false; addView(actions) })
            root.addView(rule(), LinearLayout.LayoutParams(-1, 1).apply { topMargin = dp(10) })
        }
        fun bind(item: ShadeNotice, expanded: MutableSet<String>) {
            root.tag = item.key
            val time = android.text.format.DateFormat.getTimeFormat(activity).format(java.util.Date(item.time))
            source.text = "${item.appName} · $time"
            name.text = item.title
            body.text = item.text
            body.isVisible = item.text.isNotBlank()
            body.maxLines = if (item.key in expanded) Int.MAX_VALUE else 3
            more.isVisible = item.key in expanded
            body.doOnLayout {
                if (root.tag == item.key && item.key !in expanded) {
                    val layout = body.layout
                    more.isVisible = layout != null && layout.lineCount > 0 && layout.getEllipsisCount(layout.lineCount - 1) > 0
                }
            }
            more.text = activity.getString(if (item.key in expanded) R.string.shade_less else R.string.shade_more)
            more.setOnClickListener {
                if (!expanded.add(item.key)) expanded.remove(item.key)
                body.maxLines = if (item.key in expanded) Int.MAX_VALUE else 3
                more.text = activity.getString(if (item.key in expanded) R.string.shade_less else R.string.shade_more)
            }
            root.setOnClickListener { send(item.contentIntent, item, true) }
            name.setOnClickListener { send(item.contentIntent, item, true) }
            body.setOnClickListener { send(item.contentIntent, item, true) }
            dismiss.isVisible = item.clearable
            dismiss.setOnClickListener { LauncherNotificationListener.dismiss(listOf(item.key)) }
            actions.removeAllViews()
            item.actions.forEach { action ->
                actions.addView(button(action.title.toString()).apply { setOnClickListener { send(action.actionIntent, item, false) } },
                    LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            }
        }
    }
}
