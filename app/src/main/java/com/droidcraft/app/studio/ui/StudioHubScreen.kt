package com.droidcraft.app.studio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Creator Studio — Phase 1+2: Awaaz Banao (Voice Studio), Upload QC,
 * Dubbing Studio, Clipper। Phase 3 में Script Writer, Thumbnail, Channel Manager जुड़ेंगे।
 */
@Composable
fun StudioHubScreen() {
    var tab by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Awaaz") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Upload QC") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Dubbing") })
            Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("Clipper") })
        }
        when (tab) {
            0 -> VoiceStudioScreen()
            1 -> UploadHelperScreen()
            2 -> DubbingScreen()
            3 -> ClipperScreen()
        }
    }
}
