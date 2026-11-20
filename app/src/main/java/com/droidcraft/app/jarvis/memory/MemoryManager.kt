package com.droidcraft.app.jarvis.memory

import android.content.Context
import com.droidcraft.app.data.local.AppDatabase
import com.droidcraft.app.data.local.MemoryEntity

/** Personal facts saved by Jarvis; the categorized list is also used by the Memories screen. */
class MemoryManager(context: Context) {

    private val dao = AppDatabase.getDatabase(context).memoryDao()

    suspend fun remember(text: String) {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.isBlank()) return
        save(
            id = null,
            key = titleFor(clean),
            value = clean,
            category = classify(clean)
        )
    }

    suspend fun save(id: Long?, key: String, value: String, category: String) {
        val cleanValue = value.trim().replace(Regex("\\s+"), " ")
        if (cleanValue.isBlank()) return
        val cleanKey = key.trim().ifBlank { titleFor(cleanValue) }.take(100)
        val safeCategory = category.takeIf { it in CATEGORIES } ?: classify(cleanValue)
        if (id == null) {
            dao.insert(
                MemoryEntity(
                    key = cleanKey,
                    value = cleanValue,
                    createdAt = System.currentTimeMillis(),
                    category = safeCategory
                )
            )
        } else {
            dao.update(id, cleanKey, cleanValue, safeCategory)
        }
    }

    suspend fun facts(): List<MemoryEntity> = dao.getAll()

    suspend fun search(query: String): List<MemoryEntity> =
        if (query.isBlank()) dao.getAll() else dao.search(query.trim())

    suspend fun forget(id: Long) = dao.delete(id)

    suspend fun clear() = dao.clear()

    private fun titleFor(text: String): String =
        text.lowercase().split(Regex("\\s+")).take(6).joinToString(" ").take(100)

    private fun classify(text: String): String {
        val normalized = text.lowercase()
        return when {
            listOf("phone", "mobile", "contact", "wife", "husband", "mother", "father", "email", "number")
                .any { term -> normalized.contains(term) } -> "Contacts"
            listOf("prefer", "favorite", "favourite", "like", "dislike", "pasand", "choice")
                .any { term -> normalized.contains(term) } -> "Preferences"
            else -> "Personal"
        }
    }

    private companion object {
        val CATEGORIES = setOf("Personal", "Preferences", "Contacts")
    }
}
