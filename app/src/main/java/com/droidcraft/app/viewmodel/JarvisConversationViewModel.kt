package com.droidcraft.app.viewmodel

import android.app.Application
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.droidcraft.app.ai.GeminiClient
import com.droidcraft.app.ai.GeminiLiveVoiceSession
import com.droidcraft.app.ai.LiveToolExecutor
import com.droidcraft.app.data.GeminiApiKeyStore
import com.droidcraft.app.data.GeminiModelSettings
import com.droidcraft.app.jarvis.JarvisCommandParser
import com.droidcraft.app.jarvis.JarvisIntentType
import com.droidcraft.app.jarvis.character.JarvisCharacter
import org.json.JSONObject
import com.droidcraft.app.jarvis.voice.SpokenTextCleaner
import com.droidcraft.app.jarvis.voice.VoiceSettings
import com.droidcraft.app.model.LiveConversationMessage
import com.droidcraft.app.model.LiveConversationStatus
import com.droidcraft.app.activity.HomeActivityTracker
import com.droidcraft.app.jarvis.overlay.JarvisBodyService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/** Gemini configuration and the one optional, user-started Live session. */
class JarvisConversationViewModel(application: Application) : AndroidViewModel(application) {
    private var geminiApiKey: String = GeminiApiKeyStore.read(application)

    private val _hasGeminiKey = MutableStateFlow(geminiApiKey.isNotBlank())
    val hasGeminiKey: StateFlow<Boolean> = _hasGeminiKey.asStateFlow()
    private val _geminiModelOrder = MutableStateFlow(GeminiModelSettings.read(application))
    val geminiModelOrder: StateFlow<List<String>> = _geminiModelOrder.asStateFlow()
    private val _activeGeminiModel = MutableStateFlow(_geminiModelOrder.value.first())
    val activeGeminiModel: StateFlow<String> = _activeGeminiModel.asStateFlow()
    private val _refreshingGeminiModels = MutableStateFlow(false)
    val refreshingGeminiModels: StateFlow<Boolean> = _refreshingGeminiModels.asStateFlow()
    private val _geminiModelSettingsMessage = MutableStateFlow("")
    val geminiModelSettingsMessage: StateFlow<String> = _geminiModelSettingsMessage.asStateFlow()
    private val _testingGeminiConnection = MutableStateFlow(false)
    val testingGeminiConnection: StateFlow<Boolean> = _testingGeminiConnection.asStateFlow()
    private val _geminiConnectionStatus = MutableStateFlow(if (geminiApiKey.isBlank()) "No key saved" else "Not tested")
    val geminiConnectionStatus: StateFlow<String> = _geminiConnectionStatus.asStateFlow()
    private val _geminiPingMs = MutableStateFlow<Long?>(null)
    val geminiPingMs: StateFlow<Long?> = _geminiPingMs.asStateFlow()

    private val _liveStatus = MutableStateFlow(LiveConversationStatus.IDLE)
    val liveStatus: StateFlow<LiveConversationStatus> = _liveStatus.asStateFlow()
    private val _liveAudioLevel = MutableStateFlow(0f)
    val liveAudioLevel: StateFlow<Float> = _liveAudioLevel.asStateFlow()
    private val _liveNotice = MutableStateFlow<String?>(null)
    val liveNotice: StateFlow<String?> = _liveNotice.asStateFlow()
    private val _liveMessages = MutableStateFlow<List<LiveConversationMessage>>(emptyList())
    val liveMessages: StateFlow<List<LiveConversationMessage>> = _liveMessages.asStateFlow()
    private val _liveUserDraft = MutableStateFlow("")
    val liveUserDraft: StateFlow<String> = _liveUserDraft.asStateFlow()
    private val _liveAssistantDraft = MutableStateFlow("")
    val liveAssistantDraft: StateFlow<String> = _liveAssistantDraft.asStateFlow()

    // Retained specifically as Live Talk's last-resort speech fallback.
    private var chatTextToSpeech: TextToSpeech? = null
    @Volatile private var liveFallbackTtsReady = false
    @Volatile private var liveFallbackTtsInitializationFailed = false
    private val liveFallbackSequence = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var liveFallbackUtteranceId: String? = null
    @Volatile private var pendingLivePhoneFallback: Pair<String, Long>? = null
    @Volatile private var liveActivityId: String? = null

    private var liveSession: GeminiLiveVoiceSession? = null
    @Volatile private var liveToolExecutor: LiveToolExecutor? = null
    private val pendingLiveTextLock = Any()
    private val pendingLiveTexts = java.util.ArrayDeque<String>()

    init { initializeLiveFallbackTextToSpeech() }

    internal fun setLiveToolExecutor(executor: LiveToolExecutor?) {
        liveToolExecutor = executor
    }

    fun saveGeminiKey(key: String) {
        val cleaned = key.trim()
        GeminiApiKeyStore.write(getApplication(), cleaned)
        geminiApiKey = cleaned
        _hasGeminiKey.value = cleaned.isNotBlank()
        _geminiConnectionStatus.value = if (cleaned.isBlank()) "No key saved" else "Key saved · not tested"
        _geminiPingMs.value = null
    }

    fun clearGeminiKey() {
        GeminiApiKeyStore.clear(getApplication())
        geminiApiKey = ""
        _hasGeminiKey.value = false
        _geminiConnectionStatus.value = "No key saved"
        _geminiPingMs.value = null
    }

    fun saveGeminiModelOrder(text: String): List<String> {
        val parsed = GeminiModelSettings.parse(text)
        require(parsed.isNotEmpty()) { "Enter at least one Gemini model ID." }
        val saved = GeminiModelSettings.write(getApplication(), parsed)
        _geminiModelOrder.value = saved
        if (_activeGeminiModel.value !in saved) _activeGeminiModel.value = saved.first()
        _geminiModelSettingsMessage.value = "Saved ${saved.size} models in this fallback order."
        return saved
    }

    fun resetGeminiModelOrder(): List<String> {
        val saved = GeminiModelSettings.write(getApplication(), GeminiModelSettings.DEFAULT_MODELS)
        _geminiModelOrder.value = saved
        _activeGeminiModel.value = saved.first()
        _geminiModelSettingsMessage.value = "Restored the default Gemini model order."
        return saved
    }

    fun selectPrimaryGeminiModel(modelId: String) {
        val currentOrder = _geminiModelOrder.value
        if (modelId !in currentOrder) return
        val reordered = listOf(modelId) + currentOrder.filterNot { it == modelId }
        val saved = GeminiModelSettings.write(getApplication(), reordered)
        _geminiModelOrder.value = saved
        _activeGeminiModel.value = saved.first()
        _geminiModelSettingsMessage.value = "Preferred ${saved.first()} and kept the remaining fallback order."
    }

    fun refreshAvailableGeminiModels() {
        if (_refreshingGeminiModels.value) return
        val key = geminiApiKey
        if (key.isBlank()) {
            _geminiModelSettingsMessage.value = "Add a Gemini API key before refreshing models."
            return
        }
        viewModelScope.launch {
            _refreshingGeminiModels.value = true
            _geminiModelSettingsMessage.value = "Checking models available to this API key…"
            try {
                val available = GeminiClient.fetchAvailableTextModels(key)
                val ordered = GeminiModelSettings.orderAvailable(available, _geminiModelOrder.value)
                val saved = GeminiModelSettings.write(getApplication(), ordered)
                _geminiModelOrder.value = saved
                if (_activeGeminiModel.value !in saved) _activeGeminiModel.value = saved.first()
                _geminiModelSettingsMessage.value =
                    "Found ${available.size} Gemini text models available to this key; saved ${saved.size} in fallback order."
            } catch (error: Exception) {
                _geminiModelSettingsMessage.value =
                    "Could not refresh models: ${error.message ?: "check the key and network, then retry."}"
            } finally {
                _refreshingGeminiModels.value = false
            }
        }
    }

    /** Measures an end-to-end, one-token Gemini request using the configured model fallback order. */
    fun testGeminiConnection() {
        if (_testingGeminiConnection.value) return
        val key = geminiApiKey
        if (key.isBlank()) {
            _geminiConnectionStatus.value = "No Gemini key saved"
            _geminiPingMs.value = null
            return
        }
        viewModelScope.launch {
            _testingGeminiConnection.value = true
            _geminiConnectionStatus.value = "Testing model access…"
            _geminiPingMs.value = null
            val startedAt = System.nanoTime()
            try {
                val client = GeminiClient(
                    apiKey = key,
                    systemPrompt = "You are performing a short connection check. Reply with OK.",
                    modelOrder = _geminiModelOrder.value,
                    onModelSelected = { model -> _activeGeminiModel.value = model }
                )
                client.reply(listOf(true to "Reply with only OK."))
                val elapsedMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L)
                _geminiPingMs.value = elapsedMs
                _geminiConnectionStatus.value = "Connected · ${_activeGeminiModel.value}"
            } catch (error: Exception) {
                val detail = error.message?.take(240).orEmpty().ifBlank { "check key and network" }
                _geminiConnectionStatus.value = "Connection failed · $detail"
            } finally {
                _testingGeminiConnection.value = false
            }
        }
    }

    fun startLiveConversation() {
        if (_liveStatus.value == LiveConversationStatus.CONNECTING ||
            _liveStatus.value == LiveConversationStatus.LISTENING ||
            _liveStatus.value == LiveConversationStatus.SPEAKING
        ) return

        cancelLivePhoneFallback()
        val previousActivityId = liveActivityId
        liveActivityId = null
        liveSession?.stop()
        liveSession = null
        previousActivityId?.let { HomeActivityTracker.complete(it, "Live Talk reconnecting") }

        val activityId = "live-talk-${System.currentTimeMillis()}"
        liveActivityId = activityId
        HomeActivityTracker.begin(
            id = activityId,
            kind = "Live Talk",
            title = "Live Talk session",
            step = "Checking Gemini credentials",
            progress = null
        )
        if (geminiApiKey.isBlank()) {
            _liveStatus.value = LiveConversationStatus.ERROR
            _liveNotice.value = "Add your Gemini API key in Settings before starting Live Talk."
            HomeActivityTracker.fail(activityId, _liveNotice.value.orEmpty())
            liveActivityId = null
            return
        }
        val app = getApplication<Application>()
        if (app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            _liveStatus.value = LiveConversationStatus.ERROR
            _liveNotice.value = "Microphone permission was denied; Live Talk was not started. Allow it and tap the orb again."
            HomeActivityTracker.fail(activityId, _liveNotice.value.orEmpty())
            liveActivityId = null
            return
        }
        runCatching { JarvisBodyService.microphoneActive(app) }.onFailure {
            _liveStatus.value = LiveConversationStatus.ERROR
            _liveNotice.value = "The foreground Jarvis body could not start microphone mode: ${it.message ?: "Android refused it."}"
            HomeActivityTracker.fail(activityId, _liveNotice.value.orEmpty())
            liveActivityId = null
            return
        }

        synchronized(pendingLiveTextLock) { pendingLiveTexts.clear() }
        _liveMessages.value = emptyList()
        _liveUserDraft.value = ""
        _liveAssistantDraft.value = ""
        _liveNotice.value = null
        _liveAudioLevel.value = 0f
        _liveStatus.value = LiveConversationStatus.CONNECTING
        HomeActivityTracker.update(
            activityId,
            "Connecting to Gemini Live",
            note = "Gemini Live with ${VoiceSettings.speechProvider(getApplication<Application>()).displayName} speech"
        )

        liveSession = GeminiLiveVoiceSession(
            context = getApplication<Application>(),
            geminiApiKey = geminiApiKey,
            listener = object : com.droidcraft.app.ai.LiveVoiceSessionListener {
                override fun onStatus(status: LiveConversationStatus, message: String?) {
                    _liveStatus.value = status
                    val activityId = liveActivityId
                    when (status) {
                        LiveConversationStatus.CONNECTING -> activityId?.let {
                            HomeActivityTracker.update(it, "Connecting to Gemini Live", note = message)
                        }
                        LiveConversationStatus.LISTENING -> activityId?.let {
                            HomeActivityTracker.update(it, "Listening for your next turn", note = message)
                        }
                        LiveConversationStatus.SPEAKING -> activityId?.let {
                            HomeActivityTracker.update(it, "Jarvis is speaking", note = message)
                        }
                        LiveConversationStatus.ERROR -> {
                            activityId?.let {
                                HomeActivityTracker.fail(
                                    it,
                                    message ?: "Live Talk connection failed without an error detail."
                                )
                            }
                            liveActivityId = null
                        }
                        LiveConversationStatus.IDLE -> {
                            activityId?.let { HomeActivityTracker.complete(it, "Live Talk session stopped") }
                            liveActivityId = null
                        }
                    }
                    if (status == LiveConversationStatus.ERROR || status == LiveConversationStatus.IDLE) {
                        cancelLivePhoneFallback()
                        runCatching { JarvisBodyService.microphoneInactive(getApplication<Application>()) }
                    }
                    if (message != null) {
                        _liveNotice.value = message
                    } else if (status == LiveConversationStatus.LISTENING &&
                        _liveNotice.value?.startsWith("Connecting Live Talk", ignoreCase = true) == true
                    ) {
                        _liveNotice.value = null
                    }
                    if (status == LiveConversationStatus.LISTENING) flushPendingLiveTexts()
                    if (status == LiveConversationStatus.ERROR) {
                        synchronized(pendingLiveTextLock) { pendingLiveTexts.clear() }
                    }
                }

                override fun onUserTranscript(text: String, isFinal: Boolean) {
                    if (isFinal) {
                        appendLiveMessage(text, isUser = true)
                        liveActivityId?.let { HomeActivityTracker.update(it, "Received your live transcript") }
                        _liveUserDraft.value = ""
                        // Fast path: a clear "open <app>" command runs locally at once,
                        // without waiting for the model. Opening an app twice is harmless,
                        // so this stays safe even if the model also acts on the same words.
                        val fastIntent = JarvisCommandParser.parse(text)
                        if (fastIntent?.type == JarvisIntentType.OPEN_APP) {
                            val appName = fastIntent.args["query"].orEmpty()
                            val executor = liveToolExecutor
                            if (appName.isNotBlank() && executor != null) {
                                viewModelScope.launch {
                                    runCatching {
                                        executor.execute(
                                            "launch_app_by_name",
                                            JSONObject().put("app_name", appName),
                                            text
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        _liveUserDraft.value = text
                    }
                }

                override fun onAssistantDraft(text: String) {
                    _liveAssistantDraft.value = text
                }

                override fun onAssistantMessage(text: String) {
                    appendLiveMessage(text, isUser = false)
                    liveActivityId?.let { HomeActivityTracker.update(it, "Jarvis reply delivered") }
                    _liveAssistantDraft.value = ""
                }

                override fun onAudioLevel(level: Float) {
                    _liveAudioLevel.value = level.coerceIn(0f, 1f)
                }

                override fun onNotice(message: String) {
                    _liveNotice.value = message
                }

                override fun onAssistantInterrupted() {
                    cancelLivePhoneFallback()
                }

                override fun onPhoneTtsFallback(text: String, reason: String) {
                    _liveNotice.value = reason
                    liveActivityId?.let {
                        HomeActivityTracker.update(it, "Gemini Live audio fallback · Android phone TTS", note = reason)
                    }
                    speakLivePhoneFallback(text)
                }
            },
            toolExecutor = liveToolExecutor
        ).also { it.start() }
    }

    fun stopLiveConversation() {
        runCatching { JarvisBodyService.microphoneInactive(getApplication<Application>()) }
        cancelLivePhoneFallback()
        val activityId = liveActivityId
        liveActivityId = null
        activityId?.let { HomeActivityTracker.complete(it, "Live Talk session stopped") }
        liveSession?.stop()
        liveSession = null
        synchronized(pendingLiveTextLock) { pendingLiveTexts.clear() }
        _liveUserDraft.value = ""
        _liveAssistantDraft.value = ""
        _liveNotice.value = null
        _liveAudioLevel.value = 0f
        _liveStatus.value = LiveConversationStatus.IDLE
    }

    fun showLiveNotice(message: String) {
        _liveNotice.value = message
    }

    /** Sends typed text during Live Talk; while connecting, queue it and show it immediately. */
    fun sendLiveText(text: String): Boolean {
        val clean = text.trim()
        if (clean.isBlank()) return false
        val session = liveSession
        if (session == null) {
            _liveNotice.value = "Start Live Talk before sending a message to the live conversation."
            return false
        }
        if (session.sendText(clean)) {
            appendLiveMessage(clean, isUser = true)
            return true
        }
        if (_liveStatus.value == LiveConversationStatus.CONNECTING) {
            synchronized(pendingLiveTextLock) { pendingLiveTexts.addLast(clean) }
            appendLiveMessage(clean, isUser = true)
            _liveNotice.value = "Connecting Live Talk. Your typed message will send as soon as the session is ready."
            return true
        }
        _liveNotice.value = "Live Talk is not ready to send text. Reconnect and try again."
        return false
    }

    private fun flushPendingLiveTexts() {
        val session = liveSession ?: return
        while (true) {
            val next = synchronized(pendingLiveTextLock) {
                if (pendingLiveTexts.isEmpty()) null else pendingLiveTexts.removeFirst()
            } ?: return
            if (!session.sendText(next)) {
                synchronized(pendingLiveTextLock) { pendingLiveTexts.addFirst(next) }
                _liveNotice.value = "Live Talk is connected, but a typed message could not be sent. Try again."
                return
            }
        }
    }

    private fun appendLiveMessage(text: String, isUser: Boolean) {
        if (text.isBlank()) return
        _liveMessages.value = _liveMessages.value + LiveConversationMessage(
            id = "${System.currentTimeMillis()}-${_liveMessages.value.size}",
            text = text.trim(),
            isUser = isUser
        )
    }

    private fun initializeLiveFallbackTextToSpeech() {
        chatTextToSpeech = TextToSpeech(getApplication()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = chatTextToSpeech
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        if (utteranceId != liveFallbackUtteranceId) return
                        liveSession?.setPhoneFallbackPlaybackActive(true)
                    }

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId != liveFallbackUtteranceId) return
                        liveSession?.setPhoneFallbackPlaybackActive(false)
                        liveFallbackUtteranceId = null
                        liveActivityId?.let { HomeActivityTracker.update(it, "Phone fallback finished; listening") }
                        if (_liveStatus.value == LiveConversationStatus.SPEAKING) {
                            _liveStatus.value = LiveConversationStatus.LISTENING
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId != liveFallbackUtteranceId) return
                        liveSession?.setPhoneFallbackPlaybackActive(false)
                        liveFallbackUtteranceId = null
                        val notice = "Android phone TTS could not speak this reply."
                        _liveNotice.value = notice
                        liveActivityId?.let { HomeActivityTracker.update(it, "Phone TTS fallback failed", note = notice) }
                        if (_liveStatus.value == LiveConversationStatus.SPEAKING) {
                            _liveStatus.value = LiveConversationStatus.LISTENING
                        }
                    }
                })
                liveFallbackTtsReady = true
                liveFallbackTtsInitializationFailed = false
                val liveFallback = pendingLivePhoneFallback
                pendingLivePhoneFallback = null
                if (liveFallback != null && liveFallback.second == liveFallbackSequence.get()) {
                    speakLivePhoneFallback(liveFallback.first, liveFallback.second)
                }
            } else {
                liveFallbackTtsInitializationFailed = true
                liveFallbackTtsReady = false
                pendingLivePhoneFallback = null
                if (_liveStatus.value == LiveConversationStatus.SPEAKING) {
                    val notice = "Android phone TTS could not start as a Live Talk fallback voice."
                    _liveNotice.value = notice
                    liveActivityId?.let { HomeActivityTracker.update(it, "Phone TTS fallback unavailable", note = notice) }
                    _liveStatus.value = LiveConversationStatus.LISTENING
                }
            }
        }
    }

    private fun speakLivePhoneFallback(text: String, sequence: Long = liveFallbackSequence.incrementAndGet()) {
        val spokenText = SpokenTextCleaner.clean(text)
        if (spokenText.isBlank() || sequence != liveFallbackSequence.get()) return
        viewModelScope.launch {
            if (sequence != liveFallbackSequence.get()) return@launch
            if (liveFallbackTtsInitializationFailed) {
                val notice = "Gemini Live audio failed and Android phone TTS is unavailable on this device."
                _liveNotice.value = notice
                liveActivityId?.let { HomeActivityTracker.update(it, "Phone TTS fallback unavailable", note = notice) }
                if (_liveStatus.value == LiveConversationStatus.SPEAKING) {
                    _liveStatus.value = LiveConversationStatus.LISTENING
                }
                return@launch
            }
            val engine = chatTextToSpeech
            if (engine == null || !liveFallbackTtsReady) {
                pendingLivePhoneFallback = spokenText to sequence
                return@launch
            }
            val locale = if (spokenText.any { it.code in 0x0900..0x097F }) Locale("hi", "IN") else Locale.US
            val languageStatus = runCatching { engine.setLanguage(locale) }
                .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (languageStatus < TextToSpeech.LANG_AVAILABLE) {
                runCatching { engine.setLanguage(Locale.getDefault()) }
            }
            engine.setSpeechRate(1.0f)
            val utteranceId = "live_fallback_$sequence"
            liveFallbackUtteranceId = utteranceId
            val result = engine.speak(spokenText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result == TextToSpeech.ERROR && liveFallbackUtteranceId == utteranceId) {
                liveSession?.setPhoneFallbackPlaybackActive(false)
                liveFallbackUtteranceId = null
                val notice = "Android phone TTS could not start this reply."
                _liveNotice.value = notice
                liveActivityId?.let { HomeActivityTracker.update(it, "Phone TTS fallback failed", note = notice) }
                if (_liveStatus.value == LiveConversationStatus.SPEAKING) {
                    _liveStatus.value = LiveConversationStatus.LISTENING
                }
            }
        }
    }

    private fun cancelLivePhoneFallback() {
        liveFallbackSequence.incrementAndGet()
        pendingLivePhoneFallback = null
        val hadActiveUtterance = liveFallbackUtteranceId != null
        if (hadActiveUtterance) liveSession?.setPhoneFallbackPlaybackActive(false)
        liveFallbackUtteranceId = null
        if (hadActiveUtterance) {
            viewModelScope.launch {
                if (liveFallbackUtteranceId == null) runCatching { chatTextToSpeech?.stop() }
            }
        }
    }

    override fun onCleared() {
        stopLiveConversation()
        chatTextToSpeech?.stop()
        chatTextToSpeech?.shutdown()
        chatTextToSpeech = null
        super.onCleared()
    }

}
