package com.droidcraft.app.data.local

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Jarvis user memories stored on device. */
@Entity(tableName = "jarvis_memory")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val value: String,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "'Personal'") val category: String = "Personal"
)

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: MemoryEntity)

    @Query("SELECT * FROM jarvis_memory ORDER BY id DESC")
    suspend fun getAll(): List<MemoryEntity>

    @Query("SELECT * FROM jarvis_memory WHERE value LIKE '%' || :query || '%' ORDER BY id DESC")
    suspend fun search(query: String): List<MemoryEntity>

    @Query("UPDATE jarvis_memory SET `key` = :key, `value` = :value, category = :category WHERE id = :id")
    suspend fun update(id: Long, key: String, value: String, category: String)

    @Query("DELETE FROM jarvis_memory WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM jarvis_memory")
    suspend fun clear()
}

@Database(entities = [MemoryEntity::class], version = 7, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
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

        // Historical versions are intentionally no-op: Jarvis memory remains available while
        // the 6→7 migration below removes tables from retired app functionality.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `jarvis_memory` ADD COLUMN `category` TEXT NOT NULL DEFAULT 'Personal'")
                db.execSQL(
                    "UPDATE `jarvis_memory` SET `category` = 'Preferences' WHERE " +
                        "lower(`value`) LIKE '%prefer%' OR lower(`value`) LIKE '%favorite%' OR " +
                        "lower(`value`) LIKE '%favourite%' OR lower(`value`) LIKE '%pasand%'"
                )
                db.execSQL(
                    "UPDATE `jarvis_memory` SET `category` = 'Contacts' WHERE " +
                        "lower(`value`) LIKE '%phone%' OR lower(`value`) LIKE '%contact%' OR " +
                        "lower(`value`) LIKE '%wife%' OR lower(`value`) LIKE '%husband%' OR " +
                        "lower(`value`) LIKE '%mother%' OR lower(`value`) LIKE '%father%' OR " +
                        "lower(`value`) LIKE '%email%'"
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf(
                    "social_accounts", "post_queue", "post_captions", "publish_history",
                    "campaigns", "job_applications", "hindi_notes"
                ).forEach { table -> db.execSQL("DROP TABLE IF EXISTS `$table`") }
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "droidcraft_youtube_db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7
                    )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
