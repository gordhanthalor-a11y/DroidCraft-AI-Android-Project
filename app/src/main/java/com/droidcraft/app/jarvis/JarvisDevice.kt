package com.droidcraft.app.jarvis

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
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

    /** Koi bhi JarvisIntent chalao; natija Hinglish message mein milta hai. */
    suspend fun execute(intent: JarvisIntent): JarvisOutcome = withContext(Dispatchers.Main) {
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
            JarvisIntentType.CALL -> dial(intent.args["number"].orEmpty())
            JarvisIntentType.SMS -> sms(intent.args["number"].orEmpty(), intent.args["body"].orEmpty())
            JarvisIntentType.ALARM -> setAlarm(
                intent.args["hour"]?.toIntOrNull() ?: 7,
                intent.args["minute"]?.toIntOrNull() ?: 0
            )
            JarvisIntentType.TIMER -> setTimer(intent.args["seconds"]?.toIntOrNull() ?: 60)
            JarvisIntentType.NOTE_ADD -> addNote(intent.args["text"].orEmpty())
            JarvisIntentType.NOTE_LIST -> listNotes()
            JarvisIntentType.WEATHER -> withContext(Dispatchers.IO) { weather(intent.args["city"].orEmpty()) }
            JarvisIntentType.MEMORY -> JarvisOutcome("Theek hai, ab main aapka naam yaad rakh lunga.")
            JarvisIntentType.CREATOR_HELP, JarvisIntentType.UNKNOWN ->
                JarvisOutcome("Ye kaam main abhi nahi kar sakta.")
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
        "Main J.A.R.V.I.S. hoon — Just A Rather Very Intelligent System. Aapka personal assistant: " +
            "apps kholna, call/SMS, alarm, timer, mausam, notes, flashlight, battery — sab ke liye " +
            "mujhe batariye. Saath hi aapke YouTube creator kaam mein bhi madad karta hoon."

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
        val q = query.trim()
        if (q.isBlank()) return JarvisOutcome("Kaunsa app khoolu? Naam bataiye.", false)
        val apps = launcherApps()
        val best = apps.map { (label, launch) ->
            val l = normalizeLabel(label)
            val score = when {
                l == q -> 100
                l.contains(q) || q.contains(l) -> 60
                else -> {
                    val qTokens = q.split(" ").filter { it.length > 1 }
                    val lTokens = l.split(" ")
                    qTokens.count { token -> lTokens.any { it.startsWith(token) || token.startsWith(it) } } * 20
                }
            }
            Triple(label, launch, score)
        }.filter { it.third >= 20 }.maxByOrNull { it.third }

        if (best != null) {
            return openIntent(best.second, "${best.first} khol raha hoon.")
        }
        // Device par nahi mila — Play Store par dhoondo
        val store = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=${urlEncode(q)}&c=apps"))
        return openIntent(store, "'$q' is phone mein nahi mila — Play Store khol raha hoon.")
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
            "chat" to "whatsapp", "wp" to "whatsapp",
            "insta" to "instagram", "ig" to "instagram",
            "maps" to "google maps", "gmail" to "gmail",
            "chrome" to "chrome", "browser" to "chrome",
            "yt" to "youtube", "camera" to "camera",
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

    private fun dial(number: String): JarvisOutcome {
        val n = number.filter { it.isDigit() || it == '+' }
        if (n.length < 6) return JarvisOutcome("Sahi number bataiye — 10 digit ka mobile number.", false)
        return openIntent(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n")), "Dial pad khol raha hoon.")
    }

    private fun sms(number: String, body: String): JarvisOutcome {
        val n = number.filter { it.isDigit() || it == '+' }
        if (n.length < 6) return JarvisOutcome("SMS ke liye number bataiye.", false)
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$n")).apply {
            putExtra("sms_body", body)
        }
        return openIntent(intent, "SMS app khol raha hoon.")
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

    private fun toggleFlashlight(on: Boolean): JarvisOutcome {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                runCatching {
                    cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }.getOrDefault(false)
            } ?: cm.cameraIdList.firstOrNull()
            if (cameraId == null) return JarvisOutcome("Is phone mein flashlight nahi hai.", false)
            cm.setTorchMode(cameraId, on)
            JarvisOutcome(if (on) "Flashlight on kar di." else "Flashlight off kar di.")
        } catch (e: Exception) {
            JarvisOutcome("Flashlight control nahi kar paya: ${e.message}", false)
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
