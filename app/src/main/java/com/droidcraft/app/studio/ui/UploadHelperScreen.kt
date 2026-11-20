package com.droidcraft.app.studio.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.droidcraft.app.studio.StudioSettings
import com.droidcraft.app.studio.qc.VideoQc
import kotlinx.coroutines.launch

private val Cyan = Color(0xFF7DF9FF)
private val Slate = Color(0xFF94A3B8)
private val Light = Color(0xFFE2E8F0)
private val Green = Color(0xFF4ADE80)
private val Red = Color(0xFFF87171)
private val Amber = Color(0xFFFBBF24)

/**
 * B2 — Upload Helper with QC: वीडियो चुनो → pass/fail list → templates → upload reminder।
 */
@Composable
fun UploadHelperScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var report by remember { mutableStateOf<VideoQc.Report?>(null) }
    var busy by remember { mutableStateOf(false) }
    var topic by remember { mutableStateOf("") }
    var credit by remember { mutableStateOf(StudioSettings.creditLine(context)) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) {
            uri = u
            busy = true
            scope.launch {
                report = VideoQc.run(context, u)
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Upload Helper QC", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Cyan)
        Text("Upload से पहले फ़ाइल खुद जाँचो — Shorts refuse होने की दोहराव रुकेगा", fontSize = 12.sp, color = Slate)

        Button(
            onClick = { picker.launch("video/*") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy
        ) {
            Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = Cyan)
            Spacer(Modifier.width(8.dp))
            Text(if (busy) "जाँच हो रही है…" else "वीडियो चुनो")
        }

        if (busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // ---------------- QC checks
        report?.let { r ->
            val passCount = r.checks.count { it.pass }
            Surface(shape = RoundedCornerShape(12.dp), color = if (r.ok) Green.copy(alpha = 0.10f) else Amber.copy(alpha = 0.10f)) {
                Text(
                    if (r.ok) "✅ सब checks pass ($passCount/${r.checks.size}) — upload कर सकते हो"
                    else "⚠️ ${r.checks.size - passCount} check(s) fail — नीचे fix देखो",
                    modifier = Modifier.padding(12.dp),
                    color = if (r.ok) Green else Amber,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            r.checks.forEach { c ->
                Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF111A2E)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (c.pass) "✅" else "❌", fontSize = 14.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(c.name, color = Light, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                        Text(c.detail, color = Slate, fontSize = 11.sp)
                        c.fix?.let {
                            Text("Fix: $it", color = Amber, fontSize = 11.sp)
                        }
                    }
                }
            }

            // privacy note (YouTube private video का जवाब)
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF111A2E)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("🔒 Private वीडियो का जवाब", color = Cyan, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(
                        VideoQc.PRIVACY_NOTE,
                        color = Slate, fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        // ---------------- templates
        Text("Title / Description templates", fontSize = 14.sp, color = Cyan, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = topic, onValueChange = { topic = it },
            label = { Text("Topic (जैसे: GTA 6 trailer analysis)") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = credit, onValueChange = {
                credit = it
                StudioSettings.setCreditLine(context, it)
            },
            label = { Text("Creator credit line (हमेशा लिखो)") },
            modifier = Modifier.fillMaxWidth()
        )

        val titleT = StudioSettings.titleTemplate(context).replace("{topic}", topic.ifBlank { "…" })
        val descT = StudioSettings.descriptionTemplate(context)
            .replace("{topic}", topic.ifBlank { "…" })
            .replace("{credit}", credit)

        Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF111A2E)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Title:", color = Slate, fontSize = 11.sp)
                Text(titleT, color = Light, fontSize = 13.sp)
                HorizontalDivider(color = Color(0xFF1E293B))
                Text("Description:", color = Slate, fontSize = 11.sp)
                Text(descT, color = Light, fontSize = 12.sp)
            }
        }

        // ---------------- upload flow reminder
        Text("Upload flow (हमेशा यही करो)", fontSize = 14.sp, color = Cyan, fontWeight = FontWeight.SemiBold)
        listOf(
            "1. पहले **Unlisted** publish करो (public नहीं)",
            "2. YouTube checks का इंतज़ार करो (Shorts के लिए 24-48 घंटे)",
            "3. जाँचो कि Short बन गया (9:16 badge)",
            "4. फिर YouTube Studio से **Public** करो"
        ).forEach { step ->
            Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFF111A2E)) {
                Text(step, modifier = Modifier.padding(10.dp), color = Light, fontSize = 12.sp)
            }
        }
    }
}
