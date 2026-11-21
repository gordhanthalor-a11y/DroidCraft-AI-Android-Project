package com.droidcraft.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Durable on-device audit of explicit user approval/rejection for confirmation-gated taps. */
data class ActionDecisionRecord(
    val id: String,
    val actionLabel: String,
    val packageName: String,
    val decision: String,
    val decidedAt: Long
)

object ActionDecisionStore {
    private const val PREFS = "jarvis_action_decisions"
    private const val KEY_RECORDS = "records_v1"
    private const val LIMIT = 50
    private val _records = MutableStateFlow<List<ActionDecisionRecord>>(emptyList())
    val records: StateFlow<List<ActionDecisionRecord>> = _records.asStateFlow()

    fun initialize(context: Context) {
        _records.value = read(context)
    }

    fun record(context: Context, actionLabel: String, packageName: String, approved: Boolean) {
        val row = ActionDecisionRecord(
            id = "decision-${System.currentTimeMillis()}-${actionLabel.hashCode()}",
            actionLabel = actionLabel.take(180),
            packageName = packageName.take(180),
            decision = if (approved) "APPROVED" else "REJECTED",
            decidedAt = System.currentTimeMillis()
        )
        val updated = (listOf(row) + read(context).filterNot { it.id == row.id }).take(LIMIT)
        val json = JSONArray().apply {
            updated.forEach { item ->
                put(JSONObject()
                    .put("id", item.id)
                    .put("action", item.actionLabel)
                    .put("package", item.packageName)
                    .put("decision", item.decision)
                    .put("at", item.decidedAt))
            }
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_RECORDS, json.toString()).apply()
        _records.value = updated
    }

    private fun read(context: Context): List<ActionDecisionRecord> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RECORDS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ActionDecisionRecord(
                id = id,
                actionLabel = item.optString("action"),
                packageName = item.optString("package"),
                decision = item.optString("decision"),
                decidedAt = item.optLong("at")
            )
        }.sortedByDescending { it.decidedAt }.take(LIMIT)
    }
}
