package com.droidcraft.app.data

import android.content.Context

/** Ordered text-generation model IDs used by Chat and other Gemini text requests. */
object GeminiModelSettings {
    const val MAX_MODEL_COUNT = 10

    /**
     * The first ID is tried first. These are current Gemini text models; the Settings
     * screen can refresh this list against the models visible to the user's API key.
     */
    val DEFAULT_MODELS = listOf(
        "gemini-3.8-flash",
        "gemini-4-hydrogen",
        "gemini-3.6-flash",
        "gemini-3.5-flash-lite",
        "gemini-3.1-flash-lite"
    )

    private const val PREFERENCES_NAME = "droidcraft"
    private const val PREFERENCE_KEY = "gemini_text_model_order"
    private val modelIdPattern = Regex("^[A-Za-z0-9._-]{1,128}$")

    fun read(context: Context): List<String> {
        val saved = context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(PREFERENCE_KEY, null)
            ?: return DEFAULT_MODELS
        return runCatching { parse(saved) }
            .getOrDefault(DEFAULT_MODELS)
            .ifEmpty { DEFAULT_MODELS }
    }

    fun write(context: Context, modelIds: List<String>): List<String> {
        val normalized = normalize(modelIds)
        require(normalized.isNotEmpty()) { "Enter at least one Gemini model ID." }
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREFERENCE_KEY, normalized.joinToString("\n"))
            .apply()
        return normalized
    }

    /** Accepts one ID per line or comma-separated IDs, and strips a `models/` prefix. */
    fun parse(text: String): List<String> {
        val entries = text.split(Regex("[,\\r\\n]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return normalize(entries)
    }

    fun normalize(modelIds: List<String>): List<String> {
        val cleaned = modelIds.map { value ->
            value.trim().removePrefix("models/").also { model ->
                require(modelIdPattern.matches(model)) {
                    "Invalid Gemini model ID '$value'. Use an ID such as gemini-3.8-flash."
                }
            }
        }.distinct()
        require(cleaned.size <= MAX_MODEL_COUNT) {
            "Keep the ordered model list to $MAX_MODEL_COUNT IDs or fewer."
        }
        return cleaned
    }

    /** Keeps a user's chosen order, then appends other text-generation models from Google. */
    fun orderAvailable(available: List<String>, preferred: List<String>): List<String> {
        val availableSet = available.toSet()
        return (preferred.filter { it in availableSet } +
            DEFAULT_MODELS.filter { it in availableSet } +
            available)
            .distinct()
            .take(MAX_MODEL_COUNT)
    }
}
