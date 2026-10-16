package com.dtrader.clipcapture.vision

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * ImageAnalysis analyzer: throttles to [analysisFps], turns the frame upright
 * and runs the landmarkers. Delivers the features, the upright bitmap (needed
 * when saving the reference) and the time the models took.
 */
class FrameAnalyzer(
    private val landmarkers: Landmarkers,
    @Volatile var analysisFps: Int,
    private val onFrame: (FrameFeatures, Bitmap, Long) -> Unit,
) : ImageAnalysis.Analyzer {

    private var lastRun = 0L

    override fun analyze(image: ImageProxy) {
        image.use { proxy ->
            val now = SystemClock.elapsedRealtime()
            if (now - lastRun < 1000L / analysisFps.coerceAtLeast(1)) return
            lastRun = now
            val raw = proxy.toBitmap()
            val rot = proxy.imageInfo.rotationDegrees
            val upright = if (rot == 0) raw else Bitmap.createBitmap(
                raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot.toFloat()) }, true,
            )
            val t0 = SystemClock.elapsedRealtime()
            val features = landmarkers.detect(upright, proxy.imageInfo.timestamp / 1_000_000)
            onFrame(features, upright, SystemClock.elapsedRealtime() - t0)
        }
    }
}
