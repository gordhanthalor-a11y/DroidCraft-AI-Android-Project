package com.droidcraft.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RedNoteDubbingScreen(
    videoUrl: String,
    onExportRequested: () -> Unit
) {
    var textMode by remember { mutableStateOf("hindi_replace") } // "blur" or "hindi_replace"
    var audioDuckingDb by remember { mutableFloatStateOf(-14f) }
    var voiceSpeed by remember { mutableFloatStateOf(1.05f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        // RedNote Studio Header
        Text(
            text = "RedNote Hindi Dubbing Studio (小红书)",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
        Text(
            text = "9:16 Vertical Reels · Energetic Indian Creator Voice",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 9:16 Vertical Preview Container
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "9:16 Native Vertical Video Player\n(Active Ducking: ${audioDuckingDb.toInt()}dB)",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )

            // Devanagari Dark Pill Overlay
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 40.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = .92f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (textMode == "blur") "[Gaussian Blur Mask]" else "माइक्रो ड्रोन ₹240",
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Text Mode Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(
                onClick = { textMode = "hindi_replace" },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (textMode == "hindi_replace") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text("Hindi Dark Pill")
            }

            Button(
                onClick = { textMode = "blur" },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (textMode == "blur") MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text("Blur Mask")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Export Button
        Button(
            onClick = onExportRequested,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text("Export 1080x1920 MP4 to Android Device")
        }
    }
}