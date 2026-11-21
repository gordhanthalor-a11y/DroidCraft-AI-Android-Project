package com.droidcraft.app.jarvis.voice

import android.content.Context
import android.util.Base64
import com.droidcraft.app.data.SecureKeyStore
import com.droidcraft.app.jarvis.character.Emotion
import com.droidcraft.app.jarvis.character.EmotionEngine
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Providers that can return normalized 24 kHz, mono, PCM16 speech audio. */
enum class SpeechProvider(val displayName: String) {
    GEMINI("Gemini TTS"),
    ELEVENLABS("ElevenLabs")
}

/** Android phone TTS remains a fallback and cannot be selected as the normal speech provider. */
enum class VoiceMode {
    /** Retained to migrate old preferences; [mode] upgrades it to Gemini. */
    PHONE_FREE,
    GEMINI_FREE,
    ELEVENLABS
}

object VoiceSettings {
    private const val PREFS = "jarvis_voice"
    private const val ELEVENLABS_KEY = "elevenlabs_api_key"
    private val elevenVoiceIdPattern = Regex("[A-Za-z0-9_-]{1,80}")
    @Volatile private var elevenLabsPlanBlocked = false

    const val DEFAULT_GEMINI_VOICE = "Charon"
    const val DEFAULT_ELEVEN_VOICE = "7b9mYhmnp0y2qSH1FnBL"
    const val DEFAULT_ELEVEN_VOICE_NAME = "Bunty - Funny Best Friend"

    data class VoiceOption(val id: String, val name: String, val hint: String = "")

    val ELEVENLABS_VOICES = listOf(
        VoiceOption(DEFAULT_ELEVEN_VOICE, DEFAULT_ELEVEN_VOICE_NAME, "default · friendly"),
        VoiceOption("mg9npuuaf8WJphS6E0Rt", "Baby", "saved preset"),
        VoiceOption("21m00Tcm4TlvDq8ikWAM", "Rachel", "English"),
        VoiceOption("EXAVITQu4vr4xnSDxMaL", "Bella", "English"),
        VoiceOption("AZnzlk1XvdvUeBnXmlld", "Domi", "English")
    )

    val GEMINI_VOICES = listOf(
        "Charon", "Puck", "Kore", "Fenrir", "Aoede", "Leda", "Orus", "Zephyr", "Callirrhoe", "Autonoe"
    )

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Existing Gemini users stay on Charon; old PHONE/SMART/provider modes migrate safely to Gemini. */
    fun mode(context: Context): VoiceMode {
        val prefs = preferences(context)
        val stored = prefs.getString("voice_mode", null)
        val selected = when (stored) {
            VoiceMode.ELEVENLABS.name -> VoiceMode.ELEVENLABS
            VoiceMode.GEMINI_FREE.name -> VoiceMode.GEMINI_FREE
            else -> VoiceMode.GEMINI_FREE
        }
        if (stored != selected.name) prefs.edit().putString("voice_mode", selected.name).apply()
        return selected
    }

    fun setMode(context: Context, mode: VoiceMode) {
        require(mode == VoiceMode.GEMINI_FREE || mode == VoiceMode.ELEVENLABS) {
            "Android phone TTS is reserved for emergency fallback only."
        }
        preferences(context).edit().putString("voice_mode", mode.name).apply()
    }

    fun speechProvider(context: Context): SpeechProvider =
        if (mode(context) == VoiceMode.ELEVENLABS) SpeechProvider.ELEVENLABS else SpeechProvider.GEMINI

    fun geminiVoice(context: Context): String {
        val prefs = preferences(context)
        if (!prefs.getBoolean("gemini_voice_charon_migration", false)) {
            if (prefs.getString("gemini_voice", null) == "Puck") {
            prefs.edit().putString("gemini_voice", DEFAULT_GEMINI_VOICE).apply()
                        }
        prefs.edit().putBoolean("gemini_voice_charon_migration", true).apply()
            }
        return prefs.getString("gemini_voice", DEFAULT_GEMINI_VOICE)
        ?.takeIf { it in GEMINI_VOICES }
            ?: DEFAULT_GEMINI_VOICE
    }

    fun setGeminiVoice(context: Context, voice: String) {
        require(voice in GEMINI_VOICES) { "Choose a supported Gemini preset voice." }
        preferences(context).edit().putString("gemini_voice", voice).apply()
    }

    fun elevenVoiceId(context: Context): String =
        preferences(context).getString("eleven_voice_id", DEFAULT_ELEVEN_VOICE)
            ?.trim()
            ?.takeIf(elevenVoiceIdPattern::matches)
            ?: DEFAULT_ELEVEN_VOICE

    fun elevenVoiceName(context: Context): String =
        ELEVENLABS_VOICES.firstOrNull { it.id == elevenVoiceId(context) }?.name ?: "Custom voice"

    fun setElevenVoiceId(context: Context, id: String) {
        val cleaned = id.trim()
        require(elevenVoiceIdPattern.matches(cleaned)) {
            "Enter an ElevenLabs voice ID using letters, numbers, underscores, or hyphens."
        }
        preferences(context).edit().putString("eleven_voice_id", cleaned).apply()
    }

    /** API keys stay in the encrypted Android Keystore-backed store, never ordinary preferences. */
    fun elevenLabsApiKey(context: Context): String {
        val appContext = context.applicationContext
        val secureStore = SecureKeyStore(appContext)
        val saved = runCatching { secureStore.read(ELEVENLABS_KEY) }.getOrNull().orEmpty()
        if (saved.isNotBlank()) {
            preferences(appContext).edit().remove("eleven_key").apply()
            return saved
        }

        // One-time migration from the encrypted TokenVault value used by earlier app versions.
        val legacyPreferences = preferences(appContext)
        val legacyCiphertext = legacyPreferences.getString("eleven_key", "").orEmpty()
        if (legacyCiphertext.isBlank()) return ""
        val plaintext = decryptLegacyElevenLabsKey(appContext, legacyCiphertext) ?: return ""
        secureStore.write(ELEVENLABS_KEY, plaintext)
        legacyPreferences.edit().remove("eleven_key").apply()
        return plaintext
    }

    fun setElevenLabsApiKey(context: Context, key: String) {
        SecureKeyStore(context.applicationContext).write(ELEVENLABS_KEY, key.trim())
        preferences(context).edit().remove("eleven_key").apply()
        clearElevenLabsPlanBlocked()
    }

    fun clearElevenLabsApiKey(context: Context) = setElevenLabsApiKey(context, "")

    internal fun isElevenLabsPlanBlocked(): Boolean = elevenLabsPlanBlocked

    internal fun markElevenLabsPlanBlocked() {
        elevenLabsPlanBlocked = true
    }

    internal fun clearElevenLabsPlanBlocked() {
        elevenLabsPlanBlocked = false
    }

    private fun decryptLegacyElevenLabsKey(context: Context, stored: String): String? = runCatching {
        val secureStore = SecureKeyStore(context)
        val legacyVaultPreferences = context.getSharedPreferences("jarvis_social_vault", Context.MODE_PRIVATE)
        val encodedSeed = secureStore.read("oauth_token_vault_seed")
            ?.takeIf { it.isNotBlank() }
            ?: legacyVaultPreferences.getString("vault_seed", null)
            ?: error("Legacy voice-key encryption seed is missing.")
        val seed = Base64.decode(encodedSeed, Base64.NO_WRAP)
        require(seed.size >= 32) { "Legacy voice-key encryption seed is invalid." }

        val combined = Base64.decode(stored, Base64.NO_WRAP)
        require(combined.size > LEGACY_IV_BYTES + LEGACY_TAG_BYTES) { "Legacy voice key is truncated." }
        val iv = combined.copyOfRange(0, LEGACY_IV_BYTES)
        val ciphertext = combined.copyOfRange(LEGACY_IV_BYTES, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(seed.copyOf(32), "AES"),
            GCMParameterSpec(LEGACY_TAG_BYTES * 8, iv)
        )
        String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
            .takeIf { it.isNotBlank() }
            ?: error("Legacy voice key was empty.")
    }.getOrNull()

    private const val LEGACY_IV_BYTES = 12
    private const val LEGACY_TAG_BYTES = 16
}

/** Provider choice + emotion still share the same voice-speed calculation. */
object VoiceRouter {
    enum class Provider { PHONE, GEMINI, ELEVENLABS }
    data class Decision(val provider: Provider, val speed: Float)

    fun decide(mode: VoiceMode, emotion: Emotion): Decision = Decision(
        provider = when (mode) {
            VoiceMode.PHONE_FREE -> Provider.PHONE
            VoiceMode.GEMINI_FREE -> Provider.GEMINI
            VoiceMode.ELEVENLABS -> Provider.ELEVENLABS
        },
        speed = EmotionEngine.style(emotion).voiceSpeed
    )
}
