package com.droidcraft.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel
import com.droidcraft.app.jarvis.JarvisViewModel
import com.droidcraft.app.jarvis.voice.VoiceMode
import com.droidcraft.app.jarvis.voice.VoiceSettings
import com.droidcraft.app.model.LiveVoiceMode
import com.droidcraft.app.studio.StudioSettings
import com.droidcraft.app.viewmodel.YouTubeAutomationViewModel

@Composable
fun SettingsScreen(
    viewModel: YouTubeAutomationViewModel,
    onOpenLiveTalk: () -> Unit
) {
    val context = LocalContext.current
    val jarvisViewModel: JarvisViewModel = composeViewModel()
    val handsFree by jarvisViewModel.handsFree.collectAsState()
    val selectedVoice by viewModel.liveVoiceMode.collectAsState()
    val hasGeminiKey by viewModel.hasGeminiKey.collectAsState()
    val hasElevenLabsKey by viewModel.hasElevenLabsKey.collectAsState()
    val modelOrder by viewModel.geminiModelOrder.collectAsState()
    val isRefreshingModels by viewModel.refreshingGeminiModels.collectAsState()
    val modelSettingsMessage by viewModel.geminiModelSettingsMessage.collectAsState()
    val testingConnection by viewModel.testingGeminiConnection.collectAsState()
    val connectionStatus by viewModel.geminiConnectionStatus.collectAsState()
    val pingMs by viewModel.geminiPingMs.collectAsState()
    var modelOrderText by remember { mutableStateOf(modelOrder.joinToString(System.lineSeparator())) }
    var modelOrderError by remember { mutableStateOf("") }
    var studioServerUrl by remember { mutableStateOf(StudioSettings.serverUrl(context)) }
    var studioServerMessage by remember { mutableStateOf("") }
    var studioServerSaved by remember { mutableStateOf(false) }
    var wakeListenerMessage by remember { mutableStateOf("") }
    var elevenVoiceId by remember { mutableStateOf(VoiceSettings.elevenVoiceId(context)) }
    var elevenVoiceIdMessage by remember { mutableStateOf("") }
    var jarvisVoiceMode by remember { mutableStateOf(VoiceSettings.mode(context)) }
    var voiceModeMenuExpanded by remember { mutableStateOf(false) }
    var geminiVoice by remember { mutableStateOf(VoiceSettings.geminiVoice(context)) }
    var geminiVoiceMenuExpanded by remember { mutableStateOf(false) }
    var smartVoxCpmEnabled by remember { mutableStateOf(VoiceSettings.emotionVoiceEnabled(context)) }
    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            jarvisViewModel.setHandsFree(true)
            wakeListenerMessage = "Offline wake listening is starting. First use downloads the small models once."
        } else {
            jarvisViewModel.setHandsFree(false)
            wakeListenerMessage = "Allow microphone access before enabling offline wake listening."
        }
    }

    LaunchedEffect(modelOrder) {
        modelOrderText = modelOrder.joinToString(System.lineSeparator())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("DROIDCRAFT · SYSTEM CONFIGURATION", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Voice, model access, and connected services.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        ConnectionStatusCard(
            hasGeminiKey = hasGeminiKey,
            testing = testingConnection,
            status = connectionStatus,
            pingMs = pingMs,
            onTest = viewModel::testGeminiConnection
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsGroupHeader(
                    icon = Icons.Default.Mic,
                    title = "Offline wake phrase",
                    subtitle = "Vosk listens locally for ‘Hey Jarvis’ in Hindi and English."
                )
                Text(
                    "The first start downloads the compact Hindi and Indian English models once (about 82 MB). Wake-word recognition then runs on-device; only the wake phrase is checked.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (handsFree) "Wake listener enabled" else "Wake listener off",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (handsFree) "An ongoing notification shows when it is listening." else "Allow microphone access to turn it on.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = handsFree,
                        onCheckedChange = { enabled ->
                            if (!enabled) {
                                jarvisViewModel.setHandsFree(false)
                                wakeListenerMessage = "Offline wake listening stopped."
                            } else if (ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                jarvisViewModel.setHandsFree(true)
                                wakeListenerMessage = "Offline wake listening is starting. First use downloads the small models once."
                            } else {
                                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    )
                }
                if (wakeListenerMessage.isNotBlank()) {
                    Text(wakeListenerMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsGroupHeader(
                    icon = Icons.Default.GraphicEq,
                    title = "Live voice",
                    subtitle = "Choose the voice path used in Live Talk."
                )
                LiveVoiceMode.values().forEach { mode ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { viewModel.setLiveVoiceMode(mode) },
                        shape = RoundedCornerShape(15.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selectedVoice == mode) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedVoice == mode,
                                onClick = { viewModel.setLiveVoiceMode(mode) }
                            )
                            Column(modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
                                Text(mode.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    mode.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (mode == LiveVoiceMode.ELEVENLABS) {
                                    Text(
                                        "${VoiceSettings.voiceName(elevenVoiceId)} · selected voice · Gemini remains the fallback",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    "Gemini Live handles listening and replies. With ElevenLabs selected, reply text is spoken using the voice ID saved below. " +
                        "If ElevenLabs credits or service run out, Jarvis switches to Gemini TTS's free preset voice in the same conversation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = elevenVoiceId,
                    onValueChange = { elevenVoiceId = it },
                    label = { Text("ElevenLabs voice ID") },
                    placeholder = { Text(VoiceSettings.DEFAULT_ELEVEN_VOICE) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Default: Bunty. Your saved ID is used by the next Live Talk session.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        runCatching { VoiceSettings.setElevenVoiceId(context, elevenVoiceId) }
                            .onSuccess {
                                elevenVoiceId = VoiceSettings.elevenVoiceId(context)
                                elevenVoiceIdMessage = "ElevenLabs voice ID saved."
                            }
                            .onFailure { error ->
                                elevenVoiceIdMessage = error.message ?: "Could not save this voice ID."
                            }
                    },
                    enabled = elevenVoiceId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save voice ID")
                }
                if (elevenVoiceIdMessage.isNotBlank()) {
                    Text(elevenVoiceIdMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                OutlinedButton(onClick = onOpenLiveTalk, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open Live Talk")
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsGroupHeader(
                    icon = Icons.Default.GraphicEq,
                    title = "Jarvis reply voice",
                    subtitle = "Choose the voice used for Jarvis chat replies."
                )
                Box {
                    OutlinedButton(
                        onClick = { voiceModeMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("${voiceModeTitle(jarvisVoiceMode)} · change")
                    }
                    DropdownMenu(
                        expanded = voiceModeMenuExpanded,
                        onDismissRequest = { voiceModeMenuExpanded = false }
                    ) {
                        VoiceMode.values().forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(voiceModeTitle(mode)) },
                                onClick = {
                                    VoiceSettings.setMode(context, mode)
                                    jarvisVoiceMode = mode
                                    voiceModeMenuExpanded = false
                                }
                            )
                        }
                    }
                }
                Text(
                    voiceModeDescription(jarvisVoiceMode),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Use VoxCPM2 for emotional replies", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "In Smart mode, this adds expressive delivery for non-neutral emotion when the server is configured.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = smartVoxCpmEnabled,
                        enabled = jarvisVoiceMode == VoiceMode.SMART,
                        onCheckedChange = { enabled ->
                            VoiceSettings.setEmotionVoiceEnabled(context, enabled)
                            smartVoxCpmEnabled = enabled
                        }
                    )
                }
                Box {
                    OutlinedButton(
                        onClick = { geminiVoiceMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Gemini preset voice · $geminiVoice")
                    }
                    DropdownMenu(
                        expanded = geminiVoiceMenuExpanded,
                        onDismissRequest = { geminiVoiceMenuExpanded = false }
                    ) {
                        VoiceSettings.GEMINI_VOICES.forEach { voice ->
                            DropdownMenuItem(
                                text = { Text(voice) },
                                onClick = {
                                    VoiceSettings.setGeminiVoice(context, voice)
                                    geminiVoice = voice
                                    geminiVoiceMenuExpanded = false
                                }
                            )
                        }
                    }
                }
                Text(
                    "This preset is used by Gemini TTS in Jarvis chat and as the Live Talk fallback.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        ProviderKeyCard(
            title = "Gemini API key",
            description = "Required for chat, Gemini Live conversations, and Gemini TTS speech. Get a key from Google AI Studio.",
            isSaved = hasGeminiKey,
            placeholder = "Paste Gemini API key",
            onSave = viewModel::saveGeminiKey,
            onRemove = viewModel::clearGeminiKey
        )

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsGroupHeader(
                    icon = Icons.Default.Settings,
                    title = "Gemini text models",
                    subtitle = "Ordered fallback choices used for Chat replies."
                )
                Text(
                    "Models are tried from top to bottom. A 503 is retried once after a short pause; 429 and 404 move to the next model automatically. Only after every listed model fails will Chat show an error. Use one model ID per line.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("PREFERRED FIRST MODEL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                modelOrder.forEachIndexed { index, modelId ->
                    ModelChoiceCard(
                        modelId = modelId,
                        description = modelDescription(modelId, index),
                        selected = modelOrder.firstOrNull() == modelId,
                        onClick = { viewModel.selectPrimaryGeminiModel(modelId) }
                    )
                }
                OutlinedTextField(
                    value = modelOrderText,
                    onValueChange = {
                        modelOrderText = it
                        modelOrderError = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                    label = { Text("Ordered Gemini model IDs") },
                    placeholder = { Text("gemini-3.1-flash-lite, gemini-flash-lite-latest, gemini-3.8-flash, gemini-3-flash-preview") }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            runCatching { viewModel.saveGeminiModelOrder(modelOrderText) }
                                .onSuccess { saved ->
                                    modelOrderText = saved.joinToString(System.lineSeparator())
                                    modelOrderError = ""
                                }
                                .onFailure { modelOrderError = it.message ?: "Could not save model order." }
                        }
                    ) { Text("Save order") }
                    OutlinedButton(
                        onClick = viewModel::refreshAvailableGeminiModels,
                        enabled = hasGeminiKey && !isRefreshingModels
                    ) {
                        Text(if (isRefreshingModels) "Checking…" else "Refresh from Google")
                    }
                }
                TextButton(
                    onClick = {
                        val defaults = viewModel.resetGeminiModelOrder()
                        modelOrderText = defaults.joinToString(System.lineSeparator())
                        modelOrderError = ""
                    }
                ) { Text("Restore defaults") }
                val modelMessage = modelOrderError.ifBlank { modelSettingsMessage }
                if (modelMessage.isNotBlank()) {
                    Text(
                        modelMessage,
                        color = if (modelOrderError.isNotBlank() || modelMessage.startsWith("Could not") || modelMessage.startsWith("Add a")) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        ProviderKeyCard(
            title = "ElevenLabs API key",
            description = "Required only when ElevenLabs (my voice) is selected. The key is stored encrypted on this device and sent to ElevenLabs over HTTPS.",
            isSaved = hasElevenLabsKey,
            placeholder = "Paste ElevenLabs API key",
            onSave = viewModel::saveElevenLabsKey,
            onRemove = viewModel::clearElevenLabsKey
        )

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsGroupHeader(
                    icon = Icons.Default.Cloud,
                    title = "Creator Studio server",
                    subtitle = "Endpoint for voice, dubbing, clipper, and QC tools."
                )
                Text(
                    "Voice Studio, Dubbing, Clipper, and advanced QC need the deployed Python service. Enter its HTTPS base URL; localhost and emulator addresses will not work on a physical phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = studioServerUrl,
                    onValueChange = {
                        studioServerUrl = it
                        studioServerMessage = ""
                        studioServerSaved = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Base URL") },
                    placeholder = { Text("https://your-studio-server.example") }
                )
                Button(
                    onClick = {
                        if (studioServerUrl.isBlank()) {
                            StudioSettings.setServerUrl(context, "")
                            studioServerMessage = "Creator Studio server URL cleared."
                            studioServerSaved = true
                        } else {
                            runCatching { StudioSettings.validateServerUrl(studioServerUrl) }
                                .onSuccess { cleanedUrl ->
                                    StudioSettings.setServerUrl(context, cleanedUrl)
                                    studioServerUrl = cleanedUrl
                                    studioServerMessage = "Creator Studio server saved."
                                    studioServerSaved = true
                                }
                                .onFailure {
                                    studioServerMessage = it.message.orEmpty()
                                    studioServerSaved = false
                                }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save server URL") }
                if (studioServerMessage.isNotBlank()) {
                    Text(
                        studioServerMessage,
                        color = if (studioServerSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Text(
            "Voice choice changes apply to the next conversation. The active ElevenLabs choice is not replaced in Settings if a session falls back to Gemini.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
    }
}

private fun voiceModeTitle(mode: VoiceMode): String = when (mode) {
    VoiceMode.PHONE_FREE -> "Phone · offline"
    VoiceMode.GEMINI_FREE -> "Gemini TTS · free preset"
    VoiceMode.ELEVENLABS -> "ElevenLabs"
    VoiceMode.SMALLEST -> "Smallest AI"
    VoiceMode.HEYGEN -> "HeyGen"
    VoiceMode.SMART -> "Smart emotion routing"
}

private fun voiceModeDescription(mode: VoiceMode): String = when (mode) {
    VoiceMode.PHONE_FREE -> "Use the installed Android voice; works offline."
    VoiceMode.GEMINI_FREE -> "Use the selected Gemini preset voice. Requires your Gemini API key."
    VoiceMode.ELEVENLABS -> "Use ElevenLabs for every reply; requires a configured Studio server and key."
    VoiceMode.SMALLEST -> "Use Smallest AI through your configured Studio server."
    VoiceMode.HEYGEN -> "Use HeyGen through your configured Studio server."
    VoiceMode.SMART -> "Strong emotion prefers ElevenLabs when the server and key are configured; other emotions can use VoxCPM2 on that server. Neutral and humour use Gemini TTS."
}

private fun ModelChoiceCard(
    modelId: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column(Modifier.padding(start = 8.dp, top = 7.dp, bottom = 7.dp)) {
                Text(modelId, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun modelDescription(modelId: String, index: Int): String = when (modelId) {
    "gemini-3.1-flash-lite" -> "Recommended · fast, lightweight first choice"
    "gemini-flash-lite-latest" -> "Latest Flash Lite alias · automatic fallback"
    "gemini-3.8-flash" -> "Higher-capability Flash · fallback option"
    "gemini-3-flash-preview" -> "Preview model · final default fallback"
    else -> "Custom text model · fallback slot ${index + 1}"
}

@Composable
private fun ConnectionStatusCard(
    hasGeminiKey: Boolean,
    testing: Boolean,
    status: String,
    pingMs: Long?,
    onTest: () -> Unit
) {
    val isConnected = status.startsWith("Connected", ignoreCase = true)
    val isError = status.startsWith("Connection failed", ignoreCase = true)
    val statusColor = when {
        isConnected -> MaterialTheme.colorScheme.secondary
        isError -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsGroupHeader(
                icon = Icons.Default.Cloud,
                title = "Gemini connection",
                subtitle = "Test model access and measure a live response."
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(9.dp), color = statusColor.copy(alpha = .13f)) {
                    Icon(Icons.Default.Speed, contentDescription = null, tint = statusColor, modifier = Modifier.padding(8.dp))
                }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(
                        if (isConnected) "Connection ready" else if (isError) "Needs attention" else "Connection status",
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(status, style = MaterialTheme.typography.bodySmall, color = statusColor)
                }
                when {
                    testing -> CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                    pingMs != null -> Text("PING · ${pingMs} ms", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
            Button(onClick = onTest, enabled = hasGeminiKey && !testing, modifier = Modifier.fillMaxWidth()) {
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (testing) "Testing connection…" else "Test Connection")
            }
            Text(
                if (hasGeminiKey) "Sends a brief test prompt using your configured Gemini fallback order; no API key is shown."
                else "Save a Gemini API key below to enable the connection test.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsGroupHeader(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(11.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(9.dp))
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProviderKeyCard(
    title: String,
    description: String,
    isSaved: Boolean,
    placeholder: String,
    onSave: (String) -> Unit,
    onRemove: () -> Unit
) {
    // Provider credentials intentionally use remember (not rememberSaveable), so they are not
    // copied into Android's saved-instance-state bundle while being entered.
    var keyInput by remember(title) { mutableStateOf("") }
    var showInput by remember { mutableStateOf(false) }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingsGroupHeader(
                    icon = if (title.contains("ElevenLabs", ignoreCase = true)) Icons.Default.GraphicEq else Icons.Default.Lock,
                    title = title,
                    subtitle = description,
                    modifier = Modifier.weight(1f)
                )
                if (isSaved) {
                    Text("SAVED", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
            if (isSaved) {
                Text(
                    "A key is saved securely. It is hidden and will not be shown again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(if (isSaved) "Replace saved key" else "API key") },
                placeholder = { Text(placeholder) },
                visualTransformation = if (showInput) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showInput = !showInput }) {
                        Icon(
                            imageVector = if (showInput) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showInput) "Hide key" else "Show key"
                        )
                    }
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        if (keyInput.isNotBlank()) {
                            onSave(keyInput)
                            keyInput = ""
                            showInput = false
                        }
                    },
                    enabled = keyInput.isNotBlank()
                ) {
                    Text(if (isSaved) "Update key" else "Save key")
                }
                if (isSaved) {
                    TextButton(onClick = {
                        onRemove()
                        keyInput = ""
                    }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null)
                        Text("  Remove")
                    }
                }
            }
        }
    }
}
