package app.olauncher.notifications

/** Android playback positions use elapsed real time, not the wall clock. */
object MediaSeek {
    fun position(position: Long, updatedAt: Long, speed: Float, playing: Boolean, now: Long): Long? {
        if (position < 0 || playing && !speed.isFinite()) return null
        val elapsed = if (playing && updatedAt > 0) (now - updatedAt).coerceAtLeast(0) else 0
        return (position.toDouble() + elapsed * speed.toDouble()).coerceIn(0.0, Long.MAX_VALUE.toDouble()).toLong()
    }

    fun target(position: Long, delta: Long, duration: Long): Long {
        val upper = if (duration > 0) duration else Long.MAX_VALUE
        val target = if (delta > 0 && position > Long.MAX_VALUE - delta) Long.MAX_VALUE else position + delta
        return target.coerceIn(0, upper)
    }
}
