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
}