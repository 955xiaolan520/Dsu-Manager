package com.probiotics.xiaoni

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import com.yunx.app.data.security.DownloadRequestVault
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Submits short-video downloads to Dsu's own persistent queue and task partition. */
object ShortVideoQueue {
    @JvmOverloads
    @JvmStatic
    fun enqueue(
        context: Context,
        url: String,
        fileName: String,
        platform: String,
        headers: Map<String, String>,
        lyrics: String = ""
    ): Boolean = runCatching {
        val app = context.applicationContext
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val directory = File(File(downloads, "DsuManager"), "短视频解析")
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) return false

        val safeName = fileName.substringAfterLast('/').substringAfterLast('\\')
            .map { ch -> if (ch.code < 32 || ch in "\\/:*?\"<>|") '_' else ch }
            .joinToString("").trim().trim('.')
            .take(180)
            .ifBlank { "short_video_${System.currentTimeMillis()}.mp4" }
        val stem = safeName.substringBeforeLast('.', safeName).ifBlank { safeName }
        val ext = safeName.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }

        fun inFlight(path: String): Boolean = listOf(
            DownloadService.RUNTIME_TASKS_PREFS,
            DownloadService.TASKS_PREFS
        ).any { prefsName ->
            val rows = runCatching {
                JSONArray(app.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString("items", "[]"))
            }.getOrDefault(JSONArray())
            (0 until rows.length()).any { index ->
                val row = rows.optJSONObject(index) ?: return@any false
                row.optString("output") == path || row.optString("id") == path
            }
        }

        var output = File(directory, safeName)
        var suffix = 1
        while (output.exists() || inFlight(output.absolutePath)
            || (lyrics.isNotBlank() && File(directory, output.nameWithoutExtension + ".lrc").exists())) {
            output = File(directory, "$stem ($suffix)$ext")
            suffix++
        }
        val encryptedHeaders = if (headers.isEmpty()) "" else
            DownloadRequestVault.encryptHeaders(JSONObject(headers).toString())
        val intent = Intent(app, DownloadService::class.java)
            .setAction(DownloadService.ACTION_START)
            .putExtra(DownloadService.EXTRA_ADDRESS, url)
            .putExtra(DownloadService.EXTRA_OUTPUT, output.absolutePath)
            .putExtra(DownloadService.EXTRA_PACKAGE, "短视频 · ${platform.ifBlank { "分享解析" }}")
            .putExtra(DownloadService.EXTRA_PAGE, "DownloadManagerActivity")
            .putExtra(DownloadService.EXTRA_SOURCE, DownloadService.SOURCE_DSU)
            .putExtra(DownloadService.EXTRA_HEADERS_ENCRYPTED, encryptedHeaders)
            .putExtra("download_threads", 4)
            .putExtra("download_chunk_mb", 16L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent)
        else app.startService(intent)
        if (lyrics.isNotBlank()) {
            runCatching {
                File(directory, output.nameWithoutExtension + ".lrc").writeText(lyrics.trim() + "\n", Charsets.UTF_8)
            }
        }
        true
    }.getOrDefault(false)
}
