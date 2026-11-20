package com.droidcraft.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import com.droidcraft.app.model.LiveVoiceMode
import com.droidcraft.app.studio.StudioSettings
import com.droidcraft.app.viewmodel.YouTubeAutomationViewModel

@Composable
fun SettingsScreen(
    viewModel: YouTubeAutomationViewModel,
    onOpenLiveTalk: () -> Unit
) {
    val context = LocalContext.current
    val selectedVoice by viewModel.liveVoiceMode.collectAsState()
    val hasGeminiKey by viewModel.hasGeminiKey.collectAsState()
    val hasElevenLabsKey by viewModel.hasElevenLabsKey.collectAsState()
    val modelOrder by viewModel.geminiModelOrder.collectAsState()
    val isRefreshingModels by viewModel.refreshingGeminiModels.collectAsState()
    val modelSettingsMessage by viewModel.geminiModelSettingsMessage.collectAsState()
    var modelOrderText by remember { mutableStateOf(modelOrder.joinToString(System.lineSeparator())) }
    var modelOrderError by remember { mutableStateOf("") }
    var studioServerUrl by remember { mutableStateOf(StudioSettings.serverUrl(context)) }
    var studioServerMessage by remember { mutableStateOf("") }
    var studioServerSaved by remember { mutableStateOf(false) }

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
        Column {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Manage Jarvis keys and live conversation voice.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Live Voice", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Choose how Jarvis sounds during Live Talk.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LiveVoiceMode.values().forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setLiveVoiceMode(mode) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedVoice == mode,
                            onClick = { viewModel.setLiveVoiceMode(mode) }
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(mode.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                mode.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (mode == LiveVoiceMode.ELEVENLABS) {
                                Text(
                                    "Bunty · voice ID 7b9mYhmnp0y2qSH1FnBL",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                Text(
                    "Gemini Live handles listening and replies. With ElevenLabs selected, reply text is sent to ElevenLabs to speak in Bunty's expressive voice. " +
                        "If ElevenLabs credits or service run out, Jarvis switches to Gemini's free preset voice in the same conversation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = onOpenLiveTalk, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open Live Talk")
                }
            }
        }

        ProviderKeyCard(
            title = "Gemini API key",
            description = "Required for chat and Gemini Live conversations. Get a key from Google AI Studio.",
            isSaved = hasGeminiKey,
            placeholder = "Paste Gemini API key",
            onSave = viewModel::saveGeminiKey,
            onRemove = viewModel::clearGeminiKey
        )

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Gemini text model fallback order", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Models are tried from top to bottom. A 503 is retried once after a short pause; 429 and 404 move to the next model automatically. Only after every listed model fails will Chat show an error. Use one model ID per line.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                    placeholder = { Text("gemini-3.8-flash, gemini-4-hydrogen") }
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
                Text("Creator Studio server", style = MaterialTheme.typography.titleMedium)
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
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (isSaved) {
                    Text("Saved", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
