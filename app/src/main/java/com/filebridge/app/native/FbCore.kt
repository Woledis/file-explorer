package com.filebridge.app.native

/**
 * Thin JNI bridge to the Rust core. The library may be absent in local (non-CI)
 * builds, so every access is guarded and degrades to Kotlin fallbacks.
 */
object FbCore {

    val version: String? by lazy {
        try {
            System.loadLibrary("filebridge_rust")
            ping()
        } catch (t: Throwable) {
            null
        }
    }

    val available: Boolean get() = version != null

    private external fun ping(): String

    external fun smokeHttp(port: Int): Int

    // ---- M2.1 native HTTP transfer engine ----

    private external fun serveHttp(port: Int, root: String, token: String): Int

    private external fun stopHttp()

    private var engineThread: Thread? = null

    /**
     * Starts the Rust HTTP transfer engine on a daemon thread. [root] is the
     * native root (/storage/emulated/0); [token] gates every request. Safe
     * no-op if the native library is absent (local/Kotlin builds).
     */
    fun startEngine(port: Int, root: String, token: String): Boolean {
        if (!available) return false
        synchronized(this) {
            if (engineThread?.isAlive == true) return true
            val t = Thread(null, {
                serveHttp(port, root, token)
            }, "rust-http")
            t.isDaemon = true
            t.start()
            engineThread = t
            return true
        }
    }

    fun stopEngine() {
        if (!available) return
        try {
            stopHttp()
        } catch (_: Throwable) {
        } finally {
            engineThread?.interrupt()
        }
    }
}