package com.droidcraft.app.jarvis

import android.Manifest
import android.content.Context
import com.droidcraft.app.data.CapabilitySettings
import com.droidcraft.app.data.JarvisCapability
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.droidcraft.app.jarvis.a11y.JarvisAccessibilityService
import com.droidcraft.app.jarvis.a11y.LabelTapResult
import com.droidcraft.app.jarvis.a11y.ScreenCaptureCoordinator
import com.droidcraft.app.jarvis.contacts.ContactResolver
import com.droidcraft.app.jarvis.memory.MemoryManager
import com.droidcraft.app.jarvis.notifications.NotificationStore
import com.droidcraft.app.jarvis.permissions.RuntimePermissionGate
import com.droidcraft.app.jarvis.reminders.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Device actions ka engine: apps, calls, sms, alarm, timer, weather,
 * notes, flashlight, battery — sab yahan se chalte hain.
 */
class JarvisDevice(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val notesPrefs = context.getSharedPreferences("jarvis_notes", Context.MODE_PRIVATE)
    private val contacts = ContactResolver(context)
    private val memory = MemoryManager(context)

    /** Koi bhi JarvisIntent chalao; natija Hinglish message mein milta hai. */
    suspend fun execute(intent: JarvisIntent): JarvisOutcome = withContext(Dispatchers.Main) {
        val capability = when (intent.type) {
            JarvisIntentType.FLASHLIGHT,
            JarvisIntentType.OPEN_APP,
            JarvisIntentType.WEB_SEARCH,
            JarvisIntentType.OPEN_URL,
            JarvisIntentType.BATTERY_EXEMPTION,
            JarvisIntentType.SCREEN_DUMP,
            JarvisIntentType.SCREENSHOT,
            JarvisIntentType.TAP,
            JarvisIntentType.SWIPE,
            JarvisIntentType.SCROLL,
            JarvisIntentType.NAVIGATE,
            JarvisIntentType.TYPE_TEXT -> JarvisCapability.PHONE_CONTROL
            JarvisIntentType.CALL,
            JarvisIntentType.CONTACT_LOOKUP -> JarvisCapability.CONTACTS
            JarvisIntentType.NOTIFICATIONS_READ -> JarvisCapability.NOTIFICATIONS
            JarvisIntentType.CALENDAR_READ -> JarvisCapability.CALENDAR
            JarvisIntentType.REMINDER_ADD,
            JarvisIntentType.ALARM,
            JarvisIntentType.TIMER -> JarvisCapability.REMINDERS
            else -> null
        }
        if (capability != null && !CapabilitySettings.isEnabled(context, capability)) {
            return@withContext JarvisOutcome("${capability.label} is turned off in Settings. No phone action was taken.", false)
        }
        when (intent.type) {
            JarvisIntentType.TIME -> JarvisOutcome(timeText())
            JarvisIntentType.DATE -> JarvisOutcome(dateText())
            JarvisIntentType.BATTERY -> JarvisOutcome(batteryText())
            JarvisIntentType.DEVICE_INFO -> JarvisOutcome(deviceText())
            JarvisIntentType.JOKE -> JarvisOutcome(joke())
            JarvisIntentType.GREETING -> JarvisOutcome(greeting())
            JarvisIntentType.IDENTITY -> JarvisOutcome(identity())
            JarvisIntentType.FLASHLIGHT -> toggleFlashlight(intent.args["on"] != "false")
            JarvisIntentType.OPEN_APP -> openApp(intent.args["query"].orEmpty())
            JarvisIntentType.WEB_SEARCH -> openIntent(webSearchIntent(intent.args["query"].orEmpty()), "Web search khol raha hoon.")
            JarvisIntentType.OPEN_URL -> openIntent(viewIntent(intent.args["url"].orEmpty()), "Link khol raha hoon.")
            JarvisIntentType.CALL -> dial(intent.args["number"].orEmpty(), intent.args["name"].orEmpty())
            JarvisIntentType.SMS -> JarvisOutcome(
                "SMS read/send is deferred while core phone controls are being verified. No message was prepared or sent.",
                false
            )
            JarvisIntentType.CONTACT_LOOKUP -> lookupContact(intent.args["name"].orEmpty())
            JarvisIntentType.CALENDAR_READ -> readCalendar(intent.args["days"]?.toIntOrNull() ?: 7)
            JarvisIntentType.NOTIFICATIONS_READ -> readNotifications(intent.args["limit"]?.toIntOrNull() ?: 20)
            JarvisIntentType.REMINDER_ADD -> addReminder(intent.args)
            JarvisIntentType.BATTERY_EXEMPTION -> requestBatteryExemption()
            JarvisIntentType.ALARM -> setAlarm(
                intent.args["hour"]?.toIntOrNull() ?: 7,
                intent.args["minute"]?.toIntOrNull() ?: 0
            )
            JarvisIntentType.TIMER -> setTimer(intent.args["seconds"]?.toIntOrNull() ?: 60)
            JarvisIntentType.NOTE_ADD -> addNote(intent.args["text"].orEmpty())
            JarvisIntentType.NOTE_LIST -> listNotes()
            JarvisIntentType.SCREEN_DUMP -> screenDump(intent.args["query"].orEmpty())
            JarvisIntentType.SCREENSHOT -> captureScreenshot()
            JarvisIntentType.TAP -> tapOn(
                intent.args["target"].orEmpty(),
                confirmedByUser = intent.args["confirmed"] == "true",
                expectedPackageName = intent.args["expected_package"]
            )
            JarvisIntentType.SWIPE -> swipe(intent.args["direction"].orEmpty())
            JarvisIntentType.SCROLL -> scroll(intent.args["direction"].orEmpty())
            JarvisIntentType.NAVIGATE -> navigate(intent.args["action"].orEmpty())
            JarvisIntentType.TYPE_TEXT -> typeIntoFocusedField(intent.args["text"].orEmpty())
            JarvisIntentType.MEMORY_ADD -> remember(intent.args["text"].orEmpty())
            JarvisIntentType.MEMORY_LIST -> recall(intent.args["query"].orEmpty())
            JarvisIntentType.WEATHER -> withContext(Dispatchers.IO) { weather(intent.args["city"].orEmpty()) }
            JarvisIntentType.MEMORY -> JarvisOutcome("Theek hai, ab main aapka naam yaad rakh lunga.")
            JarvisIntentType.UNKNOWN -> JarvisOutcome("Ye kaam main abhi nahi kar sakta.")
        }
    }

    // ---------------- time / date / device ----------------

    private fun timeText(): String {
        val now = Date()
        val time = SimpleDateFormat("h:mm a", Locale.ENGLISH).format(now)
        val date = SimpleDateFormat("d MMMM yyyy", Locale("hi", "IN")).format(now)
        return "Abhi $date hai aur samay $time hai."
    }

    private fun dateText(): String {
        val now = Date()
        val date = SimpleDateFormat("d MMMM yyyy", Locale("hi", "IN")).format(now)
        val day = SimpleDateFormat("EEEE", Locale("hi", "IN")).format(now)
        return "Aaj $date, $day hai."
    }

    private fun batteryText(): String {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            if (pct in 0..100) {
                val state = if (charging) "aur charging chal rahi hai" else "aur charger lagaa nahi hai"
                "Battery $pct% hai, $state."
            } else {
                "Battery ki sahi jaankaari nahi mil payi."
            }
        } catch (e: Exception) {
            "Battery ki jaankaari nahi mil payi: ${e.message}"
        }
    }

    private fun deviceText(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        val androidVer = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        return "Aapka phone: $manufacturer $model. Android version: $androidVer."
    }

    private fun greeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val part = when (hour) {
            in 4..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Good night"
        }
        return "$part! Main J.A.R.V.I.S. hoon — aapki seva mein taiyaar."
    }

    private fun identity(): String =
        "Main J.A.R.V.I.S. hoon — Just A Rather Very Intelligent System. Main general sawaalon aur " +
            "safe phone controls mein madad kar sakta hoon: screen padhna, visible controls, scroll, Back/Home aur focused field mein text."

    private fun joke(): String {
        val jokes = listOf(
            "Programmer ki biwi boli: 'Dukaan se ek doodh ki bottle aur roti laana.' Woh gaya aur do bottle doodh laaya. 'Ek bottle kyun laya?' 'Kyunki doodh ka coupon tha.'",
            "Teacher: 'Beta, 2 aur 2 kitne hote hain?' 'Mobile ke hisaab se 4, maa ke hisaab se jitne khaye.'",
            "Boss: 'Tumhare liye ek gift hai — ek cycle!' Employee: 'Par main toh ghar se aata hoon, cycle kahan?' Boss: 'Isi liye.'",
            "Aurat: 'Suno, tum mujhe kitna pyaar karte ho?' Aadmi: 'Chand tak, aur wapas.'",
            "Doctor: 'Aapko neend ki dawai lagani hogi, har raat 8 baje.' Mariz: 'Par doctor sahab, main hi 8 baje so jaata hoon!'",
            "Baccha: 'Papa, 10 rupaye de do.' Papa: 'Beta, pehle padhai karo.' Baccha: 'Padhai karke hi 10 rupaye dene the na.'",
            "IT support: 'Computer band karke dobara on karo.' Aadmi: 'Woh main karta hoon, par problem yahi hai ki band hi nahi hota!'",
            "Biwi: 'Suna hai aaj bazaar mein Anda paaya tha?' Pati: 'Haan.' Biwi: 'Toh aaja, ande dekh ke aa.'"
        )
        return jokes[Random.nextInt(jokes.size)]
    }

    // ---------------- apps / intents ----------------

    private fun openApp(query: String): JarvisOutcome {
        val rawQuery = query.trim()
        if (rawQuery.isBlank()) return JarvisOutcome("Which app should I open? Give me its name.", false)
        JarvisAccessibilityService.instance?.launchAppByName(rawQuery)?.let { label ->
            return JarvisOutcome("Opening $label.")
        }
        val needle = normalizeLabel(rawQuery)
        val apps = launcherApps()
        val exact = apps.filter { normalizeLabel(it.first) == needle }
        val candidates = if (exact.isNotEmpty()) exact else apps.filter { normalizeLabel(it.first).startsWith(needle) }
        val distinct = candidates.distinctBy { it.second.component?.packageName ?: it.first }
        return when (distinct.size) {
            1 -> openIntent(distinct.single().second, "Opening ${distinct.single().first}.")
            0 -> JarvisOutcome("No installed app has the unambiguous launcher name '$rawQuery'. No other app was opened.", false)
            else -> JarvisOutcome("More than one app matches '$rawQuery'. Name the exact app; nothing was opened.", false)
        }
    }

    private fun launcherApps(): List<Pair<String, Intent>> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching {
            pm.queryIntentActivities(intent, 0).mapNotNull { info ->
                val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault("")
                val launch = pm.getLaunchIntentForPackage(info.activityInfo.packageName)
                if (launch != null) label to launch else null
            }
        }.getOrDefault(emptyList())
    }

    private fun normalizeLabel(label: String): String {
        val alias = mapOf(
            "gpay" to "google pay", "googlepay" to "google pay",
            "insta" to "instagram", "ig" to "instagram",
            "maps" to "google maps", "gmail" to "gmail",
            "chrome" to "chrome", "browser" to "chrome",
            "camera" to "camera",
            "gallery" to "photos", "photos" to "photos",
            "clock" to "clock", "alarm" to "clock",
            "calculator" to "calculator", "calc" to "calculator",
            "telegram" to "telegram", "tg" to "telegram"
        )
        return alias[label.trim().lowercase()] ?: label.trim().lowercase()
    }

    private fun webSearchIntent(query: String): Intent {
        val q = urlEncode(query.ifBlank { "DroidCraft" })
        return Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$q"))
    }

    private fun viewIntent(raw: String): Intent {
        val url = if (raw.startsWith("http")) raw else "https://$raw"
        return Intent(Intent.ACTION_VIEW, Uri.parse(url))
    }

    private suspend fun dial(number: String, name: String): JarvisOutcome {
        val directNumber = normalizePhoneNumber(number)
        val resolved = if (directNumber != null) directNumber else {
            if (name.isBlank()) return JarvisOutcome("Tell me a phone number or a contact name to open the dialer.", false)
            if (!RuntimePermissionGate.request(context, Manifest.permission.READ_CONTACTS)) {
                return JarvisOutcome("Contacts permission was denied. No contact was read and no call was placed; allow it and ask again if you want to continue.", false)
            }
            withContext(Dispatchers.IO) { contacts.resolve(name)?.number }
                ?: return JarvisOutcome("No single unambiguous contact match for '$name'; no call was placed.", false)
        }
        return openIntent(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$resolved")),
            "The dialer is open for ${name.ifBlank { resolved }}. Review the number and tap Call yourself; Jarvis does not place calls."
        )
    }

    private fun normalizePhoneNumber(value: String): String? {
        val number = value.trim()
        val digits = number.filter { it.isDigit() || it == '+' }
        return digits.takeIf { it.count(Char::isDigit) >= 6 }
    }

    private suspend fun lookupContact(name: String): JarvisOutcome {
        val query = name.trim()
        if (query.isBlank()) return JarvisOutcome("Name one contact to look up.", false)
        if (!RuntimePermissionGate.request(context, Manifest.permission.READ_CONTACTS)) {
            return JarvisOutcome("Contacts permission was denied. No contact data was read.", false)
        }
        val contact = withContext(Dispatchers.IO) { contacts.resolve(query) }
            ?: return JarvisOutcome("No single unambiguous contact matched '$query'.", false)
        return JarvisOutcome("Contact match: ${contact.name}, ${contact.number}.")
    }

    private fun setAlarm(hour: Int, minute: Int): JarvisOutcome {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis Alarm")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            putExtra(AlarmClock.EXTRA_VIBRATE, true)
        }
        val time = String.format(Locale.ENGLISH, "%d:%02d", hour, minute)
        return openIntent(intent, "Alarm $time par set kar diya.")
    }

    private fun setTimer(seconds: Int): JarvisOutcome {
        if (seconds <= 0) return JarvisOutcome("Timer kitne second ka rakhun?", false)
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis Timer")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        val mins = seconds / 60
        val label = if (mins > 0) "$mins minute" else "$seconds second"
        return openIntent(intent, "$label ka timer set kar diya.")
    }

    private suspend fun toggleFlashlight(on: Boolean): JarvisOutcome {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED &&
            !RuntimePermissionGate.request(context, Manifest.permission.CAMERA)
        ) {
            return JarvisOutcome("Camera permission was denied. The flashlight was not changed; Jarvis does not take photos or record video.", false)
        }
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                runCatching {
                    cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }.getOrDefault(false)
            } ?: return JarvisOutcome("This phone does not expose a flashlight control.", false)
            cm.setTorchMode(cameraId, on)
            JarvisOutcome(if (on) "Flashlight on kar di." else "Flashlight off kar di.")
        } catch (error: Exception) {
            JarvisOutcome("Flashlight control nahi kar paya: ${error.message ?: "Android refused it."}", false)
        }
    }

    private fun openIntent(intent: Intent, successMessage: String): JarvisOutcome {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }
            .fold(
                onSuccess = { JarvisOutcome(successMessage) },
                onFailure = { JarvisOutcome("Kaam nahi ho paya: ${it.message}", false) }
            )
    }

    // ---------------- notes ----------------

    private fun addNote(text: String): JarvisOutcome {
        if (text.isBlank()) return JarvisOutcome("Kya note karun? Likhiye.", false)
        val time = SimpleDateFormat("d MMM, h:mm a", Locale("hi", "IN")).format(Date())
        val notes = loadNotes().toMutableList()
        notes.add(0, JarvisNote(System.currentTimeMillis(), text, time))
        saveNotes(notes)
        return JarvisOutcome("Note kar liya: \"$text\"")
    }

    private fun listNotes(): JarvisOutcome {
        val notes = loadNotes()
        if (notes.isEmpty()) return JarvisOutcome("Abhi koi note nahi hai. 'Note karo: ...' likhkar banaiye.")
        val text = notes.take(8).joinToString("\n") { "• ${it.text} (${it.time})" }
        return JarvisOutcome("Aapki notes:\n$text")
    }

    private suspend fun remember(text: String): JarvisOutcome {
        if (text.isBlank()) return JarvisOutcome("Kya yaad rakhun? Likhiye.", false)
        memory.remember(text)
        return JarvisOutcome("Yaad rakh liya: \"$text\"")
    }

    // ---------------- screen automation ----------------

    fun currentScreenElements(): String = JarvisAccessibilityService.instance?.dumpScreen().orEmpty()

    fun currentActivePackageName(): String? = JarvisAccessibilityService.instance?.activeWindowPackageName()

    fun currentActiveAppLabel(): String? = JarvisAccessibilityService.instance?.activeWindowAppLabel()

    private fun screenDump(query: String): JarvisOutcome {
        val service = JarvisAccessibilityService.instance ?: return accessibilityUnavailable()
        val text = service.dumpScreen()
        if (text.isBlank() || text.startsWith("No labelled accessibility elements")) {
            return JarvisOutcome("The active screen did not expose labelled accessibility elements. No screen action was attempted.", false)
        }
        val answer = if (query.isBlank()) text.take(1_500) else {
            text.lineSequence().filter { it.contains(query, ignoreCase = true) }.joinToString("\n")
                .ifBlank { "'$query' was not found in the labelled screen elements." }
        }
        return JarvisOutcome("Visible labelled screen elements:\n$answer")
    }

    private suspend fun captureScreenshot(): JarvisOutcome = try {
        val png = ScreenCaptureCoordinator.capture()
        JarvisOutcome(
            "A read-only screenshot was captured for this user-requested turn. The image is shared with Gemini only to answer this request.",
            imagePng = png
        )
    } catch (error: Exception) {
        JarvisOutcome("Screenshot not taken: ${error.message ?: "screen capture was not approved or unavailable."}", false)
    }

    private fun tapOn(target: String, confirmedByUser: Boolean, expectedPackageName: String?): JarvisOutcome {
        val service = JarvisAccessibilityService.instance ?: return accessibilityUnavailable()
        when (val decision = com.droidcraft.app.jarvis.a11y.ActionSafety.assessTap(
            target,
            service.activeWindowPackageName(),
            service.activeWindowAppLabel()
        )) {
            is com.droidcraft.app.jarvis.a11y.ActionSafety.TapDecision.Blocked -> return JarvisOutcome(decision.reason, false)
            is com.droidcraft.app.jarvis.a11y.ActionSafety.TapDecision.RequiresConfirmation ->
                if (!confirmedByUser) return JarvisOutcome(decision.reason, false)
            com.droidcraft.app.jarvis.a11y.ActionSafety.TapDecision.Allowed -> Unit
        }
        return when (val result = service.tapText(target, confirmedByUser, expectedPackageName)) {
            LabelTapResult.Tapped -> JarvisOutcome("Tapped the unique visible control labelled '$target'.")
            is LabelTapResult.Refused -> JarvisOutcome(result.reason, false)
            is LabelTapResult.NotFound -> JarvisOutcome(result.reason, false)
        }
    }

    private fun swipe(direction: String): JarvisOutcome {
        val service = JarvisAccessibilityService.instance ?: return accessibilityUnavailable()
        if (direction.lowercase(Locale.ROOT) !in setOf("up", "down", "left", "right")) {
            return JarvisOutcome("Swipe direction must be up, down, left, or right.", false)
        }
        return if (service.swipe(direction)) JarvisOutcome("Swiped $direction inside the visible scrollable area.")
        else JarvisOutcome("No visible scrollable area accepted that swipe; no screen-wide coordinate guess was used.", false)
    }

    private fun scroll(direction: String): JarvisOutcome {
        val service = JarvisAccessibilityService.instance ?: return accessibilityUnavailable()
        if (direction.lowercase(Locale.ROOT) !in setOf("up", "down")) return JarvisOutcome("Scroll direction must be up or down.", false)
        return if (service.scroll(direction)) JarvisOutcome("Scrolled the visible scrollable area ${direction}.")
        else JarvisOutcome("No visible scrollable element accepted scrolling; no screen-wide coordinate guess was used.", false)
    }

    private fun navigate(action: String): JarvisOutcome {
        val service = JarvisAccessibilityService.instance ?: return accessibilityUnavailable()
        return if (service.navigate(action)) {
            JarvisOutcome(if (action.equals("HOME", true)) "Moved to the Home screen." else "Went Back.")
        } else JarvisOutcome("Android did not accept the ${action.uppercase(Locale.ROOT)} navigation action.", false)
    }

    private fun typeIntoFocusedField(text: String): JarvisOutcome {
        val service = JarvisAccessibilityService.instance ?: return accessibilityUnavailable()
        if (text.isBlank()) return JarvisOutcome("No text was provided.", false)
        if (text.length > 2_000) return JarvisOutcome("Text is too long to type in one action; nothing was entered.", false)
        return if (service.typeIntoFocusedField(text)) JarvisOutcome("Entered the user-provided text in the field that already had focus.")
        else JarvisOutcome("No focused, enabled editable field was available; nothing was typed.", false)
    }

    private suspend fun readCalendar(daysAhead: Int): JarvisOutcome {
        if (!RuntimePermissionGate.request(context, Manifest.permission.READ_CALENDAR)) {
            return JarvisOutcome("Calendar permission was denied. No events were read; allow it only if you want this request handled.", false)
        }
        val days = daysAhead.coerceIn(1, 30)
        return withContext(Dispatchers.IO) {
            try {
                val start = System.currentTimeMillis()
                val end = start + days * 86_400_000L
                val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                    .appendPath(start.toString())
                    .appendPath(end.toString())
                    .build()
                val events = mutableListOf<String>()
                context.contentResolver.query(
                    uri,
                    arrayOf(
                        CalendarContract.Instances.TITLE,
                        CalendarContract.Instances.BEGIN,
                        CalendarContract.Instances.END,
                        CalendarContract.Instances.EVENT_LOCATION
                    ),
                    null, null,
                    CalendarContract.Instances.BEGIN + " ASC"
                )?.use { cursor ->
                    val titleIndex = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
                    val beginIndex = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
                    val endIndex = cursor.getColumnIndex(CalendarContract.Instances.END)
                    val locationIndex = cursor.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
                    while (cursor.moveToNext() && events.size < 30) {
                        val title = if (titleIndex >= 0) cursor.getString(titleIndex).orEmpty() else "Untitled event"
                        val begin = if (beginIndex >= 0) cursor.getLong(beginIndex) else 0L
                        val finish = if (endIndex >= 0) cursor.getLong(endIndex) else begin
                        val location = if (locationIndex >= 0) cursor.getString(locationIndex).orEmpty() else ""
                        val date = SimpleDateFormat("EEE, d MMM, h:mm a", Locale.getDefault()).format(Date(begin))
                        val until = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(finish))
                        events += "${date}–${until}: ${title.take(180)}" + location.takeIf { it.isNotBlank() }?.let { " · ${it.take(120)}" }.orEmpty()
                    }
                }
                if (events.isEmpty()) JarvisOutcome("No calendar events were found in the next $days days.")
                else JarvisOutcome("Calendar events for the next $days days:\n${events.joinToString("\n")}")
            } catch (_: SecurityException) {
                JarvisOutcome("Calendar permission is not available. No events were read; grant access in Android Settings and retry.", false)
            } catch (error: Exception) {
                JarvisOutcome("Calendar could not be read: ${error.message ?: "Android returned an error."}", false)
            }
        }
    }

    private fun readNotifications(limit: Int): JarvisOutcome {
        if (!NotificationStore.hasAccess(context)) {
            val opened = NotificationStore.openSettings(context)
            return JarvisOutcome(
                if (opened) "Notification access is not enabled. Android opened its notification-access settings; no notifications were read. Enable access and ask again."
                else "Notification access is not enabled. Open Android Settings → Special app access → Notification access; no notifications were read.",
                false
            )
        }
        return JarvisOutcome(NotificationStore.format(NotificationStore.latest(context, limit)))
    }

    private suspend fun addReminder(args: Map<String, String>): JarvisOutcome {
        val title = args["title"].orEmpty().trim()
        if (title.isBlank()) return JarvisOutcome("What should I remind you about? Nothing was scheduled.", false)
        val now = System.currentTimeMillis()
        val afterSeconds = args["after_seconds"]?.toLongOrNull()
            ?: args["after_minutes"]?.toLongOrNull()?.let { it * 60L }
        val atMillis = when {
            afterSeconds != null && afterSeconds in 10L..31_536_000L -> now + afterSeconds * 1_000L
            args["timestamp_millis"]?.toLongOrNull() != null -> args["timestamp_millis"]!!.toLong()
            !args["when_iso"].isNullOrBlank() -> parseReminderTime(args["when_iso"].orEmpty())
            else -> null
        } ?: return JarvisOutcome("I need a clear future time for that reminder. Nothing was scheduled.", false)
        return ReminderScheduler.schedule(context, title, atMillis).fold(
            onSuccess = { JarvisOutcome(it) },
            onFailure = { JarvisOutcome("Reminder was not scheduled: ${it.message ?: "Android could not arm the alarm."}", false) }
        )
    }

    private fun parseReminderTime(raw: String): Long? {
        val value = raw.trim()
        if (value.isBlank()) return null
        return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
    }

    private fun requestBatteryExemption(): JarvisOutcome =
        ReminderScheduler.requestBatteryOptimizationExemption(context).fold(
            onSuccess = { JarvisOutcome(it) },
            onFailure = { JarvisOutcome("Battery-optimisation settings could not be opened: ${it.message ?: "Android refused it."}", false) }
        )

    private fun accessibilityUnavailable() = JarvisOutcome(
        "Phone screen control ke liye Settings → Accessibility mein Jarvis Automation ON kijiye.",
        false
    )

    private suspend fun recall(query: String): JarvisOutcome {
        val facts = memory.search(query)
        if (facts.isEmpty()) {
            return JarvisOutcome("Abhi kuch yaad nahi hai. 'Remember that ...' likhkar batayiye.")
        }
        val text = facts.take(8).joinToString("\n") { "• ${it.value}" }
        return JarvisOutcome("Mujhe yaad hai:\n$text")
    }

    fun loadNotes(): List<JarvisNote> {
        val raw = notesPrefs.getString("notes", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                JarvisNote(o.getLong("id"), o.getString("text"), o.getString("time"))
            }
        }.getOrDefault(emptyList())
    }

    fun saveNotes(notes: List<JarvisNote>) {
        val arr = JSONArray()
        notes.forEach { n ->
            arr.put(JSONObject().put("id", n.id).put("text", n.text).put("time", n.time))
        }
        notesPrefs.edit().putString("notes", arr.toString()).apply()
    }

    // ---------------- weather (open-meteo, bina API key) ----------------

    private suspend fun weather(city: String): JarvisOutcome {
        return try {
            val q = city.ifBlank { "Jaipur" }
            val geo = getJson("https://geocoding-api.open-meteo.com/v1/search?name=${urlEncode(q)}&count=1&language=en&format=json")
            val results = geo.optJSONArray("results")
            if (results == null || results.length() == 0) {
                return JarvisOutcome("'$q' naam ka sheher nahi mila. Kisi bade sheher ka naam bataiye.", false)
            }
            val place = results.getJSONObject(0)
            val lat = place.getDouble("latitude")
            val lon = place.getDouble("longitude")
            val name = place.optString("name", q)
            val country = place.optString("country", "")
            val forecast = getJson(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m&timezone=auto"
            )
            val cur = forecast.getJSONObject("current")
            val temp = cur.getDouble("temperature_2m")
            val feels = cur.getDouble("apparent_temperature")
            val humidity = cur.optDouble("relative_humidity_2m", 0.0)
            val wind = cur.optDouble("wind_speed_10m", 0.0)
            val code = cur.optInt("weather_code", -1)
            val desc = weatherHindi(code)
            JarvisOutcome(
                "$name ($country): $desc. Temperature ${temp.toInt()}°C, mehsoos ${feels.toInt()}°C. " +
                    "Humidity ${humidity.toInt()}%, hawa ${wind.toInt()} km/h."
            )
        } catch (e: Exception) {
            JarvisOutcome("Mausam jaan nahi paya: ${e.message ?: "internet check kariye"}", false)
        }
    }

    private fun weatherHindi(code: Int): String = when (code) {
        0 -> "saaf aasman"
        1, 2 -> "thodi badli"
        3 -> "badli"
        45, 48 -> "dhund"
        51, 53, 55 -> "halki boondabaandi"
        56, 57 -> "thandi boondabaandi"
        61, 63, 65 -> "baarish"
        66, 67 -> "thandi baarish"
        71, 73, 75, 77 -> "barf"
        80, 81, 82 -> "baarish ki jhaad"
        85, 86 -> "barf ki jhaad"
        95, 96, 99 -> "toofani baarish"
        else -> "anjaan"
    }

    private fun getJson(url: String): JSONObject {
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return JSONObject(resp.body?.string() ?: "{}")
        }
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
