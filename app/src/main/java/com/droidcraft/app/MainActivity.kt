package com.droidcraft.app

import android.content.Intent
import android.os.Bundle
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
import com.droidcraft.app.ui.DroidCraftNavHost
import com.droidcraft.app.ui.theme.DroidCraftTheme
import com.droidcraft.app.viewmodel.YouTubeAutomationViewModel
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: YouTubeAutomationViewModel by viewModels()

    // Android Speech-to-Text Contract for Voice Commands in Hindi & English
    val speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            spoken?.let { text ->
                Log.i(TAG, "Voice recognized: $text")
                viewModel.sendAssistantMessage(text)
            }
        }
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
                        onTriggerVoiceInput = { launchVoiceRecognizer() }
                    )
                }
            }
        }
    }

    fun launchVoiceRecognizer() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_ADDITIONAL_LANGUAGES, arrayOf("en-US", "hi-IN"))
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak command in Hindi or English...")
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Speech recognizer unavailable on this device", e)
        }
    }

    companion object {
        private const val TAG = "DroidCraftActivity"
    }
}