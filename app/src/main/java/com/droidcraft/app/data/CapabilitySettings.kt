package com.droidcraft.app.data

import android.content.Context

/** Independent user switches; Android's runtime permissions/services are still checked separately. */
enum class JarvisCapability(val label: String, val description: String) {
    PHONE_CONTROL("Phone control", "Accessibility, labelled screen actions, app launch, and system navigation"),
    REMINDERS("Reminders", "Create local reminders and alarms"),
    NOTIFICATIONS("Notifications", "Read local notifications after notification-access approval"),
    CALENDAR("Calendar", "Read upcoming events after calendar permission"),
    CONTACTS("Contacts", "Look up contacts and open the system dialer")
}

object CapabilitySettings {
    private const val PREFS = "jarvis_capabilities"

    fun isEnabled(context: Context, capability: JarvisCapability): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(capability.name, true)

    fun setEnabled(context: Context, capability: JarvisCapability, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(capability.name, enabled).apply()
    }
}
