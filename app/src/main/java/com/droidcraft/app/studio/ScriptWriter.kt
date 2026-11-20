package com.droidcraft.app.studio

import android.content.Context
import com.droidcraft.app.ai.GeminiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * B5 — Script Writer: topic (या source link) → Gemini से Hindi script।
 * नियम: पहले 2 सेकंड में hook, greeting नहीं, facts सिर्फ़ source से।
 */
object ScriptWriter {

    data class Result(val script: String, val hook: String)

    suspend fun write(context: Context, topic: String, sourceUrl: String?): Result =
        withContext(Dispatchers.IO) {
            val apiKey = context.getSharedPreferences("droidcraft", Context.MODE_PRIVATE)
                .getString("gemini_key", "").orEmpty()
            if (apiKey.isBlank()) {
                return@withContext Result("", "")
            }
            val sourcePart = sourceUrl?.takeIf { it.isNotBlank() }?.let {
                "\nSource link (facts सिर्फ़ इसीसे): $it"
            } ?: ""

            val prompt = """
                Tum ek Hindi YouTube scriptwriter ho (channel: GT Marwadi Vlogs)।
                Topic: "$topic"$sourcePart

                Script लिखो ज़िद्दी नियमों के साथ:
                1. पहले 2 सेकंड में ज़ोरदार hook — दर्शक रुके।
                2. कोई greeting ("नमस्ते दोस्तों") नहीं — सीधे topic पर आओ।
                3. Facts सिर्फ़ source link से; अगर source न हो तो सामान्य जानकारी।
                4. छोटे paragraphs, हिंदी (Devanagari), spoken style।
                5. अंत में CTA (subscribe + comment)।
                6. कुल लंबाई: 2-4 मिनट की video के लिए ~500-700 शब्द।

                Output बस script का text हो, कोई markdown heading नहीं।
            """.trimIndent()

            runCatching {
                val reply = GeminiClient(apiKey).reply(listOf(true to prompt))
                val hook = reply.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
                Result(reply.trim(), hook)
            }.getOrDefault(Result("", ""))
        }
}
