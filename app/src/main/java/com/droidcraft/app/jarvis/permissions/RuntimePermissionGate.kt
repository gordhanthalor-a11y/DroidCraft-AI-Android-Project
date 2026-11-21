package com.droidcraft.app.jarvis.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** On-demand runtime permission request bridge used only after an explicit user tool request. */
object RuntimePermissionGate {
    private val pending = ConcurrentHashMap<String, kotlinx.coroutines.CancellableContinuation<Boolean>>()

    private val explanations = mapOf(
        Manifest.permission.READ_CONTACTS to
            "Jarvis needs this only to look up the contact name you just asked about. The name and number are not read for other commands.",
        Manifest.permission.READ_CALENDAR to
            "Jarvis needs this only to read the calendar events in the date range you requested.",
        Manifest.permission.POST_NOTIFICATIONS to
            "Android needs this permission so Jarvis can show the reminder notification you requested.",
        Manifest.permission.CAMERA to
            "Android requires camera permission for the flashlight control. Jarvis will not take photos or record video."
    )

    suspend fun request(context: Context, permission: String): Boolean {
        require(permission in explanations) { "This permission is not available through the on-demand request screen." }
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) return true
        val token = UUID.randomUUID().toString()
        return withTimeoutOrNull(90_000L) {
            suspendCancellableCoroutine { continuation ->
                pending[token] = continuation
                continuation.invokeOnCancellation { pending.remove(token) }
                val intent = Intent(context, JarvisRuntimePermissionActivity::class.java)
                    .putExtra(EXTRA_TOKEN, token)
                    .putExtra(EXTRA_PERMISSION, permission)
                    .putExtra(EXTRA_EXPLANATION, explanations.getValue(permission))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
                    .onFailure { complete(token, false) }
            }
        } ?: false
    }

    internal fun complete(token: String, granted: Boolean) {
        pending.remove(token)?.let { continuation ->
            if (continuation.isActive) continuation.resume(granted)
        }
    }

    internal const val EXTRA_TOKEN = "permission_token"
    internal const val EXTRA_PERMISSION = "permission_name"
    internal const val EXTRA_EXPLANATION = "permission_explanation"
}

/** Small, transparent activity that explains the request before showing Android's permission dialog. */
class JarvisRuntimePermissionActivity : Activity() {
    private var token: String = ""
    private var permission: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = intent.getStringExtra(RuntimePermissionGate.EXTRA_TOKEN).orEmpty()
        permission = intent.getStringExtra(RuntimePermissionGate.EXTRA_PERMISSION).orEmpty()
        if (token.isBlank() || permission !in setOf(
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.CAMERA
            )
        ) {
            finishRequest(false)
            return
        }
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            finishRequest(true)
            return
        }
        showExplanation(intent.getStringExtra(RuntimePermissionGate.EXTRA_EXPLANATION).orEmpty())
    }

    private fun showExplanation(explanation: String) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(android.graphics.Color.rgb(8, 20, 33))
        }
        panel.addView(TextView(this).apply {
            text = "Allow Jarvis for this request?"
            textSize = 20f
            setTextColor(android.graphics.Color.WHITE)
        }, matchWidth())
        panel.addView(TextView(this).apply {
            text = explanation
            textSize = 15f
            setTextColor(android.graphics.Color.rgb(210, 225, 232))
            setPadding(0, dp(14), 0, dp(20))
        }, matchWidth())
        panel.addView(Button(this).apply {
            text = "Continue to Android permission"
            setOnClickListener { requestPermissions(arrayOf(permission), REQUEST_CODE) }
        }, matchWidth())
        panel.addView(Button(this).apply {
            text = "Not now"
            setOnClickListener { finishRequest(false) }
        }, matchWidth())
        setContentView(panel)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE) return
        val granted = permissions.firstOrNull() == permission &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        finishRequest(granted)
    }

    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun finishRequest(granted: Boolean) {
        if (token.isNotBlank()) RuntimePermissionGate.complete(token, granted)
        finish()
    }

    companion object { private const val REQUEST_CODE = 7043 }
}
