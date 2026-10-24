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

class GeminiClient(private val apiKey: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val systemPrompt = "Tum DroidCraft ke AI Creator Assistant ho, ek Hindi farming/agriculture YouTube creator (GT Marwadi Vlogs) ki madad karte ho: video ideas, scripts, titles, descriptions, tags, thumbnails ke ideas, Shorts hooks, SEO aur posting plan. User jis bhasha mein likhe (Hindi, Hinglish ya English) usi mein chhota, kaam ka jawab do."

    /** history: list of (isUser, text) pairs, oldest first, including the latest user message. */
    suspend fun reply(history: List<Pair<Boolean, String>>): String = withContext(Dispatchers.IO) {
        val contents = JSONArray()
        history.takeLast(20).forEach { (isUser, text) ->
            contents.put(JSONObject()
                .put("role", if (isUser) "user" else "model")
                .put("parts", JSONArray().put(JSONObject().put("text", text))))
        }
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            .put("contents", contents)
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(raw).getJSONObject("error").getString("message") }.getOrDefault(raw.take(200))
                throw IllegalStateException("Gemini error ${resp.code}: $msg")
            }
            val parts = JSONObject(raw).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            buildString { for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text")) }.trim()
        }
    }
}
