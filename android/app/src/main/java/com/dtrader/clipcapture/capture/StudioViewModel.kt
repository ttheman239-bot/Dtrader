package com.dtrader.clipcapture.capture

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.dtrader.clipcapture.camera.CameraCaps
import com.dtrader.clipcapture.camera.CameraLockValues
import com.dtrader.clipcapture.camera.CameraRig
import com.dtrader.clipcapture.config.CaptureConfig
import com.dtrader.clipcapture.config.ConfigStore
import com.dtrader.clipcapture.data.AppDatabase
import com.dtrader.clipcapture.data.ClipEntity
import com.dtrader.clipcapture.data.SessionEntity
import com.dtrader.clipcapture.vision.FrameAnalyzer
import com.dtrader.clipcapture.vision.FrameFeatures
import com.dtrader.clipcapture.vision.Landmarkers
import com.dtrader.clipcapture.vision.LiveWarning
import com.dtrader.clipcapture.vision.MatchResult
import com.dtrader.clipcapture.vision.PoseMatcher
import com.dtrader.clipcapture.vision.SessionFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

enum class Phase { IDLE, WAIT_NEUTRAL, RECORD_MOVE, RECORD_RETURN, SAVING }

data class StudioUi(
    val session: SessionEntity? = null,
    val config: CaptureConfig = CaptureConfig(),
    val caps: CameraCaps? = null,
    val error: String? = null,
    val message: String? = null,
    val calibrating: Boolean = false,
    val locked: Boolean = false,
    val lockNotes: List<String> = emptyList(),
    val delegates: String = "",
    val analysisFps: Float = 0f,
    val inferenceMs: Long = 0,
    val hasFace: Boolean = false,
    val match: MatchResult? = null,
    val reference: Bitmap? = null,
    val ghost: Boolean = true,
    val phase: Phase = Phase.IDLE,
    val elapsedMs: Long = 0,
    val instruction: String? = null,
    val tag: String = "calm",
    val continuous: Boolean = true,
    val lastClip: Pair<Int, QcSummary>? = null,
    val clipCount: Int = 0,
)

/** Setup/Calibrate and Capture for one session (§4.1–4.3). */
class StudioViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    private val configStore = ConfigStore(app)
    private val rig = CameraRig(app)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var landmarkers: Landmarkers? = null
    private var analyzer: FrameAnalyzer? = null

    private val _ui = MutableStateFlow(StudioUi(config = configStore.load()))
    val ui: StateFlow<StudioUi> = _ui

    private var files: SessionFiles? = null
    private var matcher: PoseMatcher? = null
    @Volatile private var latest: Pair<FrameFeatures, Bitmap>? = null

    // State machine bookkeeping (touched only on the analysis thread).
    private var neutralSince = -1L
    private var recording: Recording? = null
    private var recordStart = 0L
    private var recordFile: File? = null
    private var recordNumber = 0
    private var stats = LiveStats()
    private var endFlags = mutableListOf<String>()
    private var lastInstruction: String? = null
    private var fpsWindowStart = 0L
    private var fpsWindowFrames = 0

    fun load(sessionId: Long) {
        if (_ui.value.session?.id == sessionId) return
        viewModelScope.launch {
            val s = db.sessions().get(sessionId) ?: return@launch
            val f = SessionFiles(getApplication(), s.id)
            files = f
            val ref = withContext(Dispatchers.IO) { f.loadReferenceFeatures() to f.loadReferenceBitmap() }
            ref.first?.let { matcher = runCatching { PoseMatcher(it, _ui.value.config) }.getOrNull() }
            val count = db.clips().maxNumber(s.id)
            _ui.update { it.copy(session = s, reference = ref.second, clipCount = count) }
        }
    }

    /** Binds the camera to the screen. Called from the composable once the PreviewView exists. */
    fun bindCamera(owner: LifecycleOwner, previewView: PreviewView) {
        val s = _ui.value.session ?: return
        viewModelScope.launch {
            try {
                val lm = landmarkers ?: withContext(Dispatchers.Default) { Landmarkers.create(getApplication()) }.also { landmarkers = it }
                val a = analyzer ?: FrameAnalyzer(lm, _ui.value.config.analysisFps, ::onFrame).also { analyzer = it }
                val caps = rig.bind(owner, previewView, s.lensFacing, _ui.value.config.fps, analysisExecutor, a)
                _ui.update { it.copy(caps = caps, delegates = lm.delegates, error = null, locked = false, lockNotes = emptyList()) }
                // Re-apply a previous calibration if the hardware allows manual values.
                s.cameraSettingsJson?.let { json ->
                    val v = CameraLockValues.fromJson(JSONObject(json))
                    try {
                        val notes = rig.applyLock(v, freshCalibration = false)
                        _ui.update {
                            it.copy(locked = true, lockNotes = notes + "ใช้ค่าที่ Calibrate ไว้ (${java.text.DateFormat.getDateTimeInstance().format(v.calibratedAt)}) — แสงห้องอาจต่างจากตอนนั้น ถ้าภาพต่างไปให้ Calibrate ใหม่")
                        }
                    } catch (e: Exception) {
                        _ui.update { it.copy(lockNotes = listOf(e.message ?: "ใช้ค่าเดิมไม่ได้"), message = "กด Calibrate ก่อนถ่าย") }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "bind failed", e)
                _ui.update { it.copy(error = e.message ?: e.toString()) }
            }
        }
    }

    fun unbindCamera() {
        if (recording != null) cancelRecording()
        rig.unbind()
    }

    fun calibrate() {
        val s = _ui.value.session ?: return
        if (_ui.value.phase != Phase.IDLE) return
        viewModelScope.launch {
            _ui.update { it.copy(calibrating = true, locked = false, message = "กล้องกำลังปรับอัตโนมัติ ${_ui.value.config.calibrateAutoMs / 1000.0} วินาที… อยู่นิ่งๆ") }
            try {
                val (values, notes) = rig.calibrate(_ui.value.config.calibrateAutoMs)
                val updated = s.copy(cameraSettingsJson = values.toJson().toString())
                db.sessions().update(updated)
                _ui.update { it.copy(session = updated, calibrating = false, locked = true, lockNotes = notes, message = "ล็อกค่ากล้องแล้ว") }
            } catch (e: Exception) {
                _ui.update { it.copy(calibrating = false, message = "Calibrate ไม่สำเร็จ: ${e.message}") }
            }
        }
    }

    /** §4.1 step 4: store the current frame + landmarks as the neutral pose. */
    fun saveReference() {
        val s = _ui.value.session ?: return
        val f = files ?: return
        val (features, bitmap) = latest ?: run { _ui.update { it.copy(message = "ยังไม่มีภาพจากกล้อง") }; return }
        val cfg = _ui.value.config
        when {
            !features.hasFace -> { _ui.update { it.copy(message = "บันทึกไม่ได้: ไม่พบหน้า") }; return }
            features.shoulderWidth(cfg.minLandmarkVisibility) == null -> { _ui.update { it.copy(message = "บันทึกไม่ได้: มองไม่เห็นไหล่ทั้งสองข้าง") }; return }
            features.jawOpen > cfg.jawOpenWarn -> { _ui.update { it.copy(message = "บันทึกไม่ได้: ปากเปิด — ปิดปากในท่ากลาง") }; return }
            PoseMatcher.handOverMouth(features, cfg.mouthRegionGrow) -> { _ui.update { it.copy(message = "บันทึกไม่ได้: มือบังปาก") }; return }
        }
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        viewModelScope.launch {
            withContext(Dispatchers.IO) { f.saveReference(copy, features) }
            matcher = PoseMatcher(features, cfg)
            val updated = s.copy(referenceImagePath = f.referenceImage.path, referenceDataPath = f.referenceData.path)
            db.sessions().update(updated)
            _ui.update { it.copy(session = updated, reference = copy, message = "บันทึกท่ากลางแล้ว") }
        }
    }

    fun setGhost(on: Boolean) = _ui.update { it.copy(ghost = on) }
    fun setTag(tag: String) = _ui.update { it.copy(tag = tag) }
    fun setContinuous(on: Boolean) = _ui.update { it.copy(continuous = on) }
    fun clearMessage() = _ui.update { it.copy(message = null) }

    /** §4.3 step 1: arm auto-capture; recording starts once the neutral pose is held. */
    fun start() {
        val u = _ui.value
        when {
            !u.locked -> { _ui.update { it.copy(message = "กด Calibrate เพื่อล็อกค่ากล้องก่อน") }; return }
            matcher == null -> { _ui.update { it.copy(message = "บันทึกท่ากลางก่อน") }; return }
            u.phase != Phase.IDLE -> return
        }
        analysisExecutor.execute {
            neutralSince = -1
            _ui.update { it.copy(phase = Phase.WAIT_NEUTRAL, instruction = "อยู่ในท่ากลาง แล้วแอปจะเริ่มอัดเอง", lastClip = null) }
        }
    }

    /** Stop button: disarm, or abandon the clip being recorded (file is deleted). */
    fun stop() {
        analysisExecutor.execute {
            when (_ui.value.phase) {
                Phase.WAIT_NEUTRAL -> _ui.update { it.copy(phase = Phase.IDLE, instruction = null) }
                Phase.RECORD_MOVE, Phase.RECORD_RETURN -> cancelRecording()
                else -> Unit
            }
        }
    }

    private fun cancelRecording() {
        endFlags = mutableListOf("cancelled")
        _ui.update { it.copy(phase = Phase.SAVING, continuous = false) }
        recording?.stop()
        recording = null
    }

    // ---- analysis thread ----

    private fun onFrame(features: FrameFeatures, bitmap: Bitmap, inferenceMs: Long) {
        latest = features to bitmap
        val now = SystemClock.elapsedRealtime()
        if (fpsWindowStart == 0L) fpsWindowStart = now
        fpsWindowFrames++
        val measuredFps = if (now - fpsWindowStart >= 1000) {
            (fpsWindowFrames * 1000f / (now - fpsWindowStart)).also { fpsWindowStart = now; fpsWindowFrames = 0 }
        } else null

        val cfg = _ui.value.config
        val m = matcher?.match(features)
        val score = m?.score
        val phase = _ui.value.phase

        val threshold = if (phase == Phase.RECORD_RETURN) cfg.poseMatchEnd else cfg.poseMatchStart
        if (score != null && score >= threshold) {
            if (neutralSince < 0) neutralSince = now
        } else {
            neutralSince = -1
        }
        val heldMs = if (neutralSince >= 0) now - neutralSince else 0

        when (phase) {
            Phase.WAIT_NEUTRAL -> if (heldMs >= cfg.neutralHoldStartMs) beginRecording(now)
            Phase.RECORD_MOVE, Phase.RECORD_RETURN -> {
                collectStats(features, m, now)
                val elapsed = now - recordStart
                if (phase == Phase.RECORD_MOVE && elapsed >= cfg.clipMinSec * 1000) {
                    neutralSince = -1
                    _ui.update { it.copy(phase = Phase.RECORD_RETURN, instruction = "กลับท่ากลาง") }
                } else if (phase == Phase.RECORD_RETURN) {
                    if (heldMs >= cfg.neutralHoldEndMs) finishRecording(emptyList())
                    else if (elapsed >= cfg.clipMaxSec * 1000) finishRecording(listOf("end_not_neutral"))
                }
                _ui.update { it.copy(elapsedMs = elapsed) }
            }
            else -> Unit
        }

        _ui.update {
            it.copy(
                match = m,
                hasFace = features.hasFace,
                inferenceMs = inferenceMs,
                analysisFps = measuredFps ?: it.analysisFps,
            )
        }
    }

    private fun beginRecording(now: Long) {
        val s = _ui.value.session ?: return
        val f = files ?: return
        recordNumber = _ui.value.clipCount + 1
        val file = f.rawClip(recordNumber)
        recordFile = file
        stats = LiveStats()
        endFlags = mutableListOf()
        recordStart = now
        val instruction = Instructions.next(_ui.value.tag, lastInstruction).also { lastInstruction = it }
        _ui.update { it.copy(phase = Phase.RECORD_MOVE, elapsedMs = 0, instruction = instruction) }
        try {
            recording = rig.startRecording(file) { event -> onRecordEvent(event, s.id) }
        } catch (e: Exception) {
            _ui.update { it.copy(phase = Phase.IDLE, message = "เริ่มอัดไม่ได้: ${e.message}") }
        }
    }

    private fun finishRecording(flags: List<String>) {
        endFlags = flags.toMutableList()
        _ui.update { it.copy(phase = Phase.SAVING, instruction = "กำลังบันทึก…") }
        recording?.stop()
        recording = null
    }

    private fun collectStats(f: FrameFeatures, m: MatchResult?, now: Long) {
        val s = stats
        s.frames++
        if (!f.hasFace) { s.noFaceFrames++; return }
        s.jawMax = maxOf(s.jawMax, f.jawOpen)
        s.jawSum += f.jawOpen
        val w = m?.warnings ?: emptySet()
        if (LiveWarning.HAND_OVER_MOUTH in w) {
            val sec = (now - recordStart) / 1000f
            if (s.handOverMouthFrames == 0) s.handOverMouthFirstSec = sec
            s.handOverMouthLastSec = sec
            s.handOverMouthFrames++
        }
        if (LiveWarning.FACE_TOO_SMALL in w || LiveWarning.FACE_TOO_LARGE in w) s.faceSizeOutFrames++
        if (LiveWarning.FACE_OUT_OF_FRAME in w) s.faceOutOfFrameFrames++
        m?.score?.let { s.scoreMin = minOf(s.scoreMin, it); s.scoreSum += it; s.scoredFrames++ }
    }

    private fun onRecordEvent(event: VideoRecordEvent, sessionId: Long) {
        if (event !is VideoRecordEvent.Finalize) return
        val file = recordFile ?: return
        val flags = endFlags.toList()
        val statsCopy = stats
        val cfg = _ui.value.config
        viewModelScope.launch {
            if (event.hasError() && event.error != VideoRecordEvent.Finalize.ERROR_NONE && !file.exists()) {
                _ui.update { it.copy(phase = Phase.IDLE, message = "บันทึกคลิปไม่สำเร็จ (error ${event.error})") }
                return@launch
            }
            if ("cancelled" in flags) {
                withContext(Dispatchers.IO) { file.delete() }
                _ui.update { it.copy(phase = Phase.IDLE, instruction = null, message = "ยกเลิกคลิปแล้ว") }
                return@launch
            }
            val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000
            val qc = LiveQc.evaluate(statsCopy, durationMs / 1000f, flags, cfg)
            db.clips().insert(
                ClipEntity(
                    sessionId = sessionId,
                    number = recordNumber,
                    rawPath = file.path,
                    createdAt = System.currentTimeMillis(),
                    tag = _ui.value.tag,
                    durationMs = durationMs,
                    flagsJson = JSONArray(flags).toString(),
                    qcStatus = qc.status.name,
                    qcReasonsJson = qc.reasonsJson(),
                    liveStatsJson = statsCopy.toJson().toString(),
                ),
            )
            _ui.update { it.copy(phase = Phase.IDLE, instruction = null, lastClip = recordNumber to qc, clipCount = recordNumber) }
            if (_ui.value.continuous) start()
        }
    }

    override fun onCleared() {
        rig.unbind()
        analysisExecutor.execute { landmarkers?.close() }
        analysisExecutor.shutdown()
    }

    private companion object {
        const val TAG = "Studio"
    }
}
