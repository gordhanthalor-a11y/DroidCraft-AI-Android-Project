package com.droidcraft.app.studio.ui

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
import com.droidcraft.app.studio.data.HindiNoteEntity
import com.droidcraft.app.studio.data.JobApplicationEntity
import kotlinx.coroutines.launch

private val Cyan = Color(0xFF56EFF7)
private val Slate = Color(0xFFA3BAC6)
private val Light = Color(0xFFE7F5F8)
private val Green = Color(0xFF48E1C2)
private val Red = Color(0xFFFF7183)
private val Dark = Color(0xFF050B15)

/** Module D — Daily-life extras: job applications + Hindi notes/reminders. */
@Composable
fun DailyScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    var jobs by remember { mutableStateOf<List<JobApplicationEntity>>(emptyList()) }
    var notes by remember { mutableStateOf<List<HindiNoteEntity>>(emptyList()) }
    var showJob by remember { mutableStateOf(false) }
    var showNote by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { db.jobApplicationDao().observeAll().collect { jobs = it } }
    LaunchedEffect(Unit) { db.hindiNoteDao().observeAll().collect { notes = it } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Job Applications", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Cyan)
                IconButton(onClick = { showJob = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add", tint = Cyan)
                }
            }
        }
        if (jobs.isEmpty()) {
            item { Text("अभी कोई application नहीं", color = Slate, fontSize = 12.sp) }
        }
        items(jobs, key = { it.id }) { j ->
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF0E2030)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(j.company, color = Light, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("${j.role} • ${j.appliedOn}", color = Slate, fontSize = 11.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(j.status, color = Cyan, fontSize = 12.sp)
                        Row {
                            TextButton(onClick = {
                                scope.launch { db.jobApplicationDao().delete(j.id) }
                            }) { Text("हटाओ", color = Red, fontSize = 11.sp) }
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Hindi Notes / Reminders", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Cyan)
                IconButton(onClick = { showNote = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add", tint = Cyan)
                }
            }
        }
        if (notes.isEmpty()) {
            item { Text("अभी कोई note नहीं", color = Slate, fontSize = 12.sp) }
        }
        items(notes, key = { it.id }) { n ->
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF0E2030)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(n.text, color = Light, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        scope.launch { db.hindiNoteDao().delete(n.id) }
                    }) { Text("हटाओ", color = Red, fontSize = 11.sp) }
                }
            }
        }
    }

    if (showJob) {
        var company by remember { mutableStateOf("") }
        var role by remember { mutableStateOf("") }
        var date by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("applied") }
        AlertDialog(
            onDismissRequest = { showJob = false },
            title = { Text("Job application", color = Light) },
            containerColor = Dark,
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = company, onValueChange = { company = it },
                        label = { Text("Company") }, singleLine = true)
                    OutlinedTextField(value = role, onValueChange = { role = it },
                        label = { Text("Role") }, singleLine = true)
                    OutlinedTextField(value = date, onValueChange = { date = it },
                        label = { Text("Date (yyyy-MM-dd)") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("applied", "interview", "offer", "rejected").forEach { s ->
                            FilterChip(
                                selected = status == s,
                                onClick = { status = s },
                                label = { Text(s, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (company.isBlank()) return@TextButton
                    scope.launch {
                        db.jobApplicationDao().upsert(
                            JobApplicationEntity(
                                company = company.trim(),
                                role = role.trim(),
                                appliedOn = date.ifBlank { "—" },
                                status = status
                            )
                        )
                    }
                    showJob = false
                }) { Text("जोड़ो", color = Cyan) }
            },
            dismissButton = { TextButton(onClick = { showJob = false }) { Text("बंद", color = Slate) } }
        )
    }

    if (showNote) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNote = false },
            title = { Text("Note / reminder", color = Light) },
            containerColor = Dark,
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("हिंदी में note") }, modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (text.isBlank()) return@TextButton
                    scope.launch { db.hindiNoteDao().upsert(HindiNoteEntity(text = text.trim())) }
                    showNote = false
                }) { Text("जोड़ो", color = Cyan) }
            },
            dismissButton = { TextButton(onClick = { showNote = false }) { Text("बंद", color = Slate) } }
        )
    }
}
