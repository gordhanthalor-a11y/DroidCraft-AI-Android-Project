package com.droidcraft.app.social.api

import android.content.Context
import android.util.Log
import com.droidcraft.app.social.auth.OAuthManager
import com.droidcraft.app.social.data.PostCaptionEntity
import com.droidcraft.app.social.data.PostQueueEntity
import com.droidcraft.app.social.data.SocialAccountEntity
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Instagram Graph API publisher — Reels/Feed.
 * Flow: ig_media container बनाओ → creation_id → media_publish.
 */
object InstagramPublisher {

    private const val TAG = "InstagramPublisher"
    private const val GRAPH = "https://graph.facebook.com/v21.0"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class Result(val success: Boolean, val externalId: String?, val error: String?)

    suspend fun publish(
        context: Context,
        post: PostQueueEntity,
        account: SocialAccountEntity,
        caption: PostCaptionEntity?
    ): Result {
        val token = OAuthManager.validAccessToken(context, account)
        val igUserId = account.accountId
        if (igUserId.isBlank() || igUserId == "meta") {
            return Result(false, null, "Instagram user ID नहीं मिली — account दोबारा connect करो.")
        }
        val captionText = caption?.caption ?: post.description

        // 1) container बनाओ (video_url के लिए publicly reachable URL चाहिए;
        //    local file को पहले relay/server या किसी CDN पर रखना होता है — यहाँ
        //    server-sync mode में file URL relay से मिलता है)
        val videoUrl = post.videoPath.takeIf { it.startsWith("http") }
            ?: return Result(false, null, "Instagram Graph API को publicly reachable video URL चाहिए. Server relay use करो (SOCIAL_SETUP.md §F).")

        val containerUrl = "$GRAPH/$igUserId/media"
        val form = FormBody.Builder()
            .add("media_type", if (post.isShort) "REELS" else "VIDEO")
            .add("video_url", videoUrl)
            .add("caption", captionText)
            .add("access_token", token)
            .build()
        val createResp = client.newCall(
            Request.Builder().url(containerUrl).post(form).build()
        ).execute()
        val createJson = JSONObject(createResp.body?.string().orEmpty())
        if (!createResp.isSuccessful) {
            return Result(false, null, "IG container fail: ${createJson.optJSONObject("error")?.optString("message") ?: createJson.toString()}")
        }
        val creationId = createJson.optString("id")
        if (creationId.isBlank()) {
            return Result(false, null, "IG creation_id नहीं मिला.")
        }

        // 2) publish
        val publishUrl = "$GRAPH/$igUserId/media_publish"
        val publishForm = FormBody.Builder()
            .add("creation_id", creationId)
            .add("access_token", token)
            .build()
        val pubResp = client.newCall(
            Request.Builder().url(publishUrl).post(publishForm).build()
        ).execute()
        val pubJson = JSONObject(pubResp.body?.string().orEmpty())
        if (!pubResp.isSuccessful) {
            return Result(false, null, "IG publish fail: ${pubJson.optJSONObject("error")?.optString("message") ?: pubJson.toString()}")
        }
        val mediaId = pubJson.optString("id").ifBlank { creationId }

        // 3) status verify
        val status = checkStatus(token, mediaId)
        return Result(true, mediaId, null).also {
            Log.i(TAG, "IG published: $mediaId status=$status")
        }
    }

    private fun checkStatus(token: String, mediaId: String): String = runCatching {
        val url = "$GRAPH/$mediaId?fields=status_code&access_token=$token"
        val resp = client.newCall(Request.Builder().url(url).build()).execute()
        JSONObject(resp.body?.string().orEmpty()).optString("status_code", "UNKNOWN")
    }.getOrDefault("UNKNOWN")
}
