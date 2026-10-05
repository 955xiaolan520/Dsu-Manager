package com.mytiantian.updater.vivo

import android.content.Context
import android.util.Log
import com.mytiantian.updater.crypto.VivoCrypto
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Random
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream

class VivoOtaClient(private val context: Context) {

    companion object {
        private const val TAG = "VivoOtaClient"
        private const val TOKEN_NATIVE = "jnisgmain_v2@com.bbk.updater"
        /** redirPost 未返回地址时的兜底下载地址前缀（后接 pkName）。 */
        private const val PK_BASE_URL = "http://sysuptxdl.vivo.com.cn/upgrade/oem/files/"

        /**
         * h5Url 页面地址 → 日志数据文件地址：H5 页面内容由 JS 从 data/CN.js 加载，
         * 解析日志正文时直接抓该文件而非渲染后的 HTML。
         */
        fun changelogDataUrl(h5Url: String): String =
            h5Url.substringBefore('?').replace(Regex("/index\\.html$"), "/data/CN.js")

        /** changelogDataUrl 的逆变换：还原出可在浏览器直接打开的 H5 页面地址。 */
        fun changelogPageUrl(url: String): String =
            url.replace(Regex("/data/[^/]+\\.js$"), "/index.html")
    }

    /** 可选择的升级服务器域名（参照升级检查接口协议分析.md）。 */
    enum class Domain(val value: String, val host: String) {
        /** 国行（默认）：sysupgrade.vivo.com.cn */
        CN("CN", "sysupgrade.vivo.com.cn"),
        /** 出口版：asia-sysupgrade-api.vivoglobal.com（KZ/RU 等海外区域使用） */
        GLOBAL("GLOBAL", "asia-sysupgrade-api.vivoglobal.com");

        /** 根据接口路径构造完整 URL：国行路径前缀为 /，出口版前缀为 /api。 */
        fun url(path: String): String = "https://$host${if (this == GLOBAL) "/api$path" else path}"
    }

    enum class QueryChannel(val value: String) {
        NORMAL("NORMAL"),
        TRIAL("TRIAL"),
        BETA("BETA"),
        ALPHA("ALPHA")
    }

    fun initCrypto(): Boolean {
        VivoCrypto.clearServerUrl(context)
        return VivoCrypto.init(context)
    }

    fun query(
        codename: String,
        modelSwVer: String,
        swVersion: String,
        androidVersion: Int,
        isPhone: Boolean,
        isFull: Boolean,
        sn: String = "A0000000000000A",
        imei: String = "",
        channel: QueryChannel = QueryChannel.NORMAL,
        domain: Domain = Domain.CN
    ): VivoOtaResult {
        // 全量包查询：如果用户输入的是最新版本号，自动回退到上一个小版本
        val adjustedSwVersion = if (isFull && swVersion == modelSwVer) {
            val decremented = decrementVersion(swVersion)
            Log.d(TAG, "Full package query with latest version, auto-decrement: $swVersion -> $decremented")
            decremented
        } else {
            swVersion
        }
        
        val hwVer = codename + "MA"
        val fullSwVersion = if (adjustedSwVersion.contains(".W")) "$adjustedSwVersion.V000L1" else adjustedSwVersion
        val fullVer = if (adjustedSwVersion.contains(".W")) "${codename}_A_$adjustedSwVersion.V000L1" else "${codename}_A_$adjustedSwVersion"
        val versionLong = if (adjustedSwVersion.contains(".W")) "${codename}_N_${codename}MA_$adjustedSwVersion.V000L1" else "${codename}_N_${codename}MA_$adjustedSwVersion"

        val ts = SimpleDateFormat("yy_MM_dd-HH_mm_ss").format(Date())
        val random = Random()
        val elapsedtime = if (isPhone) 140000 + random.nextInt(80000) else 2000000 + random.nextInt(500000)
        val isFullInt = if (isFull) 1 else 0
        // imei 由调用方给出（本机/手输/随机）；为空时退化到客户端自身的解析逻辑
        val effectiveImei = if (imei.isBlank()) genImei() else VivoImei.sanitize(imei).ifEmpty { genImei() }

        val p = LinkedHashMap<String, Any>()
        p["vgcNewActiveVer"] = ""
        p["nt"] = "WIFI"
        // vgcSwVer 取自系统属性 ro.vgc.cust.version（镜像 APP VersionUtils.getVgcSoftVersion()），
        // vivo17 协议为 "17.1.1"；旧的 "1.1.1" 会导致新机型查不到全量包
        p["vgcSwVer"] = "17.1.1"
        p["fullVer"] = fullVer
        p["emmcid"] = ""
        p["sm1"] = "null"
        p["sm2"] = "null"
        p["model"] = codename
        p["hasVgc"] = 1
        p["vgcNewPassiveVer"] = ""
        p["ch"] = "N"
        p["gn"] = 0
        p["newActiveVer"] = ""
        p["version"] = versionLong
        p["st2"] = 0
        p["cu"] = "N"
        p["srm2"] = 0
        p["srm1"] = 0
        p["cy"] = "CN-ZH"
        p["sn2"] = "null"
        p["ne"] = "null"
        p["sn1"] = "null"
        p["public_model"] = modelSwVer
        p["newPassiveVer"] = ""
        p["hwVer"] = hwVer
        p["swVer"] = fullSwVersion
        p["language"] = "zh_CN"
        p["isMan"] = 1
        p["isFull"] = isFullInt
        p["protocalversion"] = "1.0"
        
        // Virtual A/B 设备的全量包查询必须添加 disDownable 参数
        if (isFull && isPhone) {
            p["disDownable"] = 0  // 官方 APP: o0.a.w() 检查 ro.virtual_ab.enabled
        }
        
        p["checkTrige"] = "MANUL"
        p["isstlifeover"] = "false"
        p["hwFingerprint"] = ""
        
        // === 方案 4：添加所有触发全量包的参数 ===
        if (isFull) {
            // 1. 模拟"升级失败"状态 - 官方会在升级失败后添加这个参数
            // 参考：HttpUtils.java 第 610-613 行
            p["alwaysFailedVersion"] = adjustedSwVersion
            
            // 2. 升级失败次数 - 可能影响服务器判断
            // 参考：PrefsUtils.Updating.KEY_SP_MAIN_UPDATE_FAILED_TIMES
            p["update_failed_times"] = 3
            
            // 3. 系统损坏标志 - 尝试所有可能的参数名
            // 参考：PrefsUtils.Updating.KEY_IS_SYSTEM_BROKEN
            p["is_system_broken"] = 1
            p["system_broken"] = 1
            
            // 4. 二进制错误导致的升级失败
            // 参考：PrefsUtils.Updating.KEY_UPDATE_FAILED_FOR_BINARY_ERROR
            p["update_failed_for_binary_error"] = true
            
            // 5. 系统解锁状态
            // 参考：CommonUtils.isSystemBroken() 中的 SETTINGS_KEY_SYSTEM_UNLOCK_STATE
            p["unlock_state"] = 1
            p["system_unlock_state"] = 1
            
            // 6. 强制全量包标志（实验性）
            p["force_full"] = 1
            p["need_full"] = 1
        }

        if (isPhone) {
            // 增量包和全量包使用相同的基础参数
            p["vgcCu"] = "V000"
            p["sf"] = 1
            p["si"] = "null"
            p["dType"] = "phone"
            p["s_n"] = "null"
            p["logVersionNegotiation"] = 5360
            p["ram"] = 16
            p["rom"] = 512
            p["elapsedtime"] = elapsedtime
            p["st1"] = 100000 + random.nextInt(60000)
            p["imei"] = effectiveImei
            p["ms"] = 0
            p["mtype"] = "no"  // 默认 no，官方从 ro.vivo.op.entry 读取
            p["radiotype"] = "L"
        } else {
            p["romVersion"] = "Funtouch $androidVersion.0"
            p["occurTime"] = ts
            p["vgcCu"] = "NULL"
            p["battery"] = 69
            p["sf"] = 0
            p["si"] = ""
            p["oem"] = "${codename}_CN-ZH_FULL_SC_NULL"
            p["dType"] = "tablet"
            p["oemProjects"] = "$codename+${codename}B"
            p["verName"] = "1.1.1.1"
            p["elapsedtime"] = elapsedtime
            p["verCode"] = "000000001"
            p["st1"] = 0
            p["snp"] = sn
            p["imei"] = ""
            p["sdkVersion"] = 34
            p["isCharge"] = "false"
            p["ms"] = -1
            p["mtype"] = "FULL_SC"
            p["radiotype"] = "A"
        }

        // 尝鲜/公测/内测通道：去掉正式版专属字段，改用 taste 字段集
        val isTaste = channel != QueryChannel.NORMAL
        // v3.9.20：留一份 taste 字段改写前的基础参数快照，供 /beta/queryBetaOrTaste.do 使用
        //（vivo17 APP 的该接口基于 getBaseUrl 基础参数集，与 getTastePk 的 taste 字段集不同）
        val baseSnapshot = LinkedHashMap(p)
        if (isTaste) {
            p.remove("isMan")
            p.remove("protocalversion")
            p.remove("checkTrige")
            p.remove("isstlifeover")
            p["trigger"] = "verfy"
            p["isSupportVgcTaste"] = 1
            p["isSupportShowNote"] = 1
            p["hwFingerprint"] = ""
        }

        val rawParams = joinParams(p)
        Log.d(TAG, "Request params (channel=${channel.value}): $rawParams")

        // 公测/内测先查询报名状态（beta/query 或 alpha/getAlphaState）
        if (channel == QueryChannel.BETA || channel == QueryChannel.ALPHA) {
            val betaParams = buildBetaBaseParams(
                model = codename,
                hwVer = hwVer,
                swVer = swVersion,
                cy = "CN-ZH",
                cu = "N",
                dType = if (isPhone) "phone" else "tablet",
                vgcCu = "V000",
                imei = effectiveImei,
                snp = sn,
                isPhone = isPhone,
                romVer = fullSwVersion
            ) + if (channel == QueryChannel.BETA) "&manual=1&push=0" else "&manual=1"
            val stateJson = if (channel == QueryChannel.BETA) {
                sendBetaRequest(betaParams, domain)
            } else {
                sendAlphaRequest(betaParams, domain)
            }
            Log.d(TAG, "${channel.value} state: $stateJson")
        }

        // v3.9.20：镜像 vivo17 系统升级 APP PublicBetaOrTrialCheckTask ——
        // /beta/queryBetaOrTaste.do 是公测/内测/尝鲜的主查询入口：
        //   data.type=1 → 公测招募信息（PublicBetaInfo，无升级包）
        //   data.type=2 → 内测/尝鲜更新包（与普通升级响应同构，retcode/fotaUpdateInfo 含下载信息）
        // 拿到 type=2 的包则直接采用；否则回落到 getTastePk（尝鲜包查询）。
        var betaOrTasteType = 0
        var betaRecruitHint = ""
        var updateResponse: String
        if (isTaste) {
            updateResponse = ""
            var gotPackage = false
            try {
                val botParams = buildBetaOrTasteParams(baseSnapshot, isFull, androidVersion)
                Log.d(TAG, "queryBetaOrTaste params: $botParams")
                val botResp = sendBetaOrTasteRequest(botParams, domain)
                if (!botResp.startsWith("[Error]")) {
                    betaOrTasteType = extractBetaOrTasteType(botResp)
                    Log.d(TAG, "queryBetaOrTaste type=$betaOrTasteType, resp=${botResp.take(300)}")
                    if (betaOrTasteType == 1) {
                        betaRecruitHint = buildBetaRecruitHint(botResp)
                    } else if (betaOrTasteType == 2 && responseHasPackage(botResp)) {
                        updateResponse = botResp
                        gotPackage = true
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "queryBetaOrTaste failed: ${e.message}")
            }
            if (!gotPackage) {
                updateResponse = sendTasteRequest(rawParams, domain)
            }
        } else {
            updateResponse = sendFreshEncryptedRequest(rawParams, domain)
        }
        if (updateResponse.startsWith("[Error]")) {
            throw RuntimeException(updateResponse)
        }

        // 新机型（如 X500）无全量包：isFull=1 返回 retcode=210 时自动降级 isFull=0 查增量包。
        // 降级成功后 redirPost 也会用增量参数集（与实测的 VivoOtaTracker v2 行为一致）。
        // 实测结论：vivo 仅对"基准=最新版"下发同版本全量重刷包（X200 对照验证），
        // 新机型全量包通常晚于增量包上架，此处降级后由 UI 提示用户。
        var fullFallback = false
        var firstFullQueryLog = "" // 记录第一次全量包查询的日志
        Log.d(TAG, "Before fallback check: isFull=$isFull, isTaste=$isTaste, hasPackage=${responseHasPackage(updateResponse)}")
        if (isFull && !isTaste && !responseHasPackage(updateResponse)) {
            val firstRetCode = extractRetCode(updateResponse)
            val firstPkName = extractJsonStr(updateResponse, "pkName\":\"")
            
            // 记录第一次全量包查询的详细信息
            val firstLog = StringBuilder()
            firstLog.append("=== 第一次全量包查询（失败） ===\n")
            firstLog.append("isFull: 1\n")
            firstLog.append("model: ${p["model"]}\n")
            firstLog.append("swVer: ${p["swVer"]}\n")
            firstLog.append("mtype: ${p["mtype"]}\n")
            firstLog.append("retCode: $firstRetCode\n")
            firstLog.append("pkName: $firstPkName\n")
            
            // 输出所有参数
            firstLog.append("\n完整参数列表:\n")
            p.forEach { (key, value) ->
                firstLog.append("  $key: $value\n")
            }
            firstLog.append("\n")
            
            Log.d(TAG, "isFull=1 got no package (retcode=$firstRetCode, pkName='$firstPkName')")
            Log.d(TAG, "Full response preview: ${updateResponse.take(500)}")
            
            // 检查服务器是否返回 local_install_allow=1
            val localInstallAllow = extractField(updateResponse, listOf("local_install_allow"))
            firstLog.append("local_install_allow: $localInstallAllow\n")
            firstLog.append("响应预览: ${updateResponse.take(300)}\n")
            firstLog.append("\n")
            firstFullQueryLog = firstLog.toString()
            
            Log.d(TAG, "local_install_allow from first response: '$localInstallAllow'")
            
            // Recovery 模式：mtype=FULL_SC 时不降级，直接返回失败
            val currentMtype = p["mtype"] as? String
            Log.d(TAG, "Current mtype: $currentMtype")
            
            if (localInstallAllow == "1" && currentMtype != "FULL_SC") {
                // 服务器允许Recovery本地安装，尝试用Recovery参数查询
                Log.d(TAG, "Server allows recovery install, try mtype=FULL_SC")
                p["mtype"] = "FULL_SC"
                p["oem"] = "${p["model"]}_CN-ZH_FULL_SC_NULL"
                val recoveryResponse = sendFreshEncryptedRequest(joinParams(p), domain)
                if (!recoveryResponse.startsWith("[Error]") && responseHasPackage(recoveryResponse)) {
                    Log.d(TAG, "Recovery query succeeded (retcode=${extractRetCode(recoveryResponse)})")
                    updateResponse = recoveryResponse
                    // 不设置 fullFallback，这是正常的Recovery查询
                } else {
                    Log.d(TAG, "Recovery query failed, fallback to isFull=0")
                    p["isFull"] = 0
                    p["mtype"] = "no"
                    val retryResponse = sendFreshEncryptedRequest(joinParams(p), domain)
                    if (!retryResponse.startsWith("[Error]") && responseHasPackage(retryResponse)) {
                        Log.d(TAG, "Fallback query succeeded (retcode=${extractRetCode(retryResponse)})")
                        updateResponse = retryResponse
                        fullFallback = true
                    } else {
                        Log.d(TAG, "Fallback query got no package either, keep isFull=1 response")
                        p["isFull"] = 1
                        p["mtype"] = "no"
                    }
                }
            } else if (currentMtype == "FULL_SC") {
                Log.d(TAG, "Recovery mode (mtype=FULL_SC), do not fallback to isFull=0")
            } else {
                Log.d(TAG, "Normal mode (mtype=$currentMtype), fallback to isFull=0")
                p["isFull"] = 0
                p["mtype"] = "no"  // 降级时改回 no
                val retryResponse = sendFreshEncryptedRequest(joinParams(p), domain)
                if (!retryResponse.startsWith("[Error]") && responseHasPackage(retryResponse)) {
                    Log.d(TAG, "Fallback query succeeded (retcode=${extractRetCode(retryResponse)})")
                    updateResponse = retryResponse
                    fullFallback = true
                } else {
                    // 增量也查不到：还原 isFull=1 的原始响应与参数集
                    Log.d(TAG, "Fallback query got no package either, keep isFull=1 response")
                    p["isFull"] = 1
                    p["mtype"] = "no"
                }
            }
        } else if (isFull && !isTaste) {
            val retCode = extractRetCode(updateResponse)
            val pkName = extractJsonStr(updateResponse, "pkName\":\"")
            Log.d(TAG, "isFull=1 query succeeded: retcode=$retCode, pkName='$pkName'")
        }
        Log.d(TAG, "After fallback: isFull=${p["isFull"]}, mtype=${p["mtype"]}")

        return parseResult(updateResponse, p, channel, domain, fullFallback, betaOrTasteType, betaRecruitHint, firstFullQueryLog)
    }

    /** 公测/内测共用参数集，与 PC 版 buildBetaBaseParams 对应。 */
    private fun buildBetaBaseParams(
        model: String, hwVer: String, swVer: String,
        cy: String, cu: String, dType: String, vgcCu: String,
        imei: String, snp: String, isPhone: Boolean, romVer: String
    ): String {
        val sb = StringBuilder()
        sb.append("model=").append(model)
        sb.append("&hwVer=").append(hwVer)
        sb.append("&swVer=").append(swVer)
        sb.append("&cy=").append(cy)
        sb.append("&cu=").append(cu)
        sb.append("&dType=").append(dType)
        sb.append("&vgcCu=").append(vgcCu)
        sb.append(if (isPhone) "&imei=$imei" else "&snp=$snp")
        sb.append("&romVer=").append(romVer)
        return sb.toString()
    }

    private fun parseResult(
        updateResponse: String,
        queryParams: Map<String, Any>,
        channel: QueryChannel = QueryChannel.NORMAL,
        domain: Domain = Domain.CN,
        fullFallback: Boolean = false,
        betaOrTasteType: Int = 0,
        betaRecruitHint: String = "",
        firstFullQueryLog: String = ""
     ): VivoOtaResult {
        Log.d(TAG, "Raw OTA response: $updateResponse")
        
        // 收集调试日志
        val debugLog = StringBuilder()
        
        // 如果有第一次全量包查询的日志，先显示
        if (firstFullQueryLog.isNotEmpty()) {
            debugLog.append(firstFullQueryLog)
        }
        
        // 添加降级检查日志到界面
        debugLog.append("=== 降级检查 ===\n")
        debugLog.append("查询前 isFull: ${queryParams["isFull"]}\n")
        debugLog.append("查询前 mtype: ${queryParams["mtype"]}\n")
        debugLog.append("update_failed_times: ${queryParams["update_failed_times"]}\n")
        debugLog.append("fullFallback: $fullFallback\n")
        debugLog.append("\n")
        
        debugLog.append("=== 查询参数 ===\n")
        debugLog.append("API: ${if (channel == QueryChannel.NORMAL) "/vgc/v2/getVgcAndPatch.do" else "/upgrade/trial/getTastePk"}\n")
        debugLog.append("Domain: ${domain.value}\n")
        debugLog.append("Channel: ${channel.value}\n")
        debugLog.append("isFull: ${queryParams["isFull"]}\n")
        debugLog.append("model: ${queryParams["model"]}\n")
        debugLog.append("swVer: ${queryParams["swVer"]}\n")
        debugLog.append("vgcSwVer: ${queryParams["vgcSwVer"]}\n")
        debugLog.append("dType: ${queryParams["dType"]}\n")
        debugLog.append("mtype: ${queryParams["mtype"]}\n")
        debugLog.append("oem: ${queryParams["oem"]}\n")
        debugLog.append("update_failed_times: ${queryParams["update_failed_times"]}\n")
        debugLog.append("vgcCu: ${queryParams["vgcCu"]}\n")
        debugLog.append("sf: ${queryParams["sf"]}\n")
        debugLog.append("ms: ${queryParams["ms"]}\n")
        debugLog.append("radiotype: ${queryParams["radiotype"]}\n")
        debugLog.append("\n=== 响应信息 ===\n")

        val updateVersion = extractJsonStr(updateResponse, "version\":\"")
        val pkName = extractJsonStr(updateResponse, "pkName\":\"")
        // 提取真实文件名（用于下载链接）
        val realFileName = extractField(updateResponse, listOf("name", "fileName", "realName", "file", "pkFile"))
        Log.d(TAG, "pkName: '$pkName', realFileName: '$realFileName'")
        
        val retCode = extractRetCode(updateResponse)
        debugLog.append("retCode: $retCode\n")
        debugLog.append("updateVersion: $updateVersion\n")
        debugLog.append("pkName: $pkName\n")
        debugLog.append("realFileName: $realFileName\n")
        
        // 提取 Recovery 本地安装权限标志（提前提取以便在调试日志中显示）
        val localInstallAllow = extractField(updateResponse, listOf("local_install_allow"))
        debugLog.append("local_install_allow: $localInstallAllow\n")
        
        // 检查 retCode 210（全量包查询失败）时的常见问题
        if (retCode == 210) {
            debugLog.append("\n⚠️ retCode 210 表示服务器拒绝了请求\n")
            debugLog.append("常见原因：\n")
            debugLog.append("1. 用了最新版本号查询全量包\n")
            debugLog.append("   → 应该用【当前版本号】而非【目标版本号】\n")
            debugLog.append("2. 服务器暂无全量包\n")
            debugLog.append("   → 可尝试降级查询增量包\n")
        }
        // pkLen 可能是数字或字符串类型
        var pkLen = extractJsonStr(updateResponse, "pkLen\":")
        if (pkLen == "(Not found)" || pkLen.isEmpty()) {
            pkLen = extractJsonStr(updateResponse, "pkLen\":\"")
        }
        // fileSizeMb 存储字节数，供 Java 层格式化
        val sizeMb = if (pkLen != "(Not found)" && pkLen.isNotEmpty()) {
            try { 
                pkLen.toLong().toString() // 存储字节数的字符串
            } catch (_: Exception) { 
                "" 
            }
        } else {
            ""
        }
        Log.d(TAG, "pkLen: '$pkLen', sizeMb: '$sizeMb'")
        // 服务端实际返回的包类型（ext.isFull）：1=全量 0=增量；无更新/旧服务器可能不带
        val isFullPackage = extractIsFullFlag(updateResponse)

        val changelogUrl = extractJsonStr(updateResponse, "h5Url\":\"").let {
            if (it == "(Not found)") "" else it.replace("\\/", "/")
        }
        Log.d(TAG, "Changelog URL: '$changelogUrl'")

        val securityPatch = extractField(updateResponse, listOf(
            "securityPatch", "securityPath", "spVersion", "secPatch", "securityVersion", "secPatchDate", "security_patch"
        ))
        val updateDate = extractField(updateResponse, listOf(
            "createTime", "updateTime", "releaseTime", "publishTime", "upgradeTime", "pubdate", "submitTime"
        ))
        val md5 = extractField(updateResponse, listOf("md5", "fileMd5", "pkMd5"))

        Log.d(TAG, "Security patch: '$securityPatch', Update date: '$updateDate', MD5: '$md5'")
        Log.d(TAG, "Package type (ext.isFull): $isFullPackage")
        Log.d(TAG, "local_install_allow: '$localInstallAllow'")

        val pkUrl = extractPkUrl(updateResponse)
        Log.d(TAG, "pkUrl: '$pkUrl'")
        
        // 提取 sign 字段用于构建下载链接
        // sign 可能在顶层，也可能在 pk URL 的参数中
        var sign = extractField(updateResponse, listOf("sign", "pkSign", "fileSign"))
        Log.d(TAG, "Sign from response fields: '$sign'")
        
        // 如果顶层没有 sign，尝试从 pk URL 中提取
        if ((sign.isEmpty() || sign == "(Not found)") && pkUrl != null) {
            val signMatch = Regex("[?&]sign=([^&]+)").find(pkUrl)
            if (signMatch != null) {
                sign = signMatch.groupValues[1]
                Log.d(TAG, "Extracted sign from pkUrl: '$sign'")
            } else {
                Log.d(TAG, "No sign found in pkUrl")
            }
        }
        
        // 如果还是没有 sign，尝试从 redirPost 响应中提取（稍后）
        if (sign.isEmpty() || sign == "(Not found)") {
            Log.w(TAG, "Warning: No sign found in response or pkUrl, download link may be invalid")
        }
        
        Log.d(TAG, "Final download sign: '$sign'")
        
        var downloadUrl = ""
        var tParam = "" // 从 redirPost URL 中提取的 t 参数
        var redirPostRawUrl = "" // 记录 redirPost 返回的原始 URL
        
        debugLog.append("\n=== Sign 提取 ===\n")
        debugLog.append("sign: $sign\n")
        
        // redirPost 请求下载链接（pkUrl 已在前面提取）
        if (pkUrl != null) {
            try {
                // 使用 buildRedirParams 构建完整的 redirPost 参数
                val redirParams = buildRedirParams(queryParams, pkUrl, updateResponse)
                Log.d(TAG, "RedirPost params: ${redirParams.take(200)}")
                val redirRes = requestRedirPost(redirParams, domain)
                Log.d(TAG, "Redir response: $redirRes")
                
                // 尝试从 redirPost 响应中提取 sign
                if (sign.isEmpty() || sign == "(Not found)") {
                    val redirSign = extractField(redirRes, listOf("sign", "t", "token"))
                    if (redirSign.isNotEmpty() && redirSign != "(Not found)") {
                        sign = redirSign
                        Log.d(TAG, "Extracted sign from redirPost response: '$sign'")
                    }
                }
                
                val dataIdx = redirRes.indexOf("\"data\":\"")
                if (dataIdx >= 0) {
                    val urlStart = dataIdx + 8
                    val urlEnd = redirRes.indexOf("\"", urlStart)
                    if (urlEnd > urlStart) {
                        val rawUrl = redirRes.substring(urlStart, urlEnd).replace("\\/", "/")
                        redirPostRawUrl = rawUrl // 记录原始 URL
                        
                        // 无论 URL 格式如何，都尝试从中提取 sign 和 t 参数
                        val urlSignMatch = Regex("[?&]sign=([^&]+)").find(rawUrl)
                        if (urlSignMatch != null) {
                            sign = urlSignMatch.groupValues[1]
                            Log.d(TAG, "Extracted sign from redirPost URL: '$sign'")
                        }
                        val urlTMatch = Regex("[?&]t=([^&]+)").find(rawUrl)
                        if (urlTMatch != null) {
                            tParam = urlTMatch.groupValues[1]
                            Log.d(TAG, "Extracted t parameter from redirPost URL: '$tParam'")
                        }
                        
                        // 检查 URL 格式是否正常（应该包含 /upgrade/ 或 /oem/files/）
                        if (rawUrl.contains("/upgrade/") || rawUrl.contains("/oem/files/")) {
                            downloadUrl = rawUrl
                            Log.d(TAG, "Got valid downloadUrl from redirPost: $downloadUrl")
                        } else {
                            // URL 格式不对，但我们已经提取了 sign 和 t 参数，稍后用 fallback 构建正确链接
                            Log.d(TAG, "RedirPost returned invalid format URL: $rawUrl")
                            Log.d(TAG, "Will use fallback with extracted sign='$sign' and t='$tParam'")
                        }
                    }
                } else {
                    Log.d(TAG, "redirPost response has no 'data' field")
                }
            } catch (e: Exception) {
                Log.w(TAG, "redirPost failed: ${e.message}")
            }
        }
        
        if (redirPostRawUrl.isNotEmpty()) {
            debugLog.append("\n=== RedirPost 原始 URL ===\n")
            debugLog.append("$redirPostRawUrl\n")
        }
        
        // redirPost 未给出地址（无 pk / 请求失败 / data 缺失）时，按包名回退到固定的 CDN 下载地址
        if (downloadUrl.isEmpty()) {
            // 优先使用真实文件名，如果没有则使用 pkName
            val fileNameForUrl = if (realFileName.isNotEmpty() && realFileName != "(Not found)") {
                realFileName
            } else {
                pkName
            }
            downloadUrl = fallbackDownloadUrl(fileNameForUrl, sign, tParam, domain)
            if (downloadUrl.isNotEmpty()) {
                Log.d(TAG, "downloadUrl empty, fallback to: $downloadUrl (using fileName: $fileNameForUrl)")
                debugLog.append("\n=== Fallback 下载链接 ===\n")
                debugLog.append("使用文件名: $fileNameForUrl\n")
                debugLog.append("域名: sysuptxdl.vivo.com.cn\n")
            }
        }

        debugLog.append("\n=== 最终结果 ===\n")
        debugLog.append("downloadUrl: $downloadUrl\n")
        debugLog.append("changelogUrl: $changelogUrl\n")
        debugLog.append("fullFallback: $fullFallback\n")
        debugLog.append("isFullPackage: $isFullPackage\n")
        if (fullFallback) {
            debugLog.append("\n⚠ 全量包查询失败，已自动降级为增量包\n")
        }

        return VivoOtaResult(
            updateVersion = updateVersion,
            filename = pkName,
            fileSizeBytes = pkLen,
            fileSizeMb = sizeMb,
            downloadUrl = downloadUrl,
            changelogUrl = changelogUrl,
            securityPatch = securityPatch,
            updateDate = updateDate,
            md5 = md5,
            channel = channel.value,
            isFullPackage = isFullPackage,
            fullFallback = fullFallback,
            betaOrTasteType = betaOrTasteType,
            betaRecruitHint = betaRecruitHint,
            rawResponse = updateResponse,
            debugLog = debugLog.toString()
        )
    }

    /**
     * 提取响应中 ext.isFull 包类型标志：1=全量包 0=增量包。
     * 值可能是数字（"isFull":1）或字符串（"isFull":"1"），两者都兼容。
     */
    private fun extractIsFullFlag(json: String): Boolean? {
        val key = "\"isFull\":"
        var idx = json.indexOf(key)
        while (idx >= 0) {
            var s = idx + key.length
            // 跳过空白
            while (s < json.length && json[s].isWhitespace()) s++
            val quoted = s < json.length && json[s] == '"'
            if (quoted) s++
            val digits = StringBuilder()
            while (s < json.length && json[s].isDigit()) {
                digits.append(json[s])
                s++
            }
            if (digits.isNotEmpty()) return digits.toString() != "0"
            // 该处不是数字值（如 "isFullUpdate" 前缀误匹配），继续找下一处
            idx = json.indexOf(key, idx + 1)
        }
        return null
    }

    /**
     * redirPost.do 请求参数（镜像 vivo17 APP HttpUtils.getDlRequestParams()，实测验证）：
     * 直接复用升级查询的完整参数集（APP 的 baseUrl 不含 logVersionNegotiation/ram/rom），
     * 再追加 pk 自带查询串与 upversion/dlrequest/downloadType/timeStamp/nonce/hwFingerprint。
     */
    private fun buildRedirParams(
        queryParams: Map<String, Any>,
        pkUrl: String,
        updateResponse: String
    ): String {
        val rp = LinkedHashMap<String, Any>(queryParams)
        // APP 的 redirPost baseUrl 不含 logVersionNegotiation/ram/rom
        rp.remove("logVersionNegotiation")
        rp.remove("ram")
        rp.remove("rom")
        // name = pk 自带的查询串（name/fileType/fileLength/packageType 等）
        val queryStart = pkUrl.indexOf('?')
        rp["name"] = if (queryStart >= 0) pkUrl.substring(queryStart + 1) else pkUrl
        // upversion = 升级响应里的目标版本
        val targetVersion = extractJsonStr(updateResponse, "version\":\"")
        if (targetVersion != "(Not found)") {
            rp["upversion"] = targetVersion
        }
        // APP 手动下载固定字段（getDlRequestParams 实测取值，dlrequest=1 才会下发直链）
        rp["dlrequest"] = "1"
        rp["downloadType"] = "NORMAL_MANUAL_DOWNLOAD"
        rp["isPacakgeActive"] = "1"
        rp["timeToUptouch"] = "3600000"
        rp["httpsSupport"] = 1
        rp["isTrialVersion"] = "false"
        rp["retry"] = 0
        // 安全校验字段：timeStamp 取升级响应 ext 里的值，缺失时为 0
        rp["timeStamp"] = extractJsonValue(updateResponse, "timeStamp") ?: "0"
        rp["nonce"] = UUID.randomUUID().toString().replace("-", "")
        rp["hwFingerprint"] = ""
        return joinParams(rp)
    }

    /**
     * 响应是否携带可用升级包：retcode=0 且有包名（retcode=210 等表示无可用包，
     * 见镜像 APP g0/a.java 的 retcode 处理）；旧响应不带 retcode 时退化为只看包名。
     */
    private fun responseHasPackage(json: String): Boolean {
        val hasName = extractJsonStr(json, "pkName\":\"") != "(Not found)"
        val retCode = extractRetCode(json)
        return if (retCode != null) retCode == 0 && hasName else hasName
    }

    /** 解析顶层 retcode；缺失或非数字时返回 null（旧服务器/尝鲜接口可能不带）。 */
    private fun extractRetCode(json: String): Int? {
        return extractJsonValue(json, "retcode")?.toIntOrNull()
    }

    /**
     * 提取 JSON 字段的原始值，数字与带引号字符串两种格式均兼容：
     * `"key":123` / `"key":"123"`。找不到（或值为 null）返回 null。
     */
    private fun extractJsonValue(json: String, key: String): String? {
        val needle = "\"$key\":"
        var idx = json.indexOf(needle)
        while (idx >= 0) {
            var s = idx + needle.length
            while (s < json.length && json[s].isWhitespace()) s++
            val sb = StringBuilder()
            val quoted = s < json.length && json[s] == '"'
            if (quoted) s++
            while (s < json.length) {
                val c = json[s]
                if (quoted && c == '"') break
                if (!quoted && (c == ',' || c == '}' || c == ']')) break
                sb.append(c)
                s++
            }
            val value = sb.toString().trim()
            if (value.isNotEmpty() && value != "null") return value
            idx = json.indexOf(needle, idx + 1)
        }
        return null
    }

    private fun joinParams(params: Map<String, Any>): String {
        return params.entries.joinToString("&") { "${it.key}=${it.value}" }
    }

    /** 生成 15 位 IMEI：优先使用系统属性 IMEI，长度不足 15 位则随机生成。 */
    private fun genImei(): String {
        val fromProp = System.getProperty("IMEI", "").trim()
        if (fromProp.length == 15 && fromProp.all { it.isDigit() }) {
            return fromProp
        }
        val sb = StringBuilder(15)
        val rand = Random()
        for (i in 0 until 15) sb.append(rand.nextInt(10))
        return sb.toString()
    }

    /** 兜底下载地址：下载域名 + /upgrade/oem/files/ + 包名 + sign 和 t 参数。包名缺失/为 "(Not found)" 时返回空串。 */
    private fun fallbackDownloadUrl(pkName: String, sign: String = "", tParam: String = "", domain: Domain = Domain.CN): String {
        val name = pkName.trim().trimStart('/')
        if (name.isEmpty() || name == "(Not found)") return ""
        
        // 根据 domain 选择下载域名
        val downloadHost = if (domain == Domain.GLOBAL) {
            "sysuptxdl.vivo.com.cn"
        } else {
            "sysuptxdl.vivo.com.cn"
        }
        
        val baseUrl = "http://$downloadHost/upgrade/oem/files/$name"
        
        // 构建查询参数
        val params = mutableListOf<String>()
        if (sign.isNotEmpty() && sign != "(Not found)") {
            params.add("sign=$sign")
        }
        if (tParam.isNotEmpty() && tParam != "(Not found)") {
            params.add("t=$tParam")
        }
        
        return if (params.isNotEmpty()) {
            "$baseUrl?${params.joinToString("&")}"
        } else {
            baseUrl
        }
    }

    private fun extractPkUrl(json: String): String? {
        val url = extractJsonStr(json, "pk\":\"")
        return if (url == "(Not found)") null else url
    }

    private fun extractJsonStr(json: String, key: String): String {
        val idx = json.indexOf(key)
        if (idx < 0) return "(Not found)"
        val start = idx + key.length
        val end = json.indexOf('"', start)
        if (end < 0) {
            // 数字类型：找到逗号或右大括号作为结束
            val end2 = json.indexOf(',', start)
            val end3 = json.indexOf('}', start)
            val realEnd = when {
                end2 < 0 && end3 < 0 -> json.length
                end2 < 0 -> end3
                end3 < 0 -> end2
                else -> minOf(end2, end3)
            }
            return json.substring(start, realEnd).trim()
        }
        return json.substring(start, end)
    }

    private fun extractFirstAvailable(json: String, keys: List<String>): String {
        for (key in keys) {
            val value = extractJsonStr(json, key)
            if (value != "(Not found)" && value.isNotEmpty()) {
                return value
            }
        }
        return ""
    }

    private fun extractField(json: String, fieldNames: List<String>): String {
        for (name in fieldNames) {
            val strKey = "\"$name\":\""
            val strIdx = json.indexOf(strKey)
            if (strIdx >= 0) {
                val start = strIdx + strKey.length
                val end = json.indexOf('"', start)
                if (end > start) {
                    val value = json.substring(start, end).trim()
                    if (value.isNotEmpty()) return value
                }
            }
            val numKey = "\"$name\":"
            val numIdx = json.indexOf(numKey)
            if (numIdx >= 0) {
                val start = numIdx + numKey.length
                var s = start
                while (s < json.length && json[s].isWhitespace()) s++
                if (s < json.length && json[s] != ',' && json[s] != '}' && json[s] != ']' && json[s] != 'n') {
                    val end = json.indexOfAny(charArrayOf(',', '}', ']'), s)
                    val realEnd = if (end < 0) json.length else end
                    val value = json.substring(s, realEnd).trim().removeSurrounding("\"")
                    if (value.isNotEmpty() && value != "0" && value != "null") return value
                }
            }
        }
        return ""
    }

    // ================================================================
    // Protocol: encrypt → package → base64url
    // ================================================================

    private fun encryptToJvq(plaintext: String): String {
        val encrypted = VivoCrypto.encrypt(plaintext.toByteArray(StandardCharsets.UTF_8))
            ?: throw RuntimeException("Encryption failed")
        // 官方 APP 使用包类型 5 (PKGTYPE_AES_ENCRYPT)
        // SecurityUtils.encryptUrlParams() → SecurityKeyCipher.aesEncrypt() → a.aesEncrypt() → f(bArr, 5, keyVersion)
        val pkg = buildProtocolPackage(5, 2, TOKEN_NATIVE, encrypted)
        return base64UrlEncode(pkg)
    }

    private fun decryptResponse(responseB64: String): String {
        val fullPackage = base64UrlDecode(responseB64)
        val encryptedBody = extractEncryptedBody(fullPackage)
        val decrypted = VivoCrypto.decrypt(encryptedBody)
            ?: throw RuntimeException("Decryption failed")
        return String(decrypted, StandardCharsets.UTF_8)
    }

    private fun buildProtocolPackage(type: Int, keyVersion: Int, token: String, data: ByteArray): ByteArray {
        val tokenBytes = token.toByteArray(StandardCharsets.UTF_8)
        val headerTotalLen = 16 + tokenBytes.size

        val headerFieldBos = java.io.ByteArrayOutputStream()
        val headerFieldDos = DataOutputStream(headerFieldBos)
        headerFieldDos.writeShort(1)
        headerFieldDos.writeByte(tokenBytes.size)
        headerFieldDos.write(tokenBytes)
        headerFieldDos.writeShort(keyVersion)
        headerFieldDos.writeByte(type)
        val headerFieldBytes = headerFieldBos.toByteArray()

        val crc32 = CRC32()
        crc32.update(headerFieldBytes)

        val bos = java.io.ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        dos.writeShort(headerTotalLen)
        dos.writeLong(crc32.value)
        dos.write(headerFieldBytes)
        dos.write(data)
        return bos.toByteArray()
    }

    private fun extractEncryptedBody(fullPackage: ByteArray): ByteArray {
        val dis = DataInputStream(ByteArrayInputStream(fullPackage))
        val headerLen = dis.readUnsignedShort()
        val payloadLen = fullPackage.size - headerLen
        val payload = ByteArray(payloadLen)
        System.arraycopy(fullPackage, headerLen, payload, 0, payloadLen)
        return payload
    }

    private fun base64UrlEncode(data: ByteArray): String {
        return java.util.Base64.getUrlEncoder().encodeToString(data)
    }

    private fun base64UrlDecode(data: String): ByteArray {
        val decoded = URLDecoder.decode(data, "UTF-8").replace('-', '+').replace('_', '/')
        val pad = 4 - decoded.length % 4
        val padded = if (pad != 4) decoded + "=".repeat(pad) else decoded
        return java.util.Base64.getDecoder().decode(padded)
    }

    // ================================================================
    // HTTP
    // ================================================================

    private fun sendFreshEncryptedRequest(plaintext: String, domain: Domain): String {
        val jvqParam = encryptToJvq(plaintext)
        val response = httpPost(domain.url("/vgc/v2/getVgcAndPatch.do"), "jvq_param=$jvqParam")
        return if (!response.startsWith("ACw") && !response.startsWith("ACo")) {
            "[Error] $response"
        } else {
            decryptResponse(response)
        }
    }

    private fun requestRedirPost(params: String, domain: Domain): String {
        val jvqParam = encryptToJvq(params)
        val response = httpPost(domain.url("/pk/redirPost.do"), "jvq_param=$jvqParam")
        return if (!response.startsWith("ACw") && !response.startsWith("ACo")) {
            "[Error] $response"
        } else {
            decryptResponse(response)
        }
    }

    // 尝鲜/公测/内测通道的尝鲜包查询（getTastePk），与 PC 版 sendTasteRequest 对应
    private fun sendTasteRequest(plaintext: String, domain: Domain): String {
        val jvqParam = encryptToJvq(plaintext)
        val response = httpPost(domain.url("/upgrade/trial/getTastePk"), "jvq_param=$jvqParam")
        return if (!response.startsWith("ACw") && !response.startsWith("ACo")) {
            "[Error] $response"
        } else {
            decryptResponse(response)
        }
    }

    // 公测报名状态查询（beta/query）
    private fun sendBetaRequest(params: String, domain: Domain): String {
        val jvqParam = encryptToJvq(params)
        val response = httpPost(domain.url("/beta/query"), "jvq_param=$jvqParam")
        return if (!response.startsWith("ACw") && !response.startsWith("ACo")) {
            "[Error] $response"
        } else {
            decryptResponse(response)
        }
    }

    // 内测报名状态查询（alpha/getAlphaState）
    private fun sendAlphaRequest(params: String, domain: Domain): String {
        val jvqParam = encryptToJvq(params)
        val response = httpPost(domain.url("/alpha/getAlphaState"), "jvq_param=$jvqParam")
        return if (!response.startsWith("ACw") && !response.startsWith("ACo")) {
            "[Error] $response"
        } else {
            decryptResponse(response)
        }
    }

    /**
     * v3.9.20：/beta/queryBetaOrTaste.do 请求参数（镜像 vivo17 APP PublicBetaOrTrialCheckTask.b()）：
     * 基础参数集（getBaseUrl，与普通升级检查一致）追加固定字段：
     * trigger/fingerprint/manual/push/logVersionNegotiation/ram/rom/romVer/action/
     * isFull/isSupportVgcTaste/isSupportShowNote/isCdma。
     */
    private fun buildBetaOrTasteParams(
        baseParams: Map<String, Any>,
        isFull: Boolean,
        androidVersion: Int
    ): String {
        val rp = LinkedHashMap<String, Any>(baseParams)
        // 该接口基于 getBaseUrl 基础参数集：不含 isMan/protocalversion/checkTrige/isstlifeover
        rp.remove("isMan")
        rp.remove("protocalversion")
        rp.remove("checkTrige")
        rp.remove("isstlifeover")
        // 手动检查入口（逆向自 APP 字符串 manul_check_public_or_trial_version）
        rp["trigger"] = "manul_check_public_or_trial_version"
        // UpgradeSecurityHelper.getFingerprintWithNormal()：空指纹（与 hwFingerprint 一致，实测可过）
        rp["fingerprint"] = ""
        rp["manual"] = 1
        rp["push"] = 0
        rp["logVersionNegotiation"] = 5360
        rp["ram"] = 16
        rp["rom"] = 512
        // romVer = ro.vivo.os.build.display.id（300 Ultra 实测 "OriginOS 7"）；
        // OriginOS 版本号 = 安卓版本 - 10（Android 14=OriginOS 4 … Android 17=OriginOS 7），
        // 更旧系统为 Funtouch 命名
        rp["romVer"] = if (androidVersion >= 14) "OriginOS ${androidVersion - 10}" else "Funtouch $androidVersion"
        rp["action"] = "all"
        rp["isFull"] = if (isFull) 1 else 0
        rp["isSupportVgcTaste"] = 1
        rp["isSupportShowNote"] = 1
        rp["isCdma"] = 0
        return joinParams(rp)
    }

    /** v3.9.20：vivo17 系统升级 APP 公测/内测/尝鲜主查询入口。 */
    private fun sendBetaOrTasteRequest(plaintext: String, domain: Domain): String {
        val jvqParam = encryptToJvq(plaintext)
        val response = httpPost(domain.url("/beta/queryBetaOrTaste.do"), "jvq_param=$jvqParam")
        return if (!response.startsWith("ACw") && !response.startsWith("ACo")) {
            "[Error] $response"
        } else {
            decryptResponse(response)
        }
    }

    /** 提取 queryBetaOrTaste 响应的 data.type：1=公测招募 2=内测/尝鲜更新包；缺失返回 0。 */
    private fun extractBetaOrTasteType(json: String): Int {
        val dataIdx = json.indexOf("\"data\"")
        if (dataIdx < 0) return 0
        val type = extractJsonValue(json.substring(dataIdx), "type")
        return type?.toIntOrNull() ?: 0
    }

    /** type=1 时的公测招募摘要（PublicBetaInfo.data.betaInfo：项目版本/推送计划/预计推送时间）。 */
    private fun buildBetaRecruitHint(json: String): String {
        val projectVersion = extractJsonValue(json, "projectVersion") ?: ""
        val pushPlan = extractJsonValue(json, "pushPlan") ?: ""
        val expectPush = extractJsonValue(json, "expectPushTime")?.toLongOrNull() ?: 0L
        val sb = StringBuilder()
        if (projectVersion.isNotEmpty() && projectVersion != "null") sb.append("版本 ").append(projectVersion)
        if (pushPlan.isNotEmpty() && pushPlan != "null") {
            if (sb.isNotEmpty()) sb.append("，")
            sb.append(pushPlan)
        }
        if (expectPush > 0) {
            if (sb.isNotEmpty()) sb.append("，")
            sb.append("预计推送 ").append(SimpleDateFormat("yyyy-MM-dd").format(Date(expectPush)))
        }
        return sb.toString()
    }

    private fun httpPost(urlString: String, body: String): String {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("User-Agent", "okhttp/4.3.23")
        conn.setRequestProperty("Host", url.host)
        conn.setRequestProperty("Connection", "Keep-Alive")
        conn.setRequestProperty("Accept-Encoding", "gzip")
        conn.doOutput = true

        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

        val code = conn.responseCode
        val is_ = if (code >= 400) conn.errorStream else conn.inputStream
        val inputStream = if ("gzip" == conn.contentEncoding) GZIPInputStream(is_) else is_

        val resp = StringBuilder()
        BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) resp.append(line)
        }
        return resp.toString()
    }

    fun fetchChangelog(url: String): String? {
        if (url.isEmpty()) return null
        return try {
            // H5 页面是 JS 空壳，正文在 data/CN.js（形如 "var x = {...JSON...}"）
            val dataUrl = if (url.contains("/index.html")) changelogDataUrl(url) else url
            Log.d(TAG, "Fetching changelog from: $dataUrl")

            val response = httpGet(dataUrl)
            Log.d(TAG, "Changelog response (${response.length} chars): ${response.take(500)}")
            val trimmed = response.trim()
            when {
                trimmed.startsWith("{") -> parseChangelogJson(trimmed)
                // JS 数据文件：剥离 "var x =" 前缀提取 JSON（对齐 OPPO 更新日志的直接解析）
                trimmed.startsWith("var ") || trimmed.contains("={") || trimmed.contains("= {") ->
                    extractJsonFromJs(trimmed)?.let { parseChangelogJson(it) } ?: parseChangelogText(trimmed)
                trimmed.startsWith("<") -> parseChangelogHtml(trimmed)
                else -> parseChangelogText(trimmed)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Changelog fetch failed: ${e.message}")
            // 数据文件失败时兜底抓 H5 原页直接抽正文
            try {
                val page = httpGet(url)
                if (page.trimStart().startsWith("<")) parseChangelogHtml(page) else null
            } catch (e2: Exception) {
                null
            }
        }
    }

    /** 从 JS 数据文件提取 JSON：找首个 { 到最后一个 } 之间的内容 */
    private fun extractJsonFromJs(js: String): String? {
        val start = js.indexOf('{')
        val end = js.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return js.substring(start, end + 1)
    }

    /** 兜底：非 JSON 非 HTML 的纯文本，按原文返回 */
    private fun parseChangelogText(text: String): String = text.trim()

    private fun httpGet(urlString: String): String {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        val code = conn.responseCode
        val is_ = if (code >= 400) conn.errorStream else conn.inputStream
        val inputStream = if ("gzip" == conn.contentEncoding) GZIPInputStream(is_) else is_
        val resp = StringBuilder()
        BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) resp.append(line)
        }
        return resp.toString()
    }

    private fun parseChangelogJson(jsonText: String): String {
        val data = JSONObject(jsonText)
        val lines = mutableListOf<String>()

        data.optString("headContent", "").takeIf { it.isNotBlank() }?.let { lines.add(it) }

        val body = data.optJSONArray("body") ?: return lines.joinToString("\n")
        for (i in 0 until body.length()) {
            val item = body.optJSONObject(i) ?: continue
            appendChangelogSection(item, lines)
        }
        return lines.joinToString("\n").trim()
    }

    private fun appendChangelogSection(item: JSONObject, lines: MutableList<String>, level: Int = 0) {
        val title = item.optString("title", "").trim()
        if (title.isNotEmpty()) {
            if (lines.isNotEmpty() && lines.last().isNotEmpty()) lines.add("")
            lines.add(title)
        }

        val descContents = item.optJSONArray("descContents")
        if (descContents != null) {
            for (j in 0 until descContents.length()) {
                val entry = descContents.opt(j) ?: continue
                when (entry) {
                    is String -> if (entry.trim().isNotEmpty()) lines.add("  • ${entry.trim()}")
                    is JSONObject -> {
                        val content = entry.optString("content", "").ifEmpty { entry.optString("text", "") }
                        if (content.trim().isNotEmpty()) lines.add("  • ${content.trim()}")
                    }
                }
            }
        }

        val contentArr = item.optJSONArray("content")
        if (contentArr != null && descContents == null) {
            for (j in 0 until contentArr.length()) {
                val v = contentArr.opt(j)?.toString()?.trim()
                if (!v.isNullOrEmpty()) lines.add("  • $v")
            }
        }

        val children = item.optJSONArray("children")
        if (children != null) {
            for (k in 0 until children.length()) {
                val child = children.optJSONObject(k) ?: continue
                appendChangelogSection(child, lines, level + 1)
            }
        }
    }

    private fun parseChangelogHtml(html: String): String {
        // 保留 HTML 格式，只移除 script、style 和 head 标签
        var text = html
        text = text.replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("<head[^>]*>[\\s\\S]*?</head>", RegexOption.IGNORE_CASE), "")
        return text.trim()
    }
    
    /**
     * 递减版本号：17.0.16.0 -> 17.0.15.9
     * 用于全量包查询时自动回退到上一个小版本
     */
    private fun decrementVersion(version: String): String {
        // 匹配格式：17.0.16.0 或 17.0.16.0.W10
        val parts = version.split(".")
        if (parts.size < 4) return version
        
        try {
            val major = parts[0].toInt()
            val minor1 = parts[1].toInt()
            var minor2 = parts[2].toInt()
            var patch = parts[3].substringBefore(".").substringBefore("W").toInt()
            
            // 递减补丁号
            if (patch > 0) {
                patch--
            } else {
                // 补丁号已是0，递减 minor2
                patch = 9
                if (minor2 > 0) {
                    minor2--
                } else {
                    // 已无法继续递减，保持原样
                    return version
                }
            }
            
            // 重新组装版本号，保留后缀（如 .W10）
            val suffix = if (parts[3].contains("W")) {
                "." + parts[3].substringAfter("W", "")
            } else {
                ""
            }
            val newVersion = "$major.$minor1.$minor2.$patch$suffix"
            
            // 如果还有更多部分（如 .V000L1），附加上去
            if (parts.size > 4) {
                return newVersion + "." + parts.subList(4, parts.size).joinToString(".")
            }
            
            return newVersion
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decrement version: $version", e)
            return version
        }
    }
}

private typealias JSONObject = org.json.JSONObject
