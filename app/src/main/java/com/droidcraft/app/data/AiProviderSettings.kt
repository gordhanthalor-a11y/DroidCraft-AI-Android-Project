package com.droidcraft.app.data

import android.content.Context

/** Providers that implement Jarvis's text/function-calling brain. Gemini Live and TTS remain separate. */
enum class AiProvider(val displayName: String, val summary: String) {
    GEMINI("Gemini", "Google Generative Language API"),
    OPENROUTER("OpenRouter", "OpenAI-compatible routing API"),
    NVIDIA_NIM("NVIDIA NIM", "NVIDIA-hosted inference API")
}

data class AiProviderConfig(
    val provider: AiProvider,
    val apiKey: String,
    val fastModel: String,
    val strongModel: String,
    val modelOrder: List<String>
)

/** Encrypted provider credentials and local provider/model preferences. */
object AiProviderSettings {
    private const val PREFS = "droidcraft"
    private const val ACTIVE_PROVIDER = "ai_active_provider"
    private const val OPENROUTER_FAST = "openrouter_fast_model"
    private const val OPENROUTER_STRONG = "openrouter_strong_model"
    private const val NVIDIA_FAST = "nvidia_nim_fast_model"
    private const val NVIDIA_STRONG = "nvidia_nim_strong_model"

    val providersInFallbackOrder = listOf(AiProvider.GEMINI, AiProvider.OPENROUTER, AiProvider.NVIDIA_NIM)

    /** Presets include OpenRouter free-tier model IDs; availability and rate limits remain provider-controlled. */
    val modelPresets: Map<AiProvider, List<String>> = mapOf(
        AiProvider.GEMINI to GeminiModelSettings.DEFAULT_MODELS,
        AiProvider.OPENROUTER to listOf(
            "meta-llama/llama-3.3-70b-instruct:free",
            "google/gemini-2.0-flash-exp:free",
            "deepseek/deepseek-r1:free",
            "qwen/qwen3-235b-a22b:free",
            "openai/gpt-4o-mini"
        ),
        AiProvider.NVIDIA_NIM to listOf(
            "meta/llama-3.1-8b-instruct",
            "nvidia/llama-3.1-nemotron-nano-8b-v1",
            "meta/llama-3.3-70b-instruct",
            "qwen/qwen2.5-coder-32b-instruct"
        )
    )

    private val defaultModels = mapOf(
        AiProvider.OPENROUTER to ("meta-llama/llama-3.3-70b-instruct:free" to "openai/gpt-4o-mini"),
        AiProvider.NVIDIA_NIM to ("meta/llama-3.1-8b-instruct" to "meta/llama-3.3-70b-instruct")
    )

    fun activeProvider(context: Context): AiProvider = runCatching {
        AiProvider.valueOf(prefs(context).getString(ACTIVE_PROVIDER, AiProvider.GEMINI.name).orEmpty())
    }.getOrDefault(AiProvider.GEMINI)

    fun setActiveProvider(context: Context, provider: AiProvider) {
        prefs(context).edit().putString(ACTIVE_PROVIDER, provider.name).apply()
    }

    fun readKey(context: Context, provider: AiProvider): String = when (provider) {
        AiProvider.GEMINI -> GeminiApiKeyStore.read(context)
        AiProvider.OPENROUTER -> SecureKeyStore(context).read("openrouter_api_key").orEmpty()
        AiProvider.NVIDIA_NIM -> SecureKeyStore(context).read("nvidia_nim_api_key").orEmpty()
    }

    fun writeKey(context: Context, provider: AiProvider, key: String) {
        val cleaned = key.trim()
        when (provider) {
            AiProvider.GEMINI -> GeminiApiKeyStore.write(context, cleaned)
            AiProvider.OPENROUTER -> SecureKeyStore(context).write("openrouter_api_key", cleaned)
            AiProvider.NVIDIA_NIM -> SecureKeyStore(context).write("nvidia_nim_api_key", cleaned)
        }
    }

    fun hasKey(context: Context, provider: AiProvider): Boolean = readKey(context, provider).isNotBlank()

    fun readModels(context: Context, provider: AiProvider): Pair<String, String> {
        if (provider == AiProvider.GEMINI) {
            val order = GeminiModelSettings.read(context)
            val fast = order.firstOrNull() ?: GeminiModelSettings.DEFAULT_MODELS.first()
            val strong = order.firstOrNull { it != fast } ?: fast
            return fast to strong
        }
        val (defaultFast, defaultStrong) = defaultModels.getValue(provider)
        val preferences = prefs(context)
        val fastKey = if (provider == AiProvider.OPENROUTER) OPENROUTER_FAST else NVIDIA_FAST
        val strongKey = if (provider == AiProvider.OPENROUTER) OPENROUTER_STRONG else NVIDIA_STRONG
        val fast = preferences.getString(fastKey, defaultFast).orEmpty().ifBlank { defaultFast }
        val strong = preferences.getString(strongKey, defaultStrong).orEmpty().ifBlank { defaultStrong }
        return fast to strong
    }

    fun writeModel(context: Context, provider: AiProvider, strong: Boolean, model: String): String {
        require(provider != AiProvider.GEMINI) { "Manage Gemini model order in the Gemini model settings." }
        val normalized = model.trim()
        require(normalized.isNotBlank() && normalized.length <= 200 && !normalized.any { it.isWhitespace() }) {
            "Enter a model ID without spaces."
        }
        val key = when (provider) {
            AiProvider.OPENROUTER -> if (strong) OPENROUTER_STRONG else OPENROUTER_FAST
            AiProvider.NVIDIA_NIM -> if (strong) NVIDIA_STRONG else NVIDIA_FAST
            AiProvider.GEMINI -> error("Gemini models are configured through GeminiModelSettings.")
        }
        prefs(context).edit().putString(key, normalized).apply()
        return normalized
    }

    fun endpoint(provider: AiProvider): String = when (provider) {
        AiProvider.OPENROUTER -> "https://openrouter.ai/api/v1/chat/completions"
        AiProvider.NVIDIA_NIM -> "https://integrate.api.nvidia.com/v1/chat/completions"
        AiProvider.GEMINI -> error("Gemini uses its native Generative Language API client.")
    }

    /** Selected provider first, then only other providers for which this device has a saved key. */
    fun configuredProviders(context: Context): List<AiProviderConfig> {
        val active = activeProvider(context)
        return (listOf(active) + providersInFallbackOrder.filterNot { it == active })
            .mapNotNull { provider ->
                val key = readKey(context, provider)
                if (key.isBlank()) return@mapNotNull null
                val (fast, strong) = readModels(context, provider)
                val order = if (provider == AiProvider.GEMINI) {
                    GeminiModelSettings.read(context)
                } else {
                    listOf(fast, strong).distinct()
                }
                AiProviderConfig(provider, key, fast, strong, order)
            }
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
