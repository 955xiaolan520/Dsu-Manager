package com.probiotics.xiaoni

import android.util.Log
import native.PayloadExtractNative
import org.json.JSONArray
import org.json.JSONObject

/**
 * Payload 提取器 - 使用 libpayload_extract_jni.so
 */
class PayloadExtractor {
    private var handle: Long = 0
    
    data class PartitionInfo(
        val name: String,
        val size: Long,
        val hash: String?
    )
    
    data class Metadata(
        val version: String?,
        val blockSize: Int,
        val partitionCount: Int
    )
    
    fun open(input: String): Boolean {
        return try {
            handle = PayloadExtractNative.open(input)
            Log.d(TAG, "打开成功, handle=$handle")
            handle != 0L
        } catch (e: Exception) {
            Log.e(TAG, "打开失败", e)
            false
        }
    }
    
    fun listPartitions(withHash: Boolean = true): List<PartitionInfo> {
        if (handle == 0L) {
            Log.e(TAG, "handle 为 0，无法列出分区")
            return emptyList()
        }
        
        return try {
            val json = PayloadExtractNative.listPartitionsJson(handle, withHash)
            Log.d(TAG, "分区 JSON: $json")
            parsePartitions(json)
        } catch (e: Exception) {
            Log.e(TAG, "解析分区列表失败", e)
            emptyList()
        }
    }
    
    fun getMetadata(): Metadata? {
        if (handle == 0L) return null
        
        return try {
            val json = PayloadExtractNative.getMetadataJson(handle)
            Log.d(TAG, "元数据 JSON: $json")
            parseMetadata(json)
        } catch (e: Exception) {
            Log.e(TAG, "解析元数据失败", e)
            null
        }
    }
    
    fun extractPartition(
        input: String,
        outputDir: String,
        partitionName: String,
        threads: Int = 4,
        verify: Boolean = false,
        token: Long
    ) {
        Log.d(TAG, "开始提取: $partitionName -> $outputDir (token=$token)")
        try {
            PayloadExtractNative.extractPartition(input, outputDir, partitionName, threads, verify, token)
            Log.d(TAG, "JNI 调用完成: $partitionName")
        } catch (e: Exception) {
            Log.e(TAG, "JNI 调用失败: $partitionName", e)
            throw e
        }
    }
    
    /**
     * 获取提取进度
     * @return Pair(phase, permille) 或 null
     *         phase: 0=下载中, 1=提取中
     *         permille: 0-1000 (千分比)
     */
    fun getExtractProgress(token: Long): android.util.Pair<Int, Int>? {
        val raw = PayloadExtractNative.getExtractProgress(token)
        if (raw == -1L) return null
        
        val phase = (raw shr 16).toInt()
        val permille = (raw and 0xFFFF).toInt()
        return android.util.Pair(phase, permille)
    }
    
    fun close() {
        if (handle != 0L) {
            PayloadExtractNative.close(handle)
            Log.d(TAG, "已关闭 handle=$handle")
            handle = 0
        }
    }
    
    private fun parsePartitions(json: String): List<PartitionInfo> {
        val array = JSONArray(json)
        val list = mutableListOf<PartitionInfo>()
        
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(
                PartitionInfo(
                    name = obj.getString("name"),
                    size = obj.getLong("size"),
                    hash = if (obj.has("hash") && !obj.isNull("hash")) obj.getString("hash") else null
                )
            )
        }
        
        return list
    }
    
    private fun parseMetadata(json: String): Metadata {
        val obj = JSONObject(json)
        return Metadata(
            version = if (obj.has("version") && !obj.isNull("version")) obj.getString("version") else null,
            blockSize = obj.getInt("block_size"),
            partitionCount = obj.getInt("num_partitions")
        )
    }
    
    companion object {
        private const val TAG = "PayloadExtractor"

        /**
         * v3.30.35 快速列分区：Java 直读 payload 头 + manifest（毫秒级出列表）。
         * 字段号（经 payload_dumper v0.8.4 实测校准，v3.30.33 的 1/5 是错的）：
         *   DeltaArchiveManifest: field 13 = repeated PartitionUpdate
         *   PartitionUpdate: field 1 = name, field 7 = new_partition_info{ 1:size }
         * 要求文件已 root 放行（chmod/chown app）。非裸 payload.bin（OTA zip 等）返回 null，
         * 调用方回退 payload_dumper --list（支持 zip 直读）或 JNI。
         */
        @JvmStatic
        fun fastListPartitions(path: String): List<PartitionInfo>? {
            return try {
                java.io.RandomAccessFile(path, "r").use { raf ->
                    val head = ByteArray(20)
                    raf.readFully(head)
                    // magic "CrAU"
                    if (head[0] != 0x43.toByte() || head[1] != 0x72.toByte()
                        || head[2] != 0x41.toByte() || head[3] != 0x55.toByte()) return null
                    val first = be64(head, 4)
                    // 头布局：magic(4)+version(8)+manifest_len(8)+metadata_sig_len(4)
                    // v2+：manifest 从 24 起（v3.30.36 修复：此前误用 20 → 解析必失败回退）；
                    // v1：magic(4)+manifest_len(8)，manifest 从 12 起
                    val manifestOff: Long
                    val manifestLen: Long
                    if (first in 2..10) { manifestOff = 24; manifestLen = be64(head, 12) }
                    else { manifestOff = 12; manifestLen = first }
                    if (manifestLen < 8 || manifestLen > 512L * 1024 * 1024) return null
                    raf.seek(manifestOff)
                    val m = ByteArray(manifestLen.toInt())
                    raf.readFully(m)
                    val out = mutableListOf<PartitionInfo>()
                    val c = Cursor(m, 0, m.size)
                    while (c.i < c.end) {
                        val tag = readVarint(c) ?: break
                        val field = (tag ushr 3).toInt()
                        val wt = (tag and 7).toInt()
                        if (wt == 2) {
                            val len = readVarint(c) ?: break
                            val l = len.toInt()
                            if (l < 0 || c.i + l > c.end) break
                            if (field == 13) parsePartition(m, c.i, c.i + l)?.let { out.add(it) }
                            c.i += l
                        } else if (!skipField(c, wt)) break
                    }
                    if (out.isEmpty()) null else out
                }
            } catch (e: Exception) {
                Log.e(TAG, "fastListPartitions failed", e)
                null
            }
        }

        /**
         * v3.30.35 增量包检测：任一 PartitionUpdate 含 old_partition_info(field 6)
         * 即为增量（delta）payload，提取需要 --source-dir 旧镜像目录。
         */
        @JvmStatic
        fun fastIsIncremental(path: String): Boolean {
            return try {
                java.io.RandomAccessFile(path, "r").use { raf ->
                    val head = ByteArray(20)
                    raf.readFully(head)
                    if (head[0] != 0x43.toByte() || head[1] != 0x72.toByte()
                        || head[2] != 0x41.toByte() || head[3] != 0x55.toByte()) return false
                    val first = be64(head, 4)
                    val manifestOff: Long
                    val manifestLen: Long
                    if (first in 2..10) { manifestOff = 24; manifestLen = be64(head, 12) }
                    else { manifestOff = 12; manifestLen = first }
                    if (manifestLen < 8 || manifestLen > 512L * 1024 * 1024) return false
                    raf.seek(manifestOff)
                    val m = ByteArray(manifestLen.toInt())
                    raf.readFully(m)
                    val c = Cursor(m, 0, m.size)
                    while (c.i < c.end) {
                        val tag = readVarint(c) ?: break
                        val field = (tag ushr 3).toInt()
                        val wt = (tag and 7).toInt()
                        if (wt == 2) {
                            val len = readVarint(c) ?: break
                            val l = len.toInt()
                            if (l < 0 || c.i + l > c.end) break
                            if (field == 13 && hasOldInfo(m, c.i, c.i + l)) return true
                            c.i += l
                        } else if (!skipField(c, wt)) break
                    }
                    false
                }
            } catch (e: Exception) {
                false
            }
        }

        /** PartitionUpdate 内是否含 field 6（old_partition_info） */
        private fun hasOldInfo(b: ByteArray, from: Int, to: Int): Boolean {
            val c = Cursor(b, from, to)
            while (c.i < c.end) {
                val tag = readVarint(c) ?: break
                val field = (tag ushr 3).toInt()
                val wt = (tag and 7).toInt()
                if (wt == 2) {
                    val len = readVarint(c) ?: break
                    val l = len.toInt()
                    if (l < 0 || c.i + l > c.end) break
                    if (field == 6) return true
                    c.i += l
                } else if (!skipField(c, wt)) break
            }
            return false
        }

        /** PartitionUpdate{ 1:name(str), 7:new_partition_info{ 1:size(varint) } } */
        private fun parsePartition(b: ByteArray, from: Int, to: Int): PartitionInfo? {
            val c = Cursor(b, from, to)
            var name: String? = null
            var size = 0L
            while (c.i < c.end) {
                val tag = readVarint(c) ?: break
                val field = (tag ushr 3).toInt()
                val wt = (tag and 7).toInt()
                if (wt == 2) {
                    val len = readVarint(c) ?: break
                    val l = len.toInt()
                    if (l < 0 || c.i + l > c.end) break
                    if (field == 1 && name == null)
                        name = String(b, c.i, l, Charsets.UTF_8)
                    else if (field == 7 && size == 0L)
                        size = parseInfoSize(b, c.i, c.i + l)
                    c.i += l
                } else if (!skipField(c, wt)) break
            }
            return name?.let { PartitionInfo(it, size, null) }
        }

        /** PartitionInfo{ 1:size(varint) } */
        private fun parseInfoSize(b: ByteArray, from: Int, to: Int): Long {
            val c = Cursor(b, from, to)
            while (c.i < c.end) {
                val tag = readVarint(c) ?: break
                val field = (tag ushr 3).toInt()
                val wt = (tag and 7).toInt()
                if (wt == 0) {
                    val v = readVarint(c) ?: break
                    if (field == 1) return v
                } else if (!skipField(c, wt)) break
            }
            return 0L
        }

        private class Cursor(val b: ByteArray, var i: Int, val end: Int)

        private fun readVarint(c: Cursor): Long? {
            var shift = 0
            var v = 0L
            while (c.i < c.end && shift < 64) {
                val byte = c.b[c.i++].toInt() and 0xFF
                v = v or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return v
                shift += 7
            }
            return null
        }

        /** 按 wire type 跳过字段，越界返回 false */
        private fun skipField(c: Cursor, wt: Int): Boolean {
            return when (wt) {
                0 -> readVarint(c) != null
                1 -> { c.i += 8; c.i <= c.end }
                5 -> { c.i += 4; c.i <= c.end }
                else -> false
            }
        }

        private fun be64(b: ByteArray, off: Int): Long {
            var v = 0L
            for (k in 0 until 8) v = (v shl 8) or (b[off + k].toLong() and 0xFF)
            return v
        }
    }
}
