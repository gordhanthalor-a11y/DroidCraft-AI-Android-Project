package com.droidcraft.app.studio.ui

import android.content.Intent
import android.net.Uri
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
import com.droidcraft.app.studio.data.CreatorChannels
import com.droidcraft.app.studio.data.CampaignEntity
import kotlinx.coroutines.launch

private val Cyan = Color(0xFF7DF9FF)
private val Slate = Color(0xFF94A3B8)
private val Light = Color(0xFFE2E8F0)
private val Green = Color(0xFF4ADE80)
private val Amber = Color(0xFFFBBF24)

/**
 * Module C — Channel & Campaign Manager:
 * उसके channels + quick links, Content Rewards campaign tracker।
 */
@Composable
fun ManagerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    var campaigns by remember { mutableStateOf<List<CampaignEntity>>(emptyList()) }
    var showAdd by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { db.campaignDao().observeAll().collect { campaigns = it } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Mere Channels", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Cyan)
        }
        items(CreatorChannels.ALL) { ch ->
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF111A2E)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(ch.name, color = Light, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(ch.handle, color = Slate, fontSize = 11.sp)
                    }
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(CreatorChannels.studioUrl(ch)))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }) { Text("Studio", color = Cyan, fontSize = 12.sp) }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Content Rewards Campaigns", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Cyan)
                IconButton(onClick = { showAdd = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add", tint = Cyan)
                }
            }
        }

        if (campaigns.isEmpty()) {
            item {
                Text("अभी कोई campaign नहीं — + से जोड़ो", color = Slate, fontSize = 12.sp)
            }
        }

        items(campaigns, key = { it.id }) { c ->
            val statusColor = when (c.status) {
                "paid" -> Green
                "approved" -> Cyan
                else -> Amber
            }
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF111A2E)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(c.name, color = Light, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(c.status, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "$${"%.2f".format(c.ratePerThousand)} / 1,000 views • deadline ${c.deadline}",
                        color = Slate, fontSize = 11.sp
                    )
                    Text("Clips submitted: ${c.clipsSubmitted}", color = Slate, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            scope.launch {
                                db.campaignDao().setStatus(
                                    c.id,
                                    if (c.status == "pending") "approved" else "paid"
                                )
                            }
                        }) {
                            Text(
                                if (c.status == "pending") "Approve" else "Mark paid",
                                color = Cyan, fontSize = 11.sp
                            )
                        }
                        TextButton(onClick = {
                            scope.launch { db.campaignDao().delete(c.id) }
                        }) { Text("हटाओ", color = Red, fontSize = 11.sp) }
                    }
                }
            }
        }
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var rate by remember { mutableStateOf("") }
        var deadline by remember { mutableStateOf("") }
        var clips by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Campaign जोड़ो", color = Light) },
            containerColor = Dark,
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it },
                        label = { Text("Campaign name") }, singleLine = true)
                    OutlinedTextField(value = rate, onValueChange = { rate = it },
                        label = { Text("Rate ($ / 1,000 views)") }, singleLine = true)
                    OutlinedTextField(value = deadline, onValueChange = { deadline = it },
                        label = { Text("Deadline (yyyy-MM-dd)") }, singleLine = true)
                    OutlinedTextField(value = clips, onValueChange = { clips = it },
                        label = { Text("Clips submitted") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isBlank()) return@TextButton
                    scope.launch {
                        db.campaignDao().upsert(
                            CampaignEntity(
                                name = name.trim(),
                                ratePerThousand = rate.toDoubleOrNull() ?: 0.0,
                                deadline = deadline.ifBlank { "—" },
                                clipsSubmitted = clips.toIntOrNull() ?: 0
                            )
                        )
                    }
                    showAdd = false
                }) { Text("जोड़ो", color = Cyan) }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("बंद", color = Slate) }
            }
        )
    }
}

private val Dark = Color(0xFF0B1220)
private val Red = Color(0xFFF87171)
