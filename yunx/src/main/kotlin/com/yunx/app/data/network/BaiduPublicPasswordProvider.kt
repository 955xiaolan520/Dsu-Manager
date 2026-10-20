package com.yunx.app.data.network

import com.yunx.app.data.prefs.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Daily anonymous password source adapted from wenxi's public Gitee fallback.
 * The upstream UC command source requires a private signing key; this fallback
 * needs no Baidu/UC account and is refreshed automatically every Beijing half-hour.
 */
class BaiduPublicPasswordProvider(
    private val settings: SettingsRepository,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    private val mutex = Mutex()

    suspend fun get(forceRefresh: Boolean = false): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            settings.clearLegacyBaiduPublicParsePassword()
            val window = beijingWindowKey()
            if (!forceRefresh) {
                settings.cachedBaiduPublicPassword(window)?.let { return@withContext it }
            }
            val value = fetchCurrentPassword()
            settings.cacheBaiduPublicPassword(window, value)
            value
        }
    }

    private fun fetchCurrentPassword(): String {
        val url = "$BACKUP_URL?_t=${System.currentTimeMillis()}"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/plain, application/json;q=0.9")
            .header("Cache-Control", "no-cache")
            .header("Accept-Encoding", "identity")
            .header("User-Agent", "Dsu-Manager/3.66")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val host = response.request.url.host.lowercase(Locale.ROOT)
            require(host == "gitee.com" || host == "giteeusercontent.com" || host.endsWith(".giteeusercontent.com")) {
                "百度公益口令源跳转到了未授权域名"
            }
            if (!response.isSuccessful) throw IOException("百度公益口令源暂不可用（HTTP ${response.code}）")
            val body = response.body ?: throw IOException("百度公益口令源响应为空")
            if (body.contentLength() > MAX_SOURCE_BYTES) throw IOException("百度公益口令源文件超过大小限制")
            val bytes = body.byteStream().use { it.readNBytes(MAX_SOURCE_BYTES + 1) }
            if (bytes.size > MAX_SOURCE_BYTES) throw IOException("百度公益口令源文件超过大小限制")
            val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
            val firstLine = text.split(Regex("\\r\\n|\\n|\\r"), limit = 2).firstOrNull().orEmpty()
            val value = extractHan(firstLine)
            if (value.isBlank() || value.length > MAX_PASSWORD_LENGTH || value.length != firstLine.length) {
                throw IOException("百度公益口令源格式异常")
            }
            return value
        }
    }

    private fun extractHan(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                appendCodePoint(codePoint)
            }
            index += Character.charCount(codePoint)
        }
    }

    private fun beijingWindowKey(): String {
        val now = Date()
        val hour = SimpleDateFormat("yyyyMMddHH", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }.format(now)
        val minute = SimpleDateFormat("mm", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }.format(now).toIntOrNull() ?: 0
        return hour + if (minute < 30) "00" else "30"
    }

    companion object {
        private const val BACKUP_URL = "https://gitee.com/dzys/cs/raw/master/ls.json"
        private const val MAX_SOURCE_BYTES = 4096
        private const val MAX_PASSWORD_LENGTH = 4096
    }
}
