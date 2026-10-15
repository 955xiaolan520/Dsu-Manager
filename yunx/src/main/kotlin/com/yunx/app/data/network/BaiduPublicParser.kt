/*
 * Native adaptation of the public Baidu parser flow documented in z7786/wenxi.
 * The share URL, extraction code, and manually maintained parser password are
 * sent to mf.dg22.top; no Baidu account cookie or authorization header is sent.
 */
package com.yunx.app.data.network

import android.net.Uri
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * 百度公益解析接口适配器。只解析单个文件，最大接受 10 GiB 以下文件。
 * 解析口令由用户从官方页面复制后保存在应用私有设置中；不再把 Yuque 的 UC 命令
 * 或过期 Gitee 备用值误当作百度解析口令，也不会对已拒绝口令做无效重试。
 */
class BaiduPublicParser(
    private val passwordProvider: () -> String = { "" },
    private val clientProvider: () -> okhttp3.OkHttpClient = { HttpClients.apiClient() }
) {
    suspend fun downloadLink(surl: String, pwd: String, file: ShareFile): DownloadLink =
        withContext(Dispatchers.IO) {
            require(file.fsize <= 0L || file.fsize < MAX_FILE_BYTES) {
                "百度免登录公益解析仅支持小于 10 GiB 的单个文件"
            }
            require(!file.isdir) { "请选择单个文件，暂不支持用免登录公益解析下载文件夹" }

            val password = currentPassword()
            val safeSurl = surl.removePrefix("/s/").trim()
            require(safeSurl.matches(Regex("1?[A-Za-z0-9_-]{1,80}"))) { "百度分享链接格式无效" }
            val apiSurl = if (safeSurl.startsWith('1')) safeSurl else "1$safeSurl"
            val parent = file.pdirFid.ifBlank { "/" }.let { if (it.startsWith('/')) it else "/$it" }

            val response = post(
                "get_file_list",
                JSONObject()
                    .put("url", "https://pan.baidu.com/s/$apiSurl")
                    .put("surl", apiSurl)
                    .put("pwd", pwd)
                    .put("dir", parent),
                password
            )
            rejectIfPasswordInvalid(response)
            requireApiSuccess(response, "百度公益解析文件列表失败")

            val data = response.optJSONObject("data") ?: throw IOException("百度公益解析未返回文件信息")
            val entries = data.optJSONArray("list") ?: throw IOException("百度公益解析未返回文件列表")
            val entry = (0 until entries.length()).asSequence()
                .mapNotNull { entries.optJSONObject(it) }
                .firstOrNull { it.optString("fs_id") == file.fid }
                ?: throw IOException("公益解析返回的文件列表与当前选择不一致，请刷新后重试")
            val expectedSize = entry.optLong("size", file.fsize)
            require(expectedSize > 0L && expectedSize < MAX_FILE_BYTES) {
                "百度免登录公益解析仅支持小于 10 GiB 的单个文件"
            }

            val shareId = data.optString("shareid")
            val uk = data.optString("uk")
            val randsk = data.optString("randsk")
            require(shareId.isNotBlank() && uk.isNotBlank()) { "百度公益解析缺少分享信息" }
            val id = file.fid.toLongOrNull() ?: throw IOException("百度文件标识无效")
            val links = post(
                "get_download_links",
                JSONObject()
                    .put("randsk", randsk)
                    .put("uk", uk)
                    .put("shareid", shareId)
                    .put("fs_id", JSONArray().put(id))
                    .put("surl", apiSurl)
                    .put("dir", parent)
                    .put("pwd", pwd)
                    .put("token", "guest")
                    .put("download_folder", 0),
                password
            )
            rejectIfPasswordInvalid(links)
            requireApiSuccess(links, "百度公益下载链接获取失败")

            val returned = links.optJSONArray("data")?.let { array ->
                (0 until array.length()).asSequence().mapNotNull { array.optJSONObject(it) }
                    .firstOrNull { it.optString("fs_id") == file.fid }
            } ?: throw IOException("公益解析未返回所选文件的下载链接")
            val urls = returned.optJSONArray("urls")
            val url = urls?.let { array ->
                (0 until array.length()).asSequence()
                    .mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
                    .firstOrNull(::isTrustedDownloadUrl)
            } ?: throw IOException("公益解析未返回百度有效下载地址")
            val ua = returned.optString("ua").ifBlank { returned.optString("user_agent") }
                .ifBlank { FALLBACK_UA }
            require(ua.length <= 1024 && ua.none { it.code < 0x20 || it.code == 0x7f }) {
                "公益解析返回的下载参数无效"
            }
            DownloadLink(
                fid = file.fid,
                filename = file.fname,
                downloadUrl = url,
                size = expectedSize,
                headers = mapOf("User-Agent" to ua, "Accept-Encoding" to "identity")
            )
        }

    private fun currentPassword(): String {
        val value = passwordProvider().trim()
        if (value.isBlank()) {
            throw IOException("请先填写今日百度公益解析口令；口令只保存在本机，每日需手动更新")
        }
        if (value.length > MAX_PASSWORD_LENGTH || value.any { it.code < 0x20 || it.code == 0x7f }) {
            throw IOException("百度公益解析口令格式无效，请重新复制后填写")
        }
        return value
    }

    private fun rejectIfPasswordInvalid(response: JSONObject) {
        if (response.optInt("code") == PASSWORD_REJECTED_CODE) {
            throw IOException("百度公益解析口令错误或已过期；请从官方口令页面复制今日口令后重试")
        }
    }

    private suspend fun post(operation: String, body: JSONObject, password: String): JSONObject =
        withContext(Dispatchers.IO) {
            val payload = JSONObject(body.toString()).put("parse_password", password)
            val request = Request.Builder()
                .url("$PARSER_SITE/api/v1/user/parse/$operation")
                .header("User-Agent", REQUEST_UA)
                .header("Accept", "application/json")
                .header("Origin", PARSER_SITE)
                .header("Referer", "$PARSER_SITE/user/parse")
                .header("Content-Type", "application/json;charset=utf-8")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            clientProvider().newCall(request).execute().use { result ->
                if (result.request.url.scheme != "https" || result.request.url.host != PARSER_HOST) {
                    throw IOException("百度公益解析服务重定向到了非授权域名")
                }
                val text = readBounded(result, MAX_RESPONSE_BYTES)
                val parsed = runCatching { JSONObject(text) }.getOrNull()
                    ?: throw IOException("百度公益解析响应格式异常（HTTP ${result.code}）")
                if (!result.isSuccessful && parsed.optInt("code") != PASSWORD_REJECTED_CODE) {
                    throw IOException("百度公益解析服务暂不可用（HTTP ${result.code}）")
                }
                parsed
            }
        }

    private fun requireApiSuccess(response: JSONObject, fallback: String) {
        if (response.optInt("code") != SUCCESS_CODE) {
            val message = response.optString("message").take(140).ifBlank { fallback }
            throw IOException("$fallback：$message")
        }
    }

    private fun readBounded(response: okhttp3.Response, maxBytes: Int): String {
        val body = response.body ?: throw IOException("服务响应为空")
        if (body.contentLength() > maxBytes) throw IOException("服务响应超过大小限制")
        val bytes = body.byteStream().use { it.readNBytes(maxBytes + 1) }
        if (bytes.size > maxBytes) throw IOException("服务响应超过大小限制")
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun isTrustedDownloadUrl(value: String): Boolean = runCatching {
        val uri = Uri.parse(value)
        uri.scheme in setOf("http", "https") && uri.userInfo.isNullOrEmpty() && uri.fragment.isNullOrEmpty() &&
            TRUSTED_DOWNLOAD_DOMAINS.any { uri.host == it || uri.host?.endsWith(".$it") == true }
    }.getOrDefault(false)

    companion object {
        private const val PARSER_SITE = "https://mf.dg22.top"
        private const val PARSER_HOST = "mf.dg22.top"
        private const val REQUEST_UA = "Dart/3.13 (dart:io)"
        private const val FALLBACK_UA = "Mozilla/5.0 (Linux; Android 15) Chrome/134.0 Mobile"
        private const val SUCCESS_CODE = 200
        private const val PASSWORD_REJECTED_CODE = 20016
        private const val MAX_FILE_BYTES = 10L * 1024 * 1024 * 1024
        private const val MAX_PASSWORD_LENGTH = 512
        private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
        private val JSON_MEDIA = "application/json;charset=utf-8".toMediaType()
        private val TRUSTED_DOWNLOAD_DOMAINS = listOf("baidu.com", "baidupcs.com", "baidubce.com")
    }
}
