package com.probiotics.xiaoni

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * DNA 工具箱运行时（v3.10.0 新增）：
 * - 首次使用把 jniLibs 内置的 17 个 ARM64 工具释放到 filesDir/dna-tools/ 并还原真实文件名（dna、busybox、lpmake...）
 * - 原版 DNA 工具箱（com.dna.tools）即从应用数据目录执行工具，root 下运行无兼容问题
 * - 部分 ROM 的 SELinux 禁止 magisk 域 exec app_data_file 时，自动经 root 中转到 /data/local/tmp/dna-tools 兜底
 * - 所有命令经 su 执行，stdout/stderr 合并流式回传（与 Aria2c 相同的看门狗 + 取消机制）
 */
object DnaTools {

    // jniLibs 名称 → 工具真实名称（dna 按名称调用兄弟工具，必须还原）
    private val BINARIES = linkedMapOf(
        "libdna.so" to "dna",
        "libdna_busybox.so" to "busybox",
        "libdna_brotli.so" to "brotli",
        "libdna_e2fsdroid.so" to "e2fsdroid",
        "libdna_extract_erofs.so" to "extract.erofs",
        "libdna_extract_f2fs.so" to "extract.f2fs",
        "libdna_img2simg.so" to "img2simg",
        "libdna_simg2img.so" to "simg2img",
        "libdna_lpmake.so" to "lpmake",
        "libdna_magiskboot.so" to "magiskboot",
        "libdna_mke2fs.so" to "mke2fs",
        "libdna_mkfs_erofs.so" to "mkfs.erofs",
        "libdna_mkfs_f2fs.so" to "mkfs.f2fs",
        "libdna_payload_extract.so" to "payload_extract",
        "libdna_resize2fs.so" to "resize2fs",
        "libdna_sload_f2fs.so" to "sload_f2fs",
        "libdna_zstd.so" to "zstd",
    )

    class Result(val success: Boolean, val output: String, val message: String) {
        // Java 侧便捷访问（属性 getter 为 getSuccess/getOutput/getMessage）
        fun ok(): Boolean = success
        fun text(): String = output
    }

    // 已完成部署的工具目录（app 私有目录优先，执行失败自动换 /data/local/tmp 中转目录）
    @Volatile
    private var activeDir: File? = null

    fun toolsDir(ctx: Context): File = File(ctx.filesDir, "dna-tools")
    private fun relayDir(ctx: Context): File = File("/data/local/tmp/dna-tools")

    // v3.28.8：正版校验终极方案 —— 二进制全部恢复原版（零 patch，Nuitka 常量 blob 无法安全修改）。
    // dna / extract.f2fs / sload.f2fs 启动时 getcwd() 严格要求以下两路径之一（对齐原版 com.dna.tools app）：
    //   /data/user/0/com.dna.tools/files 或 /data/data/com.dna.tools/files
    // 因此 root 预建该"伪装包目录"，所有命令的工作目录 cd 到那里执行（工具本体仍在 /data/local/tmp/dna-tools，
    // 经 PATH 调用；与原版 executor.sh 的 START_DIR 机制完全一致）
    const val FAKE_HOME = "/data/data/com.dna.tools/files"
    const val RELAY_PATH = "/data/local/tmp/dna-tools"

    /** root 预建伪装包目录（getcwd 校验目标；不存在则 dna 等二进制报"盗版"退出） */
    private fun ensureFakeHome(): Boolean {
        val r = RootShell.exec(
            "mkdir -p '$FAKE_HOME' && cd '$FAKE_HOME' && echo __DNA_OK__",
            timeoutMs = 15000)
        return r.success && r.stdout.contains("__DNA_OK__")
    }

    // ============ 工程管理（v3.28.3：对齐原版 executor.sh 双目录架构） ============
    // DNA_DIR=/sdcard/PDNA 工程根目录（img / payload.bin 等源文件，用户可见）
    // DNA_TMP=/data/PDNA  分解输出根目录（root 模式，dna extract 产物在这里）
    // DNA_PRO=/sdcard/PDNA/工程名   DNA_DRO=/data/PDNA/工程名
    // 当前工程记录：SharedPreferences + /data/local/tmp/DNA.ini（兼容 dna 二进制）

    const val WORK_ROOT = "/sdcard/PDNA"
    const val TMP_ROOT = "/data/PDNA"
    private const val PREFS = "dna_prefs"
    private const val KEY_CURRENT = "current_project"
    private const val DNA_INI = "/data/local/tmp/DNA.ini"

    @JvmStatic
    fun currentProject(ctx: Context): String? {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // v3.30.18：工程前缀统一为 PDNA_（兼容识别迁移前的 PDMA_ 旧值）
        return prefs.getString(KEY_CURRENT, null)?.takeIf { it.startsWith("PDNA_") || it.startsWith("PDMA_") }
    }

    @JvmStatic
    fun setCurrentProject(ctx: Context, name: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CURRENT, name).apply()
        // 同步 DNA.ini（原版 TMPDIR/DNA.ini 机制，dna 二进制可能读取）
        runCatching {
            RootShell.exec(
                if (name.startsWith("PDNA_") || name.startsWith("PDMA_"))
                    "mkdir -p " + quote("$TMP_ROOT/$name") + "; echo " + quote(name) + " > $DNA_INI"
                else "rm -f $DNA_INI", timeoutMs = 10000)
        }
    }

    /** 清除当前工程记录（删除当前工程后调用） */
    @JvmStatic
    fun clearCurrentProject(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_CURRENT).apply()
        runCatching { RootShell.exec("rm -f $DNA_INI", timeoutMs = 10000) }
    }

    fun projectDir(name: String): File = File(WORK_ROOT, name)

    /** 分解输出目录（原版 DNA_DRO） */
    fun droDir(name: String): File = File(TMP_ROOT, name)

    /** root 检查目录存在（scoped storage 下 File API 不可靠） */
    private fun dirExists(path: String): Boolean =
        RootShell.exec("[ -d '$path' ] && echo __YES__ || echo __NO__", timeoutMs = 10000)
            .stdout.contains("__YES__")

    /** 列出全部工程（PDNA_ 开头目录；v3.30.18 兼容迁移前的 PDMA_ 旧目录），返回工程名列表 */
    @JvmStatic
    fun listProjects(): List<String> {
        val r = RootShell.exec("ls -1 '$WORK_ROOT' 2>/dev/null", timeoutMs = 15000)
        return r.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("PDNA_") || it.startsWith("PDMA_") }
            .sorted()
            .toList()
    }

    /** 新建工程（root 同时创建 /sdcard 与 /data 双目录，原版 project.sh XJ 逻辑）
     *  v3.28.4：工程名过滤全部 shell 元字符（含引号 —— 修复"实际创建成功却报 no closing quote 失败"），
     *  脚本内全部经 quote() 转义，创建后校验目录确实存在才报告成功 */
    @JvmStatic
    fun createProject(name: String): Pair<String, String?> {
        // 仅保留：中英文 / 数字 / . _ -（引号、空白、$ 等元字符全部替换为 _；长度上限 40）
        val clean = name.trim()
            .replace(Regex("[^\\w.\\-\u4e00-\u9fa5]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_', ' ')
            .take(40)
        if (clean.isEmpty()) return "" to "工程名不能为空（仅支持中英文、数字、点、横杠）"
        var final = "PDNA_$clean"
        val stamp = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US)
            .format(java.util.Date())
        if (dirExists("$WORK_ROOT/$final") || dirExists("$TMP_ROOT/$final")) {
            final = "PDNA_${clean}_$stamp"
        }
        val pro = "$WORK_ROOT/$final"
        val dro = "$TMP_ROOT/$final"
        val script = buildString {
            append("mkdir -p ").append(quote(WORK_ROOT)).append(" ").append(quote(TMP_ROOT)).append("\n")
            append("mkdir -p ").append(quote(pro)).append(" ").append(quote(dro)).append("\n")
            append("chmod 777 ").append(quote(WORK_ROOT)).append(" ").append(quote(pro)).append(" ")
                .append(quote(TMP_ROOT)).append(" ").append(quote(dro)).append(" 2>/dev/null\n")
            append("echo ").append(quote(final)).append(" > ").append(DNA_INI).append("\n")
            append("echo __DNA_OK__")
        }
        val r = RootShell.exec(script, timeoutMs = 30000)
        // v3.28.4：目录确实创建成功即算成功（不依赖 shell 返回码，杜绝误报失败）
        val ok = (r.success && r.stdout.contains("__DNA_OK__")) || dirExists(pro)
        return if (ok) final to null
        else "" to (r.stderr.trim().ifEmpty { "创建失败（需要 ROOT）" })
    }

    /** 删除工程（root 删除 /sdcard 与 /data 双目录 + 中转目录，原版 project.sh SC 逻辑） */
    @JvmStatic
    fun deleteProject(name: String): Boolean {
        if (!name.startsWith("PDNA_") && !name.startsWith("PDMA_")) return false
        val script = buildString {
            append("rm -rf ").append(quote("$WORK_ROOT/$name")).append(" ")
                .append(quote("$TMP_ROOT/$name")).append(" /data/local/tmp/dna-tools/").append(name).append("\n")
            append("[ -f ").append(DNA_INI).append(" ] && grep -q ^").append(quote(name)).append("$ ").append(DNA_INI)
            append(" && rm -f ").append(DNA_INI).append(" || true\n")
            append("echo __DNA_OK__")
        }
        val r = RootShell.exec(script, timeoutMs = 120000)
        return r.success && r.stdout.contains("__DNA_OK__")
    }

    // root 列目录（app 进程可能无权直读 /sdcard、/data，统一走 su；ls -p 目录带 /）
    private fun listDirEntries(path: String, type: String): List<String> {
        val r = RootShell.exec("ls -p '$path' 2>/dev/null", timeoutMs = 15000)
        if (!r.success) return emptyList()
        val lines = r.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (type == "dir") {
            return lines.filter { it.endsWith("/") }
                .map { it.trimEnd('/') }
                .filter { it != "config" && it != "lost+found" }
                .sorted()
        }
        val files = lines.filter { !it.endsWith("/") }.sortedBy { it.lowercase() }
        if (type == "split_sparse") {
            // 分段镜像：xxx.img.1 xxx.img.2 … 取前缀（原版 findfile.sh split_sparse）
            return files.mapNotNull { n -> Regex("^(.*)\\.[0-9]+$").find(n)?.groupValues?.get(1) }
                .distinct().sorted()
        }
        return when (type) {
            "br" -> files.filter { it.nameEnds(".br") }
            "dat" -> files.filter { it.nameEnds(".new.dat") || it.nameEnds(".dat") }
            "img" -> files.filter { it.nameEnds(".img") }
            "zst" -> files.filter { it.nameEnds(".zst") || it.nameEnds(".zstd") }
            "zip" -> files.filter { it.nameEnds(".zip") || it.nameEnds(".zip2") }
            "bin" -> files.filter { it.nameEnds("payload.bin") }
            // v3.30.13：分解 bin 同时列出 OTA zip（payload_extract 原生支持 zip 内 payload.bin）
            "bin_zip" -> files.filter { it.nameEnds("payload.bin") || it.nameEnds(".zip") }
            else -> files
        }
    }

    private fun String.nameEnds(suffix: String): Boolean =
        length >= suffix.length && substring(length - suffix.length).equals(suffix, ignoreCase = true)

    // ============ v3.30.15：内置文件浏览器（root 列目录，替换系统 SAF 选择器） ============

    /** 文件浏览器条目（Java 侧 getter：getName / isDir / getSize） */
    class BrowseEntry(val name: String, val isDir: Boolean, val size: Long)

    /** root 列目录：目录+文件+大小（隐藏文件不显示；目录在前、名称不区分大小写排序）。
     *  stat 优先（带大小），失败回退 ls -1p。
     *  v3.30.16 修复：stat 的 %n 输出的是完整路径（glob 展开后），
     *  必须取 basename，否则浏览器点目录会拼出 /a//a/b 双重路径导致读取失败 */
    @JvmStatic
    fun browseDir(path: String): List<BrowseEntry> {
        val r = RootShell.exec("stat -c '%F|%s|%n' '$path'/* 2>/dev/null", timeoutMs = 20000)
        if (r.stdout.isNotBlank()) {
            val entries = r.stdout.lines().mapNotNull { line ->
                val parts = line.split("|", limit = 3)
                if (parts.size < 3) return@mapNotNull null
                BrowseEntry(parts[2].trim().substringAfterLast('/'),
                        parts[0].trim() == "directory", parts[1].trim().toLongOrNull() ?: 0L)
            }
            if (entries.isNotEmpty()) {
                return entries.sortedWith(
                    compareByDescending<BrowseEntry> { it.isDir }.thenBy { it.name.lowercase() })
            }
        }
        val r2 = RootShell.exec("ls -1p '$path' 2>/dev/null", timeoutMs = 15000)
        return r2.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
            .map { n -> BrowseEntry(n.trimEnd('/'), n.endsWith("/"), 0L) }
            .sortedWith(compareByDescending<BrowseEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    /** 工程内文件清单（/sdcard/PDNA/工程名）：type 过滤扩展名，"dir" 列子目录，"split_sparse" 列分段镜像前缀 */
    @JvmStatic
    fun listProjectFiles(project: String?, type: String): List<String> {
        val base = if (project != null) "$WORK_ROOT/$project" else WORK_ROOT
        return listDirEntries(base, type)
    }

    /** 分解输出目录（/data/PDNA/工程名）下的子目录 —— 合成 repack 的数据源（原版 findfile.sh dir 列 DNA_DRO） */
    @JvmStatic
    fun listDroDirs(project: String?): List<String> {
        if (project == null) return emptyList()
        return listDirEntries("$TMP_ROOT/$project", "dir")
    }

    // v3.30.11：原版 more.xml 其他功能脚本（assets 内置原版 sh 原样释放，root source 执行，与原版行为 100% 一致）
    private val SCRIPTS = arrayOf(
        "del_vbmeta.sh", "patch_selinux.sh", "my_partition_merge.sh", "partition_merge.sh",
        "merge_superchunk.sh"
    )

    /** 释放原版 sh 到 filesDir/dna-scripts/ 并返回目录（执行时 export 参数后 source） */
    @JvmStatic
    fun scriptsDir(ctx: Context): File {
        val dir = File(ctx.filesDir, "dna-scripts")
        if (!dir.isDirectory) dir.mkdirs()
        for (name in SCRIPTS) {
            val out = File(dir, name)
            if (out.isFile) continue
            runCatching {
                ctx.assets.open("dna-scripts/$name").use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        return dir
    }

    /**
     * 批量获取工程内文件的 dna gettype 类型（原版 findfile.sh img 分支：列表显示 "$i ($info)"）。
     * 一次 root 往返逐文件执行 dna gettype；工具链未就绪时返回空表（列表退化为不显示类型）。
     */
    @JvmStatic
    fun getFileTypes(project: String?, names: List<String>): Map<String, String> {
        if (project == null || names.isEmpty()) return emptyMap()
        val script = buildString {
            append("mkdir -p '").append(FAKE_HOME).append("' 2>/dev/null; cd '").append(FAKE_HOME).append("' 2>/dev/null; ")
            append("export PATH='").append(RELAY_PATH).append("':\$PATH\n")
            for (n in names) {
                append("echo ").append(quote(n)).append("|$(dna gettype ").append(quote("$WORK_ROOT/$project/$n"))
                    .append(" 2>/dev/null)\n")
            }
        }
        val r = RootShell.exec(script, timeoutMs = 10_000L + names.size * 5_000L)
        if (!r.success) return emptyMap()
        return r.stdout.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf('|')
                if (idx <= 0) null
                else line.substring(0, idx) to line.substring(idx + 1).trim()
            }
            .filter { it.second.isNotEmpty() }
            .toMap()
    }


    /**
     * 确保工具可用：释放 + 赋执行权限 + root 同步到中转目录 + 预建伪装包目录 + 自检（dna gettype）。
     * 返回工具目录；root 不可用或释放失败返回 null。
     */
    @JvmStatic
    @JvmOverloads
    fun ensure(ctx: Context, onLog: ((String) -> Unit)? = null): File? {
        // v3.28.8：零 patch 方案 —— 伪装包目录（getcwd 校验）必须先就位
        if (!ensureFakeHome()) return null
        migrateLegacyPrefix(ctx)
        val relay = relayDir(ctx)
        if (selfTest(relay)) {
            activeDir = relay
            return relay
        }
        activeDir?.let { dir -> if (selfTest(dir)) return dir }
        val dir = deploy(ctx, toolsDir(ctx), onLog) ?: return null
        onLog?.invoke("同步工具链到 $RELAY_PATH ...")
        val synced = relayViaRoot(ctx, dir, onLog)
        if (synced != null && selfTest(synced)) {
            activeDir = synced
            return synced
        }
        return null
    }

    // v3.30.18：工程前缀 PDMA_ → PDNA_ 统一迁移（旧版 App 创建的 PDMA_ 工程改名为 PDNA_）
    @Volatile
    private var legacyPrefixMigrated = false

    /** 一次性迁移：两处根目录 PDMA_* 目录 → PDNA_*（目标不存在才改，幂等）+ DNA.ini + prefs 当前工程同步 */
    private fun migrateLegacyPrefix(ctx: Context) {
        if (legacyPrefixMigrated) return
        val script = buildString {
            append("for __d in '").append(WORK_ROOT).append("' '").append(TMP_ROOT).append("'; do cd \"\$__d\" 2>/dev/null")
            append(" && for __f in PDMA_*; do [ -e \"\$__f\" ] && [ ! -e \"PDNA_\${__f#PDMA_}\" ]")
            append(" && mv -f \"\$__f\" \"PDNA_\${__f#PDMA_}\"; done; done\n")
            append("sed -i 's/^PDMA_/PDNA_/' ").append(DNA_INI).append(" 2>/dev/null\n")
            append("echo __DNA_OK__")
        }
        val r = runCatching { RootShell.exec(script, timeoutMs = 60000) }.getOrNull()
        if (r != null && r.success) legacyPrefixMigrated = true
        // prefs 里的当前工程名同步替换（本地操作，始终执行）
        runCatching {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val cur = prefs.getString(KEY_CURRENT, null)
            if (cur != null && cur.startsWith("PDMA_"))
                prefs.edit().putString(KEY_CURRENT, "PDNA_" + cur.removePrefix("PDMA_")).apply()
        }
    }

    // 从 nativeLibraryDir 释放全部工具（版本变化时自动重释放）
    private fun deploy(ctx: Context, outDir: File, onLog: ((String) -> Unit)?): File? {
        return try {
            val nativeDir = File(ctx.applicationInfo.nativeLibraryDir)
            val versionCode = runCatching {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
            }.getOrDefault(0)
            val marker = File(outDir, ".v$versionCode")
            if (marker.isFile && BINARIES.keys.all { File(outDir, BINARIES[it]!!).canExecute() }) {
                return outDir
            }
            if (!outDir.exists() && !outDir.mkdirs()) return null
            BINARIES.forEach { (soName, realName) ->
                val src = File(nativeDir, soName)
                if (!src.isFile) {
                    onLog?.invoke("缺少组件 $soName")
                    return null
                }
                val dst = File(outDir, realName)
                src.copyTo(dst, overwrite = true)
                if (!dst.setExecutable(true, false)) {
                    runCatching { RootShell.exec("chmod 755 '${dst.absolutePath}'", timeoutMs = 10000) }
                }
            }
            outDir.listFiles()?.forEach { if (it.name.startsWith(".v")) it.delete() }
            marker.createNewFile()
            outDir
        } catch (e: Exception) {
            onLog?.invoke("工具释放失败: ${e.message}")
            null
        }
    }

    // root 拷贝到 shell_data_file 目录（各 ROM 均允许 root exec；版本标记避免重复复制）
    // v3.28.7：修复 toybox cp 对目录源要求 -R 的兼容问题（'dir/.' 复制失败导致工具链为空 → 误报需要 ROOT）
    //           glob 放在引号外展开（工具名无空格），复制后逐项校验数量
    private fun relayViaRoot(ctx: Context, src: File, onLog: ((String) -> Unit)?): File? {
        val relay = relayDir(ctx)
        val versionCode = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
        }.getOrDefault(0)
        val check = RootShell.exec(
            "[ -x '" + relay.absolutePath + "/dna' ] && [ -f '" + relay.absolutePath + "/.v$versionCode' ] && echo __YES__ || echo __NO__",
            timeoutMs = 15000)
        if (check.stdout.contains("__YES__")) return relay
        val script = buildString {
            append("mkdir -p '").append(relay.absolutePath).append("'\n")
            append("rm -rf '").append(relay.absolutePath).append("'/* 2>/dev/null\n")
            append("cp -f ").append(src.absolutePath).append("/* '").append(relay.absolutePath).append("/'\n")
            append("chmod 755 '").append(relay.absolutePath).append("'/*\n")
            append("touch '").append(relay.absolutePath).append("/.v").append(versionCode).append("'\n")
            append("echo __DNA_OK__")
        }
        val result = RootShell.exec(script, timeoutMs = 60000)
        if (!result.success || !result.stdout.contains("__DNA_OK__")) return null
        // 二次校验：17 个工具全部就位（cp 静默失败时兜底重试一次逐个复制）
        val verify = RootShell.exec(
            "ls -1 '" + relay.absolutePath + "' | wc -l", timeoutMs = 10000)
        if (verify.stdout.trim().toIntOrNull()?.let { it >= BINARIES.size } == true) return relay
        onLog?.invoke("工具链不完整，重试逐个复制 ...")
        val retry = buildString {
            for (name in BINARIES.values) {
                append("cp -f '").append(src.absolutePath).append("/").append(name)
                    .append("' '").append(relay.absolutePath).append("/").append(name).append("'; ")
            }
            append("chmod 755 '").append(relay.absolutePath).append("'/*; ")
            append("[ -x '").append(relay.absolutePath).append("/dna' ] && echo __DNA_OK2__ || echo __NO__")
        }
        val r2 = RootShell.exec(retry, timeoutMs = 60000)
        return if (r2.stdout.contains("__DNA_OK2__")) relay else null
    }

    /**
     * v3.40.15：确保 app 进程（JNI libpayload_extract_jni.so）可读 payload 输入文件。
     *
     * /sdcard 上 root 属主文件 app 直读必 EACCES 的真正机制：FUSE daemon 以 media_rw
     * 身份读底层 /data/media/0/...，root 建的 600 文件它读不了 → 对 app 报 EACCES；
     * 而对 FUSE 视图 chmod/chown 是 daemon 代执行（media_rw 改不了 root 属主文件的权限位，
     * v3.40.13 实测无效）。
     * 根治：root 直接 chmod 664 底层真实路径（ext4 原生权限，立即生效）→
     * FUSE daemon 可读 → app 可读 → JNI 直读原路径（免 11.9GB 复制 30s+）。
     * 兜底：外置卡/异常文件系统 → root cp 到 app cache + chmod 644（调用方用完可删）。
     *
     * @return 实际可读路径；null = 全部手段失败
     */
    @JvmStatic
    @JvmOverloads
    fun ensureJniReadable(
        ctx: Context,
        path: String,
        onLog: ((String) -> Unit)? = null,
    ): String? {
        // ① 已可直读（app 属主 / 底层本就 644）
        if (tryReadHead(path)) return path
        // ② root chmod 底层真实路径（/storage/emulated/N 与 /sdcard → /data/media/N）
        val real = realMediaPath(path)
        if (real != null) {
            onLog?.invoke("… root 放行底层文件权限 …")
            RootShell.exec("chmod 664 '$real' 2>/dev/null; true", timeoutMs = 15000)
            if (tryReadHead(path)) return path
        }
        // ③ 兜底：root cp 到 cache + chmod 644（大文件慢，仅前两级失败才走）
        onLog?.invoke("… 直读仍失败，复制到缓存（用完自动清理）…")
        val cache = File(ctx.cacheDir, "payload_jni_input")
        RootShell.exec(
            "rm -f '${cache.absolutePath}'; cp '$path' '${cache.absolutePath}' && chmod 644 '${cache.absolutePath}'",
            timeoutMs = 30 * 60_000L,
        )
        if (cache.isFile && tryReadHead(cache.absolutePath)) return cache.absolutePath
        return null
    }

    /** 试读首字节验证 app 进程可读（FUSE 上 File.canRead 不可靠，真读一次为准） */
    private fun tryReadHead(path: String): Boolean = try {
        java.io.FileInputStream(path).use { it.read() >= 0 }
    } catch (e: Exception) {
        false
    }

    /** /sdcard/... 或 /storage/emulated/N/... → /data/media/N/...（外置卡返回 null） */
    private fun realMediaPath(path: String): String? {
        if (path.startsWith("/sdcard/")) return "/data/media/0/" + path.substring("/sdcard/".length)
        val m = Regex("^/storage/emulated/(\\d+)/(.*)$").find(path) ?: return null
        return "/data/media/${m.groupValues[1]}/${m.groupValues[2]}"
    }

    // 自检：dna gettype 对自身可执行文件返回 elf 类型即认为工具链可用
    // v3.28.8：零 patch —— dna 原版二进制要求 getcwd == /data/data/com.dna.tools/files（伪装目录）
    // v3.28.7：不再用 File.canExecute()（部分 ROM 的 SELinux 下 app 进程 stat /data/local/tmp 不可靠，恒 false → 误报 ROOT 失败）
    private fun selfTest(dir: File): Boolean {
        val dna = File(dir, "dna")
        val script = "mkdir -p '" + FAKE_HOME + "' || exit 9; cd '" + FAKE_HOME + "' || exit 9; " +
                "export LD_LIBRARY_PATH='" + dir.absolutePath + "'; export PATH='" + dir.absolutePath + "':\$PATH; " +
                "[ -x '" + dna.absolutePath + "' ] || exit 8; '" + dna.absolutePath + "' gettype '" + dna.absolutePath + "'"
        val result = RootShell.exec(script, timeoutMs = 60000)
        return result.success && result.stdout.isNotBlank()
    }

    /**
     * 执行 DNA 命令（经 su，流式回传输出）。
     * @param command 完整命令行（不含环境变量前缀），如 "dna extract /sdcard/system.img --delete"
     */
    @JvmStatic
    @JvmOverloads
    fun run(
        ctx: Context,
        command: String,
        onLog: ((String) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
        timeoutMs: Long = 30 * 60_000L,
    ): Result {
        val dir = ensure(ctx, onLog) ?: run {
            // v3.28.7：区分 ROOT 不可用与工具链部署失败，避免误导性报错
            val rootOk = runCatching { RootShell.available() }.getOrDefault(false)
            return Result(false, "", if (rootOk) "DNA 工具链初始化失败（工具同步异常，请点「检测」重试）"
            else "DNA 工具链初始化失败（需要 ROOT 授权）")
        }
        val project = currentProject(ctx)
        val pro = project?.let { "$WORK_ROOT/$it" } ?: WORK_ROOT
        val dro = project?.let { "$TMP_ROOT/$it" } ?: TMP_ROOT
        val script = buildString {
            // v3.30.29：stderr 并入 stdout（lpmake 的 liblp 日志「lpmake I ... builder.cpp」与
            // 「Invalid sparse file format」都走 stderr，此前被直接丢弃 → 合成 super 日志缺关键输出）
            append("exec 2>&1\n")
            append("export LD_LIBRARY_PATH='").append(dir.absolutePath).append("'\n")
            append("export PATH='").append(dir.absolutePath).append("':\$PATH\n")
            // v3.28.3：对齐原版 executor.sh 完整环境（DNA_DIR / DNA_TMP / DNA_PRO / DNA_DRO / TMPDIR）
            append("export DNA_DIR=").append(WORK_ROOT).append("\n")
            append("export DNA_TMP=").append(TMP_ROOT).append("\n")
            append("export DNA_PRO='").append(pro).append("'\n")
            append("export DNA_DRO='").append(dro).append("'\n")
            append("export TMPDIR=/data/local/tmp\n")
            // v3.28.8：零 patch —— 原版二进制 getcwd 校验 /data/data/com.dna.tools/files（伪装目录，root 预建）
            // 工具本体在 /data/local/tmp/dna-tools 经 PATH 调用（对齐原版 executor.sh 的 START_DIR + TOOLKIT 机制）
            append("mkdir -p ").append(FAKE_HOME).append("\n")
            append("cd ").append(FAKE_HOME).append(" || cd /data/local/tmp\n")
            append("mkdir -p ").append(WORK_ROOT).append(" ").append(TMP_ROOT).append("\n")
            if (project != null) {
                append("mkdir -p '").append(dro).append("'\n")
                append("echo '").append(project).append("' > ").append(DNA_INI).append("\n")
            }
            append(command.trim()).append("\n")
            append("__rc=\$?\n")
            // dna 二进制内部固定使用 DNA_ 前缀，命令结束后两处目录统一重命名为 PDNA_ 工程前缀
            // （v3.30.18：PDMA_ → PDNA_，工程前缀与根目录 /sdcard/PDNA 命名一致）
            append("for __d in '").append(WORK_ROOT).append("' '").append(TMP_ROOT).append("'; do cd \"\$__d\" 2>/dev/null")
            append(" && for __f in DNA_*; do [ -e \"\$__f\" ] && mv -f \"\$__f\" \"PDNA_\${__f#DNA_}\"; done; done; cd /\n")
            // v3.28.10 修复：必须用 ${__rc}——$__rc__ 会被 shell 解析为变量 __rc__（不存在），
            // 导致标记行输出 __DNA_EXIT_（无数字）→ 正则不匹配 → 所有命令恒报"退出码 -1"（实际成功）
            append("echo __DNA_EXIT_\${__rc}__")
        }
        return try {
            val process = ProcessBuilder("su").start()
            process.outputStream.use { stream ->
                stream.write(script.toByteArray())
                stream.flush()
            }
            val output = StringBuilder()
            val exitCode = java.util.concurrent.atomic.AtomicInteger(-1)
            val reader = Thread {
                runCatching {
                    BufferedReader(InputStreamReader(process.inputStream)).forEachLine { line ->
                        val marker = Regex("__DNA_EXIT_(\\d+)__").find(line)
                        if (marker != null) {
                            exitCode.set(marker.groupValues[1].toIntOrNull() ?: -1)
                            return@forEachLine
                        }
                        synchronized(output) { output.appendLine(line) }
                        onLog?.invoke(line)
                    }
                }
            }.apply { isDaemon = true; start() }

            val startedAt = System.currentTimeMillis()
            while (true) {
                if (process.waitFor(1, TimeUnit.SECONDS)) break
                if (isCancelled()) {
                    process.destroyForcibly()
                    reader.join(1500)
                    return Result(false, output.toString(), "已取消")
                }
                if (System.currentTimeMillis() - startedAt > timeoutMs) {
                    process.destroyForcibly()
                    reader.join(1500)
                    return Result(false, output.toString(), "执行超时（${timeoutMs / 60000} 分钟）")
                }
            }
            reader.join(2000)
            val out = synchronized(output) { output.toString() }
            val code = exitCode.get()
            Result(code == 0, out, if (code == 0) "完成" else "退出码 $code")
        } catch (e: Exception) {
            Result(false, "", e.message ?: "执行失败")
        }
    }

    // 单引号 shell 转义（su 脚本拼接防注入）
    @JvmStatic
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
