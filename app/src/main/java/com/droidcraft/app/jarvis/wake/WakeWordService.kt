package com.droidcraft.app.jarvis.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import com.droidcraft.app.MainActivity
import java.util.Locale

/**
 * "Hey Jarvis" wake-word listener — foreground service.
 * Platform SpeechRecognizer ko loop mein chalata rehta hai; wake phrase mila toh
 * broadcast bhej kar khud band ho jata hai (app phir mic lekar baat shuru karta hai).
 */
class WakeWordService : Service(), RecognitionListener {

    private var recognizer: SpeechRecognizer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var listening = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, buildNotification())
                }
                acquireWakeLock()
                startListening()
            }
        }
        return START_STICKY
    }

    private fun startListening() {
        if (listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        listening = true
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { rec ->
            rec.setRecognitionListener(this)
            runCatching {
                rec.startListening(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                        )
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    }
                )
            }.onFailure { restart() }
        }
    }

    private fun restart() {
        listening = false
        runCatching { recognizer?.destroy() }
        recognizer = null
        android.os.Handler(mainLooper).postDelayed({ startListening() }, RESTART_DELAY_MS)
    }

    private fun checkWakeWord(results: Bundle?) {
        val phrases = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        if (phrases.any { containsWakeWord(it) }) {
            listening = false
            runCatching { recognizer?.destroy() }
            recognizer = null
            sendBroadcast(
                Intent(ACTION_WAKE_DETECTED).setPackage(packageName)
            )
            stopSelf()
        }
    }

    private fun containsWakeWord(phrase: String): Boolean {
        val text = phrase.lowercase(Locale.ROOT)
        return Regex("\\b(hey\\s+|hello\\s+)?jarvis\\b").containsMatchIn(text) ||
            Regex("\\bjarvis\\s+(wake\\s+up|wakeup|utho|jago)\\b").containsMatchIn(text) ||
            Regex("\\b(arree\\s+)?jarvis\\b").containsMatchIn(text)
    }

    // ---------------- RecognitionListener ----------------

    override fun onResults(results: Bundle?) {
        checkWakeWord(results)
        if (listening) restart()
    }

    override fun onPartialResults(partialResults: Bundle?) {
        checkWakeWord(partialResults)
    }

    override fun onError(error: Int) {
        // ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT normal hain — loop chalta rahe.
        restart()
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    // ---------------- lifecycle ----------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:WakeWord")
            .also { it.acquire(30 * 60 * 1000L) }
    }

    override fun onDestroy() {
        listening = false
        runCatching { recognizer?.destroy() }
        recognizer = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Jarvis background listening",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val contentIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis background mein sun raha hai")
            .setContentText("\"Hey Jarvis\" bolo aur main jawaab dunga")
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, "Band karo", stopIntent)
            .build()
    }

    companion object {
        const val ACTION_START = "com.droidcraft.app.action.START_WAKE_LISTENER"
        const val ACTION_STOP = "com.droidcraft.app.action.STOP_WAKE_LISTENER"
        const val ACTION_WAKE_DETECTED = "com.droidcraft.app.action.WAKE_DETECTED"
        private const val CHANNEL_ID = "jarvis_wake_word"
        private const val NOTIFICATION_ID = 41
        private const val RESTART_DELAY_MS = 350L

        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, WakeWordService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
