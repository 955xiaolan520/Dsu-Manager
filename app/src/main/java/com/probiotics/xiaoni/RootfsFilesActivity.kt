package com.probiotics.xiaoni

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** Linux-Dsu OTG 使用的本地文件选择流程：返回绝对路径，不转换成不可访问的 Uri。 */
class RootfsFilesActivity : Activity() {
    companion object {
        const val EXTRA_PICK = "extra_pick"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_EXT = "extra_ext"
        const val EXTRA_EXT_ALL = "extra_ext_all"
        const val RESULT_FILE_PATH = "result_file_path"
    }

    private var currentDir = File("/sdcard")
    private var extensions = emptyList<String>()
    private lateinit var host: LinearLayout
    private lateinit var pathView: TextView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        currentDir = File("/sdcard")
        val all = intent.getBooleanExtra(EXTRA_EXT_ALL, false)
        extensions = if (all) emptyList() else (intent.getStringExtra(EXTRA_EXT) ?: "")
            .split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
        }
        val title = TextView(this).apply {
            text = intent.getStringExtra(EXTRA_TITLE) ?: "选择文件"
            textSize = 21f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 14)
        }
        pathView = TextView(this).apply { textSize = 12f; setTextColor(Color.DKGRAY) }
        host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(host) }
        root.addView(title)
        root.addView(pathView)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val up = TextView(this).apply {
            text = "上级"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(70, 120, 220))
            setPadding(0, 20, 0, 20)
            setOnClickListener { if (currentDir.absolutePath != "/sdcard") { currentDir = currentDir.parentFile ?: File("/sdcard"); refresh() } }
        }
        root.addView(up, ViewGroup.LayoutParams(-1, 64))
        setContentView(root)
        refresh()
    }

    private fun refresh() {
        pathView.text = currentDir.absolutePath
        host.removeAllViews()
        val files = currentDir.listFiles()?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
        if (files.isNullOrEmpty()) {
            host.addView(TextView(this).apply { text = "目录为空或无权限"; setPadding(0, 24, 0, 24) })
            return
        }
        files.forEach { file ->
            val row = TextView(this).apply {
                text = if (file.isDirectory) "📁  ${file.name}" else "📄  ${file.name}  (${file.length()} bytes)"
                textSize = 15f
                setTextColor(Color.BLACK)
                setPadding(8, 22, 8, 22)
                setOnClickListener { if (file.isDirectory) { currentDir = file; refresh() } else pick(file) }
            }
            host.addView(row, ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun pick(file: File) {
        if (extensions.isNotEmpty() && extensions.none { file.name.lowercase().endsWith(it) }) {
            android.widget.Toast.makeText(this, "支持格式：${extensions.joinToString(" / ")}", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        setResult(RESULT_OK, Intent().putExtra(RESULT_FILE_PATH, file.absolutePath))
        finish()
    }
}
