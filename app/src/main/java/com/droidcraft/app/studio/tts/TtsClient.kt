package com.droidcraft.app.studio.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.droidcraft.app.ai.awaitPayload
import com.droidcraft.app.studio.StudioSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Voice Studio client — server के TTS endpoints से MP3 और voice list लाता है।
 */
class TtsClient(private val context: Context) {

    companion object {
        private const val TAG = "TtsClient"
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    data class Voice(val id: String, val name: String)

    /** Server की voice list; fail हो तो built-in fallback. */
    suspend fun voices(provider: String): List<Voice> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${StudioSettings.requireServerUrl(context)}/api/v1/tts/voices?provider=$provider"
            val resp = client.newCall(Request.Builder().url(url).build()).awaitPayload()
            val responseText = resp.body.toString(Charsets.UTF_8)
            if (!resp.isSuccessful) throw IllegalStateException("TTS ${resp.code}: ${responseText.take(200)}")
            val arr = JSONObject(responseText).optJSONArray("voices") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Voice(o.optString("id"), o.optString("name"))
            }
        }.getOrElse {
            Log.e(TAG, "voices fail", it)
            emptyList()
        }
    }

    /**
     * Text → MP3 bytes (server par generate hota है)।
     * provider key app की settings से header में जाती है (server उसे env जैसा use करता है)।
     */
    suspend fun synthesize(
        text: String,
        provider: String,
        voice: String,
        speed: Float,
        sentenceMode: Boolean,
        style: Float = 0.35f,
        stability: Float = 0.5f,
        elevenKey: String = "",
        styleRequest: String = ""
    ): ByteArray = withContext(Dispatchers.IO) {
        val url = "${StudioSettings.requireServerUrl(context)}/api/v1/tts"
        val body = JSONObject().apply {
            put("text", text)
            put("provider", provider)
            put("voice", voice)
            put("speed", speed.toDouble())
            put("sentence_mode", sentenceMode)
            put("style", style.toDouble())
            put("stability", stability.toDouble())
            put("style_request", styleRequest)
        }.toString().toRequestBody("application/json".toMediaType())

        val builder = Request.Builder().url(url).post(body)
        // keys server पर भेजने के लिए headers (server env override के बाद यह भी पढ़ेगा)
        val key = when (provider) {
            "smallest" -> StudioSettings.smallestKey(context)
            "heygen" -> StudioSettings.heygenKey(context)
            "elevenlabs" -> elevenKey
            else -> ""
        }
        if (key.isNotBlank()) {
            builder.addHeader("X-Provider-Key", key)
            if (provider == "elevenlabs") builder.addHeader("X-ElevenLabs-Key", key)
        }
        val response = client.newCall(builder.build()).awaitPayload()
        if (!response.isSuccessful) {
            val responseText = response.body.toString(Charsets.UTF_8)
            throw TtsRequestException(response.code, provider, readableError(responseText))
        }
        response.body.takeIf { it.isNotEmpty() } ?: throw IllegalStateException("खाली audio मिली")
    }

    /**
     * Local, free Android TTS fallback for Voice Studio. The engine writes a playable WAV
     * under app-private storage so the user can preview and share it like generated server audio.
     */
    suspend fun synthesizePhoneWav(text: String, speed: Float): File = withContext(Dispatchers.Main.immediate) {
        require(text.isNotBlank()) { "पहले script लिखो" }
        val directory = File(context.filesDir, "voice_studio").apply { mkdirs() }
        val outputFile = File(directory, "phone_voice_${System.currentTimeMillis()}.wav")
        val utteranceId = "voice_studio_${System.nanoTime()}"

        suspendCancellableCoroutine { continuation ->
            val engineRef = AtomicReference<TextToSpeech?>()
            val completed = AtomicBoolean(false)
            val initialized = AtomicBoolean(false)
            val initStatus = AtomicReference<Int?>()

            fun disposeEngine(stop: Boolean = false) {
                engineRef.getAndSet(null)?.let { engine ->
                    if (stop) runCatching { engine.stop() }
                    runCatching { engine.shutdown() }
                }
            }

            fun finish(result: Result<File>) {
                if (!completed.compareAndSet(false, true)) return
                disposeEngine()
                result.fold(
                    onSuccess = { file ->
                        if (continuation.isActive) continuation.resume(file) else file.delete()
                    },
                    onFailure = { error ->
                        outputFile.delete()
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                )
            }

            fun handleInitialization(status: Int) {
                if (completed.get() || !continuation.isActive) {
                    disposeEngine(stop = true)
                    outputFile.delete()
                    return
                }
                val engine = engineRef.get() ?: return
                if (!initialized.compareAndSet(false, true)) return
                if (status != TextToSpeech.SUCCESS) {
                    finish(Result.failure(IllegalStateException("Android phone voice engine could not start.")))
                    return
                }

                val hindiStatus = engine.setLanguage(Locale("hi", "IN"))
                if (hindiStatus == TextToSpeech.LANG_MISSING_DATA || hindiStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
                    val englishStatus = engine.setLanguage(Locale.US)
                    if (englishStatus == TextToSpeech.LANG_MISSING_DATA || englishStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
                        finish(Result.failure(IllegalStateException("This phone has no installed TTS voice.")))
                        return
                    }
                }
                engine.setSpeechRate(speed.coerceIn(0.5f, 2.0f))
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit

                    override fun onDone(id: String?) {
                        if (id != utteranceId) return
                        if (outputFile.isFile && outputFile.length() > 0L) {
                            finish(Result.success(outputFile))
                        } else {
                            finish(Result.failure(IllegalStateException("Phone TTS returned an empty audio file.")))
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) {
                        if (id == utteranceId) {
                            finish(Result.failure(IllegalStateException("Android phone voice could not generate this audio.")))
                        }
                    }
                })
                val result = engine.synthesizeToFile(text, Bundle(), outputFile, utteranceId)
                if (result == TextToSpeech.ERROR) {
                    finish(Result.failure(IllegalStateException("Android phone voice could not start audio generation.")))
                }
            }

            continuation.invokeOnCancellation {
                if (completed.compareAndSet(false, true)) {
                    disposeEngine(stop = true)
                    outputFile.delete()
                }
            }

            val engine = TextToSpeech(context.applicationContext) { status ->
                initStatus.set(status)
                handleInitialization(status)
            }
            engineRef.set(engine)
            if (completed.get()) disposeEngine(stop = true)
            initStatus.get()?.let(::handleInitialization)
        }
    }

    private fun readableError(responseText: String): String {
        if (responseText.isBlank()) return "The server returned no error details."
        val root = runCatching { JSONObject(responseText) }.getOrNull() ?: return responseText.take(500)
        val detail = root.opt("detail")
        if (detail is JSONObject) {
            val fields = listOf("status", "message", "code")
                .map { key -> detail.optString(key).takeIf { it.isNotBlank() && it != "null" } }
                .filterNotNull()
            return (fields.ifEmpty { listOf(detail.toString()) }).joinToString(": ").take(500)
        }
        return (detail?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: responseText).take(500)
    }

    /** MP3 को app storage में सहेजो, file वापस दो। */
    fun saveMp3(bytes: ByteArray, name: String = "voice_${System.currentTimeMillis()}.mp3"): File {
        val dir = File(context.filesDir, "voice_studio").apply { mkdirs() }
        val f = File(dir, name)
        f.writeBytes(bytes)
        return f
    }
}

class TtsRequestException(
    val httpStatus: Int,
    val provider: String,
    val responseDetail: String
) : IllegalStateException(
    responseDetail.takeIf { it.isNotBlank() }?.let { "TTS $httpStatus: ${it.take(300)}" }
        ?: "TTS request failed (HTTP $httpStatus)."
) {
    val isPaymentOrPlanError: Boolean
        get() {
            if (!provider.equals("elevenlabs", ignoreCase = true)) return false
            val diagnostic = "$provider $responseDetail ${message.orEmpty()}".lowercase()
            return httpStatus == 402 || Regex("""\b402\b""").containsMatchIn(diagnostic) || listOf(
                "paid_plan_required",
                "payment_required",
                "payment required",
                "paid plan",
                "plan required",
                "subscription required"
            ).any { diagnostic.contains(it) }
        }
}
