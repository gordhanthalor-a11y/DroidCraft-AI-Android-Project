package com.droidcraft.app.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class OrbTheme(val label: String) {
    CYAN("Cyan"),
    AMBER_GOLD("Amber Gold")
}

/** Persisted appearance choice shared by Settings, the Material palette, and all Jarvis lighting. */
object OrbThemeSettings {
    private const val PREFS = "jarvis_appearance"
    private const val KEY_ORB_THEME = "orb_theme"
    private val _selected = MutableStateFlow(OrbTheme.CYAN)
    val selected: StateFlow<OrbTheme> = _selected.asStateFlow()

    fun initialize(context: Context) {
        _selected.value = stored(context)
    }

    fun get(context: Context): OrbTheme = stored(context)

    fun set(context: Context, theme: OrbTheme) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ORB_THEME, theme.name).apply()
        _selected.value = theme
    }

    private fun stored(context: Context): OrbTheme = runCatching {
        OrbTheme.valueOf(
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ORB_THEME, OrbTheme.CYAN.name).orEmpty()
        )
    }.getOrDefault(OrbTheme.CYAN)
}
