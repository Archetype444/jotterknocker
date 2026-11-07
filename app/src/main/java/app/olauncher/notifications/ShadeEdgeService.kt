package app.olauncher.notifications

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import app.olauncher.MainActivity
import app.olauncher.R
import java.lang.ref.WeakReference

/** A home-only trusted overlay can receive the physical edge above the system status bar. */
class ShadeEdgeService : AccessibilityService() {
    private var strip: View? = null
    private val manager get() = getSystemService(WINDOW_SERVICE) as WindowManager
    override fun onServiceConnected() { super.onServiceConnected(); instance = this; sync() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = sync()
    override fun onInterrupt() { removeStrip() }
    override fun onDestroy() { removeStrip(); if (instance === this) instance = null; super.onDestroy() }

    private fun sync() {
        if (host.get()?.isNotificationEdgeActive != true) { removeStrip(); return }
        if (strip != null) return
        val density = resources.displayMetrics.density
        val pull = TopEdgePull(24f * density)
        var pulled = false
        var startX = 0f
        var startY = 0f
        val view = View(this).apply {
            contentDescription = getString(R.string.shade_open)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnClickListener { host.get()?.openNotificationShade() }
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { pulled = false; startX = event.rawX; startY = event.rawY; pull.begin(startX, startY, true) }
                    MotionEvent.ACTION_MOVE -> if (pull.move(event.rawX, event.rawY)) {
                        pulled = true; host.get()?.openNotificationShade()
                    }
                    MotionEvent.ACTION_UP -> { pull.cancel(); if (!pulled && kotlin.math.abs(event.rawX - startX) < 8 * density && kotlin.math.abs(event.rawY - startY) < 8 * density) v.performClick() }
                    MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> { pulled = true; pull.cancel() }
                }
                true
            }
        }
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

    private fun removeStrip() { strip?.let { runCatching { manager.removeViewImmediate(it) } }; strip = null }

    companion object {
        private var instance: ShadeEdgeService? = null
        internal val edgeAttached get() = instance?.strip?.isAttachedToWindow == true
        private var host = WeakReference<MainActivity>(null)
        fun attach(activity: MainActivity) { host = WeakReference(activity); refresh() }
        fun detach(activity: MainActivity) { if (host.get() === activity) { host.clear(); refresh() } }
        fun refresh() { instance?.sync() }
    }
}
