package com.dtrader.clipcapture.lipsync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import com.dtrader.clipcapture.vision.FaceIdx
import com.dtrader.clipcapture.vision.FrameFeatures
import com.dtrader.clipcapture.vision.SessionFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/** One mouth image cut from the speech take, with soft edges baked into its alpha. */
class MouthSample(
    val bitmap: Bitmap,
    /** [FaceTracker.ANCHORS] in this bitmap's pixel coordinates. */
    val anchors: FloatArray,
    val jaw: Float,
)

/**
 * Real mouth shapes of this person, taken from a take where they talk (studio
 * mode "พูด"). Only frames whose head angle is close to the neutral pose are
 * used, binned by how open the mouth is.
 */
class MouthLibrary(val samples: List<MouthSample>) {
    val maxJaw get() = samples.maxOf { it.jaw }

    companion object {
        private const val BINS = 10
        private const val PER_BIN = 3
        private const val MAX_JAW = 0.8f
        private const val MAX_ANGLE_DIFF = 10f

        private fun index(files: SessionFiles) = File(files.mouthDir, "index.json")

        fun exists(files: SessionFiles) = index(files).exists()

        fun load(files: SessionFiles): MouthLibrary? {
            val f = index(files)
            if (!f.exists()) return null
            val a = JSONArray(f.readText())
            val samples = (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val bmp = BitmapFactory.decodeFile(File(files.mouthDir, o.getString("file")).path) ?: return@mapNotNull null
                val an = o.getJSONArray("anchors")
                MouthSample(bmp, FloatArray(6) { an.getDouble(it).toFloat() }, o.getDouble("jaw").toFloat())
            }
            return if (samples.isEmpty()) null else MouthLibrary(samples)
        }

        private class Candidate(val crop: Bitmap, val anchors: FloatArray, val jaw: Float, val angleDiff: Float)

        suspend fun build(context: Context, files: SessionFiles, reference: FrameFeatures?, onProgress: (Int, Int) -> Unit): MouthLibrary =
            withContext(Dispatchers.Default) {
                val take = files.speechTake
                require(take.exists()) { "ยังไม่มีเทคพูด — ไปหน้าถ่าย เลือกโหมด \"พูด\" แล้วอัด" }
                val bins = Array(BINS) { ArrayList<Candidate>() }
                var seen = 0; var faces = 0; var offAngle = 0
                val total = runCatching {
                    val r = android.media.MediaMetadataRetriever()
                    try { r.setDataSource(take.path); VideoFrames.info(r) } finally { r.release() }
                }.getOrNull()?.let { it.frameCount ?: (it.durationMs / 40).toInt() } ?: 0
                FaceTracker(context).use { tracker ->
                    VideoFrames.forEach(take, maxSide = 1280, stride = 2) { idx, tMs, frame ->
                        seen++
                        if (total > 0) onProgress(idx, total)
                        val small = Bitmap.createScaledBitmap(frame, frame.width / 2, frame.height / 2, true)
                        val face = tracker.detect(small, tMs) ?: return@forEach
                        faces++
                        val diff = reference?.let { maxOf(abs(face.yaw - it.yaw), abs(face.pitch - it.pitch), abs(face.roll - it.roll)) } ?: 0f
                        if (diff > MAX_ANGLE_DIFF) { offAngle++; return@forEach }
                        val bin = ((face.jawOpen / MAX_JAW) * BINS).toInt().coerceIn(0, BINS - 1)
                        val list = bins[bin]
                        if (list.size >= PER_BIN && list.maxOf { it.angleDiff } <= diff) return@forEach
                        val c = cut(frame, face) ?: return@forEach
                        list += Candidate(c.first, c.second, face.jawOpen, diff)
                        if (list.size > PER_BIN) list.remove(list.maxBy { it.angleDiff })
                    }
                }
                val chosen = bins.flatMap { it }
                when {
                    faces == 0 -> error("ไม่พบหน้าในเทคพูด")
                    chosen.size < 4 -> error("ได้ปากแค่ ${chosen.size} แบบ (หันหน้าต่างจากท่ากลาง $offAngle เฟรม) — อัดใหม่โดยหน้าตรงทับเงา")
                    chosen.maxOf { it.jaw } < 0.2f -> error("ปากเปิดไม่กว้างพอ (สูงสุด %.2f) — อัดใหม่ พูดชัด ๆ อ้าปากให้กว้างขึ้น".format(chosen.maxOf { it.jaw }))
                }
                files.mouthDir.listFiles()?.forEach { it.delete() }
                val entries = JSONArray()
                chosen.sortedBy { it.jaw }.forEachIndexed { k, c ->
                    val name = "mouth_%02d.png".format(k)
                    File(files.mouthDir, name).outputStream().use { c.crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    entries.put(JSONObject().apply {
                        put("file", name)
                        put("jaw", c.jaw.toDouble())
                        put("anchors", JSONArray(c.anchors.map { it.toDouble() }))
                        put("angle_diff", c.angleDiff.toDouble())
                    })
                }
                index(files).writeText(entries.toString())
                MouthLibrary(chosen.sortedBy { it.jaw }.map { MouthSample(it.crop, it.anchors, it.jaw) })
            }

        /**
         * Cuts the mouth area (about one eye-distance wide) out of [frame] and fades
         * its edges with an elliptical mask so it blends over another frame.
         */
        private fun cut(frame: Bitmap, face: FaceTracker.Face): Pair<Bitmap, FloatArray>? {
            val w = frame.width.toFloat(); val h = frame.height.toFloat()
            val xy = face.xy
            fun px(i: Int) = xy[i * 2] * w
            fun py(i: Int) = xy[i * 2 + 1] * h
            val eye = hypot(px(FaceIdx.LEFT_EYE_OUTER) - px(FaceIdx.RIGHT_EYE_OUTER), py(FaceIdx.LEFT_EYE_OUTER) - py(FaceIdx.RIGHT_EYE_OUTER))
            if (eye < 20f) return null
            val lips = FaceTracker.LIPS_OUTER
            val cx = lips.map { px(it) }.average().toFloat()
            val cy = lips.map { py(it) }.average().toFloat()
            val cw = eye * 1.05f
            val ch = eye * 0.85f
            val left = (cx - cw / 2).toInt(); val top = (cy - ch / 2).toInt()
            val rw = cw.toInt(); val rh = ch.toInt()
            if (left < 0 || top < 0 || left + rw > frame.width || top + rh > frame.height) return null

            val out = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawBitmap(Bitmap.createBitmap(frame, left, top, rw, rh), 0f, 0f, null)
            val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(0f, 0f, 1f, intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
                    .apply { setLocalMatrix(Matrix().apply { setScale(rw / 2f, rh / 2f); postTranslate(rw / 2f, rh / 2f) }) }
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            }
            canvas.drawRect(0f, 0f, rw.toFloat(), rh.toFloat(), mask)
            val a = FaceTracker.anchors(xy)
            val local = FloatArray(6) { k -> if (k % 2 == 0) a[k] * w - left else a[k] * h - top }
            return out to local
        }
    }
}

/** Where the mouth goes in every frame of each clip, cached as clip_###.mouth.json. */
object MouthTracks {
    private fun cacheFile(clip: File) = File(clip.parentFile, clip.nameWithoutExtension + ".mouth.json")

    fun loadCached(clip: File): MouthTrack? {
        val f = cacheFile(clip)
        if (!f.exists() || f.lastModified() < clip.lastModified()) return null
        return runCatching {
            val o = JSONObject(f.readText())
            val t = o.getJSONArray("t"); val a = o.getJSONArray("a")
            MouthTrack(
                LongArray(t.length()) { t.getLong(it) },
                Array(a.length()) { i -> a.optJSONArray(i)?.let { arr -> FloatArray(6) { arr.getDouble(it).toFloat() } } },
            )
        }.getOrNull()
    }

    fun build(context: Context, clip: File): MouthTrack {
        val times = ArrayList<Long>()
        val anchors = ArrayList<FloatArray?>()
        FaceTracker(context).use { tracker ->
            VideoFrames.forEach(clip, maxSide = 640, stride = 2) { _, tMs, frame ->
                times += tMs
                anchors += tracker.detect(frame, tMs)?.let { FaceTracker.anchors(it.xy) }
            }
        }
        val track = MouthTrack(times.toLongArray(), MouthTrack.smooth(anchors.toTypedArray()))
        cacheFile(clip).writeText(
            JSONObject().apply {
                put("t", JSONArray(track.timesMs.toList()))
                put("a", JSONArray().apply { track.anchors.forEach { put(it?.let { a -> JSONArray(a.map { v -> v.toDouble() }) } ?: JSONObject.NULL) } })
            }.toString(),
        )
        return track
    }
}
