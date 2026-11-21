package com.droidcraft.app.jarvis.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationListenerService
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Receives notifications only after the user enables Notification access in Android Settings. */
class JarvisNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationStore.markConnected(this, true)
    }

    override fun onListenerDisconnected() {
        NotificationStore.markConnected(this, false)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.isGroup) return
        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = sequenceOf(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString(" · ") { it.toString() }
        ).filterNotNull().firstOrNull { it.isNotBlank() }.orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val appLabel = runCatching {
            val info = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(sbn.packageName)
        NotificationStore.record(this, appLabel, title, text, sbn.postTime)
    }

    override fun onDestroy() {
        NotificationStore.markConnected(this, false)
        super.onDestroy()
    }
}

/** Local-only notification snapshot. It is read by Gemini only after an explicit user request. */
object NotificationStore {
    private const val PREFS = "jarvis_notifications"
    private const val KEY_ITEMS = "items_v1"
    private const val MAX_ITEMS = 50
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private val lock = Any()
    @Volatile private var connected = false

    data class Item(val app: String, val title: String, val text: String, val postedAt: Long)

    fun hasAccess(context: Context): Boolean {
        val component = ComponentName(context, JarvisNotificationListenerService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        return enabled.split(':').any { it.equals(component, ignoreCase = true) }
    }

    fun isConnected(): Boolean = connected

    fun markConnected(context: Context, value: Boolean) {
        connected = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("connected", value).apply()
    }

    fun openSettings(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)

    fun record(context: Context, app: String, title: String, text: String, postedAt: Long) {
        synchronized(lock) {
            val items = read(context).filter { postedAt - it.postedAt <= MAX_AGE_MS }
            val item = Item(app.take(100), title.take(240), text.take(1_000), postedAt)
            val updated = (listOf(item) + items.filterNot {
                it.app == item.app && it.title == item.title && it.text == item.text
            }).take(MAX_ITEMS)
            write(context, updated)
        }
    }

    fun latest(context: Context, limit: Int = 20): List<Item> = synchronized(lock) {
        read(context).take(limit.coerceIn(1, MAX_ITEMS))
    }

    fun format(items: List<Item>): String {
        if (items.isEmpty()) return "There are no recent notifications stored on this device."
        val format = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())
        return items.joinToString("\n") { item ->
            val title = item.title.ifBlank { "Notification" }
            val body = item.text.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
            "${item.app}: $title$body (${format.format(Date(item.postedAt))})"
        }
    }

    private fun read(context: Context): List<Item> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val postedAt = item.optLong("at", 0L)
                    if (postedAt > 0L && System.currentTimeMillis() - postedAt <= MAX_AGE_MS) {
                        add(Item(item.optString("app"), item.optString("title"), item.optString("text"), postedAt))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun write(context: Context, items: List<Item>) {
        val array = JSONArray().apply {
            items.take(MAX_ITEMS).forEach { item ->
                put(JSONObject().put("app", item.app).put("title", item.title).put("text", item.text).put("at", item.postedAt))
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ITEMS, array.toString()).apply()
    }
}
