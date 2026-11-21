package com.droidcraft.app

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
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
import com.droidcraft.app.jarvis.JarvisViewModel
import com.droidcraft.app.jarvis.a11y.ScreenCaptureCoordinator
import com.droidcraft.app.jarvis.overlay.JarvisBubbleController
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

    override fun onDestroy() {
        ScreenCaptureCoordinator.detach()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenJarvisIntent(intent)
    }

    private fun handleOpenJarvisIntent(intent: Intent?) {
        if (intent?.action == JarvisBubbleController.ACTION_OPEN_HOME) {
            JarvisBubbleController.requestOpenHome()
            intent.action = null
        }
    }
}
