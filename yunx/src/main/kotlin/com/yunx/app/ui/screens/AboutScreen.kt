/*
 * YunX (云析) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 CYQawa
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.yunx.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import android.content.Intent
import android.net.Uri
import com.yunx.app.R
import com.yunx.app.util.AppLinks
import android.graphics.Bitmap
import android.graphics.Canvas

/**
 * 关于云析页：应用介绍、支持平台、功能特性、技术栈与免责声明。
 * Material3 风格：卡片分区 + 主题色 + 动态色适配。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onPreviewOnboarding: () -> Unit,
    modifier: Modifier = Modifier,
    embeddedMode: Boolean = false
) {
    val context = LocalContext.current
    var showLicenseDialog by remember { mutableStateOf(false) }
    val agplText = remember {
        runCatching { context.assets.open("AGPL-3.0.txt").bufferedReader().use { it.readText() } }
            .getOrElse { "GNU Affero General Public License version 3.0. The license text is included with the source repository." }
    }
    // 系统返回键 → 返回主页（而不是退出应用）
    BackHandler { onBack() }
    val pkgInfo = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    }
    val versionName = pkgInfo?.versionName ?: "1.0"
    val versionCode = pkgInfo?.versionCode ?: 1

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = if (embeddedMode) Color.Transparent else MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (embeddedMode) "关于本功能由来" else "关于云析", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (embeddedMode) Color.Transparent else MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = if (embeddedMode) 132.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (embeddedMode) {
                SectionCard(embeddedMode = true) {
                    AppHeader(versionName = versionName, versionCode = versionCode, embeddedMode = true)
                }
                SectionCard(embeddedMode = true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CardIcon(Icons.Outlined.Info, embeddedMode = true)
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text("原作者", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("CYQawa · 云析 YunX", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                InfoCard(
                    icon = Icons.Outlined.Info,
                    title = "功能由来",
                    description = "本功能集成自 CYQawa/YunX 开源网盘解析项目，保留多平台登录、分享解析与文件浏览能力，并适配 Dsu 管理器原生 aria2c 下载通道及 DNA 液态玻璃界面。",
                    embeddedMode = true
                )
                FeatureCard(embeddedMode = true)
                GitHubCard(context, embeddedMode = true)
                SectionCard(embeddedMode = true) {
                    Text(
                        text = "开源许可",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "集成源码来自 CYQawa/YunX（© 2026），并按 GNU AGPL-3.0-or-later 分发。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 22.sp
                    )
                    TextButton(onClick = { showLicenseDialog = true }) {
                        Text("查看完整 GNU AGPL-3.0 许可证")
                    }
                }
            } else {
                AppHeader(versionName = versionName, versionCode = versionCode, embeddedMode = false)
                InfoCard(
                    icon = Icons.Outlined.Cloud,
                    title = "应用简介",
                    description = "云析（YunX）是一款网盘分享链接解析与高速下载工具。粘贴分享链接，登录网盘账号后即可浏览分享内容并直接高速下载文件。"
                )
                PlatformCard()
                FeatureCard()
                TechCard()
                DisclaimerCard()
                Text(
                    text = "本项目基于 GNU AGPL-3.0 协议开源",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                PreviewOnboardingCard(onClick = onPreviewOnboarding)
                GitHubCard(context)
            }

            if (embeddedMode && showLicenseDialog) {
                AlertDialog(
                    modifier = Modifier.border(
                        1.dp,
                        Brush.linearGradient(listOf(Color.White, Color(0x8876C2CA), Color.White)),
                        RoundedCornerShape(28.dp)
                    ),
                    onDismissRequest = { showLicenseDialog = false },
                    shape = RoundedCornerShape(28.dp),
                    containerColor = if (embeddedMode) Color(0xE8E8F0F4) else MaterialTheme.colorScheme.surface,
                    tonalElevation = 12.dp,
                    title = { Text("GNU AGPL-3.0 · 完整许可证") },
                    text = {
                        Column(
                            modifier = Modifier
                                .height(420.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(agplText, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showLicenseDialog = false }) { Text("关闭") }
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (embeddedMode) {
                    "YunX © 2026 CYQawa · integrated into Dsu Manager"
                } else {
                    "云析 v$versionName · Made with ❤ and deepseek"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

/** App 头部：渐变图标 + 应用名 + 版本 + 标语 */
@Composable
private fun AppHeader(versionName: String, versionCode: Int, embeddedMode: Boolean) {
    val context = LocalContext.current
    val hostIcon = remember(context.packageName, embeddedMode) {
        if (!embeddedMode) null else runCatching {
            val drawable = context.packageManager.getApplicationIcon(context.packageName)
            Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888).also { bitmap ->
                drawable.setBounds(0, 0, 192, 192)
                drawable.draw(Canvas(bitmap))
            }.asImageBitmap()
        }.getOrNull()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .background(
                    brush = Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.tertiary
                        )
                    ),
                    shape = RoundedCornerShape(24.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (hostIcon != null) {
                Image(
                    bitmap = hostIcon,
                    contentDescription = "Dsu 管理器图标",
                    modifier = Modifier.size(88.dp).clip(RoundedCornerShape(24.dp)),
                    contentScale = ContentScale.Fit
                )
            } else {
                Image(
                    painter = painterResource(R.drawable.icon),
                    contentDescription = "云析图标",
                    modifier = Modifier.size(88.dp).clip(RoundedCornerShape(24.dp)),
                    contentScale = ContentScale.Crop
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = if (embeddedMode) "网盘解析功能" else "云析",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (embeddedMode) "Dsu 管理器 · 开源功能集成" else "YunX · v$versionName ($versionCode)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (embeddedMode) "多平台分享解析 · Dsu aria2c 高速下载" else "网盘链接解析与高速下载",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

/** 通用信息卡片：图标 + 标题 + 描述 */
@Composable
private fun InfoCard(icon: ImageVector, title: String, description: String, embeddedMode: Boolean = false) {
    SectionCard(embeddedMode = embeddedMode) {
        Row(verticalAlignment = Alignment.Top) {
            CardIcon(icon, embeddedMode = embeddedMode)
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 22.sp
                )
            }
        }
    }
}

/** 支持平台卡片 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlatformCard() {
    val platforms = listOf(
        "夸克网盘" to Icons.Outlined.Cloud,
        "UC 网盘" to Icons.Outlined.Storage,
        "迅雷网盘" to Icons.Outlined.Speed,
        "百度网盘" to Icons.Outlined.Link,
        "139 网盘" to Icons.Outlined.Cloud,
        "123云盘" to Icons.Outlined.Cloud,
        "115网盘" to Icons.Outlined.Cloud,
        "光鸭云盘" to Icons.Outlined.Cloud,
        "蓝奏云优享版" to Icons.Outlined.Cloud,
        "蓝奏云" to Icons.Outlined.Cloud
    )
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardIcon(Icons.Outlined.Storage)
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = "支持平台",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            platforms.forEach { (name, icon) ->
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = name,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }
    }
}

/** 功能特性卡片 */
@Composable
private fun FeatureCard(embeddedMode: Boolean = false) {
    val features = listOf(
        "一键解析分享链接" to "夸克 / UC / 迅雷 / 百度 / 139 / 123 / 115 / 光鸭 / 蓝奏云 / 蓝奏云优享版 分享直链识别",
        "高速分片下载" to "多线程并发 + 断点续传，充分利用带宽",
        "取链即删" to "转存后立即清理，不留残留",
        "凭证本地化" to "Cookie 加密落库，仅存本机"
    )
    SectionCard(embeddedMode = embeddedMode) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardIcon(Icons.Outlined.CheckCircle, embeddedMode = embeddedMode)
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = "功能特性",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        features.forEach { (title, desc) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            if (embeddedMode) Color(0xFF4E9FA9) else MaterialTheme.colorScheme.primary,
                            CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

/** 技术栈卡片 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TechCard() {
    val techs = listOf("Kotlin", "Jetpack Compose", "Material 3", "Room", "OkHttp", "KSP")
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardIcon(Icons.Outlined.Code)
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = "技术栈",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            techs.forEach { name ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Text(
                        text = name,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 免责声明卡片 */
@Composable
private fun DisclaimerCard() {
    SectionCard {
        Row(verticalAlignment = Alignment.Top) {
            CardIcon(Icons.Outlined.Shield)
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = "免责声明",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "本应用仅供个人学习与技术交流使用，请勿用于任何商业用途。" +
                        "下载内容版权归原作者所有，请于下载后 24 小时内删除。" +
                        "使用本应用产生的任何后果由使用者自行承担。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 22.sp
                )
            }
        }
    }
}

/** 重新预览欢迎界面入口 */
@Composable
private fun PreviewOnboardingCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "重新预览欢迎界面",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "重新展示首次启动引导页",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/** 卡片容器统一风格 */
@Composable
private fun SectionCard(embeddedMode: Boolean = false, content: @Composable () -> Unit) {
    val cardShape = RoundedCornerShape(25.dp)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (embeddedMode) Color(0xBFFFFFFF) else Color.White.copy(alpha = 0.86f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (embeddedMode) 0.dp else 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (embeddedMode) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (embeddedMode) Modifier.background(
                        Brush.linearGradient(
                            listOf(Color(0x20FFFFFF), Color(0x11FFFFFF), Color(0x1C6CB5C6), Color(0x14FFFFFF))
                        )
                    ) else Modifier
                )
                .padding(18.dp)
        ) {
            content()
        }
    }
}

/** 卡片图标圆形底 */
@Composable
private fun CardIcon(icon: ImageVector, embeddedMode: Boolean = false) {
    Surface(
        modifier = Modifier.size(40.dp),
        shape = if (embeddedMode) RoundedCornerShape(14.dp) else CircleShape,
        color = if (embeddedMode) Color(0x25FFFFFF) else MaterialTheme.colorScheme.primaryContainer,
        border = if (embeddedMode) androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.78f)) else null
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (embeddedMode) Color(0xFF327E8A) else MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

/** 开源仓库入口卡片 */
@Composable
private fun GitHubCard(context: android.content.Context, embeddedMode: Boolean = false) {
    val cardShape = RoundedCornerShape(27.dp)
    Card(
        onClick = {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AppLinks.GITHUB_REPO))
            context.startActivity(intent)
        },
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (embeddedMode) Color(0xBFFFFFFF) else Color.White.copy(alpha = 0.86f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (embeddedMode) 0.dp else 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (embeddedMode) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (embeddedMode) Modifier.background(
                        Brush.linearGradient(
                            listOf(Color(0x20FFFFFF), Color(0x11FFFFFF), Color(0x1C6CB5C6), Color(0x14FFFFFF))
                        )
                    ) else Modifier
                )
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = if (embeddedMode) RoundedCornerShape(14.dp) else CircleShape,
                color = if (embeddedMode) Color(0x25FFFFFF) else MaterialTheme.colorScheme.primaryContainer,
                border = if (embeddedMode) androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.78f)) else null
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Code,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (embeddedMode) Color(0xFF327E8A) else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "开源仓库",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = AppLinks.GITHUB_REPO_DISPLAY,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Outlined.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.outline
            )
          }
        }
    }
}
