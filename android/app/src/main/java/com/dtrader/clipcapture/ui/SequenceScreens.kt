package com.dtrader.clipcapture.ui

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.dtrader.clipcapture.config.ConfigStore
import com.dtrader.clipcapture.data.AppDatabase
import com.dtrader.clipcapture.lipsync.LipSync
import com.dtrader.clipcapture.lipsync.MouthLibrary
import com.dtrader.clipcapture.lipsync.MouthTrack
import com.dtrader.clipcapture.lipsync.MouthTracks
import com.dtrader.clipcapture.lipsync.SpeechPlayer
import com.dtrader.clipcapture.vision.SessionFiles
import kotlinx.coroutines.Job
import com.dtrader.clipcapture.sequence.ClipGraph
import com.dtrader.clipcapture.sequence.PackageExporter
import com.dtrader.clipcapture.sequence.SequenceEngine
import com.dtrader.clipcapture.sequence.SequencePlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Good = Color(0xFF22C55E)
private val Mid = Color(0xFFF59E0B)
private val Bad = Color(0xFFDC2626)
private val Unknown = Color(0xFF3F3F46)

private sealed class GraphState {
    data class Loading(val done: Int, val total: Int) : GraphState()
    data class Ready(val graph: ClipGraph) : GraphState()
    data class Failed(val message: String) : GraphState()
}

/** Loads (and if needed analyses) the session's clips, with progress. */
@Composable
private fun rememberClipGraph(sessionId: Long): GraphState {
    val context = LocalContext.current
    var state by remember { mutableStateOf<GraphState>(GraphState.Loading(0, 0)) }
    LaunchedEffect(sessionId) {
        state = try {
            GraphState.Ready(
                ClipGraph.load(context, sessionId, ConfigStore(context).load()) { d, t -> state = GraphState.Loading(d, t) },
            )
        } catch (e: Throwable) {
            GraphState.Failed("${e.javaClass.simpleName}: ${e.message}")
        }
    }
    return state
}

/** Shows loading / failure / "no clips" states; calls [content] once there is something to show. */
@Composable
private fun GraphGate(state: GraphState, minClips: Int, content: @Composable (ClipGraph) -> Unit) {
    when (state) {
        is GraphState.Loading -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (state.total == 0) "กำลังโหลดคลิป…" else "กำลังวิเคราะห์เฟรมแรก/สุดท้ายของคลิป ${state.done}/${state.total}")
            if (state.total > 0) LinearProgressIndicator(progress = { state.done.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("ครั้งแรกจะช้าหน่อย ครั้งต่อไปใช้ผลที่เก็บไว้", fontSize = 12.sp, color = Color.Gray)
        }
        is GraphState.Failed -> Text("วิเคราะห์คลิปไม่สำเร็จ: ${state.message}", color = Bad, modifier = Modifier.padding(20.dp))
        is GraphState.Ready ->
            if (state.graph.clips.size < minClips) {
                Text(
                    "ต้องมีคลิปที่ตัดแล้วอย่างน้อย $minClips คลิป (ตอนนี้มี ${state.graph.clips.size}) — ไปถ่ายเพิ่มที่หน้าถ่าย",
                    modifier = Modifier.padding(20.dp),
                )
            } else content(state.graph)
    }
}

private fun costColor(cost: Float, reject: Float): Color = when {
    cost.isNaN() -> Unknown
    cost > reject -> Bad
    else -> lerp(Good, Mid, (cost / reject).coerceIn(0f, 1f))
}

// ---------------------------------------------------------------- Transitions

@Composable
fun TransitionsScreen(sessionId: Long, onBack: () -> Unit) {
    val state = rememberClipGraph(sessionId)
    ScreenScaffold("Transitions", onBack) {
        GraphGate(state, minClips = 2) { g -> TransitionsContent(g) }
    }
}

@Composable
private fun TransitionsContent(g: ClipGraph) {
    val m = g.matrix
    val n = m.size
    val reject = g.config.transitionCostReject
    var sel by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val allowedCount = (0 until n).sumOf { i -> (0 until n).count { j -> i != j && m.allowed(i, j) } }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "แถว = จบคลิป · คอลัมน์ = เริ่มคลิปถัดไป · ต่อได้ $allowedCount จาก ${n * (n - 1)} คู่ (เกณฑ์ cost ≤ %.2f)".format(reject),
            fontSize = 13.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend(Good, "ต่อเนียน"); Legend(Mid, "พอได้"); Legend(Bad, "เกินเกณฑ์"); Legend(Unknown, "วัดไม่ได้")
        }
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(1f).pointerInput(n) {
                detectTapGestures { p ->
                    val cell = size.width.toFloat() / n
                    val i = (p.y / cell).toInt(); val j = (p.x / cell).toInt()
                    if (i in 0 until n && j in 0 until n) sel = i to j
                }
            },
        ) {
            val cell = size.width / n
            val gap = if (cell > 8f) 1.5f else 0f
            for (i in 0 until n) for (j in 0 until n) {
                val c = if (i == j) costColor(m.cost(i, j), reject).copy(alpha = 0.35f) else costColor(m.cost(i, j), reject)
                drawRect(c, Offset(j * cell + gap, i * cell + gap), Size(cell - 2 * gap, cell - 2 * gap))
            }
            sel?.let { (i, j) ->
                drawRect(Color.White, Offset(j * cell, i * cell), Size(cell, cell), style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
            }
        }
        Text("แตะช่องเพื่อดูรายละเอียด (เส้นทแยง = วนคลิปเดิม)", fontSize = 12.sp, color = Color.Gray)

        sel?.let { (i, j) -> TransitionDetail(g, i, j) }

        val dead = m.deadEnds()
        val lonely = m.unreachable()
        if (dead.isNotEmpty()) Text("ต่อไปคลิปอื่นไม่ได้เลย: " + dead.joinToString { g.label(it) } + " — จบท่าไม่ตรงท่ากลาง ลองถ่ายใหม่", fontSize = 13.sp, color = Mid)
        if (lonely.isNotEmpty()) Text("ไม่มีคลิปไหนต่อเข้ามาได้: " + lonely.joinToString { g.label(it) }, fontSize = 13.sp, color = Mid)

        Text("ท่ากลางตอนเริ่ม / จบ ของแต่ละคลิป", fontWeight = FontWeight.Bold)
        g.clips.indices.forEach { i ->
            Text(
                "clip ${g.label(i)} · ${g.clips[i].tag} · %.1f s · เริ่ม %s · จบ %s".format(
                    g.edges[i].durationMs / 1000f, g.startScores[i]?.let { "%.0f".format(it) } ?: "—", g.endScores[i]?.let { "%.0f".format(it) } ?: "—",
                ),
                fontSize = 13.sp,
            )
        }
        Box(Modifier.height(24.dp))
    }
}

@Composable
private fun Legend(c: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(c))
        Text(" $label", fontSize = 12.sp)
    }
}

@Composable
private fun TransitionDetail(g: ClipGraph, i: Int, j: Int) {
    val m = g.matrix
    val cost = m.cost(i, j)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("clip ${g.label(i)} → clip ${g.label(j)}", fontWeight = FontWeight.Bold)
            Text(
                if (cost.isNaN()) "วัดไม่ได้ (ไม่พบหน้าในเฟรมใดเฟรมหนึ่ง)"
                else "cost %.3f · %s · crossfade %d เฟรม".format(cost, if (m.allowed(i, j)) "ต่อได้" else "เกินเกณฑ์", m.crossfadeFrames(i, j)),
                color = costColor(cost, g.config.transitionCostReject),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EdgeFrame(g.clips[i].clipPath!!, atEnd = true, label = "จบ ${g.label(i)}", modifier = Modifier.weight(1f))
                EdgeFrame(g.clips[j].clipPath!!, atEnd = false, label = "เริ่ม ${g.label(j)}", modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun EdgeFrame(path: String, atEnd: Boolean, label: String, modifier: Modifier) {
    val bmp by produceState<Bitmap?>(null, path, atEnd) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                MediaMetadataRetriever().run {
                    try {
                        setDataSource(path)
                        val dur = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        getFrameAtTime(if (atEnd) maxOf(0L, dur - 80) * 1000 else 0L, MediaMetadataRetriever.OPTION_CLOSEST)?.let { f ->
                            val s = 360f / maxOf(f.width, f.height)
                            Bitmap.createScaledBitmap(f, (f.width * s).toInt(), (f.height * s).toInt(), true)
                        }
                    } finally { release() }
                }
            }.getOrNull()
        }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(8.dp)).background(Color.DarkGray)) {
            bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(0.75f), contentScale = ContentScale.Crop) }
        }
        Text(label, fontSize = 12.sp)
    }
}

// ---------------------------------------------------------- Sequence Preview

@Composable
fun SequencePreviewScreen(sessionId: Long, onBack: () -> Unit) {
    val state = rememberClipGraph(sessionId)
    val context = LocalContext.current
    val session by remember { AppDatabase.get(context).sessions().observe(sessionId) }.collectAsState(initial = null)
    ScreenScaffold("Sequence Preview", onBack) {
        GraphGate(state, minClips = 1) { g -> PreviewContent(sessionId, g, session?.portrait ?: true) }
    }
}

private const val DEFAULT_SCRIPT =
    "สวัสดีครับทุกคน ยินดีต้อนรับเข้าสู่ไลฟ์ของเรานะครับ\n" +
        "วันนี้เรามีสินค้าดี ๆ มาแนะนำหลายอย่างเลย\n" +
        "ใครสนใจตัวไหน พิมพ์ถามในแชทได้เลยครับ"

/** Mouth library + per-clip tracks, built on demand and cached on disk. */
private sealed class MouthState {
    data object Checking : MouthState()
    data object NoTake : MouthState()
    data object NeedLibrary : MouthState()
    data class Working(val text: String, val done: Int, val total: Int) : MouthState()
    data class Ready(val library: MouthLibrary, val tracks: Map<Int, MouthTrack>) : MouthState()
    data class Failed(val message: String) : MouthState()
}

@Composable
private fun PreviewContent(sessionId: Long, g: ClipGraph, portrait: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files = remember(sessionId) { SessionFiles(context, sessionId) }
    var speaking by remember { mutableStateOf(false) }
    var subtitle by remember { mutableStateOf<String?>(null) }
    var script by remember { mutableStateOf(DEFAULT_SCRIPT) }
    var paused by remember { mutableStateOf(false) }
    var ttsStatus by remember { mutableStateOf("กำลังเตรียมเสียงพูด…") }
    var ttsReady by remember { mutableStateOf(false) }
    var speechJob by remember { mutableStateOf<Job?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var mouth by remember { mutableStateOf<MouthState>(MouthState.Checking) }
    var mouthOn by remember { mutableStateOf(true) }
    val log = remember { mutableStateListOf<String>() }
    var nowPlaying by remember { mutableStateOf("") }

    val speech = remember { SpeechPlayer(context) { text, ok -> ttsStatus = text; ttsReady = ok } }
    DisposableEffect(speech) { onDispose { speech.release() } }

    val lip = (mouth as? MouthState.Ready)?.let { m -> remember(m) { LipSync(m.library, m.tracks, speech) } }
    val lipRef = remember { arrayOfNulls<LipSync>(1) }
    lipRef[0] = lip?.takeIf { mouthOn }

    val planner = remember(g) { SequencePlanner(g.matrix, g.clips.map { it.tag }, g.config) }
    val engine = remember(g) {
        SequenceEngine(
            context, g, planner,
            preferTag = { if (speech.speaking) "active" else "calm" },
            onCut = { cut ->
                nowPlaying = "clip ${g.label(cut.to)} · ${g.clips[cut.to].tag}"
                cut.step?.let { s ->
                    val line = "${g.label(cut.from!!)} → ${g.label(cut.to)} · " +
                        (if (s.cost.isNaN()) "cost —" else "cost %.2f".format(s.cost)) + " · ${s.crossfadeFrames} เฟรม" +
                        (if (s.forced) " · ⚠ ไม่มีคู่ที่ผ่านเกณฑ์" else "")
                    log.add(0, line)
                    if (log.size > 8) log.removeAt(log.lastIndex)
                }
            },
            lipSync = { lipRef[0] },
        )
    }
    DisposableEffect(engine) {
        engine.start()
        onDispose { engine.release() }
    }
    LaunchedEffect(engine) {
        while (true) withFrameMillis {
            engine.tick(it)
            speaking = speech.speaking
            subtitle = speech.line
        }
    }

    // Load the mouth library if there is one, then make sure every clip has a mouth track.
    suspend fun prepareTracks(library: MouthLibrary) {
        val tracks = HashMap<Int, MouthTrack>()
        val todo = g.clips.indices.filter { MouthTracks.loadCached(File(g.clips[it].clipPath!!))?.also { t -> tracks[it] = t } == null }
        todo.forEachIndexed { k, i ->
            mouth = MouthState.Working("กำลังหาตำแหน่งปากในคลิป ${g.label(i)}", k, todo.size)
            tracks[i] = withContext(Dispatchers.Default) { MouthTracks.build(context, File(g.clips[i].clipPath!!)) }
        }
        mouth = MouthState.Ready(library, tracks)
    }
    LaunchedEffect(files) {
        mouth = try {
            val lib = withContext(Dispatchers.IO) { MouthLibrary.load(files) }
            when {
                lib != null -> { prepareTracks(lib); mouth }
                withContext(Dispatchers.IO) { files.speechTake.exists() } -> MouthState.NeedLibrary
                else -> MouthState.NoTake
            }
        } catch (e: Throwable) {
            MouthState.Failed("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.fillMaxWidth(if (portrait) 0.78f else 1f).align(Alignment.CenterHorizontally)
                .aspectRatio(if (portrait) 9f / 16f else 16f / 9f).clip(RoundedCornerShape(14.dp)).background(Color.Black),
        ) {
            AndroidView(factory = { engine.view }, modifier = Modifier.fillMaxWidth().aspectRatio(if (portrait) 9f / 16f else 16f / 9f))
            subtitle?.let {
                Text(
                    it, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp).clip(RoundedCornerShape(8.dp))
                        .background(Color(0xAA000000)).padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            Text(
                (if (speaking) "🔊 กำลังพูด · " else "") + nowPlaying, color = Color.White, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(6.dp))
                    .background(Color(0x88000000)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { paused = !paused; engine.setPaused(paused) }) { Text(if (paused) "เล่นต่อ" else "หยุดชั่วคราว") }
        }

        Text("จำลองการพูด", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text(
            "พิมพ์บท (บรรทัดละประโยค) แล้วกดพูด: ระหว่างพูดจะเลือกคลิป active ช่วงเงียบจะเลือกคลิป calm และถ้ามีคลังปาก จะใส่ปากจริงของคุณให้ขยับตามเสียง",
            fontSize = 12.sp, color = Color.Gray,
        )
        OutlinedTextField(script, { script = it }, modifier = Modifier.fillMaxWidth().height(140.dp), label = { Text("บทพูด") })
        Text(ttsStatus, fontSize = 12.sp, color = if (ttsReady) Good else Mid)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = ttsReady && !preparing, onClick = {
                speechJob?.cancel()
                val lines = script.lines().map { it.trim() }.filter { it.isNotEmpty() }
                speechJob = scope.launch {
                    try {
                        preparing = true
                        val rendered = try { speech.prepare(lines) } finally { preparing = false }
                        speech.play(rendered, gapMs = 1200)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        ttsStatus = "พูดไม่สำเร็จ: ${e.message}"
                    }
                }
            }) { Text(if (preparing) "กำลังสร้างเสียง…" else "▶ พูด") }
            OutlinedButton(onClick = { speechJob?.cancel(); speechJob = null }) { Text("หยุดพูด") }
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { ttsStatus = "เปิดหน้าตั้งค่าเสียงไม่ได้ — ไปที่ ตั้งค่า > การจัดการทั่วไป > Text-to-speech" }
            }) {
                Text("ตั้งค่าเสียง")
            }
        }
        val tags = g.clips.groupingBy { it.tag }.eachCount()
        Text("คลิป active ${tags["active"] ?: 0} · calm ${tags["calm"] ?: 0}", fontSize = 12.sp, color = Color.Gray)

        Text("ปากขยับตามเสียง (lip-sync)", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        when (val m = mouth) {
            MouthState.Checking -> Text("กำลังตรวจคลังปาก…", fontSize = 13.sp)
            MouthState.NoTake -> Text(
                "ยังไม่มีคลังปาก: ไปหน้าถ่าย → เลือกโหมด \"พูด\" (ข้าง calm/active) → กดอัด แล้วพูดอะไรก็ได้ 20–30 วินาที หน้าตรงทับเงา → กดหยุด แล้วกลับมาหน้านี้",
                fontSize = 13.sp,
            )
            MouthState.NeedLibrary -> Button(onClick = {
                scope.launch {
                    mouth = try {
                        val lib = MouthLibrary.build(context, files, g.reference) { d, t -> mouth = MouthState.Working("กำลังตัดปากจากเทคพูด", d, t) }
                        prepareTracks(lib)
                        mouth
                    } catch (e: Throwable) {
                        MouthState.Failed(e.message ?: e.toString())
                    }
                }
            }) { Text("สร้างคลังปากจากเทคพูด") }
            is MouthState.Working -> {
                Text("${m.text} ${m.done}/${m.total}", fontSize = 13.sp)
                LinearProgressIndicator(progress = { if (m.total == 0) 0f else m.done.toFloat() / m.total }, modifier = Modifier.fillMaxWidth())
                Text("ทำครั้งเดียว ครั้งต่อไปใช้ผลที่เก็บไว้", fontSize = 12.sp, color = Color.Gray)
            }
            is MouthState.Ready -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(mouthOn, { mouthOn = it })
                    Text("ใส่ปาก (${m.library.samples.size} แบบ · ${m.tracks.size}/${g.clips.size} คลิป)", fontSize = 14.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    m.library.samples.take(8).forEach { s ->
                        Image(s.bitmap.asImageBitmap(), null, Modifier.size(38.dp).clip(RoundedCornerShape(6.dp)).background(Color.DarkGray))
                    }
                }
                Text(
                    "ปากขยับตามความดังของเสียง (ยังไม่แยกสระ/พยัญชนะ) · ถ้าสีปากไม่เนียน ให้อัดเทคพูดใหม่หลัง Calibrate ในแสงเดียวกับคลิป",
                    fontSize = 12.sp, color = Color.Gray,
                )
            }
            is MouthState.Failed -> {
                Text("สร้างคลังปากไม่สำเร็จ: ${m.message}", color = Bad, fontSize = 13.sp)
                if (files.speechTake.exists()) OutlinedButton(onClick = { mouth = MouthState.NeedLibrary }) { Text("ลองใหม่") }
            }
        }

        Text("การตัดล่าสุด", fontWeight = FontWeight.Bold)
        if (log.isEmpty()) Text("ยังไม่มี", fontSize = 12.sp, color = Color.Gray)
        log.forEach { Text(it, fontSize = 12.sp, color = if (it.contains("⚠")) Mid else Color.Unspecified) }
        Box(Modifier.height(24.dp))
    }
}

// -------------------------------------------------------------------- Export

@Composable
fun ExportScreen(sessionId: Long, onBack: () -> Unit) {
    val state = rememberClipGraph(sessionId)
    ScreenScaffold("Export Clip Package", onBack) {
        GraphGate(state, minClips = 1) { g -> ExportContent(sessionId, g) }
    }
}

@Composable
private fun ExportContent(sessionId: Long, g: ClipGraph) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by remember { AppDatabase.get(context).sessions().observe(sessionId) }.collectAsState(initial = null)
    var includeRetake by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var result by remember { mutableStateOf<PackageExporter.Result?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val retake = g.clips.count { it.qcStatus == "RETAKE" }
    val count = if (includeRetake) g.clips.size else g.clips.size - retake

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("ไฟล์ .zip เดียว: คลิป (clips/clip_###.mp4), reference.png / reference.json และ manifest.json (ข้อมูล session, ค่ากล้องที่ล็อก, config, QC ของแต่ละคลิป, transition matrix + crossfade)", fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(includeRetake, { includeRetake = it })
            Text("รวมคลิปที่ QC บอกให้ถ่ายใหม่ ($retake คลิป)", fontSize = 14.sp)
        }
        Text("จะใส่ $count คลิป", fontWeight = FontWeight.Bold)
        Button(
            enabled = progress == null && count > 0 && session != null,
            onClick = {
                val s = session ?: return@Button
                error = null; result = null; progress = 0 to count
                scope.launch {
                    try {
                        result = PackageExporter.export(context, s, g, includeRetake) { d, t -> progress = d to t }
                    } catch (e: Throwable) {
                        error = "${e.javaClass.simpleName}: ${e.message}"
                    } finally {
                        progress = null
                    }
                }
            },
        ) { Text("สร้าง Clip Package") }
        progress?.let { (d, t) ->
            LinearProgressIndicator(progress = { if (t == 0) 0f else d.toFloat() / t }, modifier = Modifier.fillMaxWidth())
            Text("กำลังเขียน $d/$t คลิป", fontSize = 12.sp)
        }
        error?.let { Text("ไม่สำเร็จ: $it", color = Bad) }
        result?.let { r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("สร้างแล้ว ✓", color = Good, fontWeight = FontWeight.Bold)
                    Text("${r.file.name}\n${r.clipCount} คลิป · %.1f MB".format(r.bytes / 1_048_576f), fontSize = 13.sp)
                    Text("เก็บไว้ที่ Android/data/${context.packageName}/files/exports", fontSize = 12.sp, color = Color.Gray)
                    Button(onClick = { share(context, r.file) }) { Text("แชร์ / ส่งไฟล์") }
                }
            }
        }
        Text("ส่งผ่าน LAN upload: ยังไม่รองรับ — ใช้ปุ่มแชร์ (Drive, LINE, Nearby Share, อีเมล ฯลฯ)", fontSize = 12.sp, color = Color.Gray)
        PreviousExports(context)
        Box(Modifier.height(24.dp))
    }
}

@Composable
private fun PreviousExports(context: android.content.Context) {
    val files = remember { PackageExporter.exportDir(context).listFiles { f -> f.extension == "zip" }?.sortedByDescending { it.lastModified() } ?: emptyList() }
    if (files.isEmpty()) return
    Text("แพ็กเกจที่สร้างไว้", fontWeight = FontWeight.Bold)
    files.take(5).forEach { f ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${f.name} · %.1f MB".format(f.length() / 1_048_576f), fontSize = 12.sp, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { share(context, f) }) { Text("แชร์") }
        }
    }
}

private fun share(context: android.content.Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "ส่ง Clip Package").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
