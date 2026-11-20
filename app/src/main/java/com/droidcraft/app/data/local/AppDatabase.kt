package com.droidcraft.app.data.local

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.droidcraft.app.model.ApprovalStatus
import com.droidcraft.app.model.PipelineStage
import com.droidcraft.app.model.VideoItem
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

@Database(entities = [VideoEntity::class, MemoryEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao
    abstract fun memoryDao(): MemoryDao

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

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "droidcraft_youtube_db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}