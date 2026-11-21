package com.droidcraft.app.jarvis.overlay

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** In-process navigation event plus package-scoped state broadcasts for the floating bubble. */
object JarvisBubbleController {
    const val ACTION_STATE_CHANGED = "com.droidcraft.app.action.JARVIS_BUBBLE_STATE"
    const val ACTION_OPEN_HOME = "com.droidcraft.app.action.OPEN_JARVIS_HOME"
    const val EXTRA_STATE = "state"
    private const val PREFS = "jarvis_overlay"
    private const val KEY_STATE = "conversation_state"

    private val _openHomeRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openHomeRequests = _openHomeRequests.asSharedFlow()

    fun state(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_STATE, "IDLE") ?: "IDLE"

    fun publishState(context: Context, state: String) {
        val normalized = state.uppercase().takeIf { it in VALID_STATES } ?: "IDLE"
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_STATE, normalized).apply()
        context.sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(context.packageName)
                .putExtra(EXTRA_STATE, normalized)
        )
    }

    fun requestOpenHome() {
        _openHomeRequests.tryEmit(Unit)
    }

    private val VALID_STATES = setOf("IDLE", "LISTENING", "WORKING", "NEEDS_ATTENTION", "THINKING", "CONNECTING", "ERROR", "SPEAKING")
}
