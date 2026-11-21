package com.droidcraft.app.studio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidcraft.app.activity.HomeActivityTracker
import com.droidcraft.app.studio.StudioClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

private val Cyan = Color(0xFF56EFF7)
private val Slate = Color(0xFFA3BAC6)
private val Light = Color(0xFFE7F5F8)
private val Green = Color(0xFF48E1C2)
private val Red = Color(0xFFFF7183)

private enum class ClipperLayout(val apiValue: String, val title: String, val detail: String) {
    FACECAM_TOP_GAMEPLAY_BOTTOM(
        apiValue = "facecam_top_gameplay_bottom",
        title = "Facecam on top · gameplay below",
        detail = "For a side-by-side source with facecam on the left and gameplay on the right."
    ),
    VERTICAL_CROP(
        apiValue = "vertical",
        title = "Vertical center crop",
        detail = "Crop the middle of one source into a 9:16 clip."
    )
}

/**
 * B4 — Clipper: source link → server crops 9:16 vertical clips and overlays the creator credit.
 * Long streams can be processed in several evenly spaced parts.
 */
@Composable
fun ClipperScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { StudioClient(context) }

    var url by remember { mutableStateOf("") }
    var clipSec by remember { mutableIntStateOf(60) }
    var selectedLayout by remember { mutableStateOf(ClipperLayout.FACECAM_TOP_GAMEPLAY_BOTTOM) }
    var parts by remember { mutableIntStateOf(1) }
    var credit by remember { mutableStateOf("@GTMarwadiVlogs") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var clipList by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var progress by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Clip Kaato", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Cyan)
        Text("Source link दो → 9:16 vertical clip और original creator credit overlay बनेगा", fontSize = 12.sp, color = Slate)

        Text("Clip layout", fontSize = 13.sp, color = Light, fontWeight = FontWeight.SemiBold)
        ClipperLayout.values().forEach { option ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { selectedLayout = option },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (selectedLayout == option) Color(0xFF163246) else Color(0xFF0E2030)
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedLayout == option,
                        onClick = { selectedLayout = option }
                    )
                    Column(Modifier.padding(start = 6.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
                        Text(option.title, fontSize = 13.sp, color = Light, fontWeight = FontWeight.SemiBold)
                        Text(option.detail, fontSize = 11.sp, color = Slate)
                    }
                }
            }
        }

        OutlinedTextField(
            value = url, onValueChange = { url = it },
            label = { Text("Stream / video link") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )

        Text("हर clip: $clipSec सेकंड", fontSize = 13.sp, color = Light)
        Slider(
            value = clipSec.toFloat(),
            onValueChange = { clipSec = it.toInt() },
            valueRange = 30f..300f, steps = 8
        )

        Text("Kitne parts में काटें (long stream के लिए): $parts", fontSize = 13.sp, color = Light)
        Slider(
            value = parts.toFloat(),
            onValueChange = { parts = it.toInt() },
            valueRange = 1f..10f, steps = 8
        )

        OutlinedTextField(
            value = credit, onValueChange = { credit = it },
            label = { Text("Credit chip handle") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Text("Credits compulsory हैं — original creator का handle हमेशा जुड़ेगा।", fontSize = 11.sp, color = Slate)

        Button(
            onClick = {
                if (url.isBlank()) { status = "पहले link डालो"; return@Button }
                if (credit.isBlank()) { status = "Original creator का credit handle डालें"; return@Button }
                busy = true; status = "Server download + cutting शुरू…"
                val activityId = "clip-${UUID.randomUUID()}"
                val sourceName = url.trim().substringAfter("://", url.trim()).take(48)
                HomeActivityTracker.begin(
                    id = activityId,
                    kind = "Clipper",
                    title = "Clip job · $sourceName",
                    step = "Submitting source link",
                    progress = null
                )
                scope.launch {
                    try {
                        val job = client.clipStart(url.trim(), clipSec, selectedLayout.apiValue, credit, parts)
                        HomeActivityTracker.update(
                            activityId,
                            clipActivityStep(job.status),
                            progress = job.progress
                        )
                        val done = client.clipWait(job.id) { serverStatus, serverProgress, error ->
                            if (serverStatus == "failed") {
                                HomeActivityTracker.fail(activityId, error ?: "Clip server reported a failure.")
                            } else {
                                HomeActivityTracker.update(
                                    activityId,
                                    clipActivityStep(serverStatus),
                                    progress = serverProgress
                                )
                            }
                        }
                        clipList = done.clips
                        progress = done.progress ?: 100
                        status = "${done.clips.size} clips तैयार ✅"
                        HomeActivityTracker.complete(activityId, "${done.clips.size} clips ready")
                    } catch (cancelled: CancellationException) {
                        HomeActivityTracker.cancel(activityId, "Clip generation was cancelled")
                        throw cancelled
                    } catch (error: Exception) {
                        val detail = error.message ?: "Clip generation failed without a server detail."
                        status = "Fail: $detail"
                        HomeActivityTracker.fail(activityId, detail)
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy, modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.ContentCut, contentDescription = null, tint = Cyan)
            Spacer(Modifier.width(8.dp))
            Text("Clips काटो")
        }

        if (status.isNotBlank()) {
            Text(status, fontSize = 12.sp, color = if (status.contains("Fail")) Red else Green)
        }
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        clipList.forEach { (index, path) ->
            var downloaded by remember { mutableStateOf<File?>(null) }
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF0E2030)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Clip ${index + 1}", color = Light, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(
                            downloaded?.name ?: "server पर तैयार",
                            color = Slate, fontSize = 11.sp
                        )
                    }
                    Row {
                        TextButton(onClick = {
                            scope.launch {
                                runCatching {
                                    client.downloadVideo(client.serverUrl(path), "clip_${index}.mp4")
                                }.onSuccess {
                                    downloaded = it
                                    status = "Clip ${index + 1} downloaded ✅"
                                }.onFailure {
                                    status = "Fail: ${it.message}"
                                }
                            }
                        }) { Text("डाउनलोड", color = Cyan, fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}

private fun clipActivityStep(status: String): String = when (status.lowercase()) {
    "downloading" -> "Downloading source video"
    "cutting" -> "Rendering vertical clips"
    "done" -> "Clip render completed"
    "failed" -> "Clip job failed on the server"
    else -> "Clip server status · $status"
}
