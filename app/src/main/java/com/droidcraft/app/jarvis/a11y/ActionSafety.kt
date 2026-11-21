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
        "purchase", "buy", "buy now", "checkout", "payment", "pay", "pay now",
        "google pay", "gpay", "phonepe", "paytm", "upi", "transfer", "send",
        "send message", "send sms", "send text", "send now", "like", "subscribe",
        "follow", "unfollow", "place order", "order now", "donate",
        "खरीद", "भुगतान", "पेमेंट", "पैसे भेज", "भेजें", "भेजो", "भेजना",
        "लाइक", "सब्सक्राइब", "फॉलो", "ऑर्डर"
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
    fun assessTap(label: String, packageName: String?, applicationLabel: String? = null): TapDecision {
        if (isFinancialOrPaymentApp(packageName, applicationLabel)) {
            return TapDecision.Blocked(
                "For safety, Jarvis will not inject taps in banking or payment apps. Please use the app directly."
            )
        }
        val normalized = normalize(label)
        if (normalized.isBlank()) return TapDecision.Blocked("There is no visible label to tap.")
        val blockedPhrase = prohibitedTapPhrases.firstOrNull { containsPhrase(normalized, normalize(it)) }
        if (blockedPhrase != null) {
            return TapDecision.Blocked(
                "For safety, Jarvis will not tap purchase, payment, send, like, subscribe, or follow controls. Please review and tap that control yourself."
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

        return when (type) {
            JarvisIntentType.SCREEN_DUMP -> screenReadAsked || tapAsked || scrollAsked
            JarvisIntentType.SCREENSHOT -> beginsWithRequest(text, "screenshot", "take screenshot", "take a screenshot", "capture screen", "capture a screenshot", "screen capture")
            JarvisIntentType.TAP -> tapAsked ||
                (beginsWithRequest(text, "open", "dismiss", "close") && containsAny(text, "on screen", "visible", "button", "menu"))
            JarvisIntentType.SWIPE, JarvisIntentType.SCROLL -> scrollAsked
            JarvisIntentType.NAVIGATE -> beginsWithRequest(text, "go back", "back", "go home", "home screen", "ghar jao", "wapas jao", "वापस जाओ", "वापस", "पीछे जाओ", "घर जाओ", "होम")
            JarvisIntentType.OPEN_APP -> beginsWithRequest(text, "open", "launch", "start", "kholo", "khol", "khol do", "on karo", "chalu karo", "chalao", "खोलो", "खोल", "खोल दो", "चालू करो", "शुरू करो", "ऑन करो")
            JarvisIntentType.TYPE_TEXT -> beginsWithRequest(text, "type", "fill", "likho", "likh do", "bhar do", "लिखो", "लिख दो", "भर दो", "टाइप करो")
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

    /** Only a literal substring of the latest user request can be sent to a focused text field. */
    fun isUserProvidedText(userText: String, candidate: String): Boolean =
        candidate.isNotBlank() && userText.contains(candidate.trim(), ignoreCase = true)

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
