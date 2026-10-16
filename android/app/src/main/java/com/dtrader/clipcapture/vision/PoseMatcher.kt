package com.dtrader.clipcapture.vision

import com.dtrader.clipcapture.config.CaptureConfig
import kotlin.math.abs
import kotlin.math.hypot

/** Live warnings shown on the capture screen (§4.2). */
enum class LiveWarning(val text: String) {
    NO_FACE("ไม่พบหน้า"),
    FACE_OUT_OF_FRAME("หน้าหลุดขอบเฟรม"),
    FACE_TOO_SMALL("หน้าเล็กกว่าท่ากลาง (ถอยห่างไป)"),
    FACE_TOO_LARGE("หน้าใหญ่กว่าท่ากลาง (เข้าใกล้ไป)"),
    MOUTH_OPEN("ปากเปิด"),
    HAND_OVER_MOUTH("มือบังปาก"),
}

data class MatchResult(
    /** 0–100, or null when there is no face to compare. */
    val score: Float?,
    val face: Float?,
    val headAngle: Float?,
    val shoulders: Float?,
    val hands: Float?,
    val warnings: Set<LiveWarning>,
    val faceSizeRatio: Float?,
)

/**
 * Compares a live frame with the neutral-pose reference (§4.2).
 * Every component becomes a sub-score in [0, 1] (1 = identical, 0 = at or beyond
 * its tolerance from [CaptureConfig]); the score is their weighted mean × 100.
 * Components that can't be measured (e.g. shoulders hidden in both frames) are
 * left out of the mean rather than guessed.
 */
class PoseMatcher(private val ref: FrameFeatures, private val cfg: CaptureConfig) {

    private val refEye = ref.eyeDistance()
    private val refShoulder = ref.shoulderWidth(cfg.minLandmarkVisibility)
    private val refFaceBox = ref.faceBox()

    init {
        require(ref.hasFace && refEye != null && refEye > 0f) { "reference has no face" }
    }

    fun match(cur: FrameFeatures): MatchResult {
        val warnings = mutableSetOf<LiveWarning>()
        val face = cur.face
        if (face == null) {
            warnings += LiveWarning.NO_FACE
            return MatchResult(null, null, null, null, null, warnings, null)
        }

        // Face landmarks: mean distance of key points, in units of the reference eye distance.
        var sum = 0.0
        for (i in FaceIdx.KEY) {
            val (ax, ay) = cur.facePoint(i)
            val (bx, by) = ref.facePoint(i)
            sum += hypot(((ax - bx) * cur.width).toDouble(), ((ay - by) * cur.height).toDouble()) / cur.width
        }
        val faceErr = (sum / FaceIdx.KEY.size / refEye!!).toFloat()
        val faceSub = sub(faceErr, cfg.faceTolEyeDist)

        // Head angles.
        val angErr = maxOf(abs(cur.yaw - ref.yaw), abs(cur.pitch - ref.pitch), abs(cur.roll - ref.roll))
        val angSub = sub(angErr, cfg.headAngleTolDeg)

        // Shoulders.
        val shoulderSub = refShoulder?.let { sw ->
            val vis = cfg.minLandmarkVisibility
            if (cur.pose == null) return@let 0f
            val err = listOf(PoseIdx.LEFT_SHOULDER, PoseIdx.RIGHT_SHOULDER).map { i ->
                val c = cur.posePoint(i)
                if (c.third < vis) return@let 0f
                val r = ref.posePoint(i)
                hypot(((c.first - r.first) * cur.width).toDouble(), ((c.second - r.second) * cur.height).toDouble()) / cur.width / sw
            }.average().toFloat()
            sub(err, cfg.shoulderTolWidth)
        }

        // Wrists: compare each wrist that is visible in the reference; a wrist that was
        // visible but is now hidden (or the other way round) counts as a full miss.
        val handSub = refShoulder?.let { sw ->
            val vis = cfg.minLandmarkVisibility
            val subs = listOf(PoseIdx.LEFT_WRIST, PoseIdx.RIGHT_WRIST).mapNotNull { i ->
                val r = ref.posePoint(i)
                val c = cur.pose?.let { cur.posePoint(i) }
                val refVis = r.third >= vis
                val curVis = c != null && c.third >= vis
                when {
                    !refVis && !curVis -> null
                    refVis != curVis -> 0f
                    else -> sub(
                        (hypot(((c!!.first - r.first) * cur.width).toDouble(), ((c.second - r.second) * cur.height).toDouble()) / cur.width / sw).toFloat(),
                        cfg.handTolWidth,
                    )
                }
            }
            if (subs.isEmpty()) null else subs.average().toFloat()
        }

        val parts = listOfNotNull(
            faceSub to cfg.weightFace,
            angSub to cfg.weightHeadAngle,
            shoulderSub?.let { it to cfg.weightShoulders },
            handSub?.let { it to cfg.weightHands },
        )
        val wSum = parts.sumOf { it.second.toDouble() }
        val score = if (wSum > 0) (parts.sumOf { (it.first * it.second).toDouble() } / wSum * 100).toFloat() else null

        // Warnings.
        val box = cur.faceBox()!!
        val cx = (box[0] + box[2]) / 2
        val cy = (box[1] + box[3]) / 2
        val m = cfg.faceEdgeMargin
        if (box[0] < 0f || box[1] < 0f || box[2] > 1f || box[3] > 1f || cx < m || cx > 1 - m || cy < m || cy > 1 - m) {
            warnings += LiveWarning.FACE_OUT_OF_FRAME
        }
        val sizeRatio = refFaceBox?.let { rb -> (box[2] - box[0]) / (rb[2] - rb[0]) }
        if (sizeRatio != null) {
            val tol = cfg.faceSizeTolPct / 100f
            if (sizeRatio < 1 - tol) warnings += LiveWarning.FACE_TOO_SMALL
            if (sizeRatio > 1 + tol) warnings += LiveWarning.FACE_TOO_LARGE
        }
        if (cur.jawOpen > cfg.jawOpenWarn) warnings += LiveWarning.MOUTH_OPEN
        if (handOverMouth(cur, cfg.mouthRegionGrow)) warnings += LiveWarning.HAND_OVER_MOUTH

        return MatchResult(score, faceSub, angSub, shoulderSub, handSub, warnings, sizeRatio)
    }

    private fun sub(err: Float, tol: Float) = (1f - err / tol).coerceIn(0f, 1f)

    companion object {
        /** True if any detected hand's bounding box overlaps the (grown) mouth region. */
        fun handOverMouth(f: FrameFeatures, grow: Float): Boolean {
            val mouth = f.mouthBox(grow) ?: return false
            return f.hands.any { h ->
                val b = FrameFeatures.boxOf(h)
                b[0] < mouth[2] && b[2] > mouth[0] && b[1] < mouth[3] && b[3] > mouth[1]
            }
        }
    }
}
