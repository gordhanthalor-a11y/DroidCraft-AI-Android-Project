package com.droidcraft.app.data

import android.content.Context

/** One encrypted Gemini key shared by Jarvis, chat, Script Writer, and Social tools. */
object GeminiApiKeyStore {
    private const val SECURE_KEY = "gemini_api_key"
    private const val PREFERENCES_NAME = "droidcraft"
    private const val LEGACY_KEY = "gemini_key"

    fun read(context: Context): String {
        val appContext = context.applicationContext
        val secureStore = SecureKeyStore(appContext)
        val saved = secureStore.read(SECURE_KEY)
        val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val legacy = preferences.getString(LEGACY_KEY, "").orEmpty()
        if (!saved.isNullOrBlank()) {
            if (legacy.isNotBlank()) preferences.edit().remove(LEGACY_KEY).apply()
            return saved
        }
        if (legacy.isBlank()) return ""
        secureStore.write(SECURE_KEY, legacy)
        preferences.edit().remove(LEGACY_KEY).apply()
        return legacy
    }

    fun write(context: Context, key: String) {
        val appContext = context.applicationContext
        SecureKeyStore(appContext).write(SECURE_KEY, key.trim())
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().remove(LEGACY_KEY).apply()
    }

    fun clear(context: Context) {
        val appContext = context.applicationContext
        SecureKeyStore(appContext).remove(SECURE_KEY)
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().remove(LEGACY_KEY).apply()
    }
}
