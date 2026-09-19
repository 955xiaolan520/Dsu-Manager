package com.probiotics.xiaoni;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * v3.9.5：Android sparse 镜像（simg）→ 原始镜像（raw）流式转换器。
 *
 * 背景：OTA payload.bin 中提取出的分区镜像（vendor.img / product.img 等）几乎都是
 * Android sparse 格式（magic 0xED26FF3A）。DSU 的 createBackingImage/mapImageDevice
 * 期望写入的是 raw 数据 —— 如果把 sparse 文件原样复制进 DSU 分区，复制会「成功」，
 * 但分区内容是稀疏容器字节而非 ext4 文件系统，重启进 DSU 必然卡开机。
 *
 * 本转换器在应用侧边读边转：raw 数据块直拷、fill 块按 4 字节图案展开、
 * don't-care 块补零，输出与 simg2img 等价的 raw 镜像，无需临时整包缓存两份。
 */
public final class SparseImageConverter {

    /** Android sparse 魔数（小端 u32 0xED26FF3A → 字节 3A FF 26 ED） */
    static boolean isSparseHeader(byte[] first4) {
        return first4 != null && first4.length >= 4
                && (first4[0] & 0xFF) == 0x3A
                && (first4[1] & 0xFF) == 0xFF
                && (first4[2] & 0xFF) == 0x26
                && (first4[3] & 0xFF) == 0xED;
    }

    /** 转换进度回调：written / total（字节） */
    public interface Progress {
        void onProgress(long written, long total);
    }

    private static final int CHUNK_TYPE_RAW = 0xCAC1;
    private static final int CHUNK_TYPE_FILL = 0xCAC2;
    private static final int CHUNK_TYPE_DONT_CARE = 0xCAC3;
    private static final int CHUNK_TYPE_CRC32 = 0xCAC4;

    private static final int BUFFER = 4 * 1024 * 1024;

    private SparseImageConverter() { }

    /**
     * 流式转换 sparse → raw。
     *
     * @param input  已定位到 sparse 文件头（字节 0）的输入流（内部会做缓冲）
     * @param output raw 输出流
     * @return 输出的 raw 总字节数（= blkSize × totalBlocks）
     */
    public static long unpack(InputStream input, OutputStream output, Progress progress) throws IOException {
        BufferedInputStream in = new BufferedInputStream(input, 1024 * 1024);
        byte[] header = new byte[28];
        readFully(in, header, 28);
        if (!isSparseHeader(header)) throw new IOException("not an Android sparse image");
        int fileHdrSz = readLe16(header, 8) & 0xFFFF;
        int chunkHdrSz = readLe16(header, 10) & 0xFFFF;
        if (fileHdrSz < 28) fileHdrSz = 28;
        if (chunkHdrSz < 12) chunkHdrSz = 12;
        if (fileHdrSz > 28) skipFully(in, fileHdrSz - 28);
        long blkSize = readLe32(header, 12) & 0xFFFFFFFFL;
        long totalBlocks = readLe32(header, 16) & 0xFFFFFFFFL;
        long totalChunks = readLe32(header, 20) & 0xFFFFFFFFL;
        if (blkSize <= 0 || totalBlocks <= 0) throw new IOException("corrupt sparse header");
        long total = blkSize * totalBlocks;

        byte[] buffer = new byte[BUFFER];
        byte[] chunkHdr = new byte[chunkHdrSz];
        byte[] fill = new byte[(int) Math.min(blkSize, 4 * 1024 * 1024)];
        byte[] zeros = new byte[(int) Math.min(blkSize, 4 * 1024 * 1024)];
        long written = 0;
        long lastUi = 0;
        for (long chunk = 0; chunk < totalChunks && written < total; chunk++) {
            readFully(in, chunkHdr, chunkHdrSz);
            int type = readLe16(chunkHdr, 0) & 0xFFFF;
            long chunkBlocks = readLe32(chunkHdr, 4) & 0xFFFFFFFFL;
            long totalSz = readLe32(chunkHdr, 8) & 0xFFFFFFFFL;
            long dataLen = totalSz - chunkHdrSz;
            long outLen = chunkBlocks * blkSize;
            switch (type) {
                case CHUNK_TYPE_RAW: {
                    if (dataLen != outLen) throw new IOException("sparse raw chunk size mismatch");
                    long remain = dataLen;
                    while (remain > 0) {
                        int n = in.read(buffer, 0, (int) Math.min(remain, buffer.length));
                        if (n < 0) throw new IOException("sparse image truncated");
                        output.write(buffer, 0, n);
                        remain -= n;
                        written += n;
                        if (progress != null) lastUi = report(progress, written, total, lastUi);
                    }
                    break;
                }
                case CHUNK_TYPE_FILL: {
                    byte[] pattern = new byte[4];
                    readFully(in, pattern, 4);
                    for (int i = 0; i + 3 < fill.length; i += 4) {
                        fill[i] = pattern[0];
                        fill[i + 1] = pattern[1];
                        fill[i + 2] = pattern[2];
                        fill[i + 3] = pattern[3];
                    }
                    long remain = outLen;
                    while (remain > 0) {
                        int n = (int) Math.min(remain, fill.length);
                        output.write(fill, 0, n);
                        remain -= n;
                        written += n;
                        if (progress != null) lastUi = report(progress, written, total, lastUi);
                    }
                    break;
                }
                case CHUNK_TYPE_DONT_CARE: {
                    // 块设备未写入区域是脏数据，don't-care 也补零保证确定性
                    long remain = outLen;
                    while (remain > 0) {
                        int n = (int) Math.min(remain, zeros.length);
                        output.write(zeros, 0, n);
                        remain -= n;
                        written += n;
                        if (progress != null) lastUi = report(progress, written, total, lastUi);
                    }
                    break;
                }
                case CHUNK_TYPE_CRC32: {
                    if (dataLen > 0) skipFully(in, dataLen);
                    break;
                }
                default:
                    throw new IOException("unknown sparse chunk type: 0x" + Integer.toHexString(type));
            }
        }
        if (written != total) throw new IOException("sparse image incomplete: " + written + " / " + total);
        return total;
    }

    /** 节流上报（≥150ms），返回本次时间戳 */
    private static long report(Progress progress, long written, long total, long lastUi) {
        long now = System.currentTimeMillis();
        if (now - lastUi >= 150 || written >= total) {
            progress.onProgress(written, total);
            return now;
        }
        return lastUi;
    }

    private static void readFully(InputStream in, byte[] buffer, int length) throws IOException {
        int off = 0;
        while (off < length) {
            int n = in.read(buffer, off, length - off);
            if (n < 0) throw new IOException("unexpected end of sparse image");
            off += n;
        }
    }

    private static void skipFully(InputStream in, long count) throws IOException {
        long remain = count;
        byte[] scratch = new byte[64 * 1024];
        while (remain > 0) {
            int n = in.read(scratch, 0, (int) Math.min(remain, scratch.length));
            if (n < 0) throw new IOException("unexpected end of sparse image");
            remain -= n;
        }
    }

    private static int readLe16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static long readLe32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }
}
