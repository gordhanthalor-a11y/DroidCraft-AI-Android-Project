package com.droidcraft.app.model

enum class PipelineStage(val displayName: String) {
    IDEAS("Ideas"),
    SCRIPTS("Scripts"),
    GENERATION("Generation"),
    QUALITY_CHECK("Quality Check"),
    READY_TO_UPLOAD("Ready to Upload"),
    SCHEDULED("Scheduled"),
    PUBLISHED("Published"),
    FAILED("Failed")
}

enum class ApprovalStatus { PENDING, APPROVED, REJECTED }

data class VideoItem(
    val id: String,
    val channelId: String,
    val title: String,
    val description: String,
    val duration: String,
    val stage: PipelineStage,
    val approvalStatus: ApprovalStatus,
    val scheduledTime: String?,
    val youtubeVideoId: String?,
    val uploadProgress: Int,
    val sha256: String,
    val retryCount: Int,
    val lastError: String?
)

data class YouTubeChannel(
    val id: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val isPrimary: Boolean = false
)

data class AssistantRequest(
    val message: String,
    val channelId: String? = null
)

data class AssistantResponse(
    val reply: String,
    val action: String? = null,
    val requiresConfirmation: Boolean = false,
    val confirmationMessage: String? = null,
    val taskTitle: String? = null
)

data class GeneratePackageRequest(
    val topic: String,
    val channelId: String? = null,
    val language: String = "hi"
)

data class GeneratedPackageResponse(
    val title: String,
    val description: String,
    val script: String,
    val tags: List<String> = emptyList()
)

data class VerificationResponse(
    val isVerified: Boolean,
    val youtubeVideoId: String? = null
)

data class ChatMessage(
    val id: String,
    val text: String,
    val isUser: Boolean,
    val action: String? = null
)

data class ConfirmationDialogState(
    val action: String,
    val message: String,
    val payload: String? = null
)
