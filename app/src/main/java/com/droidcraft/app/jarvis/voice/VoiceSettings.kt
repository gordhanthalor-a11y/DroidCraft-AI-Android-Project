package com.droidcraft.app.jarvis.voice

import android.content.Context
import com.droidcraft.app.data.SecureKeyStore
import com.droidcraft.app.jarvis.character.Emotion
import com.droidcraft.app.jarvis.character.EmotionEngine
import com.droidcraft.app.jarvis.character.EmotionStyle
import com.droidcraft.app.social.auth.TokenVault
import com.droidcraft.app.studio.StudioSettings

/**
 * Voice Mode + Voice ID settings (Settings screen से)।
 * Golden rule: कोई key hardcode नहीं — provider keys Android Keystore में encrypted रहती हैं।
 */
enum class VoiceMode {
    PHONE_FREE,    // Android built-in TTS (offline, free)
    GEMINI_FREE,   // Gemini TTS with a selected preset voice
    ELEVENLABS,    // ElevenLabs — emotion first
    SMALLEST,      // Smallest AI
    HEYGEN,        // HeyGen
    SMART          // Strong emotion: ElevenLabs, other emotion: VoxCPM2, neutral/humour: Gemini
}

object VoiceSettings {
    private const val PREFS = "jarvis_voice"
    private const val ELEVENLABS_KEY = "elevenlabs_api_key"
    private val ELEVEN_VOICE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,80}")

    /** आपकी चुनी आवाज़ — Bunty (Funny Best Friend), default। */
    const val DEFAULT_ELEVEN_VOICE = "7b9mYhmnp0y2qSH1FnBL"
    const val DEFAULT_ELEVEN_VOICE_NAME = "Bunty - Funny Best Friend"

    /** एक आवाज़ — नाम + ElevenLabs voice ID। */
    data class VoiceOption(val id: String, val name: String, val hint: String = "")

    /**
     * App में दिखने वाली आवाज़ें — उपयोगकर्ता नाम से चुन सकता है।
     * नई आवाज़ चाहिए तो यहीं जोड़ो (id + नाम)।
     */
    val VOICES = listOf(
        VoiceOption(DEFAULT_ELEVEN_VOICE, DEFAULT_ELEVEN_VOICE_NAME, "default • दोस्ताना"),
        VoiceOption("mg9npuuaf8WJphS6E0Rt", "Baby", "आपकी पसंद"),
        VoiceOption("21m00Tcm4TlvDq8ikWAM", "Rachel", "English"),
        VoiceOption("EXAVITQu4vr4xnSDxMaL", "Bella", "English"),
        VoiceOption("AZnzlk1XvdvUeBnXmlld", "Domi", "English")
    )

    /** चुनी आवाज़ का नाम (अगर सूची में हो)। */
    fun voiceName(id: String): String =
        VOICES.firstOrNull { it.id == id }?.name ?: "कस्टम आवाज़"

    private fun p(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(context: Context): VoiceMode {
        val name = p(context).getString("voice_mode", VoiceMode.SMART.name) ?: VoiceMode.SMART.name
        return runCatching { VoiceMode.valueOf(name) }.getOrDefault(VoiceMode.SMART)
    }

    fun setMode(context: Context, mode: VoiceMode) {
        p(context).edit().putString("voice_mode", mode.name).apply()
    }

    /** ElevenLabs voice ID (आपकी चुनी Bunty)। */
    fun elevenVoiceId(context: Context): String =
        p(context).getString("eleven_voice_id", DEFAULT_ELEVEN_VOICE)
            ?.trim()
            ?.takeIf(ELEVEN_VOICE_ID_PATTERN::matches)
            ?: DEFAULT_ELEVEN_VOICE

    fun setElevenVoiceId(context: Context, id: String) {
        val cleaned = id.trim()
        require(ELEVEN_VOICE_ID_PATTERN.matches(cleaned)) {
            "Enter a valid ElevenLabs voice ID using letters, numbers, underscores, or hyphens."
        }
        p(context).edit().putString("eleven_voice_id", cleaned).apply()
    }

    /** आपकी खुद की clone voice (Smallest) — permanent choice। */
    fun cloneVoiceId(context: Context): String =
        p(context).getString("clone_voice_id", "") ?: ""

    fun setCloneVoiceId(context: Context, id: String) {
        p(context).edit().putString("clone_voice_id", id.trim()).apply()
    }

    /** ElevenLabs API key — shared Android Keystore storage, with one-time legacy migration. */
    fun elevenKey(context: Context): String {
        val secureStore = SecureKeyStore(context)
        val legacyPrefs = p(context)
        val legacyValue = legacyPrefs.getString("eleven_key", "").orEmpty()
        val saved = secureStore.read(ELEVENLABS_KEY).orEmpty()
        if (saved.isNotBlank()) {
            if (legacyValue.isNotBlank()) legacyPrefs.edit().remove("eleven_key").apply()
            return saved
        }

        val legacy = TokenVault.decrypt(context, legacyValue)
        if (legacy.isNotBlank()) {
            secureStore.write(ELEVENLABS_KEY, legacy)
            legacyPrefs.edit().remove("eleven_key").apply()
        }
        return legacy
    }

    fun setElevenKey(context: Context, key: String) {
        SecureKeyStore(context).write(ELEVENLABS_KEY, key.trim())
        p(context).edit().remove("eleven_key").apply()
    }

    /** Gemini Live and Gemini TTS preset voices. Charon is the built-in default. */
    val GEMINI_VOICES = listOf(
        "Charon", "Puck", "Kore", "Fenrir", "Aoede", "Leda", "Orus", "Zephyr", "Callirrhoe", "Autonoe"
    )
    const val DEFAULT_GEMINI_VOICE = "Charon"

    fun geminiVoice(context: Context): String =
        p(context).getString("gemini_voice", DEFAULT_GEMINI_VOICE)
            ?.takeIf { it in GEMINI_VOICES }
            ?: DEFAULT_GEMINI_VOICE

    fun setGeminiVoice(context: Context, v: String) {
        require(v in GEMINI_VOICES) { "Choose a supported Gemini preset voice." }
        p(context).edit().putString("gemini_voice", v).apply()
    }

    /** Emotional VoxCPM2 speech is enabled by default; neutral and humour stay on Gemini TTS. */
    fun emotionVoiceEnabled(context: Context): Boolean =
        p(context).getBoolean("emotion_voice_voxcpm2", true)

    fun setEmotionVoiceEnabled(context: Context, enabled: Boolean) {
        p(context).edit().putBoolean("emotion_voice_voxcpm2", enabled).apply()
    }
}

/** Router — mode + emotion से तय करता है कि कौन-सी आवाज़ चलेगी। */
object VoiceRouter {

    data class Decision(
        val provider: Provider,
        val speed: Float,
        val elevenStyle: Float,
        val elevenStability: Float,
        val voiceId: String,
        val styleRequest: String = ""
    )

    enum class Provider { PHONE, GEMINI, ELEVENLABS, VOXCPM, SMALLEST, HEYGEN }

    /** mode + emotion → provider decision। */
    fun decide(context: Context, mode: VoiceMode, emotion: Emotion): Decision {
        val style = EmotionEngine.style(emotion)
        return when (mode) {
            VoiceMode.PHONE_FREE -> phone(style)
            VoiceMode.GEMINI_FREE -> gemini(style)
            VoiceMode.ELEVENLABS -> eleven(context, style)
            VoiceMode.SMALLEST -> smallest(context, style)
            VoiceMode.HEYGEN -> heygen(style)
            VoiceMode.SMART -> when {
                EmotionEngine.isStrong(emotion) && StudioSettings.serverUrl(context).isNotBlank() &&
                    !VoiceState.elevenBlocked && VoiceSettings.elevenKey(context).isNotBlank() -> eleven(context, style)
                EmotionEngine.isEmotional(emotion) && StudioSettings.serverUrl(context).isNotBlank() &&
                    VoiceSettings.emotionVoiceEnabled(context) -> voxcpm(style, emotion)
                else -> gemini(style)
            }
        }
    }

    fun phone(style: EmotionStyle) = Decision(Provider.PHONE, style.voiceSpeed, 0f, 0f, "")
    fun gemini(style: EmotionStyle) = Decision(Provider.GEMINI, style.voiceSpeed, style.elevenStyle, style.elevenStability, "")
    fun eleven(context: Context, style: EmotionStyle) = Decision(
        Provider.ELEVENLABS, style.voiceSpeed, style.elevenStyle, style.elevenStability,
        VoiceSettings.elevenVoiceId(context)
    )
    fun voxcpm(style: EmotionStyle, emotion: Emotion) = Decision(
        Provider.VOXCPM, style.voiceSpeed, style.elevenStyle, style.elevenStability, "",
        styleRequest = EmotionEngine.voxcpmInstruction(emotion)
    )
    fun smallest(context: Context, style: EmotionStyle) = Decision(
        Provider.SMALLEST, style.voiceSpeed, style.elevenStyle, style.elevenStability,
        VoiceSettings.cloneVoiceId(context)
    )
    fun heygen(style: EmotionStyle) = Decision(Provider.HEYGEN, style.voiceSpeed, style.elevenStyle, style.elevenStability, "")
}

/** ElevenLabs credits/availability की वर्तमान स्थिति (router इसे देखता है)। */
object VoiceState {
    @Volatile var elevenBlocked: Boolean = false
}
