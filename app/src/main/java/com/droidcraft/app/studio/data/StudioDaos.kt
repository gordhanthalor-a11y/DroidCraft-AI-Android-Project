package com.droidcraft.app.studio.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CampaignDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(c: CampaignEntity)

    @Query("SELECT * FROM campaigns ORDER BY deadline ASC")
    fun observeAll(): Flow<List<CampaignEntity>>

    @Query("UPDATE campaigns SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    @Query("DELETE FROM campaigns WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface JobApplicationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(j: JobApplicationEntity)

    @Query("SELECT * FROM job_applications ORDER BY id DESC")
    fun observeAll(): Flow<List<JobApplicationEntity>>

    @Query("DELETE FROM job_applications WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface HindiNoteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(n: HindiNoteEntity)

    @Query("SELECT * FROM hindi_notes ORDER BY id DESC")
    fun observeAll(): Flow<List<HindiNoteEntity>>

    @Query("DELETE FROM hindi_notes WHERE id = :id")
    suspend fun delete(id: Long)
}
