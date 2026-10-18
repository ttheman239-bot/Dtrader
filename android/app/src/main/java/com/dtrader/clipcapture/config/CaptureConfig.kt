package com.dtrader.clipcapture.config

import android.content.Context
import org.json.JSONObject

/**
 * Every tunable threshold lives here (spec §8). Values are persisted as JSON in
 * SharedPreferences and edited on the Settings screen; nothing else in the app
 * hardcodes a threshold.
 */
data class CaptureConfig(
    // §8 spec keys
    val fps: Int = 25,
    val clipMinSec: Float = 10f,
    val clipMaxSec: Float = 20f,
    val poseMatchStart: Float = 78f,
    val poseMatchEnd: Float = 78f,
    val neutralHoldStartMs: Int = 700,
    val neutralHoldEndMs: Int = 400,
    val jawOpenWarn: Float = 0.25f,
    val jawOpenReject: Float = 0.45f,
    val loopSearchWindowSec: Float = 2.0f,
    val maxAlignShiftPct: Float = 3f,
    val maxAlignScalePct: Float = 4f,
    val crossfadeLow: Int = 3,
    val crossfadeMid: Int = 5,
    val crossfadeMax: Int = 6,
    val transitionCostReject: Float = 0.25f,
    val noRepeatLastN: Int = 4,

    // Real-time analysis (§4.2)
    val analysisFps: Int = 12,
    val calibrateAutoMs: Int = 2000,
    /** Seconds to get into position after pressing "save neutral pose". */
    val referenceCountdownSec: Int = 3,
    /** Tolerances: an error equal to the tolerance scores 0 for that component. */
    val faceTolEyeDist: Float = 0.35f,
    val headAngleTolDeg: Float = 15f,
    val shoulderTolWidth: Float = 0.2f,
    val handTolWidth: Float = 0.45f,
    val weightFace: Float = 0.35f,
    val weightHeadAngle: Float = 0.25f,
    val weightShoulders: Float = 0.25f,
    val weightHands: Float = 0.15f,
    /** Face size may differ from the reference by this much before warning. */
    val faceSizeTolPct: Float = 15f,
    /** Face centre must stay this far inside the frame edges (fraction of frame). */
    val faceEdgeMargin: Float = 0.05f,
    /** Mouth region used for the hand-over-mouth test, grown by this fraction. */
    val mouthRegionGrow: Float = 0.5f,
    /** Pose landmark visibility needed to treat a wrist as visible. */
    val minLandmarkVisibility: Float = 0.5f,

    // Continuous recording with automatic cutting (§4.4)
    /** A cut needs the pose score to dip at least this far below the neutral threshold in between. */
    val segmentMinMotion: Float = 15f,
    /** Seconds between new movement cues while recording. */
    val cueIntervalSec: Float = 8f,
    /** 1 = try MediaPipe GPU delegate (falls back to CPU), 0 = CPU only. */
    val useGpu: Int = 0,
    /** 1 = start the camera at its widest zoom (ultra-wide where available). */
    val wideAngle: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        FIELDS.forEach { f -> put(f.key, f.get(this@CaptureConfig)) }
    }

    companion object {
        /** Field metadata for the Settings screen and JSON (de)serialisation. */
        class Field(
            val key: String,
            val label: String,
            val isInt: Boolean,
            val get: (CaptureConfig) -> Number,
            val set: (CaptureConfig, Double) -> CaptureConfig,
        )

        private fun i(key: String, label: String, get: (CaptureConfig) -> Int, set: (CaptureConfig, Int) -> CaptureConfig) =
            Field(key, label, true, get, { c, v -> set(c, v.toInt()) })

        private fun f(key: String, label: String, get: (CaptureConfig) -> Float, set: (CaptureConfig, Float) -> CaptureConfig) =
            Field(key, label, false, get, { c, v -> set(c, v.toFloat()) })

        val FIELDS: List<Field> = listOf(
            i("fps", "fps", { it.fps }, { c, v -> c.copy(fps = v) }),
            f("clip_min_sec", "clip_min_sec (วินาที)", { it.clipMinSec }, { c, v -> c.copy(clipMinSec = v) }),
            f("clip_max_sec", "clip_max_sec (วินาที)", { it.clipMaxSec }, { c, v -> c.copy(clipMaxSec = v) }),
            f("pose_match_start", "pose_match_start (0–100)", { it.poseMatchStart }, { c, v -> c.copy(poseMatchStart = v) }),
            f("pose_match_end", "pose_match_end (0–100)", { it.poseMatchEnd }, { c, v -> c.copy(poseMatchEnd = v) }),
            i("neutral_hold_start_ms", "neutral_hold_start_ms", { it.neutralHoldStartMs }, { c, v -> c.copy(neutralHoldStartMs = v) }),
            i("neutral_hold_end_ms", "neutral_hold_end_ms", { it.neutralHoldEndMs }, { c, v -> c.copy(neutralHoldEndMs = v) }),
            f("jaw_open_warn", "jaw_open_warn", { it.jawOpenWarn }, { c, v -> c.copy(jawOpenWarn = v) }),
            f("jaw_open_reject", "jaw_open_reject", { it.jawOpenReject }, { c, v -> c.copy(jawOpenReject = v) }),
            f("loop_search_window_sec", "loop_search_window_sec (M3)", { it.loopSearchWindowSec }, { c, v -> c.copy(loopSearchWindowSec = v) }),
            f("max_align_shift_pct", "max_align_shift_pct (M4)", { it.maxAlignShiftPct }, { c, v -> c.copy(maxAlignShiftPct = v) }),
            f("max_align_scale_pct", "max_align_scale_pct (M4)", { it.maxAlignScalePct }, { c, v -> c.copy(maxAlignScalePct = v) }),
            i("crossfade_frames_low", "crossfade_frames low (M5)", { it.crossfadeLow }, { c, v -> c.copy(crossfadeLow = v) }),
            i("crossfade_frames_mid", "crossfade_frames mid (M5)", { it.crossfadeMid }, { c, v -> c.copy(crossfadeMid = v) }),
            i("crossfade_frames_max", "crossfade_frames max (M5)", { it.crossfadeMax }, { c, v -> c.copy(crossfadeMax = v) }),
            f("transition_cost_reject", "transition_cost_reject (M5)", { it.transitionCostReject }, { c, v -> c.copy(transitionCostReject = v) }),
            i("no_repeat_last_n", "no_repeat_last_n (M5)", { it.noRepeatLastN }, { c, v -> c.copy(noRepeatLastN = v) }),
            i("analysis_fps", "analysis_fps (real-time)", { it.analysisFps }, { c, v -> c.copy(analysisFps = v) }),
            i("calibrate_auto_ms", "calibrate_auto_ms", { it.calibrateAutoMs }, { c, v -> c.copy(calibrateAutoMs = v) }),
            i("reference_countdown_sec", "reference_countdown_sec (นับถอยหลังก่อนบันทึกท่ากลาง)", { it.referenceCountdownSec }, { c, v -> c.copy(referenceCountdownSec = v) }),
            f("face_tol_eye_dist", "face_tol (× ระยะตา)", { it.faceTolEyeDist }, { c, v -> c.copy(faceTolEyeDist = v) }),
            f("head_angle_tol_deg", "head_angle_tol (องศา)", { it.headAngleTolDeg }, { c, v -> c.copy(headAngleTolDeg = v) }),
            f("shoulder_tol_width", "shoulder_tol (× ความกว้างไหล่)", { it.shoulderTolWidth }, { c, v -> c.copy(shoulderTolWidth = v) }),
            f("hand_tol_width", "hand_tol (× ความกว้างไหล่)", { it.handTolWidth }, { c, v -> c.copy(handTolWidth = v) }),
            f("weight_face", "weight_face", { it.weightFace }, { c, v -> c.copy(weightFace = v) }),
            f("weight_head_angle", "weight_head_angle", { it.weightHeadAngle }, { c, v -> c.copy(weightHeadAngle = v) }),
            f("weight_shoulders", "weight_shoulders", { it.weightShoulders }, { c, v -> c.copy(weightShoulders = v) }),
            f("weight_hands", "weight_hands", { it.weightHands }, { c, v -> c.copy(weightHands = v) }),
            f("face_size_tol_pct", "face_size_tol (%)", { it.faceSizeTolPct }, { c, v -> c.copy(faceSizeTolPct = v) }),
            f("face_edge_margin", "face_edge_margin (สัดส่วนเฟรม)", { it.faceEdgeMargin }, { c, v -> c.copy(faceEdgeMargin = v) }),
            f("mouth_region_grow", "mouth_region_grow", { it.mouthRegionGrow }, { c, v -> c.copy(mouthRegionGrow = v) }),
            f("min_landmark_visibility", "min_landmark_visibility", { it.minLandmarkVisibility }, { c, v -> c.copy(minLandmarkVisibility = v) }),
            f("segment_min_motion", "segment_min_motion (คะแนนที่ต้องลดลงระหว่างท่า)", { it.segmentMinMotion }, { c, v -> c.copy(segmentMinMotion = v) }),
            f("cue_interval_sec", "cue_interval_sec (เปลี่ยนคำแนะนำท่าทุกกี่วินาที)", { it.cueIntervalSec }, { c, v -> c.copy(cueIntervalSec = v) }),
            i("use_gpu", "use_gpu (0 = CPU, 1 = GPU)", { it.useGpu }, { c, v -> c.copy(useGpu = v) }),
            i("wide_angle", "wide_angle (1 = มุมกว้างสุด)", { it.wideAngle }, { c, v -> c.copy(wideAngle = v) }),
        )

        fun fromJson(json: JSONObject): CaptureConfig =
            FIELDS.fold(CaptureConfig()) { c, f -> if (json.has(f.key)) f.set(c, json.getDouble(f.key)) else c }
    }
}

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("capture_config", Context.MODE_PRIVATE)

    fun load(): CaptureConfig =
        prefs.getString(KEY, null)?.let { runCatching { CaptureConfig.fromJson(JSONObject(it)) }.getOrNull() }
            ?: CaptureConfig()

    fun save(config: CaptureConfig) {
        prefs.edit().putString(KEY, config.toJson().toString()).apply()
    }

    private companion object {
        const val KEY = "config_json"
    }
}
