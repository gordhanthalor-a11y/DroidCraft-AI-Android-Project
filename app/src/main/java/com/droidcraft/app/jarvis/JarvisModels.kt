package com.droidcraft.app.jarvis

/** Jarvis ke andar istemal hone wale data models. */

enum class JarvisIntentType {
    TIME,
    DATE,
    WEATHER,
    OPEN_APP,
    WEB_SEARCH,
    OPEN_URL,
    CALL,
    SMS,
    ALARM,
    TIMER,
    NOTE_ADD,
    NOTE_LIST,
    MEMORY_ADD,
    MEMORY_LIST,
    FLASHLIGHT,
    BATTERY,
    DEVICE_INFO,
    SCREEN_DUMP,
    SCREENSHOT,
    TAP,
    SWIPE,
    SCROLL,
    NAVIGATE,
    CONTACT_LOOKUP,
    CALENDAR_READ,
    NOTIFICATIONS_READ,
    REMINDER_ADD,
    BATTERY_EXEMPTION,
    TYPE_TEXT,
    MEMORY,
    JOKE,
    GREETING,
    IDENTITY,
    UNKNOWN
}

/** Local NLU parser ka natija — device par kya action karna hai. */
data class JarvisIntent(
    val type: JarvisIntentType,
    val args: Map<String, String> = emptyMap()
)

enum class JarvisMessageKind { TEXT, NOTE, WEATHER, SYSTEM }

data class JarvisMessage(
    val id: Long,
    val text: String,
    val isUser: Boolean,
    val kind: JarvisMessageKind = JarvisMessageKind.TEXT
)

data class PendingTapConfirmation(
    val target: String,
    val reason: String,
    val expectedPackageName: String
)

data class JarvisNote(
    val id: Long,
    val text: String,
    val time: String
)

/** Device action ka result. */
data class JarvisOutcome(
    val message: String,
    val success: Boolean = true,
    /** A user-requested screenshot is passed ephemerally to Gemini for this turn only. */
    val imagePng: ByteArray? = null
)

/** Gemini brain ka structured jawab. */
data class BrainReply(
    val text: String,
    val action: JarvisIntent? = null
)
