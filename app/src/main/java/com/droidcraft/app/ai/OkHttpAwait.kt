package com.droidcraft.app.ai

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A fully buffered OkHttp response; the underlying socket is closed before this is returned. */
internal data class HttpResponsePayload(
    val code: Int,
    val message: String,
    val body: ByteArray,
    val isSuccessful: Boolean
)

/** Await an OkHttp response without leaving requests running after their coroutine is cancelled. */
internal suspend fun Call.awaitPayload(): HttpResponsePayload = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }

        override fun onResponse(call: Call, response: Response) {
            val payload = try {
                response.use {
                    HttpResponsePayload(
                        code = it.code,
                        message = it.message,
                        body = it.body?.bytes() ?: ByteArray(0),
                        isSuccessful = it.isSuccessful
                    )
                }
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
                return
            }
            if (continuation.isActive) continuation.resume(payload)
        }
    })
}
