package com.probiotics.xiaoni

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.yunx.app.ui.DsuCornerAccents
import com.yunx.app.ui.screens.DownloadEngineScreen
import com.yunx.app.ui.theme.ComposeEmptyActivityTheme

/** Dsu 下载管理页内的 BT/磁力核心快捷入口；Gopeed 内核按需下载，不随 APK 分发。 */
class DsuGopeedEngineActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ComposeEmptyActivityTheme(embeddedDsuStyle = true) {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color(0xFFD9E6EF), Color(0xFFB6CAD9), Color(0xFF8AA6BA)))
                    )
                ) {
                    DsuCornerAccents(Color(0xFF62AFA5))
                    DownloadEngineScreen(onBack = { finish() }, embeddedMode = true)
                }
            }
        }
    }
}
