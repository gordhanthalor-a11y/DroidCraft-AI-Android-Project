package com.droidcraft.app.studio.ui

import android.content.Intent
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
import com.droidcraft.app.studio.StudioSettings
import com.droidcraft.app.studio.tts.TtsClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

private val Cyan = Color(0xFF56EFF7)
private val Slate = Color(0xFFA3BAC6)
private val Light = Color(0xFFE7F5F8)
private val Green = Color(0xFF48E1C2)
private val Red = Color(0xFFFF7183)

/**
 * B3 — Dubbing Studio: link → transcript (server) → Hindi script edit → voice → render → preview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DubbingScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { StudioClient(context) }
    val tts = remember { TtsClient(context) }

    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var transcript by remember { mutableStateOf("") }
    var script by remember { mutableStateOf("") }
    var jobId by remember { mutableStateOf("") }
    var voices by remember { mutableStateOf<List<TtsClient.Voice>>(emptyList()) }
    var voice by remember { mutableStateOf(StudioSettings.ttsVoice(context)) }
    var provider by remember { mutableStateOf(StudioSettings.ttsProvider(context)) }
    var speed by remember { mutableFloatStateOf(StudioSettings.ttsSpeed(context)) }
    var outFile by remember { mutableStateOf<File?>(null) }
    var step by remember { mutableIntStateOf(0) } // 0 link, 1 script, 2 done

    LaunchedEffect(provider) {
        voices = tts.voices(provider)
        if (voice.isBlank() && voices.isNotEmpty()) voice = voices.first().id
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Dubbing Studio", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Cyan)
        Text("Link दो → transcript आएगा → Hindi script बanao → server render करेगा", fontSize = 12.sp, color = Slate)

        OutlinedTextField(
            value = url, onValueChange = { url = it },
            label = { Text("YouTube / RedNote / Instagram link") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            enabled = step == 0
        )

        if (step == 0) {
            Button(
                onClick = {
                    if (url.isBlank()) {
                        status = "पहले link डालो"
                        return@Button
                    }
                    busy = true; status = "Server download + transcript शुरू…"
                    val activityId = "dubbing-prepare-${UUID.randomUUID()}"
                    val sourceName = url.trim().substringAfter("://", url.trim()).take(48)
                    HomeActivityTracker.begin(
                        id = activityId,
                        kind = "Dubbing",
                        title = "Dubbing prep · $sourceName",
                        step = "Submitting source for transcription",
                        progress = null
                    )
                    scope.launch {
                        try {
                            val job = client.dubPrepare(url.trim())
                            HomeActivityTracker.update(
                                activityId,
                                dubbingActivityStep(job.status),
                                progress = job.progress
                            )
                            jobId = job.id
                            val done = client.dubWait(job.id) { serverStatus, serverProgress, error ->
                                if (serverStatus == "failed") {
                                    HomeActivityTracker.fail(activityId, error ?: "Dubbing preparation failed on the server.")
                                } else {
                                    HomeActivityTracker.update(
                                        activityId,
                                        dubbingActivityStep(serverStatus),
                                        progress = serverProgress
                                    )
                                }
                            }
                            transcript = done.transcript
                            script = done.transcript
                            step = 1
                            status = "Transcript तैयार ✅"
                            HomeActivityTracker.complete(activityId, "Transcript ready for editing")
                        } catch (cancelled: CancellationException) {
                            HomeActivityTracker.cancel(activityId, "Dubbing preparation was cancelled")
                            throw cancelled
                        } catch (error: Exception) {
                            val detail = error.message ?: "Dubbing preparation failed without a server detail."
                            status = "Fail: $detail"
                            HomeActivityTracker.fail(activityId, detail)
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy, modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Download, contentDescription = null, tint = Cyan)
                Spacer(Modifier.width(8.dp))
                Text("Transcript लाओ")
            }
        }

        if (step >= 1) {
            Text("Hindi script (server के transcript से — edit करो)", fontSize = 13.sp, color = Light)
            OutlinedTextField(
                value = script,
                onValueChange = { script = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                minLines = 6,
                placeholder = { Text("यहाँ Devanagari में script लिखो…") }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = provider == "smallest", onClick = {
                    provider = "smallest"; StudioSettings.setTtsProvider(context, "smallest")
                }, label = { Text("Smallest AI") })
                FilterChip(selected = provider == "heygen", onClick = {
                    provider = "heygen"; StudioSettings.setTtsProvider(context, "heygen")
                }, label = { Text("HeyGen") })
            }

            if (voices.isNotEmpty()) {
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = voices.find { it.id == voice }?.name ?: voice,
                        onValueChange = {}, readOnly = true,
                        label = { Text("Voice") },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        voices.forEach { v ->
                            DropdownMenuItem(text = { Text(v.name) }, onClick = {
                                voice = v.id; expanded = false
                            })
                        }
                    }
                }
            }

            Text("स्पीड: ${"%.1f".format(speed)}x", fontSize = 13.sp, color = Light)
            Slider(value = speed, onValueChange = {
                speed = it; StudioSettings.setTtsSpeed(context, it)
            }, valueRange = 0.6f..1.6f, steps = 9)

            Button(
                onClick = {
                    busy = true; status = "Server render हो रहा है… (आवाज़ + video merge)"
                    val activityId = "dubbing-render-${UUID.randomUUID()}"
                    HomeActivityTracker.begin(
                        id = activityId,
                        kind = "Dubbing render",
                        title = "Dubbing render · ${jobId.take(24)}",
                        step = "Submitting script and voice for rendering",
                        progress = null
                    )
                    scope.launch {
                        try {
                            val job = client.dubRender(jobId, script, voice, provider, speed) { serverStatus, serverProgress, error ->
                                if (serverStatus == "failed") {
                                    HomeActivityTracker.fail(activityId, error ?: "Dubbing render failed on the server.")
                                } else {
                                    HomeActivityTracker.update(
                                        activityId,
                                        dubbingRenderActivityStep(serverStatus),
                                        progress = serverProgress
                                    )
                                }
                            }
                            HomeActivityTracker.update(activityId, "Downloading rendered video to this device")
                            val outputUrl = job.outputUrl
                                ?: throw IllegalStateException("Render finished without a download URL.")
                            val f = client.downloadVideo(outputUrl, "dubbed_$jobId.mp4")
                            outFile = f
                            step = 2
                            status = "Render तैयार ✅"
                            HomeActivityTracker.complete(activityId, "Dubbed video saved · ${f.name}")
                        } catch (cancelled: CancellationException) {
                            HomeActivityTracker.cancel(activityId, "Dubbing render was cancelled")
                            throw cancelled
                        } catch (error: Exception) {
                            val detail = error.message ?: "Dubbing render failed without a server detail."
                            status = "Fail: $detail"
                            HomeActivityTracker.fail(activityId, detail)
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && script.isNotBlank(), modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Cyan)
                Spacer(Modifier.width(8.dp))
                Text("Dub render करो")
            }
        }

        if (status.isNotBlank()) {
            Text(status, fontSize = 12.sp, color = if (status.contains("Fail")) Red else Green)
        }

        outFile?.let { f ->
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF0E2030)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(f.name, color = Light, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Text(
                        "Preview अभी video player में नहीं — file save हो गई, share करके देखो।",
                        color = Slate, fontSize = 11.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", f
                            )
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "video/mp4"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }, "Dubbed video share करो"
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }) { Text("Share") }
                    }
                }
            }
        }

        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}
