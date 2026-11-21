package com.droidcraft.app.ui

import android.graphics.Paint as AndroidPaint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Glassy plasma star and three gyroscope rings; AGSL on API 33+, animated Canvas fallback below. */
@Composable
fun JarvisOrb(
    state: String,
    energy: Float,
    audioLevel: Float,
    phase: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shader = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { RuntimeShader(PLASMA_STAR_SHADER) }.getOrNull()
        } else null
    }
    val orbPrimary = MaterialTheme.colorScheme.primary
    val orbLight = MaterialTheme.colorScheme.secondary
    val orbAccent = MaterialTheme.colorScheme.tertiary
    val orbDeep = MaterialTheme.colorScheme.primaryContainer
    val normalizedEnergy = energy.coerceIn(0f, 1f)
    val normalizedLevel = audioLevel.coerceIn(0f, 1f)
    val stateLabel = when (state) {
        "WORKING", "CONNECTING", "THINKING" -> "WORKING"
        "LISTENING" -> "LISTENING · TAP TO STOP"
        "SPEAKING" -> "SPEAKING · TAP TO STOP"
        "NEEDS_ATTENTION", "ERROR" -> "NEEDS ATTENTION · TAP TO RETRY"
        else -> "IDLE · TAP TO SPEAK"
    }
    Box(
        modifier = modifier
            .size(300.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = "Jarvis voice orb. $stateLabel"
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val unit = size.minDimension
            val ringBase = unit * 0.235f
            val coreRadius = unit * (0.295f + normalizedEnergy * 0.018f + sin(phase * 6f) * 0.004f)
            val coronaAlpha = (0.10f + normalizedEnergy * 0.18f + normalizedLevel * 0.32f).coerceIn(0.08f, 0.62f)

            // Near-black glass and voice-level corona.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        orbPrimary.copy(alpha = 0.045f + coronaAlpha * 0.12f),
                        orbDeep.copy(alpha = 0.12f + coronaAlpha * 0.22f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = unit * (0.48f + normalizedLevel * 0.045f)
                ),
                radius = unit * 0.49f,
                center = center
            )
            drawCircle(
                color = orbPrimary.copy(alpha = coronaAlpha * 0.34f),
                radius = unit * (0.405f + normalizedLevel * 0.035f),
                center = center,
                style = Stroke(width = unit * (0.018f + normalizedLevel * 0.014f))
            )
            drawCircle(
                color = orbLight.copy(alpha = 0.12f + coronaAlpha * 0.18f),
                radius = unit * 0.355f,
                center = center,
                style = Stroke(width = unit * 0.006f)
            )

            if (shader != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching {
                    shader.setFloatUniform("iResolution", size.width, size.height)
                    shader.setFloatUniform("iTime", phase * 12f)
                    shader.setFloatUniform("iEnergy", normalizedEnergy)
                    shader.setFloatUniform("iState", stateCode(state))
                    shader.setFloatUniform("iDeep", orbDeep.red, orbDeep.green, orbDeep.blue)
                    shader.setFloatUniform("iPrimary", orbPrimary.red, orbPrimary.green, orbPrimary.blue)
                    shader.setFloatUniform("iLight", orbLight.red, orbLight.green, orbLight.blue)
                    shader.setFloatUniform("iAccent", orbAccent.red, orbAccent.green, orbAccent.blue)
                    val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                        this.shader = shader
                        alpha = 255
                    }
                    drawContext.canvas.nativeCanvas.drawCircle(center.x, center.y, coreRadius, paint)
                }.onFailure {
                    drawFallbackPlasma(center, coreRadius, phase, normalizedEnergy, orbPrimary, orbLight, orbAccent, orbDeep)
                }
            } else {
                drawFallbackPlasma(center, coreRadius, phase, normalizedEnergy, orbPrimary, orbLight, orbAccent, orbDeep)
            }

            // Three independent tilted gyroscope rings use the selected Orb theme accent.
            val ringRadii = listOf(ringBase * 1.23f, ringBase * 1.48f, ringBase * 1.72f)
            val ringYScales = listOf(0.34f, 0.28f, 0.38f)
            val rotations = listOf(phase * 74f, -phase * 58f + 62f, phase * 42f - 54f)
            ringRadii.forEachIndexed { index, orbitRadius ->
                val ringAlpha = (0.48f + normalizedEnergy * 0.34f + 0.12f * sin(phase * 6f + index)).coerceIn(0.3f, 0.96f)
                rotate(rotations[index], center) {
                    scale(scaleX = 1f, scaleY = ringYScales[index], pivot = center) {
                        drawCircle(
                            color = orbAccent.copy(alpha = ringAlpha),
                            radius = orbitRadius,
                            center = center,
                            style = Stroke(width = if (index == 1) unit * 0.009f else unit * 0.006f)
                        )
                        drawCircle(
                            color = orbAccent.copy(alpha = ringAlpha * 0.18f),
                            radius = orbitRadius,
                            center = center,
                            style = Stroke(width = unit * 0.022f)
                        )
                    }
                }
                drawComet(center, orbitRadius, ringYScales[index], rotations[index], phase, index, ringAlpha, orbAccent, orbLight)
            }

            // Transparent glass shell, edge highlight, and a restrained specular reflection.
            drawCircle(
                color = Color.White.copy(alpha = 0.13f),
                radius = unit * 0.46f,
                center = center,
                style = Stroke(width = unit * 0.004f)
            )
            drawCircle(
                brush = Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.22f), Color.Transparent, orbPrimary.copy(alpha = 0.12f)),
                    start = Offset(center.x - unit * 0.28f, center.y - unit * 0.32f),
                    end = Offset(center.x + unit * 0.24f, center.y + unit * 0.26f)
                ),
                radius = unit * 0.445f,
                center = center,
                style = Stroke(width = unit * 0.007f)
            )

            // Voice bars ride the lower half of the plasma core and follow real RMS audio energy.
            val barCount = 13
            val barGap = unit * 0.012f
            val barWidth = unit * 0.013f
            val totalWidth = barCount * barWidth + (barCount - 1) * barGap
            val baseline = center.y + unit * 0.19f
            for (bar in 0 until barCount) {
                val x = center.x - totalWidth / 2f + bar * (barWidth + barGap)
                val wave = (0.22f + 0.78f * (0.5f + 0.5f * sin(phase * 8f + bar * 0.72f))).coerceIn(0f, 1f)
                val height = unit * (0.018f + (0.025f + normalizedLevel * 0.12f + normalizedEnergy * 0.018f) * wave)
                drawRoundRect(
                    color = if (bar % 3 == 1) orbAccent.copy(alpha = 0.50f + normalizedLevel * 0.40f)
                    else orbLight.copy(alpha = 0.48f + normalizedLevel * 0.45f),
                    topLeft = Offset(x, baseline - height / 2f),
                    size = Size(barWidth, height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth)
                )
            }
        }
        Text(
            text = stateLabel,
            color = orbLight.copy(alpha = 0.90f),
            fontSize = 8.sp,
            letterSpacing = 1.3.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp)
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFallbackPlasma(
    center: Offset,
    radius: Float,
    phase: Float,
    energy: Float,
    orbPrimary: Color,
    orbLight: Color,
    orbAccent: Color,
    orbDeep: Color
) {
    drawCircle(
        brush = Brush.radialGradient(
            listOf(orbLight.copy(alpha = 0.98f), orbPrimary.copy(alpha = 0.96f), orbDeep.copy(alpha = 0.92f), orbDeep.copy(alpha = 0.78f)),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )
    for (arm in 0 until 8) {
        val angle = phase * 0.75f + arm * (2f * PI.toFloat() / 8f)
        val reach = radius * (0.30f + 0.10f * sin(phase * 4f + arm * 1.4f))
        val tip = Offset(center.x + cos(angle) * reach, center.y + sin(angle) * reach)
        drawLine(
            color = (if (arm % 2 == 0) orbLight else orbAccent).copy(alpha = 0.24f + energy * 0.27f),
            start = center,
            end = tip,
            strokeWidth = radius * 0.045f
        )
        drawCircle(orbLight.copy(alpha = 0.32f), radius = radius * 0.028f, center = tip)
    }
    drawCircle(
        brush = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.23f), Color.Transparent), center, radius * 0.80f),
        radius = radius * 0.80f,
        center = center
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawComet(
    center: Offset,
    orbitRadius: Float,
    yScale: Float,
    rotationDegrees: Float,
    phase: Float,
    index: Int,
    alpha: Float,
    orbAccent: Color,
    orbLight: Color
) {
    val angle = phase * (2.6f + index * 0.45f) + index * 2.1f
    val tilt = rotationDegrees * PI.toFloat() / 180f
    val localX = orbitRadius * cos(angle)
    val localY = orbitRadius * yScale * sin(angle)
    val point = Offset(
        center.x + localX * cos(tilt) - localY * sin(tilt),
        center.y + localX * sin(tilt) + localY * cos(tilt)
    )
    val tangentX = -sin(angle)
    val tangentY = cos(angle) * yScale
    val tangentLength = kotlin.math.sqrt(tangentX * tangentX + tangentY * tangentY).coerceAtLeast(0.001f)
    val tail = Offset(
        point.x - (tangentX / tangentLength) * orbitRadius * 0.16f,
        point.y - (tangentY / tangentLength) * orbitRadius * 0.16f
    )
    drawLine(orbAccent.copy(alpha = alpha * 0.65f), tail, point, strokeWidth = orbitRadius * 0.045f)
    drawCircle(orbLight.copy(alpha = 0.88f), radius = orbitRadius * 0.045f, center = point)
    drawCircle(orbAccent.copy(alpha = alpha * 0.30f), radius = orbitRadius * 0.10f, center = point)
}

private fun stateCode(state: String): Float = when (state) {
    "LISTENING" -> 1f
    "WORKING", "THINKING", "CONNECTING" -> 2f
    "SPEAKING" -> 3f
    "NEEDS_ATTENTION", "ERROR" -> 4f
    else -> 0f
}

private const val PLASMA_STAR_SHADER = """
    uniform float2 iResolution;
    uniform float iTime;
    uniform float iEnergy;
    uniform float iState;
    uniform float3 iDeep;
    uniform float3 iPrimary;
    uniform float3 iLight;
    uniform float3 iAccent;

    half4 main(float2 p) {
        float scale = min(iResolution.x, iResolution.y);
        float2 uv = (p - 0.5 * iResolution) / scale;
        float r = length(uv);
        float a = atan(uv.y, uv.x);
        float t = iTime;
        float n1 = sin(uv.x * 18.0 + sin(uv.y * 12.0 + t * 1.25));
        float n2 = cos(uv.y * 17.0 - sin(uv.x * 11.0 - t * 0.92));
        float plasma = n1 * n2;
        float ridge = pow(1.0 - abs(plasma), 2.1);
        float arms = 0.5 + 0.5 * cos(a * 7.0 + sin(a * 3.0 + t) * 0.75 + t * 0.55);
        float star = smoothstep(0.24, 0.88, ridge * 0.74 + arms * 0.30 + iEnergy * 0.10);
        float core = 1.0 - smoothstep(0.08, 0.335, r);
        float filament = star * smoothstep(0.34, 0.02, r) * 0.74;
        float edge = exp(-abs(r - (0.245 + 0.008 * sin(a * 6.0 + t))) * 24.0);
        float pulse = 0.88 + 0.12 * sin(t * (iState == 1.0 ? 4.0 : 1.8));
        float3 color = mix(iDeep, iPrimary, clamp(core * 0.84 + filament * 0.58, 0.0, 1.0));
        color = mix(color, iLight, clamp((core * 0.28 + ridge * filament * 0.46) * pulse, 0.0, 0.66));
        color = mix(color, iAccent, clamp(edge * 0.30 + arms * filament * 0.11, 0.0, 0.36));
        float alpha = clamp(core * (0.82 + filament * 0.36) + edge * 0.20, 0.0, 1.0);
        return half4(half3(color * alpha), half(alpha));
    }
"""
