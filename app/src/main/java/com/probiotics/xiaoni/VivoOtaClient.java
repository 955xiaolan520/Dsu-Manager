package com.probiotics.xiaoni;

import android.content.Context;
import com.mytiantian.updater.vivo.VivoOtaResult;

final class VivoOtaClient {
    enum QueryChannel { NORMAL, TRIAL, BETA, ALPHA }
    private final Context context;
    private final com.mytiantian.updater.vivo.VivoOtaClient kotlinClient;

    VivoOtaClient(Context context) {
        this.context = context.getApplicationContext();
        this.kotlinClient = new com.mytiantian.updater.vivo.VivoOtaClient(this.context);
        // 初始化加密
        if (!this.kotlinClient.initCrypto()) {
            throw new RuntimeException("Failed to initialize VivoCrypto");
        }
    }

    VivoResult query(String codename, String modelSwVer, String swVersion, int androidVersion,
                     boolean isPhone, boolean isFull, String serial,
                     QueryChannel channel) throws Exception {
        // 将 Java QueryChannel 转换为 Kotlin QueryChannel
        com.mytiantian.updater.vivo.VivoOtaClient.QueryChannel kotlinChannel;
        switch (channel) {
            case NORMAL:
                kotlinChannel = com.mytiantian.updater.vivo.VivoOtaClient.QueryChannel.NORMAL;
                break;
            case TRIAL:
                kotlinChannel = com.mytiantian.updater.vivo.VivoOtaClient.QueryChannel.TRIAL;
                break;
            case BETA:
                kotlinChannel = com.mytiantian.updater.vivo.VivoOtaClient.QueryChannel.BETA;
                break;
            case ALPHA:
                kotlinChannel = com.mytiantian.updater.vivo.VivoOtaClient.QueryChannel.ALPHA;
                break;
            default:
                throw new IllegalArgumentException("Unknown channel: " + channel);
        }

        // 调用 Kotlin 实现
        VivoOtaResult kotlinResult = kotlinClient.query(
            codename,
            modelSwVer,
            swVersion,
            androidVersion,
            isPhone,
            isFull,
            serial,
            kotlinChannel
        );

        // 将 Kotlin 结果转换为 Java VivoResult
        return new VivoResult(
            kotlinResult.getUpdateVersion(),
            kotlinResult.getFilename(),
            kotlinResult.getFileSizeBytes(),
            kotlinResult.getMd5(),
            kotlinResult.getDownloadUrl(),
            kotlinResult.getSecurityPatch(),
            kotlinResult.getUpdateDate()
        );
    }

    static final class VivoResult {
        final String version, filename, size, md5, downloadUrl, securityPatch, updateTime;
        VivoResult(String version, String filename, String size, String md5, String downloadUrl, String securityPatch, String updateTime) {
            this.version = version;
            this.filename = filename;
            this.size = size;
            this.md5 = md5;
            this.downloadUrl = downloadUrl;
            this.securityPatch = securityPatch;
            this.updateTime = updateTime;
        }
    }
}
