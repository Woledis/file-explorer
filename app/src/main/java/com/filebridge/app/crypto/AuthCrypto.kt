package com.filebridge.app.crypto

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Password hashing / verification for the access password,
 * plus password-based key derivation for wrapping the vault key.
 */
object AuthCrypto {

    private const val PBKDF2_ALGO = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 210_000
    private const val SALT_BYTES = 16
    private const val KEY_BYTES = 32

    private val secureRandom = SecureRandom()

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }

    /**
     * Derive a secret of [byteCount] from [password] + [salt].
     * Used both to produce a stored verification hash and to derive AES keys.
     */
    fun derive(password: CharArray, salt: ByteArray, byteCount: Int = KEY_BYTES): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, byteCount * 8)
        return try {
            SecretKeyFactory.getInstance(PBKDF2_ALGO).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun hash(password: CharArray, salt: ByteArray): ByteArray = derive(password, salt)

    fun verify(password: CharArray, salt: ByteArray, expectedHash: ByteArray): Boolean {
        val actual = hash(password, salt)
        return constantTimeEquals(actual, expectedHash)
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}