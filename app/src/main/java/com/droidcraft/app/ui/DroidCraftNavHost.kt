package com.droidcraft.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.droidcraft.app.jarvis.JarvisViewModel
import com.droidcraft.app.ui.screens.PipelineScreen
import com.droidcraft.app.ui.theme.CircuitBackdrop
import com.droidcraft.app.viewmodel.YouTubeAutomationViewModel

private data class NavTab(val route: String, val label: String, val icon: ImageVector)

@Composable
fun DroidCraftNavHost(
    viewModel: YouTubeAutomationViewModel,
    jarvisViewModel: JarvisViewModel,
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
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            // Keep native navigation visible on every route so tool screens are never a dead end.
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
        Box(Modifier.fillMaxSize().padding(padding)) {
            CircuitBackdrop(Modifier.matchParentSize())
            NavHost(
                navController = navController,
                startDestination = "jarvis",
                modifier = Modifier.matchParentSize()
            ) {
            composable("jarvis") {
                JarvisScreen(
                    liveViewModel = viewModel,
                    jarvisViewModel = jarvisViewModel,
                    onOpenStudio = { navController.navigate("studio") },
                    onOpenSettings = { navController.navigate("settings") },
                    onOpenChat = { navController.navigate("assistant") },
                    onOpenLiveTalk = { navController.navigate("live") },
                    onOpenMore = { navController.navigate("more") },
                    onOpenSocialHub = { navController.navigate("social") },
                    onOpenClipper = { navController.navigate("clipper") },
                    onOpenVoiceStudio = { navController.navigate("voice-studio") },
                    onOpenDubbing = { navController.navigate("dubbing") },
                    onOpenUploadHelper = { navController.navigate("upload-helper") },
                    isVisible = current == "jarvis"
                )
            }
            composable("social") { com.droidcraft.app.social.ui.SocialHubScreen() }
            composable("studio") { com.droidcraft.app.studio.ui.StudioHubScreen() }
            composable("pipeline") { PipelineScreen(viewModel) }
            composable("assistant") {
                AssistantScreen(
                    viewModel = viewModel,
                    jarvisVoiceViewModel = jarvisViewModel,
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
            composable("clipper") { com.droidcraft.app.studio.ui.ClipperScreen() }
            composable("voice-studio") { com.droidcraft.app.studio.ui.VoiceStudioScreen() }
            composable("upload-helper") { com.droidcraft.app.studio.ui.UploadHelperScreen() }
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
            composable("memories") { MemoriesScreen() }
            composable("language") { LanguageScreen() }
        }
        }
    }
}

private data class MoreDestination(
    val route: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector
)

@Composable
private fun MoreScreen(onNavigate: (String) -> Unit) {
    val destinations = listOf(
        MoreDestination("social", "Social Hub", "Connected accounts and publishing queue", Icons.Default.Face),
        MoreDestination("pipeline", "Video Pipeline", "Review, approve, and track video jobs", Icons.Default.List),
        MoreDestination("dubbing", "Dubbing", "Translate and voice your creator videos", Icons.Default.GraphicEq),
        MoreDestination("memories", "Memories", "Review facts Jarvis has saved", Icons.Default.Favorite),
        MoreDestination("language", "Language", "Set your voice-input preference", Icons.Default.Translate),
        MoreDestination("settings", "Settings", "Models, voice, keys, and connections", Icons.Default.Settings)
    )
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("DROIDCRAFT · CONTROL DECK", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text("More tools", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("A connected workspace for your assistant and creator workflow.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            items(destinations) { destination ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onNavigate(destination.route) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .92f)),
                    shape = RoundedCornerShape(17.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Icon(destination.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(10.dp))
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(destination.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(destination.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }
    }
}

@Composable
fun AssistantScreen(
    viewModel: YouTubeAutomationViewModel,
    jarvisVoiceViewModel: JarvisViewModel,
    onTriggerVoiceInput: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val messages by viewModel.assistantMessages.collectAsState()
    val pending by viewModel.pendingConfirmation.collectAsState()
    val hasGeminiKey by viewModel.hasGeminiKey.collectAsState()
    val thinking by viewModel.isThinking.collectAsState()
    val activeModel by viewModel.activeGeminiModel.collectAsState()
    val speakReplies by viewModel.speakAssistantReplies.collectAsState()
    val jarvisVoiceNotice by jarvisVoiceViewModel.voiceNotice.collectAsState()
    var input by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(10.dp))
                }
                Column(Modifier.weight(1f).padding(start = 11.dp)) {
                    Text("J.A.R.V.I.S. / CHAT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text("Jarvis creator copilot", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${if (hasGeminiKey) "READY" else "SETUP REQUIRED"} · $activeModel", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = {
                    val enabled = !speakReplies
                    viewModel.setSpeakAssistantReplies(enabled)
                    if (!enabled) jarvisVoiceViewModel.stopSpeak()
                }) {
                    Icon(
                        imageVector = if (speakReplies) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                        contentDescription = if (speakReplies) "Turn spoken replies off" else "Turn spoken replies on",
                        tint = if (speakReplies) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Open settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        jarvisVoiceNotice?.takeIf { it.isNotBlank() }?.let { notice ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    notice,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (!hasGeminiKey) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(start = 13.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Connect your Gemini key", fontWeight = FontWeight.SemiBold)
                        Text("Save a key in Settings to start chatting.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    TextButton(onClick = onOpenSettings) { Text("Settings") }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            items(messages) { msg ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = if (msg.isUser) 16.dp else 5.dp, bottomEnd = if (msg.isUser) 5.dp else 16.dp),
                        color = if (msg.isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = if (msg.isUser) 1.dp else 0.dp
                    ) {
                        Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
                            Text(
                                if (msg.isUser) "YOU" else "JARVIS",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (msg.isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                msg.text,
                                modifier = Modifier.padding(top = 3.dp),
                                color = if (msg.isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(visible = thinking) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Thinking through it…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Hindi, Hinglish, or English…") },
                shape = RoundedCornerShape(17.dp),
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
                Icon(Icons.Default.Mic, contentDescription = "Voice input", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(
                onClick = {
                    if (input.isNotBlank()) {
                        viewModel.sendAssistantMessage(input.trim()) { reply, emotion ->
                            jarvisVoiceViewModel.speak(reply, emotion, force = true)
                        }
                        input = ""
                    }
                },
                enabled = input.isNotBlank()
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
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
