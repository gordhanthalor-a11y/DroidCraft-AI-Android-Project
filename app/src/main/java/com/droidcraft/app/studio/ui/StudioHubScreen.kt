package com.droidcraft.app.studio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Creator Studio — Phase 1 के दो हिस्से: Awaaz Banao (Voice Studio) + Upload QC.
 * Phase 2/3 में Dubbing, Clipper, Script Writer, Thumbnail, Channel Manager यहीं जुड़ेंगे।
 */
@Composable
fun StudioHubScreen() {
    var tab by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text("Awaaz Banao") }
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text("Upload QC") }
            )
        }
        when (tab) {
            0 -> VoiceStudioScreen()
            1 -> UploadHelperScreen()
        }
    }
}
