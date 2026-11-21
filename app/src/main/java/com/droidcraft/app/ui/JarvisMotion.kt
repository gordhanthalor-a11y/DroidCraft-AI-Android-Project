package com.droidcraft.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin

/** Soft four-edge aura and moving light traces, driven by the same conversation energy as the orb. */
@Composable
fun EnergyBackdrop(modifier: Modifier, energy: Float, phase: Float) {
    val primary = MaterialTheme.colorScheme.primary
    val accent = MaterialTheme.colorScheme.tertiary
    Canvas(modifier) {
        val intensity = (0.035f + energy.coerceIn(0f, 1f) * 0.22f + sin(phase * 6.283f) * 0.018f)
            .coerceIn(0.015f, 0.30f)
        val edgeDepth = size.minDimension * 0.16f
        drawRect(
            Brush.verticalGradient(
                listOf(primary.copy(alpha = intensity), Color.Transparent),
                startY = 0f,
                endY = edgeDepth
            ),
            size = androidx.compose.ui.geometry.Size(size.width, edgeDepth)
        )
        drawRect(
            Brush.verticalGradient(
                listOf(Color.Transparent, accent.copy(alpha = intensity * 0.55f)),
                startY = size.height - edgeDepth,
                endY = size.height
            ),
            topLeft = Offset(0f, size.height - edgeDepth),
            size = androidx.compose.ui.geometry.Size(size.width, edgeDepth)
        )
        drawRect(
            Brush.horizontalGradient(
                listOf(primary.copy(alpha = intensity * 0.72f), Color.Transparent),
                startX = 0f,
                endX = edgeDepth
            ),
            size = androidx.compose.ui.geometry.Size(edgeDepth, size.height)
        )
        drawRect(
            Brush.horizontalGradient(
                listOf(Color.Transparent, accent.copy(alpha = intensity * 0.42f)),
                startX = size.width - edgeDepth,
                endX = size.width
            ),
            topLeft = Offset(size.width - edgeDepth, 0f),
            size = androidx.compose.ui.geometry.Size(edgeDepth, size.height)
        )

        val stroke = 1.1.dp.toPx()
        val sweep = (phase * size.width * 2f) % size.width
        val lineBrush = Brush.horizontalGradient(
            listOf(Color.Transparent, primary.copy(alpha = intensity), accent.copy(alpha = intensity * 0.8f), Color.Transparent),
            startX = sweep - size.width * 0.25f,
            endX = sweep + size.width * 0.25f
        )
        drawLine(lineBrush, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), strokeWidth = stroke)
        drawLine(lineBrush, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), strokeWidth = stroke)
        val sideBrush = Brush.verticalGradient(
            listOf(Color.Transparent, primary.copy(alpha = intensity), accent.copy(alpha = intensity * 0.7f), Color.Transparent),
            startY = (phase * size.height * 2f) % size.height - size.height * 0.25f,
            endY = (phase * size.height * 2f) % size.height + size.height * 0.25f
        )
        drawLine(sideBrush, Offset(stroke / 2, 0f), Offset(stroke / 2, size.height), strokeWidth = stroke)
        drawLine(sideBrush, Offset(size.width - stroke / 2, 0f), Offset(size.width - stroke / 2, size.height), strokeWidth = stroke)
    }
}

@Composable
fun EnergyCard(
    modifier: Modifier = Modifier,
    energy: Float,
    phase: Float,
    content: @Composable () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val accent = MaterialTheme.colorScheme.tertiary
    val shape = RoundedCornerShape(18.dp)
    Surface(
        modifier = modifier.drawWithContent {
            drawContent()
            val alpha = (0.16f + energy.coerceIn(0f, 1f) * 0.24f + sin(phase * 6.283f) * 0.035f)
                .coerceIn(0.10f, 0.48f)
            drawRoundRect(
                brush = Brush.linearGradient(
                    listOf(primary.copy(alpha = alpha), accent.copy(alpha = alpha * 0.45f), accent.copy(alpha = alpha * 0.65f)),
                    start = Offset(phase * size.width, 0f),
                    end = Offset((phase + 1f) * size.width, size.height)
                ),
                cornerRadius = CornerRadius(18.dp.toPx()),
                style = Stroke(width = 1.dp.toPx())
            )
        },
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        content = { content() }
    )
}

@Composable
fun EnergyActionButton(
    label: String,
    enabled: Boolean,
    energy: Float,
    phase: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val accent = MaterialTheme.colorScheme.tertiary
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = modifier
            .drawWithContent {
                drawContent()
                drawRoundRect(
                    brush = Brush.linearGradient(
                        listOf(
                            primary.copy(alpha = if (enabled) 0.45f + energy * 0.38f else 0.12f),
                            accent.copy(alpha = if (enabled) 0.35f else 0.10f),
                            primary.copy(alpha = if (enabled) 0.2f + energy * 0.35f else 0.10f)
                        ),
                        start = Offset(phase * size.width, 0f),
                        end = Offset((phase + 1f) * size.width, size.height)
                    ),
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(width = (1.2f + energy * 0.5f).dp.toPx())
                )
            }
            .clickable(enabled = enabled, onClick = onClick),
        shape = shape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.primary
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 17.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        }
    }
}
