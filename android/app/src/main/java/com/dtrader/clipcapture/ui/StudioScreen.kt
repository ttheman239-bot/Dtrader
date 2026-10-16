package com.dtrader.clipcapture.ui

import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dtrader.clipcapture.capture.Phase
import com.dtrader.clipcapture.capture.QcStatus
import com.dtrader.clipcapture.capture.StudioViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StudioScreen(vm: StudioViewModel, onLibrary: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val session = ui.session
    val front = session?.lensFacing == CameraSelector.LENS_FACING_FRONT

    LaunchedEffect(session?.id) { if (session != null) vm.bindCamera(owner, previewView) }
    DisposableEffect(Unit) { onDispose { vm.unbindCamera() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ previewView }, Modifier.fillMaxSize())

        // Ghost overlay of the neutral-pose reference (§4.2). The preview of a front
        // camera is mirrored, so the reference is mirrored the same way.
        if (ui.ghost && ui.reference != null) {
            Image(
                ui.reference!!.asImageBitmap(), null,
                Modifier.fillMaxSize().graphicsLayer { alpha = 0.3f; scaleX = if (front) -1f else 1f },
                contentScale = ContentScale.Crop,
            )
        }

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
            ScoreBar(ui.match?.score, ui.config.poseMatchStart, ui.hasFace, ui.reference != null)
            val warns = ui.match?.warnings.orEmpty()
            if (warns.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    warns.forEach { Pill(it.text, Color(0xFFDC2626)) }
                }
            }
            Text(
                "วิเคราะห์ %.1f fps · %d ms · %s".format(ui.analysisFps, ui.inferenceMs, ui.delegates.ifEmpty { "กำลังโหลดโมเดล…" }),
                color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(Modifier.weight(1f))

            // Recording state + movement cue.
            if (ui.phase != Phase.IDLE) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (ui.phase == Phase.RECORD_MOVE || ui.phase == Phase.RECORD_RETURN) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(Color.Red))
                            Spacer(Modifier.width(8.dp))
                            Text("REC %.1f s".format(ui.elapsedMs / 1000f), color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                    ui.instruction?.let {
                        Text(it, color = if (ui.phase == Phase.RECORD_RETURN) Color(0xFFFACC15) else Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            ui.lastClip?.let { (n, qc) ->
                val color = when (qc.status) { QcStatus.PASS -> Color(0xFF16A34A); QcStatus.WARN -> Color(0xFFCA8A04); QcStatus.RETAKE -> Color(0xFFDC2626) }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(10.dp)) {
                    Text("คลิป %03d: %s".format(n, qc.status.label), color = color, fontWeight = FontWeight.Bold)
                    qc.reasons.forEach { Text("• $it", color = Color.White, fontSize = 13.sp) }
                    Text("QC เบื้องต้นจากการวิเคราะห์ระหว่างถ่าย (QC ทุกเฟรมอยู่ใน M3)", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                }
                Spacer(Modifier.height(8.dp))
            }

            ControlPanel(vm, onLibrary)
        }

        ui.error?.let { err ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)).padding(24.dp), contentAlignment = Alignment.Center) {
                Text("ใช้งานกล้องไม่ได้:\n$err", color = Color.White, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun ScoreBar(score: Float?, threshold: Float, hasFace: Boolean, hasReference: Boolean) {
    val color = when {
        score == null -> Color.Gray
        score >= threshold -> Color(0xFF16A34A)
        score >= threshold - 25 -> Color(0xFFEAB308)
        else -> Color(0xFFDC2626)
    }
    val label = when {
        !hasReference -> "Pose Match: บันทึกท่ากลางก่อน"
        !hasFace || score == null -> "Pose Match: —"
        else -> "Pose Match: ${score.toInt()}"
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(10.dp)) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold)
        LinearProgressIndicator(
            progress = { (score ?: 0f) / 100f },
            color = color,
            trackColor = Color.White.copy(alpha = 0.2f),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(8.dp),
        )
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Text(
        text, color = Color.White, fontSize = 13.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun ControlPanel(vm: StudioViewModel, onLibrary: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var showNotes by remember { mutableStateOf(false) }
    val idle = ui.phase == Phase.IDLE
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xE6111827)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ui.message?.let { msg ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(msg, color = Color(0xFF93C5FD), fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::clearMessage) { Text("ปิด") }
            }
        }
        // Setup / calibrate (§4.1)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = vm::calibrate, enabled = idle && !ui.calibrating && ui.caps != null) {
                Text(if (ui.calibrating) "กำลัง Calibrate…" else if (ui.locked) "Calibrate ใหม่" else "Calibrate")
            }
            OutlinedButton(onClick = vm::saveReference, enabled = idle && ui.hasFace) { Text("บันทึกท่ากลาง") }
            OutlinedButton(onClick = { vm.setGhost(!ui.ghost) }) { Text(if (ui.ghost) "Ghost: เปิด" else "Ghost: ปิด") }
        }
        Text(
            (if (ui.locked) "ล็อกค่ากล้องแล้ว" else "ยังไม่ล็อกค่ากล้อง") + " · " + (ui.caps?.fpsNote ?: "") +
                (if (ui.lockNotes.isNotEmpty()) " · แตะเพื่อดูรายละเอียด" else ""),
            color = if (ui.locked) Color(0xFF86EFAC) else Color(0xFFFCA5A5), fontSize = 12.sp,
            modifier = Modifier.clickableNoRipple { showNotes = !showNotes },
        )
        if (showNotes) {
            Column(Modifier.height(90.dp).verticalScroll(rememberScrollState())) {
                ui.lockNotes.forEach { Text("• $it", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp) }
            }
        }
        // Capture (§4.3)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = ui.tag == "calm", onClick = { vm.setTag("calm") }, label = { Text("calm") }, enabled = idle || ui.phase == Phase.WAIT_NEUTRAL)
            FilterChip(selected = ui.tag == "active", onClick = { vm.setTag("active") }, label = { Text("active") }, enabled = idle || ui.phase == Phase.WAIT_NEUTRAL)
            FilterChip(selected = ui.continuous, onClick = { vm.setContinuous(!ui.continuous) }, label = { Text("ถ่ายต่อเนื่อง") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (idle) {
                Button(onClick = vm::start, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)), modifier = Modifier.weight(1f)) {
                    Text("Start (อัดอัตโนมัติ)")
                }
            } else {
                Button(onClick = vm::stop, enabled = ui.phase != Phase.SAVING, modifier = Modifier.weight(1f)) {
                    Text(if (ui.phase == Phase.WAIT_NEUTRAL) "หยุดรอ" else "ยกเลิกคลิปนี้")
                }
            }
            OutlinedButton(onClick = onLibrary, enabled = idle) { Text("คลัง (${ui.clipCount})") }
        }
    }
}
