package com.dtrader.clipcapture

import com.dtrader.clipcapture.capture.Segmenter
import com.dtrader.clipcapture.config.CaptureConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmenterTest {
    private val cfg = CaptureConfig()

    /** Feeds frames every 100 ms; [score] gives the pose score at a time. */
    private fun run(
        untilMs: Long,
        handOverMouthAt: LongRange? = null,
        score: (Long) -> Float,
    ): List<Segmenter.Event> {
        val s = Segmenter(cfg)
        val events = mutableListOf<Segmenter.Event>()
        var t = 0L
        while (t <= untilMs) {
            s.feed(Segmenter.Frame(t, score(t), 0.05f, handOverMouthAt?.contains(t) == true, true))?.let { events += it }
            t += 100
        }
        return events
    }

    private fun neutralMoveNeutral(moveFrom: Long, moveTo: Long, moveScore: Float = 40f): (Long) -> Float =
        { t -> if (t in moveFrom until moveTo) moveScore else 95f }

    @Test fun findsClipBetweenNeutralHolds() {
        val ev = run(15_000, score = neutralMoveNeutral(1_500, 13_000))
        assertEquals(1, ev.size)
        val f = ev[0] as Segmenter.Event.Found
        assertEquals(1_400L, f.startMs) // last neutral frame before moving
        assertTrue("end=${f.endMs}", f.endMs in 13_400..13_600)
    }

    @Test fun rejectsTooShort() {
        val ev = run(8_000, score = neutralMoveNeutral(1_500, 4_500))
        assertTrue(ev.single() is Segmenter.Event.Rejected)
    }

    @Test fun rejectsWithoutRealMovement() {
        val ev = run(15_000, score = neutralMoveNeutral(1_500, 13_000, moveScore = cfg.poseMatchStart - 8f))
        assertTrue(ev.single() is Segmenter.Event.Rejected)
    }

    @Test fun rejectsHandOverMouth() {
        val ev = run(15_000, handOverMouthAt = 5_000L..5_300L, score = neutralMoveNeutral(1_500, 13_000))
        assertTrue(ev.single() is Segmenter.Event.Rejected)
    }

    @Test fun neutralHoldTooShortDoesNotStartClip() {
        // Only 0.5 s of neutral at the start: no anchor, so nothing is found.
        val ev = run(15_000) { t -> if (t < 500 || t >= 13_000) 95f else 40f }
        assertTrue(ev.isEmpty())
    }

    @Test fun chainsSeveralClips() {
        val score: (Long) -> Float = { t ->
            when (t) {
                in 1_500 until 13_000 -> 40f
                in 14_500 until 26_000 -> 30f
                else -> 95f
            }
        }
        val ev = run(28_000, score = score)
        assertEquals(2, ev.count { it is Segmenter.Event.Found })
    }

    @Test fun dropsClipThatNeverReturns() {
        val ev = run(30_000, score = neutralMoveNeutral(1_500, 29_000))
        assertTrue(ev.isEmpty())
    }
}
