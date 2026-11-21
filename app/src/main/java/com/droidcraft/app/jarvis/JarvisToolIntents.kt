package com.droidcraft.app.jarvis

import org.json.JSONObject

/** Shared mapping from declared model functions to local Jarvis intents (no side effects). */
object JarvisToolIntents {
    fun fromTool(name: String, args: JSONObject): JarvisIntent? {
        fun text(key: String): String = args.optString(key).takeUnless { it == "null" }.orEmpty().trim()
        fun integer(key: String, fallback: Int): String =
            args.opt(key)?.toString()?.toIntOrNull()?.toString() ?: fallback.toString()

        return when (name) {
            "get_screen_elements" -> JarvisIntent(JarvisIntentType.SCREEN_DUMP)
            "take_read_only_screenshot" -> JarvisIntent(JarvisIntentType.SCREENSHOT)
            "tap_by_label" -> text("label").takeIf { it.isNotBlank() }
                ?.let { JarvisIntent(JarvisIntentType.TAP, mapOf("target" to it)) }
            "swipe_scrollable_area" -> text("direction").takeIf { it.isNotBlank() }
                ?.let { JarvisIntent(JarvisIntentType.SWIPE, mapOf("direction" to it)) }
            "scroll_screen" -> text("direction").takeIf { it.isNotBlank() }
                ?.let { JarvisIntent(JarvisIntentType.SCROLL, mapOf("direction" to it)) }
            "system_navigation" -> text("action").takeIf { it.isNotBlank() }
                ?.let { JarvisIntent(JarvisIntentType.NAVIGATE, mapOf("action" to it)) }
            "launch_app_by_name" -> text("app_name").takeIf { it.isNotBlank() }
                ?.let { JarvisIntent(JarvisIntentType.OPEN_APP, mapOf("query" to it)) }
            "type_in_focused_field" -> text("text").takeIf { it.isNotBlank() }
                ?.let { JarvisIntent(JarvisIntentType.TYPE_TEXT, mapOf("text" to it)) }
            "read_notifications" -> JarvisIntent(JarvisIntentType.NOTIFICATIONS_READ, mapOf("limit" to integer("limit", 20)))
            "read_calendar" -> JarvisIntent(JarvisIntentType.CALENDAR_READ, mapOf("days" to integer("days_ahead", 7)))
            "lookup_contact" -> text("name").takeIf { it.isNotBlank() }
                ?.let { JarvisIntent(JarvisIntentType.CONTACT_LOOKUP, mapOf("name" to it)) }
            "open_dialer_for_contact" -> {
                val number = text("phone_number")
                val contact = text("name")
                if (number.isBlank() && contact.isBlank()) null
                else JarvisIntent(JarvisIntentType.CALL, mapOf("number" to number, "name" to contact))
            }
            "create_local_reminder" -> {
                val title = text("title")
                if (title.isBlank()) null else JarvisIntent(
                    JarvisIntentType.REMINDER_ADD,
                    mapOf(
                        "title" to title,
                        "after_seconds" to text("after_seconds"),
                        "when_iso" to text("when_iso")
                    ).filterValues { it.isNotBlank() }
                )
            }
            "request_battery_optimization_prompt" -> JarvisIntent(JarvisIntentType.BATTERY_EXEMPTION)
            else -> null
        }
    }
}
