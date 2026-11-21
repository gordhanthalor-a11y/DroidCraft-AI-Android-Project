package com.droidcraft.app.ai

import android.content.Context
import com.droidcraft.app.data.SecureKeyStore

/** Per-device Live API settings; never contains credentials. */
enum class EchoControlMode(val label: String) {
    AUTO("Auto"),
    HEADPHONES("Headphones"),
    SPEAKER("Speaker")
}

object LiveVoiceSettings {
    private const val PREFS = "jarvis_live_voice"
    private const val KEY_ECHO_MODE = "echo_control_mode"
    private const val KEY_SILENCE_MS = "silence_duration_ms"
    private const val KEY_MODEL = "live_model"

    val liveModels = listOf(
        "models/gemini-3.8-live",
        "models/gemini-3.1-flash-live"
    )
    const val DEFAULT_LIVE_MODEL = "models/gemini-3.8-live"
    const val DEFAULT_SILENCE_DURATION_MS = 600

    fun echoMode(context: Context): EchoControlMode = runCatching {
        EchoControlMode.valueOf(preferences(context).getString(KEY_ECHO_MODE, EchoControlMode.AUTO.name).orEmpty())
    }.getOrDefault(EchoControlMode.AUTO)

    fun setEchoMode(context: Context, mode: EchoControlMode) {
        preferences(context).edit().putString(KEY_ECHO_MODE, mode.name).apply()
    }

    fun silenceDurationMs(context: Context): Int =
        preferences(context).getInt(KEY_SILENCE_MS, DEFAULT_SILENCE_DURATION_MS)
            .coerceIn(200, 2_000)

    fun setSilenceDurationMs(context: Context, durationMs: Int) {
        preferences(context).edit().putInt(KEY_SILENCE_MS, durationMs.coerceIn(200, 2_000)).apply()
    }

    fun liveModel(context: Context): String = preferences(context)
        .getString(KEY_MODEL, DEFAULT_LIVE_MODEL)
        .orEmpty()
        .takeIf { it in liveModels }
        ?: DEFAULT_LIVE_MODEL

    fun setLiveModel(context: Context, model: String) {
        require(model in liveModels) { "Choose a supported Gemini Live model." }
        preferences(context).edit().putString(KEY_MODEL, model).apply()
    }

    fun sessionResumptionHandle(context: Context): String? =
        runCatching { SecureKeyStore(context).read("gemini_live_resumption_handle") }
            .getOrNull()?.takeIf { it.isNotBlank() }

    fun saveSessionResumptionHandle(context: Context, handle: String?) {
        val store = SecureKeyStore(context)
        if (handle.isNullOrBlank()) store.remove("gemini_live_resumption_handle")
        else store.write("gemini_live_resumption_handle", handle)
    }

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
