package com.filebridge.app.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.filebridge.app.FileBridgeApp
import com.filebridge.app.data.AppConfig
import com.filebridge.app.data.SettingsStore
import com.filebridge.app.server.ServerController
import com.filebridge.app.server.ServerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

data class SecurityMeta(
    val passwordSet: Boolean = false,
    val vaultUnlocked: Boolean = false,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val appCtx: FileBridgeApp = FileBridgeApp.from(app)
    private val store: SettingsStore get() = appCtx.settingsStore

    private val _config = MutableStateFlow(AppConfig())
    val config: StateFlow<AppConfig> = _config

    private val _meta = MutableStateFlow(SecurityMeta())
    val meta: StateFlow<SecurityMeta> = _meta

    val serverState: StateFlow<ServerController.UiState> = ServerController.state

    init {
        viewModelScope.launch { store.config.collect { _config.value = it } }
        _meta.value = SecurityMeta(
            passwordSet = appCtx.security.passwordSet,
            vaultUnlocked = appCtx.security.vaultUnlocked,
        )
    }

    private fun refreshMeta() {
        _meta.value = SecurityMeta(
            passwordSet = appCtx.security.passwordSet,
            vaultUnlocked = appCtx.security.vaultUnlocked,
        )
    }

    // ---- server control ----

    fun startServer() {
        val ctx = getApplication<Application>()
        val i = Intent(ctx, ServerService::class.java).setAction(ServerService.ACTION_START)
        ContextCompat.startForegroundService(ctx, i)
    }

    fun stopServer() {
        val ctx = getApplication<Application>()
        ctx.stopService(Intent(ctx, ServerService::class.java))
    }

    // ---- config ----

    fun setPort(port: Int) = viewModelScope.launch { store.setPort(port) }
    fun setTls(enabled: Boolean) = viewModelScope.launch { store.setTls(enabled) }
    fun setTimeout(min: Int) = viewModelScope.launch { store.setTimeout(min) }
    fun setEncryption(enabled: Boolean) = viewModelScope.launch { store.setEncryption(enabled) }

    // ---- shares ----

    fun addShare(uri: Uri) {
        runCatching {
            val resolver = getApplication<Application>().contentResolver
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        viewModelScope.launch { store.addShare(uri.toString()) }
    }

    fun removeShare(uri: String) = viewModelScope.launch { store.removeShare(uri) }

    fun shareLabel(uri: String): String = runCatching {
        val tree = android.net.Uri.parse(uri)
        val rootId = android.provider.DocumentsContract.getTreeDocumentId(tree)
        appCtx.docStore.displayName(tree, rootId).ifBlank { uri.substringAfterLast(":") }
    }.getOrNull() ?: uri

    // ---- password & vault ----

    fun setPassword(new: CharArray, onChangeDone: () -> Unit) = viewModelScope.launch {
        withContext(Dispatchers.Default) { appCtx.security.setPassword(new) }
        refreshMeta()
        onChangeDone()
    }

    fun changePassword(old: CharArray, new: CharArray, onChangeDone: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = withContext(Dispatchers.Default) { appCtx.security.changePassword(old, new) }
        refreshMeta()
        onChangeDone(ok)
    }

    fun unlockVault(password: CharArray, done: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = withContext(Dispatchers.Default) { appCtx.security.unlockVault(password) }
        refreshMeta()
        done(ok)
    }

    fun lockVault() {
        appCtx.security.lockVault()
        refreshMeta()
    }

    fun vaultList(): List<com.filebridge.app.data.SecurityManager.VaultEntry> =
        appCtx.security.list()

    fun addToVault(name: String, input: InputStream, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) { appCtx.security.encrypt(name, input) }
        onDone(ok)
    }

    fun deleteVault(name: String) {
        appCtx.security.delete(name)
    }
}