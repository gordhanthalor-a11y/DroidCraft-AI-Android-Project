package com.droidcraft.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiClient(
    private val apiKey: String,
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(100, TimeUnit.SECONDS)
        .build()

    companion object {
        const val MODEL_NAME = "gemini-3.8-flash"
        private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"

        val DEFAULT_SYSTEM_PROMPT = "Tum DroidCraft ke AI Creator Assistant ho, ek Hindi farming/agriculture YouTube creator (GT Marwadi Vlogs) ki madad karte ho: video ideas, scripts, titles, descriptions, tags, thumbnails ke ideas, Shorts hooks, SEO aur posting plan. User jis bhasha mein likhe (Hindi, Hinglish ya English) usi mein chhota, kaam ka jawab do."
    }

    /** History is ordered oldest-first and includes the latest user message. */
    suspend fun reply(history: List<Pair<Boolean, String>>): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Add a Gemini API key in Settings." }
        require(history.isNotEmpty()) { "Send a message before requesting a Gemini reply." }

        val contents = JSONArray()
        history.takeLast(20).forEach { (isUser, text) ->
            contents.put(
                JSONObject()
                    .put("role", if (isUser) "user" else "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", text)))
            )
        }
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            .put("contents", contents)
        val request = Request.Builder()
            .url("$API_BASE/$MODEL_NAME:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrNull()
            if (!response.isSuccessful) {
                val providerMessage = json?.optJSONObject("error")
                    ?.optString("message")
                    ?.takeIf { it.isNotBlank() }
                    ?: raw.trim().takeIf { it.isNotBlank() }
                    ?: response.message.ifBlank { "No error details returned by Gemini." }
                val providerStatus = json?.optJSONObject("error")
                    ?.optString("status")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { " ($it)" }
                    .orEmpty()
                throw IllegalStateException("Gemini error ${response.code}$providerStatus: $providerMessage")
            }

            val parsed = json
                ?: throw IllegalStateException("Gemini returned an unreadable response: ${raw.take(240)}")
            val candidates = parsed.optJSONArray("candidates")
                ?: throw IllegalStateException("Gemini returned no candidates: ${raw.take(240)}")
            if (candidates.length() == 0) {
                val reason = parsed.optJSONObject("promptFeedback")
                    ?.optString("blockReason")
                    ?.takeIf { it.isNotBlank() && it != "null" }
                    ?: "The provider returned no candidates."
                throw IllegalStateException("Gemini returned no reply: $reason")
            }
            val candidate = candidates.optJSONObject(0)
                ?: throw IllegalStateException("Gemini returned an empty candidate.")
            val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
            if (parts == null) {
                val reason = candidate.optString("finishReason").takeIf { it.isNotBlank() }
                    ?: "The response did not contain text."
                throw IllegalStateException("Gemini returned no text: $reason")
            }
            buildString {
                for (index in 0 until parts.length()) {
                    append(parts.optJSONObject(index)?.optString("text").orEmpty())
                }
            }.trim()
        }
    }
}
