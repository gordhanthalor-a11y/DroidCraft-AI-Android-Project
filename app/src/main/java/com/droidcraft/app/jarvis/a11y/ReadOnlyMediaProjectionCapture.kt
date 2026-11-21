package com.droidcraft.app.jarvis.a11y

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One-frame MediaProjection capture. It exposes pixels only and has no input or gesture path. */
class ReadOnlyMediaProjectionCapture(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    suspend fun capture(resultCode: Int, resultData: Intent): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            var projection: MediaProjection? = null
            var virtualDisplay: VirtualDisplay? = null
            var reader: ImageReader? = null

            fun release() {
                runCatching { reader?.setOnImageAvailableListener(null, null) }
                runCatching { virtualDisplay?.release() }
                runCatching { reader?.close() }
                runCatching { projection?.stop() }
                virtualDisplay = null
                reader = null
                projection = null
            }

            fun fail(error: Throwable) {
                if (!completed.compareAndSet(false, true)) return
                release()
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            fun finish(bytes: ByteArray) {
                if (!completed.compareAndSet(false, true)) return
                release()
                if (continuation.isActive) continuation.resume(bytes)
            }

            continuation.invokeOnCancellation { handler.post { if (completed.compareAndSet(false, true)) release() } }
            handler.post {
                if (!continuation.isActive) return@post
                try {
                    val manager = appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    projection = manager.getMediaProjection(resultCode, resultData)
                        ?: throw IllegalStateException("Android did not grant a MediaProjection session.")

                    val metrics = displayMetrics()
                    val width = metrics.widthPixels.coerceAtLeast(1)
                    val height = metrics.heightPixels.coerceAtLeast(1)
                    val density = metrics.densityDpi.coerceAtLeast(1)
                    reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                    val imageReader = reader ?: throw IllegalStateException("Could not create a screenshot buffer.")

                    projection?.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            fail(IllegalStateException("Android stopped the screen-capture session before a frame arrived."))
                        }
                    }, handler)

                    imageReader.setOnImageAvailableListener({ source ->
                        val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                        try {
                            val plane = image.planes.firstOrNull()
                                ?: throw IllegalStateException("Android returned a screenshot with no pixel plane.")
                            val pixelStride = plane.pixelStride.coerceAtLeast(1)
                            val rowStride = plane.rowStride.coerceAtLeast(pixelStride * width)
                            val paddedWidth = (rowStride / pixelStride).coerceAtLeast(width)
                            val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                            try {
                                padded.copyPixelsFromBuffer(plane.buffer)
                                val cropped = if (paddedWidth == width) padded else Bitmap.createBitmap(padded, 0, 0, width, height)
                                try {
                                    val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(width, height))
                                    val scaled = if (scale < 1f) {
                                        Bitmap.createScaledBitmap(
                                            cropped,
                                            (width * scale).toInt().coerceAtLeast(1),
                                            (height * scale).toInt().coerceAtLeast(1),
                                            true
                                        )
                                    } else cropped
                                    try {
                                        val output = ByteArrayOutputStream()
                                        if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                                            throw IllegalStateException("Android could not encode the screenshot.")
                                        }
                                        finish(output.toByteArray())
                                    } finally {
                                        if (scaled !== cropped) scaled.recycle()
                                    }
                                } finally {
                                    if (cropped !== padded) cropped.recycle()
                                }
                            } finally {
                                padded.recycle()
                            }
                        } catch (error: Throwable) {
                            fail(error)
                        } finally {
                            image.close()
                        }
                    }, handler)

                    virtualDisplay = projection?.createVirtualDisplay(
                        "Jarvis read-only screenshot",
                        width,
                        height,
                        density,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        imageReader.surface,
                        null,
                        handler
                    ) ?: throw IllegalStateException("Android could not create a screenshot display.")
                } catch (error: Throwable) {
                    fail(error)
                }
            }
        }

    @Suppress("DEPRECATION")
    private fun displayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    companion object { private const val MAX_SIDE = 1280 }
}
