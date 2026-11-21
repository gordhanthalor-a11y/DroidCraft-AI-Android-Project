package com.droidcraft.app.ui

import android.Manifest
import android.os.BatteryManager
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.droidcraft.app.ai.AudioOutputLevel
import com.droidcraft.app.activity.HomeActivityRecord
import com.droidcraft.app.data.ActionDecisionStore
import com.droidcraft.app.data.ActionDecisionRecord
import com.droidcraft.app.activity.HomeActivityStatus
import com.droidcraft.app.activity.HomeActivityTracker
import com.droidcraft.app.ai.LiveToolExecutor
import com.droidcraft.app.jarvis.JarvisMessage
import com.droidcraft.app.jarvis.JarvisMessageKind
import com.droidcraft.app.jarvis.JarvisViewModel
import com.droidcraft.app.jarvis.overlay.JarvisBubbleController
import com.droidcraft.app.model.LiveConversationStatus
import com.droidcraft.app.viewmodel.JarvisConversationViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.max

@Composable
fun JarvisScreen(
    conversationViewModel: JarvisConversationViewModel,
    jarvisViewModel: JarvisViewModel,
    energy: Float,
    phase: State<Float>,
    hasGeminiKey: Boolean,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val hudText = MaterialTheme.colorScheme.onBackground
    val hudMuted = MaterialTheme.colorScheme.onSurfaceVariant
    val hudPrimary = MaterialTheme.colorScheme.primary
    val hudAccent = MaterialTheme.colorScheme.tertiary
    val hudPanel = MaterialTheme.colorScheme.surface
    LaunchedEffect(conversationViewModel, jarvisViewModel) {
        conversationViewModel.setLiveToolExecutor(
            LiveToolExecutor { name, args, userText -> jarvisViewModel.executeLiveTool(name, args, userText) }
        )
    }
    val liveStatus by conversationViewModel.liveStatus.collectAsState()
    val liveNotice by conversationViewModel.liveNotice.collectAsState()
    val liveMessages by conversationViewModel.liveMessages.collectAsState()
    val liveUserDraft by conversationViewModel.liveUserDraft.collectAsState()
    val liveAssistantDraft by conversationViewModel.liveAssistantDraft.collectAsState()
    val liveAudioLevel by conversationViewModel.liveAudioLevel.collectAsState()
    val messages by jarvisViewModel.messages.collectAsState()
    val thinking by jarvisViewModel.isThinking.collectAsState()
    val assistantDraft by jarvisViewModel.assistantDraft.collectAsState()
    val needsAttention by jarvisViewModel.needsAttention.collectAsState()
    val speaking by jarvisViewModel.isSpeaking.collectAsState()
    val voiceNotice by jarvisViewModel.voiceNotice.collectAsState()
    val pendingTap by jarvisViewModel.pendingTapConfirmation.collectAsState()
    val outputLevel by AudioOutputLevel.level.collectAsState()
    var input by remember { mutableStateOf("") }
    var showActivity by remember { mutableStateOf(false) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var batteryPercent by remember { mutableStateOf(readBatteryPercent(context)) }
    val activities by HomeActivityTracker.records.collectAsState()
    val actionDecisions by ActionDecisionStore.records.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }
    LaunchedEffect(context) {
        while (true) {
            batteryPercent = readBatteryPercent(context)
            delay(30_000)
        }
    }

    LaunchedEffect(liveMessages) {
        liveMessages.forEach { message ->
            jarvisViewModel.appendLiveTranscript(message.id, message.text, message.isUser)
        }
    }

    val activeLive = liveStatus.isActive()
    val visualState = when {
        liveStatus == LiveConversationStatus.ERROR -> "NEEDS_ATTENTION"
        liveStatus == LiveConversationStatus.CONNECTING -> "WORKING"
        liveStatus == LiveConversationStatus.SPEAKING -> "SPEAKING"
        liveStatus == LiveConversationStatus.LISTENING -> "LISTENING"
        pendingTap != null || needsAttention -> "NEEDS_ATTENTION"
        thinking -> "WORKING"
        speaking -> "SPEAKING"
        else -> "IDLE"
    }
    LaunchedEffect(visualState) { JarvisBubbleController.publishState(context, visualState) }

    LaunchedEffect(messages.size, liveUserDraft, liveAssistantDraft, assistantDraft) {
        val draftCount = (if (liveUserDraft.isNotBlank()) 1 else 0) +
            (if (liveAssistantDraft.isNotBlank()) 1 else 0) +
            (if (assistantDraft.isNotBlank()) 1 else 0)
        val count = messages.size + draftCount
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) conversationViewModel.startLiveConversation()
        else conversationViewModel.showLiveNotice("Microphone permission is required for Gemini Live. Allow it in Android Settings to use the orb.")
    }
    val toggleLive = rememberUpdatedState(newValue = {
        if (activeLive) {
            conversationViewModel.stopLiveConversation()
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            conversationViewModel.startLiveConversation()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    })

    val clock = SimpleDateFormat("h:mm", Locale.getDefault()).format(Date(nowMs))
    val amPm = SimpleDateFormat("a", Locale.getDefault()).format(Date(nowMs))
    val date = SimpleDateFormat("EEE · d MMM", Locale.getDefault()).format(Date(nowMs))
    val level = max(outputLevel, liveAudioLevel).coerceIn(0f, 1f)
    val uptime = formatUptime(SystemClock.elapsedRealtime())
    val latestActivity = activities.firstOrNull()
    val latestActivityStamp = latestActivity?.let {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it.updatedAt))
    }
    val showWorkingPlinth = activities.any {
        it.kind == "Phone action" && it.status == HomeActivityStatus.RUNNING && it.steps.size >= 3
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(Modifier.fillMaxWidth().height(64.dp)) {
            Column(
                modifier = Modifier.align(Alignment.TopStart),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("${visualState.replace('_', ' ')}", color = hudPrimary, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text(
                    "BAT ${if (batteryPercent >= 0) "$batteryPercent%" else "--"}  ·  UP $uptime",
                    color = hudMuted,
                    fontSize = 9.sp,
                    letterSpacing = 0.6.sp
                )
                if (!hasGeminiKey) Text("GEMINI KEY REQUIRED", color = hudAccent, fontSize = 8.sp, letterSpacing = 0.7.sp)
            }
            Row(
                modifier = Modifier.align(Alignment.TopEnd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(clock, color = hudPrimary, fontSize = 28.sp, fontWeight = FontWeight.Light, lineHeight = 29.sp)
                        Text(amPm, color = hudAccent, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
                    }
                    Text(date.uppercase(), color = hudMuted, fontSize = 8.sp, letterSpacing = 1.sp)
                }
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = hudPrimary, modifier = Modifier.size(18.dp))
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(292.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AnimatedVisibility(
                    visible = showWorkingPlinth,
                    enter = fadeIn(tween(380)),
                    exit = fadeOut(tween(520))
                ) {
                    WorkingTaskPlinth(phase = phase, color = hudAccent)
                }
            }
            JarvisOrb(
                state = visualState,
                energy = energy,
                audioLevel = level,
                phase = phase,
                onClick = { toggleLive.value() },
                modifier = Modifier.size(284.dp)
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 5.dp, vertical = 3.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            items(messages, key = { it.id }) { message -> TranscriptBubble(message) }
            if (liveUserDraft.isNotBlank()) {
                item(key = "live-user-draft") { DraftLine("YOU · LISTENING", liveUserDraft, hudPrimary) }
            }
            if (liveAssistantDraft.isNotBlank()) {
                item(key = "live-assistant-draft") { DraftLine("JARVIS · SPEAKING", liveAssistantDraft, hudAccent) }
            }
            if (assistantDraft.isNotBlank()) {
                item(key = "jarvis-text-draft") { DraftLine("JARVIS · THINKING", assistantDraft, hudPrimary) }
            }
        }

        (liveNotice ?: voiceNotice)?.takeIf { it.isNotBlank() }?.let { notice ->
            Text(notice, color = hudAccent, fontSize = 9.sp, lineHeight = 12.sp, maxLines = 1)
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(27.dp).clickable { showActivity = true },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Canvas(Modifier.size(width = 3.dp, height = 15.dp)) {
                drawRoundRect(color = hudPrimary.copy(alpha = 0.85f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width))
            }
            Text(latestActivityStamp ?: "--:--:--", color = hudAccent, fontSize = 8.sp, letterSpacing = 0.5.sp)
            Text(
                latestActivity?.let { "${it.kind.uppercase()} · ${it.step}" } ?: "SYSTEM READY · OPEN ACTIVITY",
                modifier = Modifier.weight(1f),
                color = hudMuted,
                fontSize = 9.sp,
                maxLines = 1
            )
            Text("LOG ›", color = hudPrimary, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message Jarvis…", color = hudMuted, fontSize = 13.sp) },
                shape = RoundedCornerShape(17.dp),
                singleLine = false,
                maxLines = 2,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = hudText,
                    unfocusedTextColor = hudText,
                    focusedPlaceholderColor = hudMuted,
                    unfocusedPlaceholderColor = hudMuted,
                    cursorColor = hudPrimary,
                    focusedContainerColor = hudPanel,
                    unfocusedContainerColor = hudPanel
                )
            )
            EnergyActionButton(
                label = "Send",
                enabled = input.isNotBlank(),
                energy = energy,
                phase = phase,
                onClick = {
                    val text = input.trim()
                    if (text.isNotBlank()) {
                        if (liveStatus == LiveConversationStatus.LISTENING || liveStatus == LiveConversationStatus.SPEAKING) {
                            if (conversationViewModel.sendLiveText(text)) input = ""
                        } else {
                            jarvisViewModel.handleUserText(text)
                            input = ""
                        }
                    }
                }
            )
        }
    }

    if (showActivity) {
        AlertDialog(
            onDismissRequest = { showActivity = false },
            title = { Text("Jarvis activity", color = MaterialTheme.colorScheme.primary) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item(key = "recent-activity-title") { ActivitySectionTitle("Recent activity") }
                    if (activities.isEmpty()) item { Text("No activity recorded yet.", color = hudMuted) }
                    items(activities, key = { "activity-${it.id}" }) { record -> ActivityRecordRow(record) }
                    item(key = "decisions-title") { ActivitySectionTitle("Persistent action decisions") }
                    if (actionDecisions.isEmpty()) item { Text("No approval or rejection decisions recorded yet.", color = hudMuted) }
                    items(actionDecisions, key = { "decision-${it.id}" }) { decision -> ActionDecisionRow(decision) }
                }
            },
            confirmButton = { TextButton(onClick = { showActivity = false }) { Text("Close") } }
        )
    }

    pendingTap?.let { pending ->
        AlertDialog(
            onDismissRequest = jarvisViewModel::dismissPendingTap,
            title = { Text("Confirm screen action") },
            text = {
                Text("Tap ‘${pending.target}’? ${pending.reason}\n\nThis action will run only after you confirm here.")
            },
            confirmButton = {
                TextButton(onClick = jarvisViewModel::confirmPendingTap) { Text("Confirm tap") }
            },
            dismissButton = {
                TextButton(onClick = jarvisViewModel::dismissPendingTap) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun TranscriptBubble(message: JarvisMessage) {
    val primary = MaterialTheme.colorScheme.primary
    val accent = MaterialTheme.colorScheme.tertiary
    val textColor = MaterialTheme.colorScheme.onBackground
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val isSystem = message.kind == JarvisMessageKind.SYSTEM
    val isUser = message.isUser
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.88f),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Text(
                when {
                    isSystem -> "J.A.R.V.I.S."
                    isUser -> "YOU"
                    else -> "JARVIS"
                },
                color = if (isUser) primary else accent,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp
            )
            Text(
                message.text,
                color = if (isSystem) muted else textColor,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                textAlign = if (isUser) TextAlign.End else TextAlign.Start,
                modifier = Modifier.padding(top = 1.dp, bottom = 2.dp)
            )
        }
    }
}

@Composable
private fun DraftLine(label: String, text: String, accent: Color) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = accent, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun WorkingTaskPlinth(phase: State<Float>, color: Color) {
    Canvas(Modifier.size(300.dp)) {
        val phase = phase.value
        val unit = size.minDimension
        listOf(0.36f, 0.43f, 0.49f).forEachIndexed { index, fraction ->
            val dash = PathEffect.dashPathEffect(
                floatArrayOf(unit * (0.018f + index * 0.003f), unit * 0.014f),
                phase * unit * (0.28f + index * 0.08f)
            )
            drawCircle(
                color = color.copy(alpha = 0.18f + 0.09f * kotlin.math.sin(phase * 6.283f + index).toFloat().coerceAtLeast(0f)),
                radius = unit * fraction,
                style = Stroke(width = 1.dp.toPx(), pathEffect = dash)
            )
        }
    }
}

@Composable
private fun ActivitySectionTitle(title: String) {
    Text(title.uppercase(), color = MaterialTheme.colorScheme.primary, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
}

@Composable
private fun ActionDecisionRow(record: ActionDecisionRecord) {
    val accent = MaterialTheme.colorScheme.tertiary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(record.decision, color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Text("${record.actionLabel} · ${record.packageName}", color = MaterialTheme.colorScheme.onBackground, fontSize = 11.sp)
        Text(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(record.decidedAt)), color = muted, fontSize = 8.sp)
    }
}

@Composable
private fun ActivityRecordRow(record: HomeActivityRecord) {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("${record.kind.uppercase()} · ${record.status.name}", color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        Text(record.title, color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(record.step, color = muted, fontSize = 10.sp)
        record.steps.forEach { step -> Text("› $step", color = muted, fontSize = 9.sp) }
        (record.errorReason ?: record.note)?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = MaterialTheme.colorScheme.tertiary, fontSize = 9.sp)
        }
        Text(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(record.updatedAt)), color = muted, fontSize = 8.sp)
    }
}

private fun readBatteryPercent(context: android.content.Context): Int = runCatching {
    val manager = context.getSystemService(android.content.Context.BATTERY_SERVICE) as BatteryManager
    manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 } ?: -1
}.getOrDefault(-1)

private fun formatUptime(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format(Locale.ROOT, "%d:%02d", hours, minutes)
    else String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
}

private fun LiveConversationStatus.isActive(): Boolean = when (this) {
    LiveConversationStatus.CONNECTING,
    LiveConversationStatus.LISTENING,
    LiveConversationStatus.SPEAKING -> true
    LiveConversationStatus.IDLE,
    LiveConversationStatus.ERROR -> false
}
