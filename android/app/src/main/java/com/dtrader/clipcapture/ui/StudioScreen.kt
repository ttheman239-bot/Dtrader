package com.dtrader.clipcapture.ui

import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dtrader.clipcapture.capture.Phase
import com.dtrader.clipcapture.capture.StudioUi
import com.dtrader.clipcapture.capture.StudioViewModel
import kotlinx.coroutines.delay

private val Green = Color(0xFF22C55E)
private val Amber = Color(0xFFF59E0B)
private val Red = Color(0xFFEF4444)
private val Glass = Color(0x99000000)

@Composable
fun StudioScreen(vm: StudioViewModel, onLibrary: () -> Unit, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    // FIT_CENTER shows the whole camera frame (nothing cropped off the sides).
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    val session = ui.session
    val front = session?.lensFacing == CameraSelector.LENS_FACING_FRONT
    var showSetup by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(session?.id) { if (session != null) vm.bindCamera(owner, previewView) }
    DisposableEffect(Unit) { onDispose { vm.unbindCamera() } }
    LaunchedEffect(ui.found.size) { if (ui.found.isNotEmpty()) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }

    val recording = ui.phase == Phase.RECORDING || ui.phase == Phase.STARTING
    val ready = ui.locked && ui.reference != null

    // Green frame around the picture while a clip is being collected.
    val frameColor by animateColorAsState(
        when {
            ui.phase == Phase.RECORDING && ui.collecting -> Green
            ui.phase == Phase.RECORDING -> Red.copy(alpha = 0.7f)
            else -> Color.Transparent
        },
        label = "frame",
    )

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        if (ui.ghost && ui.reference != null && !recording) {
            Image(
                ui.reference!!.asImageBitmap(), null,
                Modifier.fillMaxSize().graphicsLayer { alpha = 0.3f; scaleX = if (front) -1f else 1f },
                contentScale = ContentScale.Fit,
            )
        }
        Box(Modifier.fillMaxSize().border(4.dp, frameColor))

        // Top scrim + HUD
        Column(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                .safeDrawingPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundIcon("‹", enabled = !recording, onClick = onBack)
                Spacer(Modifier.width(8.dp))
                if (recording) RecPill(ui.elapsedMs) else Text(session?.name ?: "", color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.weight(1f))
                if (recording) FoundPill(ui.found.size)
                else {
                    Toggle("มุมกว้าง", ui.wide) { vm.setWide(!ui.wide) }
                    Spacer(Modifier.width(6.dp))
                    Toggle("Ghost", ui.ghost) { vm.setGhost(!ui.ghost) }
                    Spacer(Modifier.width(6.dp))
                    RoundIcon("⚙", enabled = true) { showSetup = !showSetup }
                }
            }
            Spacer(Modifier.height(8.dp))
            ScorePill(ui)
        }

        // Banner (clip found / rejected / status)
        BannerView(ui, Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 108.dp))

        // Bottom area
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                .safeDrawingPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ui.message?.let { MessageCard(it, vm::clearMessage) }
            if (!ready && !recording) SetupSteps(ui, vm)
            AnimatedContent(ui.cue.takeIf { recording }, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "cue") { cue ->
                if (cue != null) {
                    Text(
                        if (ui.collecting) "ทำท่า: $cue\nแล้วกลับท่ากลางค้างไว้" else "กลับท่ากลางค้าง 1 วินาทีเพื่อเริ่มท่าใหม่",
                        color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    PillButton("คลัง ${ui.clipCount}", enabled = !recording && ui.phase != Phase.CUTTING, onClick = onLibrary)
                }
                RecordButton(
                    recording = recording,
                    enabled = when (ui.phase) {
                        Phase.IDLE -> ready
                        Phase.RECORDING -> true
                        else -> false
                    },
                    onClick = { if (recording) vm.stopRecording() else vm.startRecording() },
                )
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Row(Modifier.clip(RoundedCornerShape(50)).background(Glass)) {
                        Segment("calm", ui.tag == "calm", !recording) { vm.setTag("calm") }
                        Segment("active", ui.tag == "active", !recording) { vm.setTag("active") }
                    }
                }
            }
            Text(
                if (ui.modelsReady) "วิเคราะห์ %.0f fps · %d ms".format(ui.analysisFps, ui.inferenceMs) else "กำลังโหลดโมเดล…",
                color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (showSetup && !recording) SetupPanel(ui, vm) { showSetup = false }

        ui.cutProgress?.let { (i, n) ->
            Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Text("กำลังตัดคลิป $i / $n", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { (i - 1f) / n }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Green)
                    Text("trim · ตัดเสียง · 25 fps · keyframe ทุก 1 วินาที", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        if (ui.phase == Phase.SAVING) {
            Box(Modifier.fillMaxSize().background(Color(0x88000000)), contentAlignment = Alignment.Center) {
                Text("กำลังบันทึกวิดีโอ…", color = Color.White, fontSize = 18.sp)
            }
        }

        ui.error?.let { err ->
            Box(Modifier.fillMaxSize().background(Color(0xEE000000)).padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ใช้งานกล้องไม่ได้", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(err, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(vertical = 12.dp), textAlign = TextAlign.Center)
                    PillButton("กลับ", true, onBack)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScorePill(ui: StudioUi) {
    val score = ui.match?.score
    val threshold = ui.config.poseMatchStart
    val color by animateColorAsState(
        when {
            score == null -> Color.Gray
            score >= threshold -> Green
            score >= threshold - 25 -> Amber
            else -> Red
        },
        label = "score",
    )
    val progress by animateFloatAsState((score ?: 0f) / 100f, label = "progress")
    Column {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Glass).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    ui.reference == null -> "ยังไม่มีท่ากลาง"
                    score == null -> "ท่ากลาง —"
                    score >= threshold -> "ท่ากลาง ${score.toInt()} ✓"
                    else -> "ท่ากลาง ${score.toInt()}"
                },
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(10.dp))
            Box(Modifier.width(80.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.2f))) {
                Box(Modifier.fillMaxWidth(progress).height(4.dp).background(color))
            }
        }
        val warns = ui.match?.warnings.orEmpty()
        if (warns.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                warns.forEach {
                    Text(
                        it.text, color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Red.copy(alpha = 0.85f)).padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BannerView(ui: StudioUi, modifier: Modifier) {
    var visible by remember { mutableStateOf(false) }
    val banner = ui.banner
    LaunchedEffect(banner?.id) {
        if (banner != null) { visible = true; delay(2600); visible = false }
    }
    AnimatedVisibility(visible && banner != null, modifier, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
        Text(
            banner?.text ?: "",
            color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(14.dp))
                .background(if (banner?.good == true) Green.copy(alpha = 0.92f) else Color(0xE6374151))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun RecordButton(recording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val inner by animateDpAsState(if (recording) 30.dp else 62.dp, label = "inner")
    val corner by animateDpAsState(if (recording) 8.dp else 31.dp, label = "corner")
    Box(
        Modifier.size(80.dp).clip(CircleShape).border(4.dp, Color.White.copy(alpha = if (enabled) 1f else 0.4f), CircleShape)
            .clickableNoRipple { if (enabled) onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(inner).clip(RoundedCornerShape(corner)).background(Red.copy(alpha = if (enabled) 1f else 0.4f)))
    }
}

@Composable
private fun RecPill(elapsedMs: Long) {
    val s = elapsedMs / 1000
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Red).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
        Spacer(Modifier.width(6.dp))
        Text("%d:%02d".format(s / 60, s % 60), color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FoundPill(count: Int) {
    Text(
        "พบ $count ท่า", color = Color.White, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (count > 0) Green else Glass).padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

@Composable
private fun RoundIcon(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(Glass).clickableNoRipple { if (enabled) onClick() },
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color.White.copy(alpha = if (enabled) 1f else 0.4f), fontSize = 18.sp) }
}

@Composable
private fun Toggle(text: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text, color = if (on) Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Color.White else Glass)
            .clickableNoRipple(onClick).padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

@Composable
private fun Segment(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text, color = if (selected) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) Color.White else Color.Transparent)
            .clickableNoRipple { if (enabled) onClick() }.padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun PillButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text, color = Color.White.copy(alpha = if (enabled) 1f else 0.4f), fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Glass)
            .clickableNoRipple { if (enabled) onClick() }.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun MessageCard(text: String, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xE61F2937)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text("✕", color = Color.White.copy(alpha = 0.7f), modifier = Modifier.clickableNoRipple(onClose).padding(start = 12.dp))
    }
}

@Composable
private fun SetupSteps(ui: StudioUi, vm: StudioViewModel) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xE61F2937)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("ตั้งค่าก่อนถ่าย", color = Color.White, fontWeight = FontWeight.Bold)
        StepRow(1, "ล็อกแสง/สี/โฟกัส", done = ui.locked, busy = ui.calibrating, action = if (ui.calibrating) "กำลังปรับ…" else "Calibrate", enabled = ui.caps != null && !ui.calibrating, onClick = vm::calibrate)
        StepRow(2, "นั่งท่ากลาง ปากปิด เห็นไหล่", done = ui.reference != null, busy = false, action = "บันทึกท่ากลาง", enabled = ui.hasFace, onClick = vm::saveReference)
    }
}

@Composable
private fun StepRow(n: Int, text: String, done: Boolean, busy: Boolean, action: String, enabled: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(if (done) Green else Color(0xFF4B5563)),
            contentAlignment = Alignment.Center,
        ) { Text(if (done) "✓" else "$n", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        Text(text, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
        Text(
            if (done && !busy) "ทำใหม่" else action,
            color = if (enabled) Color.Black else Color.Black.copy(alpha = 0.4f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = if (enabled) 1f else 0.5f))
                .clickableNoRipple { if (enabled) onClick() }.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun SetupPanel(ui: StudioUi, vm: StudioViewModel, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickableNoRipple(onClose), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(Color(0xFF111827))
                .clickableNoRipple { }.safeDrawingPadding().padding(18.dp).heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("กล้องและท่ากลาง", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            StepRow(1, "ล็อกแสง/สี/โฟกัส", ui.locked, ui.calibrating, if (ui.calibrating) "กำลังปรับ…" else "Calibrate", ui.caps != null && !ui.calibrating, vm::calibrate)
            StepRow(2, "บันทึกท่ากลาง", ui.reference != null, false, "บันทึกท่ากลาง", ui.hasFace, vm::saveReference)
            Info("fps", ui.caps?.fpsNote ?: "—")
            Info("ซูม", ui.zoom?.let { "%.2f×".format(it) } ?: "—")
            Info("โมเดล", ui.delegates.ifEmpty { "—" })
            Info("วิเคราะห์", "%.1f fps · %d ms ต่อเฟรม".format(ui.analysisFps, ui.inferenceMs))
            if (ui.lockNotes.isNotEmpty()) {
                Text("รายละเอียดการล็อก", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                ui.lockNotes.forEach { Text("• $it", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp) }
            }
            Text(
                "วิธีถ่าย: กดอัด → ทำท่าไปเรื่อยๆ แต่ละท่า ${ui.config.clipMinSec.toInt()}–${ui.config.clipMaxSec.toInt()} วินาที แล้วกลับท่ากลางค้าง 1 วินาที " +
                    "แอปจะตัดท่าที่ใช้ได้ให้เองและแจ้งทุกครั้ง กดหยุดเมื่อพอใจ",
                color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
            )
            PillButton("ปิด", true, onClose)
        }
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row {
        Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.width(80.dp))
        Text(value, color = Color.White, fontSize = 13.sp)
    }
}
