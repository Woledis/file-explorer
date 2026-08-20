package com.filebridge.app.server

import android.content.Context
import com.filebridge.app.FileBridgeApp
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory

/**
 * Owns the Apache MINA FTP server. Login is validated against the app's access
 * password; the document tree is the [FtpVfs] virtual view over shared folders.
 */
class FtpManager(
    private val context: Context,
    private val roots: List<SharedRoot>,
) {
    private val app: FileBridgeApp get() = FileBridgeApp.from(context)

    @Volatile
    private var server: FtpServer? = null

    val isRunning: Boolean get() = server != null

    fun start(port: Int): Boolean {
        stop()
        val vfs = FtpVfs(context, app.docStore, roots)
        val factory = FtpServerFactory().apply {
            userManager = AppUserManager(app.security)
            fileSystemFactory = AppFileSystem(VfsView(vfs))
        }
        val listener = ListenerFactory().apply { this.port = port }.createListener()
        factory.addListener("default", listener)
        return runCatching {
            val srv = factory.createServer()
            srv.start()
            server = srv
        }.isSuccess
    }

    fun stop() {
        runCatching { server?.stop() }
        server = null
    }
}