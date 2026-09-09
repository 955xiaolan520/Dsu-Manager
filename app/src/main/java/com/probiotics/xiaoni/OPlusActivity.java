package com.probiotics.xiaoni;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;

public final class OPlusActivity extends Activity {
    private static final String[] REGIONS = {"cn", "cn_cmcc", "in", "eu", "sg", "ru", "tr", "th", "gl", "tw", "my", "vn", "id", "sa", "mea", "ph", "la", "br", "roe"};
    private static final String[] REGION_NAMES = {"中国大陆 CN", "中国移动 CN CMCC", "印度 IN", "欧洲 EU", "新加坡 SG", "俄罗斯 RU", "土耳其 TR", "泰国 TH", "全球 GL", "中国台湾 TW", "马来西亚 MY", "越南 VN", "印度尼西亚 ID", "沙特 SA", "中东和非洲 MEA", "菲律宾 PH", "拉丁美洲 LA", "巴西 BR", "欧洲其他 ROE"};
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditText deviceInput;
    private Spinner regionSpinner;
    private TextView status;
    private LinearLayout results;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(28));
        root.setBackgroundResource(com.probiotics.xiaoni.R.drawable.liquid_backdrop);
        LinearLayout title = new LinearLayout(this);
        Button back = new Button(this);
        back.setText("返回");
        back.setOnClickListener(v -> finish());
        title.addView(back, new LinearLayout.LayoutParams(dp(72), dp(48)));
        TextView heading = text("OPlus OTA 查询", 24, Color.WHITE);
        heading.setGravity(android.view.Gravity.CENTER_VERTICAL);
        title.addView(heading, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(title);
        root.addView(text("OPPO / 一加 / 真我 · ColorOS 官方更新", 13, 0xffe5edf7));

        deviceInput = new EditText(this);
        deviceInput.setHint("设备 OTA 前缀，例如 CPH2581");
        deviceInput.setSingleLine(true);
        root.addView(deviceInput, marginParams(-1, 52, 14));
        regionSpinner = new Spinner(this);
        regionSpinner.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, REGION_NAMES));
        root.addView(regionSpinner, marginParams(-1, 52, 14));
        Button query = new Button(this);
        query.setText("查询官方更新");
        query.setAllCaps(false);
        query.setOnClickListener(v -> query());
        root.addView(query, marginParams(-1, 50, 10));
        status = text("请输入设备 OTA 前缀", 14, 0xffdbe7f5);
        root.addView(status, marginParams(-1, -2, 4));
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        root.addView(results, new LinearLayout.LayoutParams(-1, -2));
        TextView source = text("协议来源: github.com/JerryTse-OSS/OPlus-Tracker · LGPL v3", 11, 0xffafbdd0);
        root.addView(source, marginParams(-1, -2, 0));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void query() {
        String prefix = deviceInput.getText().toString().trim().toUpperCase();
        if (prefix.isEmpty()) { status.setText("请输入设备 OTA 前缀"); return; }
        String region = REGIONS[regionSpinner.getSelectedItemPosition()];
        status.setText("正在查询 " + region.toUpperCase() + "...");
        results.removeAllViews();
        executor.execute(() -> {
            try {
                JSONObject body = OPlusClient.query(prefix, region);
                runOnUiThread(() -> render(body));
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("查询失败: " + readable(e)));
            }
        });
    }

    private void render(JSONObject body) {
        try {
            status.setText("查询成功");
            addInfo("版本", body.optString("version", "未知"));
            addInfo("OTA 版本", body.optString("otaVersion", "未知"));
            addInfo("发布时间", body.optString("publishedTime", "未知"));
            addInfo("安全补丁", body.optString("securityPatch", "未知"));
            JSONArray components = body.optJSONArray("components");
            if (components == null || components.length() == 0) { addInfo("结果", "当前地区没有可用组件"); return; }
            for (int i = 0; i < components.length(); i++) {
                JSONObject component = components.getJSONObject(i);
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(14), dp(12), dp(14), dp(12));
                card.setBackgroundResource(com.probiotics.xiaoni.R.drawable.liquid_glass_panel);
                card.addView(text(component.optString("name", "系统组件"), 17, 0xff172b4d));
                card.addView(text("版本: " + component.optString("version", "未知") + "\n大小: " + component.optString("size", "未知") + "\nMD5: " + component.optString("md5", "未知"), 13, 0xff5e6c83));
                Button download = new Button(this);
                download.setText("下载此组件");
                download.setAllCaps(false);
                String url = component.optString("link", "");
                download.setEnabled(url.startsWith("http"));
                download.setOnClickListener(v -> startDownload(url, component.optString("name", "oplus-update") + ".zip"));
                card.addView(download, marginParams(-1, 46, 8));
                results.addView(card, marginParams(-1, -2, 10));
            }
            String changelog = body.optString("changelog", "");
            if (!changelog.isEmpty() && !"N/A".equals(changelog)) addInfo("更新说明", changelog);
        } catch (Exception e) { status.setText("结果解析失败: " + readable(e)); }
    }

    private void startDownload(String address, String filename) {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DsuManager");
        if (!dir.exists()) dir.mkdirs();
        File output = new File(dir, filename.replaceAll("[^a-zA-Z0-9._-]", "_"));
        Intent intent = new Intent(this, DownloadService.class).setAction(DownloadService.ACTION_START)
                .putExtra(DownloadService.EXTRA_ADDRESS, address)
                .putExtra(DownloadService.EXTRA_OUTPUT, output.getAbsolutePath())
                .putExtra(DownloadService.EXTRA_PACKAGE, "OPlus OTA")
                .putExtra(DownloadService.EXTRA_OPLUS, true);
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
        status.setText("下载已开始: " + output.getName());
    }

    private void addInfo(String key, String value) { results.addView(text(key + ": " + value, 14, 0xffe5edf7), marginParams(-1, -2, 6)); }
    private TextView text(String value, int size, int color) { TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setPadding(0, dp(4), 0, dp(4)); return v; }
    private LinearLayout.LayoutParams marginParams(int w, int h, int bottom) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h < 0 ? h : dp(h)); p.setMargins(0, 0, 0, dp(bottom)); return p; }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private String readable(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }

    private static final class OPlusClient {
        private static final String CN_KEY = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEApXYGXQpNL7gmMzzvajHaoZIHQQvBc2cOEhJc7/tsaO4sT0unoQnwQKfNQCuv7qC1Nu32eCLuewe9LSYhDXr9KSBWjOcCFXVXteLO9WCaAh5hwnUoP/5/Wz0jJwBA+yqs3AaGLA9wJ0+B2lB1vLE4FZNE7exUfwUc03fJxHG9nCLKjIZlrnAAHjRCd8mpnADwfkCEIPIGhnwq7pdkbamZcoZfZud1+fPsELviB9u447C6bKnTU4AaMcR9Y2/uI6TJUTcgyCp+ilgU0JxemrSIPFk3jbCbzamQ6Shkw/jDRzYoXpBRg/2QDkbq+j3ljInu0RHDfOeXf3VBfHSnQ66HCwIDAQAB";
        private static final String SG_KEY = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAkA980wxi+eTGcFDiw2I6RrUeO4jL/Aj3Yw4dNuW7tYt+O1sRTHgrzxPD9SrOqzz7G0KgoSfdFHe3JVLPN+U1waK+T0HfLusVJshDaMrMiQFDUiKajb+QKr+bXQhVofH74fjat+oRJ8vjXARSpFk/41x5j1Bt/2bHoqtdGPcUizZ4whMwzap+hzVlZgs7BNfepo24PWPRujsN3uopl+8u4HFpQDlQl7GdqDYDjz2NOHdFQI2UpSf0aIeKCKOpSKF72KDEESpJVQsqO4nxMwEi2jMujQeCHyTCjBZ+W35RzwT9+0pyZv8FB3c7FYY9FdF/+lvfax5mvFEBd9jO+dpMQIDAQAB";
        private static final String EU_KEY = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAh8/EThsK3f0WyyPgrtXb/D0Xni6UZNppaQHUqHWo976cybl92VxmehE0ISObnxERaOtrlYmTPIxkVC9MMueDvTwZ1l0KxevZVKU0sJRxNR9AFcw6D7k9fPzzpNJmhSlhpNbt3BEepdgibdRZbacF3NWy3ejOYWHgxC+I/Vj1v7QU5gD+1OhgWeRDcwuV4nGY1ln2lvkRj8EiJYXfkSq/wUI5AvPdNXdEqwou4FBcf6mD84G8pKDyNTQwwuk9lvFlcq4mRqgYaFg9DAgpDgqV4NTJWM7tQS1GZuRA6PhupfDqnQExyBFhzCefHkEhcFywNyxlPe953NWLFWwbGvFKwIDAQAB";
        private static final String IN_KEY = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwYtghkzeStC9YvAwOQmWylbp74Tj8hhi3f9IlK7A/CWrGbLgzz/BeKxNb45zBN8pgaaEOwAJ1qZQV5G4nProWCPOP1ro1PkemFvw/vzOOT5uN0ADnHDzZkZXCU/knxqUSfLcwQlHXsYhNsAm7uOKjY9YXF4zWzYN0eFPkML3Pj/zg7hl/ov9clB2VeyI1/blMHFfcNA/fvqDTENXcNBIhgJvXiCpLcZqp+aLZPC5AwY/sCb3j5jTWer0Rk0ZjQBZE1AncwYvUx4mA65U59cWpTyl4c47J29MsQ66hqWv6eBHlDNZSEsQpHePUqgsf7lmO5Wd7teB8ugQki2oz1Y5QIDAQAB";
        static JSONObject query(String prefix, String region) throws Exception {
            String base = prefix.split("_")[0];
            String model = region.equals("eu") ? base + "EEA" : (region.equals("ru") || region.equals("tr") ? base + region.toUpperCase() : base);
            String host = region.equals("cn") || region.equals("cn_cmcc") ? "component-ota-cn.allawntech.com" : region.equals("eu") ? "component-ota-eu.allawnos.com" : region.equals("in") ? "component-ota-in.allawnos.com" : "component-ota-sg.allawnos.com";
            String language = language(region), carrier = carrier(region), key = region.equals("cn") || region.equals("cn_cmcc") ? CN_KEY : region.equals("eu") ? EU_KEY : region.equals("in") ? IN_KEY : SG_KEY;
            byte[] aes = random(32), iv = random(16), device = UUID.randomUUID().toString().replace("-", "").toUpperCase().getBytes("UTF-8");
            JSONObject request = new JSONObject().put("mode", "0").put("time", System.currentTimeMillis()).put("isRooted", "0").put("isLocked", true).put("type", "0").put("deviceId", "0000000000000000000000000000000000000000000000000000000000000000").put("opex", new JSONObject().put("check", true));
            String protectedKey = Base64.getEncoder().encodeToString(rsa(Base64.getEncoder().encode(aes), key));
            JSONObject headers = new JSONObject().put("language", language).put("newLanguage", language).put("androidVersion", "unknown").put("colorOSVersion", "unknown").put("romVersion", "unknown").put("infVersion", "1").put("otaVersion", prefix + ".01_0001_197001010000").put("model", model).put("mode", "manual").put("nvCarrier", carrier).put("pipelineKey", "ALLNET").put("operator", "ALLNET").put("companyId", "").put("version", "2").put("deviceId", new String(device, "UTF-8")).put("Content-Type", "application/json; charset=utf-8").put("protectedKey", new JSONObject().put("SCENE_1", new JSONObject().put("protectedKey", protectedKey).put("version", System.currentTimeMillis() + 86400000L).put("negotiationVersion", "1615879139745")));
            JSONObject payload = new JSONObject().put("params", new JSONObject().put("cipher", Base64.getEncoder().encodeToString(crypt(true, request.toString().getBytes("UTF-8"), aes, iv))).put("iv", Base64.getEncoder().encodeToString(iv)).toString());
            HttpURLConnection connection = (HttpURLConnection) new URL("https://" + host + "/update/v3").openConnection();
            connection.setRequestMethod("POST"); connection.setConnectTimeout(15000); connection.setReadTimeout(30000); connection.setDoOutput(true); 
            java.util.Iterator<String> headerNames = headers.keys();
            while (headerNames.hasNext()) { String name = headerNames.next(); connection.setRequestProperty(name, headers.getString(name)); }
            connection.getOutputStream().write(payload.toString().getBytes("UTF-8"));
            JSONObject envelope = new JSONObject(read(connection.getInputStream()));
            if (envelope.optInt("responseCode") != 200) throw new IllegalStateException("服务返回 " + envelope.optInt("responseCode"));
            JSONObject encrypted = new JSONObject(envelope.getString("body"));
            return parse(new String(crypt(false, Base64.getDecoder().decode(encrypted.getString("cipher")), aes, Base64.getDecoder().decode(encrypted.getString("iv"))), "UTF-8"));
        }
        private static JSONObject parse(String raw) throws Exception { JSONObject b = new JSONObject(raw), out = new JSONObject(); out.put("version", b.optString("realVersionName", b.optString("versionName", "N/A"))).put("otaVersion", b.optString("realOtaVersion", b.optString("otaVersion", "N/A"))).put("securityPatch", b.optString("securityPatch", "N/A")).put("publishedTime", b.optLong("publishedTime", 0)); JSONArray list = new JSONArray(); JSONArray components = b.optJSONArray("components"); if (components != null) for (int i = 0; i < components.length(); i++) { JSONObject c = components.getJSONObject(i), p = c.optJSONObject("componentPackets"); if (p == null) continue; list.put(new JSONObject().put("name", c.optString("componentName", "Unknown")).put("version", c.optString("componentVersion", "Unknown")).put("link", p.optString("manualUrl", p.optString("url", ""))).put("size", p.optString("size", "N/A")).put("md5", p.optString("md5", "N/A"))); } out.put("components", list); return out; }
        private static byte[] crypt(boolean encrypt, byte[] input, byte[] key, byte[] iv) throws Exception { Cipher c = Cipher.getInstance("AES/CTR/NoPadding"); c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv)); return c.doFinal(input); }
        private static byte[] rsa(byte[] input, String b64) throws Exception { byte[] der = Base64.getDecoder().decode(b64); PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der)); Cipher c = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding"); c.init(Cipher.ENCRYPT_MODE, key); return c.doFinal(input); }
        private static byte[] random(int size) { byte[] b = new byte[size]; new java.security.SecureRandom().nextBytes(b); return b; }
        private static String read(InputStream in) throws Exception { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); return out.toString("UTF-8"); }
        private static String language(String r) { Map<String,String> m = new LinkedHashMap<>(); m.put("cn", "zh-CN"); m.put("cn_cmcc", "zh-CN"); m.put("eu", "en-GB"); m.put("in", "en-IN"); m.put("sg", "en-SG"); m.put("ru", "ru-RU"); m.put("tr", "tr-TR"); m.put("th", "th-TH"); m.put("gl", "en-US"); m.put("tw", "zh-TW"); m.put("my", "ms-MY"); m.put("vn", "vi-VN"); m.put("id", "id-ID"); m.put("sa", "sa-SA"); m.put("mea", "en-MEA"); m.put("ph", "en-PH"); m.put("la", "en-LA"); m.put("br", "en-BR"); m.put("roe", "en-EU"); return m.get(r); }
        private static String carrier(String r) { String[] values = {"10010111","10011000","00011011","01000100","01011010","00110111","01010001","00111001","10100111","00011010","00111000","00111100","00110011","10000011","10100110","00111110","10011010","10011110","10001101"}; for (int i = 0; i < REGIONS.length; i++) if (REGIONS[i].equals(r)) return values[i]; return "01011010"; }
    }
}
