package com.droidcraft.app.studio.tts

import android.content.Context
import android.util.Log
import com.droidcraft.app.studio.StudioSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

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
            val url = "${StudioSettings.serverUrl(context)}/api/v1/tts/voices?provider=$provider"
            val resp = client.newCall(Request.Builder().url(url).build()).execute()
            val arr = JSONObject(resp.body?.string().orEmpty()).optJSONArray("voices") ?: JSONArray()
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
        sentenceMode: Boolean
    ): ByteArray = withContext(Dispatchers.IO) {
        val url = "${StudioSettings.serverUrl(context)}/api/v1/tts"
        val body = JSONObject().apply {
            put("text", text)
            put("provider", provider)
            put("voice", voice)
            put("speed", speed.toDouble())
            put("sentence_mode", sentenceMode)
        }.toString().toRequestBody("application/json".toMediaType())

        val builder = Request.Builder().url(url).post(body)
        // keys server पर भेजने के लिए headers (server env override के बाद यह भी पढ़ेगा)
        val key = if (provider == "smallest") StudioSettings.smallestKey(context)
        else StudioSettings.heygenKey(context)
        if (key.isNotBlank()) {
            builder.addHeader("X-Provider-Key", key)
        }
        val resp = client.newCall(builder.build()).execute()
        if (!resp.isSuccessful) {
            val err = resp.body?.string().orEmpty()
            throw IllegalStateException("TTS ${resp.code}: ${err.take(200)}")
        }
        resp.body?.bytes() ?: throw IllegalStateException("खाली audio मिली")
    }

    /** MP3 को app storage में सहेजो, file वापस दो। */
    fun saveMp3(bytes: ByteArray, name: String = "voice_${System.currentTimeMillis()}.mp3"): File {
        val dir = File(context.filesDir, "voice_studio").apply { mkdirs() }
        val f = File(dir, name)
        f.writeBytes(bytes)
        return f
    }
}
