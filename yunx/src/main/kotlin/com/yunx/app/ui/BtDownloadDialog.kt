/*
 * YunX (云析) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 CYQawa
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License v3 or later.
 */
package com.yunx.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunx.app.data.gopeed.BtTorrentFile
import com.yunx.app.data.gopeed.BtTorrentResource
import com.yunx.app.data.gopeed.TorrentResolver
import com.yunx.app.data.download.DsuNativeDownloadRouter
import com.yunx.app.data.download.MagnetLink
import kotlinx.coroutines.launch
import java.util.Locale

/** Shared magnetic/torrent picker used by both the resolver page and the integrated download manager. */
@Composable
fun BtDownloadDialog(
    title: String = "种子 / 磁力下载",
    onDismiss: () -> Unit,
    onSubmit: (url: String, name: String, selectedFiles: List<Int>) -> Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var resource by remember { mutableStateOf<BtTorrentResource?>(null) }
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var directFallbackMagnet by remember { mutableStateOf<String?>(null) }
    var timedOutMagnet by remember { mutableStateOf<String?>(null) }

    val resolve: (String) -> Unit = { value ->
        val raw = value.trim()
        if (raw.isNotBlank() && !busy) {
            busy = true
            error = null
            directFallbackMagnet = null
            scope.launch {
                try {
                    val result = TorrentResolver.resolve(context, raw)
                    resource = result
                    selected = result.files.map { it.index }.toSet()
                } catch (e: Throwable) {
                    val message = e.message.orEmpty()
                    error = if (message.contains("引擎调用超时") && message.contains("/api/v1/resolve")) {
                        directFallbackMagnet = TorrentResolver.extractMagnet(raw)
                        timedOutMagnet = directFallbackMagnet
                        "磁力元数据 30 秒内未返回。Gopeed 的本次解析仍在后台等待数据源；为避免重复占用引擎，不会再次解析同一链接。可直接加入下载队列，或重启内核后再试。"
                    } else message.take(220).ifBlank { "种子解析失败" }
                } finally {
                    busy = false
                }
            }
        }
    }
    LaunchedEffect(resource) {
        if (resource != null) error = null
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        shape = RoundedCornerShape(28.dp),
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (resource == null) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = {
                            input = it
                            directFallbackMagnet = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("magnet 链接或 .torrent 地址") },
                        placeholder = { Text("magnet:?xt=urn:btih:…") },
                        minLines = 2,
                        maxLines = 4,
                        shape = RoundedCornerShape(16.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { resolve(input) },
                            enabled = !busy && input.isNotBlank() &&
                                (TorrentResolver.extractMagnet(input) ?: input.trim()) != timedOutMagnet
                        ) {
                            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Text(if ((TorrentResolver.extractMagnet(input) ?: input.trim()) == timedOutMagnet) "重启引擎后再解析" else "解析种子")
                        }
                        TextButton(onClick = {
                            val opened = DsuNativeDownloadRouter.pickTorrentFile(context) { path ->
                                if (path != null) resolve(path)
                            }
                            if (!opened) error = "Dsu 内置文件管理器暂不可用，请从「下载引擎」入口检查"
                        }, enabled = !busy) {
                            androidx.compose.material3.Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Dsu 文件管理器")
                        }
                    }
                } else {
                    val info = resource!!
                    Text(info.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${selected.size}/${info.files.size} 个文件 · ${formatBtBytes(info.files.filter { it.index in selected }.sumOf { it.size })}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { selected = info.files.map { it.index }.toSet() }) { Text("全选") }
                        TextButton(onClick = { selected = emptySet() }) { Text("全不选") }
                        TextButton(onClick = { resource = null; error = null }) { Text("重新输入") }
                    }
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                        items(info.files, key = { it.index }) { file ->
                            TorrentFileRow(file, checked = file.index in selected) {
                                selected = if (file.index in selected) selected - file.index else selected + file.index
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                directFallbackMagnet?.let { magnet ->
                    TextButton(
                        onClick = {
                            val accepted = onSubmit(magnet, MagnetLink.displayName(magnet), emptyList())
                            if (accepted) onDismiss()
                            else error = "提交 BT 下载任务失败，请检查 Gopeed 内核和下载目录设置"
                        },
                        enabled = !busy
                    ) {
                        Text("不选文件，直接下载全部")
                    }
                }
                TextButton(
                    onClick = {
                        if (DsuNativeDownloadRouter.openGopeedEngineManager(context)) {
                            timedOutMagnet = null
                            directFallbackMagnet = null
                            error = null
                        } else {
                            error = "无法打开 Gopeed BT/磁力引擎管理页"
                        }
                    },
                    enabled = !busy
                ) {
                    Text("下载 / 管理 Gopeed BT 内核")
                }
                Text(
                    "BT/磁力由 Gopeed 的 BT 协议连接 peers 并按分片传输，不走 aria2c 的 HTTP 多线程通道；普通网盘/HTTP 下载仍按原下载引擎处理。解析前会自动检查并启动已导入的 Gopeed 内核，最终文件写入任务指定目录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (busy) {
                    Text(
                        "正在检测并自动启动 Gopeed BT 内核。磁力元数据最多等待 30 秒；若无可用 Tracker/peer，将提供直接加入下载队列的选项。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (resource != null) Spacer(Modifier.height(2.dp))
            }
        },
        confirmButton = {
            if (resource != null) {
                Button(
                    onClick = {
                        val info = resource ?: return@Button
                        if (selected.isEmpty()) {
                            error = "至少选择一个文件"
                        } else if (onSubmit(info.requestUrl, info.name, selected.sorted())) {
                            onDismiss()
                        } else {
                            error = "提交下载任务失败，请检查 Gopeed 内核和下载目录设置"
                        }
                    },
                    enabled = !busy && selected.isNotEmpty()
                ) { Text("加入下载队列") }
            } else {
                TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") }
            }
        },
        dismissButton = {
            if (resource != null) TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        }
    )
}

@Composable
private fun TorrentFileRow(file: BtTorrentFile, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(file.path, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            Text(formatBtBytes(file.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatBtBytes(size: Long): String {
    if (size <= 0L) return "大小未知"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = size.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
    return String.format(Locale.ROOT, "%.2f %s", value, units[unit])
}
