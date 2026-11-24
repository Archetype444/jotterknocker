package app.olauncher.notifications

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import app.olauncher.MainActivity
import app.olauncher.R
import java.lang.ref.WeakReference

/** Optional physical-edge input. Ordinary home pulls are owned directly by MainActivity. */
class ShadeEdgeService : AccessibilityService() {
    private var strip: View? = null
    private var touching = false
    private var readyForInput = false
    private var pendingSystemDismissal = false
    private val manager get() = getSystemService(WINDOW_SERVICE) as WindowManager

    override fun onServiceConnected() { super.onServiceConnected(); instance = this; sync() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null && (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) &&
            event.packageName?.toString() == "com.android.systemui") dismissSystemShade()
        sync()
    }
    override fun onInterrupt() { pendingSystemDismissal = false; removeStrip() }
    override fun onDestroy() {
        pendingSystemDismissal = false
        removeStrip()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun dismissSystemShade() {
        val activity = host.get() ?: return
        if (Build.VERSION.SDK_INT >= 31 && pendingSystemDismissal &&
            activity.isNotificationHomeResumed && activity.notificationShade.isOpen && !activity.hasWindowFocus()) {
            // Use the dedicated platform action only for SystemUI taking this owned edge pull.
            // Never synthesize Back, close unrelated windows, or schedule delayed retries.
            if (performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)) pendingSystemDismissal = false
        }
    }

    private fun sync() {
        val activity = host.get()
        if (activity?.isNotificationHomeResumed != true) {
            pendingSystemDismissal = false
            removeStrip()
            return
        }
        if (!activity.notificationShade.isOpen) pendingSystemDismissal = false
        // Keep ownership until UP/CANCEL. A focus event during the pull must not tear its input window down.
        if (!touching && (!activity.isNotificationHomeVisible || activity.notificationShade.isOpen)) {
            removeStrip()
            return
        }
        val density = resources.displayMetrics.density
        val top = ViewCompat.getRootWindowInsets(activity.window.decorView)
            ?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())?.top ?: 0
        val height = maxOf(top, (12 * density).toInt().coerceAtLeast(1))
        strip?.let {
            val params = it.layoutParams as WindowManager.LayoutParams
            if (params.height != height && !touching) {
                params.height = height
                runCatching { manager.updateViewLayout(it, params) }
            }
            return
        }
        val pull = TopEdgePull(12f * density)
        var pulled = false
        var startX = 0f
        var startY = 0f
        var owner = WeakReference<MainActivity>(null)
        fun open() {
            val target = owner.get() ?: return
            if (target !== host.get() || !target.isNotificationHomeResumed) return
            pulled = true
            pendingSystemDismissal = true
            target.finishNotificationEdgePull()
        }
        val view = View(this).apply {
            contentDescription = getString(R.string.shade_open)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (host.get()?.isNotificationEdgeActive != true) return@setOnTouchListener false
                        owner = WeakReference(host.get())
                        touching = true
                        pulled = false
                        startX = event.rawX
                        startY = event.rawY
                        pull.begin(startX, startY, true)
                    }
                    MotionEvent.ACTION_MOVE -> if (pull.move(event.rawX, event.rawY)) open()
                    MotionEvent.ACTION_UP -> {
                        // Batched fast swipes may first cross the threshold in their final event.
                        if (pull.move(event.rawX, event.rawY)) open()
                        touching = false
                        pull.cancel()
                        if (pulled) dismissSystemShade()
                        sync()
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        touching = false
                        pull.cancel()
                        if (pulled) dismissSystemShade()
                        sync()
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> pull.cancel()
                }
                true
            }
        }
        view.doOnLayout { view.postOnAnimation { if (strip === view) readyForInput = true } }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            title = getString(R.string.shade_edge_label)
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        runCatching { manager.addView(view, params); strip = view }
    }

    private fun removeStrip() {
        val old = strip
        strip = null
        touching = false
        readyForInput = false
        old?.let { runCatching { manager.removeViewImmediate(it) } }
    }

    companion object {
        private var instance: ShadeEdgeService? = null
        internal val edgeAttached get() = instance?.let { it.readyForInput && it.strip?.isAttachedToWindow == true } == true
        private var host = WeakReference<MainActivity>(null)
        fun attach(activity: MainActivity) { host = WeakReference(activity); refresh() }
        fun detach(activity: MainActivity) { if (host.get() === activity) { host.clear(); refresh() } }
        fun refresh() { instance?.let { it.dismissSystemShade(); it.sync() } }
        fun cancelSystemDismissal() { instance?.pendingSystemDismissal = false }
    }
}
