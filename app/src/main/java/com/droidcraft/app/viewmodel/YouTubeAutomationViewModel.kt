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

    fun sendAssistantMessage(userText: String) {
        val userMsg = ChatMessage(id = System.currentTimeMillis().toString(), text = userText, isUser = true)
        _assistantMessages.value = _assistantMessages.value + userMsg

        viewModelScope.launch {
            try {
                val response = apiService.dispatchAiCommand(
                    AssistantRequest(message = userText, channelId = _selectedChannel.value?.id)
                )
                val replyMsg = ChatMessage(
                    id = (System.currentTimeMillis() + 1).toString(),
                    text = response.reply,
                    isUser = false,
                    action = response.action
                )
                _assistantMessages.value = _assistantMessages.value + replyMsg

                if (response.requiresConfirmation && response.confirmationMessage != null) {
                    _pendingConfirmation.value = ConfirmationDialogState(
                        action = response.action ?: "CONFIRM",
                        message = response.confirmationMessage,
                        payload = response.taskTitle
                    )
                }
            } catch (e: Exception) {
                // Handle failure
            }
        }
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