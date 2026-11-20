package com.droidcraft.app.studio.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidcraft.app.jarvis.voice.VoiceSettings
import com.droidcraft.app.studio.StudioSettings
import com.droidcraft.app.studio.tts.Mp3Player
import com.droidcraft.app.studio.tts.TtsClient
import com.droidcraft.app.studio.tts.TtsRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

private val Cyan = Color(0xFF7DF9FF)
private val Slate = Color(0xFF94A3B8)
private val Light = Color(0xFFE2E8F0)
private val Green = Color(0xFF4ADE80)
private val Red = Color(0xFFF87171)
private val Dark = Color(0xFF0B1220)

/**
 * B1 — Voice Studio: Hindi script → server TTS → MP3 (play/save/share)।
 * Sentence-by-sentence generation with gaps — Gowardhan की quality recipe।
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceStudioScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { TtsClient(context) }
    val player = remember { Mp3Player(context) }

    var script by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf(StudioSettings.voiceStudioTtsProvider(context)) }
    var voice by remember { mutableStateOf(StudioSettings.voiceStudioTtsVoice(context)) }
    var speed by remember { mutableFloatStateOf(StudioSettings.ttsSpeed(context)) }
    var sentenceMode by remember { mutableStateOf(StudioSettings.sentenceMode(context)) }
    var voices by remember { mutableStateOf<List<TtsClient.Voice>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var audioFile by remember { mutableStateOf<File?>(null) }
    var status by remember { mutableStateOf("") }

    var showSettings by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf(StudioSettings.serverUrl(context)) }
    var smallestKey by remember { mutableStateOf("") }
    var heygenKey by remember { mutableStateOf("") }
    var hasSmallestKey by remember { mutableStateOf(StudioSettings.smallestKey(context).isNotBlank()) }
    var hasHeygenKey by remember { mutableStateOf(StudioSettings.heygenKey(context).isNotBlank()) }

    LaunchedEffect(provider) {
        voices = client.voices(provider)
        if (voices.isNotEmpty() && voices.none { it.id == voice }) {
            voice = voices.first().id
            StudioSettings.setVoiceStudioTtsVoice(context, voice)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Awaaz Banao", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Cyan)
                Text("Voice Studio — Hindi script से AI awaaz", fontSize = 12.sp, color = Slate)
            }
            IconButton(onClick = { showSettings = !showSettings }) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Cyan)
            }
        }

        if (showSettings) {
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF111A2E)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = serverUrl, onValueChange = { serverUrl = it },
                        label = { Text("Server URL") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = smallestKey, onValueChange = { smallestKey = it },
                        label = { Text("Smallest AI API key") },
                        placeholder = { Text(if (hasSmallestKey) "Saved securely · enter to replace" else "Paste API key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = heygenKey, onValueChange = { heygenKey = it },
                        label = { Text("HeyGen API key") },
                        placeholder = { Text(if (hasHeygenKey) "Saved securely · enter to replace" else "Paste API key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "ElevenLabs uses the secure key from Jarvis voice settings or a key configured on your server.",
                        fontSize = 11.sp,
                        color = Slate
                    )
                    Row {
                        TextButton(onClick = {
                            runCatching {
                                val cleanedServerUrl = if (serverUrl.isBlank()) "" else StudioSettings.validateServerUrl(serverUrl)
                                StudioSettings.setServerUrl(context, cleanedServerUrl)
                                serverUrl = cleanedServerUrl
                                if (smallestKey.isNotBlank()) {
                                    StudioSettings.setSmallestKey(context, smallestKey)
                                    hasSmallestKey = true
                                }
                                if (heygenKey.isNotBlank()) {
                                    StudioSettings.setHeygenKey(context, heygenKey)
                                    hasHeygenKey = true
                                }
                                smallestKey = ""
                                heygenKey = ""
                            }.onSuccess {
                                status = "Settings saved securely."
                            }.onFailure {
                                status = "Fail: ${it.message}"
                            }
                        }) { Text("Save करो", color = Cyan) }
                        TextButton(onClick = {
                            StudioSettings.setSmallestKey(context, "")
                            StudioSettings.setHeygenKey(context, "")
                            hasSmallestKey = false
                            hasHeygenKey = false
                            smallestKey = ""
                            heygenKey = ""
                            status = "Saved provider keys cleared."
                        }) { Text("Clear keys", color = Red) }
                        TextButton(onClick = { showSettings = false }) { Text("बंद", color = Slate) }
                    }
                }
            }
        }

        // provider picker
        Text("Provider चुनो", fontSize = 13.sp, color = Light)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = provider == "smallest",
                onClick = {
                    provider = "smallest"
                    StudioSettings.setVoiceStudioTtsProvider(context, "smallest")
                },
                label = { Text("Smallest AI") }
            )
            FilterChip(
                selected = provider == "heygen",
                onClick = {
                    provider = "heygen"
                    StudioSettings.setVoiceStudioTtsProvider(context, "heygen")
                },
                label = { Text("HeyGen") }
            )
            FilterChip(
                selected = provider == "elevenlabs",
                onClick = {
                    provider = "elevenlabs"
                    StudioSettings.setVoiceStudioTtsProvider(context, "elevenlabs")
                },
                label = { Text("ElevenLabs") }
            )
        }
        if (provider == "elevenlabs") {
            Text(
                "If this voice is blocked on the free plan, Voice Studio will create a free phone-voice WAV instead.",
                fontSize = 11.sp,
                color = Slate
            )
        }

        // voice picker
        if (voices.isNotEmpty()) {
            Text("Awaaz चुनो", fontSize = 13.sp, color = Light)
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = voices.find { it.id == voice }?.name ?: voice,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Voice") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    voices.forEach { v ->
                        DropdownMenuItem(
                            text = { Text(v.name) },
                            onClick = {
                                voice = v.id
                                StudioSettings.setVoiceStudioTtsVoice(context, v.id)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }

        // script
        OutlinedTextField(
            value = script,
            onValueChange = { script = it },
            label = { Text("Hindi script यहाँ लिखो (Devanagari)") },
            placeholder = { Text("दोस्तों, आज का वीडियो बहुत खास है…") },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 140.dp),
            minLines = 5
        )

        // speed
        Text("स्पीड: ${"%.1f".format(speed)}x", fontSize = 13.sp, color = Light)
        Slider(
            value = speed,
            onValueChange = {
                speed = it
                StudioSettings.setTtsSpeed(context, it)
            },
            valueRange = 0.6f..1.6f,
            steps = 9
        )

        // sentence mode
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = sentenceMode,
                onCheckedChange = {
                    sentenceMode = it
                    StudioSettings.setSentenceMode(context, it)
                }
            )
            Spacer(Modifier.width(8.dp))
            Text("Sentence-by-sentence (छोटे gaps के साथ)", fontSize = 12.sp, color = Light)
        }

        // generate
        Button(
            onClick = {
                if (script.isBlank()) {
                    status = "पहले script लिखो"
                    return@Button
                }
                busy = true
                status = "Awaaz बन रही है… server से audio आ रहा है"
                scope.launch {
                    try {
                        val bytes = client.synthesize(
                            text = script,
                            provider = provider,
                            voice = voice,
                            speed = speed,
                            sentenceMode = sentenceMode,
                            elevenKey = if (provider == "elevenlabs") VoiceSettings.elevenKey(context) else ""
                        )
                        val f = client.saveMp3(bytes)
                        audioFile = f
                        status = "तैयार ✅ ${f.name} (${bytes.size / 1024} KB)"
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: TtsRequestException) {
                        if (provider == "elevenlabs" && error.isPaymentOrPlanError) {
                            try {
                                val fallbackFile = client.synthesizePhoneWav(script, speed)
                                audioFile = fallbackFile
                                status = "ElevenLabs free plan par ye awaaz nahi chalegi — phone ki free voice se audio तैयार ✅ ${fallbackFile.name}"
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (fallbackError: Exception) {
                                audioFile = null
                                status = "ElevenLabs free plan par ye awaaz nahi chalegi. Phone ki free voice fallback भी नहीं बन पाई: ${fallbackError.message ?: "unknown error"}"
                            }
                        } else {
                            status = "Fail: ${error.message}"
                        }
                    } catch (error: Exception) {
                        status = "Fail: ${error.message}"
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Cyan)
            Spacer(Modifier.width(8.dp))
            Text(if (busy) "बन रही है…" else "Awaaz Banao")
        }

        if (status.isNotBlank()) {
            Text(
                status,
                fontSize = 12.sp,
                color = if (status.contains("Fail") || status.contains("ElevenLabs free plan")) Red else Green
            )
        }

        // playback
        audioFile?.let { f ->
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF111A2E)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(f.name, fontSize = 12.sp, color = Light, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            if (player.isPlaying) player.stop()
                            else player.play(f)
                        }) {
                            Icon(
                                if (player.isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = null
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (player.isPlaying) "रोको" else "सुनो")
                        }
                        OutlinedButton(onClick = { player.share(f) }) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Share")
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }
}
