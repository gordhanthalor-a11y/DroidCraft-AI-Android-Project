package com.droidcraft.app.ai

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Speaker-echo gate for Live microphone frames. Android AcousticEchoCanceler remains enabled as
 * the first line of defense; this second layer drops likely playback echo while retaining a
 * clearly louder barge-in signal. Headphone mode bypasses the software gate.
 */
internal class EchoGate(context: Context, mode: EchoControlMode) {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val gateForSpeaker = when (mode) {
        EchoControlMode.SPEAKER -> true
        EchoControlMode.HEADPHONES -> false
        EchoControlMode.AUTO -> !hasHeadphoneOutput()
    }

    @Volatile private var playbackActive = false
    @Volatile private var playbackEndedAtMs = 0L
    @Volatile private var echoFloor = INITIAL_ECHO_FLOOR

    fun onPlaybackStarted() {
        playbackActive = true
    }

    fun onPlaybackStopped() {
        playbackActive = false
        playbackEndedAtMs = SystemClock.elapsedRealtime()
    }

    /** Returns a copied frame. Quiet speaker leakage becomes silence; clear speech passes intact. */
    fun gate(frame: ByteArray, byteCount: Int): ByteArray {
        val count = byteCount.coerceIn(0, frame.size) and -2
        if (!gateForSpeaker || count == 0) return frame.copyOf(count)
        val inTail = SystemClock.elapsedRealtime() - playbackEndedAtMs < TAIL_MS
        if (!playbackActive && !inTail) return frame.copyOf(count)

        val rms = rms(frame, count)
        val threshold = max(MIN_BARGE_IN_RMS, echoFloor * BARGE_IN_RATIO)
        if (rms >= threshold) {
            // A clear user interruption must pass. Do not train the echo floor on speech.
            return frame.copyOf(count)
        }

        // Slowly learn the residual speaker echo/noise floor only from sub-threshold frames.
        echoFloor = (echoFloor * (1f - FLOOR_LEARNING_RATE) + rms * FLOOR_LEARNING_RATE)
            .coerceIn(MIN_ECHO_FLOOR, MAX_ECHO_FLOOR)
        return ByteArray(count)
    }

    private fun hasHeadphoneOutput(): Boolean = runCatching {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            device.type in headphoneTypes()
        }
    }.getOrDefault(false)

    private fun headphoneTypes(): Set<Int> = buildSet {
        add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
        add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        add(AudioDeviceInfo.TYPE_USB_HEADSET)
        if (android.os.Build.VERSION.SDK_INT >= 31) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    private fun rms(pcm: ByteArray, byteCount: Int): Float {
        var sumSquares = 0.0
        var samples = 0
        var index = 0
        while (index + 1 < byteCount) {
            val sample = ((pcm[index].toInt() and 0xff) or (pcm[index + 1].toInt() shl 8)).toShort().toInt()
            val normalized = sample / 32768.0
            sumSquares += normalized * normalized
            samples++
            index += 2
        }
        return if (samples == 0) 0f else sqrt(sumSquares / samples).toFloat()
    }

    private companion object {
        const val TAIL_MS = 300L
        const val MIN_BARGE_IN_RMS = 0.018f
        const val INITIAL_ECHO_FLOOR = 0.006f
        const val MIN_ECHO_FLOOR = 0.002f
        const val MAX_ECHO_FLOOR = 0.12f
        const val BARGE_IN_RATIO = 2.6f
        const val FLOOR_LEARNING_RATE = 0.08f
    }
}
