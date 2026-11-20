package com.droidcraft.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.droidcraft.app.ui.screens.PipelineScreen
import com.droidcraft.app.viewmodel.YouTubeAutomationViewModel

private data class NavTab(val route: String, val label: String, val icon: ImageVector)

@Composable
fun DroidCraftNavHost(
    viewModel: YouTubeAutomationViewModel,
    onTriggerVoiceInput: () -> Unit
) {
    val navController = rememberNavController()
    val tabs = listOf(
        NavTab("jarvis", "Jarvis", Icons.Default.AutoAwesome),
        NavTab("studio", "Studio", Icons.Default.Mic),
        NavTab("assistant", "Chat", Icons.Default.Face),
        NavTab("live", "Live Talk", Icons.Default.GraphicEq),
        NavTab("more", "More", Icons.Default.MoreHoriz)
    )
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            // Keep native navigation visible on every route, including the WebView HUD,
            // so a rendering failure never traps the user on a blank first screen.
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo("jarvis") { saveState = true }
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
            startDestination = "jarvis",
            modifier = Modifier.padding(padding)
        ) {
            composable("jarvis") {
                JarvisScreen(
                    liveViewModel = viewModel,
                    onOpenStudio = { navController.navigate("studio") },
                    onOpenSettings = { navController.navigate("settings") },
                    onOpenChat = { navController.navigate("assistant") },
                    onOpenMore = { navController.navigate("more") }
                )
            }
            composable("social") { com.droidcraft.app.social.ui.SocialHubScreen() }
            composable("studio") { com.droidcraft.app.studio.ui.StudioHubScreen() }
            composable("pipeline") { PipelineScreen(viewModel) }
            composable("assistant") {
                AssistantScreen(
                    viewModel = viewModel,
                    onTriggerVoiceInput = onTriggerVoiceInput,
                    onOpenSettings = { navController.navigate("settings") }
                )
            }
            composable("live") {
                LiveConversationScreen(
                    viewModel = viewModel,
                    onOpenSettings = { navController.navigate("settings") },
                    onOpenChat = { navController.navigate("assistant") }
                )
            }
            composable("dubbing") { com.droidcraft.app.studio.ui.DubbingScreen() }
            composable("settings") {
                SettingsScreen(viewModel) {
                    navController.navigate("live") {
                        popUpTo("jarvis") { saveState = true }
                        launchSingleTop = true
                    }
                }
            }
            composable("more") {
                MoreScreen { route -> navController.navigate(route) }
            }
        }
    }
}

@Composable
private fun MoreScreen(onNavigate: (String) -> Unit) {
    val destinations = listOf(
        "social" to "Social Hub",
        "pipeline" to "Video Pipeline",
        "dubbing" to "Dubbing",
        "settings" to "Settings"
    )
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("More tools", style = MaterialTheme.typography.headlineSmall)
        destinations.forEach { (route, label) ->
            Button(onClick = { onNavigate(route) }, modifier = Modifier.fillMaxWidth()) {
                Text(label)
            }
        }
    }
}

@Composable
fun AssistantScreen(
    viewModel: YouTubeAutomationViewModel,
    onTriggerVoiceInput: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val messages by viewModel.assistantMessages.collectAsState()
    val pending by viewModel.pendingConfirmation.collectAsState()
    val hasGeminiKey by viewModel.hasGeminiKey.collectAsState()
    val thinking by viewModel.isThinking.collectAsState()
    val activeModel by viewModel.activeGeminiModel.collectAsState()
    val speakReplies by viewModel.speakAssistantReplies.collectAsState()
    var input by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AI Creator Assistant", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Gemini model: $activeModel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { viewModel.setSpeakAssistantReplies(!speakReplies) }) {
                Icon(
                    imageVector = if (speakReplies) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                    contentDescription = if (speakReplies) "Turn spoken replies off" else "Turn spoken replies on"
                )
            }
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }
        if (!hasGeminiKey) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Add a Gemini API key in Settings to chat with the assistant.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onOpenSettings) { Text("Settings") }
                }
            }
        }
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
                        Text(
                            msg.text,
                            modifier = Modifier.padding(10.dp),
                            color = if (msg.isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        if (thinking) {
            Text("Soch raha hoon...", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Hindi ya English mein likhiye...") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )
            IconButton(onClick = onTriggerVoiceInput) {
                Icon(Icons.Default.Mic, contentDescription = "Voice input")
            }
            IconButton(
                onClick = {
                    if (input.isNotBlank()) {
                        viewModel.sendAssistantMessage(input.trim())
                        input = ""
                    }
                },
                enabled = input.isNotBlank()
            ) {
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
