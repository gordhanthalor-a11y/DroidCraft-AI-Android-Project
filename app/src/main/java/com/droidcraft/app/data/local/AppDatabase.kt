package com.droidcraft.app.data.local

import android.content.Context
import androidx.room.*
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

@Database(entities = [VideoEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "droidcraft_youtube_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}