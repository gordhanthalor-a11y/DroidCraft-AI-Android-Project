package com.droidcraft.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Synthesizes Jarvis replies in the user's selected ElevenLabs voice. */
class ElevenLabsTtsClient(private val apiKey: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun synthesizePcm(text: String): ByteArray = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Add an ElevenLabs API key in Settings." }
        require(text.isNotBlank()) { "There is no reply text to speak." }

        val body = JSONObject()
            .put("text", text)
            .put("model_id", MODEL_ID)
            .put(
                "voice_settings",
                JSONObject()
                    .put("stability", 0.38)
                    .put("similarity_boost", 0.82)
                    .put("style", 0.68)
                    .put("use_speaker_boost", true)
            )
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-speech/$BUNTY_VOICE_ID?output_format=pcm_24000")
            .header("xi-api-key", apiKey)
            .header("Accept", "audio/pcm")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val responseText = response.body?.string().orEmpty()
                val message = runCatching {
                    val error = JSONObject(responseText).optJSONObject("detail")
                    error?.optString("message")
                        ?: JSONObject(responseText).optString("message")
                }.getOrNull().orEmpty()
                throw ElevenLabsException(response.code, message)
            }
            response.body?.bytes()?.takeIf { it.isNotEmpty() }
                ?: throw ElevenLabsException(response.code, "ElevenLabs returned no audio.")
        }
    }

    companion object {
        const val BUNTY_VOICE_ID = "7b9mYhmnp0y2qSH1FnBL"
        private const val MODEL_ID = "eleven_multilingual_v2"
    }
}

class ElevenLabsException(
    val httpStatus: Int,
    detail: String
) : Exception(
    detail.takeIf { it.isNotBlank() } ?: "ElevenLabs request failed (HTTP $httpStatus)."
)
