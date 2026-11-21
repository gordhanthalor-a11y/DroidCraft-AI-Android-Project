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

/** Single-threaded PCM output queue shared by Gemini's live audio and ElevenLabs PCM audio. */
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
                        val output = getOrCreateTrack()
                        var offset = 0
                        while (isCurrent() && offset < pcm.size) {
                            val written = output.write(pcm, offset, pcm.size - offset, WRITE_BLOCKING)
                            if (written <= 0) break
                            offset += written
                        }
                    }
                } catch (_: Exception) {
                    // Keep the live session alive if this device cannot initialize or write PCM audio.
                } finally {
                    if (isCurrent()) runCatching { afterPlayback?.invoke() }
                }
            }
        }
    }

    /** Drops queued audio and stops any chunk currently playing after a server-side interruption. */
    fun flush() {
        if (closed.get()) return
        playbackGeneration.incrementAndGet()
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
