package com.droidcraft.app.social.auth

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.droidcraft.app.data.local.AppDatabase
import com.droidcraft.app.social.data.SocialPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * OAuth redirect receiver — browser से code आने पर यह activity खुलती है,
 * code को token से बदलकर account save करती है.
 */
class OAuthRedirectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri: Uri? = intent?.data
        if (uri == null) {
            finish()
            return
        }

        val prefs = getSharedPreferences("jarvis_social_oauth", MODE_PRIVATE)
        val expectedState = prefs.getString("oauth_state", null)
        val platform = prefs.getString("oauth_platform", SocialPlatform.YOUTUBE)!!
        val returnedState = uri.getQueryParameter("state")
        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")

        if (error != null) {
            Toast.makeText(this, "OAuth fail: $error", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (returnedState != expectedState) {
            Toast.makeText(this, "Security: state mismatch", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (code.isNullOrBlank()) {
            Toast.makeText(this, "Authorization code नहीं मिला", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val creds = SocialCredentials.get(this)
        val redirect = "https://localhost/oauth2callback"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            runCatching {
                if (platform == SocialPlatform.YOUTUBE) {
                    OAuthManager.exchangeGoogleCode(
                        this@OAuthRedirectActivity,
                        OAuthManager.GoogleCreds(creds.googleClientId, creds.googleClientSecret),
                        redirect, code
                    )
                } else {
                    OAuthManager.exchangeMetaCode(
                        this@OAuthRedirectActivity,
                        OAuthManager.MetaCreds(creds.metaClientId, creds.metaClientSecret),
                        redirect, code
                    )
                }
            }.onSuccess {
                Toast.makeText(this@OAuthRedirectActivity, "${it.accountName} connect ho gaya ✅", Toast.LENGTH_LONG).show()
                prefs.edit().clear().apply()
            }.onFailure {
                Log.e("OAuthRedirect", "exchange fail", it)
                Toast.makeText(this@OAuthRedirectActivity, "Token exchange fail: ${it.message}", Toast.LENGTH_LONG).show()
            }
            runOnUiThread { finish() }
        }
    }
}
