@file:OptIn(UnstableApi::class)

package com.dtrader.clipcapture.capture

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Cuts one segment out of a recording with Media3 Transformer: frame-accurate
 * trim, no audio, H.264, frames dropped to [fps] (sources recorded faster than
 * [fps] come out at [fps]), one keyframe per second.
 *
 * Alignment/colour correction (M4) and exact loop-point refinement (M3) are not
 * applied here.
 */
class ClipExporter(private val context: Context, private val fps: Int) {

    data class Result(val durationMs: Long, val fileSize: Long)

    suspend fun export(source: File, startMs: Long, endMs: Long, out: File): Result = withContext(Dispatchers.Main) {
        out.parentFile?.mkdirs()
        if (out.exists()) out.delete()
        suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setiFrameIntervalSeconds(1f).build())
                        .build(),
                )
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(Result(exportResult.durationMs, exportResult.fileSizeBytes))
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            val item = MediaItem.Builder()
                .setUri(Uri.fromFile(source))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build(),
                )
                .build()
            val edited = EditedMediaItem.Builder(item)
                .setRemoveAudio(true)
                .setEffects(Effects(emptyList(), listOf(FrameDropEffect.createDefaultFrameDropEffect(fps.toFloat()))))
                .build()
            transformer.start(edited, out.path)
            cont.invokeOnCancellation { android.os.Handler(android.os.Looper.getMainLooper()).post { transformer.cancel() } }
        }
    }
}
