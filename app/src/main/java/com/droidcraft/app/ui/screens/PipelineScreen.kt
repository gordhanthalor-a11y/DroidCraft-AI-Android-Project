package com.droidcraft.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droidcraft.app.model.PipelineStage
import com.droidcraft.app.model.VideoItem
import com.droidcraft.app.viewmodel.YouTubeAutomationViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PipelineScreen(viewModel: YouTubeAutomationViewModel) {
    val videos by viewModel.videos.collectAsState()
    var selectedStageFilter by remember { mutableStateOf<PipelineStage?>(null) }

    val stages = PipelineStage.values()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Video Automation Pipeline", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text("8-Stage Verification Queue", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Horizontal Pipeline Stage Selector Bar
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp, horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedStageFilter == null,
                        onClick = { selectedStageFilter = null },
                        label = { Text("All (${videos.size})") }
                    )
                }
                items(stages) { stage ->
                    val count = videos.count { it.stage == stage }
                    FilterChip(
                        selected = selectedStageFilter == stage,
                        onClick = { selectedStageFilter = stage },
                        label = { Text("${stage.displayName} ($count)") }
                    )
                }
            }

            // Video Cards Queue
            val filteredVideos = if (selectedStageFilter != null) {
                videos.filter { it.stage == selectedStageFilter }
            } else videos

            if (filteredVideos.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No videos in this stage.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredVideos) { video ->
                        VideoQueueCard(
                            video = video,
                            onApprove = { viewModel.approveVideo(video.id) },
                            onReject = { viewModel.rejectVideo(video.id, "Rejected by creator") },
                            onRetry = { viewModel.approveVideo(video.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun VideoQueueCard(
    video: VideoItem,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onRetry: () -> Unit
) {
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = video.stage.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = video.duration,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(text = video.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)

            Spacer(modifier = Modifier.height(8.dp))

            // Action Buttons based on stage
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (video.stage == PipelineStage.READY_TO_UPLOAD) {
                    Button(
                        onClick = onApprove,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Approve & Schedule")
                    }
                    OutlinedButton(
                        onClick = onReject,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Reject")
                    }
                } else if (video.stage == PipelineStage.FAILED) {
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Retry Upload")
                    }
                }
            }
        }
    }
}