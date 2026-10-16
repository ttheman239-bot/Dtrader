package com.dtrader.clipcapture.vision

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Landmarks extracted from one upright (not mirrored) camera frame.
 * All coordinates are normalised to the frame: x, y in [0, 1].
 */
data class FrameFeatures(
    val timestampMs: Long,
    val width: Int,
    val height: Int,
    /** 478 face landmarks as x0,y0,x1,y1,…, or null if no face was found. */
    val face: FloatArray?,
    val jawOpen: Float,
    /** Head angles in degrees from the facial transformation matrix. */
    val yaw: Float,
    val pitch: Float,
    val roll: Float,
    /** 33 pose landmarks as x,y,visibility triples, or null if no person was found. */
    val pose: FloatArray?,
    /** Each detected hand as 21 landmarks x0,y0,x1,y1,…. */
    val hands: List<FloatArray>,
) {
    val hasFace get() = face != null

    fun facePoint(i: Int): Pair<Float, Float> = face!![i * 2] to face[i * 2 + 1]
    fun posePoint(i: Int): Triple<Float, Float, Float> = Triple(pose!![i * 3], pose[i * 3 + 1], pose[i * 3 + 2])

    /** Axis-aligned face box (minX, minY, maxX, maxY). */
    fun faceBox(): FloatArray? = face?.let { boxOf(it) }

    /** Distance between the outer eye corners, used to normalise face distances. */
    fun eyeDistance(): Float? = face?.let {
        val (ax, ay) = facePoint(FaceIdx.LEFT_EYE_OUTER)
        val (bx, by) = facePoint(FaceIdx.RIGHT_EYE_OUTER)
        hypot((ax - bx) * width, (ay - by) * height) / width
    }

    /** Shoulder width in frame-width units, or null if the shoulders aren't visible. */
    fun shoulderWidth(minVis: Float): Float? = pose?.let {
        val l = posePoint(PoseIdx.LEFT_SHOULDER)
        val r = posePoint(PoseIdx.RIGHT_SHOULDER)
        if (l.third < minVis || r.third < minVis) null
        else hypot((l.first - r.first) * width, (l.second - r.second) * height) / width
    }

    /** Mouth box (minX, minY, maxX, maxY) grown by [grow] of its size on each side. */
    fun mouthBox(grow: Float): FloatArray? = face?.let {
        val xs = FaceIdx.LIPS.map { i -> it[i * 2] }
        val ys = FaceIdx.LIPS.map { i -> it[i * 2 + 1] }
        val w = xs.max() - xs.min()
        val h = ys.max() - ys.min()
        floatArrayOf(xs.min() - w * grow, ys.min() - h * grow, xs.max() + w * grow, ys.max() + h * grow)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("timestamp_ms", timestampMs)
        put("width", width)
        put("height", height)
        put("face", face?.toJsonArray() ?: JSONObject.NULL)
        put("jaw_open", jawOpen.toDouble())
        put("yaw", yaw.toDouble())
        put("pitch", pitch.toDouble())
        put("roll", roll.toDouble())
        put("pose", pose?.toJsonArray() ?: JSONObject.NULL)
        put("hands", JSONArray().apply { hands.forEach { put(it.toJsonArray()) } })
    }

    companion object {
        fun fromJson(o: JSONObject) = FrameFeatures(
            timestampMs = o.getLong("timestamp_ms"),
            width = o.getInt("width"),
            height = o.getInt("height"),
            face = o.optJSONArray("face")?.toFloatArray(),
            jawOpen = o.getDouble("jaw_open").toFloat(),
            yaw = o.getDouble("yaw").toFloat(),
            pitch = o.getDouble("pitch").toFloat(),
            roll = o.getDouble("roll").toFloat(),
            pose = o.optJSONArray("pose")?.toFloatArray(),
            hands = o.getJSONArray("hands").let { a -> (0 until a.length()).map { a.getJSONArray(it).toFloatArray() } },
        )

        fun boxOf(xy: FloatArray): FloatArray {
            var minX = 1f; var minY = 1f; var maxX = 0f; var maxY = 0f
            for (i in xy.indices step 2) {
                minX = minOf(minX, xy[i]); maxX = maxOf(maxX, xy[i])
                minY = minOf(minY, xy[i + 1]); maxY = maxOf(maxY, xy[i + 1])
            }
            return floatArrayOf(minX, minY, maxX, maxY)
        }

        /** Yaw/pitch/roll in degrees from a column-major 4x4 transformation matrix. */
        fun headAngles(m: FloatArray): Triple<Float, Float, Float> {
            val r00 = m[0]; val r10 = m[1]; val r20 = m[2]
            val r21 = m[6]; val r22 = m[10]
            val deg = (180.0 / Math.PI)
            val pitch = atan2(r21.toDouble(), r22.toDouble()) * deg
            val yaw = asin((-r20).coerceIn(-1f, 1f).toDouble()) * deg
            val roll = atan2(r10.toDouble(), r00.toDouble()) * deg
            return Triple(yaw.toFloat(), pitch.toFloat(), roll.toFloat())
        }
    }

    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/** MediaPipe Face Mesh landmark indices used by the app. */
object FaceIdx {
    const val LEFT_EYE_OUTER = 33
    const val RIGHT_EYE_OUTER = 263
    /** Stable points for pose matching: eye corners, nose, mouth corners, chin, forehead, cheeks. */
    val KEY = intArrayOf(33, 133, 362, 263, 1, 4, 61, 291, 152, 10, 234, 454, 199)
    /** Lip contour points for the mouth region. */
    val LIPS = intArrayOf(61, 291, 0, 17, 13, 14, 37, 267, 84, 314)
}

/** MediaPipe Pose landmark indices used by the app. */
object PoseIdx {
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_WRIST = 15
    const val RIGHT_WRIST = 16
}

private fun FloatArray.toJsonArray() = JSONArray().also { a -> forEach { a.put(it.toDouble()) } }
private fun JSONArray.toFloatArray() = FloatArray(length()) { getDouble(it).toFloat() }
