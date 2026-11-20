package com.droidcraft.app.ai

import com.droidcraft.app.data.GeminiModelSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiClient(
    private val apiKey: String,
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    modelOrder: List<String> = GeminiModelSettings.DEFAULT_MODELS,
    private val onModelSelected: ((String) -> Unit)? = null
) {
    private val client = newHttpClient()
    private val models = runCatching { GeminiModelSettings.normalize(modelOrder) }
        .getOrDefault(GeminiModelSettings.DEFAULT_MODELS)
        .ifEmpty { GeminiModelSettings.DEFAULT_MODELS }

    companion object {
        /** Kept as a source-compatible name for existing callers; requests use the ordered list. */
        const val MODEL_NAME = "gemini-3.8-flash"
        private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val MODEL_LIST_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val MAX_MODEL_LIST_PAGES = 5
        private const val RETRY_503_DELAY_MS = 1_500L

        val DEFAULT_SYSTEM_PROMPT = "Tum DroidCraft ke AI Creator Assistant ho, ek Hindi farming/agriculture YouTube creator (GT Marwadi Vlogs) ki madad karte ho: video ideas, scripts, titles, descriptions, tags, thumbnails ke ideas, Shorts hooks, SEO aur posting plan. User jis bhasha mein likhe (Hindi, Hinglish ya English) usi mein chhota, kaam ka jawab do."

        private fun newHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(100, TimeUnit.SECONDS)
            .build()

        /**
         * Fetches models visible to this API key that support text generateContent.
         * The returned IDs are sorted by the app's preferred order, then alphabetically.
         */
        suspend fun fetchAvailableTextModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
            require(apiKey.isNotBlank()) { "Add a Gemini API key before refreshing available models." }
            val client = newHttpClient()
            val available = linkedSetOf<String>()
            var pageToken: String? = null
            var pages = 0

            do {
                val url = MODEL_LIST_URL.toHttpUrl().newBuilder()
                    .addQueryParameter("pageSize", "100")
                    .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                    .build()
                val request = Request.Builder()
                    .url(url)
                    .header("x-goog-api-key", apiKey)
                    .get()
                    .build()
                val responseJson = client.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw createHttpException(response.code, raw, response.message, modelId = null)
                    }
                    runCatching { JSONObject(raw) }.getOrElse {
                        throw IllegalStateException("Google returned an unreadable model list.")
                    }
                }
                val responseModels = responseJson.optJSONArray("models") ?: JSONArray()
                for (index in 0 until responseModels.length()) {
                    val item = responseModels.optJSONObject(index) ?: continue
                    val modelId = item.optString("name")
                        .removePrefix("models/")
                        .trim()
                    val methods = item.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                    val supportsTextGeneration = (0 until methods.length()).any { methodIndex ->
                        methods.optString(methodIndex).equals("generateContent", ignoreCase = true)
                    }
                    if (supportsTextGeneration && isTextModel(modelId)) available += modelId
                }
                pageToken = responseJson.optString("nextPageToken")
                    .takeIf { it.isNotBlank() && it != "null" }
                pages += 1
            } while (pageToken != null && pages < MAX_MODEL_LIST_PAGES)

            if (available.isEmpty()) {
                throw IllegalStateException("Google listed no Gemini text models supporting generateContent for this API key.")
            }
            val defaultRank = GeminiModelSettings.DEFAULT_MODELS.withIndex().associate { it.value to it.index }
            available.sortedWith(
                compareBy<String> { defaultRank[it] ?: Int.MAX_VALUE }
                    .thenBy { it }
            )
        }

        private fun isTextModel(modelId: String): Boolean {
            if (!modelId.startsWith("gemini-", ignoreCase = true)) return false
            val lowered = modelId.lowercase()
            return listOf("-live", "-tts", "-image", "-transcribe", "-translate", "-embedding", "nano-banana", "omni")
                .none { lowered.contains(it) }
        }

        private fun createHttpException(
            httpStatus: Int,
            rawBody: String,
            responseMessage: String,
            modelId: String?
        ): GeminiHttpException {
            val json = runCatching { JSONObject(rawBody) }.getOrNull()
            val error = json?.optJSONObject("error")
            val message = error?.optString("message")
                ?.takeIf { it.isNotBlank() && it != "null" }
                ?: rawBody.trim().takeIf { it.isNotBlank() }
                ?: responseMessage.ifBlank { "No error details returned by Gemini." }
            val status = error?.optString("status")
                ?.takeIf { it.isNotBlank() && it != "null" }
            return GeminiHttpException(httpStatus, status, modelId, message)
        }
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
            .toString()

        val failures = mutableListOf<String>()
        for (model in models) {
            try {
                val reply = requestModel(model, body)
                runCatching { onModelSelected?.invoke(model) }
                return@withContext reply
            } catch (firstFailure: GeminiHttpException) {
                if (!firstFailure.isRetryableModelFailure()) throw firstFailure
                failures += describeFailure(model, firstFailure)

                if (firstFailure.httpStatus == 503) {
                    delay(RETRY_503_DELAY_MS)
                    try {
                        val reply = requestModel(model, body)
                        runCatching { onModelSelected?.invoke(model) }
                        return@withContext reply
                    } catch (retryFailure: Exception) {
                        if (retryFailure is CancellationException) throw retryFailure
                        failures += "$model retry: ${retryFailure.message ?: retryFailure.javaClass.simpleName}"
                    }
                }
            }
        }

        throw IllegalStateException(
            "All configured Gemini models failed. ${failures.joinToString("; ")}. " +
                "Edit or refresh the ordered model list in Settings and try again."
        )
    }

    private fun describeFailure(model: String, failure: GeminiHttpException): String =
        "$model: HTTP ${failure.httpStatus}" +
            (failure.providerStatus?.let { " ($it)" } ?: "") +
            ": ${failure.providerMessage.take(240)}"

    private fun requestModel(
        model: String,
        body: String
    ): String {
        val request = Request.Builder()
            .url("$API_BASE/$model:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw createHttpException(response.code, raw, response.message, model)
            }

            val parsed = runCatching { JSONObject(raw) }.getOrNull()
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
            val reply = buildString {
                for (index in 0 until parts.length()) {
                    append(parts.optJSONObject(index)?.optString("text").orEmpty())
                }
            }.trim()
            if (reply.isBlank()) throw IllegalStateException("Gemini returned an empty text reply.")
            return reply
        }
    }
}

private class GeminiHttpException(
    val httpStatus: Int,
    val providerStatus: String?,
    val modelId: String?,
    val providerMessage: String
) : Exception(
    "Gemini error $httpStatus" +
        (providerStatus?.let { " ($it)" } ?: "") +
        (modelId?.let { " for $it" } ?: "") +
        ": $providerMessage"
) {
    fun isRetryableModelFailure(): Boolean = httpStatus == 503 || httpStatus == 429 || httpStatus == 404
}
