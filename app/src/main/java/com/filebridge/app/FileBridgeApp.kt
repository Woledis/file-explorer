package com.filebridge.app

import android.app.Application
import android.content.Context
import com.filebridge.app.data.DocStore
import com.filebridge.app.data.SecurityManager
import com.filebridge.app.data.SettingsStore

class FileBridgeApp : Application() {

    lateinit var settingsStore: SettingsStore
        private set
    lateinit var security: SecurityManager
        private set
    lateinit var docStore: DocStore
        private set

    override fun onCreate() {
        super.onCreate()
        // Conscrypt 不再在这里无条件注册:它只在启用 HTTPS(TLS) 时才有用,
        // 由 FileServer 在真正需要时惰性安装,避免冷启动加载/注册额外 provider。
        settingsStore = SettingsStore(this)
        security = SecurityManager(this)
        docStore = DocStore(this)
    }

    companion object {
        fun from(context: Context): FileBridgeApp =
            context.applicationContext as FileBridgeApp
    }
}