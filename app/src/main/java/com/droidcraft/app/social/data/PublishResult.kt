package com.droidcraft.app.social.data

/** हर publisher का common result (कोई platform-specific nested class नहीं). */
data class PublishResult(
    val success: Boolean,
    val externalId: String?,
    val error: String?
)
