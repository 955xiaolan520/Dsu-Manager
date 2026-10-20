/*
 * YunX (云析) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 CYQawa
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License v3 or later.
 */
package com.yunx.app.data.gopeed

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

const val MAX_TORRENT_BYTES = 4 * 1024 * 1024

data class BtTorrentFile(val index: Int, val path: String, val size: Long)

data class BtTorrentResource(
    val requestUrl: String,
    val name: String,
    val files: List<BtTorrentFile>,
    val totalSize: Long
)

/** Magnet / .torrent adapter. Torrent files are parsed locally; only a magnet URI is passed to Gopeed. */
object TorrentResolver {
    private val http = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Resolves a magnet through Gopeed, or parses a local/remote .torrent file without account data. */
    suspend fun resolve(context: Context, input: String): BtTorrentResource = withContext(Dispatchers.IO) {
        val extracted = extractBtInput(input)
        if (extracted.startsWith("magnet:", ignoreCase = true)) {
            require(GopeedEngine.isInstalled(context)) { "请先在「设置 → 下载引擎」导入 Gopeed 内核，才能解析磁力链接" }
            GopeedEngine.ensureRunning(context, GopeedEngine.resolveDownloadDir(context))
            val resolved = GopeedEngine.resolveTorrent(extracted)
            val resource = resolved.optJSONObject("res")
                ?: resolved.optJSONObject("resource")
                ?: throw IllegalStateException("Gopeed 没有返回种子元数据")
            val filesJson = resource.optJSONArray("files") ?: throw IllegalStateException("磁力链接没有返回文件列表")
            val files = (0 until filesJson.length()).mapNotNull { index ->
                val item = filesJson.optJSONObject(index) ?: return@mapNotNull null
                val path = item.optString("path").trim('/')
                val name = item.optString("name").trim('/')
                val fullPath = when {
                    path.isBlank() -> name
                    name.isBlank() -> path
                    path == name || path.endsWith("/$name") -> path
                    else -> "$path/$name"
                }
                if (fullPath.isBlank()) null else BtTorrentFile(index, fullPath, item.optLong("size").coerceAtLeast(0L))
            }
            require(files.isNotEmpty()) { "磁力元数据中没有可下载文件" }
            val name = resource.optString("name").ifBlank { files.first().path.substringBefore('/') }
                .ifBlank { "BT 下载" }
            BtTorrentResource(extracted, name, files, files.sumOf { it.size })
        } else {
            val bytes = when {
                extracted.startsWith("content:", ignoreCase = true) ->
                    readBounded(context.contentResolver.openInputStream(Uri.parse(extracted))
                        ?: throw IllegalArgumentException("无法读取所选种子文件"))
                extracted.startsWith("file:", ignoreCase = true) ->
                    readBounded(java.io.FileInputStream(java.io.File(Uri.parse(extracted).path
                        ?: throw IllegalArgumentException("种子文件路径无效"))))
                extracted.startsWith("/") && extracted.endsWith(".torrent", ignoreCase = true) ->
                    readBounded(java.io.FileInputStream(java.io.File(extracted)))
                isTorrentHttpUrl(extracted) -> downloadTorrent(extracted)
                else -> throw IllegalArgumentException("请输入 magnet 链接、.torrent 下载地址，或选择本地 .torrent 文件")
            }
            parseTorrent(bytes)
        }
    }

    /** 从整段分享文案中提取可直接交给 Gopeed 后台任务的 magnet URI。 */
    fun extractMagnet(input: String): String? =
        extractBtInput(input).takeIf { it.startsWith("magnet:", ignoreCase = true) }

    private fun extractBtInput(raw: String): String {
        val text = raw.trim()
        val magnet = Regex("(?i)magnet:\\?[^\\s<>\\\"]+").find(text)?.value
        if (magnet != null) return magnet.trimEnd('.', ',', ';', ')', ']', '}', '，', '。')
        val url = Regex("(?i)https?://[^\\s<>\\\"]+\\.torrent(?:\\?[^\\s<>\\\"]*)?").find(text)?.value
        if (url != null) return url.trimEnd('.', ',', ';', ')', ']', '}', '，', '。')
        return text
    }

    private fun isTorrentHttpUrl(raw: String): Boolean = runCatching {
        val uri = Uri.parse(raw)
        uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() &&
            uri.path.orEmpty().endsWith(".torrent", ignoreCase = true)
    }.getOrDefault(false)

    private fun downloadTorrent(url: String): ByteArray {
        val request = Request.Builder().url(url).header("Accept", "application/x-bittorrent, application/octet-stream, */*")
            .header("User-Agent", "Dsu-Manager/3") .get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("种子下载地址返回 HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("种子文件内容为空")
            if (body.contentLength() > MAX_TORRENT_BYTES) throw IllegalArgumentException("种子文件不能超过 4 MiB")
            return readBounded(body.byteStream())
        }
    }

    private fun readBounded(input: InputStream): ByteArray = input.use { stream ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var count: Int
        while (stream.read(buffer).also { count = it } >= 0) {
            if (count == 0) continue
            if (out.size() + count > MAX_TORRENT_BYTES) throw IllegalArgumentException("种子文件不能超过 4 MiB")
            out.write(buffer, 0, count)
        }
        out.toByteArray().also { require(it.isNotEmpty()) { "种子文件为空" } }
    }

    private fun parseTorrent(bytes: ByteArray): BtTorrentResource {
        require(bytes.size in 1..MAX_TORRENT_BYTES) { "种子文件大小无效（最大 4 MiB）" }
        val reader = BencodeReader(bytes)
        val root = reader.read() as? BValue.Dict ?: throw IllegalArgumentException("种子文件格式无效")
        require(reader.position == bytes.size) { "种子文件含有无效尾部数据" }
        val infoBytes = reader.infoRange ?: throw IllegalArgumentException("种子文件缺少 info 元数据")
        val info = root.values["info"] as? BValue.Dict ?: throw IllegalArgumentException("种子文件 info 无效")
        val nameBytes = info.bytes("name.utf-8") ?: info.bytes("name")
            ?: throw IllegalArgumentException("种子文件缺少名称")
        val name = decodeUtf8(nameBytes).trim().ifBlank { "BT 下载" }
        val files = mutableListOf<BtTorrentFile>()
        val multi = info.values["files"] as? BValue.ListValue
        if (multi != null) {
            require(multi.values.size <= 10000) { "种子包含过多文件（上限 10000）" }
            for ((index, value) in multi.values.withIndex()) {
                val item = value as? BValue.Dict ?: continue
                val size = item.integer("length") ?: 0L
                val pieces = (item.values["path.utf-8"] as? BValue.ListValue)
                    ?: (item.values["path"] as? BValue.ListValue)
                val components = pieces?.values?.mapNotNull { (it as? BValue.Bytes)?.data?.let(::decodeUtf8) }
                    .orEmpty().filter { it.isNotBlank() && it != "." && it != ".." }
                val path = (listOf(name) + components).joinToString("/")
                if (components.isNotEmpty()) files += BtTorrentFile(index, path, size.coerceAtLeast(0L))
            }
        } else {
            val size = info.integer("length") ?: throw IllegalArgumentException("种子文件大小字段无效")
            files += BtTorrentFile(0, name, size.coerceAtLeast(0L))
        }
        require(files.isNotEmpty()) { "种子文件列表为空或格式不受支持" }
        // infoRange 是闭区间；copyOfRange 的结束下标则是排他的。少取最后一个字节会生成错误 info-hash。
        val hash = MessageDigest.getInstance("SHA-1").digest(bytes.copyOfRange(infoBytes.first, infoBytes.last + 1))
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
        val trackers = linkedSetOf<String>()
        root.bytes("announce")?.let { decodeUtf8(it).trim() }?.takeIf(::isSafeTracker)?.let(trackers::add)
        collectTrackers(root.values["announce-list"], trackers)
        val magnet = buildString {
            append("magnet:?xt=urn:btih:").append(hash).append("&dn=").append(urlEncode(name))
            trackers.take(50).forEach { append("&tr=").append(urlEncode(it)) }
        }
        return BtTorrentResource(magnet, name, files, files.sumOf { it.size })
    }

    private fun collectTrackers(value: BValue?, output: MutableSet<String>) {
        when (value) {
            is BValue.Bytes -> decodeUtf8(value.data).trim().takeIf(::isSafeTracker)?.let(output::add)
            is BValue.ListValue -> value.values.forEach { collectTrackers(it, output) }
            else -> Unit
        }
    }

    private fun isSafeTracker(value: String): Boolean = runCatching {
        val uri = Uri.parse(value)
        uri.scheme in listOf("http", "https", "udp") && !uri.host.isNullOrBlank() &&
            uri.userInfo.isNullOrEmpty() && value.length <= 2048
    }.getOrDefault(false)

    private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    private fun decodeUtf8(bytes: ByteArray): String = bytes.toString(Charsets.UTF_8)

    private sealed interface BValue {
        data class Bytes(val data: ByteArray) : BValue
        data class Number(val value: Long) : BValue
        data class ListValue(val values: List<BValue>) : BValue
        data class Dict(val values: LinkedHashMap<String, BValue>) : BValue {
            fun bytes(key: String) = (values[key] as? Bytes)?.data
            fun integer(key: String) = (values[key] as? Number)?.value
        }
    }

    private class BencodeReader(private val data: ByteArray) {
        var position = 0
            private set
        var infoRange: IntRange? = null
            private set
        private var nodes = 0

        fun read(depth: Int = 0): BValue {
            require(depth <= 64 && ++nodes <= 100000 && position < data.size) { "种子字典层级或元素数量异常" }
            return when (data[position].toInt().toChar()) {
                'i' -> readInteger()
                'l' -> {
                    position++
                    val items = ArrayList<BValue>()
                    while (peek() != 'e') items += read(depth + 1)
                    position++
                    BValue.ListValue(items)
                }
                'd' -> {
                    position++
                    val items = LinkedHashMap<String, BValue>()
                    while (peek() != 'e') {
                        val key = readBytes().toString(Charsets.UTF_8)
                        val valueStart = position
                        val value = read(depth + 1)
                        if (depth == 0 && key == "info") infoRange = valueStart until position
                        items[key] = value
                    }
                    position++
                    BValue.Dict(items)
                }
                in '0'..'9' -> BValue.Bytes(readBytes())
                else -> throw IllegalArgumentException("种子字典编码无效")
            }
        }

        private fun readInteger(): BValue.Number {
            position++
            val start = position
            while (peek() != 'e') position++
            val value = data.copyOfRange(start, position).toString(Charsets.US_ASCII).toLongOrNull()
                ?: throw IllegalArgumentException("种子数字字段无效")
            position++
            return BValue.Number(value)
        }

        private fun readBytes(): ByteArray {
            val start = position
            while (position < data.size && data[position].toInt().toChar() in '0'..'9') position++
            require(position > start && position < data.size && data[position].toInt().toChar() == ':') {
                "种子字符串长度字段无效"
            }
            val length = data.copyOfRange(start, position).toString(Charsets.US_ASCII).toIntOrNull()
                ?: throw IllegalArgumentException("种子字符串长度无效")
            require(length >= 0 && length <= data.size - position - 1) { "种子字符串越界" }
            position++
            val result = data.copyOfRange(position, position + length)
            position += length
            return result
        }

        private fun peek(): Char {
            require(position < data.size) { "种子字典意外结束" }
            return data[position].toInt().toChar()
        }
    }
}
