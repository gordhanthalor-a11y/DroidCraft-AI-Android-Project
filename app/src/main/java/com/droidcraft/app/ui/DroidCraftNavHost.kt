package com.droidcraft.app.ui

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
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.droidcraft.app.ui.screens.PipelineScreen
import com.droidcraft.app.viewmodel.YouTubeAutomationViewModel

private data class NavTab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun DroidCraftNavHost(
    viewModel: YouTubeAutomationViewModel,
    onTriggerVoiceInput: () -> Unit
) {
    val navController = rememberNavController()
    val tabs = listOf(
        NavTab("pipeline", "Pipeline", Icons.Default.List),
        NavTab("assistant", "Assistant", Icons.Default.Face),
        NavTab("dubbing", "Dubbing", Icons.Default.PlayArrow)
    )
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo("pipeline") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "pipeline",
            modifier = Modifier.padding(padding)
        ) {
            composable("pipeline") { PipelineScreen(viewModel) }
            composable("assistant") { AssistantScreen(viewModel, onTriggerVoiceInput) }
            composable("dubbing") { RedNoteDubbingScreen(videoUrl = "", onExportRequested = {}) }
        }
    }
}

@Composable
fun AssistantScreen(
    viewModel: YouTubeAutomationViewModel,
    onTriggerVoiceInput: () -> Unit
) {
    val messages by viewModel.assistantMessages.collectAsState()
    val pending by viewModel.pendingConfirmation.collectAsState()
    var input by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Text("AI Creator Assistant", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { msg ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (msg.isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(msg.text, modifier = Modifier.padding(10.dp))
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Hindi ya English mein likhiye...") }
            )
            IconButton(onClick = onTriggerVoiceInput) {
                Icon(Icons.Default.Call, contentDescription = "Voice")
            }
            IconButton(onClick = {
                if (input.isNotBlank()) {
                    viewModel.sendAssistantMessage(input.trim())
                    input = ""
                }
            }) {
                Icon(Icons.Default.Send, contentDescription = "Send")
            }
        }
    }

    pending?.let { state ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissConfirmation() },
            confirmButton = { TextButton(onClick = { viewModel.confirmPendingAction() }) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = { viewModel.dismissConfirmation() }) { Text("Cancel") } },
            title = { Text(state.action) },
            text = { Text(state.message) }
        )
    }
}
