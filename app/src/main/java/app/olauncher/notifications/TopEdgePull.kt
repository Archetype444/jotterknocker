package app.olauncher.notifications

import kotlin.math.abs

/** Only a gesture that starts in the edge strip can open the shade. */
class TopEdgePull(private val threshold: Float) {
    private var x = 0f
    private var y = 0f
    private var candidate = false
    fun begin(x: Float, y: Float, withinEdge: Boolean) {
        this.x = x; this.y = y; candidate = withinEdge
    }
    fun move(x: Float, y: Float): Boolean {
        if (!candidate) return false
        val dx = abs(x - this.x)
        val dy = y - this.y
        if (dx > threshold && dx > abs(dy) || dy < -threshold) { cancel(); return false }
        if (dy >= threshold && dy > dx * 1.2f) { cancel(); return true }
        return false
    }
    fun cancel() { candidate = false }
}
