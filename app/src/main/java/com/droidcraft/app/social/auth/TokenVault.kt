package com.droidcraft.app.social.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.droidcraft.app.data.SecureKeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts social tokens before they are stored in Room. The AES-GCM key seed is protected by
 * EncryptedSharedPreferences and migrated from the earlier plain SharedPreferences seed.
 */
object TokenVault {

    private const val TAG = "TokenVault"
    private const val PREFS = "jarvis_social_vault"
    private const val KEY_SEED = "vault_seed"
    private const val SECURE_KEY_SEED = "oauth_token_vault_seed"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Derives a 256-bit token-vault key from an EncryptedSharedPreferences-backed seed. */
    private fun key(context: Context): SecretKeySpec {
        val legacyPreferences = prefs(context)
        val secureStore = SecureKeyStore(context)
        var seed = secureStore.read(SECURE_KEY_SEED)
        if (seed.isNullOrBlank()) {
            seed = legacyPreferences.getString(KEY_SEED, null)
            if (!seed.isNullOrBlank()) {
                secureStore.write(SECURE_KEY_SEED, seed)
                legacyPreferences.edit().remove(KEY_SEED).commit()
            }
        }
        if (seed.isNullOrBlank()) {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            seed = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            secureStore.write(SECURE_KEY_SEED, seed)
        }
        val raw = android.util.Base64.decode(seed, android.util.Base64.NO_WRAP)
        return SecretKeySpec(raw.copyOf(32), "AES")
    }

    fun encrypt(context: Context, plain: String): String = try {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(context), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        android.util.Base64.encodeToString(iv + ct, android.util.Base64.NO_WRAP)
    } catch (error: Exception) {
        Log.e(TAG, "encrypt fail", error)
        throw IllegalStateException("Could not encrypt the social token.", error)
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
        SecureKeyStore(context).remove(SECURE_KEY_SEED)
    }
}
