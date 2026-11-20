package com.droidcraft.app.jarvis.live

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Gemini Live audio layer.
 * Mic: 16kHz mono PCM (Gemini Live ka input format).
 * Speaker: 24kHz mono PCM (server ka output format).
 */
object LiveAudio {

    const val SAMPLE_RATE_IN = 16000
    const val SAMPLE_RATE_OUT = 24000
    private const val CHUNK_BYTES = 1280 // 40ms @ 16kHz mono 16-bit
}

/** Microphone se 16kHz PCM chunks padhta hai. */
class LiveMic {

    private var record: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile
    private var running = false

    var onPcm: ((ByteArray) -> Unit)? = null

    fun start() {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(
            LiveAudio.SAMPLE_RATE_IN, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, LiveAudio.CHUNK_BYTES * 2)
        val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION // hardware echo cancellation
        } else {
            MediaRecorder.AudioSource.MIC
        }
        val rec = AudioRecord(
            source,
            LiveAudio.SAMPLE_RATE_IN,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return
        }
        record = rec
        rec.startRecording()
        running = true
        worker = thread(start = true, name = "JarvisMic") {
            val buf = ByteArray(LiveAudio.CHUNK_BYTES)
            while (running) {
                val read = runCatching { rec.read(buf, 0, buf.size) }.getOrDefault(-1)
                if (read > 0) onPcm?.invoke(buf.copyOf(read))
            }
        }
    }

    fun stop() {
        running = false
        runCatching { worker?.join(400) }
        worker = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
    }
}

/** Server ka 24kHz PCM stream sunata hai (low-latency streaming AudioTrack). */
class LiveSpeaker {

    private var track: AudioTrack? = null
    private var worker: Thread? = null
    private val queue = LinkedBlockingQueue<ByteArray>(220)
    @Volatile
    private var running = false

    fun start() {
        if (running) return
        val minBuf = AudioTrack.getMinBufferSize(
            LiveAudio.SAMPLE_RATE_OUT, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val at = AudioTrack(
            AudioManager.STREAM_MUSIC,
            LiveAudio.SAMPLE_RATE_OUT,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, 2400 * 2),
            AudioTrack.MODE_STREAM
        )
        track = at
        at.play()
        running = true
        worker = thread(start = true, name = "JarvisSpeaker") {
            while (running) {
                val chunk = runCatching { queue.poll(200, TimeUnit.MILLISECONDS) }.getOrNull() ?: continue
                runCatching { at.write(chunk, 0, chunk.size) }
            }
        }
    }

    fun write(pcm: ByteArray) {
        if (!running) return
        if (queue.size < 200) queue.offer(pcm)
    }

    /** Barge-in: turant purana audio band karo. */
    fun flush() = queue.clear()

    fun stop() {
        running = false
        runCatching { worker?.join(400) }
        worker = null
        queue.clear()
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
    }
}
