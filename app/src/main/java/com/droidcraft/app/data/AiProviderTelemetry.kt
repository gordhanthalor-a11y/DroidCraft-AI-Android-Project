package com.droidcraft.app.data

import android.content.Context

/** Measured end-to-end provider-route attempt durations, persisted as raw count/sum rather than estimates. */
data class ProviderLatency(
    val provider: AiProvider,
    val sampleCount: Long,
    val averageMs: Long?,
    val lastMs: Long?,
    val lastSucceeded: Boolean?
)

object AiProviderTelemetry {
    private const val PREFS = "jarvis_provider_latency"
    private val lock = Any()

    fun record(context: Context, provider: AiProvider, elapsedMs: Long, succeeded: Boolean) {
        synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val prefix = provider.name.lowercase()
            val countKey = "${prefix}_count"
            val totalKey = "${prefix}_total_ms"
            val count = prefs.getLong(countKey, 0L)
            val total = prefs.getLong(totalKey, 0L)
            val duration = elapsedMs.coerceAtLeast(0L)
            prefs.edit()
                .putLong(countKey, count + 1)
                .putLong(totalKey, total + duration)
                .putLong("${prefix}_last_ms", duration)
                .putBoolean("${prefix}_last_ok", succeeded)
                .apply()
        }
    }

    fun read(context: Context, provider: AiProvider): ProviderLatency {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = provider.name.lowercase()
        val count = prefs.getLong("${prefix}_count", 0L)
        val total = prefs.getLong("${prefix}_total_ms", 0L)
        val lastKey = "${prefix}_last_ms"
        val successKey = "${prefix}_last_ok"
        return ProviderLatency(
            provider = provider,
            sampleCount = count,
            averageMs = if (count > 0) total / count else null,
            lastMs = if (prefs.contains(lastKey)) prefs.getLong(lastKey, 0L) else null,
            lastSucceeded = if (prefs.contains(successKey)) prefs.getBoolean(successKey, false) else null
        )
    }

    fun all(context: Context): List<ProviderLatency> = AiProvider.values().map { read(context, it) }
}
