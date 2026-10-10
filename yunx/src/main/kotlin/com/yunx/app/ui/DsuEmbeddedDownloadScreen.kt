/*
 * YunX (云析) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 CYQawa
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.yunx.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DSU_DOWNLOAD_ACTION_PAUSE = "com.probiotics.xiaoni.download.PAUSE"
private const val DSU_DOWNLOAD_ACTION_RESUME = "com.probiotics.xiaoni.download.RESUME"
private const val DSU_DOWNLOAD_ACTION_CANCEL = "com.probiotics.xiaoni.download.CANCEL"
private const val DSU_DOWNLOAD_ACTION_QUERY = "com.probiotics.xiaoni.download.QUERY"
private const val DSU_DOWNLOAD_ACTION_UPDATE = "com.probiotics.xiaoni.download.UPDATE"
private const val DSU_DOWNLOAD_TASKS_PREFS = "download_tasks"
private const val DSU_DOWNLOAD_HISTORY_PREFS = "download_history"
private const val YUNX_DOWNLOAD_TASKS_PREFS = "yunx_download_tasks"
private const val YUNX_DOWNLOAD_HISTORY_PREFS = "yunx_download_history"
private const val DSU_FOLDER_ROOT_EXTRA = "com.probiotics.xiaoni.extra.FOLDER_ROOT"
private const val DEFAULT_YUNPAN_DIRECTORY = "/storage/emulated/0/Download/DsuManager/Yunpan"

enum class DsuDownloadListSource { Dsu, YunX }

private val DsuInk = Color(0xFF17334F)
private val DsuSubtleInk = Color(0xFF617A91)
private val DsuGlassBorder = Color(0xBFFFFFFF)
private val DsuGlassBrush = Brush.linearGradient(
    listOf(Color(0x20FFFFFF), Color(0x11FFFFFF), Color(0x1C6CB5C6), Color(0x14FFFFFF))
)

private data class DsuEmbeddedTask(
    val id: String,
    val name: String,
    val path: String,
    val packageName: String,
    val state: String,
    val message: String,
    val done: Long,
    val total: Long,
    val speed: Long,
    val eta: Long,
    val historical: Boolean = false,
    val historyStatus: String = "",
    val historyTime: Long = 0L,
    val folderRoot: String = "",
    val source: String = "yunx"
) {
    val isPaused: Boolean get() = state == "paused"
    val isPending: Boolean get() = state == "pending"
    val isFinished: Boolean get() = historical && historyStatus == "done"
    val isFailed: Boolean get() = historical && historyStatus != "done"
}

private data class DsuDeleteTarget(
    val label: String,
    val tasks: List<DsuEmbeddedTask>,
    val folderRoot: String = "",
    val wholeFolder: Boolean = false
)

private fun isYunxRow(context: Context, row: JSONObject): Boolean {
    when (row.optString("source", "")) {
        "yunx" -> return true
        "dsu" -> return false
    }
    if (row.optString("pkg", "").startsWith("网盘 ·")) return true
    val configured = context.getSharedPreferences("yunx_settings", Context.MODE_PRIVATE)
        .getString("dsu_download_dir_path", "")?.trim().orEmpty()
    val roots = listOf(configured, DEFAULT_YUNPAN_DIRECTORY).filter { it.isNotBlank() }
        .map { it.trimEnd('/') }.distinct()
    val paths = listOf(row.optString("output", ""), row.optString("path", ""), row.optString("folder_root", ""))
    return paths.any { path -> path.isNotBlank() && roots.any { root -> path == root || path.startsWith("$root/") } }
}

private fun readDsuEmbeddedTasks(context: Context, source: DsuDownloadListSource): List<DsuEmbeddedTask> {
    fun rows(name: String) = runCatching {
        JSONArray(context.getSharedPreferences(name, Context.MODE_PRIVATE).getString("items", "[]"))
    }.getOrDefault(JSONArray())
    val hostTasks = rows(DSU_DOWNLOAD_TASKS_PREFS)
    val active = JSONArray()
    if (source == DsuDownloadListSource.YunX) {
        val dedicated = rows(YUNX_DOWNLOAD_TASKS_PREFS)
        for (i in 0 until dedicated.length()) dedicated.optJSONObject(i)?.let(active::put)
        for (i in 0 until hostTasks.length()) {
            val row = hostTasks.optJSONObject(i) ?: continue
            if (isYunxRow(context, row)) active.put(row)
        }
    } else {
        for (i in 0 until hostTasks.length()) {
            val row = hostTasks.optJSONObject(i) ?: continue
            if (!isYunxRow(context, row)) active.put(row)
        }
    }
    val hostHistory = rows(DSU_DOWNLOAD_HISTORY_PREFS)
    val history = JSONArray()
    if (source == DsuDownloadListSource.YunX) {
        val dedicated = rows(YUNX_DOWNLOAD_HISTORY_PREFS)
        for (i in 0 until dedicated.length()) dedicated.optJSONObject(i)?.let(history::put)
        for (i in 0 until hostHistory.length()) {
            val row = hostHistory.optJSONObject(i) ?: continue
            if (isYunxRow(context, row)) history.put(row)
        }
    } else {
        for (i in 0 until hostHistory.length()) {
            val row = hostHistory.optJSONObject(i) ?: continue
            if (!isYunxRow(context, row)) history.put(row)
        }
    }
    val activePaths = HashSet<String>()
    val result = ArrayList<DsuEmbeddedTask>()
    val sourceTag = if (source == DsuDownloadListSource.YunX) "yunx" else "dsu"
    for (i in 0 until active.length()) {
        val row = active.optJSONObject(i) ?: continue
        val path = row.optString("output", "")
        val id = row.optString("id", path)
        activePaths += path
        result += DsuEmbeddedTask(
            id = id,
            name = File(path).name.ifBlank { "下载文件" },
            path = path,
            packageName = row.optString("pkg", if (sourceTag == "yunx") "网盘下载" else "Dsu 下载"),
            state = row.optString("state", "downloading"),
            message = row.optString("message", "正在连接下载节点"),
            done = row.optLong("done", -1),
            total = row.optLong("total", -1),
            speed = row.optLong("speed", -1),
            eta = row.optLong("eta", -1),
            folderRoot = row.optString("folder_root", ""),
            source = sourceTag
        )
    }
    for (i in history.length() - 1 downTo 0) {
        val row = history.optJSONObject(i) ?: continue
        val path = row.optString("path", "")
        if (path in activePaths) continue
        val time = row.optLong("time", i.toLong())
        result += DsuEmbeddedTask(
            id = "history:$path:$time",
            name = row.optString("name", File(path).name).ifBlank { "下载文件" },
            path = path,
            packageName = if (sourceTag == "yunx") "网盘下载" else "Dsu 下载",
            state = "finished",
            message = "",
            done = row.optLong("size", 0),
            total = row.optLong("size", 0),
            speed = -1,
            eta = -1,
            historical = true,
            historyStatus = row.optString("status", "done"),
            historyTime = time,
            folderRoot = row.optString("folder_root", ""),
            source = sourceTag
        )
    }
    return result
}

@Composable
fun DsuEmbeddedDownloadScreen(
    modifier: Modifier = Modifier,
    source: DsuDownloadListSource = DsuDownloadListSource.YunX
) {
    val context = LocalContext.current
    var tasks by remember(source) { mutableStateOf(readDsuEmbeddedTasks(context, source)) }
    var filter by remember { mutableStateOf("全部") }
    var pendingDelete by remember { mutableStateOf<DsuDeleteTarget?>(null) }
    fun refresh() { tasks = readDsuEmbeddedTasks(context, source) }
    fun sendAction(action: String, id: String? = null) {
        val intent = Intent().setClassName(context.packageName, "com.probiotics.xiaoni.DownloadService")
            .setAction(action)
        if (id != null) intent.putExtra("task_id", id)
        runCatching { context.startService(intent) }
        refresh()
    }
    fun openLocation(path: String, folderRoot: String = "") {
        val target = if (folderRoot.isNotBlank()) File(folderRoot) else File(path)
        if (!target.exists()) {
            SnackbarController.show("文件不存在")
            return
        }
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", target
            )
            val name = target.name.lowercase(Locale.ROOT)
            val mimeType = when {
                name.endsWith(".zip") -> "application/zip"
                name.endsWith(".img") || name.endsWith(".bin") -> "application/octet-stream"
                else -> "*/*"
            }
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(Intent.createChooser(intent, "打开方式"))
        }.onFailure { SnackbarController.show("没有可用的应用或无法访问文件") }
    }

    DisposableEffect(context, source) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) { refresh() }
        }
        val filterIntent = IntentFilter(DSU_DOWNLOAD_ACTION_UPDATE)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                ContextCompat.registerReceiver(context, receiver, filterIntent, ContextCompat.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filterIntent)
            }
        }
        sendDsuQuery(context)
        refresh()
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    LaunchedEffect(source) {
        while (true) {
            delay(1500)
            refresh()
        }
    }

    val visible = when (filter) {
        "进行中" -> tasks.filter { !it.historical }
        "已完成" -> tasks.filter { it.isFinished }
        "失败/取消" -> tasks.filter { it.historical && !it.isFinished }
        else -> tasks
    }
    val visibleFolderRoots = visible.map { it.folderRoot }.filter { it.isNotBlank() }.distinct()
    val folderGroups = visibleFolderRoots.map { root ->
        root to visible.filter { it.folderRoot == root }
    }
    val standalone = visible.filter { it.folderRoot.isBlank() }
    val activeCount = tasks.count { !it.historical }
    val completedCount = tasks.count { it.isFinished }
    val failedCount = tasks.count { it.historical && !it.isFinished }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GlassCard(accent = Color(0xFF57A98E)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SummaryMetric("进行中", activeCount.toString(), Color(0xFF248FA2))
                SummaryMetric("已完成", completedCount.toString(), Color(0xFF32946F))
                SummaryMetric("失败/取消", failedCount.toString(), Color(0xFFB76C5A))
                TextButton(onClick = { sendAction(DSU_DOWNLOAD_ACTION_QUERY); refresh() }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新", modifier = Modifier.size(18.dp),
                        tint = Color(0xFF327E8A))
                    Text("刷新", color = Color(0xFF327E8A))
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("全部", "进行中", "已完成", "失败/取消").forEach { option ->
                val selected = filter == option
                Column(
                    modifier = Modifier.weight(1f).clickable {
                        filter = option
                    }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(option, style = MaterialTheme.typography.labelMedium,
                        color = if (selected) Color(0xFF236F7D) else DsuSubtleInk,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(5.dp))
                    Box(Modifier.fillMaxWidth(.62f).height(2.dp).clip(RoundedCornerShape(50))
                        .background(if (selected) Color(0xFF58AFA6) else Color.Transparent))
                }
            }
        }

        if (visible.isEmpty()) {
            GlassCard(accent = Color(0xFF68A6AE)) {
                Column(Modifier.fillMaxWidth().padding(vertical = 30.dp, horizontal = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.CloudDownload, null, tint = Color(0xFF5097A1), modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("这里还没有下载任务", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, color = DsuInk)
                    Text(
                        if (source == DsuDownloadListSource.YunX) "网盘解析的下载会出现在此处"
                        else "Dsu ROM 与直链下载会出现在此处",
                        color = DsuSubtleInk
                    )
                }
            }
        } else {
            folderGroups.forEach { (folderRoot, groupVisible) ->
                val fullGroup = tasks.filter { it.folderRoot == folderRoot }
                DsuEmbeddedFolderCard(
                    folderRoot = folderRoot,
                    visibleTasks = groupVisible,
                    allTasks = fullGroup,
                    onOpenLocation = { openLocation(folderRoot, folderRoot) },
                    onDelete = {
                        pendingDelete = DsuDeleteTarget(
                            label = File(folderRoot).name.ifBlank { "下载文件夹" },
                            tasks = fullGroup,
                            folderRoot = folderRoot,
                            wholeFolder = true
                        )
                    },
                    onPauseResume = { task -> sendAction(
                        if (task.isPaused) DSU_DOWNLOAD_ACTION_RESUME else DSU_DOWNLOAD_ACTION_PAUSE, task.id
                    ) },
                    onCancel = { task -> sendAction(DSU_DOWNLOAD_ACTION_CANCEL, task.id) },
                    onOpenTask = { task -> openLocation(task.path, task.folderRoot) },
                    onDeleteTask = { task -> pendingDelete = DsuDeleteTarget(task.name, listOf(task), task.folderRoot) }
                )
            }
            standalone.forEach { task ->
                DsuDownloadTaskCard(
                    task = task,
                    onPauseResume = { sendAction(
                        if (task.isPaused) DSU_DOWNLOAD_ACTION_RESUME else DSU_DOWNLOAD_ACTION_PAUSE, task.id
                    ) },
                    onCancel = { sendAction(DSU_DOWNLOAD_ACTION_CANCEL, task.id) },
                    onOpenLocation = { openLocation(task.path) },
                    onDelete = { pendingDelete = DsuDeleteTarget(task.name, listOf(task)) }
                )
            }
        }
        Spacer(Modifier.height(if (source == DsuDownloadListSource.YunX) 132.dp else 18.dp))
    }

    pendingDelete?.let { target ->
        key(target.label + target.folderRoot + target.tasks.size) {
            var deleteLocal by remember { mutableStateOf(false) }
            val hasLocalFiles = if (target.wholeFolder && target.folderRoot.isNotBlank()) {
                File(target.folderRoot).exists()
            } else target.tasks.any { File(it.path).exists() }
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                shape = RoundedCornerShape(26.dp),
                containerColor = Color(0xFFE7F0F5),
                title = { Text("删除下载记录", color = DsuInk, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("确定删除「${target.label}」的下载记录吗？", color = DsuInk)
                        if (hasLocalFiles) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = deleteLocal, onCheckedChange = { deleteLocal = it })
                                Text(if (target.wholeFolder) "同时删除本地文件夹及其中内容" else "同时删除本地源文件",
                                    color = DsuInk)
                            }
                            Text("勾选后将一并删除设备上的本地文件，且不可恢复。",
                                style = MaterialTheme.typography.labelSmall, color = DsuSubtleInk)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        pendingDelete = null
                        deleteDsuDownloadTarget(context, target, deleteLocal, ::refresh, ::sendAction)
                    }) { Text("删除", color = Color(0xFFB74E4A), fontWeight = FontWeight.Bold) }
                },
                dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消", color = DsuSubtleInk) } }
            )
        }
    }
}

@Composable
private fun GlassCard(accent: Color, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(25.dp),
        border = BorderStroke(1.dp, DsuGlassBorder),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxWidth().background(DsuGlassBrush)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(
                Brush.horizontalGradient(listOf(Color.Transparent, accent.copy(alpha = .62f), Color.Transparent))
            ))
            content()
        }
    }
}

@Composable
private fun SummaryMetric(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = DsuSubtleInk)
    }
}

@Composable
private fun DsuEmbeddedFolderCard(
    folderRoot: String,
    visibleTasks: List<DsuEmbeddedTask>,
    allTasks: List<DsuEmbeddedTask>,
    onOpenLocation: () -> Unit,
    onDelete: () -> Unit,
    onPauseResume: (DsuEmbeddedTask) -> Unit,
    onCancel: (DsuEmbeddedTask) -> Unit,
    onOpenTask: (DsuEmbeddedTask) -> Unit,
    onDeleteTask: (DsuEmbeddedTask) -> Unit
) {
    var expanded by remember(folderRoot) { mutableStateOf(false) }
    val completed = allTasks.count { it.isFinished }
    val fileCount = allTasks.size
    val totalBytes = allTasks.sumOf { if (it.total > 0) it.total else it.done.coerceAtLeast(0) }
    val doneBytes = allTasks.sumOf {
        if (it.isFinished) it.done.coerceAtLeast(0) else it.done.coerceAtLeast(0)
    }.coerceAtMost(totalBytes)
    val fraction = if (totalBytes > 0) doneBytes.toFloat() / totalBytes else 0f
    val title = File(folderRoot).name.ifBlank { "网盘文件夹" }
    val stateLabel = when {
        allTasks.all { it.isFinished } -> "完成"
        allTasks.any { !it.historical } -> "下载中"
        completed > 0 -> "部分完成"
        else -> "失败/取消"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(25.dp),
        border = BorderStroke(1.dp, DsuGlassBorder),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxWidth().background(DsuGlassBrush)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(
                Brush.horizontalGradient(listOf(Color.Transparent, Color(0xAA4D9FA8), Color.Transparent))
            ))
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
                    .padding(start = 15.dp, end = 15.dp, top = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = "文件夹", tint = Color(0xFF328994),
                    modifier = Modifier.size(34.dp))
                Column(Modifier.weight(1f).padding(start = 11.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = DsuInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$completed / $fileCount 个文件 · ${formatDsuBytes(doneBytes)} / ${formatDsuBytes(totalBytes)}",
                        style = MaterialTheme.typography.bodySmall, color = DsuSubtleInk,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(stateLabel, style = MaterialTheme.typography.labelMedium,
                    color = if (stateLabel == "完成") Color(0xFF2C8966) else Color(0xFF327F91),
                    fontWeight = FontWeight.Bold)
            }
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp).height(5.dp).clip(RoundedCornerShape(50)),
                color = Color(0xFF3D9AA1), trackColor = Color(0x293D9AA1)
            )
            Text(
                if (expanded) "点击收起文件列表" else "${allTasks.count { !it.historical }} 项进行中 · 点击展开",
                modifier = Modifier.padding(start = 15.dp, end = 15.dp, top = 7.dp),
                style = MaterialTheme.typography.labelSmall, color = DsuSubtleInk
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpenLocation, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF327F91))
                    Text("打开位置", modifier = Modifier.padding(start = 5.dp), color = Color(0xFF327F91))
                }
                TextButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFFB35B55))
                    Text("删除", modifier = Modifier.padding(start = 5.dp), color = Color(0xFFB35B55))
                }
            }
            if (expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    visibleTasks.forEach { task ->
                        DsuDownloadTaskCard(
                            task = task,
                            onPauseResume = { onPauseResume(task) },
                            onCancel = { onCancel(task) },
                            onOpenLocation = { onOpenTask(task) },
                            onDelete = { onDeleteTask(task) },
                            compact = true
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DsuDownloadTaskCard(
    task: DsuEmbeddedTask,
    onPauseResume: () -> Unit,
    onCancel: () -> Unit,
    onOpenLocation: () -> Unit,
    onDelete: () -> Unit,
    compact: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (compact) 20.dp else 25.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = if (compact) .67f else .78f)),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxWidth().background(DsuGlassBrush)
            .padding(horizontal = if (compact) 12.dp else 15.dp, vertical = if (compact) 10.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when {
                        task.isFinished -> Icons.Outlined.CheckCircle
                        task.isFailed -> Icons.Outlined.ErrorOutline
                        else -> Icons.Outlined.CloudDownload
                    },
                    contentDescription = null,
                    tint = when {
                        task.isFinished -> Color(0xFF358A67)
                        task.isFailed -> Color(0xFFB35B55)
                        else -> Color(0xFF328994)
                    },
                    modifier = Modifier.size(if (compact) 24.dp else 27.dp)
                )
                Column(Modifier.weight(1f).padding(start = 9.dp)) {
                    Text(task.name, style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold, color = DsuInk, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(task.packageName, style = MaterialTheme.typography.labelSmall, color = DsuSubtleInk)
                }
                Text(
                    when {
                        task.isFinished -> "完成"
                        task.isFailed -> if (task.historyStatus == "cancelled") "已取消" else "失败"
                        task.isPaused -> "已暂停"
                        task.isPending -> "排队中"
                        else -> "下载中"
                    },
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                    color = when {
                        task.isFinished -> Color(0xFF27885F)
                        task.isFailed -> Color(0xFFAF594A)
                        else -> Color(0xFF317F95)
                    }
                )
            }
            if (!task.historical) {
                val progress = if (task.total > 0 && task.done >= 0) (task.done.toFloat() / task.total).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(
                    progress = { if (task.total > 0) progress else 0f },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)),
                    color = Color(0xFF3D9AA1), trackColor = Color(0x293D9AA1)
                )
            }
            Text(
                text = when {
                    task.isFinished -> "文件大小：${formatDsuBytes(task.done)}"
                    task.isFailed -> task.message.ifBlank { "下载任务已结束" }
                    task.isPaused -> "已下载 ${formatDsuBytes(task.done)} / ${formatDsuBytes(task.total)}"
                    task.isPending -> task.message.ifBlank { "等待下载队列" }
                    task.total > 0 -> "${(task.done.coerceAtLeast(0) * 100 / task.total).coerceIn(0, 100)}% · ${formatDsuBytes(task.done)} / ${formatDsuBytes(task.total)}${if (task.speed > 0) " · ${formatDsuBytes(task.speed)}/s" else ""}"
                    task.done >= 0 -> "已下载 ${formatDsuBytes(task.done)} · 正在获取文件大小"
                    else -> task.message.ifBlank { "正在连接下载节点" }
                },
                style = MaterialTheme.typography.bodySmall, color = DsuSubtleInk
            )
            if (task.path.isNotBlank()) {
                Text(task.path, style = MaterialTheme.typography.labelSmall, color = Color(0xFF648094),
                    maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
            }
            if (!task.historical) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onPauseResume, enabled = !task.isPending,
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                        Icon(if (task.isPaused) Icons.Outlined.PlayCircle else Icons.Outlined.PauseCircle, null,
                            modifier = Modifier.size(18.dp))
                        Text(if (task.isPaused) "继续" else if (task.isPending) "排队" else "暂停",
                            modifier = Modifier.padding(start = 4.dp), maxLines = 1)
                    }
                    TextButton(onClick = onCancel, modifier = Modifier.weight(.82f)) {
                        Icon(Icons.Outlined.StopCircle, null, modifier = Modifier.size(18.dp), tint = Color(0xFFAA625C))
                        Text("取消", modifier = Modifier.padding(start = 3.dp), color = Color(0xFFAA625C))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDelete, modifier = Modifier.weight(.82f)) {
                    Icon(Icons.Outlined.Delete, null, modifier = Modifier.size(18.dp), tint = Color(0xFFB35B55))
                    Text("删除", modifier = Modifier.padding(start = 3.dp), color = Color(0xFFB35B55))
                }
                TextButton(onClick = onOpenLocation, modifier = Modifier.weight(1.18f)) {
                    Icon(Icons.Outlined.FolderOpen, null, modifier = Modifier.size(18.dp), tint = Color(0xFF327F91))
                    Text("打开位置", modifier = Modifier.padding(start = 3.dp), color = Color(0xFF327F91), maxLines = 1)
                }
            }
        }
    }
}

private fun deleteDsuDownloadTarget(
    context: Context,
    target: DsuDeleteTarget,
    deleteLocal: Boolean,
    refresh: () -> Unit,
    sendAction: (String, String?) -> Unit
) {
    val active = target.tasks.filter { !it.historical }
    active.forEach { sendAction(DSU_DOWNLOAD_ACTION_CANCEL, it.id) }
    removeDsuHistory(context, target.tasks, target.folderRoot, target.wholeFolder)

    fun deleteLocalFiles() {
        if (!deleteLocal) return
        if (target.wholeFolder && target.folderRoot.isNotBlank()) {
            runCatching { File(target.folderRoot).deleteRecursively() }
        } else {
            target.tasks.forEach { task -> runCatching { File(task.path).deleteRecursively() } }
        }
    }
    if (active.isEmpty()) deleteLocalFiles()
    else Handler(Looper.getMainLooper()).postDelayed({
        deleteLocalFiles()
        removeDsuHistory(context, target.tasks, target.folderRoot, target.wholeFolder)
        refresh()
    }, 1500L)
    refresh()
}

private fun removeDsuHistory(
    context: Context,
    tasks: List<DsuEmbeddedTask>,
    folderRoot: String,
    wholeFolder: Boolean
) {
    runCatching {
        val isYunx = tasks.firstOrNull()?.source == "yunx"
        val prefsNames = if (isYunx) {
            listOf(YUNX_DOWNLOAD_HISTORY_PREFS, DSU_DOWNLOAD_HISTORY_PREFS)
        } else {
            listOf(DSU_DOWNLOAD_HISTORY_PREFS)
        }
        val signatures = tasks.filter { it.historical }.map { "${it.path}\u0000${it.historyTime}" }.toSet()
        val activePaths = tasks.filter { !it.historical }.map { it.path }.toSet()
        prefsNames.forEach { prefsName ->
            val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            val old = JSONArray(prefs.getString("items", "[]"))
            val updated = JSONArray()
            for (i in 0 until old.length()) {
                val row = old.optJSONObject(i) ?: continue
                if (isYunxRow(context, row) != isYunx) {
                    updated.put(row)
                    continue
                }
                val path = row.optString("path", "")
                val time = row.optLong("time", 0L)
                val matchesGroup = wholeFolder && folderRoot.isNotBlank() && row.optString("folder_root", "") == folderRoot
                val matchesItem = "${path}\u0000${time}" in signatures || path in activePaths
                if (!matchesGroup && !matchesItem) updated.put(row)
            }
            prefs.edit().putString("items", updated.toString()).apply()
        }
    }
}

private fun sendDsuQuery(context: Context) {
    runCatching {
        context.startService(Intent().setClassName(context.packageName, "com.probiotics.xiaoni.DownloadService")
            .setAction(DSU_DOWNLOAD_ACTION_QUERY))
    }
}

private fun formatDsuBytes(bytes: Long): String {
    if (bytes < 0) return "--"
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024L * 1024) return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    if (bytes < 1024L * 1024 * 1024) return String.format(Locale.getDefault(), "%.1f MB", bytes / 1048576.0)
    return String.format(Locale.getDefault(), "%.2f GB", bytes / 1073741824.0)
}
