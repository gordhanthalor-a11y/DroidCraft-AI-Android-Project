package com.droidcraft.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.droidcraft.app.data.CapabilitySettings
import com.droidcraft.app.data.JarvisCapability
import com.droidcraft.app.jarvis.JarvisViewModel
import com.droidcraft.app.jarvis.a11y.ScreenCaptureCoordinator
import com.droidcraft.app.jarvis.overlay.JarvisBubbleController
import com.droidcraft.app.jarvis.wake.WakeWordService
import com.droidcraft.app.ui.DroidCraftNavHost
import com.droidcraft.app.ui.theme.DroidCraftTheme
import com.droidcraft.app.ui.theme.OrbThemeSettings
import com.droidcraft.app.viewmodel.JarvisConversationViewModel

class MainActivity : ComponentActivity() {
    private val conversationViewModel: JarvisConversationViewModel by viewModels()
    private val jarvisViewModel: JarvisViewModel by viewModels()
    private val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        ScreenCaptureCoordinator.onProjectionResult(this, result)
    }
    private var speechRecognizer: SpeechRecognizer? = null
    private var speechRecognizerRetryUsed = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeReceiverRegistered = false
    private var wakeInputPending = false

    private val wakeWordReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WakeWordService.ACTION_WAKE_DETECTED &&
                CapabilitySettings.isEnabled(this@MainActivity, JarvisCapability.OFFLINE_WAKE)
            ) {
                WakeWordService.dismissWakeNotification(this@MainActivity)
                startSpeechAfterWakeWord()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        OrbThemeSettings.initialize(this)
        ScreenCaptureCoordinator.attach {
            val manager = getSystemService(MediaProjectionManager::class.java)
                ?: error("Android screen capture service is unavailable.")
            screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
        }
        setContent {
            val orbTheme by OrbThemeSettings.selected.collectAsState()
            DroidCraftTheme(darkTheme = true, orbTheme = orbTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DroidCraftNavHost(
                        conversationViewModel = conversationViewModel,
                        jarvisViewModel = jarvisViewModel
                    )
                }
            }
        }
        handleOpenJarvisIntent(intent)
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
        if (CapabilitySettings.isEnabled(this, JarvisCapability.OFFLINE_WAKE) &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { WakeWordService.start(this) }
                .onFailure { Log.e(TAG, "Could not resume offline wake listener", it) }
        }
    }

    override fun onStop() {
        if (wakeReceiverRegistered) {
            unregisterReceiver(wakeWordReceiver)
            wakeReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
        ScreenCaptureCoordinator.detach()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenJarvisIntent(intent)
    }

    private fun handleOpenJarvisIntent(intent: Intent?) {
        when (intent?.action) {
            JarvisBubbleController.ACTION_OPEN_HOME -> {
                JarvisBubbleController.requestOpenHome()
                intent?.action = null
            }
            WakeWordService.ACTION_OPEN_AFTER_WAKE -> {
                WakeWordService.dismissWakeNotification(this)
                if (CapabilitySettings.isEnabled(this, JarvisCapability.OFFLINE_WAKE)) {
                    startSpeechAfterWakeWord()
                }
                intent?.action = null
            }
        }
    }

    private fun startSpeechAfterWakeWord() {
        if (wakeInputPending) return
        wakeInputPending = true
        mainHandler.postDelayed({
            wakeInputPending = false
            if (!isFinishing && !isDestroyed) launchVoiceRecognizer()
        }, WAKE_TO_COMMAND_DELAY_MS)
    }

    private fun launchVoiceRecognizer() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Offline wake phrase was detected, but microphone permission has since been revoked.")
            restartWakeWordIfEnabled()
            return
        }
        val speechLanguage = getSharedPreferences("droidcraft", MODE_PRIVATE)
            .getString("jarvis_voice_language", "hi-IN") ?: "hi-IN"
        speechRecognizerRetryUsed = false
        startInAppSpeechRecognition(speechLanguage, useOnDevice = true)
    }

    /** Programmatic speech recognition: no Google voice UI ever appears on screen. */
    private fun startInAppSpeechRecognition(speechLanguage: String, useOnDevice: Boolean) {
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
        val onDevice = useOnDevice &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        val recognizer = if (onDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
        speechRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                runCatching { speechRecognizer?.destroy() }
                speechRecognizer = null
                speechRecognizerRetryUsed = false
                if (text != null) jarvisViewModel.handleUserText(text)
                restartWakeWordIfEnabled()
            }

            override fun onError(error: Int) {
                runCatching { speechRecognizer?.destroy() }
                speechRecognizer = null
                if (onDevice && !speechRecognizerRetryUsed) {
                    speechRecognizerRetryUsed = true
                    startInAppSpeechRecognition(speechLanguage, useOnDevice = false)
                    return
                }
                speechRecognizerRetryUsed = false
                Log.w(TAG, "In-app speech recognition failed with error $error")
                restartWakeWordIfEnabled()
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLanguage)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, speechLanguage)
        }
        runCatching { recognizer.startListening(intent) }.onFailure { error ->
            Log.e(TAG, "Android speech recognizer is unavailable", error)
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
            restartWakeWordIfEnabled()
        }
    }

    private fun restartWakeWordIfEnabled() {
        val enabled = CapabilitySettings.isEnabled(this, JarvisCapability.OFFLINE_WAKE)
        val microphoneGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!enabled || !microphoneGranted) return
        mainHandler.postDelayed({
            if (!isFinishing && !isDestroyed &&
                CapabilitySettings.isEnabled(this, JarvisCapability.OFFLINE_WAKE)
            ) {
                runCatching { WakeWordService.start(this) }
                    .onFailure { Log.e(TAG, "Could not restart offline wake listener", it) }
            }
        }, RESTART_WAKE_LISTENER_DELAY_MS)
    }

    companion object {
        private const val TAG = "DroidCraftActivity"
        private const val WAKE_TO_COMMAND_DELAY_MS = 700L
        private const val RESTART_WAKE_LISTENER_DELAY_MS = 700L
    }
}
