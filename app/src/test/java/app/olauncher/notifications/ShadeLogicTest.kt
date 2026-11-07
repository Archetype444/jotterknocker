package app.olauncher.notifications

import org.junit.Assert.*
import org.junit.Test

class ShadeLogicTest {
    @Test fun playingPositionUsesMonotonicTimeAndPlaybackSpeed() {
        assertEquals(13_000L, MediaSeek.position(10_000, 1000, 1.5f, true, 3000))
    }
    @Test fun pausedPositionDoesNotAdvance() { assertEquals(10_000L, MediaSeek.position(10_000, 1000, 1f, false, 9999)) }
    @Test fun invalidSpeedCannotCauseAJumpToTheBeginning() {
        assertNull(MediaSeek.position(1000, 1000, Float.NaN, true, 2000))
        assertNull(MediaSeek.position(1000, 1000, Float.POSITIVE_INFINITY, true, 2000))
    }
    @Test fun missingPositionCannotBeSought() { assertNull(MediaSeek.position(-1, 1000, 1f, true, 3000)) }
    @Test fun missingTimestampDoesNotInventElapsedPlayback() { assertEquals(500L, MediaSeek.position(500, 0, 1f, true, 9999)) }
    @Test fun futureTimestampDoesNotRunBackwards() { assertEquals(500L, MediaSeek.position(500, 9999, 1f, true, 1000)) }
    @Test fun fifteenSecondJumpsAreExact() {
        assertEquals(45_000L, MediaSeek.target(30_000, 15_000, 120_000))
        assertEquals(15_000L, MediaSeek.target(30_000, -15_000, 120_000))
    }
    @Test fun seekingClampsToStartAndEnd() {
        assertEquals(0L, MediaSeek.target(5000, -15_000, 120_000))
        assertEquals(120_000L, MediaSeek.target(115_000, 15_000, 120_000))
    }
    @Test fun unknownDurationStillAllowsSeekingForward() { assertEquals(45_000L, MediaSeek.target(30_000, 15_000, 0)) }
    @Test fun positionOverflowIsSaturated() { assertEquals(Long.MAX_VALUE, MediaSeek.target(Long.MAX_VALUE - 2, 15_000, 0)) }
    @Test fun repeatedJumpsAccumulateAndClamp() {
        var position = 10_000L
        repeat(20) { position = MediaSeek.target(position, 15_000, 120_000) }
        assertEquals(120_000L, position)
        repeat(20) { position = MediaSeek.target(position, -15_000, 120_000) }
        assertEquals(0L, position)
    }
    @Test fun downwardDragMustStartAtTheTopEdge() {
        val pull = TopEdgePull(24f)
        pull.begin(10f, 100f, false)
        assertFalse(pull.move(10f, 400f))
    }
    @Test fun edgeDragOpensOnceAfterThreshold() {
        val pull = TopEdgePull(24f)
        pull.begin(10f, 1f, true)
        assertFalse(pull.move(10f, 10f))
        assertTrue(pull.move(10f, 30f))
        assertFalse(pull.move(10f, 200f))
    }
    @Test fun horizontalAndUpwardGesturesCannotTurnIntoAnEdgePull() {
        val pull = TopEdgePull(24f)
        pull.begin(10f, 1f, true)
        assertFalse(pull.move(100f, 5f))
        assertFalse(pull.move(100f, 200f))
        pull.begin(10f, 100f, true)
        assertFalse(pull.move(10f, 50f))
        assertFalse(pull.move(10f, 300f))
    }
    @Test fun cancelDiscardsTheGesture() {
        val pull = TopEdgePull(24f)
        pull.begin(10f, 1f, true); pull.cancel()
        assertFalse(pull.move(10f, 300f))
    }
}
