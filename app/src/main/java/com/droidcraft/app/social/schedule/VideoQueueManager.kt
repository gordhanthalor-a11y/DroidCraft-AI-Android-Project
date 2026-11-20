package com.droidcraft.app.social.schedule

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.droidcraft.app.data.local.AppDatabase
import com.droidcraft.app.social.ai.CaptionGenerator
import com.droidcraft.app.social.data.PostQueueEntity
import com.droidcraft.app.social.data.SocialPlatform
import com.droidcraft.app.social.data.SocialStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Video queue manager — gallery URI से video निकालो, app storage में copy करो,
 * SHA-256 + duration निकालो, AI captions बनाओ, schedule करो.
 */
object VideoQueueManager {

    private const val TAG = "VideoQueueManager"
    private const val DIR = "social_videos"

    data class Queued(val postId: String, val platform: String, val scheduledAt: Long)

    /**
     * एक video को queue में डालो. हर platform के लिए अलग post entry.
     * @return बने हुए post IDs
     */
    suspend fun addVideo(
        context: Context,
        uri: Uri,
        platforms: List<String>,
        scheduledAt: Long,
        titleHint: String = ""
    ): List<Queued> = withContext(Dispatchers.IO) {
        val file = copyToAppStorage(context, uri)
            ?: throw IllegalStateException("Video copy नहीं हो सकी")
        val sha = sha256(file)
        val duration = durationSec(file)
        val isShort = duration in 1..60

        // Duplicate रोको — अगर यही video पहले से इसी platform पर है तो skip
        val db = AppDatabase.getDatabase(context)
        val existing = platforms.firstNotNullOfOrNull { p ->
            db.postQueueDao().findBySha(sha, p)
        }
        if (existing != null) {
            Log.i(TAG, "duplicate skip: ${existing.platform} already has sha $sha")
            return@withContext emptyList()
        }

        val masterTitle = titleHint.ifBlank { file.nameWithoutExtension.take(60) }
        val masterDesc = "Published via Jarvis Social"
        val masterTags = "#GTMarwadiVlogs #JarvisSocial"

        platforms.map { platform ->
            val id = UUID.randomUUID().toString()
            val post = PostQueueEntity(
                id = id,
                videoPath = file.absolutePath,
                videoSha256 = sha,
                title = masterTitle,
                description = masterDesc,
                hashtags = masterTags,
                thumbnailPath = null,
                durationSec = duration,
                isShort = isShort,
                platform = platform,
                accountId = "pending", // publish समय connect account लो
                scheduledAt = scheduledAt,
                status = SocialStatus.QUEUED
            )
            db.postQueueDao().upsert(post)

            // AI captions (अगर Gemini key है)
            val key = context.getSharedPreferences("droidcraft", Context.MODE_PRIVATE)
                .getString("gemini_key", "").orEmpty()
            if (key.isNotBlank()) {
                runCatching {
                    val c = CaptionGenerator.generate(context, post)
                    CaptionGenerator.save(context, post, c)
                }.onFailure { Log.e(TAG, "caption fail", it) }
            }

            // exact schedule
            PublishScheduler.schedule(context, id, scheduledAt)
            Queued(id, platform, scheduledAt)
        }
    }

    /** बहु वीडियो एक साथ (50-100) — timestamps को spread करके. */
    suspend fun addBatch(
        context: Context,
        uris: List<Uri>,
        platforms: List<String>,
        startAt: Long,
        intervalMs: Long = 30 * 60 * 1000L
    ): List<Queued> = withContext(Dispatchers.IO) {
        val all = mutableListOf<Queued>()
        var t = startAt
        uris.forEach { uri ->
            all += addVideo(context, uri, platforms, t)
            t += intervalMs
        }
        all
    }

    /** Gallery URI को app-internal file में copy (persist permission सहित). */
    private fun copyToAppStorage(context: Context, uri: Uri): File? = runCatching {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val name = queryName(context, uri) ?: "video_${System.currentTimeMillis()}.mp4"
        val dest = File(dir, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
        // persist permission ताकि future में भी readable हो
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        dest
    }.getOrElse {
        Log.e(TAG, "copy fail", it)
        null
    }

    private fun queryName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    fun durationSec(file: File): Int = runCatching {
        val r = MediaMetadataRetriever()
        r.setDataSource(file.absolutePath)
        val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        r.release()
        (ms / 1000).toInt()
    }.getOrDefault(0)

    /** Platforms जो connected हैं — queue बनाते समय default. */
    suspend fun connectedPlatforms(context: Context): List<String> {
        val db = AppDatabase.getDatabase(context)
        return listOf(
            SocialPlatform.YOUTUBE to db.socialAccountDao().getActive(SocialPlatform.YOUTUBE),
            SocialPlatform.INSTAGRAM to db.socialAccountDao().getActive(SocialPlatform.INSTAGRAM),
            SocialPlatform.FACEBOOK to db.socialAccountDao().getActive(SocialPlatform.FACEBOOK)
        ).mapNotNull { (name, acc) -> if (acc != null) name else null }
    }
}
