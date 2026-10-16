package com.dtrader.clipcapture

import com.dtrader.clipcapture.capture.LiveQc
import com.dtrader.clipcapture.capture.LiveStats
import com.dtrader.clipcapture.capture.QcStatus
import com.dtrader.clipcapture.config.CaptureConfig
import com.dtrader.clipcapture.vision.FaceIdx
import com.dtrader.clipcapture.vision.FrameFeatures
import com.dtrader.clipcapture.vision.LiveWarning
import com.dtrader.clipcapture.vision.PoseIdx
import com.dtrader.clipcapture.vision.PoseMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseMatcherTest {
    private val cfg = CaptureConfig()

    /** A synthetic face: landmarks on a ring around (cx, cy), eyes 0.1 apart. */
    private fun frame(
        dx: Float = 0f, dy: Float = 0f, scale: Float = 1f, jaw: Float = 0f, yaw: Float = 0f,
        hands: List<FloatArray> = emptyList(), shoulderShift: Float = 0f,
    ): FrameFeatures {
        val face = FloatArray(478 * 2)
        for (i in 0 until 478) {
            val a = i * 0.37
            face[i * 2] = 0.5f + dx + (0.06 * Math.cos(a)).toFloat() * scale
            face[i * 2 + 1] = 0.35f + dy + (0.08 * Math.sin(a)).toFloat() * scale
        }
        fun set(i: Int, x: Float, y: Float) { face[i * 2] = 0.5f + dx + x * scale; face[i * 2 + 1] = 0.35f + dy + y * scale }
        set(FaceIdx.LEFT_EYE_OUTER, -0.05f, -0.02f)
        set(FaceIdx.RIGHT_EYE_OUTER, 0.05f, -0.02f)
        // Lips: a small box around (0, 0.05) relative to the face centre.
        listOf(61 to (-0.02f to 0.05f), 291 to (0.02f to 0.05f), 0 to (0f to 0.04f), 17 to (0f to 0.06f),
            13 to (0f to 0.045f), 14 to (0f to 0.055f), 37 to (-0.01f to 0.042f), 267 to (0.01f to 0.042f),
            84 to (-0.01f to 0.058f), 314 to (0.01f to 0.058f))
            .forEach { (i, xy) -> set(i, xy.first, xy.second) }
        val pose = FloatArray(33 * 3) { if (it % 3 == 2) 0.1f else 0f }
        fun p(i: Int, x: Float, y: Float) { pose[i * 3] = x; pose[i * 3 + 1] = y; pose[i * 3 + 2] = 0.99f }
        p(PoseIdx.LEFT_SHOULDER, 0.35f + shoulderShift, 0.6f)
        p(PoseIdx.RIGHT_SHOULDER, 0.65f + shoulderShift, 0.6f)
        return FrameFeatures(0, 360, 640, face, jaw, yaw, 0f, 0f, pose, hands)
    }

    @Test fun identicalFrameScores100() {
        val m = PoseMatcher(frame(), cfg).match(frame())
        assertEquals(100f, m.score!!, 0.01f)
        assertTrue(m.warnings.isEmpty())
    }

    @Test fun movingAwayLowersScore() {
        val ref = frame()
        val near = PoseMatcher(ref, cfg).match(frame(dx = 0.005f)).score!!
        val far = PoseMatcher(ref, cfg).match(frame(dx = 0.03f, yaw = 15f, shoulderShift = 0.05f)).score!!
        assertTrue("near=$near far=$far", near > far)
        assertTrue(far < cfg.poseMatchStart)
    }

    @Test fun mouthOpenWarns() {
        val m = PoseMatcher(frame(), cfg).match(frame(jaw = 0.5f))
        assertTrue(LiveWarning.MOUTH_OPEN in m.warnings)
    }

    @Test fun handOverMouthDetected() {
        val hand = FloatArray(42) { if (it % 2 == 0) 0.5f else 0.4f }
        val m = PoseMatcher(frame(), cfg).match(frame(hands = listOf(hand)))
        assertTrue(LiveWarning.HAND_OVER_MOUTH in m.warnings)
    }

    @Test fun faceSizeWarns() {
        val m = PoseMatcher(frame(), cfg).match(frame(scale = 1.4f))
        assertTrue(LiveWarning.FACE_TOO_LARGE in m.warnings)
    }

    @Test fun liveQcRejectsHandOverMouthAndShortClips() {
        val s = LiveStats().apply { frames = 100; handOverMouthFrames = 3; handOverMouthFirstSec = 2f; handOverMouthLastSec = 2.4f }
        assertEquals(QcStatus.RETAKE, LiveQc.evaluate(s, 12f, emptyList(), cfg).status)
        assertEquals(QcStatus.RETAKE, LiveQc.evaluate(LiveStats().apply { frames = 50 }, 5f, emptyList(), cfg).status)
        assertEquals(QcStatus.PASS, LiveQc.evaluate(LiveStats().apply { frames = 120 }, 12f, emptyList(), cfg).status)
        assertEquals(QcStatus.WARN, LiveQc.evaluate(LiveStats().apply { frames = 120 }, 20f, listOf("end_not_neutral"), cfg).status)
    }
}
