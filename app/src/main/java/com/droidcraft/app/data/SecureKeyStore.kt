package com.droidcraft.app.data

import android.content.Context
import java.security.KeyStore
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores provider/API credentials in Jetpack EncryptedSharedPreferences. Existing values written
 * by the earlier AES-GCM SharedPreferences implementation are migrated on first access.
 */
class SecureKeyStore(context: Context) {
    private val appContext = context.applicationContext

    private val preferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            ENCRYPTED_PREFERENCES_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ).also(::migrateLegacyValues)
    }

    fun read(name: String): String? = preferences.getString(name, null)

    fun write(name: String, value: String) {
        if (value.isBlank()) {
            remove(name)
            return
        }
        check(preferences.edit().putString(name, value).commit()) {
            "Could not save the encrypted provider key."
        }
        // Remove stale ciphertext only after the EncryptedSharedPreferences write succeeds.
        legacyPreferences().edit().remove(name).commit()
    }

    fun remove(name: String) {
        preferences.edit().remove(name).commit()
        legacyPreferences().edit().remove(name).commit()
    }

    private fun migrateLegacyValues(encryptedPreferences: android.content.SharedPreferences) {
        synchronized(MIGRATION_LOCK) {
            val legacy = legacyPreferences()
            val values = legacy.all
            if (values.isEmpty()) return

            val editor = encryptedPreferences.edit()
            val migratedKeys = mutableListOf<String>()
            for ((name, encoded) in values) {
                if (encoded !is String) continue
                if (!encryptedPreferences.contains(name)) {
                    val plaintext = decryptLegacyValue(encoded) ?: continue
                    editor.putString(name, plaintext)
                }
                // Existing new-format values take precedence over a stale legacy value.
                migratedKeys += name
            }

            if (migratedKeys.isEmpty()) return
            if (editor.commit()) {
                legacy.edit().also { old -> migratedKeys.forEach { old.remove(it) } }.commit()
            }
        }
    }

    private fun decryptLegacyValue(encoded: String): String? = runCatching {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        require(combined.size > IV_SIZE) { "Invalid legacy encrypted preference" }
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val key = keyStore.getKey(LEGACY_KEY_ALIAS, null) as? SecretKey
            ?: return@runCatching null
        val iv = combined.copyOfRange(0, IV_SIZE)
        val ciphertext = combined.copyOfRange(IV_SIZE, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }.getOrNull()

    private fun legacyPreferences() =
        appContext.getSharedPreferences(LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE)

    private companion object {
        const val ENCRYPTED_PREFERENCES_NAME = "droidcraft_secure_keys_encrypted"
        const val LEGACY_PREFERENCES_NAME = "droidcraft_secure_keys"
        // Retained only to decrypt values created by the previous AES-GCM storage implementation.
        const val LEGACY_KEY_ALIAS = "droidcraft_provider_keys_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
        val MIGRATION_LOCK = Any()
    }
}
