package com.probiotics.xiaoni

import android.app.Application
import com.yunx.app.data.download.Aria2DownloadBackendRegistry
import com.yunx.app.data.download.DsuNativeDownloadRouter

/** App-wide integration point for the embedded YunX module. */
class DsuManagerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Aria2DownloadBackendRegistry.install(DsuAria2DownloadBackend(this))
        DsuNativeDownloadRouter.install(this, DsuNativeDownloadSubmitter)
    }
}
