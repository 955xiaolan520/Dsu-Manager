package com.probiotics.xiaoni

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
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
    
    // v3.66.70：圆角年轻化弹窗样式辅助方法（参考 ShortVideoActivity 短视频解析弹窗）
    private fun rounded(color: Int, strokeColor: Int, radiusDp: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != 0) setStroke(Math.max(1, dp(1)), strokeColor)
        }
    }
    
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false): TextView {
        return TextView(activity).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
    }
    
    private fun actionButton(value: String, startColor: Int, endColor: Int): Button {
        return Button(activity, null, 0).apply {
            text = value
            textSize = 13f
            setTextColor(Color.WHITE)
            isAllCaps = false
            setPadding(0, dp(9), 0, dp(9))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            stateListAnimator = null
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(startColor, endColor)
            ).apply { cornerRadius = dp(13).toFloat() }
        }
    }
    
    private fun showModernDialogWithInput(
        title: String,
        message: String,
        inputHint: String,
        defaultValue: String,
        cancelText: String = "取消",
        confirmText: String,
        onConfirm: (String) -> Unit
    ) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xffeffaff.toInt(), 0xffdceafa.toInt(), 0xfff3e8ff.toInt())
            ).apply {
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), 0xd8ffffff.toInt())
            }
        }
        
        // 顶部胶囊标签
        val capsule = LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(0x247d98bd.toInt(), 0x887d98bd.toInt(), 15)
        }
        capsule.addView(label("OTG ASSISTANT  ·  输入参数", 10, 0xff35617e.toInt(), true))
        card.addView(capsule, LinearLayout.LayoutParams(-2, -2).apply { 
            gravity = Gravity.START 
        })
        
        // 标题
        val titleView = label(title, 21, 0xff17334f.toInt(), true).apply {
            setPadding(0, dp(14), 0, dp(8))
        }
        card.addView(titleView, LinearLayout.LayoutParams(-1, -2))
        
        // 提示文字
        val messageView = label(message, 13, 0xff4c647a.toInt(), false).apply {
            setPadding(0, 0, 0, dp(12))
        }
        card.addView(messageView, LinearLayout.LayoutParams(-1, -2))
        
        // 输入框
        val inputField = EditText(activity).apply {
            setText(defaultValue)
            hint = inputHint
            setSelectAllOnFocus(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            textSize = 13f
            setTextColor(0xff17334f.toInt())
            setHintTextColor(0xffadb8c9.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xf2ffffff.toInt())
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), 0xc8d0dce8.toInt())
            }
        }
        card.addView(inputField, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(18)
        })
        
        // 按钮行
        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        val cancelBtn = actionButton(cancelText, 0xff8fa1b8.toInt(), 0xff6e7c93.toInt()).apply {
            setOnClickListener { dialog.dismiss() }
        }
        val confirmBtn = actionButton(confirmText, 0xff35A8C4.toInt(), 0xff0E7D95.toInt()).apply {
            setOnClickListener { 
                val input = inputField.text.toString().trim()
                if (input.isNotEmpty()) {
                    dialog.dismiss()
                    onConfirm(input)
                }
            }
        }
        btnRow.addView(cancelBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
        btnRow.addView(confirmBtn, LinearLayout.LayoutParams(0, dp(44), 1f))
        card.addView(btnRow, LinearLayout.LayoutParams(-1, -2))
        
        dialog.setContentView(card, ViewGroup.LayoutParams(
            (activity.resources.displayMetrics.widthPixels * 0.88).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        dialog.window?.setDimAmount(0.58f)
        dialog.show()
    }
    
    private fun showModernDialog(
        title: String,
        message: String,
        cancelText: String = "取消",
        confirmText: String,
        onConfirm: () -> Unit
    ) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xffeffaff.toInt(), 0xffdceafa.toInt(), 0xfff3e8ff.toInt())
            ).apply {
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), 0xd8ffffff.toInt())
            }
        }
        
        // 顶部胶囊标签
        val capsule = LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(0x247d98bd.toInt(), 0x887d98bd.toInt(), 15)
        }
        capsule.addView(label("OTG ASSISTANT  ·  操作确认", 10, 0xff35617e.toInt(), true))
        card.addView(capsule, LinearLayout.LayoutParams(-2, -2).apply { 
            gravity = Gravity.START 
        })
        
        // 标题
        val titleView = label(title, 21, 0xff17334f.toInt(), true).apply {
            setPadding(0, dp(14), 0, dp(8))
        }
        card.addView(titleView, LinearLayout.LayoutParams(-1, -2))
        
        // 可滚动内容区域（固定高度）
        val messageView = label(message, 13, 0xff4c647a.toInt(), false)
        val scrollView = android.widget.ScrollView(activity).apply {
            addView(messageView)
        }
        // 固定内容区最大高度为屏幕 40%
        val maxContentHeight = (activity.resources.displayMetrics.heightPixels * 0.4).toInt()
        card.addView(scrollView, LinearLayout.LayoutParams(-1, maxContentHeight).apply {
            bottomMargin = dp(18)
        })
        
        // 按钮行
        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        val cancelBtn = actionButton(cancelText, 0xff8fa1b8.toInt(), 0xff6e7c93.toInt()).apply {
            setOnClickListener { dialog.dismiss() }
        }
        val confirmBtn = actionButton(confirmText, 0xff35A8C4.toInt(), 0xff0E7D95.toInt()).apply {
            setOnClickListener { 
                dialog.dismiss()
                onConfirm()
            }
        }
        btnRow.addView(cancelBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
        btnRow.addView(confirmBtn, LinearLayout.LayoutParams(0, dp(44), 1f))
        card.addView(btnRow, LinearLayout.LayoutParams(-1, -2))
        
        dialog.setContentView(card, ViewGroup.LayoutParams(
            (activity.resources.displayMetrics.widthPixels * 0.88).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        dialog.window?.setDimAmount(0.58f)
        dialog.show()
    }
    private var selectedPartition: String? = null
    private var fullImages = mutableListOf<OtgAssistantCore.ImageInfo>()
    private val confirmationPhrase = "我确认固件包匹配当前设备，并允许清除全部用户数据"

    private val singleImageRequest = 402
    private val otaRequest = 403
    private val adbPushRequest = 404

    private fun launchBuiltInPicker(requestCode: Int, title: String, ext: String = "", all: Boolean = true) {
        activity.startActivityForResult(android.content.Intent(activity, RootfsFilesActivity::class.java).apply {
            putExtra(RootfsFilesActivity.EXTRA_PICK, true)
            putExtra(RootfsFilesActivity.EXTRA_TITLE, title)
            if (all) putExtra(RootfsFilesActivity.EXTRA_EXT_ALL, true)
            else putExtra(RootfsFilesActivity.EXTRA_EXT, ext)
        }, requestCode)
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (resultCode != Activity.RESULT_OK) return
        val path = data?.getStringExtra(RootfsFilesActivity.RESULT_FILE_PATH) ?: return
        when (requestCode) {
            singleImageRequest -> prepareSingleImage(path)
            otaRequest -> extractAndScanOta(path)
            adbPushRequest -> prepareAdbPush(path)
        }
    }
    
    // ==================== 读取分区表 ====================
    
    fun readPartitions() {
        if (operationActive) return
        setOperationActive(true)
        appendLog("\n>fastboot getvar all\n")
        Thread {
            try {
                val output = StringBuilder()
                val result = OtgAssistantCore.executeCommandDetailed(OtgAssistantCore.getFastbootPath(ctx), "fastboot getvar all") { line ->
                    synchronized(output) { output.append(line).append('\n') }
                    postUi { appendLog("$line\n") }
                }
                val partitions = OtgAssistantCore.parseFastbootPartitions(output.toString())
                postUi {
                    parsedPartitions = partitions
                    appendLog("解析到 ${partitions.size} 个分区，退出码 ${result.exitCode}\n")
                    setOperationActive(false)
                    // 弹窗显示分区列表
                    if (partitions.isNotEmpty()) {
                        showPartitionListDialog(partitions)
                    }
                }
            } catch (e: Exception) {
                postUi { appendLog("读取分区表失败：${e.message}\n"); setOperationActive(false) }
            }
        }.start()
    }
    
    private fun showPartitionListDialog(partitions: List<OtgAssistantCore.PartitionInfo>) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xffeffaff.toInt(), 0xffdceafa.toInt(), 0xfff3e8ff.toInt())
            ).apply {
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), 0xd8ffffff.toInt())
            }
        }
        
        // 顶部胶囊
        val capsule = LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(0x247d98bd.toInt(), 0x887d98bd.toInt(), 15)
        }
        capsule.addView(label("OTG ASSISTANT  ·  目标设备分区", 10, 0xff35617e.toInt(), true))
        panel.addView(capsule, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.START })
        
        // 标题
        val title = label("分区表 · 共 ${partitions.size} 个分区", 21, 0xff17334f.toInt(), true).apply {
            setPadding(0, dp(14), 0, dp(8))
        }
        panel.addView(title, LinearLayout.LayoutParams(-1, -2))
        
        // 可滚动分区列表
        val scroll = android.widget.ScrollView(activity)
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        
        for (partition in partitions) {
            val item = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(0x18000000.toInt(), 0x00000000.toInt(), 12)
            }
            
            val nameView = TextView(activity).apply {
                text = partition.name
                textSize = 14f
                setTextColor(0xff17334f.toInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            
            val sizeView = TextView(activity).apply {
                text = partition.size ?: "未知"
                textSize = 13f
                setTextColor(0xff35A8C4.toInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.END
            }
            
            item.addView(nameView)
            item.addView(sizeView)
            list.addView(item, LinearLayout.LayoutParams(-1, -2).apply { 
                bottomMargin = dp(6)
            })
        }
        
        scroll.addView(list)
        // 固定高度 500dp，参考 DNA 分解 bin 弹窗
        panel.addView(scroll, LinearLayout.LayoutParams(-1, dp(500)).apply {
            topMargin = dp(8)
            bottomMargin = dp(18)
        })
        
        // 关闭按钮
        val closeBtn = actionButton("关闭", 0xff35A8C4.toInt(), 0xff0E7D95.toInt()).apply {
            setOnClickListener { dialog.dismiss() }
        }
        panel.addView(closeBtn, LinearLayout.LayoutParams(-1, dp(44)))
        
        dialog.setContentView(panel, ViewGroup.LayoutParams(
            (activity.resources.displayMetrics.widthPixels * 0.88).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        dialog.window?.setDimAmount(0.58f)
        dialog.show()
    }

    /** Linux-Dsu OtgAssistantPage 的设备扫描逻辑；主界面只负责显示结果。 */
    fun refreshDevices(deviceView: TextView, protocolView: TextView) {
        if (operationActive) return
        setOperationActive(true)
        appendLog("\n正在扫描设备...\n")
        Thread {
            try {
                val devices = OtgAssistantCore.listUsbDevices(ctx)
                val protocol = OtgAssistantCore.detectProtocolDevices(ctx)
                postUi {
                    if (devices.isEmpty()) {
                        deviceView.text = "未找到 USB 设备"
                        appendLog("未发现设备\n")
                    } else {
                        val shown = devices.take(8).joinToString("\n") { device ->
                            String.format("VID:%04x  PID:%04x  %s", device.vendorId, device.productId, device.deviceName)
                        }
                        deviceView.text = if (devices.size > 8) "$shown\n...共 ${devices.size} 个设备" else shown
                        appendLog("发现 ${devices.size} 个设备\n")
                    }
                    protocolView.text = "ADB: ${protocol.adb}\nFastboot: ${protocol.fastboot}"
                    appendLog("协议检测：ADB=${protocol.adb != "未发现设备"}，Fastboot=${protocol.fastboot != "未发现设备"}\n")
                    setOperationActive(false)
                }
            } catch (e: Exception) {
                postUi { appendLog("设备检测失败：${e.message}\n"); setOperationActive(false) }
            }
        }.start()
    }

    /** Linux-Dsu OtgAssistantPage 的命令分流：fastboot 使用 fastboot 工具，其余使用 adb 工具。 */
    fun executeCommand(command: String) {
        if (operationActive) return
        val trimmed = command.trim()
        if (trimmed.isEmpty()) {
            toast("请输入命令")
            return
        }
        appendLog("\n>$trimmed\n")
        setOperationActive(true)
        Thread {
            try {
                val toolPath = if (trimmed.startsWith("fastboot")) OtgAssistantCore.getFastbootPath(ctx) else OtgAssistantCore.getAdbPath(ctx)
                val output = OtgAssistantCore.executeCommand(toolPath, trimmed) { line ->
                    postUi { appendLog("$line\n") }
                }
                postUi { if (output.isBlank()) appendLog("命令已完成\n"); setOperationActive(false) }
            } catch (e: Exception) {
                postUi { appendLog("命令执行失败：${e.message}\n"); setOperationActive(false) }
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
        showPartitionSelectionDialog(names) { which ->
            selectedPartition = names[which]
            // v3.66.70：换用内置文件管理器
            FileBrowserDialog.show(
                activity,
                "选择单分区镜像（.img / .bin）",
                arrayOf(".img", ".bin", ".dat"),
                null
            ) { path -> 
                // 添加刷入前确认弹窗
                showModernDialog(
                    title = "确认刷入分区？",
                    message = "即将刷入：\n分区名：$selectedPartition\n镜像文件：${File(path).name}\n\n刷入后需重启生效，错误的镜像可能导致无法开机。",
                    confirmText = "开始刷入"
                ) {
                    prepareSingleImage(path)
                }
            }
        }
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
                    // v3.66.70：圆角年轻化弹窗
                    showModernDialog(
                        title = "确认单分区刷写",
                        message = "分区: $partition\n文件: $name\n大小: ${formatBytes(size)}\nSHA-256: $digest",
                        confirmText = "刷写"
                    ) {
                        Haptics.perform(activity.window.decorView)
                        flashSingle(file, partition, name)
                    }
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
        // v3.66.70：圆角年轻化弹窗（参考短视频解析）
        showModernDialog(
            title = "全量包刷写 · 需要什么包？",
            message = "只支持 OTA 全量包 ZIP（内含 payload.bin，A/B 动态分区设备官方整包）或裸 payload.bin 文件。\n\n不支持 Recovery 卡刷包、线刷 tar/目录，选错会解包失败。\n\n流程：解包分区 → 勾选 → 进入 FastbootD 刷写 → fastboot -w 清数据。",
            confirmText = "选择文件"
        ) {
            FileBrowserDialog.show(
                activity,
                "选择全量包（OTA ZIP 内含 payload.bin / 裸 payload.bin）",
                arrayOf(".zip", ".bin"),
                null
            ) { path -> extractAndScanOta(path) }
        }
    }
    
    fun extractAndScanOta(filePath: String) {
        val file = File(filePath)
        val progressText = TextView(activity).apply {
            text = "已完成 0%\n准备读取 BIN/ZIP..."
            textSize = 13f
            setTextColor(0xff17334f.toInt())
            gravity = Gravity.CENTER
        }
        val hint = TextView(activity).apply {
            text = "解包速度取决于设备性能与存储速度，通常需要 2-5 分钟。"
            textSize = 12f
            setTextColor(0xff6e7c93.toInt())
            setPadding(0, dp(10), 0, 0)
            gravity = Gravity.CENTER
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(20))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xffeffaff.toInt(), 0xffdceafa.toInt(), 0xfff3e8ff.toInt())
            ).apply {
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), 0xd8ffffff.toInt())
            }
            
            // 顶部胶囊
            val capsule = LinearLayout(activity).apply {
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = rounded(0x247d98bd.toInt(), 0x887d98bd.toInt(), 15)
            }
            capsule.addView(label("OTG ASSISTANT  ·  解包任务", 10, 0xff35617e.toInt(), true))
            addView(capsule, LinearLayout.LayoutParams(-2, -2).apply { 
                gravity = Gravity.START
                bottomMargin = dp(12)
            })
            
            addView(label("正在解包 OTA", 18, 0xff17334f.toInt(), true))
            addView(progressText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            addView(hint, LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(false)
        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.80).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
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
            setHintTextColor(0xffadb8c9.toInt())
            textSize = 13f
            setTextColor(0xff17334f.toInt())
            isSingleLine = true
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
            addView(TextView(activity).apply {
                text = "重要警告：必须确认固件包匹配当前设备型号、地区、版本与存储规格。" +
                    "刷写成功后将执行 fastboot -w，全部用户数据会被清除。\n\n待刷分区:\n$summary\n\n请输入确认短语："
                textSize = 12f
                setTextColor(0xff17334f.toInt())
                setLineSpacing(dp(3).toFloat(), 1f)
            })
            addView(input)
        }
        
        // v3.66.70：圆角年轻化弹窗
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xffeffaff.toInt(), 0xffdceafa.toInt(), 0xfff3e8ff.toInt())
            ).apply {
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), 0xd8ffffff.toInt())
            }
        }
        
        val capsule = LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(0x247d98bd.toInt(), 0x887d98bd.toInt(), 15)
        }
        capsule.addView(label("OTG ASSISTANT  ·  危险操作", 10, 0xffcc5555.toInt(), true))
        card.addView(capsule, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.START })
        
        val titleView = label("确认全量包刷写", 21, 0xff17334f.toInt(), true).apply {
            setPadding(0, dp(14), 0, dp(8))
        }
        card.addView(titleView, LinearLayout.LayoutParams(-1, -2))
        card.addView(box, LinearLayout.LayoutParams(-1, -2))
        
        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        val cancelBtn = actionButton("取消", 0xff8fa1b8.toInt(), 0xff6e7c93.toInt()).apply {
            setOnClickListener {
                dialog.dismiss()
                OtgAssistantCore.deleteOtaDirectory(ctx)
                appendLog("已取消刷写并清理 files/ota 目录\n")
            }
        }
        val confirmBtn = actionButton("开始刷写", 0xffD95656.toInt(), 0xffB03838.toInt()).apply {
            setOnClickListener {
                if (input.text.toString() == confirmationPhrase) {
                    dialog.dismiss()
                    Haptics.perform(activity.window.decorView)
                    flashFullPackage(selected)
                } else {
                    toast("确认短语不匹配")
                }
            }
        }
        btnRow.addView(cancelBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
        btnRow.addView(confirmBtn, LinearLayout.LayoutParams(0, dp(44), 1f))
        card.addView(btnRow, LinearLayout.LayoutParams(-1, -2))
        
        dialog.setContentView(card, ViewGroup.LayoutParams(
            (activity.resources.displayMetrics.widthPixels * 0.88).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        dialog.window?.setDimAmount(0.58f)
        dialog.setOnCancelListener {
            OtgAssistantCore.deleteOtaDirectory(ctx)
            appendLog("已取消刷写并清理 files/ota 目录\n")
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
        // v3.66.70：换用 Dsu 管理器内置文件浏览器
        FileBrowserDialog.show(
            activity,
            "选择要推送到设备的文件",
            null,  // 不限制扩展名
            null
        ) { path -> prepareAdbPush(path) }
    }
    
    fun prepareAdbPush(filePath: String) {
        val file = File(filePath)
        val name = file.name
        showModernDialogWithInput(
            title = "ADB 推送文件",
            message = "文件: $name\n请输入设备上的目标路径",
            inputHint = "目标路径",
            defaultValue = "/sdcard/Download/$name",
            confirmText = "推送"
        ) { destination ->
            adbPush(file, destination)
        }
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
        // v3.66.70：圆角年轻化弹窗样式
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xffeffaff.toInt(), 0xffdceafa.toInt(), 0xfff3e8ff.toInt())
            ).apply {
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), 0xd8ffffff.toInt())
            }
        }
        
        val capsule = LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(0x247d98bd.toInt(), 0x887d98bd.toInt(), 15)
        }
        capsule.addView(label("OTG ASSISTANT  ·  目标设备重启", 10, 0xff35617e.toInt(), true))
        card.addView(capsule, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.START })
        
        val titleView = label("高级重启选项", 21, 0xff17334f.toInt(), true).apply {
            setPadding(0, dp(14), 0, dp(12))
        }
        card.addView(titleView, LinearLayout.LayoutParams(-1, -2))
        
        val items = arrayOf("正常重启", "正常关机", "Recovery", "Fastboot", "9008 (EDL)")
        val commands = arrayOf("reboot", "reboot poweroff", "reboot recovery", "reboot bootloader", "reboot edl")
        
        items.forEachIndexed { index, itemName ->
            val btn = Button(activity, null, 0).apply {
                text = itemName
                textSize = 14f
                setTextColor(0xff17334f.toInt())
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = rounded(0x20ffffff.toInt(), 0x8effffff.toInt(), 13)
                setOnClickListener {
                    dialog.dismiss()
                    executeRebootMode(commands[index])
                }
            }
            card.addView(btn, LinearLayout.LayoutParams(-1, -2).apply {
                if (index < items.size - 1) bottomMargin = dp(8)
            })
        }
        
        val cancelBtn = actionButton("取消", 0xff8fa1b8.toInt(), 0xff6e7c93.toInt()).apply {
            setOnClickListener { dialog.dismiss() }
        }
        card.addView(cancelBtn, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(12) })
        
        dialog.setContentView(card, ViewGroup.LayoutParams(
            (activity.resources.displayMetrics.widthPixels * 0.88).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        dialog.window?.setDimAmount(0.58f)
        dialog.show()
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
    
    private fun showPartitionSelectionDialog(items: Array<String>, onSelect: (Int) -> Unit) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(16))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xffeffaff.toInt(), 0xffdceafa.toInt(), 0xfff3e8ff.toInt())
            ).apply {
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), 0xd8ffffff.toInt())
            }
        }
        
        // 顶部胶囊
        val capsule = LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(0x247d98bd.toInt(), 0x887d98bd.toInt(), 15)
        }
        capsule.addView(label("OTG ASSISTANT  ·  目标设备分区", 10, 0xff35617e.toInt(), true))
        card.addView(capsule, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.START })
        
        // 标题
        val title = label("选择目标分区", 21, 0xff17334f.toInt(), true)
        card.addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        
        // 可滚动列表
        val scroll = android.widget.ScrollView(activity)
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        
        items.forEachIndexed { index, name ->
            val item = TextView(activity).apply {
                text = name
                textSize = 15f
                setTextColor(0xff17334f.toInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = rounded(0x18000000.toInt(), 0x00000000.toInt(), 16)
                setOnClickListener {
                    dialog.dismiss()
                    onSelect(index)
                }
            }
            list.addView(item, LinearLayout.LayoutParams(-1, -2).apply {
                if (index > 0) topMargin = dp(8)
            })
        }
        
        scroll.addView(list)
        card.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(12)
            bottomMargin = dp(16)
        })
        
        // 取消按钮
        val cancelBtn = actionButton("取消", 0xff8297a8.toInt(), 0xff687f93.toInt()).apply {
            setOnClickListener { dialog.dismiss() }
        }
        card.addView(cancelBtn, LinearLayout.LayoutParams(-1, dp(46)))
        
        dialog.setContentView(card)
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.88).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setDimAmount(0.58f)
        dialog.show()
    }
}
