package com.droidcraft.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFEF4444),
    secondary = Color(0xFF3B82F6),
    background = Color(0xFF020617),
    surface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFF1E293B)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFDC2626),
    secondary = Color(0xFF2563EB)
)

@Composable
fun DroidCraftTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
