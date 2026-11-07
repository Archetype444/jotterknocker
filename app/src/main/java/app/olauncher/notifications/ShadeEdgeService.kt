package app.olauncher.notifications

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import app.olauncher.MainActivity
import app.olauncher.R
import java.lang.ref.WeakReference

/** A home-only trusted overlay can receive the physical edge above the system status bar. */
class ShadeEdgeService : AccessibilityService() {
    private var strip: View? = null
    private var touching = false
    private var readyForInput = false
    private var systemWindowActive = false
    private val handler = Handler(Looper.getMainLooper())
    private val dismissNative = Runnable {
        val activity = host.get()
        if (activity != null && activity.notificationShade.isOpen && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            if (android.os.Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            else if (systemWindowActive && !activity.hasWindowFocus()) performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }
    private val manager get() = getSystemService(WINDOW_SERVICE) as WindowManager
    override fun onServiceConnected() { super.onServiceConnected(); instance = this; sync() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            systemWindowActive = event.packageName?.toString() == "com.android.systemui"
        sync()
    }
    override fun onInterrupt() { handler.removeCallbacksAndMessages(null); removeStrip() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); removeStrip(); if (instance === this) instance = null; super.onDestroy() }
    private fun recoverNativeGesture() {
        handler.removeCallbacks(dismissNative)
        handler.postDelayed(dismissNative, 120)
        handler.postDelayed(dismissNative, 400)
    }

    private fun sync() {
        val activity = host.get()
        if (activity?.isNotificationHomeVisible != true || activity.notificationShade.isOpen && !touching) { removeStrip(); return }
        if (strip != null) return
        val density = resources.displayMetrics.density
        // Activate before SystemUI's drag monitor can pilfer the pointer stream.
        val pull = TopEdgePull(12f * density)
        var pulled = false
        var startX = 0f
        var startY = 0f
        val view = View(this).apply {
            contentDescription = getString(R.string.shade_open)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnClickListener { host.get()?.openNotificationShade() }
            setOnTouchListener { v, event ->
                if (app.olauncher.BuildConfig.DEBUG) android.util.Log.d("ShadeEdge", "Touch ${event.actionMasked}, home=${host.get()?.isNotificationHomeVisible}")
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { touching = true; pulled = false; startX = event.rawX; startY = event.rawY; pull.begin(startX, startY, true) }
                    MotionEvent.ACTION_MOVE -> if (pull.move(event.rawX, event.rawY)) {
                        pulled = true; host.get()?.openNotificationShade()
                    }
                    MotionEvent.ACTION_UP -> {
                        // A fast swipe may deliver its final distance only in the up event.
                        if (pull.move(event.rawX, event.rawY)) { pulled = true; host.get()?.openNotificationShade() }
                        touching = false; pull.cancel()
                        if (!pulled && kotlin.math.abs(event.rawX - startX) < 8 * density && kotlin.math.abs(event.rawY - startY) < 8 * density) v.performClick()
                        if (pulled) recoverNativeGesture()
                        sync()
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        if (pulled) recoverNativeGesture()
                        touching = false; pulled = true; pull.cancel(); sync()
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> { pulled = true; pull.cancel() }
                }
                true
            }
        }
        // Fully transparent windows need not draw on older Android versions.
        view.doOnLayout { view.postOnAnimation { if (strip === view) readyForInput = true } }
        val height = (12 * density).toInt().coerceAtLeast(1)
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            title = getString(R.string.shade_edge_label)
            if (android.os.Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
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
        fun refresh() { instance?.sync() }
        fun cancelRecovery() { instance?.handler?.removeCallbacksAndMessages(null) }
    }
}
