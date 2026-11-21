package com.droidcraft.app.social.api

import android.content.Context
import android.net.Uri
import android.util.Log
import com.droidcraft.app.social.auth.OAuthManager
import com.droidcraft.app.social.data.PostCaptionEntity
import com.droidcraft.app.social.data.PublishResult
import com.droidcraft.app.social.data.PostQueueEntity
import com.droidcraft.app.social.data.SocialAccountEntity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Facebook Graph API publisher — Page video upload (/page/videos).
 * Page access token की ज़रूरत; लंबा token (60 din) app में store होता है.
 */
object FacebookPublisher {

    private const val TAG = "FacebookPublisher"
    private const val GRAPH = "https://graph.facebook.com/v21.0"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    suspend fun publish(
        context: Context,
        post: PostQueueEntity,
        account: SocialAccountEntity,
        caption: PostCaptionEntity?,
        onProgress: (step: String, progress: Int?) -> Unit = { _, _ -> }
    ): PublishResult {
        onProgress("Authenticating Facebook Page", null)
        val token = OAuthManager.validAccessToken(context, account)
        val pageId = account.accountId
        if (pageId.isBlank() || pageId == "meta") {
            return PublishResult(false, null, "Facebook Page ID नहीं मिली — account दोबारा connect करो.")
        }
        val file = File(post.videoPath)
        if (!file.exists()) {
            return PublishResult(false, null, "Video file nahi mili: ${post.videoPath}")
        }

        val url = Uri.parse("$GRAPH/$pageId/videos").buildUpon()
            .appendQueryParameter("access_token", token)
            .appendQueryParameter("description", caption?.caption ?: post.description)
            .appendQueryParameter("title", caption?.title ?: post.title)
            .build().toString()

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "source",
                file.name,
                file.asRequestBody("video/mp4".toMediaType())
            )
            .build()

        onProgress("Uploading video to Facebook Page", null)
        val resp = client.newCall(
            Request.Builder().url(url).post(body).build()
        ).execute()
        val json = JSONObject(resp.body?.string().orEmpty())
        if (!resp.isSuccessful) {
            return PublishResult(false, null, "FB publish fail: ${json.optJSONObject("error")?.optString("message") ?: json.toString()}")
        }
        val videoId = json.optString("id").ifBlank { null }
        onProgress("Facebook accepted the video", null)
        Log.i(TAG, "FB published: $videoId")
        return PublishResult(true, videoId, null)
    }
}
