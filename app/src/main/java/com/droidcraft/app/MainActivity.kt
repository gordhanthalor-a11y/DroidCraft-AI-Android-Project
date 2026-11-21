package com.droidcraft.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.droidcraft.app.jarvis.JarvisViewModel
import com.droidcraft.app.jarvis.wake.WakeWordService
import com.droidcraft.app.ui.DroidCraftNavHost
import com.droidcraft.app.ui.theme.DroidCraftTheme
import com.droidcraft.app.viewmodel.YouTubeAutomationViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: YouTubeAutomationViewModel by viewModels()
    private val jarvisViewModel: JarvisViewModel by viewModels()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeReceiverRegistered = false
    private var wakeInputPending = false

    private val wakeWordReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WakeWordService.ACTION_WAKE_DETECTED) {
                WakeWordService.dismissWakeNotification(this@MainActivity)
                startSpeechAfterWakeWord()
            }
        }
    }

    // Android Speech-to-Text Contract for Voice Commands in Hindi & English
    val speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            spoken?.let { text ->
                Log.i(TAG, "Voice recognized: $text")
                viewModel.sendAssistantMessage(text) { reply, emotion ->
                    jarvisViewModel.speak(reply, emotion, force = true)
                }
            }
        }
        restartWakeWordIfEnabled()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.i(TAG, "MainActivity initialized - Edge-to-Edge Material 3 dark dashboard")

        setContent {
            DroidCraftTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DroidCraftNavHost(
                        viewModel = viewModel,
                        jarvisViewModel = jarvisViewModel,
                        onTriggerVoiceInput = { launchVoiceRecognizer() }
                    )
                }
            }
        }
        handleWakeNotificationIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        if (!wakeReceiverRegistered) {
            val filter = IntentFilter(WakeWordService.ACTION_WAKE_DETECTED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(wakeWordReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(wakeWordReceiver, filter)
            }
            wakeReceiverRegistered = true
        }
    }

    override fun onStop() {
        if (wakeReceiverRegistered) {
            unregisterReceiver(wakeWordReceiver)
            wakeReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWakeNotificationIntent(intent)
    }

    private fun handleWakeNotificationIntent(intent: Intent?) {
        if (intent?.action != WakeWordService.ACTION_OPEN_AFTER_WAKE) return
        WakeWordService.dismissWakeNotification(this)
        startSpeechAfterWakeWord()
    }

    private fun startSpeechAfterWakeWord() {
        if (wakeInputPending) return
        wakeInputPending = true
        mainHandler.postDelayed({
            wakeInputPending = false
            if (!isFinishing && !isDestroyed) launchVoiceRecognizer()
        }, WAKE_TO_COMMAND_DELAY_MS)
    }

    private fun restartWakeWordIfEnabled() {
        val handsFreeEnabled = getSharedPreferences("droidcraft", MODE_PRIVATE)
            .getBoolean("jarvis_hands_free", false)
        if (!handsFreeEnabled ||
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        mainHandler.postDelayed({
            if (!isFinishing && !isDestroyed) WakeWordService.start(this)
        }, RESTART_WAKE_LISTENER_DELAY_MS)
    }

    fun launchVoiceRecognizer() {
        val speechLanguage = getSharedPreferences("droidcraft", MODE_PRIVATE)
            .getString("jarvis_voice_language", "hi-IN") ?: "hi-IN"
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLanguage)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, speechLanguage)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak a command in Hindi or English...")
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Speech recognizer unavailable on this device", e)
            restartWakeWordIfEnabled()
        }
    }

    companion object {
        private const val TAG = "DroidCraftActivity"
        private const val WAKE_TO_COMMAND_DELAY_MS = 700L
        private const val RESTART_WAKE_LISTENER_DELAY_MS = 700L
    }
}