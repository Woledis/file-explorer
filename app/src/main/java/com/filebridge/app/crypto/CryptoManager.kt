package com.filebridge.app.crypto

import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM building blocks used by the vault (static file encryption).
 *
 * Encrypted file layout:
 *   magic(4) | version(1) | nonce(12) | AES-256-GCM ciphertext(tag appended by stream)
 */
object CryptoManager {

    private const val ALGO = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val NONCE_BYTES = 12
    private const val MAGIC = "FBVC".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 1

    private val secureRandom = SecureRandom()

    fun newNonce(): ByteArray = ByteArray(NONCE_BYTES).also { secureRandom.nextBytes(it) }

    private fun keySpec(key: ByteArray) = SecretKeySpec(key, "AES")

    /** Encrypt [plain] into [out], writing header + ciphertext. Closes [out] afterwards (flushes the GCM tag). */
    fun encryptStream(key: ByteArray, plain: InputStream, out: OutputStream) {
        val nonce = newNonce()
        out.write(MAGIC)
        out.write(VERSION)
        out.write(nonce)

        val cipher = Cipher.getInstance(ALGO)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec(key), GCMParameterSpec(TAG_BITS, nonce))
        val cos = CipherOutputStream(out, cipher)
        plain.copyTo(cos, 64 * 1024)
        cos.flush()
        cos.close() // closing appends the auth tag
    }

    /** Return a CipherInputStream that decrypts the body of an encrypted [inputStream] stream. */
    fun decryptStream(key: ByteArray, inputStream: InputStream): CipherInputStream {
        val magic = ByteArray(4)
        var read = 0
        while (read < 4) {
            val n = inputStream.read(magic, read, 4 - read)
            if (n < 0) throw IllegalStateException("corrupt vault header (magic)")
            read += n
        }
        if (!magic.contentEquals(MAGIC)) throw IllegalStateException("not a vault file: $magic")
        val version = inputStream.read()
        if (version != VERSION) throw IllegalStateException("unsupported vault version: $version")
        val nonce = ByteArray(NONCE_BYTES)
        read = 0
        while (read < NONCE_BYTES) {
            val n = inputStream.read(nonce, read, NONCE_BYTES - read)
            if (n < 0) throw IllegalStateException("corrupt vault header (nonce)")
            read += n
        }
        val cipher = Cipher.getInstance(ALGO)
        cipher.init(Cipher.DECRYPT_MODE, keySpec(key), GCMParameterSpec(TAG_BITS, nonce))
        return CipherInputStream(inputStream, cipher)
    }

    /** Wrap a 32-byte [vaultKey] with a password so it can be stored at rest. */
    fun wrapVaultKey(password: CharArray, vaultKey: ByteArray): ByteArray {
        val salt = AuthCrypto.newSalt()
        val key = AuthCrypto.derive(password, salt, 32)
        val cipher = Cipher.getInstance(ALGO)
        val nonce = newNonce()
        cipher.init(Cipher.ENCRYPT_MODE, keySpec(key), GCMParameterSpec(TAG_BITS, nonce))
        val ct = cipher.doFinal(vaultKey)
        val out = java.io.ByteArrayOutputStream()
        out.write(salt)
        out.write(nonce)
        out.write(ct)
        return out.toByteArray()
    }

    /** Unwrap the vault key from a wrapped blob produced by [wrapVaultKey]. */
    fun unwrapVaultKey(password: CharArray, blob: ByteArray): ByteArray {
        val salt = blob.copyOfRange(0, 16)
        val nonce = blob.copyOfRange(16, 28)
        val ct = blob.copyOfRange(28, blob.size)
        val key = AuthCrypto.derive(password, salt, 32)
        val cipher = Cipher.getInstance(ALGO)
        cipher.init(Cipher.DECRYPT_MODE, keySpec(key), GCMParameterSpec(TAG_BITS, nonce))
        return cipher.doFinal(ct)
    }
}