package com.droidcraft.app.studio.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidcraft.app.studio.ScriptWriter
import kotlinx.coroutines.launch

private val Cyan = Color(0xFF56EFF7)
private val Slate = Color(0xFFA3BAC6)
private val Light = Color(0xFFE7F5F8)
private val Green = Color(0xFF48E1C2)
private val Red = Color(0xFFFF7183)

/** B5 — Script Writer: topic → Gemini Hindi script (hook 2s, no greeting, source facts). */
@Composable
fun ScriptWriterScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var topic by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var script by remember { mutableStateOf("") }
    var hook by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Script Writer", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Cyan)
        Text("Topic दो — Gemini Hindi script लिखेगा (hook पहले 2 सेकंड)", fontSize = 12.sp, color = Slate)

        OutlinedTextField(
            value = topic, onValueChange = { topic = it },
            label = { Text("Topic") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = source, onValueChange = { source = it },
            label = { Text("Source link (वैकल्पिक — facts सिर्फ़ इससे)") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                if (topic.isBlank()) { status = "पहले topic डालो"; return@Button }
                busy = true; status = "Script लिख रहा हूँ…"
                scope.launch {
                    val r = ScriptWriter.write(context, topic, source.takeIf { it.isNotBlank() })
                    script = r.script; hook = r.hook
                    status = if (script.isNotBlank()) "तैयार ✅" else "Gemini key Settings में डालो"
                    busy = false
                }
            },
            enabled = !busy, modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Edit, contentDescription = null, tint = Cyan)
            Spacer(Modifier.width(8.dp))
            Text("Script लिखो")
        }

        if (status.isNotBlank()) {
            Text(status, fontSize = 12.sp, color = if (status.contains("Fail")) Red else Green)
        }
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        if (script.isNotBlank()) {
            if (hook.isNotBlank()) {
                Surface(shape = RoundedCornerShape(12.dp), color = Cyan.copy(alpha = 0.12f)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Hook (पहले 2s)", fontSize = 11.sp, color = Slate)
                        Text(hook, color = Cyan, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
            }
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF0E2030)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(script, color = Light, fontSize = 13.sp, lineHeight = 20.sp)
                    TextButton(onClick = {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, script)
                                }, "Script share करो"
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }) { Text("Share", color = Cyan, fontSize = 12.sp) }
                }
            }
        }
    }
}
