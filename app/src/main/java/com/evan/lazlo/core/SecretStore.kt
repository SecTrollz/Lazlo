package com.evan.lazlo.core

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * API keys live only here: EncryptedSharedPreferences with a
 * hardware-backed MasterKey (Android Keystore on Pixel). Excluded from
 * backup at the manifest level (see AndroidManifest.xml). Never logged,
 * never serialized elsewhere, never touched by DataStore.
 */
class SecretStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "lazlo_secrets",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun setApiKey(providerId: String, key: String) {
        prefs.edit().putString("key_$providerId", key).apply()
    }

    fun getApiKey(providerId: String): String? = prefs.getString("key_$providerId", null)

    fun clearApiKey(providerId: String) {
        prefs.edit().remove("key_$providerId").apply()
    }
}
