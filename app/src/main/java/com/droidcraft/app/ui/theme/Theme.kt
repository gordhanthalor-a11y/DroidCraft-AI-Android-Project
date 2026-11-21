package com.droidcraft.app.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

private fun darkColors(orbTheme: OrbTheme) = when (orbTheme) {
    OrbTheme.CYAN -> darkColorScheme(
        primary = Color(0xFF56EFF7), onPrimary = Color(0xFF001418),
        primaryContainer = Color(0xFF07343B), onPrimaryContainer = Color(0xFFC9FCFF),
        secondary = Color(0xFFB6FBFF), onSecondary = Color(0xFF002018),
        secondaryContainer = Color(0xFF0B352E), onSecondaryContainer = Color(0xFFC6FFF0),
        tertiary = Color(0xFF48E1C2), onTertiary = Color(0xFF002018),
        background = Color(0xFF050B15), onBackground = Color(0xFFE7F5F8),
        surface = Color(0xFF081421), onSurface = Color(0xFFE7F5F8),
        surfaceVariant = Color(0xFF0E2030), onSurfaceVariant = Color(0xFFA3BAC6),
        outline = Color(0xFF294654), error = Color(0xFFFF7183),
        errorContainer = Color(0xFF3A111B), onErrorContainer = Color(0xFFFFD9DF)
    )
    OrbTheme.AMBER_GOLD -> darkColorScheme(
        primary = Color(0xFFFFC247), onPrimary = Color(0xFF291800),
        primaryContainer = Color(0xFF493006), onPrimaryContainer = Color(0xFFFFE8B1),
        secondary = Color(0xFFFFE0A0), onSecondary = Color(0xFF291800),
        secondaryContainer = Color(0xFF3D2B10), onSecondaryContainer = Color(0xFFFFEAC4),
        tertiary = Color(0xFFFF9957), onTertiary = Color(0xFF351A02),
        background = Color(0xFF100B05), onBackground = Color(0xFFFFF4DE),
        surface = Color(0xFF1A1208), onSurface = Color(0xFFFFF4DE),
        surfaceVariant = Color(0xFF302312), onSurfaceVariant = Color(0xFFD4C09A),
        outline = Color(0xFF67502B), error = Color(0xFFFF7183),
        errorContainer = Color(0xFF3A111B), onErrorContainer = Color(0xFFFFD9DF)
    )
}

private fun lightColors(orbTheme: OrbTheme) = when (orbTheme) {
    OrbTheme.CYAN -> lightColorScheme(
        primary = Color(0xFF087E8B), secondary = Color(0xFF087A68), tertiary = Color(0xFF006B76)
    )
    OrbTheme.AMBER_GOLD -> lightColorScheme(
        primary = Color(0xFF8A5B00), secondary = Color(0xFF9A4B10), tertiary = Color(0xFF735000)
    )
}

@Composable
fun DroidCraftTheme(
    darkTheme: Boolean = true,
    orbTheme: OrbTheme = OrbTheme.CYAN,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) darkColors(orbTheme) else lightColors(orbTheme),
        content = content
    )
}

/** Fine, static circuit traces shared by Compose screens; the live HUD uses its own SVG layer. */
@Composable
fun CircuitBackdrop(modifier: Modifier = Modifier) {
    val traceColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val lineWidth = 1.dp.toPx()
        val traces = listOf(
            floatArrayOf(-0.04f, 0.18f, 0.13f, 0.18f, 0.13f, 0.31f, 0.24f, 0.31f, 0.24f, 0.43f),
            floatArrayOf(1.04f, 0.24f, 0.86f, 0.24f, 0.86f, 0.36f, 0.75f, 0.36f, 0.75f, 0.50f),
            floatArrayOf(-0.04f, 0.68f, 0.12f, 0.68f, 0.12f, 0.57f, 0.24f, 0.57f, 0.24f, 0.49f),
            floatArrayOf(1.04f, 0.78f, 0.89f, 0.78f, 0.89f, 0.65f, 0.75f, 0.65f, 0.75f, 0.54f),
            floatArrayOf(0.28f, -0.03f, 0.28f, 0.08f, 0.41f, 0.08f, 0.41f, 0.16f),
            floatArrayOf(0.72f, 1.03f, 0.72f, 0.92f, 0.59f, 0.92f, 0.59f, 0.84f)
        )
        traces.forEach { points ->
            val path = Path()
            path.moveTo(points[0] * width, points[1] * height)
            var index = 2
            while (index < points.size) {
                path.lineTo(points[index] * width, points[index + 1] * height)
                index += 2
            }
            drawPath(path, traceColor.copy(alpha = 0.045f), style = Stroke(width = lineWidth))
            val end = Offset(points[points.size - 2] * width, points[points.size - 1] * height)
            drawCircle(traceColor.copy(alpha = 0.09f), radius = 2.dp.toPx(), center = end)
        }
    }
}
