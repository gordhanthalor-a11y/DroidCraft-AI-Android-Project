package com.droidcraft.app.social.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.droidcraft.app.social.ai.CaptionGenerator
import com.droidcraft.app.social.api.FacebookPublisher
import com.droidcraft.app.social.api.InstagramPublisher
import com.droidcraft.app.social.api.YouTubePublisher
import com.droidcraft.app.data.local.AppDatabase
import com.droidcraft.app.social.data.PublishHistoryEntity
import com.droidcraft.app.social.data.PublishResult
import com.droidcraft.app.social.data.SocialPlatform
import com.droidcraft.app.social.data.SocialStatus

/**
 * Publish worker — exact time पर chalta है:
 * upload → publish → verify → history. Fail हो तो retryCount++ + exponential backoff.
 */
class PublishWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_POST_ID = "post_id"
        private const val TAG = "PublishWorker"
        private const val MAX_RETRY = 5
    }

    override suspend fun doWork(): Result {
        val postId = inputData.getString(KEY_POST_ID) ?: return Result.failure()
        val db = AppDatabase.getDatabase(applicationContext)
        val post = db.postQueueDao().getById(postId) ?: return Result.failure()
        if (post.status == SocialStatus.PUBLISHED || post.status == SocialStatus.CANCELLED) {
            return Result.success()
        }

        Log.i(TAG, "publish start: ${post.platform}/${post.id} retry=${post.retryCount}")
        db.postQueueDao().markUploading(postId)

        val account = db.socialAccountDao().getActive(post.platform)
        if (account == null) {
            return fail(db, post, "Account connected नहीं है — Social Hub से connect करो.")
        }

        val caption = db.postCaptionDao().get(postId, post.platform)
        val result = when (post.platform) {
            SocialPlatform.YOUTUBE -> YouTubePublisher.publish(applicationContext, post, account, caption)
            SocialPlatform.INSTAGRAM -> InstagramPublisher.publish(applicationContext, post, account, caption)
            SocialPlatform.FACEBOOK -> FacebookPublisher.publish(applicationContext, post, account, caption)
            else -> PublishResult(false, null, "Unknown platform")
        }

        if (result.success && result.externalId != null) {
            db.postQueueDao().markPublished(postId, result.externalId, System.currentTimeMillis())
            db.publishHistoryDao().insert(
                PublishHistoryEntity(
                    postId = postId,
                    platform = post.platform,
                    attemptAt = System.currentTimeMillis(),
                    success = true,
                    externalId = result.externalId,
                    errorCode = null,
                    errorMessage = null
                )
            )
            Log.i(TAG, "published ✅ ${post.platform}/${result.externalId}")
            return Result.success()
        }

        val error = result.error ?: "unknown error"
        db.publishHistoryDao().insert(
            PublishHistoryEntity(
                postId = postId,
                platform = post.platform,
                attemptAt = System.currentTimeMillis(),
                success = false,
                externalId = null,
                errorCode = "UPLOAD_FAIL",
                errorMessage = error
            )
        )

        val retry = post.retryCount + 1
        return if (retry <= MAX_RETRY) {
            // exponential backoff: 2^retry मिनट
            val delayMs = (1L shl (retry - 1)) * 60_000L
            db.postQueueDao().markFailed(
                postId, SocialStatus.FAILED, "retry $retry/$MAX_RETRY: $error"
            )
            db.postQueueDao().reschedule(postId, System.currentTimeMillis() + delayMs)
            // अगली attempt के लिए Worker दोबारा enqueue
            val request = androidx.work.OneTimeWorkRequestBuilder<PublishWorker>()
                .setInitialDelay(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .setInputData(androidx.work.workDataOf(KEY_POST_ID to postId))
                .build()
            androidx.work.WorkManager.getInstance(applicationContext).enqueue(request)
            Result.retry()
        } else {
            db.postQueueDao().markFailed(postId, SocialStatus.FAILED, "max retry: $error")
            Result.failure()
        }
    }

    private suspend fun fail(
        db: com.droidcraft.app.data.local.AppDatabase,
        post: com.droidcraft.app.social.data.PostQueueEntity,
        message: String
    ): Result {
        db.postQueueDao().markFailed(post.id, SocialStatus.FAILED, message)
        db.publishHistoryDao().insert(
            PublishHistoryEntity(
                postId = post.id,
                platform = post.platform,
                attemptAt = System.currentTimeMillis(),
                success = false,
                externalId = null,
                errorCode = "NO_ACCOUNT",
                errorMessage = message
            )
        )
        return Result.failure()
    }
}
