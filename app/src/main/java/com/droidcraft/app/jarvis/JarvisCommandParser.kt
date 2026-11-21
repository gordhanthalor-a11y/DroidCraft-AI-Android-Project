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
        if (beginsWithCommand(text, "set timer", "set a timer", "start timer", "start a timer", "timer", "countdown", "taimer")) {
            val seconds = parseDuration(text)
            return JarvisIntent(JarvisIntentType.TIMER, mapOf("seconds" to (seconds ?: 0).toString()))
        }

        // ---------- Memory (user ke baare mein facts) ----------
        if (hasWord(text, "what do you remember", "yaad kya hai", "meri memory", "memory dikhao",
                "what do you know about me", "kya yaad hai", "memory batao")) {
            return JarvisIntent(JarvisIntentType.MEMORY_LIST, mapOf("query" to extractMemoryQuery(text)))
        }
        val memMatch = Regex("\\b(?:remember(?:\\s+that)?|yaad\\s+(?:rakho|rakh|dharak|dharo))\\s*:?\\s*(.+)$").find(text)
        if (memMatch != null) {
            val fact = memMatch.groupValues[1].trim().trimEnd('.', '?', '!', ',')
            if (fact.isNotBlank()) {
                return JarvisIntent(JarvisIntentType.MEMORY_ADD, mapOf("text" to fact))
            }
        }

        // ---------- Notes (Alarm se pehle — "note karo 10 baje" note honi chahiye) ----------
        if (beginsWithCommand(text, "add note", "save note", "make a note", "note karo", "note kar", "note likho", "notes dikhao", "show notes", "list notes")) {
            if (beginsWithCommand(text, "notes dikhao", "show notes", "list notes")) {
                return JarvisIntent(JarvisIntentType.NOTE_LIST)
            }
            val noteText = extractNoteText(raw.trim())
            if (noteText.isNotBlank()) return JarvisIntent(JarvisIntentType.NOTE_ADD, mapOf("text" to noteText))
            return JarvisIntent(JarvisIntentType.NOTE_LIST)
        }

        // ---------- Alarm ----------
        if (beginsWithCommand(text, "set alarm", "alarm", "wake me", "wake me up", "jaga", "jagao", "utha", "uthao")) {
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

        // ---------- Screen automation (accessibility) ----------
        if (beginsWithCommand(text, "take a screenshot", "take screenshot", "screenshot", "capture screen", "capture a screenshot", "screen capture")) {
            return JarvisIntent(JarvisIntentType.SCREENSHOT)
        }
        if (isScreenReadRequest(text)) {
            return JarvisIntent(JarvisIntentType.SCREEN_DUMP, mapOf("query" to extractScreenQuery(text)))
        }
        val swipeMatch = Regex("^(?:swipe|flick)\\s+(up|down|left|right)\\b").find(stripRequestLead(text))
        if (swipeMatch != null) return JarvisIntent(JarvisIntentType.SWIPE, mapOf("direction" to swipeMatch.groupValues[1]))
        if (beginsWithCommand(text, "show notifications", "read notifications", "list notifications", "recent notifications", "notification list", "notifications dikhao")) {
            return JarvisIntent(JarvisIntentType.NOTIFICATIONS_READ)
        }
        if (beginsWithCommand(text, "read calendar", "show calendar", "show events", "upcoming events", "list events", "check calendar", "check my schedule", "aaj ka schedule")) {
            return JarvisIntent(JarvisIntentType.CALENDAR_READ, mapOf("days" to "7"))
        }
        val reminderMatch = Regex("^(?:remind me|set (?:a )?reminder|create (?:a )?reminder|add (?:a )?reminder|reminder|remind|yaad dilana|yaad dilaana)\\s+(?:in|after)\\s+(\\d+)\\s*(seconds?|secs?|minutes?|mins?|hours?|days?)\\s+(?:to|that|about)?\\s*(.*)$").find(stripRequestLead(text))
        if (reminderMatch != null) {
            val amount = reminderMatch.groupValues[1].toLongOrNull() ?: 0L
            val multiplier = when (reminderMatch.groupValues[2].take(1)) { "s" -> 1L; "m" -> 60L; "h" -> 3_600L; else -> 86_400L }
            val title = reminderMatch.groupValues[3].trim().trimStart(':', ' ')
            if (amount > 0 && title.isNotBlank()) {
                return JarvisIntent(JarvisIntentType.REMINDER_ADD, mapOf("title" to title, "after_seconds" to (amount * multiplier).toString()))
            }
        }
        val navigation = when {
            beginsWithCommand(text, "back", "go back", "press back", "wapas", "wapas jao", "peeche jao", "pichhe jao", "pichhe chalo") -> "BACK"
            beginsWithCommand(text, "home", "go home", "home screen", "ghar", "ghar jao", "home par jao") -> "HOME"
            else -> null
        }
        if (navigation != null) return JarvisIntent(JarvisIntentType.NAVIGATE, mapOf("action" to navigation))

        if (beginsWithCommand(text, "scroll up", "upar scroll", "upar karo", "page up", "ऊपर स्क्रॉल")) {
            return JarvisIntent(JarvisIntentType.SCROLL, mapOf("direction" to "up"))
        }
        if (beginsWithCommand(text, "scroll down", "neeche scroll", "niche scroll", "neeche karo", "niche karo", "page down", "नीचे स्क्रॉल")) {
            return JarvisIntent(JarvisIntentType.SCROLL, mapOf("direction" to "down"))
        }

        val strippedRaw = stripRequestLead(raw.trim())
        val typeMatch = Regex("^(?:type|likho|likh do|likh|bhar do|fill in|fill)\\s*:?\\s*(.+)$", RegexOption.IGNORE_CASE).find(strippedRaw)
            ?: Regex("^(?:enter|input)\\s+(?:text|code|number|email)\\s*:?\\s*(.+)$", RegexOption.IGNORE_CASE).find(strippedRaw)
        if (typeMatch != null) {
            val typed = typeMatch.groupValues[1].trim()
            if (typed.isNotBlank()) return JarvisIntent(JarvisIntentType.TYPE_TEXT, mapOf("text" to typed))
        }

        val stripped = stripRequestLead(text)
        val tapMatch = Regex("^(?:tap\\s+(?:on\\s+)?|click\\s+(?:on\\s+)?|touch\\s+(?:on\\s+)?|daba\\s*:?\\s*|dabao\\s+|daba\\s+do)\\s*(.+)$").find(stripped)
        if (tapMatch != null) {
            val target = tapMatch.groupValues[1].trim().trimEnd('.', '?', '!')
            if (target.isNotBlank()) return JarvisIntent(JarvisIntentType.TAP, mapOf("target" to target))
        }
        val tapHindi = Regex("^(.+?)\\s+par\\s+(?:click|tap|dabao|daba)\\w*\\s*(?:karo|kar|do)?$").find(stripped)
        if (tapHindi != null) {
            val target = tapHindi.groupValues[1].trim()
            if (target.isNotBlank()) return JarvisIntent(JarvisIntentType.TAP, mapOf("target" to target))
        }

        // ---------- Weather ----------
        if (hasWord(text, "weather", "mausam", "temperature", "garmi", "baarish", "barish", "thand", "thanda")) {
            return JarvisIntent(JarvisIntentType.WEATHER, mapOf("city" to extractCity(text)))
        }

        // ---------- Contacts / dialer. SMS is recognized only to report that it is deferred. ----------
        if (containsAny(text, "contact", "contacts", "phone number", "number") &&
            beginsWithCommand(text, "find", "lookup", "look up", "show", "get", "search", "who is", "what is", "tell me")
        ) {
            val query = extractContactQuery(raw)
            if (query.isNotBlank()) return JarvisIntent(JarvisIntentType.CONTACT_LOOKUP, mapOf("name" to query))
        }
        if (beginsWithCommand(text, "send sms", "send a message", "send message", "text", "sms", "message")) {
            val number = Regex("(\\+?\\d{6,15})").find(text)?.groupValues?.get(1).orEmpty()
            val name = if (number.isBlank()) extractTarget(text) else ""
            val body = text.substringAfter(":", "").trim()
            return JarvisIntent(JarvisIntentType.SMS, mapOf("number" to number, "name" to name, "body" to body))
        }
        val dialCommand = stripRequestLead(text)
        val dialMatch = Regex("^(?:call|dial|phone(?:\\s+karo)?|open dialer for|dialer for)\\s+(.+)$").find(dialCommand)
        if (dialMatch != null) {
            val number = Regex("(\\+?\\d{6,15})").find(dialMatch.groupValues[1])?.groupValues?.get(1).orEmpty()
            val name = if (number.isBlank()) dialMatch.groupValues[1].trim() else ""
            return JarvisIntent(JarvisIntentType.CALL, mapOf("number" to number, "name" to name))
        }

        // ---------- Explicit URL launch ----------
        val strippedForLaunch = stripRequestLead(text)
        val urlMatch = Regex("^(?:open|visit|go to|navigate to)\\s+((?:https?://)?(?:[\\w-]+\\.)+(?:com|in|net|org|co|io|app|dev|gov|edu|info|xyz|live|me|tv|cc)(?:/\\S*)?)$").find(strippedForLaunch)
        if (urlMatch != null) {
            return JarvisIntent(JarvisIntentType.OPEN_URL, mapOf("url" to urlMatch.groupValues[1]))
        }

        // ---------- Open app ----------
        val openMatch = Regex("^(?:open|launch|start|kholo|khol|kholu|khulai|khol do|kholi|chalu kar|chala do|chala|karke kholo)\\s+(.+)$").find(strippedForLaunch)
        if (openMatch != null) {
            val query = cleanAppQuery(openMatch.groupValues[1])
            if (query.isNotBlank()) return JarvisIntent(JarvisIntentType.OPEN_APP, mapOf("query" to query))
        }

        // ---------- Explicit web search ----------
        val searchMatch = Regex("^(?:search|google|dhundo|dhoondo|dhoond|dikhao|dikha do)\\s+(.+)$").find(strippedForLaunch)
        if (searchMatch != null) {
            return JarvisIntent(JarvisIntentType.WEB_SEARCH, mapOf("query" to searchMatch.groupValues[1].trim()))
        }
        val siteSearch = Regex("^(?:google|bing|duckduckgo)\\s+(?:mein|me|par|pe|on)?\\s*(.+)$").find(strippedForLaunch)
        if (siteSearch != null) {
            return JarvisIntent(JarvisIntentType.WEB_SEARCH, mapOf("query" to siteSearch.groupValues[1].trim()))
        }

        // ---------- Joke ----------
        if (hasWord(text, "joke", "chutkula", "hasa", "hasao", "comedy", "funny", "majedaar")) {
            return JarvisIntent(JarvisIntentType.JOKE)
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

    /** The flashlight is toggled only for an explicit on/off command. */
    fun isFlashlight(raw: String): Boolean {
        val text = normalize(raw)
        if (!hasWord(text, "flashlight", "torch", "roshni", "light", "prakash", "jotri")) return false
        return beginsWithCommand(
            text,
            "turn on", "switch on", "turn off", "switch off",
            "flashlight on", "flashlight off", "torch on", "torch off", "light on", "light off",
            "turn flashlight on", "turn flashlight off", "turn torch on", "turn torch off",
            "roshni on", "roshni off", "prakash on", "prakash off"
        )
    }

    // ---------------------------------------------------------------------

    fun normalize(raw: String): String =
        raw.trim().lowercase().replace(Regex("[?!,;\"']+"), "").replace(Regex("\\s+"), " ")

    private fun stripRequestLead(text: String): String = Regex(
        "^(?:(?:please|could you|can you|would you|will you|i want you to|hey jarvis|jarvis)[,;:]?\\s+)+",
        RegexOption.IGNORE_CASE
    ).replaceFirst(text.trim(), "")

    private fun beginsWithCommand(text: String, vararg commands: String): Boolean {
        val stripped = stripRequestLead(text)
        return commands.any { command ->
            val phrase = normalize(command)
            stripped == phrase || stripped.startsWith("$phrase ") || stripped.startsWith("$phrase:")
        }
    }

    private fun isScreenReadRequest(text: String): Boolean {
        if (beginsWithCommand(
                text,
                "show screen", "read screen", "describe screen", "inspect screen", "screen dikhao",
                "screen dikha", "screen batao", "screen kya hai", "what is on the screen",
                "what is on my screen", "what is on screen", "whats on screen", "screen par kya hai",
                "screen dump", "screen padho"
            )
        ) return true
        val stripped = stripRequestLead(text)
        return Regex("^(?:what is|what|whats|tell me|describe|show|read|inspect)\\b.*\\b(?:screen|page|display)\\b")
            .containsMatchIn(stripped)
    }

    private fun isGreeting(text: String): Boolean =
        GREETINGS.any { Regex("^${Regex.escape(it)}\\b").containsMatchIn(text) }

    private fun hasWord(text: String, vararg words: String): Boolean =
        words.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(text) }

    private fun matchesAny(text: String, vararg words: String): Boolean =
        words.any { text.contains(it) }

    private fun containsAny(text: String, vararg words: String): Boolean = hasWord(text, *words)

    private fun parseDuration(text: String): Int? {
        val match = Regex("(\\d{1,5})\\s*(seconds?|secs?|second\\s*ka|minutes?|mins?|minaat|minute\\s*ka|hours?|hrs?|ghante?|ghanta|days?|din)\\b").find(text)
            ?: return null
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        val unit = match.groupValues[2]
        val multiplier = when {
            unit.startsWith("s") -> 1L
            unit.startsWith("m") -> 60L
            unit.startsWith("g") || unit.startsWith("h") -> 3_600L
            unit.startsWith("d") -> 86_400L
            unit == "din" -> 86_400L
            else -> return null
        }
        return (amount * multiplier).takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
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

    private fun extractNoteText(raw: String): String {
        val text = stripRequestLead(raw)
        val m = Regex("^(?:add\\s+note|save\\s+note|make\\s+a\\s+note|note\\s+(?:karo|kar|likho|likh|banao|ban|rakho|rakh|dharak|dharo))\\s*:?\\s*(.+)$", RegexOption.IGNORE_CASE)
            .find(text) ?: return ""
        return m.groupValues[1].trim().removeSurrounding("\"").removeSurrounding("'").trimEnd('.', '?', '!', ',')
    }

    private fun extractContactQuery(raw: String): String {
        var query = stripRequestLead(raw).trim()
        query = query.replace(
            Regex("^(?:find|lookup|look up|show|get|search|tell me|who is|what is)\\s+", RegexOption.IGNORE_CASE),
            ""
        )
        query = query.replace(
            Regex("^(?:(?:me|my)\\s+)?(?:(?:the|my)\\s+)?contacts?(?:\\s+(?:details?|info|information|name|number))?\\s*(?:(?:for|of|named|called)\\s+)?", RegexOption.IGNORE_CASE),
            ""
        )
        query = query.replace(
            Regex("^(?:the\\s+)?(?:phone\\s+)?number\\s+(?:for|of)\\s+", RegexOption.IGNORE_CASE),
            ""
        )
        query = query.replace(Regex("\\s+(?:in|from)\\s+(?:my\\s+)?contacts?$", RegexOption.IGNORE_CASE), "")
        query = query.replace(Regex("\\s+(?:phone\\s+)?number$", RegexOption.IGNORE_CASE), "")
        query = query.replace(Regex("\\s+contacts?(?:\\s+(?:details?|info|information))?$", RegexOption.IGNORE_CASE), "")
        query = query.replace(Regex("['’]s$", RegexOption.IGNORE_CASE), "")
        return query.trim().trim(':', '?', '.').removeSurrounding("\"").removeSurrounding("'").take(80)
    }

    /** "call papa" / "sms mom: hi" se sirf naam nikalta hai. */
    private fun extractTarget(text: String): String =
        Regex("\\b(?:call|dial|phone(?:\\s+karo)?|sms|message|msg)\\b\\s*(?:karo|kar|do)?\\s*:?\\s*(.*)$")
            .find(text)?.groupValues?.get(1)?.trim()
            ?.replace(Regex("\\b(?:ko|to|on|par)\\b"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()

    private fun extractMemoryQuery(text: String): String =
        Regex("\\b(?:about|baare\\s+mein)\\s+(.+)$").find(text)?.groupValues?.get(1)?.trim().orEmpty()

    private fun extractScreenQuery(text: String): String =
        Regex("\\b(?:mein|me|par|about|ke\\s+baare\\s+mein)\\s+(.+)$").find(text)?.groupValues?.get(1)?.trim().orEmpty()

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
