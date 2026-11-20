package com.droidcraft.app.jarvis

/**
 * Offline Hinglish/English command parser (fast path).
 * Device actions yahan se turant, bina network ke handle hote hain;
 * baaki sab baat-cheet Gemini brain ko jati hai.
 */
object JarvisCommandParser {

    private val HINDI = "\\u0900-\\u097F"

    private val GREETINGS = listOf(
        "hello", "hi", "hey", "namaste", "namaskar", "salaam", "salam",
        "ram ram", "radhe radhe", "good morning", "good afternoon", "good evening",
        "good night", "pranam", "aadab", "hii", "helo"
    )

    private val GREETINGS = listOf(
        "hello", "hi", "hey", "namaste", "namaskar", "salaam", "salam",
        "ram ram", "radhe radhe", "good morning", "good afternoon", "good evening",
        "good night", "pranam", "aadab", "hii", "helo"
    )

    fun parse(raw: String): JarvisIntent? {
        val text = normalize(raw)
        if (text.isBlank()) return null

        // ---------- Identity / greeting ----------
        if (hasWord(text, "who are you", "kaun ho", "tum kaun", "tera naam", "your name",
                "introduce yourself", "apna parichay", "tumhara naam", "aap kaun")) {
            return JarvisIntent(JarvisIntentType.IDENTITY)
        }
        if (isGreeting(text)) {
            return JarvisIntent(JarvisIntentType.GREETING)
        }

        // ---------- Memory (user ka naam) ----------
        val nameMatch = Regex("\\b(?:mera naam|my name is)\\s+([$HINDI A-Za-z]{2,24})").find(text)
        if (nameMatch != null) {
            val name = nameMatch.groupValues[1]
                .replace(Regex("\\s+(hai|hain|he|h)\\b"), "")
                .trim()
            if (name.isNotEmpty()) {
                return JarvisIntent(JarvisIntentType.MEMORY, mapOf("name" to name))
            }
        }

        // ---------- Timer (TIME se pehle) ----------
        if (hasWord(text, "timer", "countdown", "taimer")) {
            val seconds = parseDuration(text)
            return if (seconds != null) {
                JarvisIntent(JarvisIntentType.TIMER, mapOf("seconds" to seconds.toString()))
            } else {
                JarvisIntent(JarvisIntentType.TIMER, mapOf("seconds" to "60"))
            }
        }

        // ---------- Notes (Alarm se pehle — "note karo 10 baje" note honi chahiye) ----------
        if (hasWord(text, "note", "notes") || Regex("\\byaad\\s+(?:rakho|rakh|dharak|dharo)\\b").containsMatchIn(text)) {
            if (matchesAny(text, "dikha", "dikhao", "list", "kaunsi", "sab", "all")) {
                return JarvisIntent(JarvisIntentType.NOTE_LIST)
            }
            val noteText = extractNoteText(text)
            if (noteText.isNotBlank()) {
                return JarvisIntent(JarvisIntentType.NOTE_ADD, mapOf("text" to noteText))
            }
            return JarvisIntent(JarvisIntentType.NOTE_LIST)
        }

        // ---------- Alarm ----------
        if (hasWord(text, "alarm", "alaarm", "alarm clock") || wakeWord(text)) {
            val alarm = parseAlarm(text)
            if (alarm != null) return JarvisIntent(JarvisIntentType.ALARM, alarm)
        }

        // ---------- Flashlight ----------
        if (isFlashlight(text)) {
            val on = !matchesAny(text, "off", "band", "band kar", "bujh", "bujha")
            return JarvisIntent(JarvisIntentType.FLASHLIGHT, mapOf("on" to on.toString()))
        }

        // ---------- Battery ----------
        if (hasWord(text, "battery", "charge", "charging", "charges")) {
            return JarvisIntent(JarvisIntentType.BATTERY)
        }

        // ---------- Device info ----------
        if ((hasWord(text, "device", "phone", "mobile", "handset") && hasWord(text, "info", "information", "details", "kya hai", "about", "spec"))
            || matchesAny(text, "about phone", "device info", "phone info", "mobile info")) {
            return JarvisIntent(JarvisIntentType.DEVICE_INFO)
        }

        // ---------- Weather ----------
        if (hasWord(text, "weather", "mausam", "temperature", "garmi", "baarish", "barish", "thand", "thanda")) {
            return JarvisIntent(JarvisIntentType.WEATHER, mapOf("city" to extractCity(text)))
        }

        // ---------- Call / SMS ----------
        if (hasWord(text, "sms", "message", "msg") && !hasWord(text, "open")) {
            val number = Regex("(\\+?\\d{10,13})").find(text)?.groupValues?.get(1).orEmpty()
            val body = text.substringAfter(":", "").trim()
                .ifBlank { text.substringAfter("ki", "").trim() }
                .ifBlank { text.substringAfter("message", "").trim() }
            return JarvisIntent(JarvisIntentType.SMS, mapOf("number" to number, "body" to body))
        }
        if (hasWord(text, "call", "dial", "phone karo", "call karo") || Regex("\\bphone\\b").containsMatchIn(text) && hasWord(text, "karo", "kar")) {
            val number = Regex("(\\+?\\d{10,13})").find(text)?.groupValues?.get(1).orEmpty()
            if (number.isNotBlank() || hasWord(text, "call", "dial")) {
                return JarvisIntent(JarvisIntentType.CALL, mapOf("number" to number))
            }
        }

        // ---------- Open URL ----------
        val urlMatch = Regex("((?:https?://)?(?:[\\w-]+\\.)+(?:com|in|net|org|co|io|app|dev|gov|edu|info|xyz|live|me|tv|cc)(?:/\\S*)?)").find(text)
        if (urlMatch != null) {
            return JarvisIntent(JarvisIntentType.OPEN_URL, mapOf("url" to urlMatch.groupValues[1]))
        }

        // ---------- Open app ----------
        val openMatch = Regex("^(?:open|launch|start|kholo|khol|kholu|khulai|khol do|kholi|chalu kar|chala do|chala|karke kholo)\\s+(.+)$").find(text)
        if (openMatch != null) {
            val query = cleanAppQuery(openMatch.groupValues[1])
            if (query.isNotBlank()) return JarvisIntent(JarvisIntentType.OPEN_APP, mapOf("query" to query))
        }

        // ---------- Web search ----------
        val searchMatch = Regex("^(?:search|google|dhundo|dhoondo|dhoond|dikhao|dikha do|dhundo)\\s+(.+)$").find(text)
        if (searchMatch != null) {
            return JarvisIntent(JarvisIntentType.WEB_SEARCH, mapOf("query" to searchMatch.groupValues[1].trim()))
        }
        val siteSearch = Regex("^(?:youtube|google|bing|duckduckgo)\\s+(?:mein|me|par|pe|on)?\\s*(.+)$").find(text)
        if (siteSearch != null) {
            return JarvisIntent(JarvisIntentType.WEB_SEARCH, mapOf("query" to siteSearch.groupValues[1].trim()))
        }

        // ---------- Joke ----------
        if (hasWord(text, "joke", "chutkula", "hasa", "hasao", "comedy", "funny", "majedaar")) {
            return JarvisIntent(JarvisIntentType.JOKE)
        }

        // ---------- Creator help (Gemini ko bhejna hai) ----------
        if (hasWord(text, "video idea", "ideas", "script", "vlog", "youtube", "channel", "thumbnail",
                "seo", "tags", "description", "title", "content", "shorts", "idea")) {
            return JarvisIntent(JarvisIntentType.CREATOR_HELP)
        }

        // ---------- Time / date ----------
        if (hasWord(text, "time", "waqt", "samay", "baje", "kitne baje")) {
            return JarvisIntent(JarvisIntentType.TIME)
        }
        if (hasWord(text, "date", "taarikh", "tareekh", "aaj ka din", "aaj kya din")) {
            return JarvisIntent(JarvisIntentType.DATE)
        }

        return JarvisIntent(JarvisIntentType.UNKNOWN)
    }

    /** Flashlight command kuch bhi ho, screen ko permission dene mein madad ke liye. */
    fun isFlashlight(raw: String): Boolean {
        val text = normalize(raw)
        return hasWord(text, "flashlight", "torch", "roshni", "light", "prakash", "jotri")
    }

    // ---------------------------------------------------------------------

    fun normalize(raw: String): String =
        raw.trim().lowercase().replace(Regex("[?!.,;\"']+"), "").replace(Regex("\\s+"), " ")

    private fun hasWord(text: String, vararg words: String): Boolean =
        words.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(text) }

    private fun matchesAny(text: String, vararg words: String): Boolean =
        words.any { text.contains(it) }

    private fun wakeWord(text: String): Boolean =
        Regex("\\b(?:jaga|jagao|utha|uthao|uthaya|wake\\s*me)\\b").containsMatchIn(text)

    private fun parseDuration(text: String): Int? {
        val min = Regex("(\\d{1,4})\\s*(?:minute|min|minutes|minaat|minute\\s*ka)").find(text)
        if (min != null) return (min.groupValues[1].toIntOrNull() ?: return null) * 60
        val sec = Regex("(\\d{1,4})\\s*(?:second|sec|seconds|second\\s*ka)").find(text)
        if (sec != null) return sec.groupValues[1].toIntOrNull()
        return null
    }

    private fun parseAlarm(text: String): Map<String, String>? {
        val m = Regex("(\\d{1,2})(?:\\s*:\\s*(\\d{2}))?(?:\\s+(\\d{2}))?\\s*(am|pm|baje|baaje|o.?clock)?").find(text)
            ?: return null
        val rawHour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = (m.groupValues[2].ifBlank { m.groupValues[3] }).toIntOrNull() ?: 0
        val marker = m.groupValues[4]
        val isPm = marker == "pm" || hasWord(text, "raat", "night", "shaam", "evening", "raat ko")
        val isAm = marker == "am" || hasWord(text, "subah", "morning", "subah ko")
        var hour = rawHour
        if (isPm && hour < 12) hour += 12
        if (isAm && hour == 12) hour = 0
        if (hour > 23 || minute > 59) return null
        return mapOf("hour" to hour.toString(), "minute" to minute.toString())
    }

    private fun extractNoteText(text: String): String {
        val m = Regex("\\b(?:add\\s+note|note\\s+(?:karo|kar|likho|likh|banao|ban|rakho|rakh|dharak|dharo)|remember|yaad\\s+(?:rakho|rakh|dharak|dharo))\\s*:?\\s*(.+)$")
            .find(text) ?: return ""
        return m.groupValues[1].trim().trimEnd('.', '?', '!', ',')
    }

    private fun extractCity(text: String): String {
        val after = Regex("\\b(?:weather|mausam|temperature|baarish|barish)\\b\\s*(.*)$")
            .find(text)?.groupValues?.get(1).orEmpty()
        val city = after
            .replace(Regex("^(?:in|mein|me|of|for|ka|ki|ke)(?=\\s|$)"), "")
            .replace(Regex("\\b(?:kaisa|kaisi|kya|hai|hain|batao|bata|bataiye|karo|kar|dikhao|dikha|ka|ki|ke|mein|me|in|of|for)\\b"), " ")
            .replace(Regex("[?.!,]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(24)
        return city.ifBlank { "Jaipur" }
    }

    private fun cleanAppQuery(raw: String): String =
        raw.trim()
            .replace(Regex("\\b(?:app|application|kholo|khol|karke|kar)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
