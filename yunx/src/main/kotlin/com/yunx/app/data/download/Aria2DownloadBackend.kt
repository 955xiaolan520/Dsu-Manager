/*
 * YunX (云析) - embedded download backend contract.
 * Copyright (C) 2026 CYQawa
 * Distributed under GNU AGPL-3.0-or-later; see /LICENSE.
 */
package com.yunx.app.data.download

import java.io.File

/** Host-provided native aria2c executor. File payloads must not fall back to OkHttp. */
interface Aria2DownloadBackend {
    suspend fun download(
        taskId: Long,
        url: String,
        output: File,
        headers: Map<String, String>,
        threads: Int,
        chunkSizeMb: Long,
        speedLimitBytesPerSecond: Long,
        onProgress: suspend (done: Long, total: Long, bytesPerSecond: Long, etaSeconds: Long) -> Unit
    )
}

/** Installed by the host Application before any YunX screen or task manager is created. */
object Aria2DownloadBackendRegistry {
    @Volatile
    private var backend: Aria2DownloadBackend? = null

    val isInstalled: Boolean
        get() = backend != null

    fun install(value: Aria2DownloadBackend) {
        backend = value
    }

    fun requireBackend(): Aria2DownloadBackend =
        checkNotNull(backend) { "宿主 aria2c 下载后端尚未初始化" }
}
