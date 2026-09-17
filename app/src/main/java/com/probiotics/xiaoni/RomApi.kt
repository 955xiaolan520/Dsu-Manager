package com.probiotics.xiaoni

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * ROM 版本信息
 */
data class RomVersion(
    val version: String,       // 版本号，如 OS1.0.7.0.TJBCNXM
    val branchName: String,    // 分支名称，如 正式版/Beta
    val region: String,        // 区域：cn/global/eea等
    val androidVersion: String,
    val releaseDate: String,
    val securityPatch: String,
    val recoveryFile: String? = null,  // Recovery 包文件名
    val fastbootFile: String? = null,  // Fastboot 包文件名
    val md5: String = "",
)

/**
 * 设备信息
 */
data class RomDevice(
    val name: String,       // 设备中文名
    val nameEn: String,     // 设备英文名
    val codename: String,   // 设备代号
    val brand: String,      // 品牌：Xiaomi/Redmi/POCO
    val series: String,     // 系列
    val type: String,       // 类型：phone/tablet/watch
)

/**
 * ROM 下载节点
 */
enum class DownloadNode(val displayName: String, val baseUrl: String) {
    BIG_OTA("BigOTA", "https://bigota.d.miui.com/"),
    HUGE_OTA("HugeOTA", "https://hugeota.d.miui.com/"),
    CDN_ORG("CDN.ORG", "https://cdnorg.d.miui.com/"),
    ALIYUN("阿里云", "https://bkt-sgp-miui-ota-update-alisgp.oss-ap-southeast-1.aliyuncs.com/"),
    BN("BN", "https://bigota.d.miui.com/"),
}

object RomApi {

    private const val USER_AGENT = "XiaoNi-ROM/1.0"

    // 三个 ROM 数据源
    private const val FANS_DEVICE_URL = "https://data.hyperos.fans/devices/"
    private const val DEVICE_URL = "https://raw.githubusercontent.com/HegeKen/HyperData/main/devices/"
    private const val DEVICE_CDN_URL = "https://cdn.jsdelivr.net/gh/HegeKen/HyperData@main/devices/"

    // 设备列表数据源
    private const val DEVICES_JSON_URL = "https://raw.githubusercontent.com/HegeKen/HyperData/main/devices.json"

    /**
     * 获取设备列表（仅从在线获取）
     */
    suspend fun fetchDeviceList(): List<RomDevice> = withContext(Dispatchers.IO) {
        try {
            val json = httpGet(DEVICES_JSON_URL, timeoutMs = 15000)
                ?: throw Exception("无法获取设备列表")
            parseDevicesJson(json)
        } catch (e: Exception) {
            throw Exception("获取设备列表失败: ${e.message}")
        }
    }

    /**
     * 同步获取设备列表（供 Java 调用）
     */
    fun fetchDeviceListBlocking(): List<RomDevice> {
        return runBlocking {
            fetchDeviceList()
        }
    }

    /**
     * 查询设备 ROM 版本（并发请求三个源，取第一个可用的）
     */
    suspend fun fetchDeviceVersions(codename: String): List<RomVersion> = coroutineScope {
        val fans = async { queryHyperData(FANS_DEVICE_URL + codename + ".json") }
        val raw = async { queryHyperData(DEVICE_URL + codename + ".json") }
        val cdn = async { queryHyperData(DEVICE_CDN_URL + codename + ".json") }

        val results = listOf(fans, raw, cdn).awaitAll()
        val firstValid = results.firstOrNull { it != null && hasUsableBranches(it) }

        if (firstValid != null) {
            parseVersionsFromBranches(firstValid)
        } else {
            // 三个源都失败了，尝试合并所有结果
            val merged = mergeResults(results.filterNotNull())
            parseVersionsFromBranches(merged)
        }
    }

    /**
     * 同步查询设备 ROM 版本（供 Java 调用）
     */
    fun fetchDeviceVersionsBlocking(codename: String): List<RomVersion> {
        return runBlocking {
            fetchDeviceVersions(codename)
        }
    }

    /**
     * 生成下载 URL
     */
    fun getDownloadUrl(filename: String, version: String, node: DownloadNode = DownloadNode.ALIYUN): String {
        if (filename.startsWith("http://") || filename.startsWith("https://")) {
            return filename
        }
        return node.baseUrl + version + "/" + filename
    }

    // ========================== 私有方法 ==========================

    private fun httpGet(urlString: String, timeoutMs: Int = 10000): String? {
        return try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connect()

            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun queryHyperData(url: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val json = httpGet(url, timeoutMs = 15000) ?: return@withContext null
            JSONObject(json)
        } catch (e: Exception) {
            null
        }
    }

    private fun hasUsableBranches(data: JSONObject): Boolean {
        val branches = data.optJSONObject("branches") ?: return false
        return branches.keys().asSequence().any { key ->
            val branch = branches.optJSONObject(key)
            branch != null && branch.length() > 0
        }
    }

    private fun mergeResults(results: List<JSONObject>): JSONObject {
        val merged = JSONObject()
        val branchesMap = mutableMapOf<String, JSONObject>()

        for (result in results) {
            val branches = result.optJSONObject("branches") ?: continue
            for (key in branches.keys()) {
                val branch = branches.optJSONObject(key) ?: continue
                if (!branchesMap.containsKey(key) || branch.length() > branchesMap[key]!!.length()) {
                    branchesMap[key] = branch
                }
            }
        }

        if (branchesMap.isNotEmpty()) {
            val branchesObj = JSONObject()
            branchesMap.forEach { (key, value) -> branchesObj.put(key, value) }
            merged.put("branches", branchesObj)
        }

        return merged
    }

    private fun parseVersionsFromBranches(data: JSONObject): List<RomVersion> {
        val versions = mutableListOf<RomVersion>()
        val branches = data.optJSONObject("branches") ?: return emptyList()

        for (branchKey in branches.keys()) {
            val branch = branches.optJSONObject(branchKey) ?: continue
            val branchName = branchKey

            for (versionKey in branch.keys()) {
                val versionData = branch.optJSONObject(versionKey) ?: continue

                versions.add(
                    RomVersion(
                        version = versionKey,
                        branchName = branchName,
                        region = versionData.optString("region", ""),
                        androidVersion = versionData.optString("android", ""),
                        releaseDate = versionData.optString("date", ""),
                        securityPatch = versionData.optString("securityPatch", ""),
                        recoveryFile = versionData.optString("recovery", null),
                        fastbootFile = versionData.optString("fastboot", null),
                        md5 = versionData.optString("md5", ""),
                    )
                )
            }
        }

        // 按发布日期降序
        return versions.sortedByDescending { it.releaseDate }
    }

    private fun parseDevicesJson(json: String): List<RomDevice> {
        val devices = mutableListOf<RomDevice>()
        try {
            val root = JSONObject(json)
            val keys = root.keys()
            while (keys.hasNext()) {
                val groupKey = keys.next()
                val group = root.optJSONObject(groupKey) ?: continue
                val brand = group.optString("brand", groupKey)
                val devicesArray = group.optJSONArray("devices") ?: continue

                for (i in 0 until devicesArray.length()) {
                    val dev = devicesArray.optJSONObject(i) ?: continue
                    val code = dev.optString("code", "")
                    if (code.isBlank()) continue

                    val nameObj = dev.optJSONObject("name")
                    val nameZh = nameObj?.optString("zh", code) ?: code
                    val nameEn = nameObj?.optString("en", code) ?: code

                    val seriesObj = dev.optJSONObject("series")
                    val seriesZh = seriesObj?.optString("zh", "") ?: ""

                    devices.add(
                        RomDevice(
                            name = nameZh,
                            nameEn = nameEn,
                            codename = code,
                            brand = brand,
                            series = seriesZh,
                            type = dev.optString("type", "phone"),
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return devices
    }

    /**
     * 获取当前设备型号
     */
    fun getCurrentDeviceModel(): String {
        return android.os.Build.MODEL ?: ""
    }
}
