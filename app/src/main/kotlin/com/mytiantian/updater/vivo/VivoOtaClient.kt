package com.mytiantian.updater.vivo

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
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
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.ceil

class VivoOtaClient(private val context: Context) {

    companion object {
        private const val TAG = "VivoOtaClient"
        private const val TOKEN_NATIVE = "jnisgmain_v2@com.bbk.updater"

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

    private fun officialRamSize(): Int {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0
        val memory = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)
        val gib = memory.totalMem / 1.0737418E9f
        return when {
            gib <= 2f -> 2
            gib <= 3f -> 3
            gib <= 4f -> 4
            gib <= 6f -> 6
            gib <= 8f -> 8
            gib <= 12f -> 12
            gib <= 16f -> 16
            else -> gib.toInt() + 1
        }
    }

    private fun officialRomSize(): Int {
        val stat = StatFs(Environment.getDataDirectory().path)
        val gib = stat.blockCountLong.toDouble() * stat.blockSizeLong.toDouble() / 1.073741824E9
        return when {
            gib <= 32.0 -> 32
            gib <= 64.0 -> 64
            gib <= 128.0 -> 128
            gib <= 256.0 -> 256
            gib <= 512.0 -> 512
            gib <= 1024.0 -> 1024
            else -> ceil(gib).toInt()
        }
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
        domain: Domain = Domain.CN,
        allowFullFallback: Boolean = true,
        publicModelCode: String = modelSwVer
    ): VivoOtaResult {
        val localModel = readSystemProperty("ro.vivo.product.model")
        val isLocalTarget = localModel.equals(codename, ignoreCase = true)
        val hwVer = if (isLocalTarget) {
            readSystemProperty("ro.vivo.hardware.version").ifBlank { codename + "MA" }
        } else codename + "MA"
        val fullSwVersion = if (swVersion.contains(".W")) "$swVersion.V000L1" else swVersion
        val generatedFullVer = if (swVersion.contains(".W")) "${codename}_A_$swVersion.V000L1" else "${codename}_A_$swVersion"
        val fullVer = if (isLocalTarget) {
            readSystemProperty("ro.vivo.product.version")
                .ifBlank { readSystemProperty("ro.build.version.bbk") }
                .ifBlank { generatedFullVer }
        } else generatedFullVer
        val customize = if (isLocalTarget) readSystemProperty("ro.product.customize.bbk").ifBlank { "N" } else "N"
        val commercialCustomize = if (isLocalTarget) {
            readSystemProperty("ro.product.customize.comercial").takeUnless { it.isBlank() || it == "unknown" || it == "NULL" } ?: customize
        } else customize
        val countryRegion = if (isLocalTarget) {
            readSystemProperty("ro.product.country.region").takeUnless { it.isBlank() || it == "unknown" } ?: "N"
        } else "N"
        val simCountry = if (isLocalTarget) readSystemProperty("gsm.sim.operator.iso-country") else ""
        val networkCountry = if (isLocalTarget) readSystemProperty("gsm.operator.iso-country") else ""
        val userCountryChoice = if (isLocalTarget) readSystemProperty("persist.sys.vivo.product.cust") else ""
        val versionLong = "${codename}_${customize}_${hwVer}_$fullSwVersion"

        val ts = SimpleDateFormat("yy_MM_dd-HH_mm_ss").format(Date())
        val elapsedtime = android.os.SystemClock.elapsedRealtime()
        val isFullInt = if (isFull) 1 else 0
        // 不生成随机 IMEI；优先使用用户输入/本机可读值，否则使用官方客户端固定回退值。
        val effectiveImei = VivoImei.sanitize(imei).ifEmpty {
            if (isLocalTarget) VivoImei.readDeviceSync(context) else ""
        }.ifEmpty { "123456789011111" }
        val emmcId = if (isLocalTarget) readEmmcId() else ""
        val fingerprint = if (isPhone) {
            VivoHardwareFingerprint.generateNormal(context, codename, hwVer, effectiveImei, emmcId)
        } else {
            VivoHardwareFingerprint.Outcome(null, "平板查询不使用手机 VDFP 指纹")
        }

        val p = LinkedHashMap<String, Any>()
        p["vgcNewActiveVer"] = ""
        p["nt"] = if (isLocalTarget) connectionType() else "WIFI"
        // 官方 VersionUtils.getVgcSoftVersion(): ro.vgc.cust.version，调试覆盖为 persist.u.vgc.version。
        // 查询远程目标时无法读取目标机属性，沿用 Android 17 对应的官方协议值作为默认；低版本保留旧默认。
        p["vgcSwVer"] = resolveVgcSwVersion(isLocalTarget, androidVersion)
        p["fullVer"] = fullVer
        p["emmcid"] = emmcId
        p["sm1"] = if (isLocalTarget) systemSetting("sim_type_1") else "null"
        p["sm2"] = if (isLocalTarget) systemSetting("sim_type_2") else "null"
        p["model"] = if (isLocalTarget) localModel else codename
        // public_model 是设备软件型号代码（如 V2547DA），不是 UI 展示的营销名。
        // Dsu 官方 Tracker 与 Android Tracker 均用 modelSwVer 作为 public_model。
        val dModel = if (isLocalTarget) readSystemProperty("ro.vivo.update.model").ifBlank { modelSwVer } else modelSwVer
        if (dModel.isNotBlank()) p["dModel"] = dModel
        p["public_model"] = publicModelCode.ifBlank { modelSwVer.ifBlank { codename } }
        p["hasVgc"] = 1
        p["vgcNewPassiveVer"] = ""
        p["ch"] = userCountryChoice
        p["gn"] = if (isLocalTarget) readSystemProperty("ro.build.gn.support").toIntOrNull() ?: 0 else 0
        p["newActiveVer"] = ""
        p["version"] = versionLong
        p["st2"] = if (isLocalTarget) simInsertElapsed("st2") else 0
        p["cu"] = commercialCustomize
        p["srm2"] = if (isLocalTarget) systemSetting("net_state_class_2").toIntOrNull() ?: 0 else 0
        p["srm1"] = if (isLocalTarget) systemSetting("net_state_class_1").toIntOrNull() ?: 0 else 0
        p["cy"] = countryRegion
        p["sn2"] = if (isLocalTarget) systemSetting("sn2") else "null"
        p["si"] = simCountry
        p["ne"] = networkCountry
        p["sn1"] = if (isLocalTarget) systemSetting("sn1") else "null"
        p["newPassiveVer"] = ""
        p["hwVer"] = hwVer
        p["swVer"] = fullSwVersion
        p["language"] = "zh_CN"
        // UpdatePresenter.checkUpdate() 创建 AsyncTaskC5055b 后不调用 m12901r(true)，
        // 父类默认 f13856b=false/f13865k=false；官方 getUpdateRequestParams 因此发送 isMan=1。
        p["isMan"] = 1
        p["isFull"] = isFullInt
        p["protocalversion"] = "1.0"
        
        // 官方手动检查在非 system-broken 状态下 disDownable=1。
        if (isFull && isPhone && readSystemProperty("ro.virtual_ab.enabled").equals("true", ignoreCase = true)) {
            p["disDownable"] = 1
        }
        
        p["checkTrige"] = "MANUL"
        p["isstlifeover"] = "false"
        p["hwFingerprint"] = fingerprint.value.orEmpty()
        p["logVersionNegotiation"] = "5360"
        p["ram"] = officialRamSize()
        p["rom"] = officialRomSize()
        
        if (isPhone) {
            // 增量包和全量包使用相同的基础参数
            p["vgcCu"] = "V000"
            p["sf"] = if (isLocalTarget) systemSetting("sf").toIntOrNull() ?: 0 else 1
            p["si"] = simCountry
            p["dType"] = "phone"
            val suppliedSn = sn.trim().takeUnless { it.isEmpty() || it == "A0000000000000A" }
                ?: if (isLocalTarget) readSystemProperty("ro.vendor.vivo.serialno") else ""
            p["s_n"] = if (suppliedSn.isNullOrEmpty()) "null" else suppliedSn.takeLast(5)
            p["elapsedtime"] = elapsedtime
            p["st1"] = if (isLocalTarget) simInsertElapsed("st1") else 0
            p["imei"] = effectiveImei
            p["ms"] = 0
            p["mtype"] = if (isLocalTarget) readSystemProperty("ro.vivo.op.entry").ifBlank { "no" } else "no"
            p["radiotype"] = if (isLocalTarget) readSystemProperty("persist.vivo.radio.type.abbr").ifBlank { "L" } else "L"
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
        Log.d(TAG, "OTA 请求已组装（channel=${channel.value}, 参数数=${p.size}）；设备标识已隐藏")

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
            Log.d(TAG, "${channel.value} state 响应 retcode=${extractRetCode(stateJson)}")
        }

        // v3.9.20：镜像 vivo17 系统升级 APP PublicBetaOrTrialCheckTask ——
        // /beta/queryBetaOrTaste.do 是公测/内测/尝鲜的主查询入口：
        //   data.type=1 → 公测招募信息（PublicBetaInfo，无升级包）
        //   data.type=2 → 内测/尝鲜更新包（与普通升级响应同构，retcode/fotaUpdateInfo 含下载信息）
        // 拿到 type=2 的包则直接采用；否则回落到 getTastePk（尝鲜包查询）。
        var betaOrTasteType = 0
        var betaRecruitHint = ""
        var updateResponse: String
        var paramsForResult: Map<String, Any> = p
        if (channel == QueryChannel.TRIAL) {
            // 官方 TrialVersionCheckTask 单独直连 /upgrade/trial/getTastePk；
            // 不先调用 PublicBetaOrTrialCheckTask，也不复用其请求体。
            val trialParams = buildOfficialTrialPackageParams(baseSnapshot, isFull, fingerprint.value.orEmpty())
            paramsForResult = trialParams
            updateResponse = sendTasteRequest(joinParams(trialParams), domain)
            Log.d(TAG, "Official direct trial package query completed: retcode=${extractRetCode(updateResponse)}")
        } else if (isTaste) {
            updateResponse = ""
            var gotPackage = false
            try {
                val botParams = buildBetaOrTasteParams(baseSnapshot, isFull)
                val botResp = sendBetaOrTasteRequest(botParams, domain)
                if (!botResp.startsWith("[Error]")) {
                    betaOrTasteType = extractBetaOrTasteType(botResp)
                    Log.d(TAG, "queryBetaOrTaste type=$betaOrTasteType, retcode=${extractRetCode(botResp)}")
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
                val trialParams = buildOfficialTrialPackageParams(baseSnapshot, isFull, fingerprint.value.orEmpty())
                paramsForResult = trialParams
                updateResponse = sendTasteRequest(joinParams(trialParams), domain)
            }
        } else {
            updateResponse = sendFreshEncryptedRequest(rawParams, domain)
        }
        if (updateResponse.startsWith("[Error]")) {
            throw RuntimeException(updateResponse)
        }

        var fullRequestDiagnostic = ""
        if (isFull && !isTaste && extractRetCode(updateResponse) == 210) {
            fullRequestDiagnostic = "官方主查询参数已包含 logVersionNegotiation=5360、RAM=${p["ram"]}、ROM=${p["rom"]}；" +
                    "不再发送旧 Tracker 非官方兼容参数。\n"
        }

        // 新机型（如 X500）无全量包：isFull=1 返回 retcode=210 时自动降级 isFull=0 查增量包。
        // 降级成功后 redirPost 也会用增量参数集（与实测的 VivoOtaTracker v2 行为一致）。
        // 实测结论：vivo 仅对"基准=最新版"下发同版本全量重刷包（X200 对照验证），
        // 新机型全量包通常晚于增量包上架，此处降级后由 UI 提示用户。
        var fullFallback = false
        var firstFullQueryLog = "" // 记录第一次全量包查询的日志
        Log.d(TAG, "Before fallback check: isFull=$isFull, isTaste=$isTaste, hasPackage=${responseHasPackage(updateResponse)}")
        if (isFull && !isTaste && !responseHasPackage(updateResponse) && allowFullFallback) {
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
            firstLog.append("\n查询参数（设备标识已隐藏）:\n")
            p.forEach { (key, value) ->
                val hidden = key.lowercase() in setOf("imei", "snp", "s_n", "emmcid", "hwfingerprint", "fingerprint")
                firstLog.append("  $key: ${if (hidden) "(已隐藏)" else value}\n")
            }
            firstLog.append("\n")
            
            Log.d(TAG, "isFull=1 got no package (retcode=$firstRetCode, pkName='$firstPkName')")
            
            // 检查服务器是否返回 local_install_allow=1
            val localInstallAllow = extractField(updateResponse, listOf("local_install_allow"))
            firstLog.append("local_install_allow: $localInstallAllow\n")
            firstLog.append("响应正文已隐藏；响应长度=${updateResponse.length}\n")
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
        } else if (isFull && !isTaste && !responseHasPackage(updateResponse) && !allowFullFallback) {
            firstFullQueryLog = "=== 完整包查询未返回安装包 ===\n" +
                    "本次请求 isFull=1；按用户选择保持完整包查询，不自动回退到 isFull=0。\n" +
                    "retCode: ${extractRetCode(updateResponse)}\n" +
                    "响应正文已隐藏；响应长度=${updateResponse.length}\n" + fullRequestDiagnostic
            Log.d(TAG, "isFull=1 got no package; fallback disabled (retcode=${extractRetCode(updateResponse)})")
        } else if (isFull && !isTaste) {
            val retCode = extractRetCode(updateResponse)
            val pkName = extractJsonStr(updateResponse, "pkName\":\"")
            Log.d(TAG, "isFull=1 query succeeded: retcode=$retCode, pkName='$pkName'")
        }
        Log.d(TAG, "After fallback: isFull=${p["isFull"]}, mtype=${p["mtype"]}")

        return parseResult(updateResponse, paramsForResult, channel, domain, fullFallback, betaOrTasteType, betaRecruitHint, firstFullQueryLog, fingerprint.status)
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
        firstFullQueryLog: String = "",
        fingerprintStatus: String = ""
     ): VivoOtaResult {
        
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
        debugLog.append("fullFallback: $fullFallback\n")
        debugLog.append("hwFingerprint 状态: $fingerprintStatus\n")
        debugLog.append("\n")
        
        debugLog.append("=== 查询参数 ===\n")
        debugLog.append("API: ${if (channel == QueryChannel.NORMAL) "/vgc/v2/getVgcAndPatch.do" else "/upgrade/trial/getTastePk"}\n")
        debugLog.append("Domain: ${domain.value}\n")
        debugLog.append("Channel: ${channel.value}\n")
        debugLog.append("isFull: ${queryParams["isFull"]}\n")
        debugLog.append("model: ${queryParams["model"]}\n")
        debugLog.append("swVer: ${queryParams["swVer"]}\n")
        debugLog.append("vgcSwVer: ${queryParams["vgcSwVer"]?.toString() ?: "未发送"}\n")
        debugLog.append("logVersionNegotiation: ${queryParams["logVersionNegotiation"]}\n")
        debugLog.append("ram: ${queryParams["ram"]}\n")
        debugLog.append("rom: ${queryParams["rom"]}\n")
        debugLog.append("dType: ${queryParams["dType"]}\n")
        debugLog.append("isMan: ${queryParams["isMan"]}\n")
        debugLog.append("mtype: ${queryParams["mtype"]}\n")
        debugLog.append("oem: ${queryParams["oem"]}\n")
        debugLog.append("dModel: ${queryParams["dModel"]}\n")
        debugLog.append("public_model: ${queryParams["public_model"]}\n")
        debugLog.append("hwVer: ${queryParams["hwVer"]}\n")
        debugLog.append("version: ${queryParams["version"]}\n")
        debugLog.append("fullVer: ${queryParams["fullVer"]}\n")
        debugLog.append("cu: ${queryParams["cu"]}\n")
        debugLog.append("hasVgc: ${queryParams["hasVgc"]}\n")
        debugLog.append("cy: ${queryParams["cy"]}\n")
        debugLog.append("ch: ${queryParams["ch"]}\n")
        debugLog.append("si: ${queryParams["si"]}\n")
        debugLog.append("ne: ${queryParams["ne"]}\n")
        debugLog.append("vgcCu: ${queryParams["vgcCu"]}\n")
        debugLog.append("sf: ${queryParams["sf"]}\n")
        debugLog.append("ms: ${queryParams["ms"]}\n")
        debugLog.append("radiotype: ${queryParams["radiotype"]}\n")
        if (channel == QueryChannel.TRIAL) {
            debugLog.append("trigger: ${queryParams["trigger"]}\n")
            debugLog.append("isSupportVgcTaste: ${queryParams["isSupportVgcTaste"]}\n")
            debugLog.append("isSupportShowNote: ${queryParams["isSupportShowNote"]}\n")
            debugLog.append("isCdma: ${queryParams["isCdma"]}\n")
        }
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
            val responseMessage = extractField(updateResponse, listOf("message", "msg")).ifEmpty { "无更新" }
            debugLog.append("\n服务端消息：$responseMessage\n")
            debugLog.append("retcode=210 表示这组查询条件当前没有可用更新；这不是加密失败或网络异常。\n")
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
        Log.d(TAG, "服务端 pk 重定向字段: ${if (pkUrl.isNullOrBlank()) "缺失" else "已收到（内容隐藏）"}")
        
        var downloadUrl = ""
        var redirPostSummary = "未请求"

        // 系统升级 APK 的下载器并不直接提交 pk 查询串：它会用 getBaseUrl
        // 基础字段 + pk URL 查询串 + upversion/dlrequest/downloadType/时间戳/nonce 等
        // 参数重新加密后 POST 到 pk URL 对应域名的 /pk/redirPost.do。
        if (pkUrl != null) {
            try {
                val timestamp = extractField(updateResponse, listOf("timeStamp", "timestamp"))
                    .takeUnless { it == "(Not found)" || it.isBlank() } ?: "0"
                val activeFlag = extractField(updateResponse, listOf("isActivePackage", "activePackage"))
                val isActive = activeFlag != "0" && !activeFlag.equals("false", ignoreCase = true)
                val trial = channel != QueryChannel.NORMAL
                val downloadRequest = buildOfficialDownloadParams(
                    queryParams, pkUrl, updateVersion, isActive, trial, timestamp
                )
                val redirRes = requestRedirPost(pkUrl, downloadRequest.encoded)
                val responseJson = JSONObject(redirRes)
                val candidate = responseJson.optString("data", "").replace("\\/", "/").trim()
                val responseCode = responseJson.optInt("retcode", -1)
                redirPostSummary = "HTTP 接口已按官方参数请求，hwFingerprint=${downloadRequest.fingerprintStatus}，retcode=$responseCode，返回地址=${if (candidate.startsWith("http://") || candidate.startsWith("https://")) "有" else "无"}"
                if (candidate.startsWith("http://", ignoreCase = true) || candidate.startsWith("https://", ignoreCase = true)) {
                    downloadUrl = candidate
                }
            } catch (e: Exception) {
                redirPostSummary = "官方 redirPost 取链失败：${e.message ?: e.javaClass.simpleName}"
                Log.w(TAG, "Official redirPost failed: ${e.message}")
            }
        }
        debugLog.append("\n=== 官方 redirPost 取链 ===\n")
        debugLog.append("$redirPostSummary\n")
        if (pkUrl == null) debugLog.append("服务端响应未提供 pk 重定向地址，Dsu 不再伪造 CDN 下载链接。\n")

        debugLog.append("\n=== 最终结果 ===\n")
        debugLog.append("downloadUrl: ${if (downloadUrl.isEmpty()) "(Not found)" else downloadUrl}\n")
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
        // 官方 getEncoderString() 对每个 key/value 都执行 UTF-8 form encoding；
        // public_model 等字段包含空格/中文时，不编码会破坏解密后的参数串。
        return params.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value.toString(), "UTF-8")}"
        }
    }

    private data class DownloadRequestParams(val encoded: String, val fingerprintStatus: String)

    /** Mirrors the supplied system-updater APK's HttpUtils.getDlRequestParams(). */
    private fun buildOfficialDownloadParams(
        queryParams: Map<String, Any>,
        pkUrl: String,
        updateVersion: String,
        isActivePackage: Boolean,
        isTrialVersion: Boolean,
        timeStamp: String
    ): DownloadRequestParams {
        val base = LinkedHashMap(queryParams)
        listOf(
            "isMan", "isFull", "protocalversion", "checkTrige", "isstlifeover", "disDownable",
            "logVersionNegotiation", "ram", "rom", "hwFingerprint", "trigger", "fingerprint",
            "manual", "push", "action", "isSupportVgcTaste", "isSupportShowNote", "isCdma"
        ).forEach(base::remove)
        val baseText = base.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value.toString(), "UTF-8") }"
        }
        val pkQuery = runCatching { URL(pkUrl).query }.getOrNull()
            ?: pkUrl.substringAfter('?', "").substringBefore('#')
        if (pkQuery.isBlank()) throw IllegalArgumentException("服务端 pk 地址缺少查询参数")
        val sb = StringBuilder(baseText)
        if (sb.isNotEmpty()) sb.append('&')
        sb.append(pkQuery)
        fun add(name: String, value: String) {
            if (sb.isNotEmpty()) sb.append('&')
            sb.append(name).append('=').append(URLEncoder.encode(value, "UTF-8"))
        }
        add("upversion", updateVersion.takeUnless { it == "(Not found)" } ?: "")
        add("dlrequest", "0") // the updater marks a user-started download as manual
        add("downloadType", "ota_pacakge")
        add("isPacakgeActive", if (isActivePackage) "1" else "0")
        add("timeToUptouch", "0")
        add("httpsSupport", "1")
        add("isTrialVersion", isTrialVersion.toString())
        add("retry", "0")
        add("timeStamp", timeStamp)
        val nonce = UUID.randomUUID().toString().replace("-", "")
        add("nonce", nonce)
        val model = queryParams["model"]?.toString().orEmpty()
        val isPhone = queryParams["dType"]?.toString() == "phone"
        val localModel = readSystemProperty("ro.vivo.product.model")
        val fingerprint = if (isPhone && localModel.equals(model, ignoreCase = true)) {
            VivoHardwareFingerprint.generateDownload(
                context = context,
                timestamp = timeStamp,
                nonce = nonce,
                imei = queryParams["imei"]?.toString().orEmpty(),
                serial = queryParams["snp"]?.toString().orEmpty(),
                emmcId = queryParams["emmcid"]?.toString().orEmpty(),
                isPhone = isPhone
            )
        } else {
            VivoHardwareFingerprint.Outcome(null, "目标不是当前 Vivo 设备")
        }
        add("hwFingerprint", fingerprint.value.orEmpty())
        return DownloadRequestParams(sb.toString(), fingerprint.status)
    }

    private fun readSystemProperty(key: String): String {
        val reflected = runCatching {
            val type = Class.forName("android.os.SystemProperties")
            type.getMethod("get", String::class.java, String::class.java)
                .invoke(null, key, "") as? String ?: ""
        }.getOrDefault("")
        if (reflected.isNotBlank()) return reflected.trim()
        return runCatching {
            val process = ProcessBuilder("getprop", key).redirectErrorStream(true).start()
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                ""
            } else {
                process.inputStream.bufferedReader().use { it.readText().trim() }
            }
        }.getOrDefault("")
    }

    private fun resolveVgcSwVersion(isLocalTarget: Boolean, androidVersion: Int): String {
        if (isLocalTarget) {
            return readSystemProperty("persist.u.vgc.version")
                .ifBlank { readSystemProperty("ro.vgc.cust.version") }
        }
        return if (androidVersion >= 17) "17.1.1" else "1.1.1"
    }

    private fun systemSetting(key: String): String = try {
        Settings.System.getString(context.contentResolver, key)?.takeIf { it.isNotBlank() } ?: "null"
    } catch (_: Throwable) {
        "null"
    }

    private fun connectionType(): String = try {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val network = manager?.activeNetworkInfo
        if (network == null || !network.isConnected) "unknown"
        else if (network.type == android.net.ConnectivityManager.TYPE_WIFI) network.typeName
        else if (network.type == android.net.ConnectivityManager.TYPE_MOBILE) "${network.extraInfo}_${network.subtypeName}"
        else "unknown"
    } catch (_: Throwable) {
        "unknown"
    }

    /** Mirrors VersionUtils.getSim1/2InsertTime: elapsedRealtime minus Settings.System st1/st2. */
    private fun simInsertElapsed(key: String): Long = try {
        val recorded = Settings.System.getLong(context.contentResolver, key, 0L)
        if (recorded > 0L) (android.os.SystemClock.elapsedRealtime() - recorded).coerceAtLeast(0L) else 0L
    } catch (_: Throwable) {
        0L
    }

    private fun readEmmcId(): String {
        try {
            val type = Class.forName("android.util.FtDeviceInfo")
            for (name in listOf("getEmmcId", "getUFSId", "getEmmcID")) {
                val value = runCatching {
                    type.getDeclaredMethod(name).apply { isAccessible = true }.invoke(null) as? String
                }.getOrNull()
                if (!value.isNullOrBlank() && value != "unknown") return value.trim()
            }
        } catch (_: Throwable) {
        }
        for (path in listOf("/sys/ufs/ufsid", "/sys/block/mmcblk0/device/cid")) {
            val value = runCatching { java.io.File(path).readText().trim() }.getOrNull()
            if (!value.isNullOrBlank()) return value
        }
        return ""
    }

    /** 兜底下载地址：下载域名 + /upgrade/oem/files/ + 包名 + sign 和 t 参数。包名缺失/为 "(Not found)" 时返回空串。 */
    private fun extractPkUrl(json: String): String? {
        val url = extractJsonStr(json, "pk\":\"")
        return if (url == "(Not found)") null else url.replace("\\/", "/")
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
        return decodeApiResponse(response)
    }

    private fun requestRedirPost(pkUrl: String, params: String): String {
        val pk = URL(pkUrl)
        val marker = pk.path.indexOf("/pk/redir")
        require(marker >= 0) { "服务端 pk 地址不含 /pk/redir 路径" }
        val endpoint = "${pk.protocol}://${pk.authority}${pk.path.substring(0, marker)}/pk/redirPost.do"
        val jvqParam = encryptToJvq(params)
        val response = httpPost(endpoint, "jvq_param=$jvqParam")
        return decodeApiResponse(response)
    }

    // 尝鲜/公测/内测通道的尝鲜包查询（getTastePk），与 PC 版 sendTasteRequest 对应
    private fun sendTasteRequest(plaintext: String, domain: Domain): String {
        val jvqParam = encryptToJvq(plaintext)
        val response = httpPost(domain.url("/upgrade/trial/getTastePk"), "jvq_param=$jvqParam")
        return decodeApiResponse(response)
    }

    // 公测报名状态查询（beta/query）
    private fun sendBetaRequest(params: String, domain: Domain): String {
        val jvqParam = encryptToJvq(params)
        val response = httpPost(domain.url("/beta/query"), "jvq_param=$jvqParam")
        return decodeApiResponse(response)
    }

    // 内测报名状态查询（alpha/getAlphaState）
    private fun sendAlphaRequest(params: String, domain: Domain): String {
        val jvqParam = encryptToJvq(params)
        val response = httpPost(domain.url("/alpha/getAlphaState"), "jvq_param=$jvqParam")
        return decodeApiResponse(response)
    }

    /** Mirrors the official TrialVersionCheckTask request body for /upgrade/trial/getTastePk. */
    private fun buildOfficialTrialPackageParams(
        baseParams: Map<String, Any>,
        isFull: Boolean,
        hwFingerprint: String
    ): LinkedHashMap<String, Any> {
        val params = LinkedHashMap<String, Any>(baseParams)
        params.remove("isMan")
        params.remove("protocalversion")
        params.remove("checkTrige")
        params.remove("isstlifeover")
        params.remove("disDownable")
        params.remove("fingerprint")
        // 已验证的远程设备尝鲜请求不发送 VGC 软件版本回退值；这与普通 OTA 分开处理。
        params.remove("vgcSwVer")
        params.remove("logVersionNegotiation")
        params.remove("ram")
        params.remove("rom")
        params["trigger"] = "first"
        params["hwFingerprint"] = hwFingerprint
        params["isFull"] = if (isFull) 1 else 0
        params["isSupportVgcTaste"] = params["hasVgc"] ?: 1
        params["isSupportShowNote"] = 1
        params["isCdma"] = 0
        return params
    }

    /** Mirrors the official PublicBetaOrTrialCheckTask request for /beta/queryBetaOrTaste.do. */
    private fun buildBetaOrTasteParams(
        baseParams: Map<String, Any>,
        isFull: Boolean
    ): String {
        val rp = LinkedHashMap<String, Any>(baseParams)
        rp.remove("isMan")
        rp.remove("protocalversion")
        rp.remove("checkTrige")
        rp.remove("isstlifeover")
        rp.remove("vgcSwVer")
        rp.remove("disDownable")
        rp["trigger"] = "first"
        rp["hwFingerprint"] = baseParams["hwFingerprint"] ?: ""
        rp["manual"] = 1
        rp["push"] = 0
        rp["logVersionNegotiation"] = 5360
        rp["ram"] = officialRamSize()
        rp["rom"] = officialRomSize()
        rp["romVer"] = readSystemProperty("ro.vivo.os.build.display.id")
        rp["action"] = "all"
        rp["isFull"] = if (isFull) 1 else 0
        rp["isSupportVgcTaste"] = baseParams["hasVgc"] ?: 1
        rp["isSupportShowNote"] = 1
        rp["isCdma"] = if (readSystemProperty("persist.radio.vivo.iscdma").equals("true", ignoreCase = true)) 1 else 0
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
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 6000
        conn.readTimeout = 20000
        conn.setRequestProperty("Content-Type", "text/plain")
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Connection", "close")
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

    /** 官方接口的 210 无更新结果可能以明文 JSON 返回，不应被误判为加密失败。 */
    private fun decodeApiResponse(response: String): String {
        val trimmed = response.trim()
        if (trimmed.startsWith("ACw") || trimmed.startsWith("ACo")) return decryptResponse(trimmed)
        val json = runCatching { JSONObject(trimmed) }.getOrNull()
        return if (json?.has("retcode") == true) trimmed else "[Error] $response"
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
        val conn = URL(urlString).openConnection() as HttpURLConnection
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
    
}

private typealias JSONObject = org.json.JSONObject
