package com.filebridge.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.filebridge.app.FileBridgeApp
import com.filebridge.app.data.AppConfig
import com.filebridge.app.data.SecurityManager
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

    // 共享文件夹 label 缓存:uri -> 显示名。配置变更时在 IO 上刷新一次,避免
    // 每次 recomposition 都在主线程跑 SAF cursor 查询。
    private val _shareLabels = MutableStateFlow<Map<String, String>>(emptyMap())
    val shareLabels: StateFlow<Map<String, String>> = _shareLabels

    // 保险箱条目缓存:由 IO scope 加载,UI 直接 collectAsState,主线程不再做文件 I/O。
    private val _vaultEntries = MutableStateFlow<List<SecurityManager.VaultEntry>>(emptyList())
    val vaultEntries: StateFlow<List<SecurityManager.VaultEntry>> = _vaultEntries

    val serverState: StateFlow<ServerController.UiState> = ServerController.state

    init {
        viewModelScope.launch {
            // 配置变化时同步刷新 shareLabels;config 通常变化少,直接在 collector 里串行刷新即可。
            var lastUris: List<String> = emptyList()
            store.config.collect { cfg ->
                _config.value = cfg
                if (cfg.sharedUris != lastUris) {
                    lastUris = cfg.sharedUris
                    refreshShareLabels(cfg.sharedUris)
                }
            }
        }
        _meta.value = SecurityMeta(
            passwordSet = appCtx.security.passwordSet,
            vaultUnlocked = appCtx.security.vaultUnlocked,
        )
    }

    private suspend fun refreshShareLabels(uris: List<String>) {
        val labels = withContext(Dispatchers.IO) {
            uris.associateWith { uri ->
                runCatching {
                    val tree = Uri.parse(uri)
                    val rootId = DocumentsContract.getTreeDocumentId(tree)
                    appCtx.docStore.displayName(tree, rootId).ifBlank { uri.substringAfterLast(":") }
                }.getOrNull() ?: uri
            }
        }
        _shareLabels.value = labels
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
    fun setFtpEnabled(enabled: Boolean) = viewModelScope.launch { store.setFtpEnabled(enabled) }
    fun setFtpPort(port: Int) = viewModelScope.launch { store.setFtpPort(port) }

    // ---- shares ----

    fun addShare(uri: Uri) {
        // 持久化权限拿不到就别入库,否则用户看到「已共享」但服务端实际打不开。
        val ok = runCatching {
            val resolver = getApplication<Application>().contentResolver
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.isSuccess
        if (ok) {
            viewModelScope.launch { store.addShare(uri.toString()) }
        }
    }

    fun removeShare(uri: String) = viewModelScope.launch { store.removeShare(uri) }

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
        // 仅在解锁成功时刷新;失败时 vaultKey 仍为 null,刷出来也是空列表,白白做一次 IO。
        if (ok) refreshVault()
        done(ok)
    }

    fun lockVault() {
        appCtx.security.lockVault()
        _vaultEntries.value = emptyList()
        refreshMeta()
    }

    /** 在 IO scope 上重新读取保险箱条目。UI 进入或修改后调一次。 */
    fun refreshVault() = viewModelScope.launch {
        _vaultEntries.value = withContext(Dispatchers.IO) { appCtx.security.list() }
    }

    fun addToVault(name: String, input: InputStream, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) { appCtx.security.encrypt(name, input) }
        if (ok) refreshVault()
        onDone(ok)
    }

    fun deleteVault(name: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { appCtx.security.delete(name) }
        refreshVault()
    }
}