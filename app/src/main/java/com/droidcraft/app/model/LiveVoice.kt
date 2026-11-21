package com.droidcraft.app.model

enum class LiveConversationStatus {
    IDLE,
    CONNECTING,
    LISTENING,
    SPEAKING,
    ERROR
}

data class LiveConversationMessage(
    val id: String,
    val text: String,
    val isUser: Boolean
)
