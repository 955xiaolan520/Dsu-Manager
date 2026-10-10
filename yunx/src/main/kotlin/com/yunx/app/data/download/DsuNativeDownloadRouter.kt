/*
 * YunX (云析) - A network drive share-link parser and downloader for Android.
 * Copyright (C) 2026 CYQawa
 *
 * This program is free software under the GNU Affero General Public License v3 or later.
 */
package com.yunx.app.data.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Host hook: integrated Dsu builds route all YunX downloads through Dsu's persistent task service. */
object DsuNativeDownloadRouter {
    fun interface Submitter {
        fun submit(
            context: Context,
            url: String,
            fileName: String,
            headers: Map<String, String>,
            platform: String,
            onComplete: suspend () -> Unit
        ): Boolean
    }

    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingCallbacks = ConcurrentHashMap<String, suspend () -> Unit>()
    private val fallbackIds = AtomicLong(-1L)

    @Volatile
    private var submitter: Submitter? = null

    @Volatile
    private var receiverRegistered = false

    fun install(context: Context, submitter: Submitter) {
        this.submitter = submitter
        if (receiverRegistered) return
        synchronized(this) {
            if (receiverRegistered) return
            val app = context.applicationContext
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != "com.probiotics.xiaoni.download.UPDATE") return
                    val id = intent.getStringExtra("task_id") ?: return
                    val state = intent.getStringExtra("state") ?: return
                    if (state == "下载完成") {
                        pendingCallbacks.remove(id)?.let { callback ->
                            callbackScope.launch { runCatching { callback() } }
                        }
                    } else if (state.startsWith("下载失败") || state.startsWith("下载已取消")) {
                        pendingCallbacks.remove(id)
                    }
                }
            }
            val filter = IntentFilter("com.probiotics.xiaoni.download.UPDATE")
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                app.registerReceiver(receiver, filter)
            }
            receiverRegistered = true
        }
    }

    /**
     * Returns null when the host has not installed a Dsu route (standalone YunX behavior),
     * otherwise returns whether the host accepted the task.
     */
    fun submit(
        context: Context,
        url: String,
        fileName: String,
        headers: Map<String, String> = emptyMap(),
        platform: String = "网盘",
        onComplete: suspend () -> Unit = {}
    ): Boolean? = submitter?.submit(context, url, fileName, headers, platform, onComplete)
        ?: if (submitter == null) null else false

    /** Called by the host before it starts the service, avoiding a fast-completion race. */
    fun trackCompletion(taskId: String, callback: suspend () -> Unit) {
        if (taskId.isNotBlank()) pendingCallbacks[taskId] = callback
    }

    fun forgetCompletion(taskId: String) {
        pendingCallbacks.remove(taskId)
    }

    fun syntheticId(): Long = fallbackIds.getAndDecrement()
}
