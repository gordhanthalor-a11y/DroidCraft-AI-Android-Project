package com.droidcraft.app.jarvis.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.droidcraft.app.MainActivity
import com.droidcraft.app.R
import com.droidcraft.app.jarvis.a11y.ReadOnlyMediaProjectionCapture
import com.droidcraft.app.jarvis.a11y.ScreenCaptureCoordinator
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground body process for Jarvis's persistent ball and ongoing microphone session.
 * Accessibility is deliberately a separate, screen-only service and may be revoked independently.
 */
class JarvisBodyService : Service() {
    private var windowManager: WindowManager? = null
    private var bubble: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var receiverRegistered = false
    private var userEnabled = false
    private var microphoneActive = false
    private var captureInProgress = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == JarvisBubbleController.ACTION_STATE_CHANGED) {
                renderState(intent.getStringExtra(JarvisBubbleController.EXTRA_STATE).orEmpty())
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundForBody()
        userEnabled = isBodyEnabled(this)
        registerStateReceiver()
        if (userEnabled && android.provider.Settings.canDrawOverlays(this)) addBubble()
        if (!userEnabled && bubble == null) setEnabled(false)
        renderState(JarvisBubbleController.state(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                userEnabled = false
                setEnabled(false)
                removeBubble()
                stopWhenUnused()
            }
            ACTION_ENABLE -> {
                if (!android.provider.Settings.canDrawOverlays(this)) {
                    userEnabled = false
                    setEnabled(false)
                    stopWhenUnused()
                    return START_NOT_STICKY
                }
                userEnabled = true
                setEnabled(true)
                if (bubble == null) runCatching { addBubble() }
                updateForegroundType()
            }
            ACTION_MICROPHONE_ACTIVE -> {
                microphoneActive = true
                updateForegroundType()
            }
            ACTION_MICROPHONE_INACTIVE -> {
                microphoneActive = false
                updateForegroundType()
                stopWhenUnused()
            }
            ACTION_MEDIA_CAPTURE_FINISHED -> {
                captureInProgress = false
                updateForegroundType()
                stopWhenUnused()
            }
            ACTION_CAPTURE_SCREEN -> {
                // Projection result is consumed by the capture handler; it never issues touch input.
                captureInProgress = true
                updateForegroundType()
                handleProjectionCapture(intent)
            }
            null -> {
                if (userEnabled && bubble == null && android.provider.Settings.canDrawOverlays(this)) {
                    runCatching { addBubble() }
                }
            }
        }
        return if (userEnabled || microphoneActive || captureInProgress) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        setEnabled(false)
        serviceScope.cancel()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(stateReceiver) }
            receiverRegistered = false
        }
        removeBubble()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun addBubble() {
        if (!android.provider.Settings.canDrawOverlays(this) || bubble != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = TextView(this).apply {
            text = "J"
            textSize = 22f
            setTextColor(Color.rgb(3, 15, 20))
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            elevation = dp(10).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(86, 239, 247))
                setStroke(dp(2), Color.argb(210, 230, 181, 90))
            }
            contentDescription = "Jarvis floating assistant, idle. Tap to open the conversation."
        }
        val layout = WindowManager.LayoutParams(
            dp(62), dp(62), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = dp(14)
            y = 0
        }
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        view.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var dragged = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val currentParams = params ?: return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startX = currentParams.x
                        startY = currentParams.y
                        dragged = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (abs(dx) > touchSlop || abs(dy) > touchSlop) dragged = true
                        if (dragged) {
                            currentParams.x = (startX - dx.toInt()).coerceAtLeast(0)
                            currentParams.y = (startY + dy.toInt()).coerceAtLeast(dp(24))
                            runCatching { windowManager?.updateViewLayout(v, currentParams) }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!dragged) openJarvis()
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> return true
                }
                return false
            }
        })
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        wm.addView(view, layout)
        windowManager = wm
        bubble = view
        params = layout
    }

    private fun renderState(rawState: String) {
        val state = rawState.uppercase()
        val (tint, label) = when (state) {
            "LISTENING" -> Color.rgb(60, 230, 190) to "listening"
            "WORKING", "THINKING", "CONNECTING" -> Color.rgb(88, 190, 255) to "working"
            "SPEAKING" -> Color.rgb(245, 185, 82) to "speaking"
            "NEEDS_ATTENTION", "ERROR" -> Color.rgb(245, 112, 92) to "needs attention"
            else -> Color.rgb(86, 239, 247) to "idle"
        }
        bubble?.apply {
            (background as? GradientDrawable)?.setColor(tint)
            contentDescription = "Jarvis floating assistant, $label. Tap to open the conversation."
            invalidate()
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification(label))
    }

    private fun openJarvis() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = JarvisBubbleController.ACTION_OPEN_HOME
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { startActivity(intent) }
    }

    private fun registerStateReceiver() {
        val filter = IntentFilter(JarvisBubbleController.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(stateReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun notification(label: String = "ready"): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, JarvisBodyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Jarvis body is active")
            .setContentText("Assistant status: $label. Tap to open the conversation.")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Turn off ball", stop)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Jarvis assistant", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun startForegroundForBody() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
    }

    private fun updateForegroundType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var types = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (microphoneActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                types = types or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (captureInProgress) {
                types = types or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), types) }
                .onFailure { android.util.Log.w("JarvisBody", "Foreground service type update rejected", it) }
        }
    }

    private fun removeBubble() {
        bubble?.let { view -> runCatching { windowManager?.removeView(view) } }
        bubble = null
        params = null
        if (!microphoneActive && !captureInProgress) windowManager = null
    }

    private fun stopWhenUnused() {
        if (!userEnabled && !microphoneActive && !captureInProgress) stopSelf()
    }

    private fun setEnabled(enabled: Boolean) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** MediaProjection capture is implemented in the screen-capture component; it is read-only. */
    private fun handleProjectionCapture(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode == 0 || resultData == null) {
            ScreenCaptureCoordinator.complete(null, "Android screen capture was not approved; no screenshot was taken.")
            captureInProgress = false
            stopWhenUnused()
            return
        }
        captureScreenReadOnly(resultCode, resultData)
    }

    private fun captureScreenReadOnly(resultCode: Int, resultData: Intent) {
        serviceScope.launch {
            try {
                val bytes = ReadOnlyMediaProjectionCapture(this@JarvisBodyService).capture(resultCode, resultData)
                ScreenCaptureCoordinator.complete(bytes, null)
            } catch (error: Exception) {
                ScreenCaptureCoordinator.complete(
                    null,
                    "Screenshot failed: ${error.message ?: "Android did not provide a frame."}"
                )
            } finally {
                captureInProgress = false
                updateForegroundType()
                stopWhenUnused()
            }
        }
    }

    companion object {
        const val ACTION_STOP = "com.droidcraft.app.action.STOP_JARVIS_BODY"
        const val ACTION_ENABLE = "com.droidcraft.app.action.ENABLE_JARVIS_BODY"
        const val ACTION_MICROPHONE_ACTIVE = "com.droidcraft.app.action.JARVIS_MICROPHONE_ACTIVE"
        const val ACTION_MICROPHONE_INACTIVE = "com.droidcraft.app.action.JARVIS_MICROPHONE_INACTIVE"
        const val ACTION_CAPTURE_SCREEN = "com.droidcraft.app.action.CAPTURE_SCREEN_READ_ONLY"
        const val ACTION_MEDIA_CAPTURE_FINISHED = "com.droidcraft.app.action.MEDIA_CAPTURE_FINISHED"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        private const val CHANNEL_ID = "jarvis_body"
        private const val NOTIFICATION_ID = 7042
        private const val PREFS = "jarvis_overlay"
        private const val KEY_ENABLED = "bubble_enabled"

        fun isBodyEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

        fun startBody(context: Context) {
            val intent = Intent(context, JarvisBodyService::class.java).setAction(ACTION_ENABLE)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stopBody(context: Context) {
            context.startService(Intent(context, JarvisBodyService::class.java).setAction(ACTION_STOP))
        }

        fun microphoneActive(context: Context) {
            val intent = Intent(context, JarvisBodyService::class.java).setAction(ACTION_MICROPHONE_ACTIVE)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun microphoneInactive(context: Context) {
            context.startService(Intent(context, JarvisBodyService::class.java).setAction(ACTION_MICROPHONE_INACTIVE))
        }
    }
}
