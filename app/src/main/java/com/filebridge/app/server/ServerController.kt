package com.filebridge.app.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide runtime state shared between the foreground service and the
 * Compose UI. The UI never touches the socket; it only reads this flow.
 */
object ServerController {

    data class UiState(
        val running: Boolean = false,
        val host: String = "",
        val port: Int = 0,
        val tls: Boolean = false,
        val timeoutMin: Int = 30,
        val connections: Int = 0,
        val encrypted: Boolean = false,
        val ftpRunning: Boolean = false,
        val ftpPort: Int = 0,
        val nativePort: Int = 0,
        val nativeToken: String = "",
    ) {
        val scheme: String get() = if (tls) "https" else "http"
        val url: String get() = if (running) "$scheme://$host:$port" else ""
        val ftpUrl: String get() = if (ftpRunning && host.isNotEmpty()) "ftp://$host:$ftpPort" else ""
        /** Rust 原生高速服务地址(带一次性鉴权 token)。 */
        val nativeUrl: String
            get() = if (running && nativePort > 0 && nativeToken.isNotEmpty() && host.isNotEmpty()) {
                "http://$host:$nativePort/?t=$nativeToken"
            } else {
                ""
            }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    fun update(transform: (UiState) -> UiState) {
        _state.update(transform)
    }

    fun reset() {
        _state.value = UiState()
    }
}