package com.filebridge.app.server

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory session tokens issued after a successful password login.
 * Tokens are 32 random bytes, expire after idle [timeoutMs], and are all
 * revoked when the server stops.
 */
class SessionStore(private val timeoutMs: Long) {

    private val sessions = ConcurrentHashMap<String, Long>() // token -> lastSeen

    fun newToken(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        sessions[token] = System.currentTimeMillis()
        return token
    }

    /** Returns true (and refreshes lastSeen) when [token] is currently valid. */
    fun isValid(token: String): Boolean {
        val now = System.currentTimeMillis()
        // 用 compute 原子完成「读取-检查-刷新/删除」,避免 revoke 与 isValid 之间
        // 出现「先读到值,revoke 删了,isValid 又把它写回」的复活竞争。
        var valid = false
        sessions.compute(token) { _, last ->
            when {
                last == null -> null
                now - last > timeoutMs -> null
                else -> { valid = true; now }
            }
        }
        return valid
    }

    fun revoke(token: String?) {
        if (token != null) sessions.remove(token)
    }

    fun revokeAll() = sessions.clear()

    val activeCount: Int get() = sessions.size

    val hasActive: Boolean get() = sessions.isNotEmpty()
}