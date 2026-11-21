package com.droidcraft.app.jarvis.a11y

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import androidx.activity.result.ActivityResult
import com.droidcraft.app.jarvis.overlay.JarvisBodyService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Requests one user-approved, read-only screenshot when the accessibility service is unavailable. */
object ScreenCaptureCoordinator {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val requestContinuation = AtomicReference<Continuation<ByteArray>?>(null)
    @Volatile private var projectionPrompt: (() -> Unit)? = null

    fun attach(launchPrompt: () -> Unit) {
        projectionPrompt = launchPrompt
    }

    fun detach() {
        projectionPrompt = null
    }

    suspend fun capture(): ByteArray {
        JarvisAccessibilityService.instance?.let { service ->
            val fromAccessibility = runCatching { service.captureScreenshotPng() }.getOrNull()
            if (fromAccessibility != null) return fromAccessibility
        }
        return withTimeout(CAPTURE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                if (!requestContinuation.compareAndSet(null, continuation)) {
                    continuation.resumeWithException(IllegalStateException("A screen capture request is already waiting for approval."))
                    return@suspendCancellableCoroutine
                }
                continuation.invokeOnCancellation { requestContinuation.compareAndSet(continuation, null) }
                val launch = projectionPrompt
                if (launch == null) {
                    complete(null, "Screen capture needs a foreground app window. Open Jarvis and request the screenshot again.")
                    return@suspendCancellableCoroutine
                }
                mainHandler.post {
                    runCatching { launch.invoke() }.onFailure {
                        complete(null, "Android could not open the screen-capture consent prompt: ${it.message ?: "unknown error"}")
                    }
                }
            }
        }
    }

    fun onProjectionResult(context: Context, result: ActivityResult) {
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            complete(null, "Screen capture was denied; no screenshot was taken.")
            return
        }
        if (requestContinuation.get()?.isActive != true) return
        val intent = Intent(context, JarvisBodyService::class.java)
            .setAction(JarvisBodyService.ACTION_CAPTURE_SCREEN)
            .putExtra(JarvisBodyService.EXTRA_RESULT_CODE, result.resultCode)
            .putExtra(JarvisBodyService.EXTRA_RESULT_DATA, result.data)
        runCatching {
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }.onFailure {
            complete(null, "The read-only screenshot service could not start: ${it.message ?: "Android refused it."}")
        }
    }

    fun complete(bytes: ByteArray?, error: String?) {
        val continuation = requestContinuation.getAndSet(null) ?: return
        if (!continuation.isActive) return
        runCatching {
            if (bytes != null) continuation.resume(bytes)
            else continuation.resumeWithException(IllegalStateException(error ?: "Android did not return a screenshot."))
        }
    }

    private const val CAPTURE_TIMEOUT_MS = 45_000L
}
