package com.droidcraft.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.droidcraft.app.jarvis.JarvisMessage
import kotlinx.coroutines.delay
import com.droidcraft.app.jarvis.JarvisViewModel
import com.droidcraft.app.model.LiveConversationMessage
import com.droidcraft.app.model.LiveConversationStatus
import com.droidcraft.app.model.LiveVoiceMode
import com.droidcraft.app.viewmodel.YouTubeAutomationViewModel
import org.json.JSONObject
import java.util.Locale

private const val JARVIS_HUD_TAG = "JarvisHUD"
private val HudRed = Color(0xFFFF1744)
private val HudSurface = Color(0xFF0C1018)
private val HudMuted = Color(0xFFBAC3D0)

/** The supplied Mark 85 HUD is the primary Jarvis screen; its controls call into native Android. */
@Composable
fun JarvisScreen(
    liveViewModel: YouTubeAutomationViewModel,
    onOpenStudio: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenMore: () -> Unit
) {
    val context = LocalContext.current
    val jarvisViewModel: JarvisViewModel = viewModel()
    val liveStatus by liveViewModel.liveStatus.collectAsState()
    val liveNotice by liveViewModel.liveNotice.collectAsState()
    val liveMessages by liveViewModel.liveMessages.collectAsState()
    val liveUserDraft by liveViewModel.liveUserDraft.collectAsState()
    val liveAssistantDraft by liveViewModel.liveAssistantDraft.collectAsState()
    val selectedVoice by liveViewModel.liveVoiceMode.collectAsState()
    val effectiveVoice by liveViewModel.liveEffectiveVoice.collectAsState()
    val voiceFallback by liveViewModel.liveVoiceFallback.collectAsState()
    val hasGeminiKey by liveViewModel.hasGeminiKey.collectAsState()
    val jarvisMessages by jarvisViewModel.messages.collectAsState()
    val jarvisVoiceNotice by jarvisViewModel.voiceNotice.collectAsState()

    var showHistory by remember { mutableStateOf(false) }
    var hudLoading by remember { mutableStateOf(true) }
    var hudReady by remember { mutableStateOf(false) }
    var hudError by remember { mutableStateOf<String?>(null) }
    val webViewState = remember { mutableStateOf<WebView?>(null) }
    val reportHudError = rememberUpdatedState(newValue = { message: String ->
        Log.e(JARVIS_HUD_TAG, message)
        if (!hudReady) {
            hudLoading = false
            hudError = message
        }
    })

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            liveViewModel.startLiveConversation()
        } else {
            liveViewModel.showLiveNotice("Microphone permission was denied. Allow microphone access in Android Settings to use Live Talk.")
        }
    }

    val startLive = rememberUpdatedState(newValue = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            liveViewModel.startLiveConversation()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    })
    val toggleLive = rememberUpdatedState(newValue = {
        if (liveStatus.isActive()) liveViewModel.stopLiveConversation() else startLive.value.invoke()
    })
    val openHistory = rememberUpdatedState(newValue = { showHistory = true })
    val openStudio = rememberUpdatedState(newValue = onOpenStudio)
    val openSettings = rememberUpdatedState(newValue = onOpenSettings)
    val openHistoryOrStart = rememberUpdatedState(newValue = {
        if (liveStatus.isActive()) openHistory.value.invoke() else startLive.value.invoke()
    })

    LaunchedEffect(hasGeminiKey) {
        // Settings and Jarvis text commands share the same Keystore-backed Gemini key.
        jarvisViewModel.reloadGeminiKey()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                val mainHandler = Handler(Looper.getMainLooper())
                WebView(viewContext).apply {
                    setBackgroundColor(android.graphics.Color.rgb(5, 1, 2))
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = false
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                            val detail = "${message.messageLevel()}: ${message.message()} " +
                                "(${message.sourceId()}:${message.lineNumber()})"
                            when (message.messageLevel()) {
                                ConsoleMessage.MessageLevel.ERROR -> {
                                    Log.e(JARVIS_HUD_TAG, detail)
                                    reportHudError.value(detail)
                                }
                                ConsoleMessage.MessageLevel.WARNING -> Log.w(JARVIS_HUD_TAG, detail)
                                else -> Log.d(JARVIS_HUD_TAG, detail)
                            }
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            hudLoading = true
                            hudReady = false
                            hudError = null
                            Log.i(JARVIS_HUD_TAG, "Loading HUD page: $url")
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            val page = view ?: return
                            val readinessCheck = "(function(){" +
                                "var core=document.querySelector('.reactor-housing');" +
                                "if(!window.__JARVIS_HUD_READY||!core)return false;" +
                                "var r=core.getBoundingClientRect();return r.width>0&&r.height>0;" +
                                "})()"
                            page.evaluateJavascript(readinessCheck) { result ->
                                val ready = result == "true"
                                hudLoading = false
                                hudReady = ready
                                if (ready) {
                                    hudError = null
                                    Log.i(JARVIS_HUD_TAG, "HUD JavaScript initialized and reactor is laid out")
                                } else {
                                    val message = "HUD page loaded, but its JavaScript UI did not initialize. " +
                                        "Check Logcat tag $JARVIS_HUD_TAG."
                                    hudError = message
                                    Log.e(JARVIS_HUD_TAG, message)
                                }
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            if (request?.isForMainFrame == true) {
                                val message = "HUD page load failed (${error?.errorCode}): ${error?.description}"
                                Log.e(JARVIS_HUD_TAG, message)
                                hudLoading = false
                                hudReady = false
                                hudError = message
                            }
                        }

                        override fun onRenderProcessGone(
                            view: WebView,
                            detail: RenderProcessGoneDetail
                        ): Boolean {
                            val message = "Android WebView renderer exited (crashed=${detail.didCrash()})."
                            Log.e(JARVIS_HUD_TAG, message)
                            hudLoading = false
                            hudReady = false
                            hudError = message
                            return true
                        }

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            // The HUD is a bundled, local asset; never let its WebView navigate off-device.
                            return request?.url?.scheme != "file"
                        }
                    }
                    addJavascriptInterface(
                        JarvisHudBridge(
                            mainHandler = mainHandler,
                            orbClickAction = openHistoryOrStart,
                            micClickAction = openHistoryOrStart,
                            powerClickAction = toggleLive,
                            studioClickAction = openStudio,
                            historyClickAction = openHistory,
                            settingsClickAction = openSettings,
                            hudErrorAction = reportHudError
                        ),
                        "JarvisBridge"
                    )
                    webViewState.value = this
                    Log.i(
                        JARVIS_HUD_TAG,
                        "WebView config: JavaScript=${settings.javaScriptEnabled}, DOM storage=${settings.domStorageEnabled}"
                    )
                    loadUrl("file:///android_asset/jarvis-hud/index.html")
                }
            },
            update = { view ->
                if (webViewState.value !== view) webViewState.value = view
            }
        )

        DisposableEffect(Unit) {
            onDispose {
                webViewState.value?.let { view ->
                    runCatching {
                        view.stopLoading()
                        view.removeJavascriptInterface("JarvisBridge")
                        view.destroy()
                    }.onFailure { Log.w(JARVIS_HUD_TAG, "Failed to dispose WebView cleanly", it) }
                }
                webViewState.value = null
            }
        }

        LaunchedEffect(webViewState.value, hudLoading) {
            val page = webViewState.value ?: return@LaunchedEffect
            if (!hudLoading) return@LaunchedEffect
            delay(12_000)
            if (webViewState.value === page && hudLoading && !hudReady && hudError == null) {
                hudLoading = false
                hudError = "Timed out waiting for the Jarvis HUD to initialize. Check Logcat tag $JARVIS_HUD_TAG."
                Log.e(JARVIS_HUD_TAG, hudError.orEmpty())
            }
        }

        LaunchedEffect(webViewState.value, liveStatus, effectiveVoice, selectedVoice) {
            val view = webViewState.value ?: return@LaunchedEffect
            val (orbState, statusLabel, caption) = when (liveStatus) {
                LiveConversationStatus.CONNECTING -> Triple("thinking", "CONNECTING", "CONNECTING TO GEMINI LIVE...")
                LiveConversationStatus.LISTENING -> Triple("listening", "LIVE", "LIVE TALK • SPEAK NATURALLY")
                LiveConversationStatus.SPEAKING -> Triple("speaking", "LIVE", "JARVIS IS REPLYING")
                LiveConversationStatus.ERROR -> Triple("idle", "ERROR", "CONNECTION FAILED • SEE DETAILS BELOW")
                LiveConversationStatus.IDLE -> Triple("idle", "READY", "SYSTEM ONLINE • TAP TO START LIVE TALK")
            }
            val voiceCaption = when (effectiveVoice ?: selectedVoice) {
                LiveVoiceMode.ELEVENLABS -> "ELEVENLABS • BUNTY VOICE"
                LiveVoiceMode.GEMINI_LIVE -> "GEMINI LIVE • FREE VOICE"
            }
            val finalCaption = if (liveStatus == LiveConversationStatus.LISTENING || liveStatus == LiveConversationStatus.SPEAKING) {
                voiceCaption
            } else caption
            val js = "window.setOrbState(${JSONObject.quote(orbState)},0);" +
                "window.setConnectionStatus(${JSONObject.quote(statusLabel)},${JSONObject.quote(finalCaption)});" +
                "window.setPowerState(${liveStatus.isActive()});"
            view.post {
                if (view.isAttachedToWindow) {
                    view.evaluateJavascript(js) { result ->
                        if (result == null || result == "null") {
                            Log.w(JARVIS_HUD_TAG, "WebView did not return a result for native HUD state update")
                        }
                    }
                }
            }
        }

        if (hudLoading || hudError != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(20.dp),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xF20C1018),
                tonalElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (hudLoading) {
                        CircularProgressIndicator(color = HudRed, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                        Text("Loading Jarvis HUD…", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("Preparing the local interface. Navigation remains available below.", color = HudMuted)
                    } else {
                        Text("Jarvis HUD could not start", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(hudError.orEmpty(), color = HudMuted, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                hudReady = false
                                hudLoading = true
                                hudError = null
                                webViewState.value?.let { page ->
                                    page.stopLoading()
                                    page.reload()
                                }
                            }) { Text("Retry") }
                            TextButton(onClick = onOpenChat) { Text("Open Chat") }
                        }
                    }
                }
            }
        }

        if (!liveNotice.isNullOrBlank() || !jarvisVoiceNotice.isNullOrBlank()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .align(Alignment.BottomCenter),
            shape = RoundedCornerShape(14.dp),
            color = Color(0xEE10141C),
            tonalElevation = 6.dp
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    liveNotice?.takeIf { it.isNotBlank() } ?: jarvisVoiceNotice.orEmpty(),
                    color = if (liveStatus == LiveConversationStatus.ERROR) Color(0xFFFF9AA8) else HudMuted,
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (liveStatus == LiveConversationStatus.ERROR && !liveNotice.isNullOrBlank()) {
                        TextButton(onClick = { startLive.value.invoke() }) { Text("Retry", color = HudRed) }
                    }
                    TextButton(onClick = { openSettings.value.invoke() }) { Text("Settings", color = HudMuted) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        if (!liveNotice.isNullOrBlank()) liveViewModel.showLiveNotice("")
                        else jarvisViewModel.dismissVoiceNotice()
                    }) { Text("Dismiss", color = HudMuted) }
                }
            }
        }
    }

    if (showHistory) {
        JarvisHistoryDialog(
            liveStatus = liveStatus,
            liveNotice = liveNotice,
            voiceNotice = jarvisVoiceNotice,
            liveMessages = liveMessages,
            liveUserDraft = liveUserDraft,
            liveAssistantDraft = liveAssistantDraft,
            jarvisMessages = jarvisMessages,
            selectedVoice = selectedVoice,
            effectiveVoice = effectiveVoice,
            voiceFallback = voiceFallback,
            hasGeminiKey = hasGeminiKey,
            onDismiss = { showHistory = false },
            onToggleLive = { toggleLive.value.invoke() },
            onSend = { text ->
                if (liveStatus.isActive()) {
                    liveViewModel.sendLiveText(text)
                } else {
                    jarvisViewModel.handleUserText(text)
                    true
                }
            },
            onOpenSettings = { openSettings.value.invoke() },
            onDismissVoiceNotice = { jarvisViewModel.dismissVoiceNotice() },
            onOpenChat = onOpenChat,
            onOpenMore = onOpenMore
        )
    }
    }
}

private class JarvisHudBridge(
    private val mainHandler: Handler,
    private val orbClickAction: androidx.compose.runtime.State<() -> Unit>,
    private val micClickAction: androidx.compose.runtime.State<() -> Unit>,
    private val powerClickAction: androidx.compose.runtime.State<() -> Unit>,
    private val studioClickAction: androidx.compose.runtime.State<() -> Unit>,
    private val historyClickAction: androidx.compose.runtime.State<() -> Unit>,
    private val settingsClickAction: androidx.compose.runtime.State<() -> Unit>,
    private val hudErrorAction: androidx.compose.runtime.State<(String) -> Unit>
) {
    @JavascriptInterface fun onOrbClicked() = dispatch(orbClickAction)
    @JavascriptInterface fun onMicClicked() = dispatch(micClickAction)
    @JavascriptInterface fun onPowerClicked() = dispatch(powerClickAction)
    @JavascriptInterface fun onScreenShareClicked() = dispatch(studioClickAction)
    @JavascriptInterface fun onHistoryClicked() = dispatch(historyClickAction)
    @JavascriptInterface fun onSettingsClicked() = dispatch(settingsClickAction)

    @JavascriptInterface
    fun onHudError(message: String) {
        val safeMessage = message.take(1200)
        Log.e(JARVIS_HUD_TAG, "JavaScript reported HUD error: $safeMessage")
        mainHandler.post { hudErrorAction.value.invoke(safeMessage) }
    }

    private fun dispatch(action: androidx.compose.runtime.State<() -> Unit>) {
        mainHandler.post { action.value.invoke() }
    }
}

@Composable
private fun JarvisHistoryDialog(
    liveStatus: LiveConversationStatus,
    liveNotice: String?,
    voiceNotice: String?,
    liveMessages: List<LiveConversationMessage>,
    liveUserDraft: String,
    liveAssistantDraft: String,
    jarvisMessages: List<JarvisMessage>,
    selectedVoice: LiveVoiceMode,
    effectiveVoice: LiveVoiceMode?,
    voiceFallback: Boolean,
    hasGeminiKey: Boolean,
    onDismiss: () -> Unit,
    onToggleLive: () -> Unit,
    onSend: (String) -> Boolean,
    onOpenSettings: () -> Unit,
    onDismissVoiceNotice: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenMore: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val visibleJarvisMessages = jarvisMessages.takeLast(80)
    val visibleLiveMessages = liveMessages.takeLast(100)
    val hasLiveRows = liveStatus != LiveConversationStatus.IDLE || visibleLiveMessages.isNotEmpty() ||
        liveUserDraft.isNotBlank() || liveAssistantDraft.isNotBlank()
    val activeVoice = effectiveVoice ?: selectedVoice
    val historyNotice = liveNotice?.takeIf { it.isNotBlank() } ?: voiceNotice

    LaunchedEffect(visibleJarvisMessages.size, visibleLiveMessages.size, liveUserDraft, liveAssistantDraft) {
        val count = visibleJarvisMessages.size + visibleLiveMessages.size +
            (if (hasLiveRows) 1 else 0) +
            (if (liveUserDraft.isNotBlank()) 1 else 0) +
            (if (liveAssistantDraft.isNotBlank()) 1 else 0) +
            (if (visibleJarvisMessages.isNotEmpty()) 1 else 0)
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .padding(horizontal = 14.dp, vertical = 18.dp),
            shape = RoundedCornerShape(22.dp),
            color = HudSurface
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("JARVIS HISTORY", color = Color.White, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Text(
                            when {
                                liveStatus == LiveConversationStatus.CONNECTING -> "Connecting to Gemini Live…"
                                liveStatus == LiveConversationStatus.LISTENING -> "Listening • ${activeVoice.title}"
                                liveStatus == LiveConversationStatus.SPEAKING -> "Jarvis is replying • ${activeVoice.title}"
                                liveStatus == LiveConversationStatus.ERROR -> "Live Talk disconnected"
                                else -> "Type a message or start Live Talk"
                            },
                            color = HudMuted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close history", tint = HudMuted)
                    }
                }

                if (liveStatus == LiveConversationStatus.CONNECTING) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = HudRed)
                        Text("Connecting… typed messages will be sent when the session is ready.", color = HudMuted, fontSize = 12.sp)
                    }
                }
                if (!historyNotice.isNullOrBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            historyNotice,
                            modifier = Modifier.weight(1f),
                            color = if (liveStatus == LiveConversationStatus.ERROR && !liveNotice.isNullOrBlank()) {
                                Color(0xFFFF9AA8)
                            } else HudMuted,
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (liveNotice.isNullOrBlank() && !voiceNotice.isNullOrBlank()) {
                            TextButton(onClick = onDismissVoiceNotice) { Text("Dismiss", color = HudMuted) }
                        }
                    }
                }
                if (hasLiveRows) {
                    Text(
                        if (voiceFallback && selectedVoice == LiveVoiceMode.ELEVENLABS && activeVoice == LiveVoiceMode.GEMINI_LIVE) {
                            "LIVE TALK • GEMINI LIVE (FREE FALLBACK)"
                        } else {
                            "LIVE TALK • ${activeVoice.title.uppercase(Locale.ROOT)}"
                        },
                        color = HudRed,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (visibleJarvisMessages.isNotEmpty()) {
                        item(key = "jarvis-history-header") {
                            Text("JARVIS CHAT", color = HudMuted, style = MaterialTheme.typography.labelSmall)
                        }
                        items(visibleJarvisMessages, key = { "jarvis-${it.id}" }) { message ->
                            HistoryBubble(message.text, message.isUser)
                        }
                    }
                    if (hasLiveRows) {
                        item(key = "live-history-header") {
                            Text("LIVE CONVERSATION", color = HudMuted, style = MaterialTheme.typography.labelSmall)
                        }
                        items(visibleLiveMessages, key = { "live-${it.id}" }) { message ->
                            HistoryBubble(message.text, message.isUser)
                        }
                        if (liveUserDraft.isNotBlank()) {
                            item(key = "live-user-draft") { HistoryBubble(liveUserDraft, isUser = true, isDraft = true) }
                        }
                        if (liveAssistantDraft.isNotBlank()) {
                            item(key = "live-assistant-draft") { HistoryBubble(liveAssistantDraft, isUser = false, isDraft = true) }
                        }
                    }
                    if (visibleJarvisMessages.isEmpty() && !hasLiveRows) {
                        item(key = "history-empty") {
                            Text(
                                "No messages yet. Start a live conversation or type a message below.",
                                color = HudMuted,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onToggleLive) {
                        Icon(Icons.Default.Mic, contentDescription = null, tint = HudRed)
                        Text(
                            if (liveStatus.isActive()) " End Live" else " Start Live",
                            color = HudRed
                        )
                    }
                    TextButton(onClick = onOpenChat) { Text("Chat", color = HudMuted) }
                    TextButton(onClick = onOpenMore) { Text("More", color = HudMuted) }
                    Spacer(Modifier.weight(1f))
                    if (!hasGeminiKey) TextButton(onClick = onOpenSettings) { Text("Add Gemini key", color = HudRed) }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = {
                            Text(
                                if (liveStatus.isActive()) "Type to Jarvis during Live Talk…" else "Type to Jarvis…",
                                color = Color(0xFF8D98A8)
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedPlaceholderColor = Color(0xFF8D98A8),
                            unfocusedPlaceholderColor = Color(0xFF8D98A8),
                            focusedBorderColor = HudRed,
                            unfocusedBorderColor = Color(0xFF475569),
                            cursorColor = HudRed,
                            focusedContainerColor = Color(0xFF111822),
                            unfocusedContainerColor = Color(0xFF111822)
                        )
                    )
                    IconButton(
                        onClick = {
                            val message = input.trim()
                            if (message.isNotEmpty() && onSend(message)) {
                                input = ""
                            }
                        },
                        enabled = input.isNotBlank()
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send message", tint = HudRed)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryBubble(text: String, isUser: Boolean, isDraft: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (isUser) Color(0xFF6D1E2C) else Color(0xFF19212C),
            tonalElevation = if (isDraft) 0.dp else 1.dp
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                Text(
                    if (isUser) "YOU" else "JARVIS",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isUser) Color(0xFFFFC3CC) else Color(0xFFFF647C)
                )
                Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun LiveConversationStatus.isActive(): Boolean = when (this) {
    LiveConversationStatus.CONNECTING,
    LiveConversationStatus.LISTENING,
    LiveConversationStatus.SPEAKING -> true
    LiveConversationStatus.IDLE,
    LiveConversationStatus.ERROR -> false
}
