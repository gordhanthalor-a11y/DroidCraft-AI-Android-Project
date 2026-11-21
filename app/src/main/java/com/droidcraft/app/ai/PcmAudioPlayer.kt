package com.droidcraft.app.ai

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.AudioTrack.WRITE_BLOCKING
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/** Actual short-window PCM playback level shared with the animated Jarvis orb. */
object AudioOutputLevel {
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()
    internal fun update(value: Float) { _level.value = value.coerceIn(0f, 1f) }
}

/** Single-threaded PCM output queue shared by Gemini-generated and Gemini Live audio. */
internal class PcmAudioPlayer {
    private val closed = AtomicBoolean(false)
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(),
        ThreadFactory { runnable -> Thread(runnable, "droidcraft-live-audio").apply { isDaemon = true } }
    )

    @Volatile
    private var track: AudioTrack? = null

    private val playbackGeneration = AtomicLong(0)

    fun enqueue(
        pcm: ByteArray,
        shouldPlay: () -> Boolean = { true },
        onPlaybackStart: (() -> Unit)? = null,
        afterPlayback: (() -> Unit)? = null
    ) {
        if (closed.get()) return
        val generation = playbackGeneration.get()
        val isCurrent = {
            !closed.get() && generation == playbackGeneration.get() &&
                runCatching(shouldPlay).getOrDefault(false)
        }
        runCatching {
            executor.execute {
                try {
                    if (isCurrent() && pcm.isNotEmpty()) {
                        runCatching { onPlaybackStart?.invoke() }
                        val output = getOrCreateTrack()
                        var offset = 0
                        while (isCurrent() && offset < pcm.size) {
                            val count = minOf(1_920, pcm.size - offset) // 40 ms at 24 kHz mono PCM16
                            AudioOutputLevel.update(rmsLevel(pcm, offset, count))
                            val written = output.write(pcm, offset, count, WRITE_BLOCKING)
                            if (written <= 0) break
                            offset += written
                        }
                    }
                } catch (_: Exception) {
                    // Keep the live session alive if this device cannot initialize or write PCM audio.
                } finally {
                    if (isCurrent()) {
                        AudioOutputLevel.update(0f)
                        runCatching { afterPlayback?.invoke() }
                    }
                }
            }
        }
    }

    /** Drops queued audio and stops any chunk currently playing after a server-side interruption. */
    fun flush() {
        if (closed.get()) return
        playbackGeneration.incrementAndGet()
        AudioOutputLevel.update(0f)
        executor.queue.clear()
        runCatching {
            track?.let { output ->
                output.pause()
                output.flush()
                output.play()
            }
        }
    }

    /** Queues a callback after any audio already submitted has played. */
    fun afterQueuedAudio(block: () -> Unit) {
        if (closed.get()) return
        val generation = playbackGeneration.get()
        runCatching {
            executor.execute {
                if (!closed.get() && generation == playbackGeneration.get()) block()
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        playbackGeneration.incrementAndGet()
        AudioOutputLevel.update(0f)
        executor.queue.clear()
        runCatching {
            executor.execute {
                runCatching { track?.pause() }
                runCatching { track?.flush() }
                runCatching { track?.release() }
                track = null
            }
            executor.shutdown()
        }
    }

    private fun rmsLevel(pcm: ByteArray, offset: Int, byteCount: Int): Float {
        val end = offset + byteCount - 1
        var sumSquares = 0.0
        var samples = 0
        var index = offset
        while (index < end) {
            val littleEndian = (pcm[index].toInt() and 0xFF) or (pcm[index + 1].toInt() shl 8)
            val normalized = littleEndian.toShort().toInt() / 32_768.0
            sumSquares += normalized * normalized
            samples++
            index += 2
        }
        if (samples == 0) return 0f
        return (sqrt(sumSquares / samples) * 3.2).toFloat().coerceIn(0f, 1f)
    }

    private fun getOrCreateTrack(): AudioTrack {
        track?.let { return it }
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2_048)
        val created = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer * 2, 8_192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        check(created.state == AudioTrack.STATE_INITIALIZED) { "Could not start audio output." }
        created.play()
        track = created
        return created
    }

    companion object {
        const val SAMPLE_RATE = 24_000
    }
}
