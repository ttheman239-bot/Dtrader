package com.dtrader.clipcapture.sequence

import android.content.Context
import com.dtrader.clipcapture.BuildConfig
import com.dtrader.clipcapture.capture.QcStatus
import com.dtrader.clipcapture.data.SessionEntity
import com.dtrader.clipcapture.vision.SessionFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds the Clip Package (§4.7): one .zip with the clips, the neutral-pose
 * reference and manifest.json (session, camera lock, config, per-clip QC and
 * neutral scores, and the transition matrix with crossfade lengths).
 */
object PackageExporter {

    data class Result(val file: File, val clipCount: Int, val bytes: Long)

    fun exportDir(context: Context) = File(context.getExternalFilesDir(null) ?: context.filesDir, "exports").apply { mkdirs() }

    suspend fun export(
        context: Context,
        session: SessionEntity,
        graph: ClipGraph,
        includeRetake: Boolean,
        onProgress: (Int, Int) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val chosen = graph.clips.indices.filter { i ->
            includeRetake || graph.clips[i].qcStatus != QcStatus.RETAKE.name
        }
        require(chosen.isNotEmpty()) { "ไม่มีคลิปที่เข้าเงื่อนไข" }
        val files = SessionFiles(context, session.id)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safeName = session.name.replace(Regex("[^A-Za-z0-9ก-๙_-]+"), "_")
        val out = File(exportDir(context), "clip-package_${safeName}_$stamp.zip")
        val tmp = File(out.path + ".part")

        ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
            fun put(name: String, file: File, store: Boolean) {
                zip.setLevel(if (store) Deflater.NO_COMPRESSION else Deflater.DEFAULT_COMPRESSION)
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            chosen.forEachIndexed { k, i ->
                put("clips/clip_${graph.label(i)}.mp4", File(graph.clips[i].clipPath!!), store = true)
                onProgress(k + 1, chosen.size)
            }
            if (files.referenceImage.exists()) put("reference.png", files.referenceImage, store = true)
            if (files.referenceData.exists()) put("reference.json", files.referenceData, store = false)
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest(session, graph, chosen).toString(2).toByteArray())
            zip.closeEntry()
        }
        if (out.exists()) out.delete()
        check(tmp.renameTo(out)) { "เขียนไฟล์ ${out.name} ไม่สำเร็จ" }
        Result(out, chosen.size, out.length())
    }

    private fun manifest(session: SessionEntity, g: ClipGraph, chosen: List<Int>): JSONObject {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val cfg = g.config
        val ids = chosen.associateWith { "clip_${g.label(it)}" }
        return JSONObject().apply {
            put("format", "clip-package")
            put("format_version", 1)
            put("app_version", BuildConfig.VERSION_NAME)
            put("created_at", iso.format(Date()))
            put("session", JSONObject().apply {
                put("id", session.id)
                put("name", session.name)
                put("created_at", iso.format(Date(session.createdAt)))
                put("camera", if (session.lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_FRONT) "front" else "back")
                put("orientation", if (session.portrait) "portrait" else "landscape")
                put("width", if (session.portrait) 1080 else 1920)
                put("height", if (session.portrait) 1920 else 1080)
                put("fps", cfg.fps)
                put("camera_lock", session.cameraSettingsJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject.NULL)
            })
            put("reference", JSONObject().apply {
                put("image", "reference.png")
                put("landmarks", "reference.json")
            })
            put("config", cfg.toJson())
            put("clips", JSONArray().apply {
                chosen.forEach { i ->
                    val c = g.clips[i]
                    put(JSONObject().apply {
                        put("id", ids[i])
                        put("file", "clips/${ids[i]}.mp4")
                        put("number", c.number)
                        put("tag", c.tag)
                        put("duration_ms", g.edges[i].durationMs)
                        put("frames", Math.round(g.edges[i].durationMs * cfg.fps / 1000.0))
                        put("qc", JSONObject().apply {
                            put("status", c.qcStatus)
                            put("reasons", runCatching { JSONArray(c.qcReasonsJson) }.getOrDefault(JSONArray()))
                            put("source", "live_analysis")
                        })
                        put("flags", runCatching { JSONArray(c.flagsJson) }.getOrDefault(JSONArray()))
                        put("start_neutral_score", g.startScores[i]?.toDouble() ?: JSONObject.NULL)
                        put("end_neutral_score", g.endScores[i]?.toDouble() ?: JSONObject.NULL)
                    })
                }
            })
            put("transitions", JSONObject().apply {
                put("cost", "1 - pose_match(end of from, start of to) / 100")
                put("cost_reject", cfg.transitionCostReject.toDouble())
                put("no_repeat_last_n", cfg.noRepeatLastN)
                put("edges", JSONArray().apply {
                    for (i in chosen) for (j in chosen) {
                        val cost = g.matrix.cost(i, j)
                        put(JSONObject().apply {
                            put("from", ids[i])
                            put("to", ids[j])
                            put("cost", if (cost.isNaN()) JSONObject.NULL else cost.toDouble())
                            put("allowed", g.matrix.allowed(i, j))
                            put("crossfade_frames", g.matrix.crossfadeFrames(i, j))
                        })
                    }
                })
            })
            // Stated so the consumer doesn't assume these were done.
            put("not_applied", JSONArray(listOf("alignment (M4)", "colour_normalisation (M4)", "loop_point_refinement (M3)", "full_frame_qc (M3)")))
        }
    }
}
