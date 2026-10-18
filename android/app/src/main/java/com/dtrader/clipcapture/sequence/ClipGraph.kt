package com.dtrader.clipcapture.sequence

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.dtrader.clipcapture.config.CaptureConfig
import com.dtrader.clipcapture.data.AppDatabase
import com.dtrader.clipcapture.data.ClipEntity
import com.dtrader.clipcapture.vision.FrameFeatures
import com.dtrader.clipcapture.vision.Landmarkers
import com.dtrader.clipcapture.vision.PoseMatcher
import com.dtrader.clipcapture.vision.SessionFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Landmarks of a cut clip's first and last frame. */
data class ClipEdges(val start: FrameFeatures?, val end: FrameFeatures?, val durationMs: Long) {
    companion object {
        /** Cached next to the clip; stale once the clip file is newer. */
        fun cacheFile(clip: File) = File(clip.parentFile, clip.nameWithoutExtension + ".edges.json")

        fun loadCached(clip: File): ClipEdges? {
            val f = cacheFile(clip)
            if (!f.exists() || f.lastModified() < clip.lastModified()) return null
            return runCatching {
                val o = JSONObject(f.readText())
                ClipEdges(
                    o.optJSONObject("start")?.let { FrameFeatures.fromJson(it) },
                    o.optJSONObject("end")?.let { FrameFeatures.fromJson(it) },
                    o.getLong("duration_ms"),
                )
            }.getOrNull()
        }

        /** Decodes the first and last frame and runs the landmarkers on both. */
        fun extract(clip: File, landmarkers: Landmarkers): ClipEdges {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(clip.path)
                val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?: error("อ่านความยาวคลิป ${clip.name} ไม่ได้")
                val first = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)
                // ~2 frames before the end, so the decoder still has a frame there.
                val last = r.getFrameAtTime(maxOf(0L, dur - 80) * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                val edges = ClipEdges(
                    first?.let { landmarkers.detect(forModel(it), 0) },
                    last?.let { landmarkers.detect(forModel(it), 1) },
                    dur,
                )
                cacheFile(clip).writeText(
                    JSONObject().apply {
                        put("duration_ms", dur)
                        put("start", edges.start?.toJson() ?: JSONObject.NULL)
                        put("end", edges.end?.toJson() ?: JSONObject.NULL)
                    }.toString(),
                )
                return edges
            } finally {
                r.release()
            }
        }

        /** Same size class as the live analysis (longest side 640), ARGB_8888 for MediaPipe. */
        private fun forModel(b: Bitmap): Bitmap {
            val s = 640f / maxOf(b.width, b.height)
            val scaled = if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true) else b
            return if (scaled.config == Bitmap.Config.ARGB_8888) scaled else scaled.copy(Bitmap.Config.ARGB_8888, false)
        }
    }
}

/** Everything the transition, preview and export screens need for one session. */
class ClipGraph(
    /** Cut clips whose file exists, in clip-number order. */
    val clips: List<ClipEntity>,
    val edges: List<ClipEdges>,
    val matrix: TransitionMatrix,
    val reference: FrameFeatures?,
    /** Pose score of each clip's first / last frame against the neutral reference, if measurable. */
    val startScores: List<Float?>,
    val endScores: List<Float?>,
    val config: CaptureConfig,
) {
    fun label(i: Int) = "%03d".format(clips[i].number)

    companion object {
        /**
         * Loads the session's cut clips and analyses any whose edge frames aren't
         * cached yet. [onProgress] gets (analysed, total) for that work.
         */
        suspend fun load(context: Context, sessionId: Long, cfg: CaptureConfig, onProgress: (Int, Int) -> Unit): ClipGraph =
            withContext(Dispatchers.Default) {
                val db = AppDatabase.get(context)
                val clips = db.clips().listForSession(sessionId)
                    .filter { it.exportStatus == "DONE" && it.clipPath != null && File(it.clipPath).exists() }
                    .sortedBy { it.number }
                val cached = clips.map { ClipEdges.loadCached(File(it.clipPath!!)) }
                val todo = cached.count { it == null }
                var landmarkers: Landmarkers? = null
                val edges = try {
                    var done = 0
                    if (todo > 0) onProgress(0, todo)
                    clips.mapIndexed { i, c ->
                        cached[i] ?: run {
                            val lm = landmarkers ?: Landmarkers.create(context, cfg.useGpu == 1, video = false).also { landmarkers = it }
                            ClipEdges.extract(File(c.clipPath!!), lm).also { onProgress(++done, todo) }
                        }
                    }
                } finally {
                    landmarkers?.close()
                }
                val reference = SessionFiles(context, sessionId).loadReferenceFeatures()
                val refMatcher = reference?.let { runCatching { PoseMatcher(it, cfg) }.getOrNull() }
                ClipGraph(
                    clips = clips,
                    edges = edges,
                    matrix = TransitionMatrix.compute(edges.map { it.end }, edges.map { it.start }, cfg),
                    reference = reference,
                    startScores = edges.map { e -> e.start?.let { refMatcher?.match(it)?.score } },
                    endScores = edges.map { e -> e.end?.let { refMatcher?.match(it)?.score } },
                    config = cfg,
                )
            }
    }
}
