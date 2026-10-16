package com.dtrader.clipcapture.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Thrown when the device can't do something the spec requires. Shown to the user as-is. */
class UnsupportedFeatureException(message: String) : Exception(message)

/** Camera values captured at calibration; re-applied manually on later binds/days (§4.1). */
data class CameraLockValues(
    val exposureNs: Long?,
    val iso: Int?,
    val focusDistance: Float?,
    val awbGains: FloatArray?,
    /** ColorSpaceTransform as 9 numerator/denominator pairs. */
    val awbTransform: IntArray?,
    val calibratedAt: Long,
) {
    fun toJson() = JSONObject().apply {
        put("exposure_ns", exposureNs ?: JSONObject.NULL)
        put("iso", iso ?: JSONObject.NULL)
        put("focus_distance", focusDistance?.toDouble() ?: JSONObject.NULL)
        put("awb_gains", awbGains?.let { g -> JSONArray().apply { g.forEach { put(it.toDouble()) } } } ?: JSONObject.NULL)
        put("awb_transform", awbTransform?.let { t -> JSONArray().apply { t.forEach { put(it) } } } ?: JSONObject.NULL)
        put("calibrated_at", calibratedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = CameraLockValues(
            exposureNs = if (o.isNull("exposure_ns")) null else o.getLong("exposure_ns"),
            iso = if (o.isNull("iso")) null else o.getInt("iso"),
            focusDistance = if (o.isNull("focus_distance")) null else o.getDouble("focus_distance").toFloat(),
            awbGains = o.optJSONArray("awb_gains")?.let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } },
            awbTransform = o.optJSONArray("awb_transform")?.let { a -> IntArray(a.length()) { a.getInt(it) } },
            calibratedAt = o.getLong("calibrated_at"),
        )
    }

    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/** What the bound camera can do, for the status panel. */
data class CameraCaps(
    val manualSensor: Boolean,
    val manualPostProcessing: Boolean,
    val manualFocus: Boolean,
    val fpsRange: Range<Int>?,
    val fpsNote: String,
    val analysisSize: Size?,
)

/**
 * Owns the CameraX binding: Preview + VideoCapture (FHD) + ImageAnalysis,
 * plus Camera2 interop for 25 fps and AE/AWB/AF locking.
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraRig(private val context: Context) {
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var recorder: Recorder? = null
    private var analysis: ImageAnalysis? = null
    private var fps = 25
    var caps: CameraCaps? = null
        private set

    @Volatile private var lastResult: TotalCaptureResult? = null

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            lastResult = result
        }
    }

    suspend fun bind(
        owner: LifecycleOwner,
        previewView: PreviewView,
        lensFacing: Int,
        fps: Int,
        analysisExecutor: Executor,
        analyzer: ImageAnalysis.Analyzer,
    ): CameraCaps {
        this.fps = fps
        val provider = awaitProvider().also { this.provider = it }
        val info = provider.availableCameraInfos.firstOrNull { c ->
            Camera2CameraInfo.from(c).getCameraCharacteristic(CameraCharacteristics.LENS_FACING) == lensFacing
        } ?: throw UnsupportedFeatureException(if (lensFacing == CameraSelector.LENS_FACING_FRONT) "ไม่พบกล้องหน้า" else "ไม่พบกล้องหลัง")
        val c2 = Camera2CameraInfo.from(info)

        val supportedQualities = Recorder.getVideoCapabilities(info).getSupportedQualities(DynamicRange.SDR)
        if (Quality.FHD !in supportedQualities) {
            throw UnsupportedFeatureException("กล้องนี้ไม่รองรับการอัด 1080p (FHD)")
        }

        val capabilities = c2.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities
        val manualPost = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in capabilities
        val minFocus = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val (range, note) = pickFpsRange(c2, fps, manualSensor)

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val ratio = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
        val previewBuilder = Preview.Builder().setResolutionSelector(ratio.build())
        Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(captureCallback)
        val preview = previewBuilder.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val rec = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.FHD)).build()
        val videoBuilder = VideoCapture.Builder(rec)
        if (range != null && range.lower == range.upper) videoBuilder.setTargetFrameRate(range)
        val video = videoBuilder.build()

        val analysisUseCase = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(640, 360), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(analysisExecutor, analyzer) }

        val rotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
        preview.targetRotation = rotation
        video.targetRotation = rotation
        analysisUseCase.targetRotation = rotation

        provider.unbindAll()
        camera = provider.bindToLifecycle(owner, selector, preview, video, analysisUseCase)
        recorder = rec
        analysis = analysisUseCase

        caps = CameraCaps(
            manualSensor = manualSensor,
            manualPostProcessing = manualPost,
            manualFocus = manualSensor && minFocus > 0f,
            fpsRange = range,
            fpsNote = note,
            analysisSize = analysisUseCase.resolutionInfo?.resolution,
        )
        applyAuto()
        return caps!!
    }

    /** Widest zoom the camera offers (ultra-wide on many phones) or 1×. Returns the ratio set. */
    fun setWide(on: Boolean): Float? {
        val cam = camera ?: return null
        val zs = cam.cameraInfo.zoomState.value ?: return null
        val ratio = if (on) zs.minZoomRatio else 1f.coerceIn(zs.minZoomRatio, zs.maxZoomRatio)
        cam.cameraControl.setZoomRatio(ratio)
        return ratio
    }

    fun unbind() {
        provider?.unbindAll()
        camera = null
    }

    /** Lets auto-exposure/white balance/focus run freely (before calibration). */
    fun applyAuto() {
        val b = CaptureRequestOptions.Builder()
        caps?.fpsRange?.let { b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        control().setCaptureRequestOptions(b.build())
    }

    /**
     * §4.1 step 3: let auto run for [autoMs], read the converged values, then
     * lock them. Returns the values and a description of how each was locked.
     */
    suspend fun calibrate(autoMs: Int): Pair<CameraLockValues, List<String>> {
        applyAuto()
        lastResult = null
        delay(autoMs.toLong())
        val r = lastResult ?: throw UnsupportedFeatureException("ไม่ได้รับข้อมูลเฟรมจากกล้องระหว่าง calibrate")
        val values = CameraLockValues(
            exposureNs = r.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME),
            iso = r.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY),
            focusDistance = r.get(android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE),
            awbGains = r.get(android.hardware.camera2.CaptureResult.COLOR_CORRECTION_GAINS)?.let {
                floatArrayOf(it.red, it.greenEven, it.greenOdd, it.blue)
            },
            awbTransform = r.get(android.hardware.camera2.CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { t ->
                IntArray(18).also { t.copyElements(it, 0) }
            },
            calibratedAt = System.currentTimeMillis(),
        )
        return values to applyLock(values, freshCalibration = true)
    }

    /**
     * Locks exposure, white balance and focus. With MANUAL_SENSOR /
     * MANUAL_POST_PROCESSING the stored values are applied exactly (works across
     * rebinds and days); otherwise falls back to AE/AWB lock, which only holds the
     * values the camera has *now* — so it is only valid right after calibration.
     */
    fun applyLock(v: CameraLockValues, freshCalibration: Boolean): List<String> {
        val caps = caps ?: throw IllegalStateException("camera not bound")
        val notes = mutableListOf<String>()
        val b = CaptureRequestOptions.Builder()
        caps.fpsRange?.let { b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        val frameNs = 1_000_000_000L / fps

        if (caps.manualSensor && v.exposureNs != null && v.iso != null) {
            var exp = v.exposureNs
            var iso = v.iso
            if (exp > frameNs) {
                // Keep 25 fps: shorten the exposure and raise ISO to keep brightness.
                val isoRange = cameraInfo()?.let { Camera2CameraInfo.from(it).getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) }
                iso = (iso * exp.toDouble() / frameNs).toInt().let { if (isoRange != null) it.coerceIn(isoRange.lower, isoRange.upper) else it }
                exp = frameNs
                notes += "แสงน้อย: ลด exposure เหลือ 1/$fps วินาที และเพิ่ม ISO เป็น $iso เพื่อคง $fps fps"
            }
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, frameNs)
            notes += "AE: manual (exposure ${exp / 1000} µs, ISO $iso, frame ${frameNs / 1_000_000} ms)"
        } else {
            if (!freshCalibration) throw UnsupportedFeatureException("กล้องนี้ไม่รองรับ manual exposure: ต้อง Calibrate ใหม่ ใช้ค่าที่บันทึกไว้ไม่ได้")
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
            notes += "AE: lock (อุปกรณ์ไม่รองรับ manual sensor — ต้อง Calibrate ใหม่ทุกครั้งที่เปิดกล้อง)"
        }

        if (caps.manualPostProcessing && v.awbGains != null && v.awbTransform != null) {
            val g = v.awbGains
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            b.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(g[0], g[1], g[2], g[3]))
            b.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_TRANSFORM, ColorSpaceTransform(v.awbTransform))
            notes += "AWB: manual (gains %.2f/%.2f/%.2f/%.2f)".format(g[0], g[1], g[2], g[3])
        } else {
            if (!freshCalibration) throw UnsupportedFeatureException("กล้องนี้ไม่รองรับ manual white balance: ต้อง Calibrate ใหม่")
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
            notes += "AWB: lock"
        }

        if (caps.manualFocus && v.focusDistance != null) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, v.focusDistance)
            notes += "โฟกัส: manual (%.2f diopter)".format(v.focusDistance)
        } else {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            notes += "โฟกัส: ยังไม่รองรับการล็อกบนกล้องนี้ (fixed-focus หรือไม่มี manual focus)"
        }
        control().setCaptureRequestOptions(b.build())
        return notes
    }

    @SuppressLint("MissingPermission")
    fun startRecording(file: File, onEvent: (VideoRecordEvent) -> Unit): Recording {
        val rec = recorder ?: throw IllegalStateException("camera not bound")
        // No audio: the AI Live pipeline doesn't use it (§4.3 step 5).
        return rec.prepareRecording(context, FileOutputOptions.Builder(file).build())
            .start(mainExecutor) { onEvent(it) }
    }

    private fun control(): Camera2CameraControl =
        Camera2CameraControl.from(camera?.cameraControl ?: throw IllegalStateException("camera not bound"))

    private fun cameraInfo(): CameraInfo? = camera?.cameraInfo

    private fun pickFpsRange(c2: Camera2CameraInfo, fps: Int, manualSensor: Boolean): Pair<Range<Int>?, String> {
        val ranges = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: emptyList()
        ranges.firstOrNull { it.lower == fps && it.upper == fps }?.let { return it to "$fps fps คงที่ (AE target range $it)" }
        val capped = ranges.filter { it.upper == fps }.maxByOrNull { it.lower }
        if (capped != null) {
            return capped to if (manualSensor) "$fps fps คงที่หลัง Calibrate (manual frame duration)"
            else "กล้องไม่มีช่วง $fps fps คงที่ ใช้ $capped — fps อาจลดในที่มืด; M3 จะแปลงเป็น $fps fps CFR ตอน export"
        }
        return null to "กล้องนี้ไม่มีช่วง fps ที่สิ้นสุด $fps (มี ${ranges.joinToString()}) — " +
            if (manualSensor) "ใช้ manual frame duration หลัง Calibrate" else "อัดตาม fps ของกล้อง; M3 จะแปลงเป็น $fps fps CFR"
    }

    private suspend fun awaitProvider(): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
        val f = ProcessCameraProvider.getInstance(context)
        f.addListener({
            try { cont.resume(f.get()) } catch (e: Throwable) { cont.resumeWithException(e) }
        }, mainExecutor)
    }
}
