package com.filebridge.app.data

import android.content.Context
import java.util.Base64

/**
 * Wraps private SharedPreferences holding non-configuration secrets:
 * the password verification hash/salt and the password-wrapped vault key.
 */
class SecureStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("secure", Context.MODE_PRIVATE)

    fun hasPassword(): Boolean = prefs.contains(KEY_SALT) && prefs.contains(KEY_HASH)

    fun savePassword(salt: ByteArray, hash: ByteArray) {
        prefs.edit()
            .putString(KEY_SALT, b64(salt))
            .putString(KEY_HASH, b64(hash))
            .apply()
    }

    fun passwordSalt(): ByteArray? = prefs.getString(KEY_SALT, null)?.let(::unb64)
    fun passwordHash(): ByteArray? = prefs.getString(KEY_HASH, null)?.let(::unb64)

    fun saveVaultBlob(blob: ByteArray) {
        prefs.edit().putString(KEY_VAULT, b64(blob)).apply()
    }

    fun vaultBlob(): ByteArray? = prefs.getString(KEY_VAULT, null)?.let(::unb64)

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)

    private companion object {
        const val KEY_SALT = "pw_salt"
        const val KEY_HASH = "pw_hash"
        const val KEY_VAULT = "vault_blob"
    }
}