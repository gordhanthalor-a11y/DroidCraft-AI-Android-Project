package com.droidcraft.app.ui

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.droidcraft.app.activity.HomeActivityRecord
import com.droidcraft.app.activity.HomeActivityStatus
import com.droidcraft.app.activity.HomeActivityTracker
import com.droidcraft.app.data.local.AppDatabase
import com.droidcraft.app.jarvis.JarvisViewModel
import com.droidcraft.app.jarvis.voice.VoiceMode
import com.droidcraft.app.jarvis.voice.VoiceSettings
import com.droidcraft.app.model.LiveConversationStatus
import com.droidcraft.app.social.data.PostQueueEntity
import com.droidcraft.app.social.data.SocialAccountEntity
import com.droidcraft.app.social.data.SocialPlatform
import com.droidcraft.app.social.data.SocialStatus
import com.droidcraft.app.studio.StudioSettings
import com.droidcraft.app.viewmodel.YouTubeAutomationViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin

private val HudBackground = Color(0xFF03070D)
private val HudPanel = Color(0xFF0A1420)
private val HudPanelRaised = Color(0xFF0D1C2A)
private val HudCyan = Color(0xFF56EFF7)
private val HudCyanSoft = Color(0xFF9CF8FC)
private val HudAmber = Color(0xFFE6B55A)
private val HudText = Color(0xFFE7F5F8)
private val HudMuted = Color(0xFF94AEBB)
private val HudGreen = Color(0xFF48E1C2)
private val HudRed = Color(0xFFFF7183)
private val studioHealthClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.SECONDS)
    .callTimeout(7, TimeUnit.SECONDS)
    .build()

/** Native-first J.A.R.V.I.S. home. No WebView or recovery screen is required to use Home. */
@Composable
fun JarvisScreen(
    liveViewModel: YouTubeAutomationViewModel,
    jarvisViewModel: JarvisViewModel,
    onOpenStudio: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenLiveTalk: () -> Unit,
    onOpenMore: () -> Unit,
    onOpenSocialHub: () -> Unit,
    onOpenClipper: () -> Unit,
    onOpenVoiceStudio: () -> Unit,
    onOpenDubbing: () -> Unit,
    onOpenUploadHelper: () -> Unit,
    isVisible: Boolean
) {
    val context = LocalContext.current
    val liveStatus by liveViewModel.liveStatus.collectAsState()
    val liveNotice by liveViewModel.liveNotice.collectAsState()
    val hasGeminiKey by liveViewModel.hasGeminiKey.collectAsState()
    val chatThinking by liveViewModel.isThinking.collectAsState()
    val phoneTtsReady by jarvisViewModel.ttsReady.collectAsState()
    val jarvisThinking by jarvisViewModel.isThinking.collectAsState()
    val jarvisSpeaking by jarvisViewModel.isSpeaking.collectAsState()
    val jarvisListening by jarvisViewModel.isListening.collectAsState()
    val jarvisVoiceNotice by jarvisViewModel.voiceNotice.collectAsState()
    val userName by jarvisViewModel.userName.collectAsState()
    val handsFree by jarvisViewModel.handsFree.collectAsState()
    val activityRecords by HomeActivityTracker.records.collectAsState()

    val database = remember(context) { AppDatabase.getDatabase(context) }
    var socialAccounts by remember { mutableStateOf<List<SocialAccountEntity>>(emptyList()) }
    var queuedPosts by remember { mutableStateOf<List<PostQueueEntity>>(emptyList()) }
    var studioHealth by remember {
        mutableStateOf(HomeHealthState("Checking", "Checking Creator Studio", HealthTone.NEUTRAL))
    }
    var internetHealth by remember {
        mutableStateOf(HomeHealthState("Checking", "Checking network access", HealthTone.NEUTRAL))
    }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(database) {
        database.socialAccountDao().observeAll().collect { socialAccounts = it }
    }
    LaunchedEffect(database) {
        database.postQueueDao().observeAll().collect { queuedPosts = it }
    }
    LaunchedEffect(hasGeminiKey) {
        // Keep Jarvis chat's Keystore-backed key in sync with Settings.
        jarvisViewModel.reloadGeminiKey()
    }
    LaunchedEffect(isVisible) {
        if (!isVisible) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            internetHealth = readInternetHealth(context)
            studioHealth = checkStudioHealth(context)
            delay(30_000L)
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            liveViewModel.startLiveConversation()
        } else {
            liveViewModel.showLiveNotice(
                "Microphone permission was denied. Allow microphone access in Android Settings to use Live Talk."
            )
        }
    }
    val requestLiveStart = rememberUpdatedState(newValue = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            liveViewModel.startLiveConversation()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    })
    val toggleLive = rememberUpdatedState(newValue = {
        if (liveStatus.isActive()) liveViewModel.stopLiveConversation() else requestLiveStart.value()
    })

    val hour = java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.HOUR_OF_DAY)
    val greeting = greetingFor(hour)
    val displayName = userName.trim().ifBlank { "Gowardhan" }
    val localClock = remember(now) {
        SimpleDateFormat("EEEE · d MMMM · h:mm a", Locale.getDefault()).format(Date(now))
    }
    val liveLabel = when (liveStatus) {
        LiveConversationStatus.CONNECTING -> "CONNECTING TO GEMINI LIVE"
        LiveConversationStatus.LISTENING -> "LIVE TALK · LISTENING"
        LiveConversationStatus.SPEAKING -> "JARVIS IS SPEAKING"
        LiveConversationStatus.ERROR -> "LIVE TALK · CONNECTION ERROR"
        LiveConversationStatus.IDLE -> when {
            jarvisThinking || chatThinking -> "JARVIS IS THINKING"
            jarvisListening -> "VOICE INPUT ACTIVE"
            jarvisSpeaking -> "JARVIS IS SPEAKING"
            else -> if (hasGeminiKey) "SYSTEM IDLE · LIVE TALK STANDBY" else "LIVE TALK · GEMINI KEY REQUIRED"
        }
    }
    val voiceMode = VoiceSettings.mode(context)
    val voiceHealth = voiceHealth(context, voiceMode, hasGeminiKey, phoneTtsReady)

    val connectedApps = listOf(
        HomeAppState(
            name = "YouTube",
            initials = "YT",
            connectedAccount = socialAccounts.firstOrNull { it.isActive && it.platform.equals(SocialPlatform.YOUTUBE, true) },
            integrated = true
        ),
        HomeAppState(
            name = "Instagram",
            initials = "IG",
            connectedAccount = socialAccounts.firstOrNull { it.isActive && it.platform.equals(SocialPlatform.INSTAGRAM, true) },
            integrated = true
        ),
        HomeAppState(
            name = "Facebook",
            initials = "FB",
            connectedAccount = socialAccounts.firstOrNull { it.isActive && it.platform.equals(SocialPlatform.FACEBOOK, true) },
            integrated = true
        ),
        HomeAppState("GitHub", "GH", null, integrated = false),
        HomeAppState("Drive", "DR", null, integrated = false),
        HomeAppState("Kick", "K", null, integrated = false)
    )
    val trackedIds = activityRecords.mapTo(mutableSetOf()) { it.id }
    val persistedUploadRecords = queuedPosts
        .asSequence()
        .filter { it.status != SocialStatus.QUEUED && it.status != SocialStatus.CANCELLED }
        .sortedByDescending { it.createdAt }
        .take(6)
        .filterNot { "upload-${it.id}" in trackedIds }
        .map(::postWorkflowRecord)
        .toList()
    val workflows = (activityRecords + persistedUploadRecords)
        .sortedByDescending { it.updatedAt }
        .take(6)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HudBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("$greeting, $displayName", color = HudCyanSoft, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(localClock, color = HudMuted, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onOpenMore) {
                Icon(Icons.Default.MoreHoriz, contentDescription = "More tools", tint = HudMuted)
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = HudCyan)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "J.A.R.V.I.S.",
                color = HudText,
                fontSize = 28.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 7.sp
            )
            Text(
                "Five creator tools · live setup and health shown below",
                color = HudMuted,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            ReactorOrb(
                modifier = Modifier.size(236.dp),
                active = liveStatus.isActive() || chatThinking || jarvisThinking || jarvisSpeaking || jarvisListening,
                live = liveStatus.isActive(),
                onClick = { toggleLive.value() }
            )
            Text(liveLabel, color = if (liveStatus == LiveConversationStatus.ERROR) HudRed else HudCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.7.sp)
            Text(
                if (liveStatus.isActive()) "Tap the reactor to end Live Talk" else "Tap the reactor to start Live Talk",
                color = HudMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
            TextButton(onClick = onOpenLiveTalk) {
                Text("OPEN LIVE TALK CONSOLE", color = HudAmber, letterSpacing = 1.sp)
            }
        }

        val visibleNotice = liveNotice?.takeIf { it.isNotBlank() } ?: jarvisVoiceNotice?.takeIf { it.isNotBlank() }
        visibleNotice?.let { notice ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = if (liveStatus == LiveConversationStatus.ERROR || notice.contains("failed", true)) {
                    HudRed.copy(alpha = .10f)
                } else HudAmber.copy(alpha = .10f),
                border = BorderStroke(1.dp, if (liveStatus == LiveConversationStatus.ERROR || notice.contains("failed", true)) HudRed.copy(alpha = .42f) else HudAmber.copy(alpha = .35f))
            ) {
                Text(notice, modifier = Modifier.padding(12.dp), color = HudText, style = MaterialTheme.typography.bodySmall)
            }
        }

        SectionHeading("SYSTEM STATUS", trailing = "LIVE · refreshes every 30 sec")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            HomeStatusCard("SERVER", studioHealth, Modifier.weight(1f), onClick = onOpenSettings)
            HomeStatusCard(
                "VOICE ROUTER",
                voiceHealth,
                Modifier.weight(1f),
                onClick = onOpenSettings
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            HomeStatusCard(
                "WAKE WORD",
                HomeHealthState(
                    if (handsFree) "Enabled in settings" else "Off",
                    if (handsFree) "Preference is on; background listener status is not polled here" else "Enable in Settings when you want hands-free mode",
                    HealthTone.NEUTRAL
                ),
                Modifier.weight(1f),
                onClick = onOpenSettings
            )
            HomeStatusCard("INTERNET", internetHealth, Modifier.weight(1f))
        }

        SectionHeading("FIVE AGENTS", trailing = "Open Studio", onTrailingClick = onOpenStudio)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            AgentTile("Clipper", "Cut vertical clips", Icons.Default.ContentCut, Modifier.weight(1f), onOpenClipper)
            AgentTile("Voice Studio", "Script to speech", Icons.Default.GraphicEq, Modifier.weight(1f), onOpenVoiceStudio)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            AgentTile("Dubbing", "Transcribe and dub", Icons.Default.Translate, Modifier.weight(1f), onOpenDubbing)
            AgentTile("Upload Helper", "Video preflight QC", Icons.Default.VideoLibrary, Modifier.weight(1f), onOpenUploadHelper)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            AgentTile("Jarvis Chat", "Ask Jarvis anything", Icons.Default.Face, Modifier.weight(1f), onOpenChat)
            Spacer(Modifier.weight(1f))
        }

        SectionHeading("CONNECTED APPS", trailing = "Social Hub", onTrailingClick = onOpenSocialHub)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            connectedApps.chunked(2).forEach { rowApps ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    rowApps.forEach { app ->
                        ConnectedAppTile(
                            app = app,
                            modifier = Modifier.weight(1f),
                            onClick = onOpenSocialHub
                        )
                    }
                    if (rowApps.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Text(
            "YouTube, Instagram, and Facebook use Social Hub's shared account store. GitHub, Drive, and Kick are not integrated in this Android build yet.",
            color = HudMuted,
            style = MaterialTheme.typography.bodySmall
        )

        SectionHeading("WORKFLOW ACTIVITY", trailing = "${workflows.size} recent")
        if (workflows.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = HudPanel,
                border = BorderStroke(1.dp, Color(0xFF1D3040))
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("No recent jobs yet", color = HudText, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Clip, TTS, dubbing, upload, and Live Talk steps will appear here with server progress or the reported failure reason.",
                        color = HudMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            workflows.forEach { record -> WorkflowActivityCard(record) }
        }
    }
}

@Composable
private fun ReactorOrb(modifier: Modifier = Modifier, active: Boolean, live: Boolean, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "jarvis-reactor")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2.0).toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMillis = 8_000, easing = LinearEasing), RepeatMode.Restart),
        label = "reactor-particle-phase"
    )
    val pulse by transition.animateFloat(
        initialValue = .96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 2_200), RepeatMode.Reverse),
        label = "reactor-pulse"
    )
    Box(modifier = modifier.clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension * .46f
            val cyan = if (live) Color(0xFF8EFCFF) else HudCyan
            drawCircle(cyan.copy(alpha = .08f), radius = radius * 1.10f * pulse)
            drawCircle(cyan.copy(alpha = .38f), radius = radius, style = Stroke(width = 1.4.dp.toPx()))
            drawCircle(cyan.copy(alpha = .16f), radius = radius * .82f, style = Stroke(width = 1.dp.toPx()))
            drawArc(
                color = HudAmber.copy(alpha = .9f),
                startAngle = 208f,
                sweepAngle = 116f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(center.x - radius * .96f, center.y - radius * .96f),
                size = androidx.compose.ui.geometry.Size(radius * 1.92f, radius * 1.92f),
                style = Stroke(width = 2.8.dp.toPx())
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF1A6172).copy(alpha = .9f), Color(0xFF071724), Color(0xFF03070D)),
                    center = center,
                    radius = radius * .86f
                ),
                radius = radius * .77f
            )
            drawCircle(cyan.copy(alpha = .55f), radius = radius * .68f, style = Stroke(width = 1.dp.toPx()))
            drawCircle(cyan.copy(alpha = .21f), radius = radius * .53f, style = Stroke(width = 1.dp.toPx()))
            drawCircle(Color.White.copy(alpha = if (active) .12f else .06f), radius = radius * .37f)

            for (index in 0 until 36) {
                val angle = phase + (index * (Math.PI * 2.0 / 36.0)).toFloat()
                val particleRadius = radius * (.90f + .035f * sin(phase * 1.4f + index).toFloat())
                val point = androidx.compose.ui.geometry.Offset(
                    center.x + cos(angle).toFloat() * particleRadius,
                    center.y + sin(angle).toFloat() * particleRadius
                )
                val alpha = .24f + .55f * ((sin(phase + index * .63f).toFloat() + 1f) / 2f)
                drawCircle(cyan.copy(alpha = alpha), radius = if (index % 6 == 0) 2.6.dp.toPx() else 1.35.dp.toPx(), center = point)
            }
            for (index in 0 until 4) {
                val angle = phase * .32f + index * (Math.PI / 2.0).toFloat()
                val orbitRadius = radius * .57f
                drawCircle(
                    color = HudCyanSoft.copy(alpha = .65f),
                    radius = 2.4.dp.toPx(),
                    center = androidx.compose.ui.geometry.Offset(
                        center.x + cos(angle).toFloat() * orbitRadius,
                        center.y + sin(angle).toFloat() * orbitRadius
                    )
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = HudCyanSoft, modifier = Modifier.size(25.dp))
            Text(if (live) "LIVE" else "JARVIS", color = HudText, fontWeight = FontWeight.Bold, letterSpacing = 2.4.sp, fontSize = 15.sp)
            Text(if (live) "TAP TO DISCONNECT" else "TAP TO CONNECT", color = HudMuted, fontSize = 8.sp, letterSpacing = 1.1.sp)
        }
    }
}

@Composable
private fun SectionHeading(title: String, trailing: String, onTrailingClick: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(title, color = HudCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.9.sp, modifier = Modifier.weight(1f))
        if (onTrailingClick != null) {
            TextButton(onClick = onTrailingClick) {
                Text(trailing, color = HudAmber, fontSize = 11.sp)
            }
        } else {
            Text(trailing, color = HudMuted, fontSize = 10.sp)
        }
    }
}

private enum class HealthTone { GOOD, WARN, BAD, NEUTRAL }
private data class HomeHealthState(val headline: String, val detail: String, val tone: HealthTone)

private fun voiceHealth(
    context: Context,
    mode: VoiceMode,
    hasGeminiKey: Boolean,
    phoneTtsReady: Boolean
): HomeHealthState = when (mode) {
    VoiceMode.PHONE_FREE -> if (phoneTtsReady) {
        HomeHealthState("Ready", "On-device phone TTS initialized", HealthTone.GOOD)
    } else {
        HomeHealthState("Not confirmed", "Phone TTS is initializing or unavailable", HealthTone.NEUTRAL)
    }
    VoiceMode.GEMINI_FREE -> if (hasGeminiKey) {
        HomeHealthState(
            "Key saved",
            "Gemini ${VoiceSettings.geminiVoice(context)} selected · API access not tested",
            HealthTone.NEUTRAL
        )
    } else {
        HomeHealthState("Needs setup", "Gemini key missing · add one in Settings", HealthTone.WARN)
    }
    VoiceMode.ELEVENLABS -> if (VoiceSettings.elevenKey(context).isNotBlank()) {
        HomeHealthState("Key saved", "ElevenLabs selected · service access not tested", HealthTone.NEUTRAL)
    } else {
        HomeHealthState(
            "Key missing",
            if (hasGeminiKey) "Gemini fallback is configured" else "Gemini or phone fallback may be needed",
            HealthTone.WARN
        )
    }
    VoiceMode.SMALLEST -> if (StudioSettings.smallestKey(context).isNotBlank()) {
        HomeHealthState("Key saved", "Smallest selected · Creator Studio access shown separately", HealthTone.NEUTRAL)
    } else {
        HomeHealthState(
            "Key missing",
            if (hasGeminiKey) "Gemini fallback is configured" else "Gemini or phone fallback may be needed",
            HealthTone.WARN
        )
    }
    VoiceMode.HEYGEN -> if (StudioSettings.heygenKey(context).isNotBlank()) {
        HomeHealthState("Key saved", "HeyGen selected · Creator Studio access shown separately", HealthTone.NEUTRAL)
    } else {
        HomeHealthState(
            "Key missing",
            if (hasGeminiKey) "Gemini fallback is configured" else "Gemini or phone fallback may be needed",
            HealthTone.WARN
        )
    }
    VoiceMode.SMART -> if (hasGeminiKey) {
        HomeHealthState(
            "Smart routing",
            "Gemini ${VoiceSettings.geminiVoice(context)} is the default · provider access not tested",
            HealthTone.NEUTRAL
        )
    } else {
        HomeHealthState(
            "Gemini key needed",
            "Chat and Live need Gemini; Jarvis speech may fall back to phone TTS",
            HealthTone.WARN
        )
    }
}

@Composable
private fun HomeStatusCard(
    label: String,
    state: HomeHealthState,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val color = when (state.tone) {
        HealthTone.GOOD -> HudGreen
        HealthTone.WARN -> HudAmber
        HealthTone.BAD -> HudRed
        HealthTone.NEUTRAL -> HudMuted
    }
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(17.dp))
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .border(BorderStroke(1.dp, color.copy(alpha = .24f)), RoundedCornerShape(17.dp)),
        shape = RoundedCornerShape(17.dp),
        color = HudPanel
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, color = HudMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
            Text(state.headline, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(state.detail, color = HudMuted, fontSize = 10.sp, lineHeight = 13.sp, minLines = 2)
        }
    }
}

@Composable
private fun AgentTile(title: String, detail: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier
            .height(112.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .border(BorderStroke(1.dp, HudCyan.copy(alpha = .15f)), RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        color = HudPanelRaised
    ) {
        Column(Modifier.fillMaxSize().padding(13.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, contentDescription = title, tint = HudCyan, modifier = Modifier.size(21.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = HudText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
                Text(detail, color = HudMuted, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

private data class HomeAppState(
    val name: String,
    val initials: String,
    val connectedAccount: SocialAccountEntity?,
    val integrated: Boolean
)

@Composable
private fun ConnectedAppTile(app: HomeAppState, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val connected = app.connectedAccount?.isActive == true
    val stateColor = when {
        connected -> HudGreen
        app.integrated -> HudAmber
        else -> HudMuted
    }
    val stateText = when {
        connected -> "LINKED"
        app.integrated -> "NOT LINKED"
        else -> "NOT INTEGRATED"
    }
    val detail = when {
        connected -> app.connectedAccount?.accountName?.ifBlank { "Saved account" } ?: "Saved account"
        app.integrated -> "Link in Social Hub"
        else -> "Not available in this build"
    }
    Surface(
        modifier = modifier
            .height(82.dp)
            .clip(RoundedCornerShape(17.dp))
            .clickable(enabled = app.integrated, onClick = onClick)
            .border(BorderStroke(1.dp, stateColor.copy(alpha = .22f)), RoundedCornerShape(17.dp)),
        shape = RoundedCornerShape(17.dp),
        color = HudPanel
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = stateColor.copy(alpha = .13f)) {
                Box(Modifier.size(35.dp), contentAlignment = Alignment.Center) {
                    Text(app.initials, color = stateColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(9.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(app.name, color = HudText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Text(stateText, color = stateColor, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = .5.sp)
                Text(detail, color = HudMuted, fontSize = 9.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun WorkflowActivityCard(record: HomeActivityRecord) {
    val statusColor = when (record.status) {
        HomeActivityStatus.RUNNING -> HudCyan
        HomeActivityStatus.WAITING -> HudAmber
        HomeActivityStatus.COMPLETED -> HudGreen
        HomeActivityStatus.FAILED -> HudRed
        HomeActivityStatus.CANCELLED -> HudMuted
    }
    val statusLabel = when (record.status) {
        HomeActivityStatus.RUNNING -> "RUNNING"
        HomeActivityStatus.WAITING -> "WAITING"
        HomeActivityStatus.COMPLETED -> "COMPLETED"
        HomeActivityStatus.FAILED -> "FAILED"
        HomeActivityStatus.CANCELLED -> "CANCELLED"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = HudPanel,
        border = BorderStroke(1.dp, statusColor.copy(alpha = .26f))
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(record.kind.uppercase(Locale.ROOT), color = HudMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                Text(statusLabel, color = statusColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            Text(record.title, color = HudText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 2)
            Text("Step: ${record.step}", color = HudCyanSoft, fontSize = 11.sp)
            if (record.progress != null) {
                LinearProgressIndicator(
                    progress = record.progress.coerceIn(0, 100) / 100f,
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = statusColor,
                    trackColor = Color(0xFF203241)
                )
                Text("${record.progress.coerceIn(0, 100)}% · ${record.steps.size} tracked steps", color = HudMuted, fontSize = 9.sp)
            } else {
                Text("${record.steps.size} tracked steps · progress not reported", color = HudMuted, fontSize = 9.sp)
            }
            if (record.steps.isNotEmpty()) {
                Text(record.steps.takeLast(3).joinToString("  ›  "), color = HudMuted, fontSize = 9.sp, maxLines = 2)
            }
            record.note?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = HudAmber, fontSize = 10.sp)
            }
            record.errorReason?.takeIf { it.isNotBlank() }?.let {
                Text("Failure: $it", color = HudRed, fontSize = 10.sp)
            }
            Text(
                SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(record.updatedAt)),
                color = HudMuted.copy(alpha = .72f),
                fontSize = 9.sp,
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

private fun greetingFor(hour: Int): String = when (hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..20 -> "Good evening"
    else -> "Good night"
}

private suspend fun checkStudioHealth(context: Context): HomeHealthState = withContext(Dispatchers.IO) {
    try {
        val url = "${StudioSettings.requireServerUrl(context)}/api/v1/health"
        studioHealthClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                HomeHealthState("Offline", "Creator Studio returned HTTP ${response.code}", HealthTone.BAD)
            } else if (JSONObject(payload).optBoolean("ok", false)) {
                HomeHealthState("Online", "Creator Studio health check passed", HealthTone.GOOD)
            } else {
                HomeHealthState("Unavailable", "Health endpoint did not report ok", HealthTone.WARN)
            }
        }
    } catch (error: Exception) {
        HomeHealthState(
            "Offline",
            (error.message ?: error.javaClass.simpleName).take(96),
            HealthTone.BAD
        )
    }
}

@Suppress("MissingPermission")
private fun readInternetHealth(context: Context): HomeHealthState {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return HomeHealthState("Unknown", "Android network service unavailable", HealthTone.NEUTRAL)
    val network = manager.activeNetwork
        ?: return HomeHealthState("Offline", "No active network", HealthTone.BAD)
    val capabilities = manager.getNetworkCapabilities(network)
        ?: return HomeHealthState("Offline", "Network capabilities unavailable", HealthTone.BAD)
    if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
        return HomeHealthState("Offline", "Active network has no internet capability", HealthTone.BAD)
    }
    return if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
        HomeHealthState("Connected", "Android validated internet access", HealthTone.GOOD)
    } else {
        HomeHealthState("Limited", "Network exists, but internet is not validated", HealthTone.WARN)
    }
}

private fun postWorkflowRecord(post: PostQueueEntity): HomeActivityRecord {
    val status = when (post.status) {
        SocialStatus.UPLOADING -> HomeActivityStatus.RUNNING
        SocialStatus.PUBLISHED -> HomeActivityStatus.COMPLETED
        SocialStatus.FAILED -> HomeActivityStatus.FAILED
        else -> HomeActivityStatus.WAITING
    }
    val step = when (post.status) {
        SocialStatus.UPLOADING -> "Publishing to ${post.platform}"
        SocialStatus.PUBLISHED -> "Published to ${post.platform}"
        SocialStatus.FAILED -> "Upload failed · retry ${post.retryCount}"
        else -> "Waiting for scheduled upload"
    }
    return HomeActivityRecord(
        id = "upload-${post.id}",
        kind = "Upload",
        title = post.title.ifBlank { "${post.platform} upload" },
        status = status,
        step = step,
        progress = if (post.status == SocialStatus.PUBLISHED) 100 else null,
        steps = listOf("Upload queued", step),
        errorReason = post.lastError,
        updatedAt = post.publishedAt ?: post.createdAt
    )
}

private fun LiveConversationStatus.isActive(): Boolean = when (this) {
    LiveConversationStatus.CONNECTING,
    LiveConversationStatus.LISTENING,
    LiveConversationStatus.SPEAKING -> true
    LiveConversationStatus.IDLE,
    LiveConversationStatus.ERROR -> false
}
