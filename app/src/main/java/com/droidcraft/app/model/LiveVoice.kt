package com.droidcraft.app.model

/** Voice renderer selected in Settings for live Jarvis conversations. */
enum class LiveVoiceMode(val title: String, val description: String) {
    GEMINI_LIVE(
        title = "Gemini TTS (free)",
        description = "Gemini Live handles the conversation; Gemini TTS speaks replies with your preset voice."
    ),
    ELEVENLABS(
        title = "ElevenLabs (my voice)",
        description = "Speak with Bunty's ElevenLabs voice and expressive delivery."
    )
}

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
