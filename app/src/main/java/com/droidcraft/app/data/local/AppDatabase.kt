package com.droidcraft.app.data.local

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.droidcraft.app.model.ApprovalStatus
import com.droidcraft.app.model.PipelineStage
import com.droidcraft.app.model.VideoItem
import com.droidcraft.app.social.data.PostCaptionDao
import com.droidcraft.app.social.data.PostCaptionEntity
import com.droidcraft.app.social.data.PostQueueDao
import com.droidcraft.app.social.data.PostQueueEntity
import com.droidcraft.app.social.data.PublishHistoryDao
import com.droidcraft.app.social.data.PublishHistoryEntity
import com.droidcraft.app.social.data.SocialAccountDao
import com.droidcraft.app.social.data.SocialAccountEntity
import com.droidcraft.app.studio.data.CampaignEntity
import com.droidcraft.app.studio.data.CampaignDao
import com.droidcraft.app.studio.data.HindiNoteDao
import com.droidcraft.app.studio.data.HindiNoteEntity
import com.droidcraft.app.studio.data.JobApplicationDao
import com.droidcraft.app.studio.data.JobApplicationEntity
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "videos_table")
data class VideoEntity(
    @PrimaryKey val id: String,
    val channelId: String,
    val title: String,
    val description: String,
    val duration: String,
    val stage: PipelineStage,
    val approvalStatus: ApprovalStatus,
    val scheduledTime: String?,
    val youtubeVideoId: String?,
    val uploadProgress: Int,
    val sha256: String,
    val retryCount: Int,
    val lastError: String?
)

@Dao
interface VideoDao {
    @Query("SELECT * FROM videos_table ORDER BY id DESC")
    fun getAllVideos(): Flow<List<VideoItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVideo(video: VideoEntity)

    @Query("UPDATE videos_table SET stage = :stage WHERE id = :videoId")
    suspend fun updateStage(videoId: String, stage: PipelineStage)

    @Query("UPDATE videos_table SET approvalStatus = :status WHERE id = :videoId")
    suspend fun updateApproval(videoId: String, status: ApprovalStatus)
}

/** Jarvis ki yaadein — user ke baare mein facts. */
@Entity(tableName = "jarvis_memory")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val value: String,
    val createdAt: Long
)

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: MemoryEntity)

    @Query("SELECT * FROM jarvis_memory ORDER BY id DESC")
    suspend fun getAll(): List<MemoryEntity>

    @Query("SELECT * FROM jarvis_memory WHERE value LIKE '%' || :query || '%' ORDER BY id DESC")
    suspend fun search(query: String): List<MemoryEntity>

    @Query("DELETE FROM jarvis_memory WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM jarvis_memory")
    suspend fun clear()
}

@Database(
    entities = [
        VideoEntity::class,
        MemoryEntity::class,
        SocialAccountEntity::class,
        PostQueueEntity::class,
        PostCaptionEntity::class,
        PublishHistoryEntity::class,
        CampaignEntity::class,
        JobApplicationEntity::class,
        HindiNoteEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao
    abstract fun memoryDao(): MemoryDao
    abstract fun socialAccountDao(): SocialAccountDao
    abstract fun postQueueDao(): PostQueueDao
    abstract fun postCaptionDao(): PostCaptionDao
    abstract fun publishHistoryDao(): PublishHistoryDao
    abstract fun campaignDao(): CampaignDao
    abstract fun jobApplicationDao(): JobApplicationDao
    abstract fun hindiNoteDao(): HindiNoteDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `jarvis_memory` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`key` TEXT NOT NULL, `value` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `social_accounts` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`platform` TEXT NOT NULL, `accountId` TEXT NOT NULL, " +
                        "`accountName` TEXT NOT NULL, `accessToken` TEXT NOT NULL, " +
                        "`refreshToken` TEXT, `expiresAt` INTEGER NOT NULL, " +
                        "`scopes` TEXT NOT NULL, `connectedAt` INTEGER NOT NULL, " +
                        "`isActive` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_social_accounts_platform_accountId` " +
                        "ON `social_accounts` (`platform`, `accountId`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `post_queue` (" +
                        "`id` TEXT NOT NULL, `videoPath` TEXT NOT NULL, " +
                        "`videoSha256` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`description` TEXT NOT NULL, `hashtags` TEXT NOT NULL, " +
                        "`thumbnailPath` TEXT, `durationSec` INTEGER NOT NULL, " +
                        "`isShort` INTEGER NOT NULL, `platform` TEXT NOT NULL, " +
                        "`accountId` TEXT NOT NULL, `scheduledAt` INTEGER NOT NULL, " +
                        "`status` TEXT NOT NULL, `externalId` TEXT, `publishedAt` INTEGER, " +
                        "`retryCount` INTEGER NOT NULL, `lastError` TEXT, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_post_queue_externalId` " +
                        "ON `post_queue` (`externalId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_post_queue_status_scheduledAt` " +
                        "ON `post_queue` (`status`, `scheduledAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_post_queue_platform_status` " +
                        "ON `post_queue` (`platform`, `status`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `post_captions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`postId` TEXT NOT NULL, `platform` TEXT NOT NULL, " +
                        "`caption` TEXT NOT NULL, `title` TEXT, `hashtags` TEXT, " +
                        "`contentHash` TEXT NOT NULL, `generatedAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_post_captions_postId_platform` " +
                        "ON `post_captions` (`postId`, `platform`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `publish_history` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`postId` TEXT NOT NULL, `platform` TEXT NOT NULL, " +
                        "`attemptAt` INTEGER NOT NULL, `success` INTEGER NOT NULL, " +
                        "`externalId` TEXT, `errorCode` TEXT, `errorMessage` TEXT)"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `campaigns` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `ratePerThousand` REAL NOT NULL, " +
                        "`deadline` TEXT NOT NULL, `clipsSubmitted` INTEGER NOT NULL, " +
                        "`status` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `job_applications` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`company` TEXT NOT NULL, `role` TEXT NOT NULL, " +
                        "`appliedOn` TEXT NOT NULL, `status` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `hindi_notes` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`text` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "droidcraft_youtube_db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}