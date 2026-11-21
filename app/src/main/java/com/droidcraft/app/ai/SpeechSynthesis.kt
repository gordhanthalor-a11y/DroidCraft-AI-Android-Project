package com.droidcraft.app.ai

import android.content.Context
import android.os.SystemClock
import com.droidcraft.app.data.GeminiApiKeyStore
import com.droidcraft.app.jarvis.character.Emotion
import com.droidcraft.app.jarvis.character.EmotionEngine
import com.droidcraft.app.jarvis.voice.SpeechProvider
import com.droidcraft.app.jarvis.voice.VoiceSettings
import kotlinx.coroutines.CancellationException

/** Speech implementations return mono signed 16-bit PCM at 24 kHz, ready for [PcmAudioPlayer]. */
interface SpeechSynthesisProvider {
    val provider: SpeechProvider
    suspend fun synthesizePcm(text: String, emotion: Emotion): ByteArray
}

private class GeminiSpeechProvider(apiKey: String, voiceName: String) : SpeechSynthesisProvider {
    override val provider = SpeechProvider.GEMINI
    private val client = GeminiTtsClient(apiKey, voiceName)

    override suspend fun synthesizePcm(text: String, emotion: Emotion): ByteArray =
        client.synthesizePcm(text, EmotionEngine.geminiInstruction(emotion))
}

private class ElevenLabsSpeechProvider(apiKey: String, voiceId: String) : SpeechSynthesisProvider {
    override val provider = SpeechProvider.ELEVENLABS
    private val client = ElevenLabsTtsClient(apiKey, voiceId)

    override suspend fun synthesizePcm(text: String, emotion: Emotion): ByteArray {
        val (style, stability) = when (emotion) {
            Emotion.HAPPY -> 0.76f to 0.34f
            Emotion.HUMOR -> 0.82f to 0.30f
            Emotion.SAD -> 0.24f to 0.72f
            Emotion.ANGRY -> 0.35f to 0.65f
            Emotion.ENCOURAGING -> 0.70f to 0.42f
            Emotion.THINKING -> 0.45f to 0.60f
            else -> 0.58f to 0.50f
        }
        return client.synthesizePcm(text, style, stability)
    }
}

data class SpeechSynthesisResult(
    val pcm: ByteArray,
    val requestedProvider: SpeechProvider,
    val actualProvider: SpeechProvider,
    /** Non-null when a missing key or provider failure caused a user-visible route change. */
    val fallbackNotice: String? = null
)

/** Measured synthesis request timings collected on-device; values are observations, not estimates. */
data class SpeechProviderLatency(
    val provider: SpeechProvider,
    val sampleCount: Long,
    val averageMs: Long?,
    val lastMs: Long?,
    val lastSucceeded: Boolean?
)

object SpeechProviderTelemetry {
    private const val PREFS = "jarvis_speech_provider_latency"
    private val lock = Any()

    fun record(context: Context, provider: SpeechProvider, elapsedMs: Long, succeeded: Boolean) {
        synchronized(lock) {
            val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val prefix = provider.name.lowercase()
            val countKey = "${prefix}_count"
            val totalKey = "${prefix}_total_ms"
            val count = preferences.getLong(countKey, 0L)
            val total = preferences.getLong(totalKey, 0L)
            val duration = elapsedMs.coerceAtLeast(0L)
            preferences.edit()
                .putLong(countKey, (count + 1L).coerceAtLeast(1L))
                .putLong(totalKey, (total + duration).coerceAtLeast(total))
                .putLong("${prefix}_last_ms", duration)
                .putBoolean("${prefix}_last_ok", succeeded)
                .apply()
        }
    }

    fun read(context: Context, provider: SpeechProvider): SpeechProviderLatency {
        val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = provider.name.lowercase()
        val count = preferences.getLong("${prefix}_count", 0L)
        val lastKey = "${prefix}_last_ms"
        val successKey = "${prefix}_last_ok"
        return SpeechProviderLatency(
            provider = provider,
            sampleCount = count,
            averageMs = if (count > 0) preferences.getLong("${prefix}_total_ms", 0L) / count else null,
            lastMs = if (preferences.contains(lastKey)) preferences.getLong(lastKey, 0L) else null,
            lastSucceeded = if (preferences.contains(successKey)) preferences.getBoolean(successKey, false) else null
        )
    }

    fun all(context: Context): List<SpeechProviderLatency> =
        SpeechProvider.values().map { read(context, it) }
}

class SpeechSynthesisException(
    val requestedProvider: SpeechProvider,
    detail: String
) : Exception(detail)

/** Routes both ordinary replies and Gemini Live transcripts through the same provider contract. */
object SpeechSynthesisRouter {
    suspend fun synthesize(
        context: Context,
        text: String,
        emotion: Emotion,
        requestedProvider: SpeechProvider = VoiceSettings.speechProvider(context),
        geminiApiKeyOverride: String? = null
    ): SpeechSynthesisResult {
        val appContext = context.applicationContext
        val geminiKey = geminiApiKeyOverride?.trim() ?: GeminiApiKeyStore.read(appContext)
        val candidates = mutableListOf<SpeechSynthesisProvider>()
        val routeNotes = mutableListOf<String>()

        if (requestedProvider == SpeechProvider.ELEVENLABS) {
            val elevenKey = runCatching { VoiceSettings.elevenLabsApiKey(appContext) }
                .getOrDefault("")
            if (elevenKey.isBlank()) {
                routeNotes += "No ElevenLabs key is saved."
            } else if (VoiceSettings.isElevenLabsPlanBlocked()) {
                routeNotes += "A previous ElevenLabs response reported a plan or credit restriction; save the key again to retry it."
            } else {
                candidates += ElevenLabsSpeechProvider(elevenKey, VoiceSettings.elevenVoiceId(appContext))
            }
            if (geminiKey.isNotBlank()) {
                candidates += GeminiSpeechProvider(geminiKey, VoiceSettings.geminiVoice(appContext))
            } else {
                routeNotes += "No Gemini key is saved for speech fallback."
            }
        } else if (geminiKey.isNotBlank()) {
            candidates += GeminiSpeechProvider(geminiKey, VoiceSettings.geminiVoice(appContext))
        } else {
            routeNotes += "No Gemini key is saved."
        }

        if (candidates.isEmpty()) {
            val detail = when (requestedProvider) {
                SpeechProvider.GEMINI -> "Gemini TTS needs a Gemini API key."
                SpeechProvider.ELEVENLABS -> "ElevenLabs needs an ElevenLabs API key; Gemini TTS fallback also needs a Gemini API key."
            }
            throw SpeechSynthesisException(requestedProvider, detail)
        }

        val failures = mutableListOf<String>()
        for (candidate in candidates) {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val pcm = candidate.synthesizePcm(text, emotion)
                SpeechProviderTelemetry.record(
                    appContext,
                    candidate.provider,
                    SystemClock.elapsedRealtime() - startedAt,
                    succeeded = true
                )
                if (candidate.provider == SpeechProvider.ELEVENLABS) VoiceSettings.clearElevenLabsPlanBlocked()
                val didSwitch = candidate.provider != requestedProvider
                val notice = if (didSwitch) {
                    val reason = (routeNotes + failures).joinToString(" ").ifBlank { "The selected route was unavailable." }
                    "${requestedProvider.displayName} is unavailable ($reason) Switching to ${candidate.provider.displayName}."
                } else {
                    null
                }
                return SpeechSynthesisResult(
                    pcm = pcm,
                    requestedProvider = requestedProvider,
                    actualProvider = candidate.provider,
                    fallbackNotice = notice
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                SpeechProviderTelemetry.record(
                    appContext,
                    candidate.provider,
                    SystemClock.elapsedRealtime() - startedAt,
                    succeeded = false
                )
                if (candidate.provider == SpeechProvider.ELEVENLABS &&
                    error is ElevenLabsException && error.isPaymentOrPlanError
                ) {
                    VoiceSettings.markElevenLabsPlanBlocked()
                }
                val detail = error.message
                    ?.replace(Regex("(?i)(api[_ -]?key|xi-api-key)\\s*[:=]\\s*[^\\s,;]+"), "credential=[redacted]")
                    ?.take(240)
                    ?.ifBlank { null }
                    ?: error.javaClass.simpleName
                failures += "${candidate.provider.displayName} failed: $detail."
            }
        }

        val message = (routeNotes + failures).joinToString(" ")
            .ifBlank { "No configured speech provider returned audio." }
        throw SpeechSynthesisException(requestedProvider, message)
    }
}
