package com.probiotics.xiaoni;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * OTA 卡刷包打包核心（v3.43.0，纯 Java 无 Android 依赖）。
 *
 * 参照 snowwolf725/Payload_Repack_Tool 的 delta_generator 链路 + AOSP signapk -w
 * 的 whole-file 签名，在 APP 内完成「img 集 → 完整可刷 OTA.zip」：
 *
 * 1. delta_generator（云端工具链，root CLI）：img 集 → 未签名 payload.bin
 * 2. delta_generator --out_hash_file/--out_metadata_hash_file：导出两份 32 字节原始 SHA256
 * 3. {@link #signHash}：PKCS#1 v1.5 签名（NONEwithRSA + DigestInfo 前缀，内置 AOSP
 *    testkey，与 TWRP 验签同源；2026-10-05 经 delta_generator --public_key 官方验证器
 *    对拍通过：payload + metadata 双签名 Verified correct）
 * 4. delta_generator --payload_signature_file/--metadata_signature_file：注入签名
 * 5. delta_generator --properties_file：导出 payload_properties.txt（FILE_HASH 等）
 * 6. {@link #rebuildOtaZip}：以原 OTA.zip 为模板重建 —— 保留原包脚本/元数据，
 *    替换 payload.bin（STORED）+ payload_properties.txt，改写 metadata /
 *    metadata.pb 的 ota-property-files（zip64 偏移感知，实测 LineageOS 格式：
 *    name:offset:size，offset 为数据区绝对偏移，值尾部空格填充固定宽度）
 * 7. {@link #signWholeFile}：whole-file 签名（recovery/TWRP 验签格式）
 * 8. {@link #buildOtaZip}：从零生成（无模板，v3.43.1）—— 自动生成 metadata / metadata.pb /
 *    otacert + payload 完整包，适合手头没有同机型 OTA 包时使用
 *
 * whole-file 格式（与 AOSP signpk.java signWholeFile 逐字段对拍一致，签名覆盖
 * file[0, len-2)；2026-10-05 经 openssl cms -verify -binary 实测通过）：
 * [zip … EOCD32 前 20 字节][EOCD 注释长度域=total_size][注释: "signed by SignApk"\0 + CMS]
 * [sig_start LE16][FF FF][total_size LE16]，sig_start = total - 18（CMS 起点距文件尾）。
 * 注意 openssl 验证必须带 -binary（否则文本规范化改变内容字节导致验签失败）。
 */
public final class OtaPacker {

    /** 日志/进度回调（UI 注入） */
    public interface LogFn { void log(String line); }
    public interface ProgressFn { void onProgress(long done, long total); }

    // AOSP 公开测试密钥（testkey），与 Payload_Repack_Tool / CM/LineageOS 开发签名同源；
    // TWRP 默认信任 testkey，CustomOTA_CA 模块亦将其注入系统信任链
    private static final String TESTKEY_PK8_B64 = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDWkxkE3sYLJLHtx2Lg2dglPj7NbOsd4v8GjKjovKjNa9N4bqcKp2zmDrsPmTVZ/9k+d6lD5+g9S2S45P6i0+ZW8eJnqBu/sjC1eMIEQ75Mchi4RvUhFYbwOKFOicK+OH+Ovs+PysPaHuMwyeqT0KfD3ErzUCINUAgHMuCAlxfuagUzWeamlOwss/KEoKRmyHqU2DsxCTpnNy4vZBLAbm1C8VgY3/4DgcwM1ETabN3DuCRYGUgBsyVkE0+/3pjJKHdI2/VnalQNgVTIu8oHueJHVTMRxGua92/e7MyOaefIotCOeCYglD+Zcn08BP5ymR2Z35uuOKCyF3+jHVtq/ukfAgEDAoIBAQCPDLtYlIQHbcvz2kHrO+VuKX8znfIT7KoEXcXwfcXeR+JQScSxxPNECdIKZiORVTt++nDX7/Ao3O3QmKnB4pmPS+xFGr0qdssjpdatgn7doWXQL04WDln1exY0W9cpev+0fzUKhy08FJd12/G34G/X6DH3isFeNVqvd0BVug/0RXWihnmONcUztAJ25E5YNqHadWSt+vU4pJOpvxDyE6ZXrBIpHBvlaZf8atJ7maf8iXfSZUzrqnx1O5zaTGRnGo7o/UdrfuLDfpVXnXBEHm+rk6QTq2ZKyZj6JZQ/K1LB+cXqZO9KG8oBSecXohQBeJYIDEikB9xHdsvelr1MoYR7AoGBAOrAmRccm5UnjAe/npdFGIVXkXaep7Ur9rqT4NaoSMSnDRim6Kii2lNoZ2szvvKYuxRNmvi1u60iRvQsLM10duqyG+FKdx+S5632ALWTKvdH97l3VYcRCrDYAyMYdotYavF8bcT9QKgYHoWHb18KLL27A4afIXmrVXCnWXp1e2GbAoGBAOn+9xk0qK83mecSq5edXgJ1lq2NaRVmSZYc5KKtCC8YYiQ0TSuIiRSpzJ3tR28wLtxO5lvqd72R8vBMPzS6CbY5RCj7tOBVW8bPTuwOYUN+AAN87csZvlmPsUsXMmBNQTYycvo0Keh/ZR0RIoFmN37SyagZC1ybj90t4cUCkUDNAoGBAJyAZg9oZ7jFCAUqabouEFjlC6RpxSNypHxileRwMIMaCLsZ8HBskYzwRPIif0xl0g2JEfsj0nNsL01yyIj4T0chZ+uG+hUMmnP5Vc5iHKTapSZPjloLXHXlV2y6+bI68fZS89io1cVlaa5aSj9cHdPSAlm/a6ZyOPXE5lGjp5ZnAoGBAJv/T2YjGx96ZpoMcmUTlAGjuckI8Lju27lomGxzWsoQQW14M3JbBg3GiGlI2kogHz2J7ufxpSkL90rdf3h8Bnl7gsX9I0A459nfifK0QNepVVeonodmfuZfy4dkzEAzgM7MTKbNcUWqQ2i2FwDuz6nh28VmB5MSX+jJQS4BtiszAoGAYyqt2RrdpGLZlaZyYlsFzalGIfTpWXPuj5ot63Ghwawb0xoN1qKJdYcbanvrblVhtKEsYKOkae96d1grNcf4Vbm3bMrPwHdIRf6pRS+x46mMBfuap1JoGcXESY4NwdsbpYo71PuBgykeNHaO2nq0BYcm/RyNFHuJZd+PFfOevDc=";
    private static final String TESTKEY_CERT_B64 = "MIIEqDCCA5CgAwIBAgIJAJNurL4H8gHfMA0GCSqGSIb3DQEBBQUAMIGUMQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEQMA4GA1UEChMHQW5kcm9pZDEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDEiMCAGCSqGSIb3DQEJARYTYW5kcm9pZEBhbmRyb2lkLmNvbTAeFw0wODAyMjkwMTMzNDZaFw0zNTA3MTcwMTMzNDZaMIGUMQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEQMA4GA1UEChMHQW5kcm9pZDEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDEiMCAGCSqGSIb3DQEJARYTYW5kcm9pZEBhbmRyb2lkLmNvbTCCASAwDQYJKoZIhvcNAQEBBQADggENADCCAQgCggEBANaTGQTexgskse3HYuDZ2CU+Ps1s6x3i/waMqOi8qM1r03hupwqnbOYOuw+ZNVn/2T53qUPn6D1LZLjk/qLT5lbx4meoG7+yMLV4wgRDvkxyGLhG9SEVhvA4oU6Jwr44f46+z4/Kw9oe4zDJ6pPQp8PcSvNQIg1QCAcy4ICXF+5qBTNZ5qaU7Cyz8oSgpGbIepTYOzEJOmc3Li9kEsBubULxWBjf/gOBzAzURNps3cO4JFgZSAGzJWQTT7/emMkod0jb9WdqVA2BVMi7yge54kdVMxHEa5r3b97szI5p58ii0I54JiCUP5lyfTwE/nKZHZnfm644oLIXf6MdW2r+6R8CAQOjgfwwgfkwHQYDVR0OBBYEFEhZAFY9JyxGrhGGBaR0GawJyowRMIHJBgNVHSMEgcEwgb6AFEhZAFY9JyxGrhGGBaR0GawJyowRoYGapIGXMIGUMQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEQMA4GA1UEChMHQW5kcm9pZDEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDEiMCAGCSqGSIb3DQEJARYTYW5kcm9pZEBhbmRyb2lkLmNvbYIJAJNurL4H8gHfMAwGA1UdEwQFMAMBAf8wDQYJKoZIhvcNAQEFBQADggEBAHqvlozrUMRBBVEY0NqrrwFbinZaJ6cVosK0TyIUFf/azgMJWr+kLfcHCHJsIGnlw27drgQAvilFLAhLwn62oX6snb4YLCBOsVMR9FXYJLZW2+TcIkCRLXWG/oiVHQGo/rWuWkJgU134NDEFJCJGjDbiLCpe+ZTWHdcwauTJ9pUbo8EvHRkU3cYfGmLaLfgn9gP+pWA7LFQNvXwBnDa6sppCccEX31I828XzgXpJ4O+mDL1/dBd+ek8ZPUP0IgdyZm5MTYPhvVqGCHzzTy3sIeJFymwrsBbmg2OAUNLEMO6nwmocSdN2ClirfxqCzJOLSDE4QyS9BAH6EhY6UFcOaE0=";
    private static volatile PrivateKey cachedKey;
    private static volatile X509Certificate cachedCert;

    private OtaPacker() {}

    // ==================== 密钥 ====================

    public static synchronized PrivateKey testKey() throws Exception {
        if (customKey != null) return customKey;
        if (cachedKey == null) {
            byte[] der = Base64.getMimeDecoder().decode(TESTKEY_PK8_B64);
            cachedKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        }
        return cachedKey;
    }

    public static synchronized X509Certificate testCert() throws Exception {
        if (customCert != null) return customCert;
        if (cachedCert == null) {
            byte[] der = Base64.getMimeDecoder().decode(TESTKEY_CERT_B64);
            cachedCert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new java.io.ByteArrayInputStream(der));
        }
        return cachedCert;
    }

    // ==================== 自定义签名密钥（v3.43.4） ====================

    private static volatile PrivateKey customKey;
    private static volatile X509Certificate customCert;

    /**
     * 导入自定义签名密钥（与 AOSP signapk 使用的格式一致）：
     * - 私钥：PKCS#8，.pk8（DER）或 "-----BEGIN PRIVATE KEY-----" PEM 均可，RSA ≥ 2048 位；
     * - 证书：X.509，.x509.pem（"-----BEGIN CERTIFICATE-----"）或 DER 均可，须与私钥配对。
     * 导入后 payload 签名 / whole-file 签名 / otacert 条目全部改用该密钥。
     * 注意：recovery 信任链里必须装有对应公钥（otacert），否则验签不通过；TWRP 默认信任 testkey。
     */
    public static synchronized void useCustomKey(byte[] pk8, byte[] cert) throws Exception {
        PrivateKey k = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(decodeMaybePem(pk8)));
        X509Certificate c = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(decodeMaybePem(cert)));
        if (!"RSA".equals(c.getPublicKey().getAlgorithm()))
            throw new IllegalArgumentException("certificate is not RSA");
        customKey = k;
        customCert = c;
    }

    /** 恢复内置 AOSP testkey */
    public static synchronized void clearCustomKey() {
        customKey = null;
        customCert = null;
    }

    public static boolean customKeyActive() { return customKey != null; }

    /** 当前生效密钥名（日志显示用） */
    public static String activeKeyName() { return customKey != null ? "custom" : "testkey"; }

    /** PEM（-----BEGIN/END----- 包裹的 base64）→ DER；输入已是 DER 则原样返回 */
    private static byte[] decodeMaybePem(byte[] data) {
        String s = new String(data, StandardCharsets.US_ASCII).trim();
        if (s.startsWith("-----BEGIN")) {
            StringBuilder b64 = new StringBuilder();
            for (String line : s.split("\n")) {
                line = line.trim();
                if (line.startsWith("-----")) continue;
                b64.append(line);
            }
            return Base64.getMimeDecoder().decode(b64.toString());
        }
        return data;
    }

    // ==================== DER 微型编码 ====================

    static byte[] derTag(int t, byte[] content) {
        ByteArrayOutputStream len = new ByteArrayOutputStream();
        int n = content.length;
        if (n < 0x80) len.write(n);
        else {
            int bytes = (32 - Integer.numberOfLeadingZeros(n) + 7) / 8;
            len.write(0x80 | bytes);
            for (int i = bytes - 1; i >= 0; i--) len.write((n >> (8 * i)) & 0xff);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(t); out.writeBytes(len.toByteArray()); out.writeBytes(content);
        return out.toByteArray();
    }
    static byte[] derSeq(byte[]... parts) { return derConcat(0x30, parts); }
    static byte[] derSet(byte[]... parts) { return derConcat(0x31, parts); }
    static byte[] derConcat(int first, byte[][] parts) {
        int n = 0; for (byte[] p : parts) n += p.length;
        byte[] all = new byte[n]; int o = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, all, o, p.length); o += p.length; }
        return derTag(first, all);
    }
    static byte[] derExplicit(int i, byte[] part) { return derTag(0xA0 | i, part); }
    static byte[] derImplicit(int i, byte[] part) { return derTag(0x80 | i, part); }
    static byte[] derOid(String dotted) {
        String[] p = dotted.split("\\.");
        ByteArrayOutputStream c = new ByteArrayOutputStream();
        c.write(Integer.parseInt(p[0]) * 40 + Integer.parseInt(p[1]));
        for (int i = 2; i < p.length; i++) {
            long v = Long.parseLong(p[i]);
            int shift = Math.max(0, (63 - Long.numberOfLeadingZeros(v | 1)));
            shift -= shift % 7;
            for (; shift > 0; shift -= 7) c.write(0x80 | (int) ((v >> shift) & 0x7f));
            c.write((int) (v & 0x7f));
        }
        return derTag(0x06, c.toByteArray());
    }
    static byte[] derInt(java.math.BigInteger v) { return derTag(0x02, v.toByteArray()); }
    static byte[] derOctet(byte[] b) { return derTag(0x04, b); }
    static final byte[] DER_NULL = {0x05, 0x00};

    // ==================== 签名 ====================

    /** 步骤 3：对 delta_generator 导出的 32 字节原始 SHA256 做 PKCS#1 v1.5 签名（prehashed）。
     * 语义实测（delta_generator --public_key 验证端对拍）：RawVerify 校验 RSA 还原值的
     * 尾部 32B == 原始哈希 —— 即签名对象 = DigestInfo(sha256) 前缀 + rawHash（尾部即哈希本身）。
     * 注意：不能直接用 SHA256withRSA（它会把 rawHash 再哈希一遍，payload_verifier 必拒）。 */
    public static byte[] signHash(byte[] rawHash) throws Exception {
        if (rawHash == null || rawHash.length != 32)
            throw new IllegalArgumentException("raw hash must be 32 bytes");
        byte[] digestInfo = new byte[19 + 32];   // DER DigestInfo(SHA256)
        byte[] prefix = {0x30, 0x31, 0x30, 0x0d, 0x06, 0x09, 0x60, (byte) 0x86, 0x48, 0x01,
                0x65, 0x03, 0x04, 0x02, 0x01, 0x05, 0x00, 0x04, 0x20};
        System.arraycopy(prefix, 0, digestInfo, 0, prefix.length);
        System.arraycopy(rawHash, 0, digestInfo, prefix.length, 32);
        Signature s = Signature.getInstance("NONEwithRSA");
        s.initSign(testKey());
        s.update(digestInfo);
        return s.sign();
    }

    /**
     * 构建单签名者 detached CMS/PKCS#7（无 authenticatedAttributes；certificates [0]
     * 为 constructed 编码，已与 openssl cms -sign 参考输出对拍并 -binary 验证通过）。
     */
    static byte[] buildCms(byte[] sig) throws Exception {
        X509Certificate cert = testCert();
        byte[] shaOid = derOid("2.16.840.1.101.3.4.2.1");
        byte[] digestAlg = derSeq(shaOid);
        byte[] shaRsa = derSeq(derOid("1.2.840.113549.1.1.11"), DER_NULL);
        byte[] sid = derSeq(cert.getIssuerX500Principal().getEncoded(),
                derInt(cert.getSerialNumber()));
        byte[] signerInfo = derSeq(derInt(java.math.BigInteger.ONE), sid, digestAlg, shaRsa, derOctet(sig));
        byte[] signedData = derSeq(
                derInt(java.math.BigInteger.ONE),
                derSet(digestAlg),
                derSeq(derOid("1.2.840.113549.1.7.1")),   // eContent 缺席 = detached
                derExplicit(0, cert.getEncoded()),   // certificates [0] 必须 constructed（0xA0），
                // primitive [0] 会导致 openssl/cms 严格解析失败（对拍 signpk/openssl 参考输出）
                derSet(signerInfo));
        return derSeq(derOid("1.2.840.113549.1.7.2"), derExplicit(0, signedData));
    }

    /**
     * whole-file 签名（流式，zip 必须以无注释的 EOCD32 结尾）：
     * 1. SHA256withRSA 流式算 file[0, len-2)（含中央目录与 zip64 记录）
     * 2. EOCD32 末 2 字节注释长度域补丁为 total_size（不参与哈希）
     * 3. 追加注释 "signed by SignApk"\0 + CMS + [sig_start LE16][FF FF][total_size LE16]
     */
    public static void signWholeFile(File zipFile, LogFn log) throws Exception {
        long len = zipFile.length();
        if (len < 22) throw new IOException("zip too small");
        try (RandomAccessFile raf = new RandomAccessFile(zipFile, "r")) {
            byte[] tail = new byte[4];
            raf.seek(len - 22);
            raf.readFully(tail);
            if (tail[0] != 0x50 || tail[1] != 0x4b || tail[2] != 0x05 || tail[3] != 0x06)
                throw new IOException("zip 尾部不是无注释 EOCD（可能已签名）");
        }
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(testKey());
        try (FileInputStream in = new FileInputStream(zipFile)) {
            byte[] buf = new byte[1024 * 1024];
            long left = len - 2;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new EOFException("unexpected EOF");
                s.update(buf, 0, n);
                left -= n;
            }
        }
        byte[] cms = buildCms(s.sign());
        byte[] msg = "signed by SignApk".getBytes(StandardCharsets.UTF_8);
        int total = msg.length + 1 + cms.length + 6;
        if (total > 0xffff) throw new IOException("签名块超出 zip 注释上限");
        int sigStart = total - msg.length - 1;
        // 注释中不得出现伪 EOCD（minzip 自尾部扫描会误判）
        byte[] comment = new byte[msg.length + 1 + cms.length];
        System.arraycopy(msg, 0, comment, 0, msg.length);
        System.arraycopy(cms, 0, comment, msg.length + 1, cms.length);
        for (int i = 0; i < comment.length - 3; i++)
            if (comment[i] == 0x50 && comment[i + 1] == 0x4b && comment[i + 2] == 0x05 && comment[i + 3] == 0x06)
                throw new IOException("CMS 内出现伪 EOCD 序列");
        try (RandomAccessFile raf = new RandomAccessFile(zipFile, "rw")) {
            raf.seek(len - 2);
            raf.write(total & 0xff); raf.write((total >> 8) & 0xff);   // EOCD 注释长度域
            raf.write(comment);
            raf.write(sigStart & 0xff); raf.write((sigStart >> 8) & 0xff);
            raf.write(0xff); raf.write(0xff);
            raf.write(total & 0xff); raf.write((total >> 8) & 0xff);
        }
        if (log != null) log.log("✓ whole-file 签名完成（" + activeKeyName() + " · CMS " + cms.length + "B）");
    }

    // ==================== OTA zip 重建 ====================

    /** 模板里跳过不拷贝的条目（重建/替换/失效项 + 旧 JAR 签名残留） */
    private static boolean skipEntry(String name) {
        if (name.endsWith("/")) return true;
        if (name.equals("payload.bin") || name.equals("payload_properties.txt")
                || name.equals("payload_metadata.bin") || name.equals("apex_info.pb")
                || name.equals("apex_info.txt") || name.equals("care_map.pb")
                || name.equals("care_map.txt")) return true;
        if (name.equals("META-INF/com/android/metadata")
                || name.equals("META-INF/com/android/metadata.pb")
                || name.equals("META-INF/com/android/otacert")
                || name.equals("META-INF/MANIFEST.MF")) return true;
        String u = name.toUpperCase(java.util.Locale.ROOT);
        if (u.startsWith("META-INF/") && (u.endsWith(".SF") || u.endsWith(".RSA")
                || u.endsWith(".DSA") || u.endsWith(".EC"))) return true;
        return false;
    }

    /** ota-property-files 值固定填充宽度（LineageOS 同款尾部空格手法，长度恒定免迭代） */
    private static final int PROP_VALUE_PAD = 512;

    /**
     * 以模板 OTA.zip 重建完整卡刷包（payload 已签名）。
     * @param propsText delta_generator --properties_file 输出的原文（FILE_HASH 等）
     * @return 输出文件字节数
     */
    public static long rebuildOtaZip(File templateZip, File payloadBin, byte[] propsText,
                                     File outZip, LogFn log, ProgressFn progress) throws Exception {
        // ---- 1. 读模板：静态条目压缩缓存 + 原始 metadata 文本 / pb ----
        List<String> names = new ArrayList<>();
        List<byte[]> compDatas = new ArrayList<>();
        List<long[]> sizes = new ArrayList<>();   // [uncomp, comp]
        List<Long> crcs = new ArrayList<>();
        byte[] metaText = null, metaPb = null;
        try (ZipFile tpl = new ZipFile(templateZip)) {
            java.util.Enumeration<? extends ZipEntry> en = tpl.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (skipEntry(e.getName())) {
                    if (e.getName().equals("META-INF/com/android/metadata"))
                        metaText = readAll(tpl.getInputStream(e));
                    else if (e.getName().equals("META-INF/com/android/metadata.pb"))
                        metaPb = readAll(tpl.getInputStream(e));
                    continue;
                }
                byte[] raw = readAll(tpl.getInputStream(e));
                byte[] comp = deflate(raw);
                CRC32 c = new CRC32(); c.update(raw);
                names.add(e.getName());
                compDatas.add(comp);
                sizes.add(new long[]{raw.length, comp.length});
                crcs.add(c.getValue());
            }
        }
        if (log != null) log.log("… 模板静态条目 " + names.size() + " 个"
                + (metaText == null ? "（无 metadata 文本，将生成最小格式）" : " + metadata"));

        // ---- 2. 布局计算（全部尺寸已知 → 单次渲染） ----
        final String META_NAME = "META-INF/com/android/metadata";
        final String METAPB_NAME = "META-INF/com/android/metadata.pb";
        final String PAYLOAD_NAME = "payload.bin";
        final String PROPS_NAME = "payload_properties.txt";
        long off = 0;
        for (int i = 0; i < names.size(); i++)
            off += 30 + nameBytes(names.get(i)).length + compDatas.get(i).length;
        long offMeta = off + 30 + nameBytes(META_NAME).length;   // metadata 数据起点
        long lenMeta, lenMetaPb;
        long payloadSize = payloadBin.length();
        // 占位渲染量长度（值填充到 512 → 长度与偏移数值无关，恒定）
        {
            byte[] t = patchMetadataText(metaText, placeholderValue());
            lenMeta = t.length;
            byte[] p = patchMetadataPb(metaPb, placeholderValue(), placeholderValue());
            lenMetaPb = p.length;
        }
        // ota-property-files 偏移 = 数据区起点（LFH(30+nameLen[+zip64 extra]) 之后；
        // AOSP ota_utils.py 同款 header_offset + 本地头长度，recovery 按此直接读 payload 数据）
        long offMetaPbData = offMeta + lenMeta + 30 + nameBytes(METAPB_NAME).length;
        boolean payloadZip64 = payloadSize > 0xFFFFFFFEL;
        long offPayload = offMetaPbData + lenMetaPb + 30 + nameBytes(PAYLOAD_NAME).length
                + (payloadZip64 ? 20 : 0);
        long offProps = offPayload + payloadSize + 30 + nameBytes(PROPS_NAME).length;

        String propsValue = padValue("payload.bin:" + offPayload + ":" + payloadSize
                + "," + PROPS_NAME + ":" + offProps + ":" + propsText.length
                + "," + "metadata:" + offMeta + ":" + lenMeta
                + "," + "metadata.pb:" + offMetaPbData + ":" + lenMetaPb);
        // 尺寸与占位渲染一致（防御：不一致则带真实值重渲染一次并校验）
        byte[] metaTextOut = patchMetadataText(metaText, propsValue);
        byte[] metaPbOut = patchMetadataPb(metaPb, propsValue, propsValue);
        if (metaTextOut.length != lenMeta || metaPbOut.length != lenMetaPb)
            throw new IOException("metadata 尺寸计算不稳定");

        // ---- 3. 写 zip（手写 zip64 感知，payload STORED 流式 CRC 回填） ----
        List<CdRec> cd = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(outZip, "rw")) {
            raf.setLength(0);
            for (int i = 0; i < names.size(); i++)
                writeEntry(raf, cd, names.get(i), compDatas.get(i), 8,
                        crcs.get(i), sizes.get(i)[0], sizes.get(i)[1]);
            // metadata 双件 STORED：长度即布局计算用的长度（压缩会移位 ota-property-files 偏移）
            long metaCrc = crc32Of(metaTextOut);
            writeEntry(raf, cd, META_NAME, metaTextOut, 0, metaCrc,
                    metaTextOut.length, metaTextOut.length);
            long metaPbCrc = crc32Of(metaPbOut);
            writeEntry(raf, cd, METAPB_NAME, metaPbOut, 0, metaPbCrc,
                    metaPbOut.length, metaPbOut.length);
            writePayload(raf, cd, PAYLOAD_NAME, payloadBin, payloadSize, progress);
            long propsCrc = crc32Of(propsText);
            writeEntry(raf, cd, PROPS_NAME, propsText, 0, propsCrc,
                    propsText.length, propsText.length);
            writeCentralDirectory(raf, cd);
        }
        if (log != null) log.log("… zip 组装完成（payload @ " + offPayload + "，"
                + (payloadZip64 ? "zip64" : "zip32") + "）");
        return outZip.length();
    }

    // ==================== 从零生成 OTA zip（无模板） ====================

    /**
     * 从零构建完整 A/B 卡刷包（无需模板，v3.43.1）。
     * 生成 recovery/TWRP 安装 A/B payload 所需的最小完整结构（对齐 AOSP
     * ota_from_target_files 产物布局 + 真机 LineageOS 包）：
     * <pre>
     *   META-INF/com/android/metadata      文本元数据（STORED）
     *   META-INF/com/android/metadata.pb   protobuf 元数据（STORED，OtaMetadata 强类型
     *                                      precondition/postcondition + property_files map 双表示）
     *   payload.bin                        已签名 payload（STORED，zip64 感知）
     *   payload_properties.txt             FILE_HASH 等（STORED）
     *   META-INF/com/android/otacert       签名证书 PEM（DEFLATED，尾部不影响偏移）
     * </pre>
     * proto 结构依据 AOSP build/make tools/releasetools/ota_metadata.proto（proto3）：
     * OtaMetadata{ type=1; wipe=2; downgrade=3; map property_files=4;
     *              DeviceState precondition=5; DeviceState postcondition=6; … }
     * DeviceState{ repeated string device=1; repeated string build=2;
     *              string build_incremental=3; int64 timestamp=4; … string security_patch_level=6 }
     *
     * @param meta 设备元数据：pre-device 必填；post-build / post-build-incremental /
     *             post-timestamp / post-security-patch-level 建议提供（getprop 采集）
     * @return 输出文件字节数
     */
    public static long buildOtaZip(File payloadBin, byte[] propsText, Map<String, String> meta,
                                    File outZip, LogFn log, ProgressFn progress) throws Exception {
        final String META_NAME = "META-INF/com/android/metadata";
        final String METAPB_NAME = "META-INF/com/android/metadata.pb";
        final String PAYLOAD_NAME = "payload.bin";
        final String PROPS_NAME = "payload_properties.txt";
        final String CERT_NAME = "META-INF/com/android/otacert";

        String device = meta.getOrDefault("pre-device", "");
        if (device.isEmpty()) throw new IOException("pre-device 缺失（从零生成需设备代号）");

        // ---- 1. 占位渲染定长（ota-property-files 值 512 填充，长度与偏移数值无关） ----
        long payloadSize = payloadBin.length();
        long lenMeta = genMetadataText(meta, placeholderValue()).length;
        long lenMetaPb = genMetadataPb(meta, placeholderValue()).length;
        long offMeta = 30 + nameBytes(META_NAME).length;
        long offMetaPbData = offMeta + lenMeta + 30 + nameBytes(METAPB_NAME).length;
        boolean payloadZip64 = payloadSize > 0xFFFFFFFEL;
        long offPayload = offMetaPbData + lenMetaPb + 30 + nameBytes(PAYLOAD_NAME).length
                + (payloadZip64 ? 20 : 0);
        long offProps = offPayload + payloadSize + 30 + nameBytes(PROPS_NAME).length;

        String propsValue = padValue("payload.bin:" + offPayload + ":" + payloadSize
                + "," + PROPS_NAME + ":" + offProps + ":" + propsText.length
                + "," + "metadata:" + offMeta + ":" + lenMeta
                + "," + "metadata.pb:" + offMetaPbData + ":" + lenMetaPb);
        byte[] metaTextOut = genMetadataText(meta, propsValue);
        byte[] metaPbOut = genMetadataPb(meta, propsValue);
        if (metaTextOut.length != lenMeta || metaPbOut.length != lenMetaPb)
            throw new IOException("metadata 尺寸计算不稳定");

        // ---- 2. 写 zip（复用 rebuildOtaZip 同款原语） ----
        List<CdRec> cd = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(outZip, "rw")) {
            raf.setLength(0);
            writeEntry(raf, cd, META_NAME, metaTextOut, 0, crc32Of(metaTextOut),
                    metaTextOut.length, metaTextOut.length);
            writeEntry(raf, cd, METAPB_NAME, metaPbOut, 0, crc32Of(metaPbOut),
                    metaPbOut.length, metaPbOut.length);
            writePayload(raf, cd, PAYLOAD_NAME, payloadBin, payloadSize, progress);
            writeEntry(raf, cd, PROPS_NAME, propsText, 0, crc32Of(propsText),
                    propsText.length, propsText.length);
            byte[] pem = testCertPem();
            byte[] pemComp = deflate(pem);
            writeEntry(raf, cd, CERT_NAME, pemComp, 8, crc32Of(pem),
                    pem.length, pemComp.length);
            writeCentralDirectory(raf, cd);
        }
        if (log != null) log.log("… 从零生成完成（" + device + " · payload @ " + offPayload
                + " · " + (payloadZip64 ? "zip64" : "zip32") + "）");
        return outZip.length();
    }

    /**
     * 解析目标机 system.img 的 build.prop → metadata 键值（v3.43.2 模式三：
     * 给其他手机打包时，post-build 等必须取自目标系统而非本机）。
     * 属性带分区前缀 fallback（Treble：ro.product.device / ro.product.system.device /
     * ro.product.vendor.device …值一致；ro.build.fingerprint 优先取 system 侧）。
     * 输入：build.prop 文本（可多分区合并拼接，后者覆盖前者 —— 调用方让 vendor 先、
     * system 后，保证指纹类取 system 值）。
     */
    public static Map<String, String> parseBuildProp(String content) {
        Map<String, String> props = new LinkedHashMap<>();
        for (String line : content.split("\n", -1)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            props.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        Map<String, String> meta = new LinkedHashMap<>();
        putMetaFromProps(meta, props, "pre-device",
                "ro.product.device", "ro.product.system.device", "ro.product.vendor.device",
                "ro.product.product.device", "ro.product.odm.device", "ro.product.modem_device");
        // 指纹取值顺序（v3.43.3 修正）：QSSI 机型（oppo/一加/realme/新小米等）的
        // system 侧只有高通通用指纹 qti/qssi/qssi，真正的设备指纹在 vendor —— 故
        // vendor 优先于 system；ro.build.fingerprint 若存在必是设备指纹（非 QSSI 机型）。
        putMetaFromProps(meta, props, "post-build",
                "ro.build.fingerprint", "ro.vendor.build.fingerprint",
                "ro.product.build.fingerprint", "ro.odm.build.fingerprint",
                "ro.bootimage.build.fingerprint", "ro.system.build.fingerprint");
        putMetaFromProps(meta, props, "post-build-incremental",
                "ro.build.version.incremental", "ro.vendor.build.version.incremental",
                "ro.system.build.version.incremental");
        putMetaFromProps(meta, props, "post-timestamp", "ro.build.date.utc",
                "ro.system.build.date.utc");
        putMetaFromProps(meta, props, "post-security-patch-level",
                "ro.build.version.security_patch", "ro.system.build.version.security_patch");
        return meta;
    }

    /** 按 fallback 顺序取第一个非空属性写入 meta */
    private static void putMetaFromProps(Map<String, String> meta, Map<String, String> props,
                                         String metaKey, String... propKeys) {
        for (String k : propKeys) {
            String v = props.get(k);
            if (v != null && !v.isEmpty()) {
                meta.put(metaKey, v);
                return;
            }
        }
    }

    /** 签名证书 → PEM（真机 OTA 的 otacert 条目同款文本格式） */
    public static byte[] testCertPem() throws Exception {
        String b64 = Base64.getMimeEncoder(76, new byte[]{'\n'})
                .encodeToString(testCert().getEncoded());
        return ("-----BEGIN CERTIFICATE-----\n" + b64 + "\n-----END CERTIFICATE-----\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * 读取 payload.bin manifest 里的分区名列表（v3.43.4：模板分区对比用）。
     * 头 24B：magic "CrAU" + version(8B BE) + manifest_size(8B BE) + meta_sig_size(4B BE)；
     * manifest 为 DeltaArchiveManifest protobuf —— field 13 = repeated PartitionUpdate，
     * PartitionUpdate 内 field 1 = partition_name (string)。
     */
    public static List<String> payloadPartitions(File payloadBin) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(payloadBin, "r")) {
            byte[] hdr = new byte[24];
            raf.readFully(hdr);
            if (hdr[0] != 'C' || hdr[1] != 'r' || hdr[2] != 'A' || hdr[3] != 'U')
                throw new IOException("payload magic mismatch");
            long manifestSize = ((hdr[12] & 0xffL) << 56) | ((hdr[13] & 0xffL) << 48)
                    | ((hdr[14] & 0xffL) << 40) | ((hdr[15] & 0xffL) << 32)
                    | ((hdr[16] & 0xffL) << 24) | ((hdr[17] & 0xffL) << 16)
                    | ((hdr[18] & 0xffL) << 8) | (hdr[19] & 0xffL);
            if (manifestSize <= 0 || manifestSize > 64L * 1024 * 1024)
                throw new IOException("bad manifest size: " + manifestSize);
            byte[] manifest = new byte[(int) manifestSize];
            raf.seek(24);
            raf.readFully(manifest);
            return partitionsFromManifest(manifest);
        }
    }

    /** manifest 字节 → 分区名列表（zip 流式读取时无需落盘整个 payload） */
    public static List<String> partitionsFromManifest(byte[] manifest) {
        List<String> parts = new ArrayList<>();
        int i = 0;
        while (i < manifest.length) {
            long[] tag = readVarint(manifest, i);
            int field = (int) (tag[0] >>> 3), wire = (int) (tag[0] & 7);
            i = (int) tag[1];
            if (wire == 0) {
                i = (int) readVarint(manifest, i)[1];
            } else if (wire == 1) {
                i += 8;
            } else if (wire == 2) {
                long[] l = readVarint(manifest, i);
                i = (int) l[1];
                int len = (int) l[0];
                if (field == 13) {   // PartitionUpdate
                    String name = partitionNameOf(manifest, i, i + len);
                    if (name != null) parts.add(name);
                }
                i += len;
            } else if (wire == 5) {
                i += 4;
            } else {
                throw new IllegalArgumentException("bad wire type in manifest: " + wire);
            }
        }
        return parts;
    }

    /** 自检：私钥签名 ↔ 证书公钥验签（导入自定义密钥时调用，确保两文件配对） */
    public static void verifyKeyPair() throws Exception {
        byte[] dummy = new byte[32];
        new java.security.SecureRandom().nextBytes(dummy);
        byte[] sig = signHash(dummy);
        byte[] digestInfo = new byte[19 + 32];
        byte[] prefix = {0x30, 0x31, 0x30, 0x0d, 0x06, 0x09, 0x60, (byte) 0x86, 0x48, 0x01,
                0x65, 0x03, 0x04, 0x02, 0x01, 0x05, 0x00, 0x04, 0x20};
        System.arraycopy(prefix, 0, digestInfo, 0, prefix.length);
        System.arraycopy(dummy, 0, digestInfo, prefix.length, 32);
        java.security.Signature v = java.security.Signature.getInstance("NONEwithRSA");
        v.initVerify(testCert().getPublicKey());
        v.update(digestInfo);
        if (!v.verify(sig))
            throw new IllegalArgumentException("私钥与证书不配对（签名验证失败）");
    }

    /** PartitionUpdate 子消息里取 field 1（partition_name） */
    private static String partitionNameOf(byte[] msg, int from, int to) {
        int i = from;
        while (i < to) {
            long[] tag = readVarint(msg, i);
            int field = (int) (tag[0] >>> 3), wire = (int) (tag[0] & 7);
            i = (int) tag[1];
            if (wire == 0) {
                i = (int) readVarint(msg, i)[1];
            } else if (wire == 1) {
                i += 8;
            } else if (wire == 2) {
                long[] l = readVarint(msg, i);
                i = (int) l[1];
                int len = (int) l[0];
                if (field == 1) return new String(msg, i, len, StandardCharsets.UTF_8);
                i += len;
            } else if (wire == 5) {
                i += 4;
            } else {
                return null;
            }
        }
        return null;
    }

    /** 从零生成文本 metadata：固定字段序 + 双 property-files（占位值 → 偏移定长后重渲染） */
    static byte[] genMetadataText(Map<String, String> meta, String propValue) {
        StringBuilder sb = new StringBuilder();
        sb.append("ota-type=AB\n");
        appendMetaLine(sb, "pre-device", meta);
        appendMetaLine(sb, "post-build", meta);
        appendMetaLine(sb, "post-build-incremental", meta);
        appendMetaLine(sb, "post-timestamp", meta);
        appendMetaLine(sb, "post-security-patch-level", meta);
        appendMetaLine(sb, "serialno", meta);
        sb.append("ota-property-files=").append(propValue).append('\n');
        sb.append("ota-streaming-property-files=").append(propValue).append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendMetaLine(StringBuilder sb, String key, Map<String, String> meta) {
        String v = meta.getOrDefault(key, "");
        if (!v.isEmpty()) sb.append(key).append('=').append(v).append('\n');
    }

    /**
     * 从零生成 metadata.pb（OtaMetadata，proto3 语义：默认值不序列化）：
     * type=AB + property_files map（文本元数据全量镜像，recovery 端展开即 key=value）
     * + precondition{device} + postcondition{device,build,incremental,timestamp,spl}。
     */
    static byte[] genMetadataPb(Map<String, String> meta, String propValue) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(varint((1L << 3) | 0)); o.writeBytes(varint(1));   // type = AB(1)
        LinkedHashMap<String, String> kv = new LinkedHashMap<>();
        kv.put("ota-type", "AB");
        for (String k : new String[]{"pre-device", "post-build", "post-build-incremental",
                "post-timestamp", "post-security-patch-level", "serialno"}) {
            String v = meta.getOrDefault(k, "");
            if (!v.isEmpty()) kv.put(k, v);
        }
        kv.put("ota-property-files", propValue);
        kv.put("ota-streaming-property-files", propValue);
        for (Map.Entry<String, String> e : kv.entrySet())
            writeLd(o, 4, keyValuePair(e.getKey(), e.getValue()));
        String device = meta.getOrDefault("pre-device", "");
        writeLd(o, 5, deviceState(new String[]{device}, null, null, 0, null));
        writeLd(o, 6, deviceState(new String[]{device},
                splitValues(meta.get("post-build")),
                meta.getOrDefault("post-build-incremental", ""),
                parseLongOr(meta.get("post-timestamp"), 0),
                meta.getOrDefault("post-security-patch-level", "")));
        return o.toByteArray();
    }

    /** proto3 map 条目 wire 格式：KeyValue{ 1:key, 2:value } */
    private static byte[] keyValuePair(String key, String value) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        writeLd(o, 1, key.getBytes(StandardCharsets.UTF_8));
        writeLd(o, 2, value.getBytes(StandardCharsets.UTF_8));
        return o.toByteArray();
    }

    /** DeviceState（proto3：空串/0 不序列化） */
    private static byte[] deviceState(String[] devices, String[] builds,
                                      String incremental, long timestamp, String spl) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        if (devices != null)
            for (String d : devices)
                if (d != null && !d.isEmpty())
                    writeLd(o, 1, d.getBytes(StandardCharsets.UTF_8));
        if (builds != null)
            for (String b : builds)
                if (b != null && !b.isEmpty())
                    writeLd(o, 2, b.getBytes(StandardCharsets.UTF_8));
        if (incremental != null && !incremental.isEmpty())
            writeLd(o, 3, incremental.getBytes(StandardCharsets.UTF_8));
        if (timestamp > 0) {
            o.writeBytes(varint((4L << 3) | 0));
            o.writeBytes(varint(timestamp));
        }
        if (spl != null && !spl.isEmpty())
            writeLd(o, 6, spl.getBytes(StandardCharsets.UTF_8));
        return o.toByteArray();
    }

    /** length-delimited 字段写出 */
    private static void writeLd(ByteArrayOutputStream o, long field, byte[] data) {
        o.writeBytes(varint((field << 3) | 2));
        o.writeBytes(varint(data.length));
        o.writeBytes(data);
    }

    private static String[] splitValues(String v) {
        return v == null || v.isEmpty() ? new String[0] : new String[]{v};
    }

    private static long parseLongOr(String v, long def) {
        try {
            return v == null || v.isEmpty() ? def : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String placeholderValue() {
        return padValue("payload.bin:0:0,payload_properties.txt:0:0,metadata:0:0,metadata.pb:0:0");
    }
    private static String padValue(String v) {
        if (v.length() >= PROP_VALUE_PAD) return v;
        StringBuilder sb = new StringBuilder(PROP_VALUE_PAD);
        sb.append(v);
        for (int i = v.length(); i < PROP_VALUE_PAD; i++) sb.append(' ');
        return sb.toString();
    }

    // ==================== metadata 文本 / pb 补丁 ====================

    static byte[] patchMetadataText(byte[] orig, String value) {
        String text = orig == null
                ? "ota-type=AB\npre-device=android\n"
                : new String(orig, StandardCharsets.UTF_8);
        boolean hasProp = false, hasStream = false;
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            if (line.startsWith("ota-property-files=")) { sb.append("ota-property-files=").append(value); hasProp = true; }
            else if (line.startsWith("ota-streaming-property-files=")) { sb.append("ota-streaming-property-files=").append(value); hasStream = true; }
            else if (!line.isEmpty()) sb.append(line);
            else continue;
            sb.append('\n');
        }
        if (!hasProp) sb.append("ota-property-files=").append(value).append('\n');
        if (!hasStream) sb.append("ota-streaming-property-files=").append(value).append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * metadata.pb 补丁：顶层遍历 protobuf TLV，field 4（KeyValue{1:key,2:value}）
     * 的 ota-property-files / ota-streaming-property-files 值替换，其余字节原样保留。
     */
    static byte[] patchMetadataPb(byte[] orig, String normalValue, String streamingValue) {
        if (orig == null || orig.length == 0) return new byte[]{};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = 0;
        while (i < orig.length) {
            long[] tag = readVarint(orig, i);
            int field = (int) (tag[0] >>> 3), wire = (int) (tag[0] & 7);
            i = (int) tag[1];
            if (wire == 0) {
                long[] v = readVarint(orig, i);
                out.writeBytes(varint(tag[0])); out.writeBytes(varint(v[0]));
                i = (int) v[1];
            } else if (wire == 2) {
                long[] l = readVarint(orig, i);
                i = (int) l[1];
                int len = (int) l[0];
                byte[] blob = java.util.Arrays.copyOfRange(orig, i, i + len);
                i += len;
                if (field == 4) {
                    byte[] patched = patchKeyValue(blob, normalValue, streamingValue);
                    if (patched != null) blob = patched;
                }
                out.writeBytes(varint(tag[0])); out.writeBytes(varint(blob.length)); out.writeBytes(blob);
            } else if (wire == 5) {
                out.writeBytes(varint(tag[0])); out.write(orig, i, 4); i += 4;
            } else if (wire == 1) {
                out.writeBytes(varint(tag[0])); out.write(orig, i, 8); i += 8;
            } else {
                throw new IllegalArgumentException("unsupported protobuf wire " + wire);
            }
        }
        return out.toByteArray();
    }

    /** KeyValue 子消息：key(1)=ota-property-files/… → value(2) 替换；其他原样 */
    private static byte[] patchKeyValue(byte[] blob, String normalValue, String streamingValue) {
        String key = null;
        int i = 0;
        while (i < blob.length) {
            long[] tag = readVarint(blob, i);
            int field = (int) (tag[0] >>> 3), wire = (int) (tag[0] & 7);
            i = (int) tag[1];
            if (wire != 2) return null;
            long[] l = readVarint(blob, i);
            i = (int) l[1];
            int len = (int) l[0];
            if (field == 1)
                key = new String(blob, i, len, StandardCharsets.UTF_8);
            i += len;
        }
        String repl;
        if ("ota-property-files".equals(key)) repl = normalValue;
        else if ("ota-streaming-property-files".equals(key)) repl = streamingValue;
        else return null;
        byte[] keyB = key.getBytes(StandardCharsets.UTF_8);
        byte[] valB = repl.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(varint((1L << 3) | 2)); o.writeBytes(varint(keyB.length)); o.writeBytes(keyB);
        o.writeBytes(varint((2L << 3) | 2)); o.writeBytes(varint(valB.length)); o.writeBytes(valB);
        return o.toByteArray();
    }

    static long[] readVarint(byte[] b, int off) {
        long v = 0; int shift = 0, p = off;
        while (true) {
            if (p >= b.length) throw new IllegalArgumentException("varint overflow");
            byte x = b[p++];
            v |= (long) (x & 0x7f) << shift;
            if ((x & 0x80) == 0) break;
            shift += 7;
        }
        return new long[]{v, p};
    }
    static byte[] varint(long v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        while ((v & ~0x7fL) != 0) { o.write((int) ((v & 0x7f) | 0x80)); v >>>= 7; }
        o.write((int) v);
        return o.toByteArray();
    }

    // ==================== 手写 zip（zip64 感知） ====================

    private static final int DOS_TIME = 0, DOS_DATE = ((2024 - 1980) << 9) | (1 << 5) | 1;

    private static final class CdRec {
        String name; int method; long crc, comp, uncomp, offset; boolean zip64;
    }

    private static byte[] nameBytes(String name) { return name.getBytes(StandardCharsets.UTF_8); }

    private static void writeEntry(RandomAccessFile raf, List<CdRec> cd, String name,
                                   byte[] compOrRaw, int method, long crc,
                                   long uncomp, long comp) throws IOException {
        byte[] nb = nameBytes(name);
        boolean utf8 = !name.chars().allMatch(c -> c < 0x80);
        int flags = utf8 ? 0x0800 : 0;
        boolean z64 = uncomp > 0xFFFFFFFEL || comp > 0xFFFFFFFEL;
        long headerStart = raf.length();
        raf.seek(headerStart);
        ByteArrayOutputStream h = new ByteArrayOutputStream();
        h.writeBytes(int32(0x04034b50));
        h.writeBytes(int16(z64 ? 45 : (method == 8 ? 20 : 10)));
        h.writeBytes(int16(flags));
        h.writeBytes(int16(method));
        h.writeBytes(int16(DOS_TIME)); h.writeBytes(int16(DOS_DATE));
        h.writeBytes(int32((int) crc));
        if (z64) {
            h.writeBytes(int32(0xFFFFFFFF)); h.writeBytes(int32(0xFFFFFFFF));
            h.writeBytes(int16(nb.length));
            h.writeBytes(int16(20));   // zip64 extra: uncomp(8) + comp(8)
            h.writeBytes(int64(uncomp)); h.writeBytes(int64(comp));
        } else {
            h.writeBytes(int32((int) comp)); h.writeBytes(int32((int) uncomp));
            h.writeBytes(int16(nb.length)); h.writeBytes(int16(0));
        }
        h.writeBytes(nb);
        raf.write(h.toByteArray());
        raf.write(compOrRaw);
        CdRec r = new CdRec();
        r.name = name; r.method = method; r.crc = crc; r.comp = comp; r.uncomp = uncomp;
        r.offset = headerStart; r.zip64 = z64;
        cd.add(r);
    }

    /** payload.bin：STORED 流式写入，CRC 单遍计算后回填本地头（避免二次读 13G） */
    private static void writePayload(RandomAccessFile raf, List<CdRec> cd, String name,
                                     File payload, long size, ProgressFn progress) throws IOException {
        byte[] nb = nameBytes(name);
        boolean z64 = size > 0xFFFFFFFEL;
        long headerStart = raf.length();
        raf.seek(headerStart);
        ByteArrayOutputStream h = new ByteArrayOutputStream();
        h.writeBytes(int32(0x04034b50));
        h.writeBytes(int16(z64 ? 45 : 10));
        h.writeBytes(int16(0));
        h.writeBytes(int16(0));   // STORED
        h.writeBytes(int16(DOS_TIME)); h.writeBytes(int16(DOS_DATE));
        h.writeBytes(int32(0));   // CRC 占位，写完数据回填
        if (z64) {
            h.writeBytes(int32(0xFFFFFFFF)); h.writeBytes(int32(0xFFFFFFFF));
            h.writeBytes(int16(nb.length)); h.writeBytes(int16(20));
            h.writeBytes(int64(size)); h.writeBytes(int64(size));
        } else {
            h.writeBytes(int32((int) size)); h.writeBytes(int32((int) size));
            h.writeBytes(int16(nb.length)); h.writeBytes(int16(0));
        }
        h.writeBytes(nb);
        raf.write(h.toByteArray());
        CRC32 crc = new CRC32();
        try (FileInputStream in = new FileInputStream(payload)) {
            byte[] buf = new byte[1024 * 1024];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                raf.write(buf, 0, n);
                crc.update(buf, 0, n);
                done += n;
                if (progress != null && (done & ~0x3FFFFFFL) != ((done - n) & ~0x3FFFFFFL))
                    progress.onProgress(done, size);   // 每 64M 汇报一次
            }
            if (done != size) throw new IOException("payload 尺寸变化（写入期间被修改？）");
        }
        if (progress != null) progress.onProgress(size, size);
        long end = raf.length();
        raf.seek(headerStart + 14);
        raf.write(int32((int) crc.getValue()));   // 回填 CRC
        raf.seek(end);
        CdRec r = new CdRec();
        r.name = name; r.method = 0; r.crc = crc.getValue(); r.comp = size; r.uncomp = size;
        r.offset = headerStart; r.zip64 = z64;
        cd.add(r);
    }

    private static void writeCentralDirectory(RandomAccessFile raf, List<CdRec> cd) throws IOException {
        long cdStart = raf.length();
        raf.seek(cdStart);
        for (CdRec r : cd) {
            byte[] nb = nameBytes(r.name);
            boolean offZ64 = r.offset > 0xFFFFFFFEL;
            boolean sizeZ64 = r.uncomp > 0xFFFFFFFEL || r.comp > 0xFFFFFFFEL;
            boolean anyZ64 = r.zip64 || offZ64;
            ByteArrayOutputStream h = new ByteArrayOutputStream();
            h.writeBytes(int32(0x02014b50));
            h.writeBytes(int16((3 << 8) | (anyZ64 ? 45 : 20)));
            h.writeBytes(int16(anyZ64 ? 45 : (r.method == 8 ? 20 : 10)));
            h.writeBytes(int16(0));
            h.writeBytes(int16(r.method));
            h.writeBytes(int16(DOS_TIME)); h.writeBytes(int16(DOS_DATE));
            h.writeBytes(int32((int) r.crc));
            if (anyZ64) { h.writeBytes(int32(0xFFFFFFFF)); h.writeBytes(int32(0xFFFFFFFF)); }
            else { h.writeBytes(int32((int) r.comp)); h.writeBytes(int32((int) r.uncomp)); }
            h.writeBytes(int16(nb.length));
            ByteArrayOutputStream extra = new ByteArrayOutputStream();
            if (anyZ64) {
                int fields = (sizeZ64 ? 16 : 0) + (offZ64 ? 8 : 0);
                extra.writeBytes(int16(0x0001)); extra.writeBytes(int16(fields));
                if (sizeZ64) { extra.writeBytes(int64(r.uncomp)); extra.writeBytes(int64(r.comp)); }
                if (offZ64) extra.writeBytes(int64(r.offset));
            }
            h.writeBytes(int16(extra.size()));
            h.writeBytes(int16(0));   // comment
            h.writeBytes(int16(0));   // disk number start
            h.writeBytes(int16(0));   // internal attrs
            h.writeBytes(int32(1 << 16));   // external: 0644
            h.writeBytes(int32(offZ64 ? 0xFFFFFFFF : (int) r.offset));
            h.writeBytes(nb);
            h.writeBytes(extra.toByteArray());
            raf.write(h.toByteArray());
        }
        long cdSize = raf.length() - cdStart;
        boolean zip64Eocd = cdStart > 0xFFFFFFFEL || cdSize > 0xFFFFFFFEL || cd.size() > 0xFFFF;
        if (zip64Eocd) {
            long zip64EocdOff = raf.length();
            ByteArrayOutputStream z = new ByteArrayOutputStream();
            z.writeBytes(int32(0x06064b50));
            z.writeBytes(int64(44));
            z.writeBytes(int16(45)); z.writeBytes(int16(45));
            z.writeBytes(int16(0)); z.writeBytes(int16(0));
            z.writeBytes(int64(cd.size())); z.writeBytes(int64(cd.size()));
            z.writeBytes(int64(cdSize)); z.writeBytes(int64(cdStart));
            raf.write(z.toByteArray());
            ByteArrayOutputStream l = new ByteArrayOutputStream();
            l.writeBytes(int32(0x07064b50));
            l.writeBytes(int32(0)); l.writeBytes(int64(zip64EocdOff)); l.writeBytes(int32(1));
            raf.write(l.toByteArray());
        }
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        e.writeBytes(int32(0x06054b50));
        e.writeBytes(int16(0)); e.writeBytes(int16(0));
        e.writeBytes(int16(Math.min(cd.size(), 0xFFFF)));
        e.writeBytes(int16(Math.min(cd.size(), 0xFFFF)));
        e.writeBytes(int32((int) Math.min(cdSize, 0xFFFFFFFEL)));
        e.writeBytes(int32((int) Math.min(cdStart, 0xFFFFFFFEL)));
        e.writeBytes(int16(0));   // 注释长度 0 —— whole-file 签名前占位
        raf.write(e.toByteArray());
    }

    // ==================== 小工具 ====================

    static byte[] int32(int v) { return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)}; }
    static byte[] int16(int v) { return new byte[]{(byte) v, (byte) (v >> 8)}; }
    static byte[] int64(long v) {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) b[i] = (byte) (v >> (8 * i));
        return b;
    }
    static long crc32Of(byte[] b) { CRC32 c = new CRC32(); c.update(b); return c.getValue(); }
    static byte[] deflate(byte[] raw) {
        // nowrap=true：zip 条目数据区是裸 deflate 流（无 zlib 头/尾，APPNOTE 4.4.5）
        Deflater d = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        d.setInput(raw); d.finish();
        ByteArrayOutputStream o = new ByteArrayOutputStream(raw.length / 2 + 16);
        byte[] buf = new byte[65536];
        while (!d.finished()) { int n = d.deflate(buf); if (n > 0) o.write(buf, 0, n); }
        d.end();
        return o.toByteArray();
    }
    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        in.close();
        return o.toByteArray();
    }
}
