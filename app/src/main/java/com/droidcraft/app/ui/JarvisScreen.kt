package com.droidcraft.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.Geocoder
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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.droidcraft.app.jarvis.HudWeatherRepository
import com.droidcraft.app.jarvis.JarvisMessage
import com.droidcraft.app.jarvis.JarvisViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.droidcraft.app.model.LiveConversationMessage
import com.droidcraft.app.model.LiveConversationStatus
import com.droidcraft.app.model.LiveVoiceMode
import com.droidcraft.app.viewmodel.YouTubeAutomationViewModel
import org.json.JSONObject
import java.util.Locale

private const val JARVIS_HUD_TAG = "JarvisHUD"
private val HudCyan = Color(0xFF56EFF7)
private val HudRed = Color(0xFFFF4964)
private val HudSurface = Color(0xFF081421)
private val HudMuted = Color(0xFFA3BAC6)

/** The supplied Mark 85 HUD is the primary Jarvis screen; its controls call into native Android. */
@Composable
fun JarvisScreen(
    liveViewModel: YouTubeAutomationViewModel,
    onOpenStudio: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenMore: () -> Unit,
    onOpenMemories: () -> Unit,
    onOpenLanguage: () -> Unit,
    isVisible: Boolean
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var activityVisible by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
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
    val jarvisThinking by jarvisViewModel.isThinking.collectAsState()
    val jarvisSpeaking by jarvisViewModel.isSpeaking.collectAsState()
    val jarvisListening by jarvisViewModel.isListening.collectAsState()

    var showHistory by remember { mutableStateOf(false) }
    var hudLoading by remember { mutableStateOf(true) }
    var hudReady by remember { mutableStateOf(false) }
    var hudError by remember { mutableStateOf<String?>(null) }
    var weatherPlace by remember { mutableStateOf("Local weather") }
    var weatherValue by remember { mutableStateOf("Tap to enable") }
    var weatherLoading by remember { mutableStateOf(false) }
    val weatherScope = rememberCoroutineScope()
    val webViewState = remember { mutableStateOf<WebView?>(null) }
    val reportHudError = rememberUpdatedState(newValue = { message: String ->
        Log.e(JARVIS_HUD_TAG, message)
        if (!hudReady) {
            hudLoading = false
            hudError = message
        }
    })

    val loadWeatherForLocation = rememberUpdatedState(newValue = { location: Location ->
        weatherLoading = true
        weatherPlace = "Your area"
        weatherValue = "Finding conditions…"
        weatherScope.launch {
            try {
                val weather = withContext(Dispatchers.IO) {
                    val place = resolveLocality(context, location)
                    HudWeatherRepository().current(location.latitude, location.longitude, place)
                }
                weatherPlace = weather.place
                weatherValue = "${weather.temperatureC}°C · ${weather.condition}"
                webViewState.value?.evaluateJavascript(
                    "window.setHudFeedback(${JSONObject.quote("Weather updated · ${weather.place}")});",
                    null
                )
            } catch (error: Exception) {
                weatherPlace = "Local weather"
                weatherValue = "Unavailable · tap to retry"
                Log.w(JARVIS_HUD_TAG, "Location weather request failed: ${error.message}")
            } finally {
                weatherLoading = false
            }
        }
    })
    val resolveCurrentLocation = rememberUpdatedState(newValue = {
        requestSingleHudLocation(
            context = context,
            onLocation = { location -> loadWeatherForLocation.value(location) },
            onUnavailable = {
                weatherLoading = false
                weatherPlace = "Location unavailable"
                weatherValue = "Turn on Location and retry"
            }
        )
    })
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) {
            resolveCurrentLocation.value()
        } else {
            weatherPlace = "Location is off"
            weatherValue = "Allow approximate location to continue"
        }
    }
    val requestWeather = rememberUpdatedState(newValue = {
        val hasLocationPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (hasLocationPermission) {
            resolveCurrentLocation.value()
        } else {
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION))
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
    val openMemories = rememberUpdatedState(newValue = onOpenMemories)
    val openLanguage = rememberUpdatedState(newValue = onOpenLanguage)
    val openHistoryOrStart = rememberUpdatedState(newValue = {
        if (liveStatus.isActive()) openHistory.value.invoke() else startLive.value.invoke()
    })

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> activityVisible = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> activityVisible = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
                    setBackgroundColor(android.graphics.Color.rgb(5, 11, 21))
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
                            memoriesClickAction = openMemories,
                            languageClickAction = openLanguage,
                            weatherClickAction = requestWeather,
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

        LaunchedEffect(webViewState.value, hudReady, isVisible, activityVisible) {
            val page = webViewState.value ?: return@LaunchedEffect
            if (!hudReady) return@LaunchedEffect
            val shouldAnimate = isVisible && activityVisible
            page.evaluateJavascript("window.setHudVisible($shouldAnimate);", null)
        }

        LaunchedEffect(webViewState.value, hudReady, weatherPlace, weatherValue, weatherLoading) {
            val page = webViewState.value ?: return@LaunchedEffect
            if (!hudReady) return@LaunchedEffect
            val js = "window.setHudWeather(" +
                "${JSONObject.quote(weatherPlace)}," +
                "${JSONObject.quote(weatherValue)}," +
                "$weatherLoading);"
            page.evaluateJavascript(js, null)
        }

        LaunchedEffect(webViewState.value, hudReady, liveStatus, effectiveVoice, selectedVoice, jarvisThinking, jarvisSpeaking, jarvisListening) {
            val view = webViewState.value ?: return@LaunchedEffect
            if (!hudReady) return@LaunchedEffect
            val (orbState, statusLabel, caption) = when {
                liveStatus == LiveConversationStatus.CONNECTING -> Triple("thinking", "CONNECTING", "CONNECTING TO GEMINI LIVE...")
                liveStatus == LiveConversationStatus.LISTENING -> Triple("listening", "LIVE", "LIVE TALK • SPEAK NATURALLY")
                liveStatus == LiveConversationStatus.SPEAKING -> Triple("speaking", "LIVE", "JARVIS IS REPLYING")
                liveStatus == LiveConversationStatus.ERROR -> Triple("idle", "ERROR", "CONNECTION FAILED • SEE DETAILS BELOW")
                jarvisListening -> Triple("listening", "LISTENING", "VOICE INPUT ACTIVE • SPEAK NATURALLY")
                jarvisThinking -> Triple("thinking", "THINKING", "PARSING REQUEST • STAND BY")
                jarvisSpeaking -> Triple("speaking", "SPEAKING", "JARVIS IS TRANSMITTING AUDIO")
                else -> Triple("idle", "READY", "SYSTEM ONLINE • TAP TO START LIVE TALK")
            }
            val voiceCaption = when (effectiveVoice ?: selectedVoice) {
                LiveVoiceMode.ELEVENLABS -> "ELEVENLABS • BUNTY VOICE"
                LiveVoiceMode.GEMINI_LIVE -> "GEMINI TTS • FREE PRESET"
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

        if (hudError != null) {
            NativeHudFallback(
                error = hudError.orEmpty(),
                isLiveActive = liveStatus.isActive(),
                onRetry = {
                    hudReady = false
                    hudLoading = true
                    hudError = null
                    webViewState.value?.let { page ->
                        page.stopLoading()
                        page.reload()
                    }
                },
                onToggleLive = { toggleLive.value.invoke() },
                onOrbClicked = { openHistoryOrStart.value.invoke() },
                onOpenHistory = { openHistory.value.invoke() },
                onOpenStudio = { openStudio.value.invoke() },
                onOpenMemories = { openMemories.value.invoke() },
                onOpenLanguage = { openLanguage.value.invoke() },
                onOpenSettings = { openSettings.value.invoke() },
                onOpenChat = onOpenChat
            )
        } else if (hudLoading) {
            Surface(
                modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(20.dp),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xF2081421),
                tonalElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(color = HudCyan, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    Text("Loading Jarvis HUD…", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Preparing the local interface. Navigation remains available below.", color = HudMuted)
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
            color = Color(0xEE081421),
            tonalElevation = 6.dp
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    liveNotice?.takeIf { it.isNotBlank() } ?: jarvisVoiceNotice.orEmpty(),
                    color = if (liveStatus == LiveConversationStatus.ERROR) MaterialTheme.colorScheme.error else HudMuted,
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

@Composable
private fun NativeHudFallback(
    error: String,
    isLiveActive: Boolean,
    onRetry: () -> Unit,
    onToggleLive: () -> Unit,
    onOrbClicked: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenStudio: () -> Unit,
    onOpenMemories: () -> Unit,
    onOpenLanguage: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenChat: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("J . A . R . V . I . S", style = MaterialTheme.typography.titleMedium, color = HudCyan, fontWeight = FontWeight.Bold)
                Text("NATIVE RECOVERY INTERFACE", style = MaterialTheme.typography.labelSmall, color = HudMuted)
            }
            Surface(shape = RoundedCornerShape(99.dp), color = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    "WEB HUD OFFLINE",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        NativeFallbackOrb(onClick = onOrbClicked)
        Text("NATIVE HUD READY", style = MaterialTheme.typography.labelMedium, color = HudCyan, fontWeight = FontWeight.Bold)
        Text(
            "The animated WebView core could not start. Your voice controls and quick actions are still available here.",
            style = MaterialTheme.typography.bodySmall,
            color = HudMuted
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(15.dp),
            color = MaterialTheme.colorScheme.errorContainer
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("HUD startup issue", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.SemiBold)
                Text(error.take(320), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                TextButton(onClick = onRetry) { Text("Retry WebView HUD") }
            }
        }

        Button(onClick = onToggleLive, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Mic, contentDescription = null, tint = HudRed)
            Spacer(Modifier.width(8.dp))
            Text(if (isLiveActive) "End Live Talk" else "Start Live Talk")
        }
        OutlinedButton(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) {
            Text("Open history & type to Jarvis")
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = onOpenChat) { Text("Chat") }
            TextButton(onClick = onOpenStudio) { Text("Screen tools") }
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = onOpenMemories) { Text("Memories") }
            TextButton(onClick = onOpenLanguage) { Text("Language") }
        }
    }
}

@Composable
private fun NativeFallbackOrb(onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "native-fallback-orb")
    val pulse by transition.animateFloat(
        initialValue = .92f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(1800), repeatMode = RepeatMode.Reverse),
        label = "core-pulse"
    )
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(18000, easing = androidx.compose.animation.core.LinearEasing)),
        label = "ring-rotation"
    )
    val amber = MaterialTheme.colorScheme.tertiary
    Box(
        modifier = Modifier.size(230.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension * .38f
            drawCircle(HudCyan.copy(alpha = .09f), radius = radius * pulse)
            drawCircle(HudCyan.copy(alpha = .18f), radius = radius * .85f, style = Stroke(1.dp.toPx()))
            drawCircle(HudCyan.copy(alpha = .42f), radius = radius * .72f, style = Stroke(1.dp.toPx(), pathEffect = null))
            drawArc(
                color = HudCyan.copy(alpha = .88f),
                startAngle = rotation,
                sweepAngle = 76f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            )
            drawArc(
                color = amber.copy(alpha = .85f),
                startAngle = -rotation * .72f,
                sweepAngle = 34f,
                useCenter = false,
                topLeft = Offset(center.x - radius * .62f, center.y - radius * .62f),
                size = Size(radius * 1.24f, radius * 1.24f),
                style = Stroke(width = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            )
            drawCircle(Color(0xFFDCFFFF), radius = size.minDimension * .075f * pulse)
            drawCircle(HudCyan.copy(alpha = .5f), radius = size.minDimension * .16f, style = Stroke(1.5.dp.toPx()))
        }
        Text("CORE", style = MaterialTheme.typography.labelSmall, color = Color(0xFF001418), fontWeight = FontWeight.Bold)
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
    private val memoriesClickAction: androidx.compose.runtime.State<() -> Unit>,
    private val languageClickAction: androidx.compose.runtime.State<() -> Unit>,
    private val weatherClickAction: androidx.compose.runtime.State<() -> Unit>,
    private val hudErrorAction: androidx.compose.runtime.State<(String) -> Unit>
) {
    @JavascriptInterface fun onOrbClicked() = dispatch(orbClickAction)
    @JavascriptInterface fun onMicClicked() = dispatch(micClickAction)
    @JavascriptInterface fun onPowerClicked() = dispatch(powerClickAction)
    @JavascriptInterface fun onScreenShareClicked() = dispatch(studioClickAction)
    @JavascriptInterface fun onHistoryClicked() = dispatch(historyClickAction)
    @JavascriptInterface fun onSettingsClicked() = dispatch(settingsClickAction)
    @JavascriptInterface fun onMemoriesClicked() = dispatch(memoriesClickAction)
    @JavascriptInterface fun onLanguageClicked() = dispatch(languageClickAction)
    @JavascriptInterface fun onWeatherClicked() = dispatch(weatherClickAction)

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
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = HudCyan)
                        Text("Connecting… typed messages will be sent when the session is ready.", color = HudMuted, fontSize = 12.sp)
                    }
                }
                if (!historyNotice.isNullOrBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            historyNotice,
                            modifier = Modifier.weight(1f),
                            color = if (liveStatus == LiveConversationStatus.ERROR && !liveNotice.isNullOrBlank()) {
                                MaterialTheme.colorScheme.error
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
                            "LIVE TALK • GEMINI TTS (FREE FALLBACK)"
                        } else {
                            "LIVE TALK • ${activeVoice.title.uppercase(Locale.ROOT)}"
                        },
                        color = HudCyan,
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
                            focusedBorderColor = HudCyan,
                            unfocusedBorderColor = Color(0xFF475569),
                            cursorColor = HudCyan,
                            focusedContainerColor = Color(0xFF0E2030),
                            unfocusedContainerColor = Color(0xFF0E2030)
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
            color = if (isUser) Color(0xFF0B3540) else Color(0xFF0E2030),
            tonalElevation = if (isDraft) 0.dp else 1.dp
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                Text(
                    if (isUser) "YOU" else "JARVIS",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isUser) Color(0xFFC9FCFF) else HudCyan
                )
                Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun resolveLocality(context: Context, location: Location): String = try {
    Geocoder(context, Locale.getDefault())
        .getFromLocation(location.latitude, location.longitude, 1)
        ?.firstOrNull()?.locality.orEmpty()
} catch (_: Exception) {
    ""
}

@Suppress("MissingPermission")
private fun requestSingleHudLocation(
    context: Context,
    onLocation: (Location) -> Unit,
    onUnavailable: () -> Unit
) {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    if (manager == null) {
        onUnavailable()
        return
    }
    val enabledProviders = runCatching { manager.getProviders(true) }.getOrDefault(emptyList())
    val providerOrder = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
    val provider = providerOrder.firstOrNull { it in enabledProviders } ?: enabledProviders.firstOrNull()
    if (provider == null) {
        onUnavailable()
        return
    }

    val recentLocation = enabledProviders.asSequence()
        .mapNotNull { name -> runCatching { manager.getLastKnownLocation(name) }.getOrNull() }
        .maxByOrNull { it.time }
        ?.takeIf { System.currentTimeMillis() - it.time < 60L * 60L * 1000L }
    if (recentLocation != null) {
        onLocation(recentLocation)
        return
    }

    val handler = Handler(Looper.getMainLooper())
    var completed = false
    lateinit var listener: LocationListener
    val timeout = Runnable {
        if (!completed) {
            completed = true
            runCatching { manager.removeUpdates(listener) }
            onUnavailable()
        }
    }
    listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (completed) return
            completed = true
            handler.removeCallbacks(timeout)
            runCatching { manager.removeUpdates(this) }
            onLocation(location)
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }
    handler.postDelayed(timeout, 15_000L)
    try {
        manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
    } catch (_: SecurityException) {
        handler.removeCallbacks(timeout)
        completed = true
        onUnavailable()
    } catch (_: IllegalArgumentException) {
        handler.removeCallbacks(timeout)
        completed = true
        onUnavailable()
    }
}

private fun LiveConversationStatus.isActive(): Boolean = when (this) {
    LiveConversationStatus.CONNECTING,
    LiveConversationStatus.LISTENING,
    LiveConversationStatus.SPEAKING -> true
    LiveConversationStatus.IDLE,
    LiveConversationStatus.ERROR -> false
}
