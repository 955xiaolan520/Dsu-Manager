package com.probiotics.xiaoni

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File

/**
 * OTG 刷机助手功能实现
 * 完整复制自 Linux-Dsu 的 OtgAssistantPage
 */
class OtgFlashHelper(
    private val activity: Activity,
    private val logTextView: TextView,
    private val logScrollView: ScrollView,
) {
    private val ctx: Context get() = activity
    private val handler = Handler(Looper.getMainLooper())
    
    @Volatile
    private var operationActive = false
    private var parsedPartitions = emptyList<OtgAssistantCore.PartitionInfo>()
    private var selectedPartition: String? = null
    private var fullImages = mutableListOf<OtgAssistantCore.ImageInfo>()
    private val confirmationPhrase = "我确认固件包匹配当前设备，并允许清除全部用户数据"
    
    // ==================== 读取分区表 ====================
    
    fun readPartitions() {
        if (operationActive) return
        appendLog("\n>fastboot getvar all\n")
        Thread {
            val output = StringBuilder()
            OtgAssistantCore.executeCommandDetailed(OtgAssistantCore.getFastbootPath(ctx), "fastboot getvar all") { line ->
                synchronized(output) { output.append(line).append('\n') }
                postUi { appendLog("$line\n") }
            }
            val partitions = OtgAssistantCore.parseFastbootPartitions(output.toString())
            postUi {
                parsedPartitions = partitions
                appendLog("解析到 ${partitions.size} 个分区\n")
            }
        }.start()
    }
    
    // ==================== 单分区刷写 ====================
    
    fun chooseSinglePartition() {
        if (operationActive) return
        if (parsedPartitions.isEmpty()) {
            toast("请先读取分区表")
            return
        }
        val names = parsedPartitions.map { it.name }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("选择目标分区")
            .setItems(names) { _, which ->
                selectedPartition = names[which]
                // 启动文件选择器
                val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
                intent.type = "*/*"
                intent.addCategory(android.content.Intent.CATEGORY_OPENABLE)
                activity.startActivityForResult(intent, 70) // REQUEST_OTG_SINGLE_IMAGE
            }
            .show()
    }
    
    fun prepareSingleImage(filePath: String) {
        val file = File(filePath)
        val partition = selectedPartition ?: return
        Thread {
            try {
                val name = file.name
                val digest = OtgAssistantCore.sha256(file)
                val size = file.length()
                postUi {
                    AlertDialog.Builder(activity)
                        .setTitle("确认单分区刷写")
                        .setMessage("分区: $partition\n文件: $name\n大小: ${formatBytes(size)}\nSHA-256: $digest")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("刷写") { _, _ -> 
                            Haptics.perform(activity.window.decorView)
                            flashSingle(file, partition, name)
                        }
                        .show()
                }
            } catch (e: Exception) {
                postUi { appendLog("读取镜像失败: ${e.message}\n") }
            }
        }.start()
    }
    
    private fun flashSingle(file: File, partition: String, name: String) {
        setOperationActive(true)
        Thread {
            try {
                val image = OtgAssistantCore.copyLocalFileToCache(ctx, file, name)
                runFastbootBlocking("fastboot flash $partition ${image.absolutePath}")
            } catch (e: Exception) {
                postUi { appendLog("准备镜像失败: ${e.message}\n") }
            } finally {
                postUi { setOperationActive(false) }
            }
        }.start()
    }
    
    // ==================== 全量包（OTA payload） ====================
    
    fun chooseFirmwareDirectory() {
        if (operationActive) return
        // 启动文件选择器
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
        intent.type = "application/zip"
        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE)
        activity.startActivityForResult(intent, 71) // REQUEST_OTG_FULL_PACKAGE
    }
    
    fun extractAndScanOta(filePath: String) {
        val file = File(filePath)
        val progressText = TextView(activity).apply {
            text = "已完成 0%\n准备读取 BIN/ZIP..."
            textSize = 13f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }
        val hint = TextView(activity).apply {
            text = "解包速度取决于设备性能与存储速度，通常需要 2-5 分钟。"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, dp(10), 0, 0)
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            addView(progressText)
            addView(hint)
        }
        val progressDialog = AlertDialog.Builder(activity)
            .setTitle("正在解包 OTA")
            .setView(content)
            .setCancelable(false)
            .create()
        progressDialog.show()
        
        Thread {
            try {
                val extraction = OtgAssistantCore.extractOtaPackage(ctx, file) { percent, line ->
                    postUi {
                        if (percent >= 0) progressText.text = "已完成 $percent%\n$line"
                        else progressText.text = line
                    }
                }
                val images = OtgAssistantCore.listExtractedImages(extraction.root)
                postUi {
                    progressDialog.dismiss()
                    fullImages = images.toMutableList()
                    if (images.isEmpty()) {
                        appendLog("OTA 包中没有可用的 .img 镜像\n")
                        OtgAssistantCore.deleteOtaDirectory(ctx)
                    } else {
                        showImageSelection()
                    }
                }
            } catch (e: Exception) {
                OtgAssistantCore.deleteOtaDirectory(ctx)
                postUi {
                    progressDialog.dismiss()
                    appendLog("解压或扫描 OTA 包失败: ${e.message}\n")
                }
            }
        }.start()
    }
    
    private fun showImageSelection() {
        val checks = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        fullImages.forEach { image ->
            CheckBox(activity).apply {
                isChecked = image.selected
                text = "  ${image.partition}  ${formatBytes(image.sizeBytes)}${if (image.highRisk) "  [高风险]" else ""}"
                setTextColor(Color.BLACK)
                setOnCheckedChangeListener { _, checked -> image.selected = checked }
                checks.addView(this)
            }
        }
        val box = ScrollView(activity).apply {
            addView(checks)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300))
        }
        val nextBtn = TextView(activity).apply {
            text = "下一步：进入 FastbootD"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#3b82f6"))
            setPadding(0, dp(12), 0, dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), dp(16))
            addView(TextView(activity).apply {
                text = "共发现 ${fullImages.size} 个 .img 文件（已解压到 APP 私有 files/ota 目录）"
                textSize = 12f
                setTextColor(Color.GRAY)
                setPadding(0, 0, 0, dp(8))
            })
            addView(box)
            addView(nextBtn)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("选择 OTA 全量包分区（默认全选）")
            .setView(content)
            .setNegativeButton("取消") { _, _ ->
                OtgAssistantCore.deleteOtaDirectory(ctx)
                appendLog("已取消选择并清理 files/ota 目录\n")
            }
            .create()
        dialog.setOnCancelListener {
            OtgAssistantCore.deleteOtaDirectory(ctx)
            appendLog("已取消选择并清理 files/ota 目录\n")
        }
        nextBtn.setOnClickListener {
            val selected = fullImages.filter { it.selected }
            if (selected.isEmpty()) {
                toast("至少选择一个分区")
                return@setOnClickListener
            }
            dialog.dismiss()
            enterFastbootDThenConfirm(selected)
        }
        dialog.show()
    }
    
    private fun enterFastbootDThenConfirm(images: List<OtgAssistantCore.ImageInfo>) {
        if (operationActive) return
        setOperationActive(true)
        Thread {
            val result = runFastbootBlocking("fastboot reboot fastboot")
            postUi {
                setOperationActive(false)
                if (result.exitCode == 0) {
                    appendLog("设备正在进入 FastbootD，请等待设备重新枚举\n")
                    handler.postDelayed({ showFullFlashConfirmation(images) }, 1500)
                } else {
                    appendLog("进入 FastbootD 失败，请检查设备连接和当前模式\n")
                }
            }
        }.start()
    }
    
    private fun showFullFlashConfirmation(selected: List<OtgAssistantCore.ImageInfo>) {
        if (selected.isEmpty()) {
            toast("至少选择一个分区")
            return
        }
        val summary = selected.joinToString("\n") { "${it.partition}: ${it.name}" }
        val input = EditText(activity).apply {
            hint = confirmationPhrase
            setSingleLine(false)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
            addView(TextView(activity).apply {
                text = "重要警告：必须确认固件包匹配当前设备型号、地区、版本与存储规格。" +
                    "刷写成功后将执行 fastboot -w，全部用户数据会被清除。\n\n待刷分区:\n$summary\n\n请输入确认短语："
                textSize = 12f
                setTextColor(Color.BLACK)
                setLineSpacing(dp(3).toFloat(), 1f)
            })
            addView(input)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("确认全量包刷写")
            .setView(box)
            .setNegativeButton("取消") { _, _ ->
                OtgAssistantCore.deleteOtaDirectory(ctx)
                appendLog("已取消刷写并清理 files/ota 目录\n")
            }
            .setPositiveButton("开始刷写", null)
            .create()
        dialog.setOnCancelListener {
            OtgAssistantCore.deleteOtaDirectory(ctx)
            appendLog("已取消刷写并清理 files/ota 目录\n")
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                if (input.text.toString() == confirmationPhrase) {
                    dialog.dismiss()
                    Haptics.perform(activity.window.decorView)
                    flashFullPackage(selected)
                } else {
                    toast("确认短语不匹配")
                }
            }
        }
        dialog.show()
    }
    
    private fun flashFullPackage(images: List<OtgAssistantCore.ImageInfo>) {
        setOperationActive(true)
        Thread {
            var success = true
            try {
                for (image in images) {
                    postUi { appendLog("\n开始刷写 ${image.partition}: ${image.name}\n") }
                    val file = image.file ?: throw IllegalStateException("镜像文件不存在")
                    val result = runFastbootBlocking("fastboot flash ${image.partition} ${file.absolutePath}")
                    if (result.exitCode != 0) {
                        success = false
                        postUi { appendLog("分区 ${image.partition} 刷写失败，已停止后续操作\n") }
                        break
                    }
                }
                if (success) {
                    postUi { appendLog("全部分区刷写成功，开始执行 fastboot -w\n") }
                    val wipe = runFastbootBlocking("fastboot -w")
                    postUi { appendLog(if (wipe.exitCode == 0) "数据清除成功\n" else "数据清除失败，退出码 ${wipe.exitCode}\n") }
                }
            } catch (e: Exception) {
                postUi { appendLog("全量刷写失败: ${e.message}\n") }
            } finally {
                OtgAssistantCore.deleteOtaDirectory(ctx)
                postUi {
                    appendLog("已清理 files/ota 目录\n")
                    setOperationActive(false)
                }
            }
        }.start()
    }
    
    // ==================== ADB 推送 ====================
    
    fun chooseAdbPushFile() {
        if (operationActive) return
        // 启动文件选择器
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
        intent.type = "*/*"
        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE)
        activity.startActivityForResult(intent, 72) // REQUEST_OTG_ADB_PUSH
    }
    
    fun prepareAdbPush(filePath: String) {
        val file = File(filePath)
        val name = file.name
        val destination = EditText(activity).apply {
            setText("/sdcard/Download/$name")
            setSelectAllOnFocus(true)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        AlertDialog.Builder(activity)
            .setTitle("ADB 推送文件")
            .setMessage("文件: $name\n请输入设备上的目标路径")
            .setView(destination)
            .setNegativeButton("取消", null)
            .setPositiveButton("推送") { _, _ -> adbPush(file, destination.text.toString().trim()) }
            .show()
    }
    
    private fun adbPush(sourceFile: File, destination: String) {
        if (destination.isEmpty()) {
            toast("请输入目标路径")
            return
        }
        setOperationActive(true)
        Thread {
            try {
                val name = sourceFile.name
                val file = OtgAssistantCore.copyLocalFileToCache(ctx, sourceFile, name)
                postUi { appendLog("\n>adb push ${file.name} $destination\n") }
                val result = OtgAssistantCore.executeCommandDetailed(
                    OtgAssistantCore.getAdbPath(ctx),
                    "adb push ${file.absolutePath} $destination",
                ) { line -> postUi { appendLog("$line\n") } }
                file.delete()
                postUi { appendLog("ADB 推送${if (result.exitCode == 0) "成功" else "失败，退出码 ${result.exitCode}"}\n") }
            } catch (e: Exception) {
                postUi { appendLog("ADB 推送失败: ${e.message}\n") }
            } finally {
                postUi { setOperationActive(false) }
            }
        }.start()
    }
    
    // ==================== ADB 设备信息 ====================
    
    fun showAdbDeviceInfo() {
        if (operationActive) return
        setOperationActive(true)
        Thread {
            val commands = listOf(
                "adb get-state",
                "adb shell getprop ro.product.manufacturer",
                "adb shell getprop ro.product.model",
                "adb shell getprop ro.build.version.release",
            )
            val output = StringBuilder()
            commands.forEach { command ->
                val result = OtgAssistantCore.executeCommandDetailed(OtgAssistantCore.getAdbPath(ctx), command)
                synchronized(output) { output.append(command).append(": ").append(result.output.trim()).append('\n') }
            }
            postUi {
                appendLog("\nADB 设备信息:\n${output.toString().ifBlank { "未检测到 ADB 设备\n" }}")
                setOperationActive(false)
            }
        }.start()
    }
    
    // ==================== 高级重启 ====================
    
    fun showRebootMenu() {
        val items = arrayOf("正常重启", "正常关机", "Recovery", "Fastboot", "9008 (EDL)")
        val commands = arrayOf("reboot", "reboot poweroff", "reboot recovery", "reboot bootloader", "reboot edl")
        AlertDialog.Builder(activity)
            .setTitle("高级重启")
            .setItems(items) { _, which -> executeRebootMode(commands[which]) }
            .show()
    }

    private fun executeRebootMode(target: String) {
        if (operationActive) return
        setOperationActive(true)
        Thread {
            try {
                val status = OtgAssistantCore.detectProtocolDevices(ctx)
                when {
                    status.adb != "未发现设备" -> {
                        val result = runToolBlocking(OtgAssistantCore.getAdbPath(ctx), "adb $target")
                        if (result.exitCode == 0 && target != "reboot") {
                            postUi { appendLog("设备正在切换启动模式，等待重新枚举\n") }
                            waitForUsbReenumeration()
                        }
                    }
                    status.fastboot != "未发现设备" -> {
                        val fastbootTarget = if (target == "reboot bootloader") "reboot-bootloader" else target
                        runToolBlocking(OtgAssistantCore.getFastbootPath(ctx), "fastboot $fastbootTarget")
                    }
                    else -> postUi { appendLog("高级重启失败：未检测到可用的 ADB/Fastboot 设备\n") }
                }
            } catch (e: Exception) {
                postUi { appendLog("高级重启失败：${e.message}\n") }
            } finally {
                postUi { setOperationActive(false) }
            }
        }.start()
    }

    private fun waitForUsbReenumeration() {
        Thread {
            repeat(8) {
                Thread.sleep(750)
                val status = OtgAssistantCore.detectProtocolDevices(ctx)
                if (status.fastboot != "未发现设备") {
                    postUi { appendLog("Fastboot 状态：${status.fastboot}\n") }
                    return@Thread
                }
            }
            postUi { appendLog("切换模式后 Fastboot 未发现设备，请检查连接与 fastboot 输出\n") }
        }.start()
    }
    
    // ==================== 辅助方法 ====================
    
    private fun runFastbootBlocking(command: String): OtgAssistantCore.CommandResult {
        postUi { appendLog(">$command\n") }
        return OtgAssistantCore.executeCommandDetailed(OtgAssistantCore.getFastbootPath(ctx), command) { line ->
            postUi { appendLog("$line\n") }
        }.also { result ->
            postUi { appendLog("退出码: ${result.exitCode}\n") }
        }
    }
    
    private fun runToolBlocking(toolPath: String?, command: String): OtgAssistantCore.CommandResult {
        postUi { appendLog(">$command\n") }
        return OtgAssistantCore.executeCommandDetailed(toolPath, command) { line ->
            postUi { appendLog("$line\n") }
        }.also { result ->
            postUi { appendLog("退出码: ${result.exitCode}\n") }
        }
    }
    
    private fun setOperationActive(active: Boolean) {
        operationActive = active
    }
    
    private fun appendLog(text: String) {
        logTextView.append(text)
        logScrollView.post { logScrollView.fullScroll(View.FOCUS_DOWN) }
    }
    
    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.2f GiB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "%.2f MiB".format(bytes / (1024.0 * 1024))
        bytes >= 1024L -> "%.2f KiB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
    
    private fun toast(msg: String) {
        postUi { Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show() }
    }
    
    private fun postUi(action: () -> Unit) {
        handler.post(action)
    }
    
    private fun dp(value: Int): Int {
        return (value * activity.resources.displayMetrics.density).toInt()
    }
}
