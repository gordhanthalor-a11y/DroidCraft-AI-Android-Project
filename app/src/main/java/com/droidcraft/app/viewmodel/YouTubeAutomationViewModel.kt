package com.droidcraft.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
            } catch (e: Exception) {
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

    private val prefs = application.getSharedPreferences("droidcraft", android.content.Context.MODE_PRIVATE)
    private val _geminiKey = MutableStateFlow(prefs.getString("gemini_key", "") ?: "")
    val geminiKey: StateFlow<String> = _geminiKey.asStateFlow()

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    fun saveGeminiKey(key: String) {
        val k = key.trim()
        prefs.edit().putString("gemini_key", k).apply()
        _geminiKey.value = k
    }

    fun sendAssistantMessage(userText: String) {
        val userMsg = ChatMessage(id = System.currentTimeMillis().toString(), text = userText, isUser = true)
        _assistantMessages.value = _assistantMessages.value + userMsg
        val key = _geminiKey.value
        if (key.isBlank()) {
            addBotMessage("Pehle upar Gemini API key daaliye (aistudio.google.com/apikey se free milti hai).")
            return
        }
        viewModelScope.launch {
            _isThinking.value = true
            try {
                val history = _assistantMessages.value.map { it.isUser to it.text }
                val reply = com.droidcraft.app.ai.GeminiClient(key).reply(history)
                addBotMessage(reply.ifBlank { "(Khaali jawab mila, dobara puchiye)" })
            } catch (e: Exception) {
                addBotMessage("Jawab nahi mil paya: ${e.message ?: "internet check kariye"}")
            } finally {
                _isThinking.value = false
            }
        }
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
}