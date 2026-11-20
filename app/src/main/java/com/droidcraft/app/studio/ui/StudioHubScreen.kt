package com.droidcraft.app.studio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Creator Studio — पूरा Phase 1-3: Awaaz Banao, Upload QC, Dubbing, Clipper,
 * Script Writer, Thumbnail, Channels/Campaigns, Daily (jobs+notes)।
 */
@Composable
fun StudioHubScreen() {
    var tab by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Awaaz") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("QC") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Dubbing") })
            Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("Clipper") })
            Tab(selected = tab == 4, onClick = { tab = 4 }, text = { Text("Script") })
            Tab(selected = tab == 5, onClick = { tab = 5 }, text = { Text("Thumbnail") })
            Tab(selected = tab == 6, onClick = { tab = 6 }, text = { Text("Channels") })
            Tab(selected = tab == 7, onClick = { tab = 7 }, text = { Text("Daily") })
        }
        when (tab) {
            0 -> VoiceStudioScreen()
            1 -> UploadHelperScreen()
            2 -> DubbingScreen()
            3 -> ClipperScreen()
            4 -> ScriptWriterScreen()
            5 -> ThumbnailTextScreen()
            6 -> ManagerScreen()
            7 -> DailyScreen()
        }
    }
}
