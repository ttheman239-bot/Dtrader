package com.dtrader.clipcapture.capture

import com.dtrader.clipcapture.config.CaptureConfig
import org.json.JSONArray
import org.json.JSONObject

enum class QcStatus(val label: String) { PASS("ผ่าน"), WARN("ผ่าน (มีคำเตือน)"), RETAKE("ควรถ่ายใหม่") }

/**
 * Statistics gathered from every analysed frame while a clip is recording.
 * These come from the live analysis (≈ analysis_fps), not from decoding the
 * file — the full per-frame QC of §5.1 is milestone M3.
 */
class LiveStats {
    var frames = 0
    var noFaceFrames = 0
    var jawMax = 0f
    var jawSum = 0.0
    var handOverMouthFrames = 0
    var handOverMouthFirstSec = -1f
    var handOverMouthLastSec = -1f
    var faceSizeOutFrames = 0
    var faceOutOfFrameFrames = 0
    var scoreMin = 100f
    var scoreSum = 0.0
    var scoredFrames = 0

    fun toJson() = JSONObject().apply {
        put("frames", frames)
        put("no_face_frames", noFaceFrames)
        put("jaw_open_max", jawMax.toDouble())
        put("jaw_open_mean", if (frames > 0) jawSum / frames else 0.0)
        put("hand_over_mouth_frames", handOverMouthFrames)
        put("hand_over_mouth_from_sec", handOverMouthFirstSec.toDouble())
        put("hand_over_mouth_to_sec", handOverMouthLastSec.toDouble())
        put("face_size_out_frames", faceSizeOutFrames)
        put("face_out_of_frame_frames", faceOutOfFrameFrames)
        put("pose_score_min", scoreMin.toDouble())
        put("pose_score_mean", if (scoredFrames > 0) scoreSum / scoredFrames else 0.0)
    }
}

data class QcSummary(val status: QcStatus, val reasons: List<String>) {
    fun reasonsJson() = JSONArray(reasons).toString()
}

object LiveQc {
    /** Shortest clip that can still be 8 s after trimming (§5.1 length rule). */
    private const val MIN_LENGTH_SEC = 8f

    fun evaluate(s: LiveStats, durationSec: Float, flags: List<String>, cfg: CaptureConfig): QcSummary {
        val retake = mutableListOf<String>()
        val warn = mutableListOf<String>()
        if (s.frames == 0) retake += "ไม่มีเฟรมที่วิเคราะห์ได้"
        if (s.noFaceFrames > 0) retake += "ไม่พบหน้า ${s.noFaceFrames} จาก ${s.frames} เฟรมที่วิเคราะห์"
        when {
            s.jawMax > cfg.jawOpenReject -> retake += "ปากเปิดเกิน (jawOpen สูงสุด %.2f > %.2f)".format(s.jawMax, cfg.jawOpenReject)
            s.jawMax > cfg.jawOpenWarn -> warn += "ปากเปิดบางช่วง (jawOpen สูงสุด %.2f > %.2f)".format(s.jawMax, cfg.jawOpenWarn)
        }
        if (s.handOverMouthFrames > 0) {
            retake += "มือบังปากช่วง %.1f–%.1f วินาที".format(s.handOverMouthFirstSec, s.handOverMouthLastSec)
        }
        if (durationSec < MIN_LENGTH_SEC) retake += "สั้นเกินไป (%.1f วินาที < %.0f)".format(durationSec, MIN_LENGTH_SEC)
        if (durationSec > cfg.clipMaxSec + 1f) warn += "ยาวเกิน (%.1f วินาที)".format(durationSec)
        if (s.faceSizeOutFrames > 0) warn += "ขนาดหน้าต่างจากท่ากลางเกิน ${cfg.faceSizeTolPct.toInt()}% (${s.faceSizeOutFrames} เฟรม)"
        if (s.faceOutOfFrameFrames > 0) warn += "หน้าชิดขอบ/หลุดเฟรม ${s.faceOutOfFrameFrames} เฟรม"
        if ("end_not_neutral" in flags) warn += "จบคลิปโดยไม่กลับท่ากลาง (ครบ ${cfg.clipMaxSec.toInt()} วินาที)"
        val status = when {
            retake.isNotEmpty() -> QcStatus.RETAKE
            warn.isNotEmpty() -> QcStatus.WARN
            else -> QcStatus.PASS
        }
        return QcSummary(status, retake + warn)
    }
}
