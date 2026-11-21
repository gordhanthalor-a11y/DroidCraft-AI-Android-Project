package com.droidcraft.app.jarvis.voice

import android.content.Context
import com.droidcraft.app.jarvis.character.Emotion
import com.droidcraft.app.jarvis.character.EmotionEngine

/** Gemini is the only selectable Jarvis route; PHONE is retained for the emergency fallback path. */
enum class VoiceMode {
    /** Legacy value; Android phone TTS is no longer selectable as the normal voice. */
    PHONE_FREE,
    GEMINI_FREE
}

object VoiceSettings {
    private const val PREFS = "jarvis_voice"
    val GEMINI_VOICES = listOf(
        "Charon", "Puck", "Kore", "Fenrir", "Aoede", "Leda", "Orus", "Zephyr", "Callirrhoe", "Autonoe"
    )
    const val DEFAULT_GEMINI_VOICE = "Charon"

    private fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Old phone-voice preferences are migrated to Gemini; phone TTS is only invoked by a failure fallback. */
    fun mode(context: Context): VoiceMode {
        val prefs = preferences(context)
        if (prefs.getString("voice_mode", VoiceMode.GEMINI_FREE.name) != VoiceMode.GEMINI_FREE.name) {
            prefs.edit().putString("voice_mode", VoiceMode.GEMINI_FREE.name).apply()
        }
        return VoiceMode.GEMINI_FREE
    }

    fun setMode(context: Context, mode: VoiceMode) {
        require(mode == VoiceMode.GEMINI_FREE) { "Android phone TTS is reserved for emergency fallback only." }
        preferences(context).edit().putString("voice_mode", VoiceMode.GEMINI_FREE.name).apply()
    }

    fun geminiVoice(context: Context): String =
        preferences(context).getString("gemini_voice", DEFAULT_GEMINI_VOICE)
            ?.takeIf { it in GEMINI_VOICES }
            ?: DEFAULT_GEMINI_VOICE

    fun setGeminiVoice(context: Context, voice: String) {
        require(voice in GEMINI_VOICES) { "Choose a supported Gemini preset voice." }
        preferences(context).edit().putString("gemini_voice", voice).apply()
    }
}

/** Routes normal speech to Gemini; phone output is kept only for the explicitly labelled fallback. */
object VoiceRouter {
    enum class Provider { PHONE, GEMINI }
    data class Decision(val provider: Provider, val speed: Float)

    fun decide(mode: VoiceMode, emotion: Emotion): Decision = Decision(
        provider = if (mode == VoiceMode.PHONE_FREE) Provider.PHONE else Provider.GEMINI,
        speed = EmotionEngine.style(emotion).voiceSpeed
    )
}
