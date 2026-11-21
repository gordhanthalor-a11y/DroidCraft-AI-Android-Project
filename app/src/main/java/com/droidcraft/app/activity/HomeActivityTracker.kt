package com.droidcraft.app.activity

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-process activity timeline for Jarvis conversation and speech sessions. */
enum class HomeActivityStatus {
    RUNNING,
    WAITING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class HomeActivityRecord(
    val id: String,
    val kind: String,
    val title: String,
    val status: HomeActivityStatus,
    val step: String,
    val progress: Int?,
    val steps: List<String>,
    val errorReason: String? = null,
    val note: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

object HomeActivityTracker {
    private const val MAX_RECORDS = 24
    private val lock = Any()
    private val _records = MutableStateFlow<List<HomeActivityRecord>>(emptyList())
    val records: StateFlow<List<HomeActivityRecord>> = _records.asStateFlow()

    fun begin(
        id: String,
        kind: String,
        title: String,
        step: String,
        progress: Int? = null
    ) {
        val record = HomeActivityRecord(
            id = id,
            kind = kind,
            title = title,
            status = HomeActivityStatus.RUNNING,
            step = step,
            progress = progress?.coerceIn(0, 100),
            steps = listOf(step)
        )
        synchronized(lock) {
            _records.value = (_records.value.filterNot { it.id == id } + record)
                .sortedByDescending { it.updatedAt }
                .take(MAX_RECORDS)
        }
    }

    fun update(
        id: String,
        step: String,
        progress: Int? = null,
        status: HomeActivityStatus = HomeActivityStatus.RUNNING,
        note: String? = null
    ) {
        mutate(id) { current ->
            current.copy(
                status = status,
                step = step,
                progress = progress?.coerceIn(0, 100),
                steps = appendStep(current.steps, step),
                errorReason = null,
                note = note ?: current.note,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    fun complete(id: String, step: String = "Completed", note: String? = null) {
        mutate(id) { current ->
            current.copy(
                status = HomeActivityStatus.COMPLETED,
                step = step,
                progress = 100,
                steps = appendStep(current.steps, step),
                errorReason = null,
                note = note ?: current.note,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    fun fail(id: String, reason: String) {
        val detail = reason.trim().ifBlank { "The task failed without a server error message." }.take(500)
        mutate(id) { current ->
            current.copy(
                status = HomeActivityStatus.FAILED,
                step = "Failed",
                steps = appendStep(current.steps, "Failed"),
                errorReason = detail,
                note = null,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    fun cancel(id: String, reason: String = "Stopped before completion") {
        mutate(id) { current ->
            current.copy(
                status = HomeActivityStatus.CANCELLED,
                step = "Cancelled",
                steps = appendStep(current.steps, "Cancelled"),
                errorReason = null,
                note = reason.trim().take(500),
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    private fun mutate(id: String, transform: (HomeActivityRecord) -> HomeActivityRecord) {
        synchronized(lock) {
            val current = _records.value.firstOrNull { it.id == id } ?: return
            _records.value = _records.value
                .map { if (it.id == id) transform(current) else it }
                .sortedByDescending { it.updatedAt }
                .take(MAX_RECORDS)
        }
    }

    private fun appendStep(existing: List<String>, next: String): List<String> =
        if (existing.lastOrNull() == next) existing else (existing + next).takeLast(6)
}
