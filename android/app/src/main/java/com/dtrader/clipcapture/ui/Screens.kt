package com.dtrader.clipcapture.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.camera.core.CameraSelector
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtrader.clipcapture.capture.QcStatus
import com.dtrader.clipcapture.config.CaptureConfig
import com.dtrader.clipcapture.config.ConfigStore
import com.dtrader.clipcapture.data.AppDatabase
import com.dtrader.clipcapture.data.ClipEntity
import com.dtrader.clipcapture.data.SessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.text.DateFormat

@Composable
fun SessionsScreen(onOpen: (Long) -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val db = remember { AppDatabase.get(context) }
    val sessions by db.sessions().observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }

    ScreenScaffold("Sessions", null) {
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { creating = true }) { Text("+ สร้าง session") }
            OutlinedButton(onClick = onSettings) { Text("Settings") }
        }
        LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sessions, key = { it.id }) { s ->
                Card(Modifier.fillMaxWidth().clickableNoRipple { onOpen(s.id) }) {
                    Column(Modifier.padding(14.dp)) {
                        Text(s.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(
                            (if (s.lensFacing == CameraSelector.LENS_FACING_FRONT) "กล้องหน้า" else "กล้องหลัง") + " · " +
                                (if (s.portrait) "1080×1920" else "1920×1080") + " · " +
                                DateFormat.getDateTimeInstance().format(s.createdAt),
                            fontSize = 13.sp,
                        )
                        Text(
                            (if (s.cameraSettingsJson != null) "calibrate แล้ว" else "ยังไม่ calibrate") + " · " +
                                (if (s.referenceDataPath != null) "มีท่ากลาง" else "ยังไม่มีท่ากลาง"),
                            fontSize = 12.sp, color = Color.Gray,
                        )
                    }
                }
            }
        }
    }

    if (creating) {
        var name by remember { mutableStateOf("Session ${sessions.size + 1}") }
        var front by remember { mutableStateOf(true) }
        var portrait by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("Session ใหม่") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("ชื่อ") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(front, { front = true }, { Text("กล้องหน้า") })
                        FilterChip(!front, { front = false }, { Text("กล้องหลัง") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(portrait, { portrait = true }, { Text("แนวตั้ง 1080×1920") })
                        FilterChip(!portrait, { portrait = false }, { Text("แนวนอน") })
                    }
                    Text("ตั้งกล้องบนขาตั้งก่อนเริ่ม", fontSize = 12.sp, color = Color.Gray)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    creating = false
                    scope.launch {
                        val id = db.sessions().insert(
                            SessionEntity(
                                name = name.ifBlank { "Session" },
                                createdAt = System.currentTimeMillis(),
                                lensFacing = if (front) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK,
                                portrait = portrait,
                            ),
                        )
                        onOpen(id)
                    }
                }) { Text("สร้าง") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("ยกเลิก") } },
        )
    }
}

/** Per-session menu (spec §9 screens 2–7). */
@Composable
fun SessionMenuScreen(sessionId: Long, onNavigate: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { AppDatabase.get(context) }
    val session by db.sessions().observe(sessionId).collectAsState(initial = null)
    val clips by db.clips().observeForSession(sessionId).collectAsState(initial = emptyList())
    val passed = clips.count { it.qcStatus != QcStatus.RETAKE.name }
    ScreenScaffold(session?.name ?: "", onBack) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("คลิปทั้งหมด ${clips.size} · ผ่าน QC เบื้องต้น $passed", fontSize = 14.sp)
            MenuButton("ตั้งกล้อง / Calibrate / ถ่าย", "M1–M2") { onNavigate("studio") }
            MenuButton("คลังคลิป (Library)", "M1–M2") { onNavigate("library") }
            MenuButton("Transitions (heatmap)", "M5 · ยังไม่รองรับ") { onNavigate("transitions") }
            MenuButton("Sequence Preview", "M5 · ยังไม่รองรับ") { onNavigate("preview") }
            MenuButton("Export Clip Package", "M6 · ยังไม่รองรับ") { onNavigate("export") }
        }
    }
}

@Composable
private fun MenuButton(title: String, note: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickableNoRipple(onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(note, fontSize = 12.sp, color = Color.Gray)
        }
    }
}

@Composable
fun LibraryScreen(sessionId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { AppDatabase.get(context) }
    val clips by db.clips().observeForSession(sessionId).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf<ClipEntity?>(null) }
    val passed = clips.count { it.qcStatus != QcStatus.RETAKE.name }

    ScreenScaffold("คลังคลิป", onBack) {
        Text(
            "ผ่าน $passed / ${clips.size} คลิป · สถานะเป็น QC เบื้องต้นจากการวิเคราะห์ระหว่างถ่าย (QC ทุกเฟรม, ตัดจุด loop และ export อยู่ใน M3)",
            fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyColumn(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(clips, key = { it.id }) { clip ->
                ClipRow(clip) { confirmDelete = clip }
            }
        }
    }

    confirmDelete?.let { clip ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("ลบคลิป %03d?".format(clip.number)) },
            text = { Text("ไฟล์จะถูกลบถาวร") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch {
                        withContext(Dispatchers.IO) { File(clip.rawPath).delete() }
                        db.clips().delete(clip)
                    }
                }) { Text("ลบ") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("ยกเลิก") } },
        )
    }
}

@Composable
private fun ClipRow(clip: ClipEntity, onDelete: () -> Unit) {
    val thumb by produceState<Bitmap?>(null, clip.rawPath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                MediaMetadataRetriever().run {
                    try {
                        setDataSource(clip.rawPath)
                        getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { f ->
                            val scale = 160f / maxOf(f.width, f.height)
                            Bitmap.createScaledBitmap(f, (f.width * scale).toInt(), (f.height * scale).toInt(), true)
                        }
                    }
                    finally { release() }
                }
            }.getOrNull()
        }
    }
    val status = runCatching { QcStatus.valueOf(clip.qcStatus) }.getOrDefault(QcStatus.RETAKE)
    val color = when (status) { QcStatus.PASS -> Color(0xFF16A34A); QcStatus.WARN -> Color(0xFFCA8A04); QcStatus.RETAKE -> Color(0xFFDC2626) }
    val reasons = runCatching { JSONArray(clip.qcReasonsJson).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
    val exists = remember(clip.rawPath) { File(clip.rawPath).exists() }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp)) {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)).background(Color.DarkGray)) {
                thumb?.let { Image(it.asImageBitmap(), null, Modifier.size(72.dp), contentScale = ContentScale.Crop) }
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("clip %03d · %s · %.1f s".format(clip.number, clip.tag, clip.durationMs / 1000f), fontWeight = FontWeight.Bold)
                Text(status.label, color = color, fontSize = 13.sp)
                reasons.forEach { Text("• $it", fontSize = 12.sp) }
                if (!exists) Text("ไม่พบไฟล์", color = Color.Red, fontSize = 12.sp)
            }
            TextButton(onClick = onDelete) { Text("ลบ") }
        }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ConfigStore(context) }
    val initial = remember { store.load() }
    val text = remember { mutableStateMapOf<String, String>().apply { CaptureConfig.FIELDS.forEach { put(it.key, it.get(initial).toString()) } } }
    var saved by remember { mutableStateOf<String?>(null) }

    ScreenScaffold("Settings (config §8)", onBack) {
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                var cfg = CaptureConfig()
                val bad = mutableListOf<String>()
                CaptureConfig.FIELDS.forEach { f ->
                    val v = text[f.key]?.trim()?.toDoubleOrNull()
                    if (v == null) bad += f.key else cfg = f.set(cfg, v)
                }
                if (bad.isEmpty()) { store.save(cfg); saved = "บันทึกแล้ว (มีผลเมื่อเปิดหน้าถ่ายครั้งถัดไป)" } else saved = "ค่าไม่ถูกต้อง: ${bad.joinToString()}"
            }) { Text("บันทึก") }
            OutlinedButton(onClick = { CaptureConfig.FIELDS.forEach { text[it.key] = it.get(CaptureConfig()).toString() } }) { Text("ค่าเริ่มต้น") }
        }
        saved?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 13.sp) }
        LazyColumn(Modifier.padding(horizontal = 16.dp)) {
            items(CaptureConfig.FIELDS, key = { it.key }) { f ->
                OutlinedTextField(
                    value = text[f.key].orEmpty(),
                    onValueChange = { text[f.key] = it },
                    label = { Text(f.label) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                )
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}
