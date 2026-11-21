package com.droidcraft.app.social.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SocialAccountDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: SocialAccountEntity)

    @Query("SELECT * FROM social_accounts WHERE platform = :platform AND isActive = 1 LIMIT 1")
    suspend fun getActive(platform: String): SocialAccountEntity?

    @Query("SELECT * FROM social_accounts WHERE platform = :platform AND isActive = 1 LIMIT 1")
    fun observeActive(platform: String): Flow<SocialAccountEntity?>

    @Query("SELECT * FROM social_accounts ORDER BY connectedAt DESC")
    fun observeAll(): Flow<List<SocialAccountEntity>>

    @Query("UPDATE social_accounts SET accessToken = :token, refreshToken = :refresh, expiresAt = :expires WHERE id = :id")
    suspend fun updateTokens(id: Long, token: String, refresh: String?, expires: Long)

    @Query("DELETE FROM social_accounts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM social_accounts WHERE isActive = 1")
    suspend fun countActive(): Int
}

@Dao
interface PostQueueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(post: PostQueueEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(posts: List<PostQueueEntity>): List<Long>

    @Query("SELECT * FROM post_queue ORDER BY scheduledAt ASC")
    fun observeAll(): Flow<List<PostQueueEntity>>

    @Query("SELECT * FROM post_queue WHERE status = 'QUEUED' ORDER BY scheduledAt ASC")
    fun observeQueued(): Flow<List<PostQueueEntity>>

    @Query("SELECT * FROM post_queue WHERE status = :status")
    suspend fun getByStatus(status: String): List<PostQueueEntity>

    @Query("SELECT * FROM post_queue WHERE id = :id")
    suspend fun getById(id: String): PostQueueEntity?

    @Query("SELECT * FROM post_queue WHERE videoSha256 = :sha AND platform = :platform LIMIT 1")
    suspend fun findBySha(sha: String, platform: String): PostQueueEntity?

    @Query("UPDATE post_queue SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: String)

    @Query("UPDATE post_queue SET status = :status, lastError = :error, retryCount = retryCount + 1 WHERE id = :id")
    suspend fun markFailed(id: String, status: String, error: String?)

    @Query("UPDATE post_queue SET status = 'PUBLISHED', externalId = :externalId, publishedAt = :at WHERE id = :id")
    suspend fun markPublished(id: String, externalId: String, at: Long)

    @Query("UPDATE post_queue SET status = 'UPLOADING' WHERE id = :id")
    suspend fun markUploading(id: String)

    @Query("UPDATE post_queue SET scheduledAt = :at WHERE id = :id")
    suspend fun reschedule(id: String, at: Long)

    @Query("DELETE FROM post_queue WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM post_queue WHERE status = 'QUEUED'")
    fun observeQueuedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM post_queue WHERE status = 'PUBLISHED'")
    fun observePublishedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM post_queue WHERE status = 'FAILED'")
    fun observeFailedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM post_queue")
    fun observeTotalCount(): Flow<Int>

    @Query("SELECT * FROM post_queue WHERE scheduledAt <= :now AND status = 'QUEUED'")
    suspend fun duePosts(now: Long): List<PostQueueEntity>
}

@Dao
interface PostCaptionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(caption: PostCaptionEntity)

    @Query("SELECT * FROM post_captions WHERE postId = :postId AND platform = :platform LIMIT 1")
    suspend fun get(postId: String, platform: String): PostCaptionEntity?

    @Query("SELECT * FROM post_captions WHERE postId = :postId")
    suspend fun forPost(postId: String): List<PostCaptionEntity>

    @Query("SELECT COUNT(*) FROM post_captions WHERE platform = :platform AND contentHash = :hash")
    suspend fun countByHash(platform: String, hash: String): Int
}

@Dao
interface PublishHistoryDao {
    @Insert
    suspend fun insert(entry: PublishHistoryEntity)

    @Query("SELECT * FROM publish_history ORDER BY attemptAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<PublishHistoryEntity>>

    @Query("SELECT COUNT(*) FROM publish_history WHERE postId = :postId AND success = 1")
    suspend fun successCount(postId: String): Int
}
