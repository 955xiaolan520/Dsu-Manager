package com.probiotics.xiaoni;

import android.content.Context;
import com.mytiantian.updater.vivo.VivoOtaResult;

final class VivoOtaClient {
    enum QueryChannel { NORMAL, TRIAL, BETA, ALPHA }
    /** 可选择的升级服务器域名（与 Kotlin 端 Domain 枚举一一对应）。 */
    enum Domain { CN, GLOBAL }
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
                     boolean isPhone, boolean isFull, String serial, String imei,
                     QueryChannel channel, Domain domain) throws Exception {
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
        com.mytiantian.updater.vivo.VivoOtaClient.Domain kotlinDomain = domain == Domain.GLOBAL
                ? com.mytiantian.updater.vivo.VivoOtaClient.Domain.GLOBAL
                : com.mytiantian.updater.vivo.VivoOtaClient.Domain.CN;

        // 调用 Kotlin 实现（imei 由调用方给出：手动/随机；为空时 Kotlin 端自行兜底）
        VivoOtaResult kotlinResult = kotlinClient.query(
            codename,
            modelSwVer,
            swVersion,
            androidVersion,
            isPhone,
            isFull,
            serial,
            imei == null ? "" : imei,
            kotlinChannel,
            kotlinDomain
        );

        // 将 Kotlin 结果转换为 Java VivoResult
        return new VivoResult(
            kotlinResult.getUpdateVersion(),
            kotlinResult.getFilename(),
            kotlinResult.getFileSizeBytes(),
            kotlinResult.getMd5(),
            kotlinResult.getDownloadUrl(),
            kotlinResult.getSecurityPatch(),
            kotlinResult.getUpdateDate(),
            kotlinResult.getChangelogUrl(),
            kotlinResult.isFullPackage(),
            kotlinResult.getFullFallback(),
            kotlinResult.getBetaOrTasteType(),
            kotlinResult.getBetaRecruitHint()
        );
    }

    /** 更新日志抓取与解析（H5 data/CN.js → 正文），失败返回 null。 */
    String fetchChangelog(String changelogUrl) {
        return kotlinClient.fetchChangelog(changelogUrl);
    }

    static final class VivoResult {
        final String version, filename, size, md5, downloadUrl, securityPatch, updateTime, changelogUrl;
        /** 服务端实际返回的包类型（ext.isFull）：TRUE=全量 FALSE=增量，null=响应未携带。 */
        final Boolean isFullPackage;
        /** 请求全量包但服务器无全量（retcode=210），已自动降级查到增量包；UI 需明确提示。 */
        final boolean fullFallback;
        /** v3.9.20：/beta/queryBetaOrTaste.do 响应 data.type：0=未走该接口 1=公测招募 2=内测/尝鲜更新包。 */
        final int betaOrTasteType;
        /** type=1 时的公测招募摘要（项目版本/推送计划）。 */
        final String betaRecruitHint;
        VivoResult(String version, String filename, String size, String md5, String downloadUrl,
                   String securityPatch, String updateTime, String changelogUrl, Boolean isFullPackage,
                   boolean fullFallback, int betaOrTasteType, String betaRecruitHint) {
            this.version = version;
            this.filename = filename;
            this.size = size;
            this.md5 = md5;
            this.downloadUrl = downloadUrl;
            this.securityPatch = securityPatch;
            this.updateTime = updateTime;
            this.changelogUrl = changelogUrl;
            this.isFullPackage = isFullPackage;
            this.fullFallback = fullFallback;
            this.betaOrTasteType = betaOrTasteType;
            this.betaRecruitHint = betaRecruitHint == null ? "" : betaRecruitHint;
        }
    }
}
