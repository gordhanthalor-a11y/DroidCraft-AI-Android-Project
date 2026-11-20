package com.droidcraft.app.studio

import android.content.Context
import android.content.SharedPreferences

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
        prefs(context).getString("server_url", "http://10.0.2.2:8000")?.trimEnd('/') ?: "http://10.0.2.2:8000"

    fun setServerUrl(context: Context, url: String) {
        prefs(context).edit().putString("server_url", url.trimEnd('/')).apply()
    }

    // ---------------- TTS
    fun ttsProvider(context: Context): String =
        prefs(context).getString("tts_provider", "smallest") ?: "smallest"

    fun setTtsProvider(context: Context, v: String) {
        prefs(context).edit().putString("tts_provider", v).apply()
    }

    fun ttsVoice(context: Context): String =
        prefs(context).getString("tts_voice", "") ?: ""

    fun setTtsVoice(context: Context, v: String) {
        prefs(context).edit().putString("tts_voice", v).apply()
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

    // ---------------- provider keys (app से server तक भेजने के लिए; server env की जगह)
    fun smallestKey(context: Context): String =
        prefs(context).getString("smallest_api_key", "") ?: ""

    fun setSmallestKey(context: Context, v: String) {
        prefs(context).edit().putString("smallest_api_key", v.trim()).apply()
    }

    fun heygenKey(context: Context): String =
        prefs(context).getString("heygen_api_key", "") ?: ""

    fun setHeygenKey(context: Context, v: String) {
        prefs(context).edit().putString("heygen_api_key", v.trim()).apply()
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
