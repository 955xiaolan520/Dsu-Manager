package com.probiotics.xiaoni

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.HapticFeedbackConstants
import android.view.ViewConfiguration
import android.webkit.URLUtil
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.yunx.app.ui.DsuDownloadListSource
import com.yunx.app.ui.DsuCornerAccents
import com.yunx.app.ui.DsuEmbeddedDownloadScreen
import com.yunx.app.ui.DsuGlassSnackbarHost
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.rememberGlobalSnackbarHostState
import com.yunx.app.ui.theme.ComposeEmptyActivityTheme
import org.json.JSONArray
import java.io.File

/**
 * Dsu 下载管理的独立页面：复用 YunX 下载组件的 UI 与交互，
 * 但只读取 Dsu 自己的任务/历史来源，不跳转工作区，也不显示网盘任务。
 */
class DownloadManagerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val transparency = getSharedPreferences("settings", MODE_PRIVATE)
            .getInt("window_transparency", 100)
        window.attributes = window.attributes.apply { alpha = transparency / 100f }
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent {
            ComposeEmptyActivityTheme(embeddedDsuStyle = true) {
                DsuDownloadManagerPage(onBack = { finish() })
            }
        }
    }
}

@Composable
private fun DsuDownloadManagerPage(onBack: () -> Unit) {
    val tapView = LocalView.current
    val tapSlopPx = ViewConfiguration.get(tapView.context).scaledTouchSlop.toFloat()
    val snackbarHostState = rememberGlobalSnackbarHostState()
    var showNewDownload by rememberSaveable { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFE0EAF2), Color(0xFFC9D9E8), Color(0xFFD7E2EB))
                )
            )
            .pointerInput(tapView, tapSlopPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var moved = false
                    var releasedAt = 0L
                    while (releasedAt == 0L) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if ((change.position - down.position).getDistance() > tapSlopPx) moved = true
                        if (!change.pressed) {
                            releasedAt = change.uptimeMillis
                            break
                        }
                    }
                    if (releasedAt > 0L && !moved && releasedAt - down.uptimeMillis < 550L) {
                        tapView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                }
            }
    ) {
        DsuCornerAccents(Color(0xFF6E82C5))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.Top
        ) {
            DownloadManagerHeader(
                onBack = onBack,
                onNewDownload = { showNewDownload = true }
            )
            DsuEmbeddedDownloadScreen(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                source = DsuDownloadListSource.Dsu
            )
        }
        DsuGlassSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 88.dp, start = 12.dp, end = 12.dp)
        )
    }
    if (showNewDownload) {
        NewDsuDownloadDialog(
            context = tapView.context,
            onDismiss = { showNewDownload = false }
        )
    }
}

@Composable
private fun DownloadManagerHeader(onBack: () -> Unit, onNewDownload: () -> Unit) {
    val view = LocalView.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 8.dp),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.82f)),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(Color(0x48FFFFFF), Color(0x25FFFFFF), Color(0x2263A9B8))
                    )
                )
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "返回",
                    tint = Color(0xFF17334F)
                )
            }
            Box(
                Modifier
                    .width(4.dp)
                    .height(38.dp)
                    .background(
                        Brush.verticalGradient(listOf(Color(0xFF45B4BE), Color(0xFF6587D3), Color(0xFF8F79C8))),
                        RoundedCornerShape(4.dp)
                    )
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "下载管理",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF17334F)
                )
                Text(
                    text = "Dsu aria2c · ROM 与直链任务",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF617A91)
                )
            }
            Surface(
                modifier = Modifier
                    .padding(end = 4.dp)
                    .clickable {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onNewDownload()
                    },
                shape = RoundedCornerShape(17.dp),
                color = Color(0x2D6CAFC1),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.78f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, tint = Color(0xFF327E8A), modifier = Modifier.size(18.dp))
                    Text("新建", color = Color(0xFF286F7D), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun NewDsuDownloadDialog(context: Context, onDismiss: () -> Unit) {
    val clipboardSeed = remember(context) { clipboardHttpUrl(context) }
    var url by rememberSaveable { mutableStateOf(clipboardSeed) }
    var fileName by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    val shape = RoundedCornerShape(28.dp)

    AlertDialog(
        modifier = Modifier.border(1.dp, Brush.linearGradient(listOf(Color.White, Color(0x8876C2CA), Color.White)), shape),
        onDismissRequest = onDismiss,
        shape = shape,
        containerColor = Color(0xE8E8F0F4),
        tonalElevation = 12.dp,
        icon = {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0x2D6CAFC1),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.88f))
            ) {
                Icon(Icons.Outlined.Link, contentDescription = null, tint = Color(0xFF327E8A), modifier = Modifier.padding(10.dp))
            }
        },
        title = { Text("新建下载", color = Color(0xFF17334F), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "粘贴 HTTP / HTTPS 文件直链，通过 Dsu aria2c 下载。默认保存至 Download/DsuManager。",
                    color = Color(0xFF617A91),
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = "" },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("下载链接") },
                    placeholder = { Text("https://example.com/file.zip") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    shape = RoundedCornerShape(18.dp)
                )
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("文件名（选填）") },
                    placeholder = { Text("留空时从链接自动识别") },
                    shape = RoundedCornerShape(18.dp)
                )
                if (error.isNotBlank()) {
                    Text(error, color = Color(0xFFB74E4A), style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val normalized = url.trim().replace("\\s".toRegex(), "")
                    if (!isHttpDownloadUrl(normalized)) {
                        error = "链接无效，请输入完整的 HTTP / HTTPS 下载地址"
                    } else if (submitDsuManualDownload(context, normalized, fileName)) {
                        onDismiss()
                    }
                },
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF287F87))
            ) { Text("加入下载队列", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF617A91))) {
                Text("取消")
            }
        }
    )
}

private fun clipboardHttpUrl(context: Context): String = runCatching {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val text = manager?.takeIf { it.hasPrimaryClip() }?.primaryClip
        ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
    if (isHttpDownloadUrl(text)) text else ""
}.getOrDefault("")

private fun isHttpDownloadUrl(value: String): Boolean = runCatching {
    val uri = Uri.parse(value)
    (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrBlank()
}.getOrDefault(false)

private fun submitDsuManualDownload(context: Context, url: String, suppliedName: String): Boolean {
    return runCatching {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val directory = File(downloads, "DsuManager")
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
            SnackbarController.show("无法创建 Download/DsuManager 目录")
            return false
        }
        val fromUrl = Uri.parse(url).lastPathSegment.orEmpty().ifBlank { URLUtil.guessFileName(url, null, null) }
        val rawName = suppliedName.trim().ifBlank { fromUrl }
        val cleaned = rawName.substringAfterLast('/').substringAfterLast('\\')
            .map { ch -> if (ch.code < 32 || ch in "\\/:*?\"<>|") '_' else ch }
            .joinToString("").trim().trim('.')
        val safeName = cleaned.take(180).ifBlank { "download_${System.currentTimeMillis()}.bin" }
        val dot = safeName.lastIndexOf('.')
        val stem = if (dot > 0) safeName.substring(0, dot) else safeName
        val extension = if (dot > 0) safeName.substring(dot) else ""

        fun inFlight(path: String): Boolean = listOf(DownloadService.RUNTIME_TASKS_PREFS, DownloadService.TASKS_PREFS)
            .any { prefsName ->
                val rows = runCatching {
                    JSONArray(context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString("items", "[]"))
                }.getOrDefault(JSONArray())
                (0 until rows.length()).any { index ->
                    val row = rows.optJSONObject(index) ?: return@any false
                    row.optString("output") == path || row.optString("id") == path
                }
            }

        var output = File(directory, safeName)
        var suffix = 1
        while (output.exists() || inFlight(output.absolutePath)) {
            output = File(directory, "$stem ($suffix)$extension")
            suffix++
        }
        val intent = Intent(context, DownloadService::class.java)
            .setAction(DownloadService.ACTION_START)
            .putExtra(DownloadService.EXTRA_ADDRESS, url)
            .putExtra(DownloadService.EXTRA_OUTPUT, output.absolutePath)
            .putExtra(DownloadService.EXTRA_PACKAGE, "链接下载")
            .putExtra(DownloadService.EXTRA_PAGE, "DownloadManagerActivity")
            .putExtra(DownloadService.EXTRA_SOURCE, DownloadService.SOURCE_DSU)
            .putExtra("download_threads", 4)
            .putExtra("download_chunk_mb", 16L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
        SnackbarController.show("已加入下载队列：${output.name}")
        true
    }.getOrElse { error ->
        SnackbarController.show("任务提交失败：${error.message ?: "请稍后重试"}")
        false
    }
}
