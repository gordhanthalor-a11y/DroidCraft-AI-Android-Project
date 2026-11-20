package com.droidcraft.app.studio.ui

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
import com.droidcraft.app.studio.StudioClient
import kotlinx.coroutines.launch
import java.io.File

private val Cyan = Color(0xFF56EFF7)
private val Slate = Color(0xFFA3BAC6)
private val Light = Color(0xFFE7F5F8)
private val Green = Color(0xFF48E1C2)
private val Red = Color(0xFFFF7183)

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
        Text("Source link दो → 9:16 vertical crop और original creator credit overlay बनेगा", fontSize = 12.sp, color = Slate)

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
                scope.launch {
                    runCatching {
                        val job = client.clipStart(url.trim(), clipSec, "vertical", credit, parts)
                        val done = client.clipWait(job.id)
                        clipList = done.clips
                        progress = 100
                        status = "${done.clips.size} clips तैयार ✅"
                    }.onFailure { status = "Fail: ${it.message}" }
                    busy = false
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
