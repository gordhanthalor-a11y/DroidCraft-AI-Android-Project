package com.droidcraft.app.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class RedNoteLinkResolver {

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun resolveRedNoteUrl(url: String): RedNoteResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Resolving RedNote link: $url")
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            client.newCall(request).execute().use { response ->
                val finalUrl = response.request.url.toString()
                val body = response.body?.string() ?: ""

                // Extract public title
                val titleRegex = Regex("<title>([^<]+)</title>")
                val title = titleRegex.find(body)?.groupValues?.get(1)?.replace(" - 小红书", "") ?: "RedNote Video"

                // Extract video MP4 stream
                val mp4Regex = Regex("https?://[^\"'\\s]+\\.mp4[^\"'\\s]*")
                val streamUrl = mp4Regex.find(body)?.value

                if (streamUrl != null) {
                    Log.i(TAG, "Stream extracted: $streamUrl")
                    RedNoteResult.Success(streamUrl = streamUrl, canonicalUrl = finalUrl, title = title)
                } else {
                    Log.w(TAG, "Direct stream protected by Xiaohongshu WAF/Login. Directing to device picker.")
                    RedNoteResult.Protected(canonicalUrl = finalUrl, title = title)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "RedNote resolution failed: ${e.message}", e)
            RedNoteResult.Error(e.message ?: "Network error")
        }
    }

    sealed class RedNoteResult {
        data class Success(val streamUrl: String, val canonicalUrl: String, val title: String) : RedNoteResult()
        data class Protected(val canonicalUrl: String, val title: String) : RedNoteResult()
        data class Error(val message: String) : RedNoteResult()
    }

    companion object {
        private const val TAG = "RedNoteLinkResolver"
    }
}