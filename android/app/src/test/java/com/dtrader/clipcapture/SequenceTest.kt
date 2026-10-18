package com.dtrader.clipcapture

import com.dtrader.clipcapture.config.CaptureConfig
import com.dtrader.clipcapture.sequence.SequencePlanner
import com.dtrader.clipcapture.sequence.TransitionMatrix
import com.dtrader.clipcapture.vision.FaceIdx
import com.dtrader.clipcapture.vision.FrameFeatures
import com.dtrader.clipcapture.vision.PoseIdx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SequenceTest {
    private val cfg = CaptureConfig()

    /** Synthetic frame: face ring shifted by [dx], head turned by [yaw]. */
    private fun frame(dx: Float = 0f, yaw: Float = 0f): FrameFeatures {
        val face = FloatArray(478 * 2)
        for (i in 0 until 478) {
            val a = i * 0.37
            face[i * 2] = 0.5f + dx + (0.06 * Math.cos(a)).toFloat()
            face[i * 2 + 1] = 0.35f + (0.08 * Math.sin(a)).toFloat()
        }
        face[FaceIdx.LEFT_EYE_OUTER * 2] = 0.45f + dx; face[FaceIdx.LEFT_EYE_OUTER * 2 + 1] = 0.33f
        face[FaceIdx.RIGHT_EYE_OUTER * 2] = 0.55f + dx; face[FaceIdx.RIGHT_EYE_OUTER * 2 + 1] = 0.33f
        val pose = FloatArray(33 * 3) { if (it % 3 == 2) 0.1f else 0f }
        fun p(i: Int, x: Float, y: Float) { pose[i * 3] = x; pose[i * 3 + 1] = y; pose[i * 3 + 2] = 0.99f }
        p(PoseIdx.LEFT_SHOULDER, 0.35f + dx, 0.6f)
        p(PoseIdx.RIGHT_SHOULDER, 0.65f + dx, 0.6f)
        return FrameFeatures(0, 360, 640, face, 0f, yaw, 0f, 0f, pose, emptyList())
    }

    @Test fun matchingEdgesAreCheapAndAllowed() {
        val m = TransitionMatrix.compute(listOf(frame(), frame()), listOf(frame(), frame(dx = 0.002f)), cfg)
        assertEquals(0f, m.cost(0, 0), 0.001f)
        assertTrue(m.allowed(0, 1))
        assertEquals(cfg.crossfadeLow, m.crossfadeFrames(0, 1))
    }

    @Test fun differentPoseIsRejected() {
        val m = TransitionMatrix.compute(listOf(frame(), frame()), listOf(frame(), frame(dx = 0.04f, yaw = 20f)), cfg)
        assertFalse("cost=${m.cost(0, 1)}", m.allowed(0, 1))
        assertEquals(cfg.crossfadeMax, m.crossfadeFrames(0, 1))
        assertEquals(listOf(0), m.deadEnds())
    }

    @Test fun missingFaceIsUnknown() {
        val noFace = frame().copy(face = null)
        val m = TransitionMatrix.compute(listOf(noFace, frame()), listOf(frame(), frame()), cfg)
        assertTrue(m.cost(0, 1).isNaN())
        assertFalse(m.allowed(0, 1))
        assertTrue(m.allowed(1, 0))
    }

    private fun allAllowed(n: Int) = TransitionMatrix(Array(n) { FloatArray(n) { 0.05f } }, cfg)

    @Test fun plannerNeverRepeatsRecentClips() {
        val n = 6
        val planner = SequencePlanner(allAllowed(n), List(n) { "calm" }, cfg.copy(noRepeatLastN = 4), Random(1))
        val seq = mutableListOf(planner.first(null))
        repeat(200) { seq += planner.next(seq.last(), null).clip }
        for (k in 1 until seq.size) {
            val recent = seq.subList(maxOf(0, k - 4), k)
            assertFalse("clip ${seq[k]} repeated within $recent", seq[k] in recent)
        }
    }

    @Test fun plannerPrefersTagAndOnlyAllowedCuts() {
        val n = 4
        val costs = Array(n) { FloatArray(n) { 0.05f } }
        costs[0][3] = 0.9f // 0 → 3 not allowed
        val tags = listOf("calm", "calm", "active", "active")
        val planner = SequencePlanner(TransitionMatrix(costs, cfg), tags, cfg.copy(noRepeatLastN = 0), Random(2))
        repeat(50) {
            val s = planner.next(0, "active")
            assertEquals(2, s.clip)
            assertFalse(s.forced)
        }
    }

    @Test fun plannerForcesCheapestOnDeadEnd() {
        val costs = arrayOf(floatArrayOf(0f, 0.6f, 0.4f), floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f))
        val planner = SequencePlanner(TransitionMatrix(costs, cfg), List(3) { "calm" }, cfg, Random(3))
        val s = planner.next(0, null)
        assertEquals(2, s.clip)
        assertTrue(s.forced)
    }
}
