package com.droidcraft.app.jarvis.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.droidcraft.app.MainActivity

/** Foreground service for the fully on-device, Hindi + English Vosk wake-word listener. */
class WakeWordService : Service() {

    @Volatile
    private var listening = false

    @Volatile
    private var detachedNotification = false

    private var wakeListener: VoskWakeWordListener? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (listening) return START_STICKY
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }

        listening = true
        detachedNotification = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("Preparing offline Hindi + Indian English models…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("Preparing offline models…"))
        }

        acquireWakeLock()
        wakeListener = VoskWakeWordListener(
            context = this,
            onStatus = ::updateStatus,
            onWakeWord = { mainThread { onWakeWordDetected() } },
            onFailure = { failure -> mainThread { onListenerFailure(failure) } }
        ).also(VoskWakeWordListener::start)
        return START_STICKY
    }

    private fun updateStatus(message: String) {
        if (!listening) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun onWakeWordDetected() {
        if (!listening) return
        listening = false
        wakeListener?.stop()
        wakeListener = null
        sendBroadcast(Intent(ACTION_WAKE_DETECTED).setPackage(packageName))
        showFinalStatus("‘Hey Jarvis’ heard offline. Tap to speak.", openForVoice = true)
    }

    private fun onListenerFailure(failure: Throwable) {
        if (!listening) return
        Log.e(TAG, "Offline Vosk wake listener stopped", failure)
        listening = false
        wakeListener?.stop()
        wakeListener = null
        val detail = failure.message?.takeIf { it.isNotBlank() }
        val message = if (detail == null) {
            "Could not start offline wake listening. Check storage and try again."
        } else {
            "Offline wake listening stopped: ${detail.take(140)}"
        }
        showFinalStatus(message)
    }

    private fun showFinalStatus(message: String, openForVoice: Boolean = false) {
        detachedNotification = true
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(message, ongoing = false, openForVoice = openForVoice))
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun mainThread(action: () -> Unit) {
        android.os.Handler(mainLooper).post(action)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:OfflineWakeWord")
            .also { it.acquire() }
    }

    override fun onDestroy() {
        listening = false
        wakeListener?.stop()
        wakeListener = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        if (!detachedNotification) stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(
        text: String,
        ongoing: Boolean = true,
        openForVoice: Boolean = false
    ): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Jarvis offline wake listening",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val stopIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            if (openForVoice) action = ACTION_OPEN_AFTER_WAKE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            if (openForVoice) WAKE_REQUEST_CODE else MAIN_REQUEST_CODE,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (openForVoice) "Jarvis is ready" else "Jarvis offline wake listener")
            .setContentText(text)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setSilent(true)
            .setContentIntent(contentIntent)
            .apply {
                if (ongoing) addAction(0, "Stop listening", stopIntent)
                if (openForVoice) addAction(android.R.drawable.ic_btn_speak_now, "Speak now", contentIntent)
            }
            .build()
    }

    companion object {
        const val ACTION_START = "com.droidcraft.app.action.START_WAKE_LISTENER"
        const val ACTION_STOP = "com.droidcraft.app.action.STOP_WAKE_LISTENER"
        const val ACTION_WAKE_DETECTED = "com.droidcraft.app.action.WAKE_DETECTED"
        const val ACTION_OPEN_AFTER_WAKE = "com.droidcraft.app.action.OPEN_AFTER_WAKE"
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "jarvis_wake_word"
        private const val NOTIFICATION_ID = 41
        private const val STOP_REQUEST_CODE = 0
        private const val MAIN_REQUEST_CODE = 1
        private const val WAKE_REQUEST_CODE = 2

        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.applicationContext.startForegroundService(intent)
            } else {
                context.applicationContext.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(Intent(context, WakeWordService::class.java))
        }

        fun dismissWakeNotification(context: Context) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIFICATION_ID)
        }
    }
}
