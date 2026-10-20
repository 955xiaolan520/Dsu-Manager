package com.mytiantian.updater.vivo

import android.content.Context
import com.mytiantian.updater.crypto.FakeVivoContext
import com.vivo.seckeysdk.utils.SDKCipherNative
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.TreeMap

/** Uses the Vivo System Update APK's VDFP request shape; never fabricates a fingerprint. */
internal object VivoHardwareFingerprint {
    private const val TARGET_PACKAGE = "com.bbk.updater"
    private const val SECURITY_KEY_UTILS = "com.vivo.services.cipher.utils.SecurityKeyUtils"
    private const val VDFP_TOKEN = "IBkgIP8PAAgAAAAAVQ2DoXJB_jfvXfvucHppveENtBpK5gcKotYxl6p8JSuKO478LZSUXxzfnD4UyzrGQHd3DQdNvtrsxSl2jsiZV29eHisERdXEfFR7EKwdXWy1le0khrF-4E7G06gzpppxFQQz8dA6X3A1AlRu7ELRiaF4XQubsDJu8WOhNGaqGpK0RepcyVbjfwy-7V-sknKcjQ5jEDh7gxKXvaF6tK5-G8Q0SRek4LGSae4bgSLvG4TrzYNFe-bFOC0rw2Pw_Gxy63ypx18sLgx21JZnNZ5sbI2RGwiVeKRM6vuI401OAw6w8CvsYqgyOYmWZhEA30_2vvZzNzfNHjmxIWF_CaBUkg=="

    data class Outcome(val value: String?, val status: String)

    fun generateNormal(
        context: Context,
        model: String,
        hardwareVersion: String,
        imei: String,
        emmcId: String
    ): Outcome {
        val appInfo = TreeMap<String, String>()
        appInfo["model"] = model
        appInfo["hwVer"] = hardwareVersion
        appInfo["imei"] = imei
        appInfo["emmcid"] = emmcId
        addAppTime(appInfo)
        return invokeVdfp(context, appInfo)
    }

    fun generateDownload(
        context: Context,
        timestamp: String,
        nonce: String,
        imei: String,
        serial: String,
        emmcId: String,
        isPhone: Boolean
    ): Outcome {
        val appInfo = TreeMap<String, String>()
        appInfo["timeStamp"] = timestamp
        appInfo["nonce"] = nonce
        appInfo[if (isPhone) "imei" else "snp"] = if (isPhone) imei else serial
        appInfo["emmcid"] = emmcId
        addAppTime(appInfo)
        return invokeVdfp(context, appInfo)
    }

    private fun addAppTime(appInfo: TreeMap<String, String>) {
        val timeFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        appInfo["app_time"] = timeFormat.format(Date())
    }

    private fun invokeVdfp(context: Context, appInfo: TreeMap<String, String>): Outcome {
        return try {
            val appHash = sha256Upper(appInfo.values.joinToString(separator = ""))
            val fakeContext = FakeVivoContext(context, TARGET_PACKAGE, SDKCipherNative.getCertBytes())
            val sdkClass = Class.forName(SECURITY_KEY_UTILS)
            val sdk = sdkClass.getMethod("getInstance", Context::class.java).invoke(null, fakeContext)
                ?: return Outcome(null, "SecurityKeyUtils 未提供实例")
            val invoke = sdkClass.getMethod("invokeCmd", ByteArray::class.java)

            // Official VDFP adapter initializes the hardware backend with hash_null first.
            val initResult = invoke.invoke(sdk, frameRequest(makePlatformRequest(fakeContext.packageName, "hash_null"))) as? ByteArray
                ?: return Outcome(null, "SecurityKeyUtils 初始化未返回数据")
            val initCode = parsePlatformResult(initResult)?.first
                ?: return Outcome(null, "SecurityKeyUtils 初始化响应格式无效")
            if (initCode != 0) return Outcome(null, "SecurityKeyUtils 初始化状态 $initCode")

            val raw = invoke.invoke(sdk, frameRequest(makePlatformRequest(fakeContext.packageName, appHash))) as? ByteArray
                ?: return Outcome(null, "SecurityKeyUtils 未返回指纹数据")
            val platformResult = parsePlatformResult(raw)
                ?: return Outcome(null, "SecurityKeyUtils 返回的数据格式无效")
            if (platformResult.first != 0) return Outcome(null, "SecurityKeyUtils 返回状态 ${platformResult.first}")

            val root = JSONObject(platformResult.second.toString(Charsets.UTF_8))
            val keyInfo = root.optJSONObject("key_info")
                ?: return Outcome(null, "VDFP 响应缺少 key_info")
            keyInfo.put("seq_no", sequenceNumber())
            root.put("key_info", keyInfo)
            root.put("app_info", JSONObject().apply {
                appInfo.forEach { (key, value) -> put(key, value) }
            })
            Outcome(root.toString(2), "已通过 SecurityKeyUtils 生成官方 hwFingerprint")
        } catch (e: ClassNotFoundException) {
            Outcome(null, "设备未提供 Vivo SecurityKeyUtils 服务")
        } catch (e: Throwable) {
            // Never log device identifiers, VDFP payloads, tokens, or generated fingerprints.
            Outcome(null, "官方 VDFP 指纹生成失败（${e.javaClass.simpleName}）")
        }
    }

    private fun makePlatformRequest(packageName: String, appHash: String): ByteArray =
        JSONObject()
            .put("pacakage_name", packageName)
            .put("app_hash", appHash)
            .put("token", VDFP_TOKEN)
            .put("time", System.currentTimeMillis().toString())
            .toString()
            .toByteArray(Charsets.UTF_8)

    private fun frameRequest(json: ByteArray): ByteArray {
        require(json.size <= 4096) { "VDFP request too large" }
        return ByteBuffer.allocate(4224).apply {
            putInt(8193)
            val padded = ByteArray(4096)
            System.arraycopy(json, 0, padded, 0, json.size)
            putInt(json.size)
            put(padded)
        }.array()
    }

    private fun parsePlatformResult(raw: ByteArray): Pair<Int, ByteArray>? {
        if (raw.size < 24) return null
        val input = ByteBuffer.wrap(raw)
        val code = input.int
        val dataLength = input.int
        if (dataLength < 0 || dataLength > input.remaining() - 16) return null
        val data = ByteArray(dataLength)
        input.get(data)
        input.int // keyVersion
        val uniqueLength = input.int
        if (uniqueLength < 0 || uniqueLength > input.remaining() - 8) return null
        input.position(input.position() + uniqueLength)
        val hashLength = input.int
        if (hashLength < 0 || hashLength > input.remaining() - 4) return null
        input.position(input.position() + hashLength)
        input.int // needUpdateKey
        return code to data
    }

    private fun sha256Upper(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(Locale.US, it.toInt() and 0xff) }

    /** Matches VDFP AbstractC4134e.m9157c(32): millis + underscore + uppercase random hex. */
    private fun sequenceNumber(): String {
        val prefix = "${System.currentTimeMillis()}_"
        val bytes = ByteArray(9).also { SecureRandom().nextBytes(it) }
        val suffix = bytes.joinToString("") { "%02X".format(Locale.US, it.toInt() and 0xff) }
        return (prefix + suffix).take(32)
    }
}
