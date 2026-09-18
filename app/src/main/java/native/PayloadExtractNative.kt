package native

/**
 * libpayload_extract_jni.so 的 JNI 桥接（ROM 镜像提取功能）。
 *
 * 注意：so 内的 JNI 符号为 Java_native_PayloadExtractNative_*，
 * 因此本类必须位于包 `native`、且类名必须为 `PayloadExtractNative`。
 * （`native` 是 Java 关键字、不是 Kotlin 关键字，故用 Kotlin 声明）
 */
object PayloadExtractNative {

    init {
        System.loadLibrary("payload_extract_jni")
    }

    /** 打开 payload.bin / OTA 包，返回句柄（0 表示失败） */
    external fun open(input: String): Long

    /** 列出分区，返回 JSON 数组字符串 */
    external fun listPartitionsJson(handle: Long, withHash: Boolean): String

    /** 获取 payload 元数据，返回 JSON 字符串 */
    external fun getMetadataJson(handle: Long): String

    /** 提取指定分区（多线程），token 用于进度查询与取消 */
    external fun extractPartition(
        input: String,
        outputDir: String,
        partitionName: String,
        threads: Int,
        verify: Boolean,
        token: Long,
    )

    /**
     * 查询提取进度。
     * 返回 -1 表示无进度；否则高 16 位为阶段（0=下载中, 1=提取中），低 16 位为千分比。
     */
    external fun getExtractProgress(token: Long): Long

    /** 关闭句柄 */
    external fun close(handle: Long)
}
