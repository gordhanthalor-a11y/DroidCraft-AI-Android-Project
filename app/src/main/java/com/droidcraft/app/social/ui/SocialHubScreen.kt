package com.droidcraft.app.social.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.droidcraft.app.data.local.AppDatabase
import com.droidcraft.app.social.auth.OAuthManager
import com.droidcraft.app.social.auth.SocialCredentials
import com.droidcraft.app.social.data.SocialAccountEntity
import com.droidcraft.app.social.data.SocialPlatform
import com.droidcraft.app.social.data.SocialStatus
import kotlinx.coroutines.launch

/**
 * Social Hub — Buffer-जैसा dashboard.
 * Scheduled / Published / Failed counts, queue list, connect accounts, schedule videos.
 */
@Composable
fun SocialHubScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }

    var accounts by remember { mutableStateOf<List<SocialAccountEntity>>(emptyList()) }
    var queue by remember { mutableStateOf<List<com.droidcraft.app.social.data.PostQueueEntity>>(emptyList()) }
    var history by remember { mutableStateOf<List<com.droidcraft.app.social.data.PublishHistoryEntity>>(emptyList()) }
    var tab by remember { mutableIntStateOf(0) }
    var showConnect by remember { mutableStateOf(false) }
    var showSchedule by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        db.socialAccountDao().observeAll().collect { accounts = it }
    }
    LaunchedEffect(Unit) {
        db.postQueueDao().observeAll().collect { queue = it }
    }
    LaunchedEffect(Unit) {
        db.publishHistoryDao().observeRecent(50).collect { history = it }
    }

    val queued = queue.count { it.status == SocialStatus.QUEUED }
    val published = queue.count { it.status == SocialStatus.PUBLISHED }
    val failed = queue.count { it.status == SocialStatus.FAILED }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Jarvis Social", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF56EFF7))
                Text("Buffer-जैसा auto publishing", fontSize = 12.sp, color = Color(0xFFA3BAC6))
            }
            Row {
                IconButton(onClick = { showConnect = true }) {
                    Icon(Icons.Default.Link, contentDescription = "Connect", tint = Color(0xFF56EFF7))
                }
                IconButton(onClick = { showSchedule = true }) {
                    Icon(Icons.Default.Schedule, contentDescription = "Schedule", tint = Color(0xFF56EFF7))
                }
            }
        }

        // Stats
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatCard("Scheduled", queued, Color(0xFF56EFF7), Modifier.weight(1f))
            StatCard("Published", published, Color(0xFF48E1C2), Modifier.weight(1f))
            StatCard("Failed", failed, Color(0xFFFF7183), Modifier.weight(1f))
        }

        Spacer(Modifier.height(8.dp))

        // Tabs
        TabRow(selectedTabIndex = tab, containerColor = Color(0xFF050B15)) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Queue", color = Color(0xFFA3BAC6)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("History", color = Color(0xFFA3BAC6)) })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Accounts", color = Color(0xFFA3BAC6)) })
        }

        when (tab) {
            0 -> QueueList(queue)
            1 -> HistoryList(history)
            2 -> AccountsList(accounts, onDisconnect = { id ->
                scope.launch {
                    db.socialAccountDao().delete(id)
                    Toast.makeText(context, "Account disconnect ho gaya", Toast.LENGTH_SHORT).show()
                }
            })
        }
    }

    if (showConnect) {
        ConnectAccountDialog(
            onDismiss = { showConnect = false },
            onConnected = { showConnect = false }
        )
    }
    if (showSchedule) {
        ScheduleVideosDialog(
            onDismiss = { showSchedule = false }
        )
    }
}

@Composable
private fun StatCard(label: String, value: Int, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.10f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("$value", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = color)
            Text(label, fontSize = 11.sp, color = Color(0xFFA3BAC6))
        }
    }
}

@Composable
private fun QueueList(queue: List<com.droidcraft.app.social.data.PostQueueEntity>) {
    if (queue.isEmpty()) {
        EmptyState("कोई video queue में नहीं", "Schedule icon दबाकर videos जोड़ो")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(queue, key = { it.id }) { post ->
            val (label, color) = when (post.status) {
                SocialStatus.QUEUED -> "⏳ Scheduled" to Color(0xFF56EFF7)
                SocialStatus.UPLOADING -> "⬆️ Uploading" to Color(0xFFE6B55A)
                SocialStatus.PUBLISHED -> "✅ Published" to Color(0xFF48E1C2)
                SocialStatus.FAILED -> "❌ Failed" to Color(0xFFFF7183)
                else -> post.status to Color(0xFFA3BAC6)
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF0E2030)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(post.title.ifBlank { "Video" }, fontWeight = FontWeight.SemiBold, color = Color(0xFFE7F5F8), fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${post.platform} • ${java.text.SimpleDateFormat("dd MMM yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(post.scheduledAt))}" +
                            (if (post.isShort) " • Short" else ""),
                        fontSize = 11.sp, color = Color(0xFFA3BAC6)
                    )
                    Text(label, fontSize = 12.sp, color = color)
                    post.lastError?.let {
                        Text(it, fontSize = 10.sp, color = Color(0xFFFF7183), maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryList(history: List<com.droidcraft.app.social.data.PublishHistoryEntity>) {
    if (history.isEmpty()) {
        EmptyState("अभी कोई history नहीं", "Publish होने पर यहाँ दिखेगा")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(history, key = { it.id }) { h ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${if (h.success) "✅" else "❌"} ${h.platform} • ${h.externalId ?: "—"}",
                    fontSize = 12.sp,
                    color = if (h.success) Color(0xFF48E1C2) else Color(0xFFFF7183)
                )
                Text(
                    java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(h.attemptAt)),
                    fontSize = 10.sp, color = Color(0xFF7893A0)
                )
            }
            h.errorMessage?.let {
                Text(it, fontSize = 10.sp, color = Color(0xFFFF7183), maxLines = 2)
            }
        }
    }
}

@Composable
private fun AccountsList(
    accounts: List<SocialAccountEntity>,
    onDisconnect: (Long) -> Unit
) {
    if (accounts.isEmpty()) {
        EmptyState("कोई account connected नहीं", "Link icon से YouTube/Instagram जोड़ो")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(accounts, key = { it.id }) { acc ->
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF0E2030)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(acc.accountName, color = Color(0xFFE7F5F8), fontWeight = FontWeight.SemiBold)
                        Text(
                            "${acc.platform} • ${java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(acc.connectedAt))}",
                            fontSize = 11.sp, color = Color(0xFF7893A0)
                        )
                    }
                    TextButton(onClick = { onDisconnect(acc.id) }) {
                        Text("Disconnect", color = Color(0xFFFF7183), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Color(0xFFA3BAC6), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = Color(0xFF7893A0), fontSize = 12.sp)
        }
    }
}

// ---------------------------------------------------------------- Connect dialog

@Composable
private fun ConnectAccountDialog(onDismiss: () -> Unit, onConnected: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var platform by remember { mutableStateOf(SocialPlatform.YOUTUBE) }
    var gClientId by remember { mutableStateOf("") }
    var gClientSecret by remember { mutableStateOf("") }
    var mClientId by remember { mutableStateOf("") }
    var mClientSecret by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect Account", color = Color(0xFFE7F5F8)) },
        containerColor = Color(0xFF050B15),
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = platform == SocialPlatform.YOUTUBE,
                        onClick = { platform = SocialPlatform.YOUTUBE },
                        label = { Text("YouTube") }
                    )
                    FilterChip(
                        selected = platform == SocialPlatform.INSTAGRAM,
                        onClick = { platform = SocialPlatform.INSTAGRAM },
                        label = { Text("Instagram") }
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (platform == SocialPlatform.YOUTUBE) {
                    OutlinedTextField(
                        value = gClientId, onValueChange = { gClientId = it },
                        label = { Text("Google Client ID") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = gClientSecret, onValueChange = { gClientSecret = it },
                        label = { Text("Google Client Secret") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedTextField(
                        value = mClientId, onValueChange = { mClientId = it },
                        label = { Text("Meta App ID") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = mClientSecret, onValueChange = { mClientSecret = it },
                        label = { Text("Meta App Secret") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Credentials सिर्फ़ इसी device पर रहती हैं (encrypted). SOCIAL_SETUP.md देखो.",
                    fontSize = 10.sp, color = Color(0xFF7893A0)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                SocialCredentials.save(
                    context,
                    SocialCredentials.Creds(
                        googleClientId = gClientId.trim(),
                        googleClientSecret = gClientSecret.trim(),
                        metaClientId = mClientId.trim(),
                        metaClientSecret = mClientSecret.trim()
                    )
                )
                val state = OAuthManager.newState()
                context.getSharedPreferences("jarvis_social_oauth", Context.MODE_PRIVATE)
                    .edit().putString("oauth_state", state)
                    .putString("oauth_platform", platform).apply()

                val creds = SocialCredentials.get(context)
                val redirect = "https://localhost/oauth2callback"
                val url = if (platform == SocialPlatform.YOUTUBE) {
                    OAuthManager.googleAuthUrl(OAuthManager.GoogleCreds(creds.googleClientId, creds.googleClientSecret), redirect, state)
                } else {
                    OAuthManager.metaAuthUrl(OAuthManager.MetaCreds(creds.metaClientId, creds.metaClientSecret), redirect, state)
                }
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                onConnected()
            }) {
                Text("OAuth करो", color = Color(0xFF56EFF7))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Band", color = Color(0xFFA3BAC6)) }
        }
    )
}

// ---------------------------------------------------------------- Schedule dialog

@Composable
private fun ScheduleVideosDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    var hours by remember { mutableStateOf(1) }
    var platform by remember { mutableStateOf(SocialPlatform.YOUTUBE) }

    var connected by remember { mutableStateOf<List<String>>(emptyList()) }
    var picked by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            picked = uris
            scope.launch {
                Toast.makeText(context, "${uris.size} videos चुनी — queue में जोड़ रहा हूँ", Toast.LENGTH_SHORT).show()
            }
        }
    }
    LaunchedEffect(Unit) {
        connected = com.droidcraft.app.social.schedule.VideoQueueManager.connectedPlatforms(context)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Schedule Videos", color = Color(0xFFE7F5F8)) },
        containerColor = Color(0xFF050B15),
        text = {
            Column {
                Text("Videos चुनो (gallery), फिर date/time सेट करो।", fontSize = 13.sp, color = Color(0xFFA3BAC6))
                Spacer(Modifier.height(12.dp))
                Button(onClick = { picker.launch("video/*") }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = Color(0xFF56EFF7))
                    Spacer(Modifier.width(8.dp))
                    Text("Video चुनो")
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = platform == SocialPlatform.YOUTUBE,
                        onClick = { platform = SocialPlatform.YOUTUBE },
                        label = { Text("YouTube") }
                    )
                    FilterChip(
                        selected = platform == SocialPlatform.INSTAGRAM,
                        onClick = { platform = SocialPlatform.INSTAGRAM },
                        label = { Text("Instagram") }
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (picked.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "${picked.size} videos तैयार — ${connected.size} platform(s) connected",
                        fontSize = 12.sp, color = Color(0xFF56EFF7)
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    val platforms = connected.ifEmpty { listOf("youtube") }
                                    val start = System.currentTimeMillis() + hours * 3600_000L
                                    val out = com.droidcraft.app.social.schedule.VideoQueueManager.addBatch(
                                        context, picked, platforms, start
                                    )
                                    Toast.makeText(
                                        context,
                                        "${out.size} posts schedule हो गए  ✅",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    picked = emptyList()
                                    onDismiss()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Schedule करो (${hours}h बाद)")
                        }
                    }
                    Text(
                        "AI captions अपने आप बनेंगी, exact time पर publish होगा।",
                        fontSize = 10.sp, color = Color(0xFF7893A0)
                    )
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "50-100 videos एक साथ चुन सकते हो। Connected platforms: ${connected.joinToString(", ").ifBlank { "कोई नहीं — Accounts tab से जोड़ो" }}",
                        fontSize = 11.sp, color = Color(0xFF7893A0)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("ठीक", color = Color(0xFF56EFF7)) }
        }
    )
}
