package com.droidcraft.app.social.ai

import android.content.Context
import android.util.Log
import com.droidcraft.app.data.GeminiApiKeyStore
import com.droidcraft.app.data.local.AppDatabase
import com.droidcraft.app.social.data.PostCaptionEntity
import com.droidcraft.app.social.data.PostQueueEntity
import com.droidcraft.app.social.data.SocialPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * AI caption generator — Gemini से हर platform के लिए अलग caption/title/hashtags.
 * Duplicate से बचाव: contentHash को unique रखते हैं (अगली बार अलग tone लाते हैं).
 */
object CaptionGenerator {

    private const val TAG = "CaptionGenerator"

    private val TONES = listOf(
        "energetic & punchy",
        "friendly & conversational",
        "professional & informative",
        "fun & casual",
        "dramatic & curiosity-driven"
    )

    data class Captions(
        val youtubeTitle: String,
        val youtubeDescription: String,
        val youtubeHashtags: String,
        val instagramCaption: String,
        val instagramHashtags: String,
        val facebookCaption: String,
        val topic: String
    )

    suspend fun generate(context: Context, post: PostQueueEntity): Captions = withContext(Dispatchers.IO) {
        val apiKey = GeminiApiKeyStore.read(context)
        val topic = post.title.ifBlank { "this video" }
        val tone = TONES[Random.nextInt(TONES.size)]
        val prompt = """
            You are a social media expert for a YouTube creator "GT Marwadi Vlogs".
            Video topic: "$topic". Short: ${post.isShort}. Duration: ${post.durationSec}s.
            Create platform-specific captions in Hinglish/English mix.
            IMPORTANT: Be original — this is variation #${post.retryCount + 1}, tone: $tone.
            Reply ONLY with valid JSON (no markdown):
            {
              "topic": "short topic phrase",
              "youtube_title": "max 80 chars, click-worthy",
              "youtube_description": "2-4 lines, with CTA",
              "youtube_hashtags": "comma separated, 8-12 tags",
              "instagram_caption": "engaging caption, 2-4 lines",
              "instagram_hashtags": "comma separated, 10-15 tags",
              "facebook_caption": "short friendly caption"
            }
        """.trimIndent()

        val jsonText = runCatching {
            val client = com.droidcraft.app.ai.GeminiClient(apiKey)
            kotlinx.coroutines.runBlocking { client.reply(listOf(true to prompt)) }
        }.getOrElse {
            Log.e(TAG, "gemini caption fail", it)
            null
        }

        parse(jsonText, post)
    }

    private fun parse(raw: String?, post: PostQueueEntity): Captions {
        val cleaned = raw?.replace("```json", "")?.replace("```", "")?.trim()
        val o = runCatching { cleaned?.let { org.json.JSONObject(it) } }.getOrNull()
        val topic = o?.optString("topic") ?: post.title
        return Captions(
            youtubeTitle = o?.optString("youtube_title") ?: post.title,
            youtubeDescription = o?.optString("youtube_description") ?: post.description,
            youtubeHashtags = o?.optString("youtube_hashtags") ?: post.hashtags,
            instagramCaption = o?.optString("instagram_caption") ?: post.description,
            instagramHashtags = o?.optString("instagram_hashtags") ?: post.hashtags,
            facebookCaption = o?.optString("facebook_caption") ?: post.description,
            topic = topic
        )
    }

    /** Captions को DB में सहेजो (हर platform के लिए अलग row). */
    suspend fun save(context: Context, post: PostQueueEntity, c: Captions) {
        val dao = AppDatabase.getDatabase(context).postCaptionDao()
        val now = System.currentTimeMillis()

        fun hash(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
                .joinToString("") { "%02x".format(it) }

        // Duplicate रोकने के लिए हर बार थोड़ा अलग रखो (variation counter)
        val salt = "${post.retryCount}-${post.scheduledAt}"

        dao.upsert(
            PostCaptionEntity(
                postId = post.id,
                platform = SocialPlatform.YOUTUBE,
                caption = c.youtubeDescription,
                title = c.youtubeTitle,
                hashtags = c.youtubeHashtags,
                contentHash = hash(c.youtubeDescription + salt),
                generatedAt = now
            )
        )
        dao.upsert(
            PostCaptionEntity(
                postId = post.id,
                platform = SocialPlatform.INSTAGRAM,
                caption = c.instagramCaption,
                title = null,
                hashtags = c.instagramHashtags,
                contentHash = hash(c.instagramCaption + salt),
                generatedAt = now
            )
        )
        dao.upsert(
            PostCaptionEntity(
                postId = post.id,
                platform = SocialPlatform.FACEBOOK,
                caption = c.facebookCaption,
                title = null,
                hashtags = null,
                contentHash = hash(c.facebookCaption + salt),
                generatedAt = now
            )
        )
    }

    suspend fun get(context: Context, postId: String, platform: String): PostCaptionEntity? =
        AppDatabase.getDatabase(context).postCaptionDao().get(postId, platform)
}

private val Random = kotlin.random.Random.Default
