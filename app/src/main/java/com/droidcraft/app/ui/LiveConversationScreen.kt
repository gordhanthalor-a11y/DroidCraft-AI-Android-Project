package com.droidcraft.app.ui

import android.Manifest
import androidx.compose.animation.AnimatedVisibility
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.droidcraft.app.model.LiveConversationStatus
import com.droidcraft.app.model.LiveVoiceMode
import com.droidcraft.app.viewmodel.YouTubeAutomationViewModel

@Composable
fun LiveConversationScreen(
    viewModel: YouTubeAutomationViewModel,
    onOpenSettings: () -> Unit,
    onOpenChat: () -> Unit
) {
    val context = LocalContext.current
    val status by viewModel.liveStatus.collectAsState()
    val messages by viewModel.liveMessages.collectAsState()
    val userDraft by viewModel.liveUserDraft.collectAsState()
    val assistantDraft by viewModel.liveAssistantDraft.collectAsState()
    val selectedVoice by viewModel.liveVoiceMode.collectAsState()
    val activeVoice by viewModel.liveEffectiveVoice.collectAsState()
    val voiceFallback by viewModel.liveVoiceFallback.collectAsState()
    val notice by viewModel.liveNotice.collectAsState()
    var textInput by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    val microphonePermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startLiveConversation()
        } else {
            viewModel.showLiveNotice("Microphone permission is needed for a live voice conversation.")
        }
    }

    val isActive = status == LiveConversationStatus.CONNECTING ||
        status == LiveConversationStatus.LISTENING ||
        status == LiveConversationStatus.SPEAKING
    val voiceInUse = activeVoice ?: selectedVoice

    LaunchedEffect(messages.size, userDraft, assistantDraft) {
        val extraRows = (if (userDraft.isNotBlank()) 1 else 0) + (if (assistantDraft.isNotBlank()) 1 else 0)
        val last = messages.size + extraRows - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Jarvis Live Talk", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            statusLabel(status),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status == LiveConversationStatus.ERROR) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (status == LiveConversationStatus.CONNECTING) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }

                Text(
                    text = if (voiceFallback && activeVoice == LiveVoiceMode.GEMINI_LIVE && selectedVoice == LiveVoiceMode.ELEVENLABS) {
                        "Voice now: Gemini TTS (free) · automatic fallback"
                    } else {
                        "Voice now: ${voiceInUse.title}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Gemini Live handles listening and replies. ElevenLabs mode speaks the transcript as Bunty; otherwise Gemini TTS uses your preset voice. Native Live audio is a last resort when reply text is unavailable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Live model: models/gemini-3.8-live · Gemini Live WebSocket v1beta",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            if (isActive) {
                                viewModel.stopLiveConversation()
                            } else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                viewModel.startLiveConversation()
                            } else {
                                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (isActive) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (isActive) "End conversation" else "Start Live Talk")
                    }
                    TextButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Open Settings")
                    }
                }
            }
        }

        AnimatedVisibility(visible = isActive) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Text(
                    when (status) {
                        LiveConversationStatus.CONNECTING -> "Opening a secure session…"
                        LiveConversationStatus.LISTENING -> "Voice link active · listening for your next turn"
                        LiveConversationStatus.SPEAKING -> "Voice link active · Jarvis is responding"
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (notice != null) {
            Spacer(Modifier.height(8.dp))
            val errorNotice = status == LiveConversationStatus.ERROR
            Card(colors = CardDefaults.cardColors(
                containerColor = if (errorNotice) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.secondaryContainer
            )) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = notice.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (errorNotice) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    if (status == LiveConversationStatus.ERROR) {
                        Text(
                            "Use Retry Live Talk, or open Chat fallback for a Gemini text reply with phone TTS.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { viewModel.startLiveConversation() }) { Text("Retry Live Talk") }
                            TextButton(onClick = onOpenChat) { Text("Open Chat fallback") }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                TranscriptBubble(message.text, isUser = message.isUser)
            }
            if (userDraft.isNotBlank()) {
                item(key = "live-user-draft") { TranscriptBubble(userDraft, isUser = true, isDraft = true) }
            }
            if (assistantDraft.isNotBlank()) {
                item(key = "live-assistant-draft") { TranscriptBubble(assistantDraft, isUser = false, isDraft = true) }
            }
            if (messages.isEmpty() && userDraft.isBlank() && assistantDraft.isBlank() && !isActive) {
                item(key = "live-empty") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Start a conversation and speak naturally in Hindi, Hinglish, or English.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = textInput,
                onValueChange = { textInput = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Type during the conversation...") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )
            IconButton(
                onClick = {
                    val message = textInput.trim()
                    if (message.isNotEmpty() && viewModel.sendLiveText(message)) {
                        textInput = ""
                    }
                },
                enabled = textInput.isNotBlank() && isActive
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send text to Jarvis")
            }
        }
    }
}

@Composable
private fun TranscriptBubble(text: String, isUser: Boolean, isDraft: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = if (isDraft) 0.dp else 1.dp
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                Text(
                    if (isUser) "You" else "Jarvis",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.primary
                )
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun statusLabel(status: LiveConversationStatus): String = when (status) {
    LiveConversationStatus.IDLE -> "Ready to connect"
    LiveConversationStatus.CONNECTING -> "Connecting to Gemini Live…"
    LiveConversationStatus.LISTENING -> "Listening — speak naturally"
    LiveConversationStatus.SPEAKING -> "Jarvis is speaking"
    LiveConversationStatus.ERROR -> "Not connected"
}
