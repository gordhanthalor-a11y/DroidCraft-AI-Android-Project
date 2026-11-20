package com.droidcraft.app.studio

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.droidcraft.app.data.SecureKeyStore

/**
 * Creator Studio की settings — server URL, TTS provider/voice/speed, provider keys.
 * Golden rule #1: keys सिर्फ यहीं (Settings), कहीं hardcode नहीं।
 * Golden rule #2: heavy work server पर — इसीलिए server URL यहाँ।
 */
object StudioSettings {
    private const val PREFS = "droidcraft_studio"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------- server
    fun serverUrl(context: Context): String =
        prefs(context).getString("server_url", "")?.trim()?.trimEnd('/').orEmpty()

    fun validateServerUrl(url: String): String {
        val base = url.trim().trimEnd('/')
        if (base.isBlank()) throw IllegalStateException("Enter the Creator Studio server URL.")
        val parsed = Uri.parse(base)
        val scheme = parsed.scheme?.lowercase()
        val host = parsed.host?.lowercase()
        if (scheme != "https" || host.isNullOrBlank() || parsed.query != null || parsed.fragment != null) {
            throw IllegalStateException("Enter a valid HTTPS base URL such as https://your-studio-server.example.")
        }
        return base
    }

    fun requireServerUrl(context: Context): String {
        val base = serverUrl(context)
        if (base.isBlank()) {
            throw IllegalStateException("Set the Creator Studio server URL in Settings before using this tool.")
        }
        return validateServerUrl(base)
    }

    fun setServerUrl(context: Context, url: String) {
        val cleaned = url.trim().trimEnd('/')
        prefs(context).edit().putString("server_url", cleaned).apply()
    }

    // ---------------- TTS
    fun ttsProvider(context: Context): String =
        prefs(context).getString("tts_provider", "smallest") ?: "smallest"

    fun setTtsProvider(context: Context, v: String) {
        prefs(context).edit().putString("tts_provider", v).apply()
    }

    /** Voice Studio keeps its provider choice separate from Dubbing's shared provider setting. */
    fun voiceStudioTtsProvider(context: Context): String =
        prefs(context).getString("voice_studio_tts_provider", ttsProvider(context)) ?: ttsProvider(context)

    fun setVoiceStudioTtsProvider(context: Context, v: String) {
        prefs(context).edit().putString("voice_studio_tts_provider", v).apply()
    }

    fun ttsVoice(context: Context): String =
        prefs(context).getString("tts_voice", "") ?: ""

    fun setTtsVoice(context: Context, v: String) {
        prefs(context).edit().putString("tts_voice", v).apply()
    }

    fun voiceStudioTtsVoice(context: Context): String =
        prefs(context).getString("voice_studio_tts_voice", ttsVoice(context)) ?: ttsVoice(context)

    fun setVoiceStudioTtsVoice(context: Context, v: String) {
        prefs(context).edit().putString("voice_studio_tts_voice", v).apply()
    }

    fun ttsSpeed(context: Context): Float =
        prefs(context).getFloat("tts_speed", 1.0f)

    fun setTtsSpeed(context: Context, v: Float) {
        prefs(context).edit().putFloat("tts_speed", v).apply()
    }

    fun sentenceMode(context: Context): Boolean =
        prefs(context).getBoolean("tts_sentence_mode", true)

    fun setSentenceMode(context: Context, v: Boolean) {
        prefs(context).edit().putBoolean("tts_sentence_mode", v).apply()
    }

    // ---------------- provider keys (encrypted at rest; sent only over the configured server connection)
    fun smallestKey(context: Context): String =
        readProviderKey(context, "studio_smallest_api_key", "smallest_api_key")

    fun setSmallestKey(context: Context, v: String) {
        SecureKeyStore(context).write("studio_smallest_api_key", v.trim())
        prefs(context).edit().remove("smallest_api_key").apply()
    }

    fun heygenKey(context: Context): String =
        readProviderKey(context, "studio_heygen_api_key", "heygen_api_key")

    fun setHeygenKey(context: Context, v: String) {
        SecureKeyStore(context).write("studio_heygen_api_key", v.trim())
        prefs(context).edit().remove("heygen_api_key").apply()
    }

    private fun readProviderKey(context: Context, secureName: String, legacyName: String): String {
        val store = SecureKeyStore(context)
        val saved = store.read(secureName)
        if (!saved.isNullOrBlank()) return saved

        val legacy = prefs(context).getString(legacyName, "").orEmpty()
        if (legacy.isBlank()) return ""
        store.write(secureName, legacy)
        prefs(context).edit().remove(legacyName).apply()
        return legacy
    }

    // ---------------- credits / templates (spec B2)
    fun creditLine(context: Context): String =
        prefs(context).getString("credit_line", "Original creator को credit दिया गया है।") ?: ""

    fun setCreditLine(context: Context, v: String) {
        prefs(context).edit().putString("credit_line", v).apply()
    }

    fun titleTemplate(context: Context): String =
        prefs(context).getString("title_template", "{topic} | GT Marwadi Vlogs") ?: ""

    fun setTitleTemplate(context: Context, v: String) {
        prefs(context).edit().putString("title_template", v).apply()
    }

    fun descriptionTemplate(context: Context): String =
        prefs(context).getString("description_template",
            "दोस्तों, इस वीडियो में {topic} के बारे में बताया गया है।\n\n" +
            "Original credit: {credit}\n\n" +
            "चैनल को subscribe करना न भूलें — GT Marwadi Vlogs") ?: ""

    fun setDescriptionTemplate(context: Context, v: String) {
        prefs(context).edit().putString("description_template", v).apply()
    }
}
