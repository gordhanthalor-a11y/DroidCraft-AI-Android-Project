package com.droidcraft.app.social.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Connected social account (YouTube / Instagram / Facebook). */
@Entity(
    tableName = "social_accounts",
    indices = [Index(value = ["platform", "accountId"], unique = true)]
)
data class SocialAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val platform: String,          // "youtube" | "instagram" | "facebook"
    val accountId: String,
    val accountName: String,
    val accessToken: String,       // encrypted at rest
    val refreshToken: String?,     // encrypted at rest
    val expiresAt: Long,
    val scopes: String,
    val connectedAt: Long,
    val isActive: Boolean = true
)

/**
 * Post queue — ek video, ek platform. Bulk schedule में 50-100 entries बनती हैं.
 * external_id unique है → एक ही video एक platform पर दो बार publish नहीं होगी.
 */
@Entity(
    tableName = "post_queue",
    indices = [
        Index(value = ["externalId"], unique = true),
        Index(value = ["status", "scheduledAt"]),
        Index(value = ["platform", "status"])
    ]
)
data class PostQueueEntity(
    @PrimaryKey val id: String,
    val videoPath: String,
    val videoSha256: String,
    val title: String,
    val description: String,
    val hashtags: String,
    val thumbnailPath: String?,
    val durationSec: Int,
    val isShort: Boolean,
    val platform: String,
    val accountId: String,
    val scheduledAt: Long,
    val status: String = "QUEUED",   // QUEUED | UPLOADING | PUBLISHED | FAILED | CANCELLED
    val externalId: String? = null,
    val publishedAt: Long? = null,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/** Har platform के लिए अलग AI caption — contentHash से दोहराव रुकता है. */
@Entity(
    tableName = "post_captions",
    indices = [Index(value = ["postId", "platform"], unique = true)]
)
data class PostCaptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val postId: String,
    val platform: String,
    val caption: String,
    val title: String?,
    val hashtags: String?,
    val contentHash: String,
    val generatedAt: Long
)

/** Publish history — audit, dashboard, retry tracking. */
@Entity(tableName = "publish_history")
data class PublishHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val postId: String,
    val platform: String,
    val attemptAt: Long,
    val success: Boolean,
    val externalId: String?,
    val errorCode: String?,
    val errorMessage: String?
)

/** Caption generation status per platform. */
object SocialStatus {
    const val QUEUED = "QUEUED"
    const val UPLOADING = "UPLOADING"
    const val PUBLISHED = "PUBLISHED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
}

object SocialPlatform {
    const val YOUTUBE = "youtube"
    const val INSTAGRAM = "instagram"
    const val FACEBOOK = "facebook"
}
