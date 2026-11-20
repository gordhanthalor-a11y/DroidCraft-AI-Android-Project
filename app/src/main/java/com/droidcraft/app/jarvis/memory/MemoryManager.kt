package com.droidcraft.app.jarvis.memory

import android.content.Context
import com.droidcraft.app.data.local.AppDatabase
import com.droidcraft.app.data.local.MemoryEntity

/**
 * Jarvis ki yaadein — "remember that mere bike ka number 1234 hai" jaise facts.
 * Room (jarvis_memory table) mein savaat hai.
 */
class MemoryManager(context: Context) {

    private val dao = AppDatabase.getDatabase(context).memoryDao()

    suspend fun remember(text: String) {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.isBlank()) return
        val key = clean.lowercase().split(" ").take(6).joinToString(" ")
        dao.insert(
            MemoryEntity(
                key = key,
                value = clean,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun facts(): List<MemoryEntity> = dao.getAll()

    suspend fun search(query: String): List<MemoryEntity> =
        if (query.isBlank()) dao.getAll() else dao.search(query.trim())

    suspend fun forget(id: Long) = dao.delete(id)

    suspend fun clear() = dao.clear()
}
