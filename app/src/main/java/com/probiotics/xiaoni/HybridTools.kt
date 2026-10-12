package com.probiotics.xiaoni

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipFile
import java.util.zip.ZipEntry

/**
 * DNA·rec 专用工具链。
 *
 * 与 DnaTools 完全分离：这里不读取 DNA 的 17 个 CLI 工具，也不调用 DNA 的 Release/API。
 * 卡刷包资源固定来自 dna-tools Release 下的独立 dna-rec-tools-v1.zip 附件。
 * tar.zst 使用工具包内的 Android 7z 解压；成品 ZIP 由 APK 内置 ZIP 流生成。
 */
object HybridTools {
    const val TOOLS_ROOT = "/data/local/pdna-rec-tools"
    const val WORK_ROOT = "/sdcard/PDNA"
    const val ASSET_URL = "https://github.com/955xiaolan520/Dsu-Manager/releases/download/dna-tools/dna-rec-tools-v1.zip"
    private val DOWNLOAD_MIRRORS = arrayOf("https://gh-proxy.com/", "https://ghfast.top/", "https://ghproxy.net/", "")
    private const val MIN_REC_ZIP_BYTES = 12L * 1024 * 1024

    private val REQUIRED = arrayOf(
        "bin.tar.zst", "twrp.tar.zst", "7z", "bash", "duk", "sign", "sparse", "tar", "zstd",
        "functions.sh", "hybrid_builder.sh", "SUPERHYBRID_FLASHER.bat", "SUPERHYBRID_FLASHER.sh"
    )

    @JvmStatic
    fun privateDir(ctx: Context): File = File(ctx.filesDir, "dna-rec-tools")

    @JvmStatic
    fun installTemplateScripts(ctx: Context): File {
        val dir = File(ctx.filesDir, "dna-scripts").apply { mkdirs() }
        listOf("functions.sh", "hybrid_builder.sh").forEach { name ->
            val out = File(dir, name)
            ctx.assets.open("dna-scripts/$name").use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            out.setExecutable(true, false)
        }
        return dir
    }

    @JvmStatic
    fun ensureDirectories(): Boolean {
        val r = RootShell.exec(
            "mkdir -p '$TOOLS_ROOT' '$WORK_ROOT/twrp/probiotics' '$WORK_ROOT/twrp1/images' && " +
                "chmod 755 '$TOOLS_ROOT' '$WORK_ROOT' '$WORK_ROOT/twrp' '$WORK_ROOT/twrp/probiotics' '$WORK_ROOT/twrp1' '$WORK_ROOT/twrp1/images' && echo __REC_DIRS_OK__",
            timeoutMs = 15000
        )
        return r.success && r.stdout.contains("__REC_DIRS_OK__")
    }

    @JvmStatic
    private fun missingDeployedTools(): Set<String> {
        val checks = REQUIRED.joinToString(" ") { name ->
            "if [ -s '$TOOLS_ROOT/$name' ]; then echo 'OK:$name'; else echo 'MISSING:$name'; fi;"
        }
        val result = RootShell.exec("$checks echo __REC_CHECK_DONE__", timeoutMs = 15000)
        if (!result.success || !result.stdout.contains("__REC_CHECK_DONE__")) return REQUIRED.toSet()
        return result.stdout.lineSequence()
            .filter { it.startsWith("MISSING:") }
            .map { it.removePrefix("MISSING:").trim() }
            .filter { it in REQUIRED }
            .toSet()
    }

    @JvmStatic
    fun toolsInstalled(ctx: Context): Boolean = missingDeployedTools().isEmpty()

    private fun workspaceReady(work: String, cardTemplate: Boolean): Boolean {
        val root = File(work)
        return if (cardTemplate) {
            File(root, "bin").isDirectory && File(root, "images").isDirectory
        } else {
            File(root, "META-INF/com/google/android/helpers").isDirectory &&
                File(root, "META-INF/com/google/android/update-binary").isFile &&
                File(root, "Platform-Tools").isDirectory && File(root, "probiotics").isDirectory
        }
    }

    @JvmStatic
    fun check(ctx: Context): List<String> {
        val dir = privateDir(ctx)
        val missing = missingDeployedTools()
        return REQUIRED.map { name ->
            val f = File(dir, name)
            val display = when (name) {
                "bin.tar.zst" -> "bin 工具"
                "twrp.tar.zst" -> "TWRP 模板"
                "zstd" -> "Zstandard 解压引擎"
                "7z" -> "7z 压缩解压引擎"
                "bash" -> "Bash 执行引擎"
                "duk" -> "Duk 脚本引擎"
                "sign" -> "Sign 工具"
                "sparse" -> "Sparse 工具"
                "tar" -> "Tar 工具"
                "functions.sh" -> "阳·卡刷模板"
                "hybrid_builder.sh" -> "小布丁·线卡模板"
                "SUPERHYBRID_FLASHER.bat" -> "Windows 线刷脚本"
                "SUPERHYBRID_FLASHER.sh" -> "Linux 线刷脚本"
                else -> "卡刷资源"
            }
            if (name !in missing) "✓ $display · ${if (f.isFile) "${f.length() / 1024} KB" else "已部署"}"
            else "✗ $display · /data/local 未部署"
        }
    }

    @JvmStatic
    fun downloadTools(
        ctx: Context,
        onLog: ((String) -> Unit)? = null,
        onProgress: ((Int) -> Unit)? = null,
    ): Boolean {
        ensureDirectories()
        if (toolsInstalled(ctx)) {
            onProgress?.invoke(100)
            onLog?.invoke("✓ 已复用 /data/local/dna-rec-tools 中的工具链")
            return true
        }
        val cachedToolsReady = REQUIRED.all { File(privateDir(ctx), it).isFile && File(privateDir(ctx), it).length() > 0 }
        if (cachedToolsReady) {
            val cachedDir = privateDir(ctx).absolutePath
            val deploy = RootShell.exec(
                "mkdir -p '$TOOLS_ROOT' && cp -f '$cachedDir'/* '$TOOLS_ROOT/' && chmod 755 '$TOOLS_ROOT'/* && echo __REC_DEPLOYED__",
                timeoutMs = 60000
            )
            if (deploy.success && deploy.stdout.contains("__REC_DEPLOYED__") && toolsInstalled(ctx)) {
                onProgress?.invoke(100)
                onLog?.invoke("✓ 从应用缓存重新部署已删除的 /data/local 工具文件")
                return true
            }
            onLog?.invoke("缓存文件存在，但 /data/local 重新部署失败，将尝试重新下载")
        }
        val dest = File(ctx.cacheDir, "dna-rec-tools-v1.zip")
        dest.delete()
        val safeLog: ((String) -> Unit)? = onLog?.let { sink -> { line -> if (!isNoisyDownloadLine(line)) sink(line) } }
        var downloaded = false
        var lastMessage = "下载失败"
        for ((index, mirror) in DOWNLOAD_MIRRORS.withIndex()) {
            dest.delete()
            safeLog?.invoke("正在尝试下载线路 ${index + 1}/${DOWNLOAD_MIRRORS.size}")
            val started = System.nanoTime()
            var lastReport = 0L
            val result = Aria2c.download(ctx, mirror + ASSET_URL, dest, { p ->
                onProgress?.invoke(p)
                val now = System.nanoTime()
                if (now - lastReport > 500_000_000L) {
                    val bytes = dest.length()
                    val seconds = ((now - started).coerceAtLeast(1L)) / 1_000_000_000.0
                    safeLog?.invoke("已下载 ${formatBytes(bytes)} / 12.4 MB · ${formatBytes((bytes / seconds).toLong())}/秒")
                    lastReport = now
                }
            }, onLog = safeLog)
            if (result.success && dest.isFile && dest.length() >= MIN_REC_ZIP_BYTES) {
                downloaded = true
                break
            }
            lastMessage = result.message.ifBlank { "线路下载失败" }
            safeLog?.invoke("线路 ${index + 1} 失败：$lastMessage")
            if (result.message == "已取消") break
        }
        if (!downloaded) {
            safeLog?.invoke("✗ DNA·rec 独立工具包下载失败：$lastMessage")
            dest.delete()
            return false
        }
        val private = privateDir(ctx).apply { mkdirs() }
        val staging = File(ctx.cacheDir, "dna-rec-tools-staging").apply { deleteRecursively(); mkdirs() }
        return try {
            ZipFile(dest).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    if (entry.isDirectory) return@forEach
                    val name = entry.name.substringAfterLast('/')
                    if (name !in REQUIRED) return@forEach
                    val out = File(staging, name)
                    zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                }
            }
            if (!REQUIRED.all { File(staging, it).isFile }) {
                safeLog?.invoke("✗ 独立工具包缺少必要文件")
                return false
            }
            private.deleteRecursively()
            private.mkdirs()
            staging.listFiles()?.forEach { it.copyTo(File(private, it.name), overwrite = true) }
            val r = RootShell.exec(
                "rm -rf '$TOOLS_ROOT' && mkdir -p '$TOOLS_ROOT' && cp -f '${private.absolutePath}'/* '$TOOLS_ROOT/' && chmod 755 '$TOOLS_ROOT'/* && echo __REC_DEPLOYED__",
                timeoutMs = 60000
            )
            val ok = r.success && r.stdout.contains("__REC_DEPLOYED__") && toolsInstalled(ctx)
            onProgress?.invoke(100)
            safeLog?.invoke(if (ok) "✓ DNA·rec 独立工具链已部署" else "✗ DNA·rec 工具链部署失败")
            ok
        } catch (e: Throwable) {
            safeLog?.invoke("✗ DNA·rec 工具包解压失败：${e.message}")
            false
        } finally {
            dest.delete()
            staging.deleteRecursively()
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
            bytes >= 1024 -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun isNoisyDownloadLine(line: String): Boolean {
        val s = line.trim()
        return s.contains("libaria2c.so", true) || s.contains("CN:", true) ||
            s.matches(Regex("^\\[#.*\\]\\s*\\d+(?:\\.\\d+)?(?:MiB|KiB|B)/.*")) ||
            s.startsWith("固定地址：") || s.startsWith("固定地址:")
    }

    /** 在脚本启动前使用工具包内 7z 展开对应归档。 */
    @JvmStatic
    fun prepareWorkspace(ctx: Context, work: String, cardTemplate: Boolean, onLog: ((String) -> Unit)? = null): Boolean {
        if (workspaceReady(work, cardTemplate)) {
            onLog?.invoke("✓ 已复用本地工具与模板工作区")
            return true
        }
        if (!toolsInstalled(ctx)) {
            onLog?.invoke("✗ 请先下载 DNA·rec 独立工具链")
            return false
        }
        val archive = File(privateDir(ctx), if (cardTemplate) "bin.tar.zst" else "twrp.tar.zst")
        val staging = File(ctx.cacheDir, if (cardTemplate) "rec-bin-staging" else "rec-twrp-staging")
        staging.deleteRecursively(); staging.mkdirs()
        return try {
            val decode = RootShell.exec(
                "set -o pipefail; " +
                    "rm -rf '${staging.absolutePath}/.zst' && mkdir -p '${staging.absolutePath}/.zst' && " +
                    "echo '[rec] 7z 正在解压 ${archive.name}...' && " +
                    "'${TOOLS_ROOT}/7z' x '${archive.absolutePath}' -y -o'${staging.absolutePath}/.zst' 2>&1; " +
                    "__rc=\$?; if [ \$__rc -ne 0 ]; then echo '[rec] 7z 第一阶段失败，退出码: '\$__rc; exit \$__rc; fi; " +
                    "__found=0; for __tar in \$(find '${staging.absolutePath}/.zst' -type f -name '*.tar' -print); do " +
                    "__found=1; echo '[rec] 7z 正在展开:' \$__tar; " +
                    "'${TOOLS_ROOT}/7z' x \"\$__tar\" -y -o'${staging.absolutePath}' 2>&1; " +
                    "__rc=\$?; if [ \$__rc -ne 0 ]; then echo '[rec] tar 展开失败，退出码: '\$__rc; exit \$__rc; fi; " +
                    "done; if [ \$__found -eq 0 ]; then echo '[rec] 7z 未找到内部 tar 文件'; exit 2; fi; " +
                    "chmod -R 755 '${staging.absolutePath}'",
                timeoutMs = 120000
            )
            if (!decode.success) {
                val detail = listOf(decode.stderr.trim(), decode.stdout.trim())
                    .firstOrNull { it.isNotEmpty() } ?: "无错误输出"
                throw IOException("7z 解压失败（退出码 ${decode.code}）：$detail")
            }
            val command = if (cardTemplate) {
                "__bin=\$(find '${staging.absolutePath}' -type d -name bin -print -quit); " +
                    "[ -n \"\$__bin\" ] || { echo '[rec] 归档内缺少 bin 目录'; exit 1; }; " +
                    "rm -rf '$work/bin' && mkdir -p '$work/bin' && cp -rf \"\$__bin/.\" '$work/bin/' && " +
                    "chmod -R 755 '$work/bin' && test -f '$work/bin/android/7zzs'"
            } else {
            "__meta=\$(find '${staging.absolutePath}' -type d -name META-INF -print -quit); " +
                    "[ -n \"\$__meta\" ] || { echo '[rec] 归档内缺少 META-INF 目录'; exit 1; }; " +
                    "__root=\${__meta%/META-INF}; rm -rf '$work/META-INF' '$work/Platform-Tools' && mkdir -p '$work' '$work/probiotics' && " +
                    "cp -rf \"\$__root/.\" '$work/' && rm -f '$work/Regional_Flasher_COS.bat' && chmod -R 755 '$work/META-INF' '$work/Platform-Tools' 2>/dev/null && " +
                    "test -d '$work/META-INF/com/google/android/helpers' && test -f '$work/META-INF/com/google/android/update-binary'"
            }
            val r = RootShell.exec(command, timeoutMs = 60000)
            val ok = r.success
            onLog?.invoke(if (ok) "✓ ${if (cardTemplate) "bin" else "TWRP 模板"} 已由 APK 解压到工作区" else "✗ 工作区部署失败")
            ok
        } catch (e: Throwable) {
            onLog?.invoke("✗ tar.zst 解压失败：${e.message}")
            false
        } finally {
            staging.deleteRecursively()
        }
    }

    /** 使用独立工具链压缩 ZIP；关闭 7-Zip 逐文件输出，避免大体积日志管道拖慢压缩。 */
    @JvmStatic
    fun zipWorkspace(ctx: Context, work: String, outDir: String, name: String, onLog: ((String) -> Unit)? = null, cancelled: (() -> Boolean)? = null): Boolean {
        if (cancelled?.invoke() == true) return false
        val zipPath = "$outDir/$name.zip"
        return try {
            if (cancelled?.invoke() == true) return false
            val p7z = "$TOOLS_ROOT/7z"
            val result = RootShell.exec(
                "rm -f '$zipPath' && mkdir -p '$outDir' && " +
                    "echo '[rec] 使用独立 7z 开始压缩成品 ZIP；隐藏逐文件百分比输出' && " +
                    "cd '$work' && __files=\$(find . -type f | wc -l | tr -d ' ') && __kb=\$(du -sk . | awk '{print \$1}') && " +
                    "echo \"[rec] 待打包文件：\$__files 个 · 工作区约 \$__kb KiB\" && " +
                    "'$p7z' a -tzip '$zipPath' . -y -bsp0 -bso0 -bse1 2>&1 && " +
                    "test -s '$zipPath'",
                timeoutMs = 0L,
                log = onLog,
                isCancelled = cancelled
            )
            val ok = result.success
            onLog?.invoke(if (ok) "✓ 7z ZIP 打包完成：$zipPath" else "✗ 7z ZIP 打包失败（退出码 ${result.code}）：${result.stderr.trim().ifBlank { result.stdout.trim() }}")
            ok
        } finally {
            if (cancelled?.invoke() == true) RootShell.exec("rm -f '$zipPath'", timeoutMs = 15000)
        }
    }

    private fun extractTar(archive: File, destination: File) {
        if (!archive.isFile) throw IOException("缺少 ${archive.name}")
        FileInputStream(archive).use { raw ->
                TarArchiveInputStream(raw).use { tar ->
                    val root = destination.toPath().toAbsolutePath().normalize()
                    var top: String? = null
                    var entry = tar.nextTarEntry
                    while (entry != null) {
                        var name = entry.name.replace('\\', '/')
                        while (name.startsWith("/")) name = name.substring(1)
                        if (top == null && entry.isDirectory && !name.contains('/')) top = name
                        if (top != null && name.startsWith("${top!!}/")) name = name.substring(top!!.length + 1)
                        if (name.isNotEmpty() && !name.contains("..")) {
                            val target: Path = root.resolve(Paths.get(name).normalize()).normalize()
                            if (!target.startsWith(root)) throw IOException("archive path escapes destination")
                            if (entry.isDirectory) {
                                Files.createDirectories(target)
                            } else if (!entry.isSymbolicLink && !entry.isLink && !entry.isCharacterDevice && !entry.isBlockDevice && !entry.isFIFO) {
                                target.parent?.let { Files.createDirectories(it) }
                                FileOutputStream(target.toFile()).use { out -> tar.copyTo(out) }
                                target.toFile().setExecutable((entry.mode and 0x40) != 0, false)
                            }
                        }
                        entry = tar.nextTarEntry
                    }
        }
    }
    }
}
