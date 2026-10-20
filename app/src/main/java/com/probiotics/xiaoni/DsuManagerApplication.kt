package com.probiotics.xiaoni

import android.app.Application
import android.util.Log
import com.yunx.app.data.download.Aria2DownloadBackendRegistry
import com.yunx.app.data.download.DsuNativeDownloadRouter
import com.yunx.app.data.gopeed.GopeedEngine

/** App-wide integration point for the embedded YunX module. */
class DsuManagerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Aria2DownloadBackendRegistry.install(DsuAria2DownloadBackend(this))
        DsuNativeDownloadRouter.install(this, DsuNativeDownloadSubmitter)
        GopeedEngine.syncInstalledState(this)
        if (GopeedEngine.isInstalled(this)) {
            Thread({
                runCatching {
                    GopeedEngine.ensureRunning(this, GopeedEngine.resolveDownloadDir(this))
                }.onFailure { error ->
                    Log.e("DsuManager", "启动时自动加载 Gopeed BT 内核失败：${error.message}", error)
                }
            }, "gopeed-autostart").start()
        }
    }
}
