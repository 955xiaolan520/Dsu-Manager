package com.probiotics.xiaoni

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * DNA 工具箱运行时（v3.10.0 新增）：
 * - v3.41.11：17 个 ARM64 工具不再内置 jniLibs（APK 减重约 27M），改为云端下载
 *   （GitHub Release dna-tools-v1.zip ≈ 15M，aria2c 多线程 + 镜像线路兜底），
 *   下载后部署到 filesDir/dna-tools/ 并还原真实文件名（dna、busybox、lpmake...）
 * - 原版 DNA 工具箱（com.dna.tools）即从应用数据目录执行工具，root 下运行无兼容问题
 * - 部分 ROM 的 SELinux 禁止 magisk 域 exec app_data_file 时，自动经 root 中转到 /data/local/tmp/dna-tools 兜底
 * - 所有命令经 su 执行，stdout/stderr 合并流式回传（与 Aria2c 相同的看门狗 + 取消机制）
 */
object DnaTools {

    // 工具真实名称清单（dna 按名称调用兄弟工具，必须还原原名；旧版 jniLibs 部署过的目录同样兼容）
    private val TOOLS = arrayOf(
        "dna", "busybox", "brotli", "e2fsdroid", "extract.erofs", "extract.f2fs",
        "img2simg", "simg2img", "lpmake", "magiskboot", "mke2fs", "mkfs.erofs",
        "mkfs.f2fs", "payload_extract", "resize2fs", "sload_f2fs", "zstd",
    )

    // v3.41.15：工具包云端获取 —— 对齐 UpdateCenter（APP 更新）的「API 动态解析 + 镜像回退」，
    // 不再硬编码 release tag：先经 GitHub API（镜像优先）读 latest Release 的 assets，
    // 找到 dna-tools*.zip 的 browser_download_url，再镜像前缀回退下载。
    // 用户把 zip 传到任意版本 Release（且为最新 Release）即可，无需固定 v3.41.11。
    private val TOOLS_API_URLS = arrayOf(
        "https://gh-proxy.com/https://api.github.com/repos/955xiaolan520/Dsu-Manager/releases/latest",
        "https://ghfast.top/https://api.github.com/repos/955xiaolan520/Dsu-Manager/releases/latest",
        "https://ghproxy.net/https://api.github.com/repos/955xiaolan520/Dsu-Manager/releases/latest",
        "https://api.github.com/repos/955xiaolan520/Dsu-Manager/releases/latest",
    )
    private val TOOLS_MIRRORS = arrayOf(
        "https://gh-proxy.com/", "https://ghfast.top/", "https://ghproxy.net/", "")
    // 完整性下限：工具包 ≈ 15M，拒绝镜像返回的 KB 级 HTML 错误页
    private const val MIN_TOOLS_ZIP_BYTES = 14L * 1024 * 1024

    /**
     * v3.41.15：经 GitHub API 解析 latest Release 里工具包附件的真实下载地址（镜像优先回退）。
     * 与 UpdateCenter.fetchLatest 同套路：HttpURLConnection + JSON 解析 assets。
     * @return browser_download_url；Release 没传附件返回 null（区别于网络失败抛异常）
     */
    private fun fetchToolsAssetUrl(onLog: ((String) -> Unit)?): String? {
        var lastError: java.io.IOException? = null
        for (api in TOOLS_API_URLS) {
            var conn: java.net.HttpURLConnection? = null
            try {
                conn = java.net.URL(api).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 8000
                conn.readTimeout = 12000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "Dsu-Manager-Android")
                conn.useCaches = false
                val code = conn.responseCode
                if (code < 200 || code >= 300) throw java.io.IOException("HTTP $code")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val assets = org.json.JSONArray(
                    org.json.JSONObject(body).optJSONArray("assets")?.toString() ?: "[]")
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "")
                    if (name.startsWith("dna-tools") && name.endsWith(".zip")) {
                        return asset.optString("browser_download_url", "")
                    }
                }
                return null   // API 通了但没有工具包附件 → 明确的「未上传」
            } catch (e: java.io.IOException) {
                lastError = e
                onLog?.invoke("API 线路失败：${e.message}")
            } finally {
                conn?.disconnect()
            }
        }
        throw lastError ?: java.io.IOException("no mirror reachable")
    }

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
     * 确保工具可用：工具已就位（云端下载 / 旧版部署遗留）+ root 同步到中转目录 + 预建伪装包目录 + 自检（dna gettype）。
     * 返回工具目录；root 不可用或工具未下载返回 null。
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
        // v3.41.11：工具链云端化 —— 本地未部署时不再从 jniLibs 释放，需先点「下载工具」
        if (!toolsInstalled(ctx)) return null
        val dir = toolsDir(ctx)
        onLog?.invoke("同步工具链到 $RELAY_PATH ...")
        val synced = relayViaRoot(ctx, dir, onLog)
        if (synced != null && selfTest(synced)) {
            activeDir = synced
            return synced
        }
        return null
    }

    /** 本地工具是否已就位（云端下载部署 / 旧版 jniLibs 遗留，17 个全部可执行才算） */
    @JvmStatic
    fun toolsInstalled(ctx: Context): Boolean {
        val dir = toolsDir(ctx)
        return TOOLS.all { File(dir, it).isFile && File(dir, it).canExecute() }
    }

    // ============ v3.41.16：工具链深度自检（「检测」按钮完整报告） ============
    // 文件存在 ≠ 可用：架构不符 / 下载截断的 ELF 只有真实执行才能暴露 ——
    // 「检测」从「只看状态」升级为「完整性逐项校验 + 关键工具真实执行」

    /** 缺失或不可执行的工具名清单（空 = 17 个全部就位） */
    @JvmStatic
    fun missingTools(ctx: Context): List<String> {
        val dir = toolsDir(ctx)
        return TOOLS.filter { !(File(dir, it).isFile && File(dir, it).canExecute()) }
    }

    /** 工具目录总字节数（检测报告显示用） */
    @JvmStatic
    fun toolsBytes(ctx: Context): Long = treeSize(toolsDir(ctx))

    /** 单项检查结果（Java 侧经 getName/getOk/getDetail 读取） */
    class CheckItem(val name: String, val ok: Boolean, val detail: String)

    /**
     * 关键工具真实执行自检（dna / busybox / magiskboot 三个代表）：
     * - dna：走 ensure 全链路（伪装目录 + 中转目录同步 + gettype 自检）—— DNA 核心功能可用的最终判定
     * - busybox / magiskboot：版本或 usage 回显 —— 校验 ELF 完好（截断/架构不符会静默失败）
     * @return 三项结果（顺序固定，供检测报告逐行展示）
     */
    @JvmStatic
    fun functionalCheck(ctx: Context): List<CheckItem> {
        val items = ArrayList<CheckItem>()
        // 1. dna：完整链路（ensure 内部已做 root 同步 + gettype）
        val ensureLogs = ArrayList<String>()
        val dir = ensure(ctx) { line -> ensureLogs.add(line) }
        items.add(CheckItem(
            "dna 自检", dir != null,
            if (dir != null) "gettype 通过 · ${dir.absolutePath}"
            else ensureLogs.lastOrNull() ?: "自检失败（root 未授权或工具损坏）"
        ))
        if (dir != null) {
            // 2. busybox：版本回显
            val bb = runCatching {
                RootShell.exec("'" + File(dir, "busybox").absolutePath + "' 2>&1 | head -1", timeoutMs = 15000)
            }.getOrNull()
            val bbLine = bb?.stdout?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: ""
            items.add(CheckItem("busybox", bbLine.contains("BusyBox", true),
                if (bbLine.contains("BusyBox", true)) bbLine else "无版本回显（文件损坏或架构不符）"))
            // 3. magiskboot：usage 回显（无参退出码非 0，按输出判断）
            // v3.41.19 修复恒误报：标准 magiskboot 无参输出第 1 行是版本横幅、第 2 行空行、
            // "Usage" 在第 3 行 —— 旧 head -2 永远截不到，二进制再健康也报"无 usage 回显"。
            // 改为 head -10 + 同时匹配版本横幅（MagiskBoot）与 Usage。
            val mb = runCatching {
                RootShell.exec("cd '" + dir.absolutePath + "' && './magiskboot' 2>&1 | head -10", timeoutMs = 15000)
            }.getOrNull()
            val mbOut = mb?.stdout ?: ""
            val mbOk = mbOut.contains("Usage", true) || mbOut.contains("MagiskBoot", true)
            val mbLine = mbOut.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
            items.add(CheckItem("magiskboot", mbOk,
                when {
                    mbOk && mbLine.isNotBlank() -> mbLine      // 显示版本横幅，如 "MagiskBoot v26.x - Boot Image Patching Tool"
                    mbOk -> "usage 回显正常"
                    else -> "无回显（文件损坏或架构不符）"
                }))
        } else {
            items.add(CheckItem("busybox", false, "跳过（dna 未通过）"))
            items.add(CheckItem("magiskboot", false, "跳过（dna 未通过）"))
        }
        return items
    }

    /** downloadTools 的失败原因（供 UI 区分「未上传」与「网络失败」给出准确提示） */
    const val TOOLS_ERR_NOT_UPLOADED =
        "GitHub 最新 Release 未上传工具包 dna-tools-v1.zip，请在仓库 Releases 页面手动上传后重试"

    /**
     * v3.41.15：从 GitHub Release 下载工具包（dna-tools-v1.zip ≈ 15M）并部署到 filesDir/dna-tools/。
     * 对齐 APP 更新逻辑（UpdateCenter）：先经 API（镜像优先）动态解析 latest Release 附件地址，
     * 再 aria2c 多线程 + 镜像线路逐个兜底下载（gh-proxy → ghfast → ghproxy → 直连）。
     * @return 下载并部署全部成功
     */
    @JvmStatic
    @JvmOverloads
    fun downloadTools(
        ctx: Context,
        onLog: ((String) -> Unit)? = null,
        onProgress: ((Int) -> Unit)? = null,
    ): Boolean {
        val dest = File(ctx.cacheDir, "dna-tools-v1.zip")
        // 第一步：API 解析工具包真实地址（镜像回退；null = Release 无附件）
        val assetUrl = try {
            onLog?.invoke("正在查询工具包地址（GitHub API）…")
            fetchToolsAssetUrl(onLog)
        } catch (e: java.io.IOException) {
            onLog?.invoke("✗ 无法访问 GitHub API：${e.message}")
            onLog?.invoke("✗ 请检查网络（或稍后重试）")
            return false
        }
        if (assetUrl.isNullOrBlank()) {
            onLog?.invoke("✗ $TOOLS_ERR_NOT_UPLOADED")
            return false
        }
        onLog?.invoke("已定位工具包：${assetUrl.substringAfterLast('/')}")
        // 第二步：镜像前缀回退下载
        var lastError = "下载失败"
        for (mirror in TOOLS_MIRRORS) {
            val url = mirror + assetUrl
            if (mirror.isNotEmpty()) onLog?.invoke("尝试线路：${mirror.trimEnd('/')}")
            val r = Aria2c.download(ctx, url, dest, { p -> onProgress?.invoke(p) },
                onLog = { line -> onLog?.invoke(line) })
            if (r.success && dest.isFile && dest.length() >= MIN_TOOLS_ZIP_BYTES) break
            lastError = if (dest.isFile && dest.length() in 1 until MIN_TOOLS_ZIP_BYTES)
                "文件不完整（${dest.length() / 1048576}M / 15M）"
            else r.message.ifBlank { "下载失败" }
            onLog?.invoke("线路失败：$lastError")
            dest.delete()
            if (r.message == "已取消") break
        }
        if (!dest.isFile || dest.length() < MIN_TOOLS_ZIP_BYTES) {
            onLog?.invoke("✗ 工具包下载失败：$lastError")
            return false
        }
        onProgress?.invoke(100)
        onLog?.invoke("下载完成（${dest.length() / 1048576}M），正在部署 …")
        val deployed = deployToolsZip(dest, toolsDir(ctx))
        dest.delete()
        if (!deployed) {
            onLog?.invoke("✗ 工具包部署失败（解压异常或包不完整）")
            return false
        }
        // 作废中转目录与进程缓存，下次 ensure 重新同步全新工具链
        activeDir = null
        runCatching { RootShell.exec("rm -rf '$RELAY_PATH'", timeoutMs = 15000) }
        onLog?.invoke("✓ 工具链部署完成（17 个工具）")
        return true
    }

    /**
     * 解压工具包到临时目录，17 个工具全部就位后原子替换 toolsDir（失败不影响旧工具链）。
     * Zip Slip 防护：只取条目 basename，且必须是工具清单内的名称。
     */
    private fun deployToolsZip(zipFile: File, outDir: File): Boolean {
        return try {
            val staging = File(outDir.parentFile, "dna-tools.staging")
            staging.deleteRecursively()
            if (!staging.exists() && !staging.mkdirs()) return false
            java.util.zip.ZipFile(zipFile).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    if (entry.isDirectory) return@forEach
                    val name = entry.name.substringAfterLast('/')
                    if (name !in TOOLS) return@forEach
                    val out = File(staging, name)
                    zip.getInputStream(entry).use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                    out.setExecutable(true, false)
                }
            }
            if (!TOOLS.all { File(staging, it).isFile }) return false
            outDir.deleteRecursively()
            staging.renameTo(outDir)
        } catch (e: Exception) {
            false
        }
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
        if (verify.stdout.trim().toIntOrNull()?.let { it >= TOOLS.size } == true) return relay
        onLog?.invoke("工具链不完整，重试逐个复制 ...")
        val retry = buildString {
            for (name in TOOLS) {
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
     * v3.40.17：root 放行底层真实路径（不复制文件）。
     *
     * /sdcard 上 root 属主文件 app 直读必 EACCES 的机制：FUSE 视图上的 chmod/chown 由
     * daemon 代执行，对 root 属主文件实测无效（v3.40.13/15 实测）；必须操作底层
     * ext4 真实路径 /data/media/N/...：
     * ① chmod 664 文件 + 祖先目录链 a+rx（root 建的目录常为 700，daemon 穿不进时文件 chmod 无效）
     * ② 仍失败 → chown 给 app（ext4 原生必生效；FUSE 视图里文件属主变 app，owner 语义放行）
     *
     * @return 可直读的原路径；null = 放行失败（外置卡 / root 异常）
     */
    @JvmStatic
    @JvmOverloads
    fun rootRelaxForApp(path: String, onLog: ((String) -> Unit)? = null): String? {
        if (tryReadHead(path)) return path
        val real = realMediaPath(path) ?: return null
        onLog?.invoke("… root 放行底层文件与目录权限 …")
        RootShell.exec(
            "chmod 664 '$real' 2>/dev/null; " +
                    "d=$(dirname '$real'); " +
                    "while [ \"\$d\" != '/' ] && [ \"\$d\" != '/data/media' ]; do " +
                    "chmod a+rx \"\$d\" 2>/dev/null; d=\$(dirname \"\$d\"); done; true",
            timeoutMs = 20000,
        )
        if (tryReadHead(path)) return path
        onLog?.invoke("… root 转移属主后重试直读 …")
        RootShell.exec(
            "chown ${android.os.Process.myUid()} '$real' 2>/dev/null && chmod 664 '$real'; true",
            timeoutMs = 20000,
        )
        if (tryReadHead(path)) return path
        return null
    }

    /**
     * v3.40.18：确保 app 进程（JNI libpayload_extract_jni.so）可读 payload 输入文件。
     * 链路：直读 / root 底层放行（rootRelaxForApp）/ 兜底 root cp 到内部 cache。
     *
     * v3.40.17 三版实测结论：本类 ROM 的 FUSE/sdcardfs 视图对 other 权限位强制 mask，
     * 底层 chmod 664 甚至 chown app 都无法让 app 直读 /sdcard 上的 root 属主文件
     * （root 的 payload_dumper 能读是因为 daemon 对 uid 0 直接放行）。
     * 唯一 100% 可行的兜底是复制到 app 内部存储（纯 ext4，不经 FUSE，权限真实生效，
     * v3.40.13 实测链路）。本版把 cp 提速做实：
     * ① 源改用底层真实路径 /data/media/N/...（root ext4 直读，不经 FUSE daemon 中转）
     * ② 空间预检（不够提前报错，避免 cp 到一半 ENOSPC）
     * ③ 复制期间轮询 cache 大小输出实时进度
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
        // ①② 直读 / root 放行底层真实路径（chmod 目录链 + chown 兜底）
        rootRelaxForApp(path, onLog)?.let { return it }
        // ③ 兜底：root cp 到内部 cache —— 绕开 FUSE/sdcardfs（内部存储纯 ext4，
        //    root chmod 644 真实生效，app 经 other r 必可读）
        val cache = File(ctx.cacheDir, "payload_jni_input")
        val src = realMediaPath(path) ?: path   // 底层路径直拷（快）；外置卡无映射退回 FUSE 路径
        val need = rootFileSize(src)
        if (need > 0) {
            val free = runCatching {
                android.os.StatFs(ctx.cacheDir.absolutePath).availableBytes
            }.getOrDefault(0L)
            if (free in 1 until need) {
                onLog?.invoke("✗ 内部存储空间不足：需 ${fmtGB(need)}，剩余 ${fmtGB(free)}")
                return null
            }
        }
        onLog?.invoke("… 直读不可用，root 底层直拷到内部缓存" +
                (if (need > 0) "（${fmtGB(need)}，同一文件只拷一次）" else "（同一文件只拷一次）") + " …")
        val cpRc = java.util.concurrent.atomic.AtomicInteger(-1)
        val cpDone = java.util.concurrent.atomic.AtomicBoolean(false)
        val cpThread = Thread({
            val r = RootShell.exec(
                "rm -f '${cache.absolutePath}'; " +
                        "cp '$src' '${cache.absolutePath}' && chmod 644 '${cache.absolutePath}'",
                timeoutMs = 40 * 60_000L,
            )
            cpRc.set(r.code)
            cpDone.set(true)
        }, "jni-cache-cp").apply { isDaemon = true }
        cpThread.start()
        // 复制进度：轮询 cache 落盘字节（root cp 写入，app stat 可见）
        var lastLen = -1L
        var lastLogMs = 0L
        while (!cpDone.get()) {
            try { Thread.sleep(1500) } catch (e: InterruptedException) { break }
            val len = runCatching { cache.length() }.getOrDefault(0L)
            val now = System.currentTimeMillis()
            if (len != lastLen && now - lastLogMs >= 3000) {
                onLog?.invoke("… 缓存中 ${fmtGB(len)}" +
                        (if (need > 0) " / ${fmtGB(need)}" else "") + " …")
                lastLen = len
                lastLogMs = now
            }
        }
        runCatching { cpThread.join(3000) }
        if (cpDone.get() && cpRc.get() == 0 && cache.isFile && tryReadHead(cache.absolutePath)) {
            onLog?.invoke("✓ 缓存就绪，开始 JNI 提取")
            return cache.absolutePath
        }
        onLog?.invoke("✗ 缓存复制失败（空间不足或 root 异常，rc=${cpRc.get()}）")
        return null
    }

    /** root stat 文件大小（app 对 root 属主 700 目录下的文件 stat 不可靠） */
    private fun rootFileSize(path: String): Long =
        runCatching {
            RootShell.exec("stat -c %s '$path'", timeoutMs = 10000)
                .stdout.trim().toLongOrNull() ?: 0L
        }.getOrDefault(0L)

    private fun fmtGB(bytes: Long): String = String.format("%.1fG", bytes / 1073741824.0)

    // JNI 可读输入 memo：原始路径 → 实际可读路径（进程级，多页面共享）。
    // v3.40.17 核心修复：此前 ROM 界面单分区提取对每个分区各调一次 ensureJniReadable，
    // 放行失败时每个分区都把整包 root cp 到缓存（16G 包 × 61 分区）。
    @Volatile
    private var jniReadableMemo: Pair<String, String>? = null

    /**
     * v3.40.17：解析 JNI 可读输入（带进程级 memo）—— 同一输入文件只放行/兜底复制一次，
     * 后续分区与页面直接复用；换文件自动清理旧缓存副本。所有 JNI 提取调用点统一走这里。
     */
    @JvmStatic
    @JvmOverloads
    @Synchronized
    fun resolveJniReadable(
        ctx: Context,
        path: String,
        onLog: ((String) -> Unit)? = null,
    ): String? {
        val memo = jniReadableMemo
        if (memo != null && memo.first == path) {
            // 原路径现已可直读（权限已放行 / 文件被替换为可读）→ 优先直读，弃旧缓存副本
            if (tryReadHead(path)) {
                jniReadableMemo = path to path
                if (memo.second != path) File(memo.second).delete()
                return path
            }
            // 缓存副本仍在 → 直接复用（免再次复制）
            if (memo.second != path && File(memo.second).isFile) return memo.second
            if (memo.second == path) return path
        }
        val resolved = ensureJniReadable(ctx, path, onLog) ?: return null
        // 换源：清理上一个文件的缓存副本
        if (memo != null && memo.first != path && memo.second != memo.first) {
            File(memo.second).delete()
        }
        jniReadableMemo = path to resolved
        return resolved
    }

    // ============ v3.41.12：私有目录深度清理 ============
    // 问题：FUSE/sdcardfs 直读不可用时，ensureJniReadable 经 root cp 把整包 ROM 复制到
    // cacheDir/payload_jni_input（12G 包 = 12G 副本）。该文件属主是 root —— Android 的
    // 缓存统计按属主 UID 配额，root 属主文件不计入「缓存」（设置里显示 0B、清除缓存按钮
    // 灰色），却整包计入「数据」→ 出现"占用 12.6G 但清不掉缓存"的现象。
    // 系统只有「清除数据」能删（连 ROOT 授权/设置/工具链一起没），故 App 内提供本入口。

    // 待清理清单（filesDir 与 cacheDir 各查一遍；不含 aria2c/dna-tools/dna-scripts 等必需文件）
    private val CLEAN_ENTRIES = arrayOf(
        "ota",                     // filesDir：OTG 助手解包的 payload.bin + extracted 分区镜像（中断/退出遗留）
        "payload_jni_input",       // cacheDir：JNI 提取整包缓存副本（root 属主，最大的坑）
        "payload_online_full.zip", // cacheDir：在线 payload 整包下载
        "flash-images",            // cacheDir：刷机镜像缓存副本（带时间戳累积）
        "bin_parse_input",         // cacheDir：分解 bin 输入缓存
        "dna-tools-v1.zip",        // cacheDir：工具包下载残留（部署失败/中断遗留）
        "dsu-manager-update.apk",  // cacheDir：更新包安装残留
        "dsu-install.zip",         // cacheDir：DSU 安装包缓存
        "local-rootfs-pick",       // cacheDir：rootfs 选择缓存
        "logo.img",                // cacheDir：logo 镜像缓存
    )

    private fun treeSize(f: File): Long = runCatching {
        if (f.isDirectory) f.listFiles()?.sumOf { treeSize(it) } ?: 0L else f.length()
    }.getOrDefault(0L)

    /**
     * v3.41.22 全扫描白名单：filesDir / cacheDir 下除这些条目外全部视为可清理副本。
     * 必需文件：DNA 工具链与脚本、aria2c/adb/fastboot 组件、系统目录（shared_prefs /
     * databases / code_cache / app_webview）。CLEAN_ENTRIES 清不掉的「漏网」大文件
     * （新版本新增的缓存名、意外路径）由全扫描兜住。
     */
    private val KEEP_ENTRIES = setOf(
        "dna-tools", "dna-scripts", "aria2c", "adb", "fastboot",
        "rootfs",        // 终端 Linux rootfs（LinuxImages）
        "bin",           // AdbManager 释放的 adb / fastboot
        "dna-module",    // 用户安装的 DNA 插件
        "shared_prefs", "databases", "code_cache", "app_webview",
    )

    /** 统计可清理空间（字节）。app 是目录属主可 stat root 属主文件，大小统计可靠。
     *  v3.41.22：改为全扫描白名单统计（CLEAN_ENTRIES 全部不在白名单内，天然被覆盖） */
    @JvmStatic
    fun cleanableBytes(ctx: Context): Long {
        var total = 0L
        for (base in arrayOf(ctx.filesDir, ctx.cacheDir)) {
            val children = base.listFiles() ?: continue
            for (child in children) {
                if (child.name in KEEP_ENTRIES) continue
                total += treeSize(child)
            }
        }
        return total
    }

    /**
     * v3.41.22 终极清理：全扫描 filesDir + cacheDir，白名单（工具链/脚本/下载组件/系统目录）
     * 之外的所有文件与目录全部删除 —— Java 删除 + su rm -rf 兜底（root 属主副本）。
     * CLEAN_ENTRIES 点名式清理的增强版：无论副本叫什么名字都逃不掉。
     * 返回实际释放字节数。
     */
    @JvmStatic
    fun deepCleanAll(ctx: Context): Long {
        var freed = 0L
        for (base in arrayOf(ctx.filesDir, ctx.cacheDir)) {
            val children = base.listFiles() ?: continue
            for (child in children) {
                if (child.name in KEEP_ENTRIES) continue
                freed += treeSize(child)
                runCatching { child.deleteRecursively() }
                if (child.exists()) runCatching {
                    RootShell.exec("rm -rf '" + child.absolutePath + "'", timeoutMs = 180000)
                }
            }
        }
        jniReadableMemo = null
        return freed
    }

    /**
     * 深度清理私有目录大文件，返回实际释放字节数。
     * root 属主文件：优先 Java 删除（父目录属主是 app，无 sticky 位，unlink 允许），
     * 残留（部分 ROM 的 SELinux 拦截）经 su rm -rf 兜底。
     */
    @JvmStatic
    fun deepClean(ctx: Context): Long {
        var freed = 0L
        for (name in CLEAN_ENTRIES) {
            for (base in arrayOf(ctx.filesDir, ctx.cacheDir)) {
                val f = File(base, name)
                if (!f.exists()) continue
                freed += treeSize(f)
                runCatching { f.deleteRecursively() }
                if (f.exists()) runCatching {
                    RootShell.exec("rm -rf '" + f.absolutePath + "'", timeoutMs = 120000)
                }
            }
        }
        // JNI 输入 memo 作废（缓存副本已删，下次提取重新放行/复制）
        jniReadableMemo = null
        return freed
    }

    /**
     * v3.41.13：启动自动清理跨进程遗留的大缓存。
     *
     * 遗留成因：JNI 兜底副本（payload_jni_input）与 OTG 解包目录（files/ota）只在
     * 「同进程内换文件 / 刷机流程正常走完」时自动删除 —— App 被杀或流程中断后，
     * 副本成为死文件且可能高达十几 G。启动时进程内必然没有提取/刷机任务在跑，清理安全；
     * 两个目录都是按需重建的（extractOtaPackage 开头自删重建、ensureJniReadable 重新拷贝）。
     *
     * v3.41.21 修复「12.6G 清不掉、必须卸载」：root 属主的副本文件（root cp 产生）
     * 对 app 进程是 EACCES，此前仅 File.deleteRecursively() 删不动 → 改走 deepClean
     * 全套路（覆盖 CLEAN_ENTRIES 全部条目 + su rm -rf 兜底），启动时把历史遗留一并清空。
     */
    @JvmStatic
    fun cleanStaleCaches(ctx: Context) {
        Thread({
            runCatching { deepClean(ctx) }
        }, "startup-clean").apply { isDaemon = true }.start()
    }

    /**
     * v3.41.14：页面退出即清理 —— 分解/提取页 onDestroy 时调用，删除本次会话拷贝的
     * JNI 解析兜底副本（payload_jni_input / bin_parse_input），返回实际释放字节数供调用方提示。
     *
     * v3.41.21 修复：root 属主副本此前删不动（返回上一页提示了清理但文件仍在，
     * 设置里数据占用一直不降）→ Java 删除后残留经 su rm -rf 兜底；filesDir 下
     * 同名目录一并清理。调用方应在后台线程调用（root 删除大目录可能耗时数秒）。
     *
     * 安全性：副本只服务于"解析/列分区"，提取链路全部走原始路径（root CLI 直读）；
     * 页面销毁时本次会话已结束，无引用。作废 memo：下次进入页面重新按需放行/拷贝。
     */
    @JvmStatic
    fun releaseJniCache(ctx: Context): Long {
        var freed = 0L
        for (name in arrayOf("payload_jni_input", "bin_parse_input")) {
            for (base in arrayOf(ctx.cacheDir, ctx.filesDir)) {
                val f = File(base, name)
                if (f.exists()) {
                    freed += treeSize(f)
                    runCatching { f.deleteRecursively() }
                    // root 属主残留（app 视图 EACCES）经 su 兜底删除
                    if (f.exists()) runCatching {
                        RootShell.exec("rm -rf '" + f.absolutePath + "'", timeoutMs = 120000)
                    }
                }
            }
        }
        jniReadableMemo = null
        return freed
    }

    /**
     * v3.40.17：确保 app 进程（JNI）可写 /sdcard 下的输出目录。
     * root 属主目录经 FUSE 对 app 写 = EACCES，且 FUSE 视图 chmod 不生效 →
     * root 在底层真实路径 mkdir -p + 放行（目录 a+rwx + 祖先链 a+rx，
     * 仍失败再 chown -R 给 app），真写探测文件验证。
     */
    @JvmStatic
    @JvmOverloads
    fun ensureAppWritable(dirPath: String, onLog: ((String) -> Unit)? = null): Boolean {
        if (dirWritable(dirPath)) return true
        val real = realMediaPath(dirPath)
        if (real != null) {
            onLog?.invoke("… root 放行输出目录底层权限 …")
            RootShell.exec(
                "mkdir -p '$real'; chmod a+rwx '$real'; " +
                        "d=$(dirname '$real'); " +
                        "while [ \"\$d\" != '/' ] && [ \"\$d\" != '/data/media' ]; do " +
                        "chmod a+rx \"\$d\" 2>/dev/null; d=\$(dirname \"\$d\"); done; true",
                timeoutMs = 20000,
            )
            if (dirWritable(dirPath)) return true
            RootShell.exec(
                "chown -R ${android.os.Process.myUid()} '$real' 2>/dev/null; " +
                        "chmod -R a+rwX '$real' 2>/dev/null; true",
                timeoutMs = 30000,
            )
            if (dirWritable(dirPath)) return true
        } else {
            // 外置卡等无底层映射：FUSE 路径 mkdir 尽力而为
            RootShell.exec("mkdir -p '$dirPath'; chmod a+rwx '$dirPath'; true", timeoutMs = 15000)
            if (dirWritable(dirPath)) return true
        }
        return false
    }

    /** 真写一个探测文件验证目录可写（FUSE 上 File.canWrite 不可靠） */
    private fun dirWritable(dir: String): Boolean = try {
        val probe = File(dir, ".w_probe")
        java.io.FileOutputStream(probe).use { it.write(1) }
        probe.delete()
        true
    } catch (e: Exception) {
        false
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
            // v3.41.11：新增工具未下载提示（工具链已云端化）
            val rootOk = runCatching { RootShell.available() }.getOrDefault(false)
            return Result(false, "", when {
                !rootOk -> "DNA 工具链初始化失败（需要 ROOT 授权）"
                !toolsInstalled(ctx) -> "DNA 工具未下载（请在 DNA 主页点「下载工具」）"
                else -> "DNA 工具链初始化失败（工具同步异常，请点「检测」重试）"
            })
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

    /**
     * v3.40.21：root CLI 提取 —— libpayload_extract.so 是 pie 可执行文件（非 JNI 库，
     * 0 个 Java 符号；JNI 桥接是 libpayload_extract_jni.so 的 Java_native_PayloadExtractNative_*）。
     * 经 root shell 直接运行：root 直读 payload.bin / OTA zip（含 URL，内部 Range 流式）、
     * root 直写输出目录，全程无 FUSE/sdcardfs 权限障碍、零复制。
     *
     * 旗标来源（不再靠猜）：
     * ① 运行时 --help 探测（词边界匹配，进程级缓存一次）——绝对权威；
     * ② 探测失败的兜底默认值取实证：--out/--no-verify 为 OTG 助手生产代码同款调用，
     *    --images/--threads 为 ELF 字符串表证据（IMAGES/NAMES、THREADS/concurrency/COUNT）。
     *
     * @return Result.success=退出码 0；输出/错误在 output（stderr 已并入）
     */
    @JvmStatic
    @JvmOverloads
    fun payloadExtractCli(
        ctx: Context,
        input: String,
        outputDir: String,
        partitions: String,
        onLog: ((String) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
        timeoutMs: Long = 20 * 60_000L,
    ): Result {
        val lib = File(ctx.applicationInfo.nativeLibraryDir, "libpayload_extract.so").absolutePath
        val freshProbe = payloadCliFlags == null
        val flags = detectPayloadCliFlags(lib)
        if (freshProbe) {
            onLog?.invoke("… CLI 旗标: ${flags["images"]} / ${flags["out"]} / " +
                    "${flags["threads"]} / ${flags["noVerify"]} …")
        }
        val cmd = "mkdir -p " + quote(outputDir) + " && " + quote(lib) + " " + quote(input) +
                " " + flags.getValue("images") + " " + quote(partitions) +
                " " + flags.getValue("out") + " " + quote(outputDir) +
                " " + flags.getValue("threads") + " 4 " + flags.getValue("noVerify")
        return run(ctx, cmd, onLog, isCancelled, timeoutMs)
    }

    // CLI 旗标探测缓存（进程级）：null = 未探测
    @Volatile
    private var payloadCliFlags: Map<String, String>? = null

    /** v3.40.21：读 --help 探测旗标（词边界匹配防子串误配：--out 不得命中 --output） */
    private fun detectPayloadCliFlags(lib: String): Map<String, String> {
        payloadCliFlags?.let { return it }
        val help = runCatching {
            RootShell.exec("'" + lib + "' --help 2>&1", timeoutMs = 15000).stdout
        }.getOrDefault("")
        fun pick(default: String, vararg cands: String): String {
            for (c in cands) {
                val re = Regex("(?<![\\w-])" + Regex.escape(c) + "(?![\\w-])")
                if (re.containsMatchIn(help)) return c
            }
            return default
        }
        val flags = linkedMapOf(
            "images" to pick("--images", "--partitions", "-i", "-p"),
            "out" to pick("--out", "--output", "-o"),
            "threads" to pick("--threads", "-t", "--concurrency"),
            "noVerify" to pick("--no-verify", "--no_verify"),
        )
        payloadCliFlags = flags
        return flags
    }

    /**
     * v3.40.21：CLI 失败摘要 —— 优先 error: 行（真实原因），跳过 clap 样板
     * （Usage: / For more information / tip:），取最后一条有意义行。
     */
    @JvmStatic
    fun briefOf(r: Result): String {
        val out = r.output ?: return r.message
        var errLine = ""
        val meaningful = ArrayList<String>()
        for (raw in out.split("\n")) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (errLine.isEmpty() && line.startsWith("error:")) errLine = line
            if (!line.startsWith("Usage:") && !line.startsWith("For more information")
                    && !line.startsWith("tip:")) meaningful.add(line)
        }
        if (errLine.isNotEmpty()) return errLine.removePrefix("error:").trim()
        return meaningful.lastOrNull() ?: r.message
    }

    // 单引号 shell 转义（su 脚本拼接防注入）
    @JvmStatic
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
