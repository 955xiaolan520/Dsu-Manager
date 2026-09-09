package com.probiotics.xiaoni;

import android.content.Context;
import android.util.Base64;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;

final class VivoOtaClient {
    enum QueryChannel { NORMAL, TRIAL, BETA, ALPHA }
    private static final String TOKEN = "jnisgmain_v2@com.bbk.updater";
    private static final String OTA_URL = "https://sysupgrade.vivo.com.cn/vgc/v2/getVgcAndPatch.do";
    private static final String REDIR_URL = "https://sysupgrade.vivo.com.cn/pk/redirPost.do";
    private static final String TASTE_URL = "https://sysupgrade.vivo.com.cn/upgrade/trial/getTastePk";
    private final Context context;

    VivoOtaClient(Context context) { this.context = context.getApplicationContext(); }

    VivoResult query(String codename, String modelSwVer, String swVersion, int androidVersion,
                     boolean isPhone, boolean isFull, String serial, boolean verbose,
                     QueryChannel channel) throws Exception {
        if (!VivoCrypto.init(context)) throw new IllegalStateException("Vivo 原生加密引擎初始化失败");
        String fullSw = swVersion.contains(".W") ? swVersion + ".V000L1" : swVersion;
        String fullVer = codename + "_A_" + fullSw;
        String version = codename + "_N_" + codename + "MA_" + fullSw;
        Random random = new Random();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("vgcNewActiveVer", ""); p.put("nt", "WIFI"); p.put("vgcSwVer", "1.1.1");
        p.put("fullVer", fullVer); p.put("emmcid", ""); p.put("sm1", "null"); p.put("sm2", "null");
        p.put("model", codename); p.put("hasVgc", 1); p.put("vgcNewPassiveVer", ""); p.put("ch", "N");
        p.put("gn", 0); p.put("newActiveVer", ""); p.put("version", version); p.put("st2", 0);
        p.put("cu", "N"); p.put("srm2", 0); p.put("srm1", 0); p.put("cy", "CN-ZH");
        p.put("sn2", "null"); p.put("ne", "null"); p.put("sn1", "null"); p.put("public_model", modelSwVer);
        p.put("newPassiveVer", ""); p.put("hwVer", codename + "MA"); p.put("swVer", fullSw);
        p.put("language", "zh_CN"); p.put("isMan", 1); p.put("isFull", isFull ? 1 : 0); p.put("protocalversion", "1.0");
        p.put("checkTrige", "MANUL"); p.put("isstlifeover", "false"); p.put("hwFingerprint", "");
        p.put("vgcCu", "V000"); p.put("sf", 1); p.put("si", "null"); p.put("dType", "phone");
        p.put("s_n", "null"); p.put("elapsedtime", isPhone ? 140000 + random.nextInt(80000) : 2000000 + random.nextInt(500000));
        p.put("st1", 100000 + random.nextInt(60000)); p.put("imei", "000000000000000");
        p.put("ms", 0); p.put("mtype", "no"); p.put("radiotype", "L");
        if (!isPhone) {
            p.put("romVersion", "Funtouch " + androidVersion + ".0");
            p.put("snp", serial);
            p.put("dType", "tablet");
        }
        if (channel != QueryChannel.NORMAL) {
            p.remove("isMan"); p.remove("protocalversion"); p.remove("checkTrige"); p.remove("isstlifeover");
            p.put("trigger", "verfy"); p.put("isSupportVgcTaste", 1); p.put("isSupportShowNote", 1);
        }
        String response;
        if (channel == QueryChannel.NORMAL) {
            response = postEncrypted(OTA_URL, join(p));
        } else if (channel == QueryChannel.TRIAL) {
            response = postEncrypted(TASTE_URL, join(p));
        } else {
            throw new UnsupportedOperationException("Beta 和 Alpha 接口协议尚未确认");
        }
        String pk = stringField(response, "pk");
        String download = "";
        if (!pk.isEmpty()) {
            String redirected = postEncrypted(REDIR_URL, pk.contains("?") ? pk.substring(pk.indexOf('?') + 1) : pk);
            download = stringField(redirected, "data").replace("\\/", "/");
        }
        return new VivoResult(stringField(response, "version"), stringField(response, "pkName"),
                stringField(response, "pkLen"), stringField(response, "md5"), download,
                stringField(response, "securityPatch"), stringField(response, "updateTime"));
    }

    private String postEncrypted(String endpoint, String plain) throws Exception {
        String encoded = Base64.encodeToString(packageData(VivoCrypto.crypt(plain.getBytes(StandardCharsets.UTF_8), true)), Base64.URL_SAFE | Base64.NO_WRAP);
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST"); connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("User-Agent", "okhttp/4.3.23"); connection.setRequestProperty("Accept-Encoding", "gzip");
        connection.setDoOutput(true);
        connection.getOutputStream().write(("jvq_param=" + encoded).getBytes(StandardCharsets.UTF_8));
        InputStream input = connection.getResponseCode() >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if ("gzip".equalsIgnoreCase(connection.getContentEncoding())) input = new GZIPInputStream(input);
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line; while ((line = reader.readLine()) != null) body.append(line);
        }
        String raw = body.toString();
        if (!raw.startsWith("ACw") && !raw.startsWith("ACo")) throw new IllegalStateException("Vivo 服务返回异常");
        byte[] packet = Base64.decode(raw.replace('-', '+').replace('_', '/'), Base64.DEFAULT);
        DataInputStream data = new DataInputStream(new ByteArrayInputStream(packet));
        int headerLength = data.readUnsignedShort();
        byte[] encrypted = new byte[packet.length - headerLength];
        System.arraycopy(packet, headerLength, encrypted, 0, encrypted.length);
        return new String(VivoCrypto.crypt(encrypted, false), StandardCharsets.UTF_8);
    }

    private byte[] packageData(byte[] encrypted) throws Exception {
        byte[] token = TOKEN.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream fields = new ByteArrayOutputStream();
        DataOutputStream field = new DataOutputStream(fields);
        field.writeShort(1); field.writeByte(token.length); field.write(token); field.writeShort(2); field.writeByte(5);
        byte[] fieldBytes = fields.toByteArray(); CRC32 crc = new CRC32(); crc.update(fieldBytes);
        ByteArrayOutputStream result = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(result);
        out.writeShort(16 + token.length); out.writeLong(crc.getValue()); out.write(fieldBytes); out.write(encrypted);
        return result.toByteArray();
    }

    private static String join(Map<String, Object> params) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (out.length() > 0) out.append('&');
            out.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return out.toString();
    }

    private static String stringField(String json, String key) {
        try { return new JSONObject(json).optString(key, ""); } catch (Exception ignored) { return ""; }
    }

    static final class VivoResult {
        final String version, filename, size, md5, downloadUrl, securityPatch, updateTime;
        VivoResult(String version, String filename, String size, String md5, String downloadUrl, String securityPatch, String updateTime) {
            this.version = version; this.filename = filename; this.size = size; this.md5 = md5; this.downloadUrl = downloadUrl;
            this.securityPatch = securityPatch; this.updateTime = updateTime;
        }
    }
}
