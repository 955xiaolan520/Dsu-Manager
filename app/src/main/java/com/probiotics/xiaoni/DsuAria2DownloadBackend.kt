package com.probiotics.xiaoni

import android.content.Context
import com.yunx.app.data.download.Aria2DownloadBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Adapts YunX's persistent task queue to Dsu-Manager's existing multi-threaded aria2c runner. */
class DsuAria2DownloadBackend(context: Context) : Aria2DownloadBackend {
    private val appContext = context.applicationContext

    override suspend fun download(
        taskId: Long,
        url: String,
        output: File,
        headers: Map<String, String>,
        threads: Int,
        chunkSizeMb: Long,
        speedLimitBytesPerSecond: Long,
        onProgress: suspend (done: Long, total: Long, bytesPerSecond: Long, etaSeconds: Long) -> Unit
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        val done = AtomicLong(0L)
        val total = AtomicLong(-1L)
        val speed = AtomicLong(0L)
        val eta = AtomicLong(-1L)

        fun reportProgress() {
            if (!continuation.isActive) return
            // Listener callbacks run on aria2c's reader thread, never on the UI thread.
            try {
                runBlocking(Dispatchers.IO) {
                    if (continuation.isActive) {
                        onProgress(done.get(), total.get(), speed.get(), eta.get())
                    }
                }
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            }
        }

        val listener = object : Aria2Downloader.Listener {
            override fun onState(text: String) {
                if (text.startsWith("已暂停") && continuation.isActive) {
                    continuation.cancel(CancellationException("aria2c task paused"))
                }
            }

            override fun onProgress(downloaded: Long, expected: Long) {
                done.set(downloaded)
                total.set(expected)
                reportProgress()
            }

            override fun onSpeed(bytesPerSecond: Long, etaSeconds: Long) {
                speed.set(bytesPerSecond)
                eta.set(etaSeconds)
                reportProgress()
            }

            override fun onError(message: String) {
                if (continuation.isActive) {
                    continuation.resumeWith(Result.failure(IllegalStateException(message)))
                }
            }

            override fun onComplete(file: File) {
                if (continuation.isActive) continuation.resumeWith(Result.success(Unit))
            }
        }

        val downloader = Aria2Downloader(
            appContext,
            url,
            output,
            listener,
            threads.coerceAtLeast(1),
            chunkSizeMb.coerceAtLeast(1L),
            headers,
            speedLimitBytesPerSecond.coerceAtLeast(0L)
        )
        continuation.invokeOnCancellation { downloader.pause() }
        RUNNERS.execute {
            try {
                downloader.run()
                if (continuation.isActive) {
                    continuation.resumeWith(Result.failure(IllegalStateException("aria2c 已退出，但没有收到完成回调")))
                }
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            }
        }
    }

    private companion object {
        val RUNNERS = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "dsu-yunx-aria2").apply { isDaemon = true }
        }
    }
}
