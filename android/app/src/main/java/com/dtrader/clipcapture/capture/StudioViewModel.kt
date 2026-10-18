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

enum class Phase { IDLE, STARTING, RECORDING, SAVING, CUTTING }

/** A clip found during the current recording. */
data class FoundSegment(val startMs: Long, val endMs: Long, val qc: QcSummary, val stats: LiveStats)

/** Short message shown as a banner; [id] changes on every new banner. */
data class Banner(val id: Long, val text: String, val good: Boolean)

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
    val modelsReady: Boolean = false,
    val analysisFps: Float = 0f,
    val inferenceMs: Long = 0,
    val hasFace: Boolean = false,
    val match: MatchResult? = null,
    val reference: Bitmap? = null,
    /** Landmarks of the neutral pose and of the latest frame, for the ghost outline. */
    val refFeatures: FrameFeatures? = null,
    val live: FrameFeatures? = null,
    /** How long the neutral pose has been held right now, in ms (0 if not neutral). */
    val heldMs: Long = 0,
    val ghost: Boolean = true,
    val wide: Boolean = true,
    val zoom: Float? = null,
    val phase: Phase = Phase.IDLE,
    val elapsedMs: Long = 0,
    val cue: String? = null,
    val tag: String = "calm",
    /** True while the user is back in neutral and a clip is being collected. */
    val collecting: Boolean = false,
    /** How much of the current clip has been collected (ms since its possible start), or null. */
    val collectedMs: Long? = null,
    /** Recent errors (time, message, location) for the diagnostics panel. */
    val errors: List<String> = emptyList(),
    val found: List<FoundSegment> = emptyList(),
    val banner: Banner? = null,
    val cutProgress: Pair<Int, Int>? = null,
    /** Seconds left before the neutral pose is saved, or null when not counting down. */
    val countdown: Int? = null,
    val clipCount: Int = 0,
)

/**
 * Setup (calibrate, neutral pose) and continuous capture with automatic cutting
 * (§4.1, §4.2, §4.4). Every camera/analysis callback is guarded: a failure is
 * shown on screen instead of crashing the app.
 */
class StudioViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    private val rig = CameraRig(app)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var landmarkers: Landmarkers? = null
    private var analyzer: FrameAnalyzer? = null

    private val _ui = MutableStateFlow(StudioUi(config = ConfigStore(app).load()))
    val ui: StateFlow<StudioUi> = _ui

    private var files: SessionFiles? = null
    private var matcher: PoseMatcher? = null
    @Volatile private var latest: Pair<FrameFeatures, Bitmap>? = null

    // Recording state (analysis thread + main thread; guarded by @Volatile where shared).
    @Volatile private var recording: Recording? = null
    @Volatile private var recordStartElapsed = 0L
    private var takeFile: File? = null
    private var segmenter: Segmenter? = null
    private var nextCueAt = 0L
    private var lastCue: String? = null
    private var bannerSeq = 0L
    private var neutralSince = -1L
    private var fpsWindowStart = 0L
    private var fpsWindowFrames = 0

    fun load(sessionId: Long) {
        if (_ui.value.session?.id == sessionId) return
        viewModelScope.launch {
            val s = db.sessions().get(sessionId) ?: return@launch
            val f = SessionFiles(getApplication(), s.id)
            files = f
            val (refFeatures, refBitmap) = withContext(Dispatchers.IO) { f.loadReferenceFeatures() to f.loadReferenceBitmap() }
            refFeatures?.let { matcher = runCatching { PoseMatcher(it, _ui.value.config) }.getOrNull() }
            val count = db.clips().maxNumber(s.id)
            _ui.update { it.copy(session = s, reference = refBitmap, refFeatures = refFeatures, clipCount = count, wide = it.config.wideAngle == 1) }
            // Finish cutting clips left over from an interrupted session.
            if (db.clips().pending(s.id).isNotEmpty()) cutPending()
        }
    }

    fun bindCamera(owner: LifecycleOwner, previewView: PreviewView) {
        val s = _ui.value.session ?: return
        viewModelScope.launch {
            try {
                val cfg = _ui.value.config
                val lm = landmarkers ?: withContext(Dispatchers.Default) { Landmarkers.create(getApplication(), cfg.useGpu == 1) }
                    .also { landmarkers = it }
                val a = analyzer ?: FrameAnalyzer(lm, cfg.analysisFps, ::onFrameSafe).also { analyzer = it }
                val caps = rig.bind(owner, previewView, s.lensFacing, cfg.fps, analysisExecutor, a)
                val zoom = rig.setWide(_ui.value.wide)
                _ui.update { it.copy(caps = caps, delegates = lm.delegates, modelsReady = true, error = null, locked = false, lockNotes = emptyList(), zoom = zoom) }
                s.cameraSettingsJson?.let { json ->
                    val v = CameraLockValues.fromJson(JSONObject(json))
                    try {
                        val notes = rig.applyLock(v, freshCalibration = false)
                        _ui.update {
                            it.copy(
                                locked = true,
                                lockNotes = notes + "ใช้ค่าที่ Calibrate ไว้ (${java.text.DateFormat.getDateTimeInstance().format(v.calibratedAt)}) — ถ้าแสงห้องต่างจากตอนนั้นให้ Calibrate ใหม่",
                            )
                        }
                    } catch (e: Exception) {
                        _ui.update { it.copy(lockNotes = listOf(e.message ?: "ใช้ค่าเดิมไม่ได้")) }
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "bind failed", e)
                _ui.update { it.copy(error = (e.message ?: e.toString())) }
            }
        }
    }

    fun unbindCamera() {
        if (recording != null) stopRecording()
        rig.unbind()
    }

    fun calibrate() {
        val s = _ui.value.session ?: return
        if (_ui.value.phase != Phase.IDLE) return
        viewModelScope.launch {
            _ui.update { it.copy(calibrating = true, locked = false) }
            try {
                val (values, notes) = rig.calibrate(_ui.value.config.calibrateAutoMs)
                val updated = s.copy(cameraSettingsJson = values.toJson().toString())
                db.sessions().update(updated)
                _ui.update { it.copy(session = updated, calibrating = false, locked = true, lockNotes = notes, banner = banner("ล็อกค่ากล้องแล้ว", true)) }
            } catch (e: Exception) {
                _ui.update { it.copy(calibrating = false, message = "Calibrate ไม่สำเร็จ: ${e.message}") }
            }
        }
    }

    private var countdownJob: kotlinx.coroutines.Job? = null

    /** Gives the user reference_countdown_sec to get into position, then saves the neutral pose. Pressing again cancels. */
    fun startReferenceCountdown() {
        countdownJob?.let { it.cancel(); countdownJob = null; _ui.update { u -> u.copy(countdown = null) }; return }
        if (_ui.value.phase != Phase.IDLE) return
        countdownJob = viewModelScope.launch {
            try {
                for (s in _ui.value.config.referenceCountdownSec downTo 1) {
                    _ui.update { it.copy(countdown = s) }
                    kotlinx.coroutines.delay(1000)
                }
                _ui.update { it.copy(countdown = null) }
                saveReference()
            } finally {
                if (countdownJob === coroutineContext[kotlinx.coroutines.Job]) {
                    countdownJob = null
                    _ui.update { it.copy(countdown = null) }
                }
            }
        }
    }

    fun saveReference() {
        val s = _ui.value.session ?: return
        val f = files ?: return
        val (features, bitmap) = latest ?: run { _ui.update { it.copy(message = "ยังไม่มีภาพจากกล้อง") }; return }
        val cfg = _ui.value.config
        val problem = when {
            !features.hasFace -> "ไม่พบหน้า"
            features.shoulderWidth(cfg.minLandmarkVisibility) == null -> "มองไม่เห็นไหล่ทั้งสองข้าง — ถอยห่างกล้องอีกนิด"
            features.jawOpen > cfg.jawOpenWarn -> "ปากเปิด — ปิดปากในท่ากลาง"
            PoseMatcher.handOverMouth(features, cfg.mouthRegionGrow) -> "มือบังปาก"
            else -> null
        }
        if (problem != null) { _ui.update { it.copy(message = "บันทึกท่ากลางไม่ได้: $problem") }; return }
        val copy = try {
            if (bitmap.isRecycled) null else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } catch (e: Throwable) {
            null
        } ?: run { _ui.update { it.copy(message = "ภาพจากกล้องยังไม่พร้อม ลองกดอีกครั้ง") }; return }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { f.saveReference(copy, features) }
                matcher = PoseMatcher(features, cfg)
                val updated = s.copy(referenceImagePath = f.referenceImage.path, referenceDataPath = f.referenceData.path)
                db.sessions().update(updated)
                _ui.update { it.copy(session = updated, reference = copy, refFeatures = features, banner = banner("บันทึกท่ากลางแล้ว", true)) }
            } catch (e: Exception) {
                _ui.update { it.copy(message = "บันทึกท่ากลางไม่ได้: ${e.message}") }
            }
        }
    }

    fun setGhost(on: Boolean) = _ui.update { it.copy(ghost = on) }
    fun setTag(tag: String) = _ui.update { it.copy(tag = tag) }
    fun clearMessage() = _ui.update { it.copy(message = null) }

    fun setWide(on: Boolean) {
        val zoom = runCatching { rig.setWide(on) }.getOrNull()
        _ui.update {
            it.copy(
                wide = on, zoom = zoom,
                message = if (it.reference != null) "เปลี่ยนมุมกล้องแล้ว — บันทึกท่ากลางใหม่เพื่อให้คะแนนตรง" else it.message,
            )
        }
    }

    /** Starts one continuous recording; clips are found while it runs. */
    fun startRecording() {
        val u = _ui.value
        val f = files ?: return
        when {
            u.phase != Phase.IDLE -> return
            !u.locked -> { _ui.update { it.copy(message = "กด Calibrate ก่อน (ล็อกแสง/สี/โฟกัส)") }; return }
            matcher == null -> { _ui.update { it.copy(message = "บันทึกท่ากลางก่อน") }; return }
        }
        val file = f.take(System.currentTimeMillis())
        takeFile = file
        recordStartElapsed = 0
        // A speech take is kept whole for the mouth library; no clips are cut from it.
        val speech = u.tag == SPEECH_TAG
        analysisExecutor.execute { segmenter = if (speech) null else Segmenter(_ui.value.config) }
        _ui.update { it.copy(phase = Phase.STARTING, found = emptyList(), elapsedMs = 0, collecting = false, cue = null) }
        try {
            recording = rig.startRecording(file) { event -> onRecordEvent(event) }
        } catch (e: Throwable) {
            Log.e(TAG, "start failed", e)
            recording = null
            _ui.update { it.copy(phase = Phase.IDLE, message = "เริ่มอัดไม่ได้: ${e.message ?: e}") }
        }
    }

    fun stopRecording() {
        val r = recording ?: return
        recording = null
        _ui.update { it.copy(phase = Phase.SAVING, cue = null, collecting = false) }
        try { r.stop() } catch (e: Throwable) { Log.e(TAG, "stop failed", e) }
    }

    private fun onRecordEvent(event: VideoRecordEvent) {
        try {
            when (event) {
                is VideoRecordEvent.Start -> {
                    recordStartElapsed = SystemClock.elapsedRealtime()
                    nextCueAt = 0
                    val text = if (_ui.value.tag == SPEECH_TAG) "เริ่มอัดแล้ว — พูดไปเรื่อย ๆ หน้าตรงทับเงา" else "เริ่มอัดแล้ว — ระหว่างท่าให้กลับมาทับเงาจนเป็นสีเขียว"
                    _ui.update { it.copy(phase = Phase.RECORDING, banner = banner(text, true)) }
                }
                is VideoRecordEvent.Finalize -> onFinalized(event)
                else -> Unit
            }
        } catch (e: Throwable) {
            Log.e(TAG, "record event", e)
            logError("อัดวิดีโอ", e)
            _ui.update { it.copy(phase = Phase.IDLE, message = "ผิดพลาดระหว่างอัด: ${e.message ?: e}") }
        }
    }

    private fun onFinalized(event: VideoRecordEvent.Finalize) {
        recording = null
        val file = takeFile
        val found = _ui.value.found
        val s = _ui.value.session
        if (file == null || s == null) { _ui.update { it.copy(phase = Phase.IDLE) }; return }
        if (event.hasError() && !file.exists()) {
            _ui.update { it.copy(phase = Phase.IDLE, message = "บันทึกวิดีโอไม่สำเร็จ (error ${event.error}: ${event.cause?.message ?: ""})") }
            return
        }
        if (_ui.value.tag == SPEECH_TAG) { keepSpeechTake(file); return }
        viewModelScope.launch {
            if (found.isEmpty()) {
                withContext(Dispatchers.IO) { file.delete() }
                _ui.update { it.copy(phase = Phase.IDLE, message = "เทคนี้ไม่พบท่าที่ใช้ได้ — ทำท่าแล้วกลับมาทับเงาจนเป็นสีเขียว ให้แต่ละท่ายาว ${it.config.clipMinSec.toInt()}–${it.config.clipMaxSec.toInt()} วินาที") }
                return@launch
            }
            var number = db.clips().maxNumber(s.id)
            for (seg in found) {
                number++
                db.clips().insert(
                    ClipEntity(
                        sessionId = s.id, number = number, rawPath = file.path,
                        startMs = seg.startMs, endMs = seg.endMs,
                        createdAt = System.currentTimeMillis(), tag = _ui.value.tag,
                        durationMs = seg.endMs - seg.startMs,
                        flagsJson = "[]", qcStatus = seg.qc.status.name, qcReasonsJson = seg.qc.reasonsJson(),
                        liveStatsJson = seg.stats.toJson().toString(),
                    ),
                )
            }
            _ui.update { it.copy(clipCount = number) }
            cutPending()
        }
    }

    /** Keeps a speech take as the session's mouth-library source (replacing the old one). */
    private fun keepSpeechTake(file: File) {
        val f = files ?: return
        viewModelScope.launch {
            val secs = withContext(Dispatchers.IO) {
                val r = android.media.MediaMetadataRetriever()
                val ms = try { r.setDataSource(file.path); r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L }
                    catch (e: Exception) { 0L } finally { r.release() }
                ms / 1000f
            }
            if (secs < MIN_SPEECH_SEC) {
                withContext(Dispatchers.IO) { file.delete() }
                _ui.update { it.copy(phase = Phase.IDLE, message = "เทคพูดสั้นไป (%.0f วินาที) — พูดอย่างน้อย %d วินาที".format(secs, MIN_SPEECH_SEC)) }
                return@launch
            }
            withContext(Dispatchers.IO) { f.replaceSpeechTake(file) }
            _ui.update { it.copy(phase = Phase.IDLE, banner = banner("บันทึกเทคพูดแล้ว (%.0f วิ) — ไปที่ Sequence Preview เพื่อสร้างคลังปาก".format(secs), true)) }
        }
    }

    /** Cuts every PENDING clip of this session out of its recording. */
    private suspend fun cutPending() {
        val s = _ui.value.session ?: return
        val f = files ?: return
        val pending = db.clips().pending(s.id)
        if (pending.isEmpty()) { _ui.update { it.copy(phase = Phase.IDLE) }; return }
        val exporter = ClipExporter(getApplication(), _ui.value.config.fps)
        var ok = 0
        val t0 = SystemClock.elapsedRealtime()
        pending.forEachIndexed { i, clip ->
            _ui.update { it.copy(phase = Phase.CUTTING, cutProgress = (i + 1) to pending.size) }
            val out = f.clip(clip.number)
            try {
                val r = exporter.export(File(clip.rawPath), clip.startMs, clip.endMs, out)
                db.clips().update(clip.copy(clipPath = out.path, exportStatus = "DONE", durationMs = r.durationMs, exportError = null))
                ok++
            } catch (e: Throwable) {
                Log.e(TAG, "export failed", e)
                logError("ตัดคลิป", e)
                db.clips().update(clip.copy(exportStatus = "FAILED", exportError = e.message ?: e.toString()))
            }
        }
        val secs = (SystemClock.elapsedRealtime() - t0) / 1000f
        _ui.update {
            it.copy(
                phase = Phase.IDLE, cutProgress = null,
                banner = banner("ตัดคลิปเสร็จ $ok/${pending.size} คลิป (%.1f วินาที)".format(secs), ok == pending.size),
            )
        }
    }

    // ---- analysis thread ----

    private fun onFrameSafe(features: FrameFeatures, bitmap: Bitmap, inferenceMs: Long) {
        try {
            onFrame(features, bitmap, inferenceMs)
        } catch (e: Throwable) {
            Log.e(TAG, "frame", e)
            logError("วิเคราะห์ภาพ", e)
        }
    }

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
        val isNeutral = m?.score?.let { it >= cfg.poseMatchStart } == true
        if (isNeutral) { if (neutralSince < 0) neutralSince = now } else neutralSince = -1
        val heldMs = if (neutralSince >= 0) now - neutralSince else 0L

        var newFound: FoundSegment? = null
        var newBanner: Banner? = null
        var cue: String? = _ui.value.cue
        var collectedMs: Long? = null
        var elapsed = _ui.value.elapsedMs
        val start = recordStartElapsed
        val seg = segmenter
        if (_ui.value.phase == Phase.RECORDING && start > 0 && seg == null) elapsed = now - start
        if (_ui.value.phase == Phase.RECORDING && start > 0 && seg != null) {
            // Frame capture time ≈ now − model time; good to ~0.1 s.
            val t = now - inferenceMs - start
            elapsed = now - start
            val w = m?.warnings.orEmpty()
            when (val ev = seg.feed(
                Segmenter.Frame(
                    tMs = t, score = m?.score, jawOpen = features.jawOpen,
                    handOverMouth = LiveWarning.HAND_OVER_MOUTH in w, hasFace = features.hasFace,
                    faceSizeOut = LiveWarning.FACE_TOO_SMALL in w || LiveWarning.FACE_TOO_LARGE in w,
                    faceOutOfFrame = LiveWarning.FACE_OUT_OF_FRAME in w,
                ),
            )) {
                is Segmenter.Event.Found -> {
                    newFound = FoundSegment(ev.startMs.coerceAtLeast(0), ev.endMs, ev.qc, ev.stats)
                    newBanner = banner(
                        "✓ พบท่าที่ ${_ui.value.found.size + 1} (%.1f วิ)".format((ev.endMs - ev.startMs) / 1000f) +
                            if (ev.qc.status == QcStatus.WARN) " · ${ev.qc.reasons.first()}" else "",
                        true,
                    )
                }
                is Segmenter.Event.Rejected -> newBanner = banner("ยังไม่ได้ท่า: ${ev.reasons.first()}", false)
                null -> Unit
            }
            collectedMs = seg.collectingSince(t)?.let { t - it }
            if (now >= nextCueAt) {
                cue = Instructions.next(_ui.value.tag, lastCue).also { lastCue = it }
                nextCueAt = now + (cfg.cueIntervalSec * 1000).toLong()
            }
        }

        _ui.update {
            it.copy(
                match = m,
                live = features,
                heldMs = heldMs,
                hasFace = features.hasFace,
                inferenceMs = inferenceMs,
                analysisFps = measuredFps ?: it.analysisFps,
                elapsedMs = elapsed,
                cue = cue,
                collecting = collectedMs != null && it.phase == Phase.RECORDING,
                collectedMs = if (it.phase == Phase.RECORDING) collectedMs else null,
                found = newFound?.let { f -> it.found + f } ?: it.found,
                banner = newBanner ?: it.banner,
            )
        }
    }

    private fun banner(text: String, good: Boolean) = Banner(++bannerSeq, text, good)

    /** Keeps the last few errors (with where they happened) for the ⚙ panel. */
    private fun logError(where: String, e: Throwable) {
        val at = e.stackTrace.firstOrNull { it.className.startsWith("com.dtrader") } ?: e.stackTrace.firstOrNull()
        val line = "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $where: " +
            "${e.javaClass.simpleName}: ${e.message}" + (at?.let { " @ ${it.fileName}:${it.lineNumber}" } ?: "")
        _ui.update { it.copy(errors = (it.errors + line).takeLast(6)) }
    }

    override fun onCleared() {
        rig.unbind()
        analysisExecutor.execute { runCatching { landmarkers?.close() } }
        analysisExecutor.shutdown()
    }

    companion object {
        private const val TAG = "Studio"
        /** Recording mode that keeps the whole take for the mouth library. */
        const val SPEECH_TAG = "speech"
        const val MIN_SPEECH_SEC = 15
    }
}
