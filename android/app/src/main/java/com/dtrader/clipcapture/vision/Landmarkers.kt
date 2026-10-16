package com.dtrader.clipcapture.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker

/**
 * MediaPipe Face (with blendshapes + transformation matrix), Pose and Hand
 * landmarkers in VIDEO mode. Each model tries the GPU delegate first and falls
 * back to CPU. Not thread-safe: call [detect] from one thread only.
 */
class Landmarkers private constructor(
    private val face: FaceLandmarker,
    private val pose: PoseLandmarker,
    private val hands: HandLandmarker,
    val delegates: String,
) : AutoCloseable {

    private var lastTs = -1L

    /** Runs all three models on an upright bitmap. [timestampMs] must increase. */
    fun detect(bitmap: Bitmap, timestampMs: Long): FrameFeatures {
        val ts = if (timestampMs <= lastTs) lastTs + 1 else timestampMs
        lastTs = ts
        // Not closed on purpose: MPImage.close() recycles the bitmap, and the caller
        // still needs it (e.g. to save the neutral-pose reference). GC frees it.
        val image = BitmapImageBuilder(bitmap).build()
        return run {
            val f = face.detectForVideo(image, ts)
            val p = pose.detectForVideo(image, ts)
            val h = hands.detectForVideo(image, ts)

            val faceLm = f.faceLandmarks().firstOrNull()
            val faceXy = faceLm?.let { lm -> FloatArray(lm.size * 2).also { a -> lm.forEachIndexed { i, l -> a[i * 2] = l.x(); a[i * 2 + 1] = l.y() } } }
            val jaw = f.faceBlendshapes().orElse(null)?.firstOrNull()
                ?.firstOrNull { it.categoryName() == "jawOpen" }?.score() ?: 0f
            val angles = f.facialTransformationMatrixes().orElse(null)?.firstOrNull()
                ?.let { FrameFeatures.headAngles(it) } ?: Triple(0f, 0f, 0f)

            val poseLm = p.landmarks().firstOrNull()
            val poseArr = poseLm?.let { lm ->
                FloatArray(lm.size * 3).also { a ->
                    lm.forEachIndexed { i, l ->
                        a[i * 3] = l.x(); a[i * 3 + 1] = l.y(); a[i * 3 + 2] = l.visibility().orElse(0f)
                    }
                }
            }
            val handArr = h.landmarks().map { lm ->
                FloatArray(lm.size * 2).also { a -> lm.forEachIndexed { i, l -> a[i * 2] = l.x(); a[i * 2 + 1] = l.y() } }
            }
            FrameFeatures(
                timestampMs = ts, width = bitmap.width, height = bitmap.height,
                face = faceXy, jawOpen = jaw,
                yaw = angles.first, pitch = angles.second, roll = angles.third,
                pose = poseArr, hands = handArr,
            )
        }
    }

    override fun close() {
        face.close(); pose.close(); hands.close()
    }

    companion object {
        private const val TAG = "Landmarkers"

        fun create(context: Context, useGpu: Boolean): Landmarkers {
            val used = mutableListOf<String>()
            val face = withFallback("face", used, useGpu) { d ->
                FaceLandmarker.createFromOptions(
                    context,
                    FaceLandmarker.FaceLandmarkerOptions.builder()
                        .setBaseOptions(base("models/face_landmarker.task", d))
                        .setRunningMode(RunningMode.VIDEO)
                        .setNumFaces(1)
                        .setOutputFaceBlendshapes(true)
                        .setOutputFacialTransformationMatrixes(true)
                        .build(),
                )
            }
            val pose = withFallback("pose", used, useGpu) { d ->
                PoseLandmarker.createFromOptions(
                    context,
                    PoseLandmarker.PoseLandmarkerOptions.builder()
                        .setBaseOptions(base("models/pose_landmarker_lite.task", d))
                        .setRunningMode(RunningMode.VIDEO)
                        .setNumPoses(1)
                        .build(),
                )
            }
            val hands = withFallback("hand", used, useGpu) { d ->
                HandLandmarker.createFromOptions(
                    context,
                    HandLandmarker.HandLandmarkerOptions.builder()
                        .setBaseOptions(base("models/hand_landmarker.task", d))
                        .setRunningMode(RunningMode.VIDEO)
                        .setNumHands(2)
                        .build(),
                )
            }
            return Landmarkers(face, pose, hands, used.joinToString(", "))
        }

        private fun base(path: String, delegate: Delegate) =
            BaseOptions.builder().setModelAssetPath(path).setDelegate(delegate).build()

        /**
         * CPU by default: a GPU delegate that fails on some drivers can take the
         * whole process down with a native crash that no try/catch sees.
         */
        private fun <T> withFallback(name: String, used: MutableList<String>, useGpu: Boolean, make: (Delegate) -> T): T =
            if (!useGpu) make(Delegate.CPU).also { used += "$name:CPU" } else try {
                make(Delegate.GPU).also { used += "$name:GPU" }
            } catch (e: Throwable) {
                Log.w(TAG, "$name GPU delegate failed, using CPU", e)
                make(Delegate.CPU).also { used += "$name:CPU" }
            }
    }
}
