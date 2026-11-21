package com.droidcraft.app.jarvis.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.droidcraft.app.MainActivity
import com.droidcraft.app.R
import com.droidcraft.app.jarvis.permissions.RuntimePermissionGate
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Local AlarmManager reminders. Data is committed before arming and restored after reboot. */
object ReminderScheduler {
    private const val PREFS = "jarvis_reminders"
    private const val KEY_ITEMS = "items_v1"
    private const val CHANNEL_ID = "jarvis_reminders"
    private const val ACTION_DUE = "com.droidcraft.app.action.REMINDER_DUE"
    private const val MAX_REMINDERS = 100
    private val lock = Any()

    data class Reminder(val id: String, val title: String, val atMillis: Long)

    suspend fun schedule(context: Context, title: String, atMillis: Long): Result<String> {
        val app = context.applicationContext
        val cleanTitle = title.trim().take(180)
        if (cleanTitle.isBlank()) return Result.failure(IllegalArgumentException("A reminder needs a short title."))
        val now = System.currentTimeMillis()
        if (atMillis <= now + 5_000L) return Result.failure(IllegalArgumentException("Choose a reminder time at least a few seconds in the future."))
        if (atMillis > now + 366L * 24 * 60 * 60 * 1000) {
            return Result.failure(IllegalArgumentException("Reminders can be set up to one year ahead."))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            val granted = RuntimePermissionGate.request(app, Manifest.permission.POST_NOTIFICATIONS)
            if (!granted) return Result.failure(SecurityException("Notification permission was denied; the reminder was not scheduled."))
        }

        val reminder = Reminder(UUID.randomUUID().toString(), cleanTitle, atMillis)
        synchronized(lock) {
            val existing = readItems(app).filter { it.atMillis > now }.take(MAX_REMINDERS - 1)
            val serialized = JSONArray().apply {
                (existing + reminder).forEach { item ->
                    put(JSONObject().put("id", item.id).put("title", item.title).put("at", item.atMillis))
                }
            }
            if (!app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ITEMS, serialized.toString()).commit()) {
                return Result.failure(IllegalStateException("The reminder could not be saved on this device."))
            }
            runCatching { arm(app, reminder) }.onFailure {
                // Roll back the saved row rather than promise an alarm that was not armed.
                writeItems(app, existing)
                return Result.failure(it)
            }
        }
        val time = SimpleDateFormat("EEE, d MMM, h:mm a", Locale.getDefault()).format(Date(atMillis))
        return Result.success("Reminder saved on this device for $time: $cleanTitle. Android may delay alarms during battery saving; the app has an optional battery-optimisation prompt in Settings.")
    }

    fun restoreAfterBoot(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val future = readItems(app).filter { it.atMillis > now }
            writeItems(app, future)
            future.forEach { reminder -> runCatching { arm(app, reminder) } }
        }
    }

    internal fun deliver(context: Context, reminderId: String) {
        val app = context.applicationContext
        val reminder = synchronized(lock) {
            val items = readItems(app)
            val due = items.firstOrNull { it.id == reminderId }
            writeItems(app, items.filterNot { it.id == reminderId })
            due
        } ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("last_missed", reminder.title)
                .apply()
            return
        }
        createChannel(app)
        val openApp = PendingIntent.getActivity(
            app,
            reminder.id.hashCode(),
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Jarvis reminder")
            .setContentText(reminder.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.title))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { NotificationManagerCompat.from(app).notify(reminder.id.hashCode(), notification) }
            .onFailure {
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("last_missed", reminder.title)
                    .apply()
            }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            runCatching {
                val power = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                power.isIgnoringBatteryOptimizations(context.packageName)
            }.getOrDefault(false)

    /** Opens Android's affirmative/decline system prompt only after the user asks for it. */
    fun requestBatteryOptimizationExemption(context: Context): Result<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return Result.success("Battery optimisation exemption is not needed on this Android version.")
        if (isIgnoringBatteryOptimizations(context)) return Result.success("Jarvis is already exempt from battery optimisation.")
        val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            "Android's battery-optimisation prompt is open. Choose Allow only if you want reminders to be less likely to be delayed."
        }
    }

    private fun arm(context: Context, reminder: Reminder) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderAlarmReceiver::class.java)
            .setAction(ACTION_DUE)
            .setData(Uri.parse("droidcraft://reminder/${reminder.id}"))
            .putExtra(EXTRA_REMINDER_ID, reminder.id)
        val pending = PendingIntent.getBroadcast(
            context,
            reminder.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.atMillis, pending)
    }

    private fun readItems(context: Context): List<Reminder> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            buildList {
                for (index in 0 until json.length()) {
                    val item = json.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    val title = item.optString("title")
                    val at = item.optLong("at", 0L)
                    if (id.isNotBlank() && title.isNotBlank() && at > 0L) add(Reminder(id, title, at))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeItems(context: Context, items: List<Reminder>) {
        val json = JSONArray().apply {
            items.take(MAX_REMINDERS).forEach { item ->
                put(JSONObject().put("id", item.id).put("title", item.title).put("at", item.atMillis))
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ITEMS, json.toString()).commit()
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Jarvis reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "User-requested local reminders"
            }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    internal const val EXTRA_REMINDER_ID = "reminder_id"
    private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
}

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
            ReminderScheduler.restoreAfterBoot(app)
            pending.finish()
            return
        }
        if (action == "com.droidcraft.app.action.REMINDER_DUE") {
            val id = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID).orEmpty()
            ReminderScheduler.deliver(app, id)
        }
        pending.finish()
    }
}
