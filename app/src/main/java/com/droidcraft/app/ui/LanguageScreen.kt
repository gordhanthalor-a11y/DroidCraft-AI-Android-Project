package com.droidcraft.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class VoiceLanguageOption(val tag: String, val name: String, val subtitle: String)

@Composable
fun LanguageScreen() {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("droidcraft", 0) }
    val options = listOf(
        VoiceLanguageOption("hi-IN", "हिन्दी + Hinglish", "Hindi speech recognition, with everyday Hinglish"),
        VoiceLanguageOption("en-IN", "English (India)", "Indian English speech recognition")
    )
    var selected by remember {
        mutableStateOf(preferences.getString("jarvis_voice_language", "hi-IN") ?: "hi-IN")
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    Icons.Default.Language,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(11.dp).size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Language", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("VOICE INPUT PREFERENCE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }

        Text(
            "Choose the language Android should listen for when you tap the voice-input control. Your choice is saved on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        options.forEach { option ->
            val isSelected = selected == option.tag
            Card(
                modifier = Modifier.fillMaxWidth().clickable {
                    selected = option.tag
                    preferences.edit().putString("jarvis_voice_language", option.tag).apply()
                },
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = {
                            selected = option.tag
                            preferences.edit().putString("jarvis_voice_language", option.tag).apply()
                        }
                    )
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(option.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(option.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (isSelected) Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Text(
            "Jarvis can still understand and reply in Hindi, Hinglish, or English in Chat and Live Talk; this setting controls the device speech recognizer's preferred locale.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
