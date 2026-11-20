package com.droidcraft.app.studio

import android.content.Context
import android.util.Log
import com.droidcraft.app.studio.tts.TtsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Dubbing (B3) + Clipper (B4) के server calls। App = UI/remote; server = काम।
 */
class StudioClient(private val context: Context) {

    companion object {
        private const val TAG = "StudioClient"
        private const val POLL_MS = 3000L
        private const val MAX_WAIT_MS = 20 * 60 * 1000L
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    private fun url(path: String) = StudioSettings.serverUrl(context) + path

    private fun post(path: String, body: JSONObject): JSONObject {
        val resp = client.newCall(
            Request.Builder().url(url(path))
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
        ).execute()
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw IllegalStateException("server ${resp.code}: ${text.take(160)}")
        return JSONObject(text)
    }

    private suspend fun getJson(path: String): JSONObject = withContext(Dispatchers.IO) {
        val resp = client.newCall(Request.Builder().url(url(path)).build()).execute()
        JSONObject(resp.body?.string().orEmpty())
    }

    /** Job पूरा होने तक wait करो (या failed)। */
    private suspend fun awaitJob(path: String, statusField: String = "status"): JSONObject {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < MAX_WAIT_MS) {
            val j = getJson(path)
            val st = j.optString(statusField, "")
            if (st == "ready" || st == "done" || st == "failed") return j
            kotlinx.coroutines.delay(POLL_MS)
        }
        throw IllegalStateException("server job timeout")
    }

    // ---------------------------------------------------------------- Dubbing

    data class DubJob(
        val id: String, val status: String, val transcript: String,
        val outputUrl: String?, val error: String?
    )

    suspend fun dubPrepare(url: String): DubJob = withContext(Dispatchers.IO) {
        val j = post("/api/v1/dub/prepare", JSONObject().put("url", url))
        DubJob(j.optString("id"), j.optString("status"), "", null, j.optString("error"))
    }

    suspend fun dubWait(jobId: String): DubJob = withContext(Dispatchers.IO) {
        val j = awaitJob("/api/v1/dub/$jobId")
        DubJob(
            j.optString("id"), j.optString("status"), j.optString("transcript"),
            null, j.optString("error").takeIf { it.isNotBlank() }
        )
    }

    suspend fun dubRender(jobId: String, script: String, voice: String, provider: String, speed: Float): DubJob =
        withContext(Dispatchers.IO) {
            val j = post(
                "/api/v1/dub/render",
                JSONObject().put("job_id", jobId).put("script", script)
                    .put("voice", voice).put("provider", provider).put("speed", speed.toDouble())
            )
            awaitJob("/api/v1/dub/$jobId")
            DubJob(
                j.optString("job_id", jobId), "done", "",
                url("/api/v1/dub/$jobId/file"), j.optString("error").takeIf { it.isNotBlank() }
            )
        }

    /** Dubbed video download करके cache में सहेजो। */
    suspend fun downloadVideo(remoteUrl: String, name: String): File = withContext(Dispatchers.IO) {
        val resp = client.newCall(Request.Builder().url(remoteUrl).build()).execute()
        val dir = File(context.cacheDir, "studio").apply { mkdirs() }
        val f = File(dir, name)
        f.writeBytes(resp.body?.bytes() ?: byteArrayOf())
        f
    }

    // ---------------------------------------------------------------- Clipper

    data class ClipJob(
        val id: String, val status: String,
        val clips: List<Pair<Int, String>>, // index, server path
        val credit: String?, val error: String?
    )

    suspend fun clipStart(url: String, clipSec: Int, layout: String, credit: String, parts: Int): ClipJob =
        withContext(Dispatchers.IO) {
            val j = post(
                "/api/v1/clip",
                JSONObject().put("url", url).put("clip_sec", clipSec)
                    .put("layout", layout).put("credit", credit).put("parts", parts)
            )
            ClipJob(j.optString("id"), j.optString("status"), emptyList(), null, j.optString("error"))
        }

    suspend fun clipWait(jobId: String): ClipJob = withContext(Dispatchers.IO) {
        val j = awaitJob("/api/v1/clip/$jobId")
        val arr = j.optJSONArray("clips")
        val clips = mutableListOf<Pair<Int, String>>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                clips += c.optInt("index") to c.optString("url_path")
            }
        }
        ClipJob(
            j.optString("id"), j.optString("status"), clips,
            j.optString("credit").takeIf { it.isNotBlank() },
            j.optString("error").takeIf { it.isNotBlank() }
        )
    }
}
