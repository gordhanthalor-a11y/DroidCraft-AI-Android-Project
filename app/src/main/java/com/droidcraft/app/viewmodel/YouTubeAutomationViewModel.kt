package com.droidcraft.app.viewmodel

import android.app.Application
import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.droidcraft.app.ai.GeminiLiveVoiceSession
import com.droidcraft.app.ai.GeminiClient
import com.droidcraft.app.data.GeminiApiKeyStore
import com.droidcraft.app.data.GeminiModelSettings
import com.droidcraft.app.data.SecureKeyStore
import com.droidcraft.app.data.YouTubeApiService
import com.droidcraft.app.data.local.AppDatabase
import com.droidcraft.app.jarvis.voice.VoiceSettings
import com.droidcraft.app.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class YouTubeAutomationViewModel(application: Application) : AndroidViewModel(application) {

    private val apiService = YouTubeApiService.create()
    private val db = AppDatabase.getDatabase(application)
    private val videoDao = db.videoDao()

    // StateFlow observables for Jetpack Compose UI
    private val _videos = MutableStateFlow<List<VideoItem>>(emptyList())
    val videos: StateFlow<List<VideoItem>> = _videos.asStateFlow()

    private val _channels = MutableStateFlow<List<YouTubeChannel>>(emptyList())
    val channels: StateFlow<List<YouTubeChannel>> = _channels.asStateFlow()

    private val _selectedChannel = MutableStateFlow<YouTubeChannel?>(null)
    val selectedChannel: StateFlow<YouTubeChannel?> = _selectedChannel.asStateFlow()

    private val _assistantMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val assistantMessages: StateFlow<List<ChatMessage>> = _assistantMessages.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<ConfirmationDialogState?>(null)
    val pendingConfirmation: StateFlow<ConfirmationDialogState?> = _pendingConfirmation.asStateFlow()

    private val prefs = application.getSharedPreferences("droidcraft", Context.MODE_PRIVATE)
    private val secureKeyStore = SecureKeyStore(application)

    // Migrate older locally saved Gemini keys to encrypted storage without showing the key again.
    private var geminiApiKey: String = GeminiApiKeyStore.read(application)
    private var elevenLabsApiKey: String = VoiceSettings.elevenKey(application)

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

    private val _speakAssistantReplies = MutableStateFlow(prefs.getBoolean(PREF_CHAT_SPEAK_REPLIES, true))
    val speakAssistantReplies: StateFlow<Boolean> = _speakAssistantReplies.asStateFlow()
    private var chatTextToSpeech: TextToSpeech? = null
    @Volatile private var chatTtsReady = false
    @Volatile private var pendingChatSpeech: String? = null

    private val _hasElevenLabsKey = MutableStateFlow(elevenLabsApiKey.isNotBlank())
    val hasElevenLabsKey: StateFlow<Boolean> = _hasElevenLabsKey.asStateFlow()

    private val _liveVoiceMode = MutableStateFlow(
        runCatching {
            LiveVoiceMode.valueOf(prefs.getString(PREF_LIVE_VOICE_MODE, LiveVoiceMode.GEMINI_LIVE.name).orEmpty())
        }.getOrDefault(LiveVoiceMode.GEMINI_LIVE)
    )
    val liveVoiceMode: StateFlow<LiveVoiceMode> = _liveVoiceMode.asStateFlow()

    private val _liveEffectiveVoice = MutableStateFlow<LiveVoiceMode?>(null)
    val liveEffectiveVoice: StateFlow<LiveVoiceMode?> = _liveEffectiveVoice.asStateFlow()

    private val _liveVoiceFallback = MutableStateFlow(false)
    val liveVoiceFallback: StateFlow<Boolean> = _liveVoiceFallback.asStateFlow()

    private val _liveStatus = MutableStateFlow(LiveConversationStatus.IDLE)
    val liveStatus: StateFlow<LiveConversationStatus> = _liveStatus.asStateFlow()

    private val _liveNotice = MutableStateFlow<String?>(null)
    val liveNotice: StateFlow<String?> = _liveNotice.asStateFlow()

    private val _liveMessages = MutableStateFlow<List<LiveConversationMessage>>(emptyList())
    val liveMessages: StateFlow<List<LiveConversationMessage>> = _liveMessages.asStateFlow()

    private val _liveUserDraft = MutableStateFlow("")
    val liveUserDraft: StateFlow<String> = _liveUserDraft.asStateFlow()

    private val _liveAssistantDraft = MutableStateFlow("")
    val liveAssistantDraft: StateFlow<String> = _liveAssistantDraft.asStateFlow()

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    private var liveSession: GeminiLiveVoiceSession? = null
    private val pendingLiveTextLock = Any()
    private val pendingLiveTexts = java.util.ArrayDeque<String>()

    init {
        loadChannels()
        loadVideosFromDb()
        initializeChatTextToSpeech()
    }

    fun loadChannels() {
        viewModelScope.launch {
            try {
                val list = apiService.getConnectedChannels()
                _channels.value = list
                _selectedChannel.value = list.firstOrNull { it.isPrimary } ?: list.firstOrNull()
            } catch (_: Exception) {
                // Fallback local cache
            }
        }
    }

    fun loadVideosFromDb() {
        viewModelScope.launch {
            videoDao.getAllVideos().collect { cached ->
                _videos.value = cached
            }
        }
    }

    fun approveVideo(videoId: String) {
        viewModelScope.launch {
            videoDao.updateStage(videoId, PipelineStage.SCHEDULED)
            videoDao.updateApproval(videoId, ApprovalStatus.APPROVED)
            // Trigger WorkManager upload schedule
        }
    }

    fun rejectVideo(videoId: String, reason: String) {
        viewModelScope.launch {
            videoDao.updateStage(videoId, PipelineStage.FAILED)
            videoDao.updateApproval(videoId, ApprovalStatus.REJECTED)
        }
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

    fun setSpeakAssistantReplies(enabled: Boolean) {
        _speakAssistantReplies.value = enabled
        prefs.edit().putBoolean(PREF_CHAT_SPEAK_REPLIES, enabled).apply()
        if (!enabled) {
            pendingChatSpeech = null
            chatTextToSpeech?.stop()
        }
    }

    fun saveElevenLabsKey(key: String) {
        val cleaned = key.trim()
        secureKeyStore.write(SECURE_ELEVENLABS_KEY, cleaned)
        elevenLabsApiKey = cleaned
        _hasElevenLabsKey.value = cleaned.isNotBlank()
    }

    fun clearElevenLabsKey() {
        secureKeyStore.remove(SECURE_ELEVENLABS_KEY)
        elevenLabsApiKey = ""
        _hasElevenLabsKey.value = false
    }

    fun setLiveVoiceMode(mode: LiveVoiceMode) {
        _liveVoiceMode.value = mode
        prefs.edit().putString(PREF_LIVE_VOICE_MODE, mode.name).apply()
    }

    fun startLiveConversation() {
        if (_liveStatus.value == LiveConversationStatus.CONNECTING ||
            _liveStatus.value == LiveConversationStatus.LISTENING ||
            _liveStatus.value == LiveConversationStatus.SPEAKING
        ) return

        if (geminiApiKey.isBlank()) {
            _liveStatus.value = LiveConversationStatus.ERROR
            _liveNotice.value = "Add your Gemini API key in Settings before starting Live Talk."
            return
        }

        liveSession?.stop()
        synchronized(pendingLiveTextLock) { pendingLiveTexts.clear() }
        _liveMessages.value = emptyList()
        _liveUserDraft.value = ""
        _liveAssistantDraft.value = ""
        _liveNotice.value = null
        _liveVoiceFallback.value = false

        val selectedVoice = _liveVoiceMode.value
        val sessionVoice = if (selectedVoice == LiveVoiceMode.ELEVENLABS && elevenLabsApiKey.isBlank()) {
            _liveVoiceFallback.value = true
            _liveNotice.value = "Add your ElevenLabs API key in Settings. Starting with Gemini Live (free) for now."
            LiveVoiceMode.GEMINI_LIVE
        } else {
            selectedVoice
        }
        _liveEffectiveVoice.value = sessionVoice
        _liveStatus.value = LiveConversationStatus.CONNECTING

        liveSession = GeminiLiveVoiceSession(
            context = getApplication<Application>(),
            geminiApiKey = geminiApiKey,
            requestedVoice = sessionVoice,
            elevenLabsApiKey = elevenLabsApiKey,
            listener = object : com.droidcraft.app.ai.LiveVoiceSessionListener {
                override fun onStatus(status: LiveConversationStatus, message: String?) {
                    _liveStatus.value = status
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
                        _liveUserDraft.value = ""
                    } else {
                        _liveUserDraft.value = text
                    }
                }

                override fun onAssistantDraft(text: String) {
                    _liveAssistantDraft.value = text
                }

                override fun onAssistantMessage(text: String) {
                    appendLiveMessage(text, isUser = false)
                    _liveAssistantDraft.value = ""
                }

                override fun onEffectiveVoiceChanged(mode: LiveVoiceMode, message: String?) {
                    _liveEffectiveVoice.value = mode
                    if (message != null) {
                        _liveNotice.value = message
                        if (mode == LiveVoiceMode.GEMINI_LIVE) _liveVoiceFallback.value = true
                    }
                }

                override fun onNotice(message: String) {
                    _liveNotice.value = message
                }
            }
        ).also { it.start() }
    }

    fun stopLiveConversation() {
        liveSession?.stop()
        liveSession = null
        synchronized(pendingLiveTextLock) { pendingLiveTexts.clear() }
        _liveEffectiveVoice.value = null
        _liveUserDraft.value = ""
        _liveAssistantDraft.value = ""
        _liveNotice.value = null
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

    fun sendAssistantMessage(userText: String) {
        val userMsg = ChatMessage(id = System.currentTimeMillis().toString(), text = userText, isUser = true)
        _assistantMessages.value = _assistantMessages.value + userMsg
        val key = geminiApiKey
        if (key.isBlank()) {
            addBotMessage("Pehle Settings mein Gemini API key add kijiye.")
            return
        }
        viewModelScope.launch {
            _isThinking.value = true
            try {
                val history = _assistantMessages.value.map { it.isUser to it.text }
                val reply = GeminiClient(
                    apiKey = key,
                    modelOrder = _geminiModelOrder.value,
                    onModelSelected = { model -> _activeGeminiModel.value = model }
                ).reply(history)
                val spokenReply = reply.ifBlank { "(Khaali jawab mila, dobara puchiye)" }
                addBotMessage(spokenReply)
                speakChatReply(spokenReply)
            } catch (e: Exception) {
                addBotMessage("Jawab nahi mil paya: ${e.message ?: "internet check kariye"}")
            } finally {
                _isThinking.value = false
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

    private fun addBotMessage(text: String) {
        _assistantMessages.value = _assistantMessages.value +
            ChatMessage(id = (System.currentTimeMillis() + 1).toString(), text = text, isUser = false)
    }

    private fun initializeChatTextToSpeech() {
        chatTextToSpeech = TextToSpeech(getApplication()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                chatTtsReady = true
                val pending = pendingChatSpeech
                pendingChatSpeech = null
                if (pending != null) speakChatReply(pending)
            }
        }
    }

    private fun speakChatReply(text: String) {
        if (!_speakAssistantReplies.value || text.isBlank()) return
        val engine = chatTextToSpeech
        if (engine == null || !chatTtsReady) {
            pendingChatSpeech = text
            return
        }
        val locale = if (text.any { it.code in 0x0900..0x097F }) Locale("hi", "IN") else Locale.US
        val languageStatus = runCatching { engine.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (languageStatus < TextToSpeech.LANG_AVAILABLE) {
            runCatching { engine.setLanguage(Locale.getDefault()) }
        }
        engine.setSpeechRate(1.0f)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant_${System.currentTimeMillis()}")
    }

    fun confirmPendingAction() {
        val pending = _pendingConfirmation.value ?: return
        when (pending.action) {
            "APPROVE_VIDEO" -> approveVideo("vid_01")
            "SCHEDULE_VIDEO" -> approveVideo("vid_01")
        }
        _pendingConfirmation.value = null
    }

    fun dismissConfirmation() {
        _pendingConfirmation.value = null
    }

    override fun onCleared() {
        stopLiveConversation()
        chatTextToSpeech?.stop()
        chatTextToSpeech?.shutdown()
        chatTextToSpeech = null
        super.onCleared()
    }

    private companion object {
        const val SECURE_ELEVENLABS_KEY = "elevenlabs_api_key"
        const val PREF_LIVE_VOICE_MODE = "live_voice_mode"
        const val PREF_CHAT_SPEAK_REPLIES = "chat_speak_replies"
    }
}
