package com.droidcraft.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.droidcraft.app.jarvis.JarvisViewModel
import com.droidcraft.app.jarvis.a11y.JarvisAccessibilityService
import com.droidcraft.app.jarvis.overlay.JarvisBodyService
import com.droidcraft.app.jarvis.voice.VoiceSettings
import com.droidcraft.app.ai.EchoControlMode
import com.droidcraft.app.data.ActionDecisionStore
import com.droidcraft.app.data.AiProvider
import com.droidcraft.app.data.AiProviderSettings
import com.droidcraft.app.data.AiProviderTelemetry
import com.droidcraft.app.data.CapabilitySettings
import com.droidcraft.app.data.JarvisCapability
import com.droidcraft.app.ai.LiveVoiceSettings
import com.droidcraft.app.activity.HomeActivityStatus
import com.droidcraft.app.activity.HomeActivityTracker
import com.droidcraft.app.jarvis.notifications.NotificationStore
import com.droidcraft.app.ui.theme.OrbTheme
import com.droidcraft.app.ui.theme.OrbThemeSettings
import com.droidcraft.app.viewmodel.JarvisConversationViewModel

@Composable
fun SettingsScreen(
    conversationViewModel: JarvisConversationViewModel,
    jarvisViewModel: JarvisViewModel,
    energy: Float,
    phase: Float,
    onBack: () -> Unit,
    onOpenMemories: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val hasGeminiKey by conversationViewModel.hasGeminiKey.collectAsState()
    val connectionStatus by conversationViewModel.geminiConnectionStatus.collectAsState()
    val pingMs by conversationViewModel.geminiPingMs.collectAsState()
    val liveStatus by conversationViewModel.liveStatus.collectAsState()
    val liveNotice by conversationViewModel.liveNotice.collectAsState()
    val activityRecords by HomeActivityTracker.records.collectAsState()
    val actionDecisions by ActionDecisionStore.records.collectAsState()
    var latencySnapshot by remember { mutableStateOf(AiProviderTelemetry.all(context)) }
    val testingConnection by conversationViewModel.testingGeminiConnection.collectAsState()
    val modelOrder by conversationViewModel.geminiModelOrder.collectAsState()
    val modelMessage by conversationViewModel.geminiModelSettingsMessage.collectAsState()
    val refreshingModels by conversationViewModel.refreshingGeminiModels.collectAsState()
    val autoSpeak by jarvisViewModel.autoSpeak.collectAsState()
    val orbTheme by OrbThemeSettings.selected.collectAsState()
    var selectedProvider by remember { mutableStateOf(AiProviderSettings.activeProvider(context)) }
    val selectedProviderHasKey = AiProviderSettings.hasKey(context, selectedProvider)
    var providerKeyInput by remember { mutableStateOf("") }
    var providerFastModel by remember { mutableStateOf(AiProviderSettings.readModels(context, selectedProvider).first) }
    var providerStrongModel by remember { mutableStateOf(AiProviderSettings.readModels(context, selectedProvider).second) }
    var providerFastMenu by remember { mutableStateOf(false) }
    var providerStrongMenu by remember { mutableStateOf(false) }
    var liveModel by remember { mutableStateOf(LiveVoiceSettings.liveModel(context)) }
    var echoMode by remember { mutableStateOf(LiveVoiceSettings.echoMode(context)) }
    var silenceDurationMs by remember { mutableStateOf(LiveVoiceSettings.silenceDurationMs(context)) }
    var liveModelMenu by remember { mutableStateOf(false) }
    var modelOrderInput by remember { mutableStateOf(modelOrder.joinToString("\n")) }
    var keyNotice by remember { mutableStateOf("") }
    var geminiVoice by remember { mutableStateOf(VoiceSettings.geminiVoice(context)) }
    var bubbleNotice by remember { mutableStateOf("") }
    var voiceMenu by remember { mutableStateOf(false) }
    var accessibilityEnabled by remember { mutableStateOf(JarvisAccessibilityService.isEnabled(context)) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var bubbleRunning by remember { mutableStateOf(JarvisBodyService.isBodyEnabled(context)) }
    var enabledCapabilities by remember {
        mutableStateOf(JarvisCapability.values().associateWith { CapabilitySettings.isEnabled(context, it) })
    }

    LaunchedEffect(modelOrder) { modelOrderInput = modelOrder.joinToString("\n") }
    LaunchedEffect(selectedProvider) {
        AiProviderSettings.setActiveProvider(context, selectedProvider)
        providerKeyInput = if (selectedProvider == AiProvider.GEMINI) "" else AiProviderSettings.readKey(context, selectedProvider)
        val models = AiProviderSettings.readModels(context, selectedProvider)
        providerFastModel = models.first
        providerStrongModel = models.second
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled = JarvisAccessibilityService.isEnabled(context)
                overlayGranted = Settings.canDrawOverlays(context)
                bubbleRunning = JarvisBodyService.isBodyEnabled(context)
                latencySnapshot = AiProviderTelemetry.all(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val accessibilityLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        accessibilityEnabled = JarvisAccessibilityService.isEnabled(context)
    }
    val overlayLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        overlayGranted = Settings.canDrawOverlays(context)
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
            Column(Modifier.weight(1f)) {
                Text("JARVIS · SETTINGS", color = MaterialTheme.colorScheme.primary, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }

        SettingsCard(energy, phase) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                SectionHeading(Icons.Default.GraphicEq, "Appearance", "Orb theme and Live audio behaviour.")
                Text("Orb theme", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                OrbTheme.values().forEach { theme ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { OrbThemeSettings.set(context, theme) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = orbTheme == theme, onClick = { OrbThemeSettings.set(context, theme) })
                        Text(theme.label, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                    }
                }
                Text("Live model", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                OutlinedButton(onClick = { liveModelMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(liveModel.removePrefix("models/"))
                }
                DropdownMenu(expanded = liveModelMenu, onDismissRequest = { liveModelMenu = false }) {
                    LiveVoiceSettings.liveModels.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model.removePrefix("models/")) },
                            onClick = {
                                liveModel = model
                                LiveVoiceSettings.setLiveModel(context, model)
                                liveModelMenu = false
                            }
                        )
                    }
                }
                Text("Playback echo mode · ${echoMode.label}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                EchoControlMode.values().forEach { mode ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = echoMode == mode, onClick = {
                            echoMode = mode
                            LiveVoiceSettings.setEchoMode(context, mode)
                        })
                        Text(mode.label, color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp)
                    }
                }
                Text("End-of-turn silence · ${silenceDurationMs} ms", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                Slider(
                    value = silenceDurationMs.toFloat(),
                    onValueChange = { silenceDurationMs = (it / 100f).toInt() * 100 },
                    onValueChangeFinished = { LiveVoiceSettings.setSilenceDurationMs(context, silenceDurationMs) },
                    valueRange = 200f..2_000f,
                    steps = 17
                )
                TextButton(onClick = onOpenMemories, modifier = Modifier.fillMaxWidth()) { Text("Open Jarvis memory") }
            }
        }

        SettingsCard(energy, phase) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                SectionHeading(Icons.Default.Key, "AI provider and key slots", "Gemini is the default. Each provider uses a separate encrypted key slot; configured providers may be tried in fallback order.")
                AiProvider.values().forEach { provider ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { selectedProvider = provider },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selectedProvider == provider, onClick = { selectedProvider = provider })
                        Column {
                            Text(provider.displayName, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Text(provider.summary, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                        }
                    }
                }
                OutlinedTextField(
                    value = providerKeyInput,
                    onValueChange = { providerKeyInput = it; keyNotice = "" },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("${selectedProvider.displayName} API key") },
                    placeholder = { Text(if (selectedProviderHasKey) "Key saved · paste a replacement" else "Paste ${selectedProvider.displayName} API key") },
                    visualTransformation = if (providerKeyInput.isBlank()) VisualTransformation.None else PasswordVisualTransformation(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            if (providerKeyInput.isNotBlank()) {
                                if (selectedProvider == AiProvider.GEMINI) conversationViewModel.saveGeminiKey(providerKeyInput)
                                else AiProviderSettings.writeKey(context, selectedProvider, providerKeyInput)
                                providerKeyInput = ""
                                keyNotice = "${selectedProvider.displayName} key saved in its own device slot."
                            }
                        },
                        enabled = providerKeyInput.isNotBlank()
                    ) { Text("Save key") }
                    OutlinedButton(
                        onClick = {
                            if (selectedProvider == AiProvider.GEMINI) conversationViewModel.clearGeminiKey()
                            else AiProviderSettings.writeKey(context, selectedProvider, "")
                            providerKeyInput = ""
                            keyNotice = "${selectedProvider.displayName} key removed from this device."
                        },
                        enabled = selectedProviderHasKey
                    ) { Text("Remove key") }
                }
                Text(if (selectedProviderHasKey) "${selectedProvider.displayName} key saved" else "No ${selectedProvider.displayName} key saved", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                if (selectedProvider == AiProvider.GEMINI) {
                    Text("Gemini connection · $connectionStatus${pingMs?.let { " · measured ${it}ms" }.orEmpty()}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    OutlinedButton(
                        onClick = conversationViewModel::testGeminiConnection,
                        enabled = hasGeminiKey && !testingConnection
                    ) { Text(if (testingConnection) "Testing Gemini…" else "Test Gemini connection") }
                } else {
                    Text("${selectedProvider.displayName} fast model", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    OutlinedButton(onClick = { providerFastMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(providerFastModel) }
                    DropdownMenu(expanded = providerFastMenu, onDismissRequest = { providerFastMenu = false }) {
                        AiProviderSettings.modelPresets[selectedProvider].orEmpty().forEach { model ->
                            DropdownMenuItem(text = { Text(model) }, onClick = {
                                providerFastModel = model
                                runCatching { AiProviderSettings.writeModel(context, selectedProvider, false, model) }
                                providerFastMenu = false
                            })
                        }
                    }
                    Text("${selectedProvider.displayName} stronger fallback model", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    OutlinedButton(onClick = { providerStrongMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(providerStrongModel) }
                    DropdownMenu(expanded = providerStrongMenu, onDismissRequest = { providerStrongMenu = false }) {
                        AiProviderSettings.modelPresets[selectedProvider].orEmpty().forEach { model ->
                            DropdownMenuItem(text = { Text(model) }, onClick = {
                                providerStrongModel = model
                                runCatching { AiProviderSettings.writeModel(context, selectedProvider, true, model) }
                                providerStrongMenu = false
                            })
                        }
                    }
                }
                if (keyNotice.isNotBlank()) Text(keyNotice, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
            }
        }

        SettingsCard(energy, phase) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                SectionHeading(Icons.Default.GraphicEq, "Voice", "Gemini TTS with Charon is the normal Jarvis voice. Android phone TTS is reserved for an emergency fallback when Gemini TTS fails.")
                Text("Primary voice · Gemini TTS · Charon", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Text("If Gemini TTS cannot speak, Jarvis switches to Android phone TTS and labels that fallback in the conversation.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                OutlinedButton(onClick = { voiceMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Gemini preset · $geminiVoice")
                }
                DropdownMenu(expanded = voiceMenu, onDismissRequest = { voiceMenu = false }) {
                    VoiceSettings.GEMINI_VOICES.forEach { voice ->
                        DropdownMenuItem(
                            text = { Text(if (voice == "Charon") "$voice · default" else voice) },
                            onClick = {
                                geminiVoice = voice
                                VoiceSettings.setGeminiVoice(context, voice)
                                voiceMenu = false
                            }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Speak Jarvis replies", fontWeight = FontWeight.SemiBold)
                        Text("Live Talk uses its active audio session; Android phone TTS remains its last-resort fallback.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                    }
                    Switch(checked = autoSpeak, onCheckedChange = { jarvisViewModel.toggleAutoSpeak() })
                }
            }
        }

        SettingsCard(energy, phase) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                SectionHeading(Icons.Default.Settings, "Gemini text models", "Ordered fallback choices for Jarvis replies.")
                modelOrder.forEachIndexed { index, id ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { conversationViewModel.selectPrimaryGeminiModel(id) }.padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = index == 0, onClick = { conversationViewModel.selectPrimaryGeminiModel(id) })
                        Column {
                            Text(id, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Text(if (index == 0) "Preferred first" else "Fallback ${index + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                        }
                    }
                }
                OutlinedTextField(
                    value = modelOrderInput,
                    onValueChange = { modelOrderInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("One model ID per line") },
                    minLines = 3,
                    maxLines = 8
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        runCatching { conversationViewModel.saveGeminiModelOrder(modelOrderInput) }
                            .onSuccess { modelOrderInput = it.joinToString("\n") }
                            .onFailure { keyNotice = it.message.orEmpty() }
                    }) { Text("Save order") }
                    OutlinedButton(onClick = conversationViewModel::refreshAvailableGeminiModels, enabled = hasGeminiKey && !refreshingModels) {
                        Text(if (refreshingModels) "Refreshing…" else "Refresh")
                    }
                    TextButton(onClick = {
                        val defaults = conversationViewModel.resetGeminiModelOrder()
                        modelOrderInput = defaults.joinToString("\n")
                    }) { Text("Defaults") }
                }
                if (modelMessage.isNotBlank()) Text(modelMessage, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                if (keyNotice.isNotBlank() && !keyNotice.startsWith("Gemini key")) Text(keyNotice, color = MaterialTheme.colorScheme.error, fontSize = 10.sp)
            }
        }

        SettingsCard(energy, phase) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                SectionHeading(Icons.Default.Security, "Phone control permissions", "Each capability has its own switch. Android runtime permissions and service approval remain separate.")
                JarvisCapability.values().forEach { capability ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(capability.label, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Text(capability.description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                        }
                        Switch(
                            checked = enabledCapabilities[capability] == true,
                            onCheckedChange = { enabled ->
                                CapabilitySettings.setEnabled(context, capability, enabled)
                                enabledCapabilities = enabledCapabilities + (capability to enabled)
                            }
                        )
                    }
                }
                Text(
                    "Accessibility service · ${if (accessibilityEnabled) "enabled" else "not enabled"}${if (JarvisAccessibilityService.isRunning()) " · running" else ""}",
                    color = if (accessibilityEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedButton(onClick = {
                    accessibilityLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }, modifier = Modifier.fillMaxWidth()) { Text("Open Accessibility settings") }
                Text("Notification access · ${if (NotificationStore.hasAccess(context)) "enabled" else "not enabled"} · ${if (NotificationStore.isConnected()) "connected" else "not connected"}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                OutlinedButton(onClick = { NotificationStore.openSettings(context) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Open Notification access settings")
                }
                Text(
                    "Overlay permission · ${if (overlayGranted) "granted" else "not granted"} · Floating bubble · ${if (bubbleRunning) "on" else "off"}",
                    color = if (overlayGranted && bubbleRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (!overlayGranted) {
                    OutlinedButton(onClick = {
                        overlayLauncher.launch(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                        )
                    }, modifier = Modifier.fillMaxWidth()) { Text("Grant overlay permission") }
                }
                Button(
                    onClick = {
                        bubbleNotice = ""
                        runCatching {
                            if (bubbleRunning) JarvisBodyService.stopBody(context)
                            else JarvisBodyService.startBody(context)
                        }.onSuccess {
                            bubbleRunning = !bubbleRunning
                        }.onFailure {
                            bubbleNotice = "The floating Jarvis body could not be changed: ${it.message ?: "Android refused the service request."}"
                        }
                    },
                    enabled = overlayGranted,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (bubbleRunning) "Turn floating ball off" else "Start floating Jarvis ball") }
                Text("The foreground body is optional and user-started. It hosts the ball, microphone, and conversation; phone screen access is a separate optional AccessibilityService.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                if (bubbleNotice.isNotBlank()) Text(bubbleNotice, color = MaterialTheme.colorScheme.error, fontSize = 10.sp)
            }
        }
        SettingsCard(energy, phase) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                SectionHeading(Icons.Default.Security, "Health", "Live feature state and measurements captured on this device.")
                Text("Microphone · ${permissionState(context, Manifest.permission.RECORD_AUDIO)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                Text("Accessibility · ${if (accessibilityEnabled) "enabled" else "disabled"} · ${if (JarvisAccessibilityService.isRunning()) "running" else "not running"}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                Text("Overlay · ${if (overlayGranted) "granted" else "not granted"} · Floating body · ${if (bubbleRunning) "on" else "off"}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                Text("Contacts · ${permissionState(context, Manifest.permission.READ_CONTACTS)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                Text("Calendar · ${permissionState(context, Manifest.permission.READ_CALENDAR)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                Text("Notification access · ${if (NotificationStore.hasAccess(context)) "enabled" else "disabled"} · ${if (NotificationStore.isConnected()) "connected" else "not connected"}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                Text("Gemini self-test · $connectionStatus${pingMs?.let { " · measured ${it}ms" }.orEmpty()}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                Text("Live session · ${liveStatus.name}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                val lastError = liveNotice ?: activityRecords.firstOrNull { it.status == HomeActivityStatus.FAILED }?.errorReason
                Text("Last Live error/event · ${lastError ?: "None recorded"}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                val lastDecision = actionDecisions.firstOrNull()
                Text("Last action decision · ${lastDecision?.let { "${it.decision} · ${it.actionLabel}" } ?: "None recorded"}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                val lastTts = activityRecords.firstOrNull { it.kind == "TTS" }
                Text(
                    "Last TTS call · ${lastTts?.let { "${it.step} · ${java.text.DateFormat.getTimeInstance().format(java.util.Date(it.updatedAt))}" } ?: "None recorded"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp
                )
                Text("Measured provider-route duration · whole reply attempt, including tool calls", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                latencySnapshot.forEach { metric ->
                    val average = metric.averageMs?.let { "avg ${it}ms from ${metric.sampleCount} route attempt(s)" } ?: "No route timings yet"
                    val last = metric.lastMs?.let { " · last ${it}ms · ${if (metric.lastSucceeded == true) "success" else "failed"}" }.orEmpty()
                    Text("${metric.provider.displayName} · $average$last", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

private fun permissionState(context: android.content.Context, permission: String): String =
    if (ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) "granted" else "not granted"

@Composable
private fun SettingsCard(energy: Float, phase: Float, content: @Composable () -> Unit) {
    EnergyCard(
        modifier = Modifier.fillMaxWidth(),
        energy = energy,
        phase = phase,
        content = content
    )
}

@Composable
private fun SectionHeading(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}
