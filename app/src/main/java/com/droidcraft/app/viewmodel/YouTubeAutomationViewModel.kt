package com.droidcraft.app.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.droidcraft.app.ai.GeminiLiveVoiceSession
import com.droidcraft.app.ai.GeminiClient
import com.droidcraft.app.data.SecureKeyStore
import com.droidcraft.app.data.YouTubeApiService
import com.droidcraft.app.data.local.AppDatabase
import com.droidcraft.app.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    private var geminiApiKey: String = loadGeminiKey()
    private var elevenLabsApiKey: String = secureKeyStore.read(SECURE_ELEVENLABS_KEY).orEmpty()

    private val _hasGeminiKey = MutableStateFlow(geminiApiKey.isNotBlank())
    val hasGeminiKey: StateFlow<Boolean> = _hasGeminiKey.asStateFlow()

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

    init {
        loadChannels()
        loadVideosFromDb()
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
        secureKeyStore.write(SECURE_GEMINI_KEY, cleaned)
        geminiApiKey = cleaned
        _hasGeminiKey.value = cleaned.isNotBlank()
    }

    fun clearGeminiKey() {
        secureKeyStore.remove(SECURE_GEMINI_KEY)
        prefs.edit().remove(LEGACY_GEMINI_KEY).apply()
        geminiApiKey = ""
        _hasGeminiKey.value = false
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
                    if (message != null) _liveNotice.value = message
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
        _liveEffectiveVoice.value = null
        _liveUserDraft.value = ""
        _liveAssistantDraft.value = ""
    }

    fun showLiveNotice(message: String) {
        _liveNotice.value = message
    }

    fun sendLiveText(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        val session = liveSession
        if (session == null || !session.sendText(clean)) {
            _liveNotice.value = "Connect Live Talk before sending a message."
            return
        }
        appendLiveMessage(clean, isUser = true)
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
                val reply = GeminiClient(key).reply(history)
                addBotMessage(reply.ifBlank { "(Khaali jawab mila, dobara puchiye)" })
            } catch (e: Exception) {
                addBotMessage("Jawab nahi mil paya: ${e.message ?: "internet check kariye"}")
            } finally {
                _isThinking.value = false
            }
        }
    }

    private fun loadGeminiKey(): String {
        val encrypted = secureKeyStore.read(SECURE_GEMINI_KEY)
        val legacy = prefs.getString(LEGACY_GEMINI_KEY, "").orEmpty()
        if (legacy.isNotBlank()) prefs.edit().remove(LEGACY_GEMINI_KEY).apply()
        if (encrypted != null) return encrypted
        if (legacy.isBlank()) return ""
        secureKeyStore.write(SECURE_GEMINI_KEY, legacy)
        return legacy
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
        super.onCleared()
    }

    private companion object {
        const val SECURE_GEMINI_KEY = "gemini_api_key"
        const val SECURE_ELEVENLABS_KEY = "elevenlabs_api_key"
        const val LEGACY_GEMINI_KEY = "gemini_key"
        const val PREF_LIVE_VOICE_MODE = "live_voice_mode"
    }
}
