package com.droidcraft.app.ai

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Gemini 3.8 TTS client. Delivery guidance is separate from the verbatim transcript. */
class GeminiTtsClient(
    private val apiKey: String,
    private val voiceName: String
) {
    private val client: OkHttpClient
        get() = sharedClient

    /** Returns mono, signed 16-bit little-endian PCM at 24 kHz for [PcmAudioPlayer]. */
    suspend fun synthesizePcm(text: String, styleInstruction: String? = null): ByteArray =
        withContext(Dispatchers.IO) {
            require(apiKey.isNotBlank()) { "Add a Gemini API key in Settings before using Gemini TTS." }
            require(text.isNotBlank()) { "There is no reply text to speak." }

            val input = JSONObject()
                .put("type", "text")
                .put("text", text)
            styleInstruction
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.take(MAX_STYLE_LENGTH)
                ?.let { style ->
                    input.put(
                        "annotations",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "speech_metadata")
                                .put("style", style)
                        )
                    )
                }

            val body = JSONObject()
                .put("model", MODEL)
                .put("input", JSONArray().put(input))
                .put("response_format", JSONObject().put("type", "audio"))
                .put(
                    "generation_config",
                    JSONObject().put(
                        "speech_config",
                        JSONArray().put(JSONObject().put("voice", voiceName.ifBlank { DEFAULT_VOICE }))
                    )
                )
                .toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val url = HttpUrl.Builder()
                .scheme("https")
                .host("generativelanguage.googleapis.com")
                .addPathSegments("v1/interactions")
                .build()
            val request = Request.Builder()
                .url(url)
                .header("x-goog-api-key", apiKey)
                .header("Accept", "application/json")
                .post(body)
                .build()

            val response = client.newCall(request).awaitPayload()
            val responseText = response.body.toString(Charsets.UTF_8)
            if (!response.isSuccessful) {
                throw GeminiTtsException(response.code, providerMessage(responseText, response.message))
            }
            val root = runCatching { JSONObject(responseText) }.getOrElse {
                throw GeminiTtsException(response.code, "Gemini TTS returned invalid JSON.")
            }
            val (encodedAudio, mimeType) = findAudio(root)
                ?: throw GeminiTtsException(
                    response.code,
                    root.optJSONObject("promptFeedback")?.optString("blockReason")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { "Gemini TTS did not return audio (blocked: $it)." }
                        ?: "Gemini TTS returned no audio output."
                )
            val bytes = decodeBase64Audio(encodedAudio)
                ?: throw GeminiTtsException(response.code, "Gemini TTS returned invalid or empty audio data.")
            normalizeAudio(bytes, mimeType, response.code)
        }

    private fun findAudio(root: JSONObject): Pair<String, String>? {
        root.optJSONObject("output_audio")?.let { audio ->
            val encoded = audio.optString("data").takeIf { it.isNotBlank() }
            if (encoded != null) {
                return encoded to audio.optString("mime_type", audio.optString("mimeType", ""))
            }
        }
        root.optJSONArray("outputs")?.let { outputs ->
            for (index in 0 until outputs.length()) {
                val audio = outputs.optJSONObject(index) ?: continue
                if (audio.optString("type") != "audio") continue
                val encoded = audio.optString("data").takeIf { it.isNotBlank() } ?: continue
                return encoded to audio.optString("mime_type", "")
            }
        }
        // Keep parsing tolerant of the GenerateContent audio response shape during API rollout.
        val parts = root.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
        if (parts != null) {
            for (index in 0 until parts.length()) {
                val inline = parts.optJSONObject(index)?.let {
                    it.optJSONObject("inlineData") ?: it.optJSONObject("inline_data")
                } ?: continue
                val encoded = inline.optString("data").takeIf { it.isNotBlank() } ?: continue
                return encoded to inline.optString("mimeType", inline.optString("mime_type", ""))
            }
        }
        return null
    }

    private fun decodeBase64Audio(value: String): ByteArray? {
        val encoded = value.substringAfter("base64,", value).trim()
        if (encoded.isBlank()) return null
        return runCatching { Base64.decode(encoded, Base64.DEFAULT) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    /** The unary Interactions API returns a WAV container; streaming audio is raw PCM. */
    private fun normalizeAudio(audio: ByteArray, mimeType: String, status: Int): ByteArray {
        if (audio.size >= 12 && ascii(audio, 0, 4) == "RIFF" && ascii(audio, 8, 4) == "WAVE") {
            return extractPcmFromWav(audio, status)
        }
        val rate = Regex("(?i)rate\\s*=\\s*(\\d+)")
            .find(mimeType)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        if (rate != null && rate != SAMPLE_RATE) {
            throw GeminiTtsException(status, "Gemini TTS returned ${rate}Hz PCM; expected ${SAMPLE_RATE}Hz.")
        }
        if (mimeType.isNotBlank() && !mimeType.contains("pcm", ignoreCase = true) &&
            !mimeType.contains("l16", ignoreCase = true)
        ) {
            throw GeminiTtsException(status, "Gemini TTS returned an unsupported audio format ($mimeType).")
        }
        if (audio.size < 2 || audio.size % 2 != 0) {
            throw GeminiTtsException(status, "Gemini TTS returned malformed 16-bit PCM audio.")
        }
        return audio
    }

    private fun extractPcmFromWav(wav: ByteArray, status: Int): ByteArray {
        var position = 12
        var format = -1
        var channels = -1
        var sampleRate = -1L
        var bitsPerSample = -1
        var pcm: ByteArray? = null

        while (position + 8 <= wav.size) {
            val chunkName = ascii(wav, position, 4)
            val chunkSize = readUInt32LE(wav, position + 4)
            val dataStart = position + 8
            val dataEnd = dataStart.toLong() + chunkSize
            if (dataEnd > wav.size || chunkSize > Int.MAX_VALUE) {
                throw GeminiTtsException(status, "Gemini TTS returned a truncated WAV file.")
            }
            when (chunkName) {
                "fmt " -> {
                    if (chunkSize < 16) throw GeminiTtsException(status, "Gemini TTS returned an invalid WAV format chunk.")
                    format = readUInt16LE(wav, dataStart)
                    channels = readUInt16LE(wav, dataStart + 2)
                    sampleRate = readUInt32LE(wav, dataStart + 4)
                    bitsPerSample = readUInt16LE(wav, dataStart + 14)
                }
                "data" -> pcm = wav.copyOfRange(dataStart, dataEnd.toInt())
            }
            position = dataEnd.toInt() + (chunkSize.toInt() and 1)
            if (position > wav.size) break
        }

        if (format != PCM_FORMAT || channels != 1 || sampleRate != SAMPLE_RATE.toLong() || bitsPerSample != 16) {
            throw GeminiTtsException(
                status,
                "Gemini TTS WAV must be mono 24 kHz 16-bit PCM (received ${sampleRate}Hz, $channels channel(s), $bitsPerSample-bit)."
            )
        }
        return pcm?.takeIf { it.isNotEmpty() && it.size % 2 == 0 }
            ?: throw GeminiTtsException(status, "Gemini TTS WAV contains no usable PCM audio.")
    }

    private fun ascii(bytes: ByteArray, offset: Int, count: Int): String =
        String(bytes, offset, count, Charsets.US_ASCII)

    private fun readUInt16LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun readUInt32LE(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private fun providerMessage(responseText: String, fallback: String): String {
        val root = runCatching { JSONObject(responseText) }.getOrNull()
            ?: return responseText.trim().take(500).ifBlank { fallback }
        val error = root.optJSONObject("error")
        val message = error?.optString("message")?.takeIf { it.isNotBlank() && it != "null" }
        val status = error?.optString("status")?.takeIf { it.isNotBlank() && it != "null" }
        return listOfNotNull(message, status?.let { "[$it]" })
            .joinToString(" ")
            .ifBlank { responseText.trim().take(500).ifBlank { fallback } }
    }

    companion object {
        const val MODEL = "gemini-3.8-flash-tts"
        const val DEFAULT_VOICE = "Charon"
        const val SAMPLE_RATE = 24_000
        private const val PCM_FORMAT = 1
        private const val MAX_STYLE_LENGTH = 1_000
        private val sharedClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(110, TimeUnit.SECONDS)
            .build()
    }
}

class GeminiTtsException(val httpStatus: Int, detail: String) :
    IllegalStateException(detail.takeIf { it.isNotBlank() } ?: "Gemini TTS failed (HTTP $httpStatus).")
