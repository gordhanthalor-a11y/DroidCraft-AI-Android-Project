package com.droidcraft.app.jarvis

import com.droidcraft.app.ai.GeminiClient

/**
 * Jarvis ka dimag — Gemini 2.5 Flash.
 * Baat-cheet plain text mein; jab device action chahiye to JSON mein.
 */
class JarvisBrain(apiKey: String) {

    private val client = GeminiClient(apiKey, JARVIS_PROMPT)

    suspend fun reply(history: List<JarvisMessage>): BrainReply {
        val raw = client.reply(history.map { it.isUser to it.text })
        return parseReply(raw)
    }

    private fun parseReply(raw: String): BrainReply {
        val cleaned = raw.trim()
        if (!cleaned.startsWith("{")) return BrainReply(cleaned)

        val fenced = cleaned.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = fenced.indexOf('{')
        val end = fenced.lastIndexOf('}')
        if (start < 0 || end <= start) return BrainReply(cleaned)

        return runCatching {
            val json = org.json.JSONObject(fenced.substring(start, end + 1))
            val reply = json.optString("reply").ifBlank { "" }
            val actionName = json.optString("action").ifBlank { "" }
            val params = json.optJSONObject("params")
            val action = if (actionName.isBlank()) null else toIntent(actionName, params)
            BrainReply(reply.ifBlank { cleaned }, action)
        }.getOrDefault(BrainReply(cleaned))
    }

    private fun toIntent(actionName: String, params: org.json.JSONObject?): JarvisIntent? {
        val type = JarvisIntentType.entries.firstOrNull { it.name.equals(actionName.trim(), ignoreCase = true) }
            ?: return null
        if (type == JarvisIntentType.UNKNOWN) return null
        val args = mutableMapOf<String, String>()
        params?.keys()?.forEach { key ->
            args[key] = params.optString(key, "")
        }
        return JarvisIntent(type, args)
    }

    companion object {
        private val JARVIS_PROMPT = """
            Tum J.A.R.V.I.S. ho (Just A Rather Very Intelligent System) — ek personal AI assistant jo Hindi, Hinglish aur English mein baat karta hai. Tum ek YouTube creator "GT Marwadi Vlogs" (farming/agriculture niche) ke saath kaam karte ho.

            Niyam:
            1. Jawab hamesha user ki bhasha mein do — Hindi/Hinglish mein Hinglish, English mein English. Chhota aur seedha (2-4 line), bina natak.
            2. Device action ki zaroorat ho to SIRF JSON output karo (markdown nahi):
               {"reply": "chhota spoken jawab", "action": "OPEN_APP", "params": {"query": "whatsapp"}}
            3. Action names: TIME, DATE, WEATHER, OPEN_APP, WEB_SEARCH, OPEN_URL, CALL, SMS, ALARM, TIMER, NOTE_ADD, NOTE_LIST, FLASHLIGHT, BATTERY, DEVICE_INFO, JOKE, GREETING, IDENTITY, CREATOR_HELP.
            4. Params: WEATHER {"city":"Jaipur"}, ALARM {"hour":7,"minute":0}, TIMER {"seconds":300}, CALL {"number":"9876543210"}, SMS {"number":"9876543210","body":"namaste"}, NOTE_ADD {"text":"..."}, OPEN_APP {"query":"youtube"}, WEB_SEARCH {"query":"..."}, OPEN_URL {"url":"..."}.
            5. Normal baat-cheet, video ideas, scripts, titles, SEO, thumbnail ideas ke liye plain text likho — JSON mat likhna.
            6. Kabhi bhi apni asli AI prakriti reveal mat karo; tum hamesha J.A.R.V.I.S. hi rahoge.
            7. Creator tasks mein practical, step-by-step aur Hinglish madad do.
        """.trimIndent()
    }
}
