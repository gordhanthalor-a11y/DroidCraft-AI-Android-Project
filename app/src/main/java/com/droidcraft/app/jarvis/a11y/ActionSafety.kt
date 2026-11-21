package com.droidcraft.app.jarvis.a11y

import com.droidcraft.app.jarvis.JarvisIntent
import com.droidcraft.app.jarvis.JarvisIntentType
import java.util.Locale

/** Code-enforced policy for accessibility actions. Prompts are never the safety boundary. */
object ActionSafety {
    sealed interface TapDecision {
        data object Allowed : TapDecision
        data class RequiresConfirmation(val reason: String) : TapDecision
        data class Blocked(val reason: String) : TapDecision
    }

    private val prohibitedTapPhrases = listOf(
        "transfer", "like", "subscribe", "follow", "unfollow", "donate",
        "पैसे भेज", "लाइक", "सब्स्क्राइब", "फॉलो"
    )

    /** Send controls in messaging apps: allowed only when the user explicitly asked to send. */
    private val sendTapPhrases = listOf(
        "send", "send message", "send sms", "send text", "send now",
        "भेजें", "भेजो", "भेजना", "भेज"
    )

    /** Purchase/payment controls: allowed only after the user's explicit spoken consent to pay. */
    private val paymentTapPhrases = listOf(
        "purchase", "buy", "buy now", "checkout", "payment", "pay", "pay now",
        "google pay", "gpay", "phonepe", "paytm", "upi", "place order", "order now",
        "खरीद", "भुगतान", "पेमेंट", "अ़र्डर"
    )

    private val messagingPackageMarkers = listOf(
        "whatsapp", "telegram", "messenger", "instagram", "mms", "messaging", "sms"
    )

    private val confirmationPhrases = listOf(
        "delete", "remove", "erase", "uninstall", "reset", "clear data", "delete account",
        "cancel subscription", "submit", "publish", "post", "book", "confirm", "approve",
        "revoke", "disconnect", "discard", "save changes", "place bid", "allow", "grant",
        "accept", "authorize", "consent", "start trial", "enable access", "disable account",
        "yes", "ok", "okay", "continue", "proceed",
        "हटाएं", "मिटाएं", "रीसेट", "प्रकाशित", "पोस्ट", "बुक", "पुष्टि", "अनुमति दें"
    )

    /** Package IDs for known banking, payment, wallet and financial apps. */
    private val financialPackageMarkers = listOf(
        "bank", "banking", "payment", "payments", "wallet", "upi", "paisa", "money", "pay",
        "phonepe", "paytm", "google.android.apps.nbu.paisa", "gpay", "amazonpay",
        "mobikwik", "cred", "paypal", "stripe", "cashapp", "venmo", "revolut",
        "wise", "bhim", "payzapp", "freecharge", "fi.money", "jupiter", "slice",
        "hdfc", "icici", "sbi", "yono", "axis", "kotak", "canara", "punjabnational",
        "pnb", "bankofbaroda", "indusind", "yesbank", "unionbank", "idfc", "federalbank",
        "rbl", "bandhan", "dbs", "hsbc", "standardchartered", "citibank", "maharashtra",
        "centralbank", "southindianbank", "uco", "bankofindia", "tmb", "au-small-finance"
    )

    fun isFinancialOrPaymentApp(packageName: String?, applicationLabel: String? = null): Boolean {
        val pkg = packageName.orEmpty().lowercase(Locale.ROOT)
        if (pkg.isNotBlank() && financialPackageMarkers.any(pkg::contains)) return true
        val label = normalize(applicationLabel.orEmpty())
        val labelMarkers = listOf(
            "bank", "banking", "payment", "payments", "wallet", "upi", "money", "paisa",
            "phonepe", "paytm", "google pay", "gpay", "amazon pay", "mobikwik", "bhim",
            "financial", "finance", "paypal", "cash app", "venmo", "revolut", "wise"
        )
        return labelMarkers.any { containsPhrase(label, normalize(it)) }
    }

    fun assessTap(label: String): TapDecision = assessTap(label, packageName = null, applicationLabel = null)

    /** The active package and app label are independent of the requested target and cannot be overridden. */
    fun assessTap(label: String, packageName: String?, applicationLabel: String? = null, userText: String = ""): TapDecision {
        if (isFinancialOrPaymentApp(packageName, applicationLabel)) {
            return TapDecision.Blocked(
                "For safety, Jarvis will not inject taps in banking or payment apps. Please use the app directly."
            )
        }
        val normalized = normalize(label)
        if (normalized.isBlank()) return TapDecision.Blocked("There is no visible label to tap.")
        val sendPhrase = sendTapPhrases.firstOrNull { containsPhrase(normalized, normalize(it)) }
        if (sendPhrase != null) {
            val pkg = packageName.orEmpty().lowercase(Locale.ROOT)
            val inMessagingApp = messagingPackageMarkers.any { pkg.contains(it) }
            return if (inMessagingApp && explicitlyAskedToSend(userText)) {
                TapDecision.Allowed
            } else {
                TapDecision.RequiresConfirmation(
                    "This control sends a message ($sendPhrase). Confirm on screen only if you intend to send it."
                )
            }
        }
        val paymentPhrase = paymentTapPhrases.firstOrNull { containsPhrase(normalized, normalize(it)) }
        if (paymentPhrase != null) {
            return if (explicitPayConsent(userText)) {
                TapDecision.Allowed
            } else {
                TapDecision.RequiresConfirmation(
                    "This is a purchase or payment control ($paymentPhrase). Jarvis must ask the user first and proceed only after the user explicitly says to pay."
                )
            }
        }
        val blockedPhrase = prohibitedTapPhrases.firstOrNull { containsPhrase(normalized, normalize(it)) }
        if (blockedPhrase != null) {
            return TapDecision.Blocked(
                "For safety, Jarvis will not tap money-transfer, like, subscribe, or follow controls. Please review and tap that control yourself."
            )
        }
        
        val confirmationPhrase = confirmationPhrases.firstOrNull { containsPhrase(normalized, normalize(it)) }
        return if (confirmationPhrase != null) {
            TapDecision.RequiresConfirmation(
                "This control may cause an irreversible or privacy-sensitive change ($confirmationPhrase). Confirm on screen only if you intend to continue."
            )
        } else {
            TapDecision.Allowed
        }
    }

    /**
     * Conservative authorization check for Gemini tool calls. A model cannot create permission
     * from screen text: the requested action must be apparent in the user's latest message.
     */
    fun wasExplicitlyRequested(userText: String, type: JarvisIntentType): Boolean {
        val text = normalize(userText)
        if (text.isBlank()) return false
        val tapAsked = beginsWithRequest(text, "tap", "click", "press", "select", "choose", "dismiss", "close", "daba", "dabao", "दबाओ", "दबा दो", "क्लिक करो", "क्लिक")
        val scrollAsked = beginsWithRequest(text, "scroll", "swipe", "flick", "upar scroll", "neeche scroll", "स्क्रॉल करो", "स्क्रॉल", "ऊपर स्क्रॉल", "नीचे स्क्रॉल")
        val screenReadAsked = beginsWithRequest(text, "read", "show", "describe", "inspect", "tell me", "what is on", "what's on", "whats on", "दिखाओ", "बताओ", "पढ़ो", "दिखा") &&
            containsAny(text, "screen", "page", "display", "स्क्रीन", "पेज", "डिस्प्ले")
        // A spoken task ("WhatsApp kholo aur message bhejo", "YouTube par live stream chalao",
        // "form bharo") authorizes the screen steps it needs: read, tap, scroll, type, navigate.
        // Dangerous controls stay guarded separately by assessTap.
        val taskControlAsked = containsAny(
            text,
            "kholo", "khol do", "खोलो", "खोल", "open", "launch", "bhejo", "bhej do", "भेजो", "भेज",
            "send", "message", "मैसेज", "search", "सर्च", "dhundo", "dhundho", "ढूंढो", "खोजो",
            "lookup", "लुकअप", "chalao", "चलाओ", "play", "live", "stream", "video", "वीडियो",
            "form", "फॉर्म", "bharo", "भरो", "likho", "लिखो", "prompt", "scroll", "स्क्रॉल",
            "tap", "दबाओ", "click", "type", "टाइप", "shopping", "शॉपिंग"
        )

        return when (type) {
            JarvisIntentType.SCREEN_DUMP -> screenReadAsked || tapAsked || scrollAsked || taskControlAsked
            JarvisIntentType.SCREENSHOT -> beginsWithRequest(text, "screenshot", "take screenshot", "take a screenshot", "capture screen", "capture a screenshot", "screen capture")
            JarvisIntentType.TAP -> tapAsked || taskControlAsked ||
                (beginsWithRequest(text, "open", "dismiss", "close") && containsAny(text, "on screen", "visible", "button", "menu"))
            JarvisIntentType.SWIPE, JarvisIntentType.SCROLL -> scrollAsked || taskControlAsked
            JarvisIntentType.NAVIGATE -> beginsWithRequest(text, "go back", "back", "go home", "home screen", "ghar jao", "wapas jao", "वापस जाओ", "वापस", "पीछे जाओ", "घर जाओ", "होम") || taskControlAsked
            JarvisIntentType.OPEN_APP -> beginsWithRequest(text, "open", "launch", "start", "kholo", "khol", "khol do", "on karo", "chalu karo", "chalao", "खोलो", "खोल", "खोल दो", "चालू करो", "शुरू करो", "ऑन करो") ||
                Regex("(?:^|\\s)(?:खोलो|खोल दो|खोल|kholo|khol|चालू करो|अ़न करो)$").containsMatchIn(text)
            JarvisIntentType.TYPE_TEXT -> beginsWithRequest(text, "type", "fill", "likho", "likh do", "bhar do", "लिखो", "लिख दो", "भर दो", "टाइप करो") || taskControlAsked
            JarvisIntentType.NOTIFICATIONS_READ ->
                containsAny(text, "notification", "notifications", "नोटिफिकेशन", "सूचना", "सूचनाएं") && beginsWithRequest(text, "read", "show", "list", "check", "tell me", "dikhao", "dikha", "दिखाओ", "बताओ", "पढ़ो")
            JarvisIntentType.CALENDAR_READ ->
                containsAny(text, "calendar", "event", "schedule", "कैलेंडर") && beginsWithRequest(text, "read", "show", "list", "check", "tell me", "what is on", "what s on", "whats on", "dikhao", "dikha", "दिखाओ", "बताओ")
            JarvisIntentType.CONTACT_LOOKUP ->
                beginsWithRequest(text, "find", "look up", "lookup", "show", "get", "search", "tell me", "who is", "what is", "dhundo", "dhundho", "khojo", "nikalo", "batao", "ढूंढो", "ढूंढ", "खोजो", "निकालो", "बताओ", "दिखाओ", "लुकअप") ||
                    (containsAny(text, "contact", "contacts", "address book", "number", "phone number", "नंबर", "कॉन्टैक्ट") &&
                        beginsWithRequest(text, "read", "check", "list", "पढ़ो", "बताओ", "दिखाओ"))
            JarvisIntentType.CALL -> beginsWithRequest(text, "call", "dial", "phone karo", "dial karo", "open dialer for", "call karo", "कॉल करो", "फोन करो", "डायल करो", "मिलाओ")
            JarvisIntentType.REMINDER_ADD -> beginsWithRequest(text, "remind me", "set a reminder", "set reminder", "create reminder", "add reminder", "reminder", "yaad dilana", "yaad dilaana", "yaad dilao", "reminder lagao", "रिमाइंडर", "रिमाइंडर लगाओ", "रिमाइंडर सेट करो", "याद दिलाओ", "याद दिलाना")
            JarvisIntentType.BATTERY_EXEMPTION ->
                containsAny(text, "battery optimization", "battery optimisation") &&
                    containsAny(text, "prompt", "exemption", "ignore", "disable", "allow", "open", "request")
            else -> false
        }
    }

    /**
     * Intent-aware variant: a contact lookup is also authorized when the exact contact name
     * the tool will look up appears literally in the user's latest message — this covers the
     * natural conversation flow where Jarvis asks "which contact?" and the user replies with
     * just the name. Lookup is read-only, so this stays a tight, literal check.
     */
    fun wasExplicitlyRequested(userText: String, intent: JarvisIntent): Boolean {
        if (wasExplicitlyRequested(userText, intent.type)) return true
        if (intent.type == JarvisIntentType.CONTACT_LOOKUP) {
            val name = intent.args["name"].orEmpty().trim()
            if (name.length >= 3 && normalize(userText).contains(normalize(name))) return true
        }
        return false
    }

    /** Text the user supplied — literally, matra-insensitively, or as the same words reordered — may be typed. */
    fun isUserProvidedText(userText: String, candidate: String): Boolean {
        val typed = candidate.trim()
        if (typed.isBlank()) return false
        if (userText.contains(typed, ignoreCase = true)) return true
        val userNorm = normalize(userText)
        val candNorm = normalize(candidate)
        if (candNorm.isBlank()) return false
        if (userNorm.contains(candNorm)) return true
        val userFlat = stripMatras(userNorm)
        val candFlat = stripMatras(candNorm)
        if (candFlat.isNotBlank() && userFlat.contains(candFlat)) return true
        val userTokens = userFlat.split(" ").filter { it.isNotBlank() }.toSet()
        val candTokens = candFlat.split(" ").filter { it.isNotBlank() }
        return candTokens.isNotEmpty() && candTokens.all { it in userTokens }
    }

    /** The user explicitly asked Jarvis to compose/write the text himself (a prompt, a form answer, a message draft). */
    fun userAskedToCompose(userText: String): Boolean {
        val text = normalize(userText)
        if (text.isBlank()) return false
        return containsAny(
            text,
            "prompt likho", "prompt likh", "prompt banao", "prompt bana", "prompt likh do",
            "likh do", "likho", "लिख दो", "लिखो", "अपने हिसाब से लिख", "खुद लिख", "खुद से लिख",
            "compose", "write a prompt", "form bharo", "form bhar", "फॉर्म भरो", "फॉर्म भर", "भर दो"
        )
    }

    /** The user's own words in this conversation explicitly asked to send a message. */
    fun explicitlyAskedToSend(userText: String): Boolean {
        val text = normalize(userText)
        if (text.isBlank()) return false
        return beginsWithRequest(text, "send", "bhejo", "bhej do", "bhej", "भेजो", "भेज दो", "भेज", "भेजना") ||
            containsAny(
                text,
                "message bhejo", "message bhej", "bhej do", "bhejdo", "भेज दो",
                "मैसेज भेजो", "मैसेज भेज", "संदेश भेजो", "whatsapp par bhejo", "व्हाट्सएप पर भेजो",
                "व्हाट्सऐप पर भेजो", "को भेजो", "को मैसेज करो", "ko message karo", "message karo"
            )
    }

    /** Short, explicit spoken consent to complete a payment, given after Jarvis asked. */
    fun explicitPayConsent(userText: String): Boolean {
        val text = normalize(userText)
        if (text.isBlank()) return false
        val words = text.split(" ").filter { it.isNotBlank() }
        if (words.size > 10) return false
        return containsAny(
            text,
                "payment kar do", "payment kardo", "pay kar do", "pay kardo",
                "पेमेंट कर दो", "पेमेंट करो", "भुगतान कर दो", "भुगतान करो",
                "order kar do", "अ़र्डर कर दो", "haan kar do", "हां कर दो", "हाँ कर दो",
                "haan payment", "yes pay", "kar do payment"
        )
    }

    /** Devanagari vowel signs (matras) removed, so small spelling differences still match. */
    private fun stripMatras(value: String): String =
        value.replace(Regex("[\\u093C\\u093E-\\u094D\\u0900-\\u0903\\u0962-\\u0963\\u200C\\u200D]"), "")

    private fun beginsWithRequest(text: String, vararg verbs: String): Boolean {
        val lead = "(?:(?:please|could you|can you|would you|will you|i want you to|please can you|can you please|would you please|jarvis)\\s+)*"
        return verbs.any { verb ->
            val normalizedVerb = Regex.escape(normalize(verb)).replace("\\ ", "\\s+")
            Regex("^$lead$normalizedVerb(?:\\b|$)", RegexOption.IGNORE_CASE).containsMatchIn(text)
        }
    }

    private fun containsAny(text: String, vararg phrases: String): Boolean =
        phrases.any { containsPhrase(text, normalize(it)) }

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun containsPhrase(text: String, phrase: String): Boolean =
        phrase.isNotBlank() &&
            (text == phrase || text.startsWith("$phrase ") || text.endsWith(" $phrase") || text.contains(" $phrase "))
}
