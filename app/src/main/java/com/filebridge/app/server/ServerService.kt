package com.filebridge.app.server

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import com.filebridge.app.FileBridgeApp
import com.filebridge.app.MainActivity
import com.filebridge.app.R
import com.filebridge.app.data.DocStore
import com.filebridge.app.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the NanoHTTPD accept loop so file sharing
 * survives the app going to background.
 */
class ServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: FileServer? = null
    private var ftpManager: FtpManager? = null
    private var sessionStore: SessionStore? = null
    private var pollJob: Job? = null
    // 跟踪 startRoutine 的 Job,Stop 时若启动还在进行中就取消,
    // 避免「通知已移除但 socket 还在 listen」的 Start/Stop 竞态。
    private var startJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopEverything()
            else -> {
                startForegroundCompat()
                startJob?.cancel()
                startJob = scope.launch { startRoutine() }
            }
        }
        return START_STICKY
    }

    private suspend fun startRoutine() {
        val app = FileBridgeApp.from(this)
        val cfg = app.settingsStore.config.first()
        val host = NetworkUtils.getIpv4(this).orEmpty()

        val sesStore = SessionStore(cfg.sessionTimeoutMin * 60_000L)
        val roots = cfg.sharedUris.mapNotNull { uriToRoot(it, app.docStore) }
        val srv = FileServer(
            port = cfg.port,
            tls = cfg.tlsEnabled,
            sharedRoots = roots,
            vaultEnabled = cfg.encryptionEnabled,
            security = app.security,
            docStore = app.docStore,
            sessionStore = sesStore,
        )
        val started = runCatching { srv.start(NanoHTTPSocketReadTimeout, false) }.isSuccess
        // 启动期间若收到 Stop(startJob 已被 cancel),立即收尾,避免「socket 已 listen
        // 但通知已移除、UI 显示已停止」的不一致状态。
        if (!coroutineContext.isActive) {
            if (started) runCatching { srv.stop() }
            return
        }
        if (started) {
            server = srv
            sessionStore = sesStore
            ServerController.update {
                it.copy(
                    running = true,
                    host = host,
                    port = cfg.port,
                    tls = cfg.tlsEnabled,
                    timeoutMin = cfg.sessionTimeoutMin,
                    encrypted = cfg.encryptionEnabled,
                )
            }
            pollJob = scope.launch {
                // 事件驱动:仅在会话数真正变化时更新一次 UI,
                // 空闲服务器不再每 3s 定时唤醒(省电)。
                sesStore.version.collect {
                    val n = sesStore.activeCount
                    ServerController.update { cur ->
                        if (cur.connections == n) cur else cur.copy(connections = n)
                    }
                }
            }
        } else {
            ServerController.update { it.copy(running = false, connections = 0) }
        }

        // FTP server follows its own user-facing toggle; web and FTP are independent.
        if (cfg.ftpEnabled) {
            if (cfg.ftpAllFiles && !allFilesGranted(this)) {
                ServerController.update { it.copy(ftpRunning = false, ftpPort = 0) }
            } else {
                val fm = FtpManager(this, roots, cfg.ftpAllFiles)
                if (fm.start(cfg.ftpPort)) {
                    ftpManager = fm
                    ServerController.update { it.copy(ftpRunning = true, ftpPort = cfg.ftpPort) }
                } else {
                    ServerController.update { it.copy(ftpRunning = false, ftpPort = 0) }
                }
            }
        } else {
            ServerController.update { it.copy(ftpRunning = false, ftpPort = 0) }
        }
    }

    /** All-files access: Android 11+ via MANAGE_EXTERNAL_STORAGE, older via READ. */
    private fun allFilesGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun uriToRoot(uri: String, docStore: DocStore): SharedRoot? {
        return runCatching {
            val tree = Uri.parse(uri)
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val label = docStore.displayName(tree, rootId).ifBlank { "共享文件夹" }
            SharedRoot(label, uri)
        }.getOrNull()
    }

    private fun stopEverything() {
        startJob?.cancel()
        startJob = null
        pollJob?.cancel()
        pollJob = null
        server?.stop()
        ftpManager?.stop()
        ftpManager = null
        sessionStore?.revokeAll()
        server = null
        sessionStore = null
        ServerController.update { it.copy(running = false, connections = 0, ftpRunning = false, ftpPort = 0) }
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ notification

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.notification_channel_desc) }
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundCompat() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_serving))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .build()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        const val ACTION_START = "com.filebridge.app.action.START"
        const val ACTION_STOP = "com.filebridge.app.action.STOP"
        private const val CHANNEL_ID = "filebridge_service"
        private const val NOTIF_ID = 1001
        private const val NanoHTTPSocketReadTimeout = 60_000
    }
}