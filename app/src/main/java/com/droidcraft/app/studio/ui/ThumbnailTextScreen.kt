package com.droidcraft.app.studio.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.droidcraft.app.studio.ThumbnailRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Cyan = Color(0xFF56EFF7)
private val Slate = Color(0xFFA3BAC6)
private val Light = Color(0xFFE7F5F8)
private val Green = Color(0xFF48E1C2)
private val Red = Color(0xFFFF7183)

/** B6 — Thumbnail Text: photo पर bold Hindi text, 9:16, नीचे-बाएँ खाली। */
@Composable
fun ThumbnailTextScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var out by remember { mutableStateOf<File?>(null) }
    var status by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) { imageUri = u; out = null }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Thumbnail Text", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Cyan)
        Text("Photo पर बड़ा पीला text — 9:16, नीचे-बाएँ कोना खाली", fontSize = 12.sp, color = Slate)

        if (imageUri != null) {
            Image(
                painter = rememberAsyncImagePainter(imageUri),
                contentDescription = "selected photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(260.dp)
            )
        }

        OutlinedButton(onClick = { picker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Image, contentDescription = null, tint = Cyan)
            Spacer(Modifier.width(8.dp))
            Text("Photo चुनो")
        }

        OutlinedTextField(
            value = text, onValueChange = { text = it },
            label = { Text("बड़ा text (हिंदी)") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp)
        )

        Button(
            onClick = {
                val u = imageUri ?: run { status = "पहले photo चुनो"; return@Button }
                if (text.isBlank()) { status = "पहले text लिखो"; return@Button }
                busy = true; status = "Thumbnail बन रहा है…"
                scope.launch {
                    runCatching {
                        val f = withContext(Dispatchers.IO) {
                            ThumbnailRenderer.render(
                                context, u,
                                ThumbnailRenderer.Options(text = text)
                            )
                        }
                        out = f
                        status = "तैयार ✅ ${f.name}"
                    }.onFailure { status = "Fail: ${it.message}" }
                    busy = false
                }
            },
            enabled = !busy, modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.TextFields, contentDescription = null, tint = Cyan)
            Spacer(Modifier.width(8.dp))
            Text("Thumbnail बनाओ")
        }

        if (status.isNotBlank()) {
            Text(status, fontSize = 12.sp, color = if (status.contains("Fail")) Red else Green)
        }
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        out?.let { f ->
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF0E2030)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(f.name, color = Light, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("1080×1920 PNG — नीचे-बाएँ कोना खाली रखा गया", color = Slate, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", f
                            )
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "image/png"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }, "Thumbnail share करो"
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }) { Text("Share") }
                    }
                }
            }
        }
    }
}
