package com.dtrader.clipcapture.lipsync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.os.Build
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.dtrader.clipcapture.vision.FrameFeatures
import java.io.File

/** Decodes a video frame by frame, upright, scaled so the longest side is at most [maxSide]. */
object VideoFrames {

    class Info(val durationMs: Long, val frameCount: Int?, val rotation: Int)

    fun info(r: MediaMetadataRetriever): Info {
        val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: error("อ่านความยาววิดีโอไม่ได้")
        val count = if (Build.VERSION.SDK_INT >= 28) r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() else null
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        return Info(dur, count, rot)
    }

    /**
     * Calls [block] with (frame index, time ms, bitmap) for every frame, or every
     * [stride]-th frame. Bitmaps are only valid during the call.
     */
    fun forEach(file: File, maxSide: Int, stride: Int, block: (Int, Long, Bitmap) -> Unit): Info {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.path)
            val info = info(r)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val portraitShown = if (info.rotation % 180 != 0) w > h else h > w
            val count = info.frameCount
            if (count != null && count > 0 && Build.VERSION.SDK_INT >= 28) {
                // Sequential batches: far cheaper than seeking for every frame.
                val batch = 8
                var i = 0
                while (i < count) {
                    val n = minOf(batch, count - i)
                    val frames = r.getFramesAtIndex(i, n)
                    frames.forEachIndexed { k, f ->
                        val idx = i + k
                        if (idx % stride == 0) block(idx, idx * info.durationMs / count, upright(f, portraitShown, info.rotation, maxSide))
                        f.recycle()
                    }
                    i += n
                }
            } else {
                val stepMs = 40L * stride
                var t = 0L
                var idx = 0
                while (t < info.durationMs) {
                    r.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST)?.let { f ->
                        block(idx, t, upright(f, portraitShown, info.rotation, maxSide))
                    }
                    t += stepMs; idx += stride
                }
            }
            return info
        } finally {
            r.release()
        }
    }

    /**
     * Some decoders return frames before the container rotation is applied; rotate
     * those so they match what a player shows. Always ARGB_8888 for MediaPipe.
     */
    fun upright(src: Bitmap, portraitShown: Boolean, rotation: Int, maxSide: Int): Bitmap {
        val needsRotate = rotation % 180 != 0 && (src.height > src.width) != portraitShown
        val s = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height))
        val m = Matrix().apply {
            postScale(s, s)
            if (needsRotate) postRotate(rotation.toFloat())
        }
        val out = if (s < 1f || needsRotate) Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true) else src
        return if (out.config == Bitmap.Config.ARGB_8888) out else out.copy(Bitmap.Config.ARGB_8888, false)
    }
}

/** Face landmarks + jawOpen for consecutive video frames (MediaPipe VIDEO mode). */
class FaceTracker(context: Context) : AutoCloseable {
    private val face = FaceLandmarker.createFromOptions(
        context,
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("models/face_landmarker.task").setDelegate(Delegate.CPU).build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(true)
            .setOutputFacialTransformationMatrixes(true)
            .build(),
    )
    private var lastTs = -1L

    class Face(val xy: FloatArray, val jawOpen: Float, val yaw: Float, val pitch: Float, val roll: Float)

    fun detect(bitmap: Bitmap, timeMs: Long): Face? {
        val ts = if (timeMs <= lastTs) lastTs + 1 else timeMs
        lastTs = ts
        val r = face.detectForVideo(BitmapImageBuilder(bitmap).build(), ts)
        val lm = r.faceLandmarks().firstOrNull() ?: return null
        val xy = FloatArray(lm.size * 2).also { a -> lm.forEachIndexed { i, l -> a[i * 2] = l.x(); a[i * 2 + 1] = l.y() } }
        val jaw = r.faceBlendshapes().orElse(null)?.firstOrNull()?.firstOrNull { it.categoryName() == "jawOpen" }?.score() ?: 0f
        val (yaw, pitch, roll) = r.facialTransformationMatrixes().orElse(null)?.firstOrNull()?.let { FrameFeatures.headAngles(it) } ?: Triple(0f, 0f, 0f)
        return Face(xy, jaw, yaw, pitch, roll)
    }

    override fun close() = face.close()

    companion object {
        /** Landmarks the mouth is placed by: outer eye corners and the base of the nose (they don't move with the jaw). */
        val ANCHORS = intArrayOf(33, 263, 2)
        val LIPS_OUTER = intArrayOf(61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291, 375, 321, 405, 314, 17, 84, 181, 91, 146)

        fun anchors(xy: FloatArray): FloatArray = FloatArray(6).also { a ->
            ANCHORS.forEachIndexed { k, i -> a[k * 2] = xy[i * 2]; a[k * 2 + 1] = xy[i * 2 + 1] }
        }
    }
}
