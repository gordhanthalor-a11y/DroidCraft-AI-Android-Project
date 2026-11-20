package com.droidcraft.app.jarvis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class HudWeather(
    val place: String,
    val temperatureC: Int,
    val condition: String
)

/** Small, keyless Open-Meteo request for the HUD. Coordinates are used only for this request. */
class HudWeatherRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun current(latitude: Double, longitude: Double, place: String): HudWeather = withContext(Dispatchers.IO) {
        require(latitude.isFinite() && longitude.isFinite()) { "Location is unavailable." }
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$latitude&longitude=$longitude" +
            "&current=temperature_2m,weather_code&timezone=auto"
        val request = Request.Builder().url(url).get().build()
        val responseText = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Weather service returned HTTP ${response.code}.")
            response.body?.string().orEmpty()
        }
        val current = JSONObject(responseText).optJSONObject("current")
            ?: throw IOException("Weather service returned no current conditions.")
        val temperature = current.optDouble("temperature_2m", Double.NaN)
        if (!temperature.isFinite()) throw IOException("Temperature is not available right now.")
        HudWeather(
            place = place.ifBlank { "Nearby" },
            temperatureC = temperature.toInt(),
            condition = describe(current.optInt("weather_code", -1))
        )
    }

    private fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Foggy"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 65, 66, 67 -> "Rain"
        71, 73, 75, 77, 85, 86 -> "Snow"
        80, 81, 82 -> "Rain showers"
        95, 96, 99 -> "Thunderstorm"
        else -> "Weather updated"
    }
}
