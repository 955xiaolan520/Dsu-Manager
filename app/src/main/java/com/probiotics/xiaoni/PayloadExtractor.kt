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
    }
}
