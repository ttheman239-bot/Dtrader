package com.dtrader.clipcapture

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dtrader.clipcapture.capture.StudioViewModel
import com.dtrader.clipcapture.data.AppDatabase
import com.dtrader.clipcapture.ui.ExportScreen
import com.dtrader.clipcapture.ui.LibraryScreen
import com.dtrader.clipcapture.ui.ScreenScaffold
import com.dtrader.clipcapture.ui.SequencePreviewScreen
import com.dtrader.clipcapture.ui.SessionMenuScreen
import com.dtrader.clipcapture.ui.SessionsScreen
import com.dtrader.clipcapture.ui.SettingsScreen
import com.dtrader.clipcapture.ui.StudioScreen
import com.dtrader.clipcapture.ui.TransitionsScreen

/** Simple back stack of routes: "sessions", "settings", "<route>/<sessionId>". */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface { App(this) }
            }
        }
    }
}

@Composable
private fun App(activity: ComponentActivity) {
    val stack = remember { mutableStateListOf("sessions") }
    val route = stack.last()
    val push: (String) -> Unit = { stack.add(it) }
    val pop: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) else activity.finish() }
    BackHandler(enabled = stack.size > 1) { pop() }

    val parts = route.split("/")
    val sessionId = parts.getOrNull(1)?.toLongOrNull() ?: 0L
    when (parts[0]) {
        "sessions" -> SessionsScreen(onOpen = { push("menu/$it") }, onSettings = { push("settings") })
        "settings" -> SettingsScreen(onBack = pop)
        "menu" -> SessionMenuScreen(sessionId, onNavigate = { push("$it/$sessionId") }, onBack = pop)
        "studio" -> CameraGate(activity, onBack = pop) { StudioRoute(activity, sessionId, onLibrary = { push("library/$sessionId") }, onBack = pop) }
        "library" -> LibraryScreen(sessionId, onBack = pop)
        "transitions" -> TransitionsScreen(sessionId, onBack = pop)
        "preview" -> SequencePreviewScreen(sessionId, onBack = pop)
        "export" -> ExportScreen(sessionId, onBack = pop)
    }
}

@Composable
private fun StudioRoute(activity: ComponentActivity, sessionId: Long, onLibrary: () -> Unit, onBack: () -> Unit) {
    val vm: StudioViewModel = viewModel(key = "studio-$sessionId")
    LaunchedEffect(sessionId) { vm.load(sessionId) }
    // Lock the screen to the session's orientation while filming.
    var portrait by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(sessionId) { portrait = AppDatabase.get(activity).sessions().get(sessionId)?.portrait }
    DisposableEffect(portrait) {
        portrait?.let {
            activity.requestedOrientation = if (it) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        onDispose { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    // Bind the camera only once the display is in the session's orientation, so the
    // use cases get the right target rotation.
    val orientation = LocalConfiguration.current.orientation
    val p = portrait
    if (p != null && (orientation == Configuration.ORIENTATION_PORTRAIT) == p) StudioScreen(vm, onLibrary, onBack)
}

@Composable
private fun CameraGate(activity: ComponentActivity, onBack: () -> Unit, content: @Composable () -> Unit) {
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    if (granted) content() else ScreenScaffold("กล้อง", onBack) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("แอปต้องใช้สิทธิ์กล้องเพื่อถ่ายคลิป")
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("อนุญาตกล้อง") }
        }
    }
}
