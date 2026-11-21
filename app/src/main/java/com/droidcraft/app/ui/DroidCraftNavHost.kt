package com.droidcraft.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.droidcraft.app.ai.AudioOutputLevel
import com.droidcraft.app.jarvis.JarvisViewModel
import com.droidcraft.app.jarvis.overlay.JarvisBubbleController
import com.droidcraft.app.model.LiveConversationStatus
import com.droidcraft.app.ui.theme.CircuitBackdrop
import com.droidcraft.app.viewmodel.JarvisConversationViewModel
import kotlin.math.max

/** The only home destination is Jarvis; supporting screens can only be opened from Settings. */
@Composable
fun DroidCraftNavHost(
    conversationViewModel: JarvisConversationViewModel,
    jarvisViewModel: JarvisViewModel
) {
    var screen by rememberSaveable { mutableStateOf("jarvis") }
    val liveStatus by conversationViewModel.liveStatus.collectAsState()
    val liveAudioLevel by conversationViewModel.liveAudioLevel.collectAsState()
    val outputLevel by AudioOutputLevel.level.collectAsState()
    val thinking by jarvisViewModel.isThinking.collectAsState()
    val speaking by jarvisViewModel.isSpeaking.collectAsState()
    val listening by jarvisViewModel.isListening.collectAsState()
    val hasGeminiKey by conversationViewModel.hasGeminiKey.collectAsState()
    val actualAudioLevel = max(liveAudioLevel, outputLevel).coerceIn(0f, 1f)
    val targetEnergy = when (liveStatus) {
        LiveConversationStatus.CONNECTING -> 0.56f
        LiveConversationStatus.LISTENING -> 0.27f + actualAudioLevel * 0.68f
        LiveConversationStatus.SPEAKING -> 0.62f + actualAudioLevel * 0.37f
        LiveConversationStatus.ERROR -> 0.12f
        LiveConversationStatus.IDLE -> when {
            thinking -> 0.68f
            speaking -> 0.55f + actualAudioLevel * 0.42f
            listening -> 0.34f + actualAudioLevel * 0.52f
            else -> 0.09f
        }
    }.coerceIn(0f, 1f)
    val energy by animateFloatAsState(targetValue = targetEnergy, animationSpec = tween(180), label = "shared-energy")
    val motion = rememberInfiniteTransition(label = "jarvis-motion")
    val phase = motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(12_000, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "motion-phase"
    )
    LaunchedEffect(Unit) {
        JarvisBubbleController.openHomeRequests.collect { screen = "jarvis" }
    }
    BackHandler(enabled = screen != "jarvis") {
        screen = if (screen == "memories") "settings" else "jarvis"
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            CircuitBackdrop(Modifier.matchParentSize())
            EnergyBackdrop(Modifier.matchParentSize(), energy, phase)
            AnimatedContent(
                targetState = screen,
                modifier = Modifier.matchParentSize(),
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 12 }) togetherWith
                        (fadeOut(tween(180)) + slideOutHorizontally(tween(180)) { -it / 14 })
                },
                label = "jarvis-screen-transition"
            ) { destination ->
                when (destination) {
                    "settings" -> SettingsScreen(
                        conversationViewModel = conversationViewModel,
                        jarvisViewModel = jarvisViewModel,
                        energy = energy,
                        phase = phase,
                        onBack = { screen = "jarvis" },
                        onOpenMemories = { screen = "memories" }
                    )
                    "memories" -> MemoriesScreen(onBack = { screen = "settings" })
                    else -> JarvisScreen(
                        conversationViewModel = conversationViewModel,
                        jarvisViewModel = jarvisViewModel,
                        energy = energy,
                        phase = phase,
                        hasGeminiKey = hasGeminiKey,
                        onOpenSettings = { screen = "settings" }
                    )
                }
            }
        }
    }
}
