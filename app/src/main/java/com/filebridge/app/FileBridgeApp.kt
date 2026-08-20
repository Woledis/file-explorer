package com.filebridge.app

import android.app.Application
import android.content.Context
import com.filebridge.app.data.DocStore
import com.filebridge.app.data.SecurityManager
import com.filebridge.app.data.SettingsStore
import org.conscrypt.Conscrypt
import java.security.Security

class FileBridgeApp : Application() {

    lateinit var settingsStore: SettingsStore
        private set
    lateinit var security: SecurityManager
        private set
    lateinit var docStore: DocStore
        private set

    override fun onCreate() {
        super.onCreate()
        installCryptoProvider()
        settingsStore = SettingsStore(this)
        security = SecurityManager(this)
        docStore = DocStore(this)
    }

    private fun installCryptoProvider() {
        try {
            if (Conscrypt.isAvailable()) {
                Security.insertProviderAt(Conscrypt.newProvider(), 1)
            }
        } catch (_: Throwable) {
            // fall back to platform providers
        }
    }

    companion object {
        fun from(context: Context): FileBridgeApp =
            context.applicationContext as FileBridgeApp
    }
}