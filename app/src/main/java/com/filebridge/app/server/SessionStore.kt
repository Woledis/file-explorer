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
        val last = sessions[token] ?: return false
        if (now - last > timeoutMs) {
            sessions.remove(token)
            return false
        }
        sessions[token] = now
        return true
    }

    fun revoke(token: String?) {
        if (token != null) sessions.remove(token)
    }

    fun revokeAll() = sessions.clear()

    val activeCount: Int get() = sessions.size

    val hasActive: Boolean get() = sessions.isNotEmpty()
}