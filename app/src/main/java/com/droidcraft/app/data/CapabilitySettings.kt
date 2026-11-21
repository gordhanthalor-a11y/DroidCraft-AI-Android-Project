package com.droidcraft.app.data

import android.content.Context

/** Independent user switches; Android's runtime permissions/services are still checked separately. */
enum class JarvisCapability(val label: String, val description: String) {
    PHONE_CONTROL("Phone control", "Accessibility, labelled screen actions, app launch, and system navigation"),
    REMINDERS("Reminders", "Create local reminders and alarms"),
    NOTIFICATIONS("Notifications", "Read local notifications after notification-access approval"),
    CALENDAR("Calendar", "Read upcoming events after calendar permission"),
    CONTACTS("Contacts", "Look up contacts and open the system dialer"),
    OFFLINE_WAKE("Offline wake phrase", "On-device Hindi and Indian English wake detection; after wake, Android's speech recognizer captures the command")
}

object CapabilitySettings {
    private const val PREFS = "jarvis_capabilities"

    fun isEnabled(context: Context, capability: JarvisCapability): Boolean {
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (capability == JarvisCapability.OFFLINE_WAKE && !preferences.contains(capability.name)) {
            val legacyEnabled = appContext.getSharedPreferences("droidcraft", Context.MODE_PRIVATE)
                .getBoolean("jarvis_hands_free", false)
            preferences.edit().putBoolean(capability.name, legacyEnabled).apply()
            return legacyEnabled
        }
        return preferences.getBoolean(capability.name, capability != JarvisCapability.OFFLINE_WAKE)
    }

    fun setEnabled(context: Context, capability: JarvisCapability, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(capability.name, enabled).apply()
    }
}
