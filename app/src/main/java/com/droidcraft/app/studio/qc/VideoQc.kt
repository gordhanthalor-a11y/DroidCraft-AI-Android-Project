package com.droidcraft.app.studio.qc

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.droidcraft.app.studio.StudioSettings
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
 * Upload Helper QC (spec B2) — वीडियो को upload से पहले जाँचता है:
 *  - Resolution 1080×1920 (Shorts)
 *  - Sample aspect ratio 1:1 और display aspect 9:16 (यह ज़रूरी है —
 *    एक बार hidden 16:9 flag की वजह से YouTube ने Short बनने से मना किया था)
 *  - Duration (Shorts < 60s), audio present, loudness ~ -14 LUFS
 *
 * Local (phone) से basic जाँचें; सटीक SAR/DAR/LUFS server के ffprobe से।
 */
object VideoQc {

    private const val TAG = "VideoQc"

    data class Check(
        val name: String,
        val pass: Boolean,
        val detail: String,
        val fix: String? = null
    )

    data class Report(
        val checks: List<Check>,
        val ok: Boolean,
        val privacyNote: String
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    // ---------------------------------------------------------------- local

    private data class LocalInfo(
        val width: Int, val height: Int, val rotation: Int,
        val durationMs: Long, val hasAudio: Boolean, val audioMime: String?
    )

    private fun localInfo(context: Context, uri: Uri): LocalInfo {
        var width = 0; var height = 0; var rotation = 0; var durationMs = 0L
        runCatching {
            val r = MediaMetadataRetriever()
            r.setDataSource(context, uri)
            width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            r.release()
        }
        var hasAudio = false
        var audioMime: String? = null
        runCatching {
            val ex = MediaExtractor()
            ex.setDataSource(context, uri, null)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    hasAudio = true
                    audioMime = mime
                    break
                }
            }
            ex.release()
        }
        return LocalInfo(width, height, rotation, durationMs, hasAudio, audioMime)
    }

    // ---------------------------------------------------------------- server

    private suspend fun serverQc(context: Context, file: File): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${StudioSettings.serverUrl(context)}/api/v1/qc"
            val body = file.readBytes().toRequestBody("video/mp4".toMediaType())
            val req = Request.Builder()
                .url(url)
                .addHeader("X-File-Name", file.name)
                .post(body)
                .build()
            val resp = client.newCall(req).execute()
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return@runCatching null
            JSONObject(text)
        }.getOrElse {
            Log.e(TAG, "server qc fail", it)
            null
        }
    }

    private suspend fun copyForServer(context: Context, uri: Uri): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "qc").apply { mkdirs() }
            val f = File(dir, "qc_${System.currentTimeMillis()}.mp4")
            context.contentResolver.openInputStream(uri)?.use { input ->
                f.outputStream().use { input.copyTo(it) }
            }
            f
        }.getOrNull()
    }

    // ---------------------------------------------------------------- public

    /**
     * पूरी QC report बनाओ। Server उपलब्ध न हो तो local checks + privacy note.
     */
    suspend fun run(context: Context, uri: Uri): Report = withContext(Dispatchers.IO) {
        val info = localInfo(context, uri)
        val file = copyForServer(context, uri)
        val server = file?.let { serverQc(context, it) }

        val checks = mutableListOf<Check>()

        // 1) resolution
        val rotated = info.rotation == 90 || info.rotation == 270
        val effW = if (rotated) info.height else info.width
        val effH = if (rotated) info.width else info.height
        checks += Check(
            "Resolution 1080×1920 (Shorts)",
            effW == 1080 && effH == 1920,
            "वीडियो ${effW}×${effH} है" + if (rotated) " (rotation ${info.rotation}°)" else "",
            if (!(effW == 1080 && effH == 1920)) "Server पर render करते समय 1080×1920 force करें" else null
        )

        // 2) SAR 1:1 + DAR 9:16 (server ffprobe)
        val v = server?.optJSONObject("video")
        if (v != null) {
            val sar = v.optString("sample_aspect_ratio", "?")
            val dar = v.optString("display_aspect_ratio", "?")
            val sarOk = sar == "1:1"
            val darOk = dar == "9:16"
            checks += Check(
                "Sample aspect ratio 1:1",
                sarOk,
                "SAR = $sar",
                if (!sarOk) "SAR 1:1 नहीं है — ffmpeg में setsar=1 लगाकर render करें" else null
            )
            checks += Check(
                "Display aspect ratio 9:16",
                darOk,
                "DAR = $dar",
                if (!darOk) "DAR 9:16 नहीं है — यही ज़िद पुरानी 16:9 flag वाली file की थी; setsar=1:1 + scale=1080:1920 करें" else null
            )
        } else {
            checks += Check(
                "Sample/display aspect (SAR 1:1, DAR 9:16)",
                false,
                "Server QC उपलब्ध नहीं — server URL check करें (${StudioSettings.serverUrl(context)})",
                "Render/Railway पर server चलाओ; साथ ही Settings में server URL सही डालें"
            )
        }

        // 3) duration
        val durSec = if (server?.optDouble("duration_sec") != null)
            server.optDouble("duration_sec") else info.durationMs / 1000.0
        checks += Check(
            "Duration (Shorts < 60s preferred)",
            durSec in 1.0..60.0,
            "वीडियो %.1f सेकंड का है".format(durSec),
            if (durSec > 60.0) "Shorts के लिए 60s से कम काटें" else null
        )

        // 4) audio present
        checks += Check(
            "Audio present",
            info.hasAudio,
            info.audioMime?.let { "audio track मिली ($it)" } ?: "audio track नहीं मिली",
            if (!info.hasAudio) "audio track जोड़ें — बिना आवाज़ का Short publish नहीं होगा" else null
        )

        // 5) loudness ~ -14 LUFS
        val lufs = server?.optJSONObject("loudness")?.optDouble("integrated_lufs")
        if (lufs != null) {
            checks += Check(
                "Loudness ~ −14 LUFS",
                lufs in -18.0..-10.0,
                "measured %.1f LUFS".format(lufs),
                if (lufs !in -18.0..-10.0)
                    "ffmpeg loudnorm=I=-14:TP=-1.5:LRA=11 से normalize करें" else null
            )
        } else {
            checks += Check(
                "Loudness ~ −14 LUFS",
                false,
                "server से loudness नहीं मापा जा सका",
                "server पर ffmpeg मौजूद हो तो यह जाँच अपने आप चलेगी"
            )
        }

        file?.delete()

        val ok = checks.all { it.pass }
        Report(
            checks = checks,
            ok = ok,
            privacyNote = PRIVACY_NOTE
        )
    }

    /**
     * YouTube Data API से upload की गई वीडियो क्यों "Private" रह सकती है — और क्या करें।
     */
    val PRIVACY_NOTE = """
YouTube API से upload की गई वीडियो अक्सर **Private** ही रह जाती है। कारण:
1. `status.privacyStatus` में "public" लिखा हो फिर भी, नया/अन-verified channel होने पर YouTube कुछ uploads को private रख देता है।
2. Channel का **verification** न होना (phone verify) — तब तक कुछ features बंद रहते हैं।
3. Video में **copyrighted audio** होने पर Content ID claim आकर YouTube उसे private/restricted कर देता है।
4. `publishAt` (scheduled) सेट हो तब तक वीडियो private रहती है।

**हमारा हल (Upload Helper में built-in):**
- हमेशा पहले **Unlisted** publish करो (public नहीं) — फिर YouTube checks का इंतज़ार करो।
- Checks pass → YouTube Studio में खुद एक बार **Public** करो (Shorts के लिए 24-48 घंटे)।
- API से public करने की कोशिश से पहले channel verification पूरा करो।
- App में privacy status post में सहेजा जाता है; दोबारा publish नहीं होगी।
""".trim()
}
