package com.probiotics.xiaoni

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.net.Uri
import android.widget.Toast
import com.yunx.app.data.download.DsuNativeDownloadRouter
import com.yunx.app.data.security.DownloadRequestVault
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Submits YunX share downloads to the existing Dsu aria2c task service. */
object DsuNativeDownloadSubmitter : DsuNativeDownloadRouter.Submitter {
    override fun submit(
        context: Context,
        url: String,
        fileName: String,
        headers: Map<String, String>,
        platform: String,
        onComplete: suspend () -> Unit
    ): Boolean {
        val app = context.applicationContext
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri == null || (uri.scheme != "http" && uri.scheme != "https") || uri.host.isNullOrBlank()) {
            notify(app, "Dsu aria2c 仅支持有效的 HTTP(S) 文件直链")
            return false
        }
        if (uri.path.orEmpty().endsWith(".m3u8", ignoreCase = true) ||
            uri.toString().contains(".m3u8?", ignoreCase = true)) {
            notify(app, "此分享是 HLS/m3u8 流，当前 Dsu 下载通道不支持")
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            notify(app, "请先允许 Dsu 管理器访问下载目录")
            runCatching {
                val settingsIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    .setData(Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(settingsIntent)
            }
            return false
        }

        val configuredPath = app.getSharedPreferences("yunx_settings", Context.MODE_PRIVATE)
            .getString("dsu_download_dir_path", "")?.trim().orEmpty()
        val directory = runCatching {
            if (configuredPath.isBlank()) {
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DsuManager/Yunpan")
            } else {
                File(configuredPath).canonicalFile
            }
        }.getOrElse {
            notify(app, "下载目录无效，请重新选择文件夹")
            return false
        }
        val relativeSegments = safeRelativeSegments(fileName.ifBlank { uri.lastPathSegment ?: "download.bin" })
        if (relativeSegments.isEmpty()) {
            notify(app, "文件名或文件夹路径无效")
            return false
        }
        if (!directory.isAbsolute || directory.path == "/") {
            notify(app, "下载目录无效，请重新选择文件夹")
            return false
        }
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) {
            notify(app, "无法创建网盘下载目录：${directory.absolutePath}")
            return false
        }
        val rootDirectory = runCatching { directory.canonicalFile }.getOrElse {
            notify(app, "下载目录无效，请重新选择文件夹")
            return false
        }
        val outputDirectory = runCatching {
            relativeSegments.dropLast(1).fold(rootDirectory) { parent, segment -> File(parent, segment) }.canonicalFile
        }.getOrElse {
            notify(app, "文件夹路径无效")
            return false
        }
        if (outputDirectory != rootDirectory && !outputDirectory.path.startsWith(rootDirectory.path + File.separator)) {
            notify(app, "文件夹路径无效")
            return false
        }
        if ((!outputDirectory.exists() && !outputDirectory.mkdirs()) || !outputDirectory.isDirectory) {
            notify(app, "无法创建网盘文件夹：${outputDirectory.absolutePath}")
            return false
        }
        val probe = File(outputDirectory, ".dsu_write_test_${System.currentTimeMillis()}")
        if (!runCatching { probe.createNewFile() && probe.delete() }.getOrDefault(false)) {
            notify(app, "无法写入网盘下载目录，请检查文件访问权限")
            return false
        }

        val safeName = relativeSegments.last()
        val output = uniqueOutput(app, outputDirectory, safeName)
        val folderRoot = if (relativeSegments.size > 1) {
            runCatching { File(rootDirectory, relativeSegments.first()).canonicalPath }.getOrDefault("")
        } else ""
        val encryptedHeaders = try {
            if (headers.isEmpty()) "" else DownloadRequestVault.encryptHeaders(JSONObject(headers).toString())
        } catch (error: Throwable) {
            notify(app, "下载授权信息加密失败：${error.message ?: "请重新登录网盘"}")
            return false
        }

        val taskId = output.absolutePath
        DsuNativeDownloadRouter.trackCompletion(taskId, onComplete)
        val intent = Intent(app, DownloadService::class.java)
            .setAction(DownloadService.ACTION_START)
            .putExtra(DownloadService.EXTRA_ADDRESS, url)
            .putExtra(DownloadService.EXTRA_OUTPUT, taskId)
            .putExtra(DownloadService.EXTRA_PACKAGE, "网盘 · $platform")
            .putExtra(DownloadService.EXTRA_PAGE, "DownloadManagerActivity")
            .putExtra(DownloadService.EXTRA_FOLDER_ROOT, folderRoot)
            .putExtra(DownloadService.EXTRA_SOURCE, DownloadService.SOURCE_YUNX)
            .putExtra("download_threads", 16)
            .putExtra("download_chunk_mb", 8L)
            .putExtra(DownloadService.EXTRA_HEADERS_ENCRYPTED, encryptedHeaders)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent)
            else app.startService(intent)
            true
        } catch (error: Throwable) {
            DsuNativeDownloadRouter.forgetCompletion(taskId)
            notify(app, "提交到 Dsu 下载管理失败：${error.message ?: "请稍后重试"}")
            false
        }
    }

    private fun uniqueOutput(context: Context, directory: File, name: String): File {
        val base = name.substringBeforeLast('.', name).ifBlank { name }
        val suffix = name.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
        val stores = listOf(DownloadService.TASKS_PREFS, DownloadService.YUNX_TASKS_PREFS).map { name ->
            val store = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            runCatching { JSONArray(store.getString("items", "[]")) }.getOrDefault(JSONArray())
        }
        fun isInFlight(path: String): Boolean = stores.any { rows ->
            (0 until rows.length()).any { index ->
                val row = rows.optJSONObject(index) ?: return@any false
                row.optString("output") == path || row.optString("id") == path
            }
        }
        var candidate = File(directory, name)
        var index = 1
        while (candidate.exists() || isInFlight(candidate.absolutePath)) {
            candidate = File(directory, "$base ($index)$suffix")
            index++
        }
        return candidate
    }

    private fun sanitizeName(value: String): String {
        val basename = value.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = basename.map { c ->
            if (c.code < 32 || c in "\\/:*?\"<>|") '_' else c
        }.joinToString("").trim().trim('.')
        return cleaned.take(180).ifBlank { "download_${System.currentTimeMillis()}.bin" }
    }

    private fun safeRelativeSegments(value: String): List<String> = value
        .replace('\\', '/')
        .split('/')
        .filter { it.isNotBlank() && it != "." && it != ".." }
        .map(::sanitizeName)
        .filter { it.isNotBlank() }

    private fun notify(context: Context, text: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }
}
