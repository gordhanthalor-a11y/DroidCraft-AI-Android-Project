package com.droidcraft.app.studio

import android.content.Context
import android.net.Uri
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

    fun serverUrl(path: String): String {
        require(path.startsWith("/") && !path.startsWith("//")) { "Expected a path on the Creator Studio server." }
        val parsedPath = Uri.parse(path)
        require(
            parsedPath.scheme.isNullOrBlank() && parsedPath.host.isNullOrBlank() &&
                parsedPath.query == null && parsedPath.fragment == null &&
                parsedPath.pathSegments.none { it == "." || it == ".." }
        ) { "Creator Studio download path must stay on the configured server." }
        return StudioSettings.requireServerUrl(context) + path
    }

    private fun url(path: String) = serverUrl(path)

    private fun post(path: String, body: JSONObject, headers: Map<String, String> = emptyMap()): JSONObject {
        val builder = Request.Builder().url(url(path))
        headers.forEach { (name, value) -> builder.addHeader(name, value) }
        val resp = client.newCall(
            builder
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
        ).execute()
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw IllegalStateException("server ${resp.code}: ${text.take(160)}")
        return JSONObject(text)
    }

    private suspend fun getJson(path: String): JSONObject = withContext(Dispatchers.IO) {
        val resp = client.newCall(Request.Builder().url(url(path)).build()).execute()
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw IllegalStateException("server ${resp.code}: ${text.take(240)}")
        JSONObject(text)
    }

    /** Job पूरा होने तक wait करो (या failed)। */
    private suspend fun awaitJob(path: String, statusField: String = "status"): JSONObject {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < MAX_WAIT_MS) {
            val j = getJson(path)
            if (j.length() == 0) throw IllegalStateException("Creator Studio could not find this job.")
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
        val status = j.optString("status")
        val error = j.optString("error").takeIf { it.isNotBlank() && it != "null" }
        if (status == "failed") throw IllegalStateException(error ?: "Dubbing preparation failed on the server.")
        DubJob(
            j.optString("id", jobId), status, j.optString("transcript"),
            null, error
        )
    }

    suspend fun dubRender(jobId: String, script: String, voice: String, provider: String, speed: Float): DubJob =
        withContext(Dispatchers.IO) {
            val providerKey = when (provider.lowercase()) {
                "smallest" -> StudioSettings.smallestKey(context)
                "heygen" -> StudioSettings.heygenKey(context)
                else -> ""
            }
            val headers = if (providerKey.isBlank()) emptyMap() else mapOf("X-Provider-Key" to providerKey)
            val started = post(
                "/api/v1/dub/render",
                JSONObject().put("job_id", jobId).put("script", script)
                    .put("voice", voice).put("provider", provider).put("speed", speed.toDouble()),
                headers
            )
            if (!started.optBoolean("ok", false)) {
                throw IllegalStateException(started.optString("error").ifBlank { "The server could not start dubbing." })
            }
            val completed = awaitJob("/api/v1/dub/$jobId")
            val status = completed.optString("status")
            val error = completed.optString("error").takeIf { it.isNotBlank() && it != "null" }
            if (status != "done") throw IllegalStateException(error ?: "Dubbing render did not complete (status: $status).")
            DubJob(
                completed.optString("id", jobId), status, completed.optString("transcript"),
                serverUrl("/api/v1/dub/$jobId/file"), error
            )
        }

    /** Dubbed video download करके cache में सहेजो। */
    suspend fun downloadVideo(remoteUrl: String, name: String): File = withContext(Dispatchers.IO) {
        val configuredServer = Uri.parse(StudioSettings.requireServerUrl(context))
        val requestedFile = Uri.parse(remoteUrl)
        require(
            requestedFile.scheme == configuredServer.scheme &&
                requestedFile.host?.equals(configuredServer.host, ignoreCase = true) == true &&
                requestedFile.port == configuredServer.port
        ) { "Refusing to download a file from outside the configured Creator Studio server." }
        val resp = client.newCall(Request.Builder().url(remoteUrl).build()).execute()
        val bytes = resp.body?.bytes() ?: byteArrayOf()
        if (!resp.isSuccessful) {
            throw IllegalStateException("server ${resp.code}: ${bytes.toString(Charsets.UTF_8).take(200)}")
        }
        if (bytes.isEmpty()) throw IllegalStateException("Server returned an empty video file.")
        val dir = File(context.cacheDir, "studio").apply { mkdirs() }
        val f = File(dir, name)
        f.writeBytes(bytes)
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
        val status = j.optString("status")
        val error = j.optString("error").takeIf { it.isNotBlank() && it != "null" }
        if (status == "failed") throw IllegalStateException(error ?: "Clip generation failed on the server.")
        val arr = j.optJSONArray("clips")
        val clips = mutableListOf<Pair<Int, String>>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                clips += c.optInt("index") to c.optString("url_path")
            }
        }
        if (clips.isEmpty()) throw IllegalStateException(error ?: "The server finished but did not produce any clips.")
        ClipJob(
            j.optString("id", jobId), status, clips,
            j.optString("credit").takeIf { it.isNotBlank() && it != "null" },
            error
        )
    }
}
