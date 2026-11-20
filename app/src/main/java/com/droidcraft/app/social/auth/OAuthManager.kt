package com.droidcraft.app.social.auth

import android.content.Context
import android.net.Uri
import android.util.Log
import com.droidcraft.app.data.SecureKeyStore
import com.droidcraft.app.data.local.AppDatabase
import com.droidcraft.app.social.data.SocialAccountEntity
import com.droidcraft.app.social.data.SocialPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * OAuth 2.0 manager — official Google / Meta flows.
 * - Authorization URL बनाता है (WebView/Custom Tabs के लिए)
 * - Code → tokens exchange
 * - Access token auto-refresh (जब expire हो)
 * - Tokens encrypted vault में store
 */
object OAuthManager {

    private const val TAG = "OAuthManager"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // ---------------------------------------------------------------- Google (YouTube)

    data class GoogleCreds(val clientId: String, val clientSecret: String)

    fun googleAuthUrl(creds: GoogleCreds, redirectUri: String, state: String): String {
        return Uri.parse("https://accounts.google.com/o/oauth2/v2/auth").buildUpon()
            .appendQueryParameter("client_id", creds.clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", "https://www.googleapis.com/auth/youtube.upload https://www.googleapis.com/auth/youtube.readonly")
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent")
            .appendQueryParameter("state", state)
            .build()
            .toString()
    }

    suspend fun exchangeGoogleCode(
        context: Context,
        creds: GoogleCreds,
        redirectUri: String,
        code: String,
        accountName: String = "YouTube Channel"
    ): SocialAccountEntity = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("code", code)
            .add("client_id", creds.clientId)
            .add("client_secret", creds.clientSecret)
            .add("redirect_uri", redirectUri)
            .add("grant_type", "authorization_code")
            .build()
        val resp = client.newCall(
            Request.Builder().url("https://oauth2.googleapis.com/token").post(body).build()
        ).execute()
        val text = resp.body?.string().orEmpty()
        val json = JSONObject(text)
        if (!resp.isSuccessful) {
            throw IllegalStateException("Google token exchange fail: ${json.optString("error_description", text)}")
        }
        val account = SocialAccountEntity(
            platform = SocialPlatform.YOUTUBE,
            accountId = json.optString("scope").ifBlank { "youtube" },
            accountName = accountName,
            accessToken = TokenVault.encrypt(context, json.getString("access_token")),
            refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() }
                ?.let { TokenVault.encrypt(context, it) },
            expiresAt = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000,
            scopes = json.optString("scope"),
            connectedAt = System.currentTimeMillis()
        )
        AppDatabase.getDatabase(context).socialAccountDao().upsert(account)
        account
    }

    /** Access token वापस लाओ; expire हो तो refresh करके नया लाओ. */
    suspend fun validAccessToken(context: Context, account: SocialAccountEntity): String =
        withContext(Dispatchers.IO) {
            val access = TokenVault.decrypt(context, account.accessToken)
            if (System.currentTimeMillis() < account.expiresAt - 60_000) return@withContext access
            val refresh = account.refreshToken?.let { TokenVault.decrypt(context, it) }
                ?: return@withContext access
            refreshGoogleToken(context, account, refresh) ?: access
        }

    private suspend fun refreshGoogleToken(
        context: Context,
        account: SocialAccountEntity,
        refresh: String
    ): String? = withContext(Dispatchers.IO) {
        val creds = SocialCredentials.get(context)
        val body = FormBody.Builder()
            .add("client_id", creds.googleClientId)
            .add("client_secret", creds.googleClientSecret)
            .add("refresh_token", refresh)
            .add("grant_type", "refresh_token")
            .build()
        runCatching {
            val resp = client.newCall(
                Request.Builder().url("https://oauth2.googleapis.com/token").post(body).build()
            ).execute()
            val json = JSONObject(resp.body?.string().orEmpty())
            if (!resp.isSuccessful) return@runCatching null
            val newAccess = json.getString("access_token")
            val newRefresh = json.optString("refresh_token").takeIf { it.isNotBlank() }
            AppDatabase.getDatabase(context).socialAccountDao().updateTokens(
                id = account.id,
                token = TokenVault.encrypt(context, newAccess),
                refresh = newRefresh?.let { TokenVault.encrypt(context, it) } ?: account.refreshToken,
                expires = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000
            )
            newAccess
        }.getOrElse {
            Log.e(TAG, "google refresh fail", it)
            null
        }
    }

    // ---------------------------------------------------------------- Meta (Instagram / Facebook)

    data class MetaCreds(val clientId: String, val clientSecret: String)

    fun metaAuthUrl(creds: MetaCreds, redirectUri: String, state: String): String {
        return Uri.parse("https://www.facebook.com/v21.0/dialog/oauth").buildUpon()
            .appendQueryParameter("client_id", creds.clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", "instagram_basic,instagram_content_publish,pages_read_engagement,pages_manage_posts")
            .appendQueryParameter("state", state)
            .build()
            .toString()
    }

    suspend fun exchangeMetaCode(
        context: Context,
        creds: MetaCreds,
        redirectUri: String,
        code: String
    ): SocialAccountEntity = withContext(Dispatchers.IO) {
        // 1) short-lived token
        val tokenResp = client.newCall(
            Request.Builder()
                .url(
                    Uri.parse("https://graph.facebook.com/v21.0/oauth/access_token").buildUpon()
                        .appendQueryParameter("client_id", creds.clientId)
                        .appendQueryParameter("client_secret", creds.clientSecret)
                        .appendQueryParameter("redirect_uri", redirectUri)
                        .appendQueryParameter("code", code)
                        .build().toString()
                ).build()
        ).execute()
        val tokenJson = JSONObject(tokenResp.body?.string().orEmpty())
        if (!tokenResp.isSuccessful) {
            throw IllegalStateException("Meta token fail: ${tokenJson.optString("error", tokenJson.toString())}")
        }
        val shortToken = tokenJson.getString("access_token")

        // 2) long-lived token (60 din)
        val longResp = client.newCall(
            Request.Builder()
                .url(
                    Uri.parse("https://graph.facebook.com/v21.0/oauth/access_token").buildUpon()
                        .appendQueryParameter("grant_type", "fb_exchange_token")
                        .appendQueryParameter("client_id", creds.clientId)
                        .appendQueryParameter("client_secret", creds.clientSecret)
                        .appendQueryParameter("fb_exchange_token", shortToken)
                        .build().toString()
                ).build()
        ).execute()
        val longJson = JSONObject(longResp.body?.string().orEmpty())
        val accessToken = longJson.optString("access_token", shortToken)
        val expiresIn = longJson.optLong("expires_in", 5_184_000)

        val account = SocialAccountEntity(
            platform = SocialPlatform.INSTAGRAM,
            accountId = tokenJson.optString("user_id", "meta"),
            accountName = "Instagram",
            accessToken = TokenVault.encrypt(context, accessToken),
            refreshToken = null,
            expiresAt = System.currentTimeMillis() + expiresIn * 1000,
            scopes = "instagram_basic,instagram_content_publish",
            connectedAt = System.currentTimeMillis()
        )
        AppDatabase.getDatabase(context).socialAccountDao().upsert(account)
        account
    }

    // ---------------------------------------------------------------- helpers

    fun newState(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/** OAuth client credentials use the same EncryptedSharedPreferences-backed vault as API keys. */
object SocialCredentials {
    private const val PREFS = "jarvis_social_creds"
    private const val GOOGLE_CLIENT_ID = "oauth_google_client_id"
    private const val GOOGLE_CLIENT_SECRET = "oauth_google_client_secret"
    private const val META_CLIENT_ID = "oauth_meta_client_id"
    private const val META_CLIENT_SECRET = "oauth_meta_client_secret"

    data class Creds(
        val googleClientId: String,
        val googleClientSecret: String,
        val metaClientId: String,
        val metaClientSecret: String
    ) {
        fun googleBlank(): Boolean = googleClientId.isBlank() || googleClientSecret.isBlank()
        fun metaBlank(): Boolean = metaClientId.isBlank() || metaClientSecret.isBlank()
    }

    fun save(context: Context, creds: Creds) {
        val secureStore = SecureKeyStore(context)
        secureStore.write(GOOGLE_CLIENT_ID, creds.googleClientId)
        secureStore.write(GOOGLE_CLIENT_SECRET, creds.googleClientSecret)
        secureStore.write(META_CLIENT_ID, creds.metaClientId)
        secureStore.write(META_CLIENT_SECRET, creds.metaClientSecret)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    fun get(context: Context): Creds {
        val legacy = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val secureStore = SecureKeyStore(context)
        val googleClientId = readOrMigrate(secureStore, legacy, GOOGLE_CLIENT_ID, "googleClientId")
        val googleClientSecret = readOrMigrate(secureStore, legacy, GOOGLE_CLIENT_SECRET, "googleClientSecret")
        val metaClientId = readOrMigrate(secureStore, legacy, META_CLIENT_ID, "metaClientId")
        val metaClientSecret = readOrMigrate(secureStore, legacy, META_CLIENT_SECRET, "metaClientSecret")

        val migrationComplete = listOf(
            "googleClientId" to googleClientId,
            "googleClientSecret" to googleClientSecret,
            "metaClientId" to metaClientId,
            "metaClientSecret" to metaClientSecret
        ).all { (legacyName, secureValue) ->
            legacy.getString(legacyName, "").isNullOrBlank() || secureValue.isNotBlank()
        }
        if (migrationComplete && legacy.all.isNotEmpty()) legacy.edit().clear().commit()

        return Creds(googleClientId, googleClientSecret, metaClientId, metaClientSecret)
    }

    private fun readOrMigrate(
        secureStore: SecureKeyStore,
        legacy: android.content.SharedPreferences,
        secureName: String,
        legacyName: String
    ): String {
        secureStore.read(secureName)?.takeIf { it.isNotBlank() }?.let { return it }
        val oldValue = legacy.getString(legacyName, "").orEmpty()
        if (oldValue.isBlank()) return ""
        secureStore.write(secureName, oldValue)
        return secureStore.read(secureName).orEmpty()
    }
}
