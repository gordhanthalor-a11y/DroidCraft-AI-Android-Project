package com.droidcraft.app.ui

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.droidcraft.app.jarvis.JarvisCommandParser
import com.droidcraft.app.jarvis.JarvisIntentType
import com.droidcraft.app.jarvis.JarvisMessage
import com.droidcraft.app.jarvis.JarvisMessageKind
import com.droidcraft.app.jarvis.JarvisNote
import com.droidcraft.app.jarvis.JarvisViewModel
import com.droidcraft.app.jarvis.character.Emotion
import com.droidcraft.app.jarvis.character.EmotionEngine
import com.droidcraft.app.jarvis.character.EmotionStyle
import com.droidcraft.app.jarvis.voice.VoiceMode
import com.droidcraft.app.jarvis.voice.VoiceSettings
import com.droidcraft.app.jarvis.a11y.JarvisAccessibilityService
import com.droidcraft.app.jarvis.wake.WakeWordService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private object JarvisColors {
    val bg = Color(0xFF020617)
    val bgDeep = Color(0xFF070D1C)
    val cyan = Color(0xFF22D3EE)
    val blue = Color(0xFF38BDF8)
    val violet = Color(0xFFA78BFA)
    val green = Color(0xFF34D399)
    val slate = Color(0xFF94A3B8)
    val slateLight = Color(0xFFCBD5E1)
    val surface = Color(0xFF1E293B)
    val surfaceDeep = Color(0xFF0F172A)
}

private enum class ReactorState { IDLE, LISTENING, THINKING, SPEAKING }

@Composable
fun JarvisScreen(viewModel: JarvisViewModel = viewModel()) {
    val context = LocalContext.current
    val messages by viewModel.messages.collectAsState()
    val isThinking by viewModel.isThinking.collectAsState()
    val isSpeaking by viewModel.isSpeaking.collectAsState()
    val emotion by viewModel.emotion.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val notes by viewModel.notes.collectAsState()
    val autoSpeak by viewModel.autoSpeak.collectAsState()
    val userName by viewModel.userName.collectAsState()
    val handsFree by viewModel.handsFree.collectAsState()
    val memories by viewModel.memories.collectAsState()
    val liveOn by viewModel.liveOn.collectAsState()
    val liveStatus by viewModel.liveStatus.collectAsState()
    val liveText by viewModel.liveText.collectAsState()

    var input by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(false) }
    var showMemory by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var pendingFlashlight by remember { mutableStateOf<String?>(null) }
    var pendingContact by remember { mutableStateOf<String?>(null) }
    var showVoice by remember { mutableStateOf(false) }
    var a11yOn by remember { mutableStateOf(JarvisAccessibilityService.isEnabled(context)) }

    val listState = rememberLazyListState()
    val now = remember { SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date()) }

    val reactorState = when {
        isListening -> ReactorState.LISTENING
        isThinking -> ReactorState.THINKING
        isSpeaking -> ReactorState.SPEAKING
        else -> ReactorState.IDLE
    }
    val mood = EmotionEngine.style(if (isThinking) Emotion.THINKING else if (isSpeaking) Emotion.SPEAKING else emotion)

    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.setListening(false)
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) viewModel.handleUserText(spoken)
        }
    }

    val launchSpeech: () -> Unit = {
        viewModel.setListening(true)
        runCatching {
            speechLauncher.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Boliye... main sun raha hoon")
                }
            )
        }.onFailure { viewModel.setListening(false) }
    }

    val recordPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchSpeech() else viewModel.onMicDenied()
    }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingFlashlight
        pendingFlashlight = null
        if (pending != null) {
            if (granted) viewModel.handleUserText(pending)
            else viewModel.handleUserText("Flashlight ke liye camera permission zaroori hai.")
        }
    }

    fun startListening() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) launchSpeech() else recordPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.setHandsFree(true)
    }

    fun toggleHandsFree() {
        if (handsFree) {
            viewModel.setHandsFree(false)
        } else {
            val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) viewModel.setHandsFree(true)
            else notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingContact
        pendingContact = null
        if (pending != null) viewModel.handleUserText(pending)
        if (!granted) {
            viewModel.handleUserText("Contacts permission na hona theek hai — number seedha bhi bana sakte hain.")
        }
    }

    fun submit(text: String) {
        if (text.isBlank()) return
        val parsed = JarvisCommandParser.parse(text)
        val needsContactName = (parsed?.type == JarvisIntentType.CALL || parsed?.type == JarvisIntentType.SMS) &&
            !text.any { it.isDigit() }
        val contactsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (needsContactName && !contactsGranted) {
            pendingContact = text
            contactsPermission.launch(Manifest.permission.READ_CONTACTS)
            return
        }
        if (JarvisCommandParser.isFlashlight(text)) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) {
                viewModel.handleUserText(text)
            } else {
                pendingFlashlight = text
                cameraPermission.launch(Manifest.permission.CAMERA)
            }
        } else {
            viewModel.handleUserText(text)
        }
    }

    // "Hey Jarvis" wake hone par turant sunna shuru karo.
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == WakeWordService.ACTION_WAKE_DETECTED) startListening()
            }
        }
        val filter = IntentFilter(WakeWordService.ACTION_WAKE_DETECTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(messages.size - 1) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(JarvisColors.bg, JarvisColors.bgDeep)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            // ---------------- header ----------------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "J.A.R.V.I.S.",
                        color = JarvisColors.cyan,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp
                    )
                    Text(
                        if (liveOn) "LIVE • $liveStatus • $now" else "${mood.statusLabel} • ${statusText(reactorState)} • $now • ${userName.ifBlank { "sahab" }}",
                        color = if (liveOn) JarvisColors.green else JarvisColors.slate,
                        fontSize = 12.sp
                    )
                }
                IconButton(onClick = { viewModel.toggleAutoSpeak() }) {
                    Text(
                        if (autoSpeak) "🔊" else "🔇",
                        fontSize = 18.sp,
                        modifier = Modifier.padding(4.dp)
                    )
                }
                IconButton(onClick = {
                    a11yOn = JarvisAccessibilityService.isEnabled(context)
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }) {
                    Icon(
                        Icons.Default.Accessibility,
                        contentDescription = "Accessibility",
                        tint = if (a11yOn) JarvisColors.cyan else JarvisColors.slate
                    )
                }
                IconButton(onClick = { showVoice = true }) {
                    Icon(
                        Icons.Default.RecordVoiceOver,
                        contentDescription = "Voice settings",
                        tint = JarvisColors.slate
                    )
                }
                IconButton(onClick = { if (liveOn) viewModel.stopLive() else viewModel.startLive() }) {
                    Icon(
                        Icons.Default.GraphicEq,
                        contentDescription = "Live voice",
                        tint = if (liveOn) JarvisColors.green else JarvisColors.slate
                    )
                }
                IconButton(onClick = { toggleHandsFree() }) {
                    Icon(
                        Icons.Default.Hearing,
                        contentDescription = "Hey Jarvis",
                        tint = if (handsFree) JarvisColors.cyan else JarvisColors.slate
                    )
                }
                IconButton(onClick = { showNotes = true }) {
                    Icon(Icons.Default.Notes, contentDescription = "Notes", tint = JarvisColors.slate)
                }
                IconButton(onClick = { showMemory = true }) {
                    Icon(
                        Icons.Default.Psychology,
                        contentDescription = "Memory",
                        tint = if (memories.isNotEmpty()) JarvisColors.cyan else JarvisColors.slate
                    )
                }
                IconButton(onClick = { showKey = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = JarvisColors.slate)
                }
            }

            Spacer(Modifier.height(4.dp))

            // ---------------- arc reactor ----------------
            ArcReactor(
                state = reactorState,
                mood = mood,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
            )

            // ---------------- chat ----------------
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    MessageBubble(msg)
                }
            }

            if (liveOn && liveText.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = JarvisColors.green.copy(alpha = 0.10f)
                ) {
                    Text(
                        liveText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        color = JarvisColors.slateLight,
                        fontSize = 13.sp
                    )
                }
            }

            // ---------------- quick suggestions ----------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                suggestions.forEach { s ->
                    AssistChip(
                        onClick = { submit(s) },
                        label = { Text(s, fontSize = 12.sp) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = JarvisColors.surface,
                            labelColor = JarvisColors.slateLight
                        )
                    )
                }
            }

            // ---------------- input ----------------
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Hindi ya English mein likhiye...", fontSize = 14.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = JarvisColors.slateLight,
                        focusedBorderColor = JarvisColors.cyan,
                        unfocusedBorderColor = Color(0xFF334155),
                        cursorColor = JarvisColors.cyan,
                        focusedContainerColor = JarvisColors.surfaceDeep,
                        unfocusedContainerColor = JarvisColors.surfaceDeep
                    )
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(if (isListening) JarvisColors.cyan else JarvisColors.surface),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(onClick = { startListening() }) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = "Voice",
                            tint = if (isListening) JarvisColors.bg else JarvisColors.cyan
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                IconButton(
                    onClick = {
                        submit(input)
                        input = ""
                    },
                    enabled = input.isNotBlank()
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send", tint = JarvisColors.cyan)
                }
            }
        }
    }

    // ---------------- voice settings dialog ----------------
    if (showVoice) {
        VoiceSettingsDialog(onDismiss = { showVoice = false })
    }

    // ---------------- API key dialog ----------------
    if (showKey) {
        AlertDialog(
            onDismissRequest = { showKey = false },
            title = { Text("Gemini API key", color = Color.White) },
            text = {
                Column {
                    Text(
                        "Baat-cheet aur creator tasks ke liye API key daaliye (aistudio.google.com/apikey se free). Device ke kaam bina key ke bhi chalte hain.",
                        color = JarvisColors.slateLight,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        singleLine = true,
                        placeholder = { Text("AIza...") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = JarvisColors.slateLight,
                            focusedBorderColor = JarvisColors.cyan,
                            unfocusedBorderColor = Color(0xFF334155),
                            cursorColor = JarvisColors.cyan
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (keyInput.isNotBlank()) {
                        viewModel.saveGeminiKey(keyInput)
                        keyInput = ""
                    }
                    showKey = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showKey = false }) { Text("Close") }
            },
            containerColor = JarvisColors.surfaceDeep
        )
    }

    // ---------------- memory dialog ----------------
    if (showMemory) {
        AlertDialog(
            onDismissRequest = { showMemory = false },
            title = { Text("Jarvis ki Yaadein", color = Color.White) },
            text = {
                if (memories.isEmpty()) {
                    Text(
                        "Abhi kuch yaad nahi hai. \"Remember that mere bike ka number 1234 hai\" — aise likhkar batayiye.",
                        color = JarvisColors.slateLight
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        memories.forEach { fact ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = JarvisColors.surface.copy(alpha = 0.7f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        fact.value,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = { viewModel.forgetMemory(fact.id) }, modifier = Modifier.size(32.dp)) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Forget",
                                            tint = JarvisColors.slate,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (memories.isNotEmpty()) {
                    TextButton(onClick = { viewModel.clearMemory() }) { Text("Sab mitao") }
                } else {
                    TextButton(onClick = { showMemory = false }) { Text("Close") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showMemory = false }) { Text("Close") }
            },
            containerColor = JarvisColors.surfaceDeep
        )
    }

    // ---------------- notes dialog ----------------
    if (showNotes) {
        AlertDialog(
            onDismissRequest = { showNotes = false },
            title = { Text("Jarvis Notes", color = Color.White) },
            text = {
                if (notes.isEmpty()) {
                    Text("Abhi koi note nahi hai. 'Note karo: ...' likhkar banaiye.", color = JarvisColors.slateLight)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        notes.forEach { note ->
                            NoteRow(note) { viewModel.deleteNote(note.id) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showNotes = false }) { Text("Close") }
            },
            containerColor = JarvisColors.surfaceDeep
        )
    }
}

private val suggestions = listOf(
    "Mausam kaisa hai?",
    "Alarm 7 baje",
    "Timer 5 minute",
    "Battery kitni hai?",
    "Note karo: dua hai",
    "Video ideas",
    "Joke sunao",
    "Flashlight on"
)

private fun statusText(state: ReactorState): String = when (state) {
    ReactorState.LISTENING -> "SUN RAHA HAI"
    ReactorState.THINKING -> "SOCH RAHA HAI"
    ReactorState.SPEAKING -> "BOL RAHA HAI"
    ReactorState.IDLE -> "ONLINE"
}

// ---------------------------------------------------------------- reactor

@Composable
private fun ArcReactor(state: ReactorState, mood: EmotionStyle, modifier: Modifier = Modifier) {
    val coreColor = when (state) {
        ReactorState.IDLE -> JarvisColors.blue
        ReactorState.LISTENING -> JarvisColors.cyan
        ReactorState.THINKING -> JarvisColors.violet
        ReactorState.SPEAKING -> JarvisColors.green
    }
    // mood के साथ रंग बदले (happy/sad/angry...) — state से override
    val moodColor = Color(mood.avatarColor)

    val transition = rememberInfiniteTransition(label = "jarvis")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(7000, easing = LinearEasing), RepeatMode.Restart),
        label = "rotation"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    val glow by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow"
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f * 0.88f

            // bahar ki halki roshni
            drawCircle(
                color = moodColor.copy(alpha = 0.08f + 0.10f * glow),
                radius = radius * 1.2f,
                center = center
            )
            // outer ring
            drawCircle(
                color = coreColor.copy(alpha = 0.35f),
                radius = radius,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )
            // ghoomta hua arc segment
            rotate(degrees = rotation, pivot = center) {
                repeat(3) { i ->
                    drawArc(
                        brush = Brush.sweepGradient(
                            listOf(Color.Transparent, coreColor, Color.Transparent)
                        ),
                        startAngle = i * 120f,
                        sweepAngle = 90f,
                        useCenter = false,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = Size(radius * 2f, radius * 2f),
                        style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
            // ticks
            repeat(60) { i ->
                val angle = Math.toRadians((i * 6 + rotation * 2).toDouble())
                val inner = radius * 0.84f
                val outer = if (i % 5 == 0) radius * 0.75f else radius * 0.79f
                drawLine(
                    color = coreColor.copy(alpha = if (i % 5 == 0) 0.8f else 0.28f),
                    start = Offset(center.x + inner * cos(angle).toFloat(), center.y + inner * sin(angle).toFloat()),
                    end = Offset(center.x + outer * cos(angle).toFloat(), center.y + outer * sin(angle).toFloat()),
                    strokeWidth = if (i % 5 == 0) 2.dp.toPx() else 1.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
            // core
            val coreRadius = radius * 0.58f * pulse
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(moodColor, moodColor.copy(alpha = 0f)),
                    center = center,
                    radius = coreRadius
                ),
                radius = coreRadius,
                center = center
            )
            drawCircle(
                color = coreColor.copy(alpha = 0.7f),
                radius = radius * 0.62f * pulse,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )
            drawCircle(
                color = coreColor.copy(alpha = 0.9f),
                radius = radius * 0.14f * pulse,
                center = center
            )
        }
        Text(
            text = mood.avatarExpression,
            fontSize = 28.sp
        )
        Text(
            text = when (state) {
                ReactorState.IDLE -> "ONLINE"
                ReactorState.LISTENING -> "SUN RAHA HAI"
                ReactorState.THINKING -> "SOCH RAHA HAI"
                ReactorState.SPEAKING -> "BOL RAHA HAI"
            },
            color = coreColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            textAlign = TextAlign.Center
        )
    }
}

// ---------------------------------------------------------------- messages

@Composable
private fun MessageBubble(msg: JarvisMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!msg.isUser) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(JarvisColors.cyan.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Text("J", color = JarvisColors.cyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Spacer(Modifier.width(8.dp))
        }
        Surface(
            shape = RoundedCornerShape(
                topStart = 14.dp,
                topEnd = 14.dp,
                bottomStart = if (msg.isUser) 14.dp else 3.dp,
                bottomEnd = if (msg.isUser) 3.dp else 14.dp
            ),
            color = when {
                msg.isUser -> JarvisColors.cyan.copy(alpha = 0.16f)
                msg.kind == JarvisMessageKind.SYSTEM -> JarvisColors.blue.copy(alpha = 0.12f)
                else -> JarvisColors.surface
            }
        ) {
            Text(
                msg.text,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                color = if (msg.isUser) Color.White else JarvisColors.slateLight,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun NoteRow(note: JarvisNote, onDelete: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = JarvisColors.surface.copy(alpha = 0.7f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(note.text, color = Color.White, fontSize = 13.sp)
                Text(note.time, color = JarvisColors.slate, fontSize = 11.sp)
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = JarvisColors.slate, modifier = Modifier.size(16.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- voice settings

@Composable
private fun VoiceSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(VoiceSettings.mode(context)) }
    var elevenKey by remember { mutableStateOf(VoiceSettings.elevenKey(context)) }
    var voiceId by remember { mutableStateOf(VoiceSettings.elevenVoiceId(context)) }
    var cloneId by remember { mutableStateOf(VoiceSettings.cloneVoiceId(context)) }
    var geminiVoice by remember { mutableStateOf(VoiceSettings.geminiVoice(context)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Jarvis Voice Settings", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Voice Mode", color = JarvisColors.slateLight, fontSize = 12.sp)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        VoiceMode.PHONE_FREE to "Free – फ़ोन की आवाज़ (offline)",
                        VoiceMode.GEMINI_FREE to "Free – Gemini Voices",
                        VoiceMode.ELEVENLABS to "ElevenLabs (भावना के साथ)",
                        VoiceMode.SMALLEST to "Smallest AI",
                        VoiceMode.HEYGEN to "HeyGen",
                        VoiceMode.SMART to "Smart (भावना = ElevenLabs, बाकी free)"
                    ).forEach { (m, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = mode == m,
                                onClick = {
                                    mode = m
                                    VoiceSettings.setMode(context, m)
                                }
                            )
                            Text(label, color = JarvisColors.slateLight, fontSize = 12.sp)
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFF334155))

                Text("ElevenLabs API key (encrypted)", color = JarvisColors.slateLight, fontSize = 12.sp)
                OutlinedTextField(
                    value = elevenKey, onValueChange = { elevenKey = it },
                    singleLine = true, placeholder = { Text("ElevenLabs key...") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = JarvisColors.slateLight,
                        focusedBorderColor = JarvisColors.cyan,
                        unfocusedBorderColor = Color(0xFF334155),
                        cursorColor = JarvisColors.cyan
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Voice ID (default: Bunty - Funny Best Friend)", color = JarvisColors.slateLight, fontSize = 12.sp)
                OutlinedTextField(
                    value = voiceId, onValueChange = {
                        voiceId = it
                        VoiceSettings.setElevenVoiceId(context, it)
                    },
                    singleLine = true, placeholder = { Text("7b9mYhmnp0y2qSH1FnBL") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = JarvisColors.slateLight,
                        focusedBorderColor = JarvisColors.cyan,
                        unfocusedBorderColor = Color(0xFF334155),
                        cursorColor = JarvisColors.cyan
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Text("मेरी clone voice ID (Smallest)", color = JarvisColors.slateLight, fontSize = 12.sp)
                OutlinedTextField(
                    value = cloneId, onValueChange = {
                        cloneId = it
                        VoiceSettings.setCloneVoiceId(context, it)
                    },
                    singleLine = true, placeholder = { Text("अपनी cloned voice ID") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = JarvisColors.slateLight,
                        focusedBorderColor = JarvisColors.cyan,
                        unfocusedBorderColor = Color(0xFF334155),
                        cursorColor = JarvisColors.cyan
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Gemini voice (Free – Gemini Voices mode)", color = JarvisColors.slateLight, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    VoiceSettings.GEMINI_VOICES.take(5).forEach { v ->
                        FilterChip(
                            selected = geminiVoice == v,
                            onClick = {
                                geminiVoice = v
                                VoiceSettings.setGeminiVoice(context, v)
                            },
                            label = { Text(v, fontSize = 11.sp) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (elevenKey.isNotBlank()) VoiceSettings.setElevenKey(context, elevenKey)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        containerColor = JarvisColors.surfaceDeep
    )
}
