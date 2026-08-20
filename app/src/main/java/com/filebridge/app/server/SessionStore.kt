package com.filebridge.app.server

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-memory session tokens issued after a successful password login.
 * Tokens are 32 random bytes, expire after idle [timeoutMs], and are all
 * revoked when the server stops.
 *
 * The server drives its UI state from [version]: every mutation (issue /
 * revoke / idle-expire) bumps it, so the foreground service can update the
 * connection count on change instead of polling on a timer. An idle server
 * never wakes up the UI.
 */
class SessionStore(private val timeoutMs: Long) {

    private val sessions = ConcurrentHashMap<String, Long>() // token -> lastSeen
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> get() = _version

    private fun bump() {
        _version.value = _version.value + 1
    }

    fun newToken(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        sessions[token] = System.currentTimeMillis()
        bump()
        return token
    }

    /** Returns true (and refreshes lastSeen) when [token] is currently valid. */
    fun isValid(token: String): Boolean {
        val now = System.currentTimeMillis()
        // 用 compute 原子完成「读取-检查-刷新/删除」,避免 revoke 与 isValid 之间
        // 出现「先读到值,revoke 删了,isValid 又把它写回」的复活竞争。
        var valid = false
        var expired = false
        sessions.compute(token) { _, last ->
            when {
                last == null -> null
                now - last > timeoutMs -> { expired = true; null }
                else -> { valid = true; now }
            }
        }
        // 仅当本次调用真正清掉了一个超时会话时才发信号。
        if (expired) bump()
        return valid
    }

    fun revoke(token: String?) {
        if (token != null && sessions.remove(token) != null) bump()
    }

    fun revokeAll() {
        if (sessions.isNotEmpty()) {
            sessions.clear()
            bump()
        }
    }

    val activeCount: Int get() = sessions.size

    val hasActive: Boolean get() = sessions.isNotEmpty()
}