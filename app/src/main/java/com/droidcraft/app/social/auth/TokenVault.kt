package com.droidcraft.app.social.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Token vault — tokens ko AES-256-GCM se encrypt karke SharedPreferences में रखता है.
 * Key Android Keystore में नहीं रख सकते (यों चाइन टूटेगी), इसलिए key को एक
 * ही secure random seed से derive करते हैं जो app के private storage में रहता है.
 */
object TokenVault {

    private const val TAG = "TokenVault"
    private const val PREFS = "jarvis_social_vault"
    private const val KEY_SEED = "vault_seed"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 256-bit key derive करो (seed से). */
    private fun key(context: Context): SecretKeySpec {
        val p = prefs(context)
        var seed = p.getString(KEY_SEED, null)
        if (seed == null) {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            seed = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            p.edit().putString(KEY_SEED, seed).apply()
        }
        val raw = android.util.Base64.decode(seed, android.util.Base64.NO_WRAP)
        return SecretKeySpec(raw.copyOf(32), "AES")
    }

    fun encrypt(context: Context, plain: String): String = runCatching {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(context), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        android.util.Base64.encodeToString(iv + ct, android.util.Base64.NO_WRAP)
    }.getOrElse {
        Log.e(TAG, "encrypt fail", it)
        plain // fallback: कम से कम app टूटे नहीं
    }

    fun decrypt(context: Context, stored: String): String = runCatching {
        val all = android.util.Base64.decode(stored, android.util.Base64.NO_WRAP)
        val iv = all.copyOfRange(0, IV_BYTES)
        val ct = all.copyOfRange(IV_BYTES, all.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(context), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.getOrElse {
        Log.e(TAG, "decrypt fail", it)
        stored
    }

    fun wipe(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
