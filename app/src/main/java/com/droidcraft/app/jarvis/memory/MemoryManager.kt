package com.droidcraft.app.jarvis.memory

import android.content.Context
import com.droidcraft.app.data.local.AppDatabase
import com.droidcraft.app.data.local.MemoryEntity

/** Personal facts saved by Jarvis; the categorized list is also used by the Memories screen. */
class MemoryManager(context: Context) {

    private val dao = AppDatabase.getDatabase(context).memoryDao()

    data class DurableFact(val key: String, val value: String, val category: String)

    /** Conservative on-device extraction; only explicit memory requests and stable self-facts qualify. */
    fun extractDurableFact(userText: String): DurableFact? {
        val clean = userText.trim().replace(Regex("\\s+"), " ").take(500)
        if (clean.isBlank()) return null
        val normalized = clean.lowercase()
        if (listOf("password", "passcode", "otp", "one time code", "credit card", "debit card", "bank account", "cvv")
                .any { normalized.contains(it) }) return null

        fun capture(pattern: Regex, key: String, category: String): DurableFact? =
            pattern.find(clean)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
                ?.let { DurableFact(key, it, category) }

        return capture(Regex("(?i)^(?:remember that|please remember|yaad rakhna|yaad rakh)\\s+(.+)$"), "Remembered fact", "Personal")
            ?: capture(Regex("(?i)^(?:my name is|mera naam)\\s+([\\p{L}][\\p{L} .'-]{1,48})$"), "Name", "Personal")
            ?: capture(Regex("(?i)^(?:i prefer|i like|i love|i dislike|i avoid|my favorite (?:thing|food|color|colour) is|my favourite (?:thing|food|color|colour) is)\\s+(.+)$"), "Preference", "Preferences")
            ?: capture(Regex("(?i)^(?:i live in|i work at|i work as|my role is|i am a|i'm a)\\s+(.+)$"), "Personal detail", "Personal")
            ?: capture(Regex("(?i)^(?:mujhe)\\s+(.+?)\\s+(?:pasand hai|pasand nahin hai|pasand nahi hai)$"), "Preference", "Preferences")
    }

    suspend fun remember(text: String) {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.isBlank()) return
        if (dao.getAll().any { it.value.equals(clean, ignoreCase = true) }) return
        save(
            id = null,
            key = titleFor(clean),
            value = clean,
            category = classify(clean)
        )
    }

    suspend fun remember(fact: DurableFact) {
        if (dao.getAll().any { it.value.equals(fact.value, ignoreCase = true) }) return
        save(id = null, key = fact.key, value = fact.value, category = fact.category)
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
