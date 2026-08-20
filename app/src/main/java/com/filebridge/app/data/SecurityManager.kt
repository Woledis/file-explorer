package com.filebridge.app.data

import android.content.Context
import android.net.Uri
import com.filebridge.app.crypto.AuthCrypto
import com.filebridge.app.crypto.CryptoManager
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.SecureRandom
import java.util.Base64

/**
 * Owns the single master password and the encrypted vault (static file
 * encryption, off by default). The vault master key is derived at first
 * password setup, wrapped under the password, and held in memory only after
 * the user re-enters it following an app restart.
 */
class SecurityManager(private val context: Context) {

    private val store = SecureStore(context)
    private val vaultDir = File(context.applicationContext.filesDir, "vault").apply { mkdirs() }

    @Volatile
    private var vaultKey: ByteArray? = null

    val passwordSet: Boolean get() = store.hasPassword()

    /** True when the in-memory vault key is available for serve/encrypt. */
    val vaultUnlocked: Boolean get() = vaultKey != null

    /** Set the master password (first time), deriving + wrapping the vault key. */
    fun setPassword(password: CharArray) {
        val salt = AuthCrypto.newSalt()
        val hash = AuthCrypto.hash(password, salt)
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val blob = CryptoManager.wrapVaultKey(password, key)
        store.savePassword(salt, hash)
        store.saveVaultBlob(blob)
        vaultKey = key
    }

    /**
     * Change password without asking for the old one: keeps the in-memory vault
     * key when the vault is unlocked, otherwise re-rolls it (rendering any
     * never-unlocked vault content inaccessible — acceptable, and simplified at
     * the user's request).
     */
    fun resetPasswordNoOld(new: CharArray) {
        val key = if (vaultKey != null) {
            vaultKey!!
        } else {
            ByteArray(32).also { SecureRandom().nextBytes(it) }
        }
        val salt = AuthCrypto.newSalt()
        store.savePassword(salt, AuthCrypto.hash(new, salt))
        store.saveVaultBlob(CryptoManager.wrapVaultKey(new, key))
        vaultKey = key
    }

    fun verifyPassword(password: CharArray): Boolean {
        val salt = store.passwordSalt() ?: return false
        val hash = store.passwordHash() ?: return false
        return AuthCrypto.verify(password, salt, hash)
    }

    fun unlockVault(password: CharArray): Boolean {
        val blob = store.vaultBlob() ?: return false
        return try {
            vaultKey = CryptoManager.unwrapVaultKey(password, blob)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun lockVault() { vaultKey = null }

    fun ensureKey(): ByteArray? = vaultKey

    // ---- Vault file operations ----

    data class VaultEntry(val name: String, val cipherSize: Long)

    fun list(): List<VaultEntry> {
        val key = vaultKey ?: return emptyList()
        return vaultDir.listFiles()?.sortedByDescending { it.lastModified() }?.mapNotNull { f ->
            if (!f.name.endsWith(".enc")) return@mapNotNull null
            runCatching {
                VaultEntry(plainName(f), f.length())
            }.getOrNull()
        } ?: emptyList()
    }

    /** Encrypt [input] under the name [plainName] into the vault. */
    fun encrypt(plainName: String, input: InputStream): Boolean {
        val key = vaultKey ?: return false
        val safe = encFileName(plainName)
        val tmp = File(vaultDir, "$safe.tmp")
        val dest = File(vaultDir, safe)
        return try {
            // 关闭 plain 会顺带关闭底层 input(由调用方传入,如 session.inputStream)。
            BufferedInputStream(input).use { plain ->
                CryptoManager.encryptStream(
                    key,
                    plain,
                    BufferedOutputStream(FileOutputStream(tmp))
                )
            }
            // 先删旧目标;若旧文件存在且删除失败,放弃替换,清理临时文件。
            if (dest.exists() && !dest.delete()) {
                tmp.delete()
                return false
            }
            // renameTo 失败时不能返回 true,否则用户以为已加密入库但列表中不可见。
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                return false
            }
            true
        } catch (_: Exception) {
            tmp.delete()
            false
        }
    }

    /** Open a decrypted stream of a vault entry. */
    fun open(name: String): InputStream? {
        val key = vaultKey ?: return null
        val f = File(vaultDir, encFileName(name))
        if (!f.exists()) return null
        // decryptStream 读取头部时若抛出,必须关闭底层流,否则 FileInputStream 泄漏。
        val bis = BufferedInputStream(FileInputStream(f))
        return try {
            CryptoManager.decryptStream(key, bis)
        } catch (_: Exception) {
            bis.close()
            null
        }
    }

    fun delete(name: String): Boolean {
        val f = File(vaultDir, encFileName(name))
        return f.exists() && f.delete()
    }

    fun exists(name: String): Boolean = File(vaultDir, encFileName(name)).exists()

    private fun encFileName(name: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(name.toByteArray(Charsets.UTF_8)) + ".enc"

    private fun plainName(encFile: File): String =
        String(Base64.getUrlDecoder().decode(encFile.name.removeSuffix(".enc")), Charsets.UTF_8)
}