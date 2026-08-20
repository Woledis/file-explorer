package com.filebridge.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context._store by preferencesDataStore(name = "settings")

data class AppConfig(
    val port: Int = 8443,
    val tlsEnabled: Boolean = false,
    val sessionTimeoutMin: Int = 30,
    val encryptionEnabled: Boolean = false,
    val ftpEnabled: Boolean = false,
    val ftpPort: Int = 2121,
    val ftpAllFiles: Boolean = false,
    val sharedUris: List<String> = emptyList(),
) {
    val scheme: String get() = if (tlsEnabled) "https" else "http"
}

class SettingsStore(private val context: Context) {

    private object Keys {
        val PORT = intPreferencesKey("port")
        val TLS = booleanPreferencesKey("tls_enabled")
        val TIMEOUT = intPreferencesKey("session_timeout_min")
        val ENCRYPTION = booleanPreferencesKey("encryption_enabled")
        val FTP_ENABLED = booleanPreferencesKey("ftp_enabled")
        val FTP_PORT = intPreferencesKey("ftp_port")
        val FTP_ALL_FILES = booleanPreferencesKey("ftp_all_files")
        val SHARED = stringSetPreferencesKey("shared_uris")
    }

    val config: Flow<AppConfig> = context._store.data.map { p ->
        AppConfig(
            port = p[Keys.PORT] ?: 8443,
            tlsEnabled = p[Keys.TLS] ?: false,
            sessionTimeoutMin = p[Keys.TIMEOUT] ?: 30,
            encryptionEnabled = p[Keys.ENCRYPTION] ?: false,
            ftpEnabled = p[Keys.FTP_ENABLED] ?: false,
            ftpPort = p[Keys.FTP_PORT] ?: 2121,
            ftpAllFiles = p[Keys.FTP_ALL_FILES] ?: false,
            // DataStore 的 stringSetPreferencesKey 读出来的 Set 顺序不保证,
            // 排序后再 toList,UI 列表项不会每次启动都抖动。
            sharedUris = (p[Keys.SHARED] ?: emptySet()).toSortedSet().toList(),
        )
    }

    suspend fun update(transform: (AppConfig) -> AppConfig) {
        val current = config.first()
        val next = transform(current)
        context._store.edit { p ->
            p[Keys.PORT] = next.port
            p[Keys.TLS] = next.tlsEnabled
            p[Keys.TIMEOUT] = next.sessionTimeoutMin
            p[Keys.ENCRYPTION] = next.encryptionEnabled
            p[Keys.FTP_ENABLED] = next.ftpEnabled
            p[Keys.FTP_PORT] = next.ftpPort
            p[Keys.FTP_ALL_FILES] = next.ftpAllFiles
            p[Keys.SHARED] = next.sharedUris.toSet()
        }
    }

    suspend fun setPort(port: Int) = update { it.copy(port = port) }
    suspend fun setTls(enabled: Boolean) = update { it.copy(tlsEnabled = enabled) }
    suspend fun setTimeout(min: Int) = update { it.copy(sessionTimeoutMin = min) }
    suspend fun setEncryption(enabled: Boolean) = update { it.copy(encryptionEnabled = enabled) }
    suspend fun setFtpEnabled(enabled: Boolean) = update { it.copy(ftpEnabled = enabled) }
    suspend fun setFtpPort(port: Int) = update { it.copy(ftpPort = port) }
    suspend fun setFtpAllFiles(enabled: Boolean) = update { it.copy(ftpAllFiles = enabled) }
    suspend fun addShare(uri: String) = update { it.copy(sharedUris = (it.sharedUris + uri).distinct()) }
    suspend fun removeShare(uri: String) = update { it.copy(sharedUris = it.sharedUris - uri) }
}