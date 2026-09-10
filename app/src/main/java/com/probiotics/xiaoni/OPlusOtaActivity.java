package com.probiotics.xiaoni;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.math.BigInteger;
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** OPlus 官方更新查询：全量 OTA / 增量包 / 降级包 / Realme EDL，四种协议各自独立表单。 */
public final class OPlusOtaActivity extends Activity {
    private static final String[] REGION_CODES = {"auto", "cn", "cn_cmcc", "in", "eu", "sg", "ru", "tr", "th", "gl", "tw", "my", "vn", "id", "sa", "mea", "ph", "la", "br", "roe"};
    private static final String[] REGION_LABELS = {"通用地区（自动匹配全部地区）", "中国大陆 CN", "中国移动 CN CMCC", "印度 IN", "欧洲 EU", "新加坡 SG", "俄罗斯 RU", "土耳其 TR", "泰国 TH", "全球海外 GL", "中国台湾 TW", "马来西亚 MY", "越南 VN", "印度尼西亚 ID", "沙特 SA", "中东和非洲 MEA", "菲律宾 PH", "拉丁美洲 LA", "巴西 BR", "欧洲其他 ROE"};
    private static final String[] EDL_REGIONS = {"中国大陆 (domestic)", "欧盟 / 英国 (GDPR)", "其他地区 (export)"};
    private static final String[] TAB_LABELS = {"全量包 OTA", "增量包", "降级包", "EDL"};
    private static final String[] TAB_HINTS = {"输入 OTA 前缀与地区，查询官方全量系统包", "输入当前完整版本与起始组件，查询官方增量包", "输入版本、PrjNum 与 DUID，查询官方降级包", "输入 Realme 版本名与日期前缀，查询 EDL 刷机包"};
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Button[] typeTabs;
    private EditText fullPrefix;
    private Spinner fullRegion;
    private CheckBox fullAnti;
    private CheckBox fullGray;
    private CheckBox fullPreview;
    private EditText fullGuid;
    private EditText deltaPrefix;
    private EditText deltaComponents;
    private Spinner deltaRegion;
    private EditText downOta;
    private EditText downPrj;
    private EditText downDuid;
    private CheckBox downCmcc;
    private EditText edlVersion;
    private Spinner edlRegion;
    private EditText edlDate;
    private LinearLayout[] panels;
    private TextView status;
    private LinearLayout results;
    private LinearLayout tabItems;
    private LiquidGlassIndicator tabIndicator;
    private int tabIndicatorLeft = -1;
    private android.animation.ValueAnimator tabPulse;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean tabDragging;
    private int tabDragTarget = -1;
    private float tabDragStartX;
    private Runnable tabLongPress;
    private LinearLayout downloadCard;
    private TextView downloadFileTitle;
    private TextView downloadStatus;
    private TextView downloadPercent;
    private TextView downloadBytes;
    private TextView downloadPath;
    private ProgressBar downloadProgress;
    private Button pauseDownloadButton;
    private Button cancelDownloadButton;
    private boolean downloadPaused;
    private long speedWindowBytes;
    private long speedWindowTime;
    private long smoothedSpeed;
    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!DownloadService.ACTION_UPDATE.equals(intent.getAction())) return;
            String state = intent.getStringExtra(DownloadService.EXTRA_STATE);
            long done = intent.getLongExtra(DownloadService.EXTRA_DONE, -1);
            long total = intent.getLongExtra(DownloadService.EXTRA_TOTAL, -1);
            if (state != null) {
                downloadStatus.setText(state);
                if ("下载完成".equals(state) || state.startsWith("下载失败") || state.startsWith("下载地址无效")) {
                    speedWindowBytes = 0;
                    speedWindowTime = 0;
                    smoothedSpeed = 0;
                }
            }
            if (done >= 0) updateDownloadProgress(done, total);
            if ("下载完成".equals(state)) {
                downloadProgress.setProgress(100);
                downloadPercent.setText("100%");
                pauseDownloadButton.setVisibility(View.GONE);
                cancelDownloadButton.setText("关闭");
            } else if (state != null && state.startsWith("下载失败")) {
                pauseDownloadButton.setText("重试");
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0x00000000);
        getWindow().setNavigationBarColor(0x220b131f);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        buildUi();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        Haptics.onTouch(getWindow().getDecorView(), event);
        return super.dispatchTouchEvent(event);
    }

    private void buildUi() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(10), dp(18), dp(22));

        LinearLayout title = glass();
        title.setOrientation(LinearLayout.VERTICAL);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(14), dp(8), dp(14), dp(8));
        Button back = button("‹");
        back.setTextSize(32);
        back.setContentDescription("返回");
        back.setOnClickListener(v -> {
            finish();
            overridePendingTransition(R.anim.explode_in, R.anim.explode_out);
        });
        FrameLayout titleRow = new FrameLayout(this);
        LinearLayout titleText = new LinearLayout(this);
        titleText.setOrientation(LinearLayout.VERTICAL);
        TextView heading = label("欧伽真系统查询", 22, 0xff142037);
        heading.setTypeface(null, 1);
        heading.setGravity(Gravity.CENTER);
        titleText.addView(heading, new LinearLayout.LayoutParams(-1, dp(32)));
        TextView vendor = label("OPPO / 一加 / 真我\nColorOS 官方系统更新", 11, 0xff596579);
        vendor.setGravity(Gravity.CENTER);
        titleText.addView(vendor, new LinearLayout.LayoutParams(-1, dp(32)));
        FrameLayout.LayoutParams titleTextLp = new FrameLayout.LayoutParams(-1, dp(64));
        titleRow.addView(titleText, titleTextLp);
        FrameLayout.LayoutParams backLp = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER_VERTICAL | Gravity.LEFT);
        titleRow.addView(back, backLp);
        title.addView(titleRow, new LinearLayout.LayoutParams(-1, dp(64)));
        content.addView(title, margins(-1, 82, 10));

        FrameLayout tabs = new FrameLayout(this);
        tabs.setPadding(dp(2), dp(2), dp(2), dp(2));
        tabs.setBackgroundResource(R.drawable.navigation_glass_bg);
        tabItems = new LinearLayout(this);
        tabItems.setOrientation(LinearLayout.HORIZONTAL);
        tabItems.setGravity(Gravity.CENTER);
        tabItems.setClipChildren(false);
        tabs.addView(tabItems, new FrameLayout.LayoutParams(-1, dp(64)));
        typeTabs = new Button[TAB_LABELS.length];
        for (int i = 0; i < TAB_LABELS.length; i++) {
            final int index = i;
            typeTabs[i] = button(TAB_LABELS[i].replace(" OTA", ""));
            typeTabs[i].setTextSize(12);
            typeTabs[i].setGravity(Gravity.CENTER);
            typeTabs[i].setTextColor(i == 0 ? 0xff17334f : 0xfff8fbff);
            typeTabs[i].setBackgroundColor(Color.TRANSPARENT);
            typeTabs[i].setPadding(0, 0, 0, 0);
            typeTabs[i].setOnTouchListener((v, event) -> handleTabGesture(v, event, index));
            LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(0, dp(64), 1);
            tabLp.setMargins(dp(2), 0, dp(2), 0);
            tabItems.addView(typeTabs[i], tabLp);
        }
        tabIndicator = new LiquidGlassIndicator(this);
        tabIndicator.setElevation(dp(4));
        tabs.addView(tabIndicator, new FrameLayout.LayoutParams(dp(62), dp(48)));
        content.addView(tabs, margins(-1, 68, 10));

        panels = new LinearLayout[TAB_LABELS.length];
        panels[0] = buildFullPanel();
        panels[1] = buildDeltaPanel();
        panels[2] = buildDowngradePanel();
        panels[3] = buildEdlPanel();
        for (LinearLayout panel : panels) content.addView(panel, margins(-1, -2, 10));

        status = label(TAB_HINTS[0], 13, 0xff596579);
        status.setPadding(dp(12), dp(8), dp(12), dp(8));
        content.addView(status, margins(-1, -2, 6));
        buildDownloadCard(content);
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        content.addView(results, new LinearLayout.LayoutParams(-1, -2));
        TextView source = label("协议来源:JerryTse-OSS", 11, 0xff596579);
        source.setPadding(dp(8), dp(12), dp(8), dp(6));
        content.addView(source, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        scroll.addView(content);
        
        FrameLayout root = new FrameLayout(this);
        root.setBackground(createOPlusGradientBackground());
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                view.setPadding(0, bars.top, 0, 0);
            } else {
                view.setPadding(0, insets.getSystemWindowInsetTop(), 0, 0);
            }
            return insets;
        });
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        selectTab(0);
        tabs.post(() -> moveTabIndicator(0, false));
    }
    
    @Override public void onBackPressed() {
        finish();
        overridePendingTransition(R.anim.explode_in, R.anim.explode_out);
    }

    private LinearLayout buildFullPanel() {
        LinearLayout panel = glass();
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(14));
        panel.addView(label("全量 OTA 查询", 17, 0xff20375b), margins(-1, 28, 4));
        panel.addView(label("按设备代号 + 地区查询当前可用的官方全量系统包，自动匹配对应运营商通道。", 12, 0xff596579), margins(-1, -2, 6));
        fullPrefix = field("OTA 前缀（必填）", "设备代号，如 PJX110 或 PJX110_11.A");
        panel.addView(fullPrefix, margins(-1, 50, 8));
        fullRegion = spinner(REGION_LABELS);
        panel.addView(wrappedSpinner("地区（必填）", fullRegion), margins(-1, 62, 8));
        fullAnti = checkBox("防查询模式（taste 请求模式）");
        fullAnti.setChecked(true);
        panel.addView(fullAnti, margins(-1, 38, 0));
        fullGray = checkBox("灰度 / 内测更新（携带 recruitId）");
        panel.addView(fullGray, margins(-1, 38, 0));
        fullPreview = checkBox("预览版更新（使用 v6，需填写 GUID）");
        panel.addView(fullPreview, margins(-1, 38, 4));
        fullGuid = field("GUID（预览版必填）", "64 位十六进制设备 GUID");
        fullGuid.setVisibility(View.GONE);
        fullPreview.setOnCheckedChangeListener((button, checked) -> fullGuid.setVisibility(checked ? View.VISIBLE : View.GONE));
        panel.addView(fullGuid, margins(-1, 50, 8));
        Button query = button("查询官方全量更新");
        query.setOnClickListener(v -> query(0));
        panel.addView(query, margins(-1, 48, 0));
        return panel;
    }

    private LinearLayout buildDeltaPanel() {
        LinearLayout panel = glass();
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(14));
        panel.addView(label("增量包查询", 17, 0xff20375b), margins(-1, 28, 4));
        panel.addView(label("按设备当前完整版本与各组件起始版本请求官方增量包。组件用 名称:版本 填写，多个组件用英文逗号分隔。", 12, 0xff596579), margins(-1, -2, 6));
        deltaPrefix = field("当前完整 OTA 版本（必填）", "如 PJZ110_11.C.36_1360_20250814");
        panel.addView(deltaPrefix, margins(-1, 50, 8));
        deltaComponents = field("起始组件版本（必填）", "system:PJZ110_11.C.35_1300_20250701,vendor:…");
        panel.addView(deltaComponents, margins(-1, 50, 8));
        deltaRegion = spinner(REGION_LABELS);
        panel.addView(wrappedSpinner("地区（必填）", deltaRegion), margins(-1, 62, 8));
        Button query = button("查询官方增量包");
        query.setOnClickListener(v -> query(1));
        panel.addView(query, margins(-1, 48, 0));
        return panel;
    }

    private LinearLayout buildDowngradePanel() {
        LinearLayout panel = glass();
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(14));
        panel.addView(label("降级包查询", 17, 0xff20375b), margins(-1, 28, 4));
        panel.addView(label("查询官方降级包（仅中国大陆）。DUID 在拨号盘输入 *#6776# 获取，为 64 位十六进制字符串。", 12, 0xff596579), margins(-1, -2, 6));
        downOta = field("OTA 版本（必填）", "如 PJZ110 或 PJZ110_11.A，机型输入会自动补全");
        panel.addView(downOta, margins(-1, 50, 8));
        downPrj = field("PrjNum 项目号（必填）", "5 位数字，如 24821");
        panel.addView(downPrj, margins(-1, 50, 8));
        downDuid = field("DUID（必填）", "64 位十六进制，拨号 *#6776# 查看");
        panel.addView(downDuid, margins(-1, 50, 8));
        downCmcc = new CheckBox(this);
        downCmcc.setText("使用中国移动通道 (nvCarrier 10011000)");
        downCmcc.setTextColor(0xff20375b);
        downCmcc.setTextSize(13);
        panel.addView(downCmcc, margins(-1, 44, 8));
        Button query = button("查询官方降级包");
        query.setOnClickListener(v -> query(2));
        panel.addView(query, margins(-1, 48, 0));
        return panel;
    }

    private LinearLayout buildEdlPanel() {
        LinearLayout panel = glass();
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(14));
        panel.addView(label("Realme EDL 查询", 17, 0xff20375b), margins(-1, 28, 4));
        panel.addView(label("按版本名 + 地区 + 发布时间前缀探测 Realme 官方 EDL 救砖包。命中即停，最多探测 10000 个序号。", 12, 0xff596579), margins(-1, -2, 6));
        edlVersion = field("Realme 版本名（必填）", "如 RMX3888_16.0.3.500(CN01)");
        panel.addView(edlVersion, margins(-1, 50, 8));
        edlRegion = spinner(EDL_REGIONS);
        panel.addView(wrappedSpinner("地区（必填）", edlRegion), margins(-1, 62, 8));
        edlDate = field("日期前缀（必填）", "12 位时间戳，如 202601241320");
        panel.addView(edlDate, margins(-1, 50, 8));
        Button query = button("查询 Realme EDL");
        query.setOnClickListener(v -> query(3));
        panel.addView(query, margins(-1, 48, 0));
        return panel;
    }

    private void selectTab(int index) {
        for (int i = 0; i < panels.length; i++) panels[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
        for (int i = 0; i < typeTabs.length; i++) {
            typeTabs[i].setAlpha(i == index ? 1f : .55f);
            typeTabs[i].setTypeface(null, i == index ? 1 : 0);
            typeTabs[i].setTextColor(i == index ? 0xff17334f : 0xff596579);
        }
        moveTabIndicator(index, true);
        results.removeAllViews();
        status.setText(TAB_HINTS[index]);
    }

    private void query(int type) {
        results.removeAllViews();
        switch (type) {
            case 0: queryFull(); break;
            case 1: queryDelta(); break;
            case 2: queryDowngrade(); break;
            case 3: queryEdl(); break;
        }
    }

    private void queryFull() {
        String prefix = fullPrefix.getText().toString().trim();
        if (prefix.isEmpty()) { status.setText("请输入 OTA 前缀，例如 PJX110 或 PJX110_11.A"); return; }
        String region = REGION_CODES[fullRegion.getSelectedItemPosition()];
        boolean preview = fullPreview.isChecked();
        String guid = fullGuid.getText().toString().trim();
        if (preview && !guid.matches("[0-9A-Fa-f]{64}")) { status.setText("预览版需要填写 64 位 GUID"); return; }
        status.setText("auto".equals(region) ? "正在自动匹配全部地区的全量包..." : "正在查询 " + region.toUpperCase() + " 全量包...");
        executor.execute(() -> {
            try {
                JSONArray versions = new JSONArray();
                StringBuilder failures = new StringBuilder();
                String[] suffixes = prefix.indexOf('_') < 0 ? new String[]{"_11.A", "_11.C", "_11.F", "_11.H", "_11.J"} : new String[]{""};
                for (String suffix : suffixes) {
                    String ota = prefix + suffix;
                    if ("auto".equals(region)) {
                        queryAllRegions(ota, "", fullAnti.isChecked(), fullGray.isChecked(), preview ? guid : "", versions, failures,
                                result -> runOnUiThread(() -> renderOta(result)));
                    } else {
                        try {
                            JSONObject result;
                            try {
                                result = queryRegional(ota, region, "", fullAnti.isChecked(), fullGray.isChecked(), preview ? guid : "");
                            } catch (Exception firstError) {
                                if (!fullAnti.isChecked() || !isNoVersion(firstError)) throw firstError;
                                result = queryRegional(ota, region, "", false, fullGray.isChecked(), preview ? guid : "");
                            }
                            versions.put(result);
                        } catch (Exception error) {
                            if (!isNoVersion(error)) {
                                if (failures.length() > 0) failures.append("；");
                                failures.append(suffix.isEmpty() ? "当前版本" : suffix).append(": ").append(readable(error));
                            }
                        }
                    }
                }
                if (versions.length() == 0) {
                    String message = failures.length() == 0
                            ? "该地区暂无匹配的官方版本（服务返回 2004）"
                            : "官方服务未返回可用版本：" + failures;
                    throw new IllegalStateException(message);
                }
                if (!"auto".equals(region)) {
                    runOnUiThread(() -> renderOtaVersions(versions));
                } else {
                    runOnUiThread(() -> status.setText("自动匹配完成，找到 " + versions.length() + " 个地区版本"));
                }
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("查询失败: " + readable(error)));
            }
        });
    }

    private void queryDelta() {
        String prefix = deltaPrefix.getText().toString().trim();
        String components = deltaComponents.getText().toString().trim();
        if (prefix.isEmpty()) { status.setText("请输入当前完整 OTA 版本，例如 PJZ110_11.C.36_1360_20250814"); return; }
        if (components.isEmpty()) { status.setText("请输入起始组件版本，格式 system:完整版本,vendor:完整版本"); return; }
        String region = REGION_CODES[deltaRegion.getSelectedItemPosition()];
        status.setText("auto".equals(region) ? "正在自动匹配全部地区的增量包..." : "正在查询 " + region.toUpperCase() + " 增量包...");
        executor.execute(() -> {
            try {
                if ("auto".equals(region)) {
                    JSONArray matches = new JSONArray();
                    StringBuilder failures = new StringBuilder();
                    queryAllRegions(prefix, components, false, false, "", matches, failures,
                            result -> runOnUiThread(() -> renderOta(result)));
                    if (matches.length() == 0) throw new IllegalStateException("全部公开地区均未返回可用增量包");
                    runOnUiThread(() -> status.setText("自动匹配完成，找到 " + matches.length() + " 个地区版本"));
                } else {
                    JSONObject result = queryRegional(prefix, region, components, false, false, "");
                    final JSONObject resolvedResult = result;
                    runOnUiThread(() -> renderOta(resolvedResult));
                }
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("查询失败: " + readable(error)));
            }
        });
    }

    private void queryDowngrade() {
        String ota = normalizeDowngradeOta(downOta.getText().toString());
        String prj = downPrj.getText().toString().trim();
        String duid = downDuid.getText().toString().trim();
        if (!ota.matches("[A-Za-z0-9]+_11\\.[A-Za-z0-9.]+")) { status.setText("OTA 版本格式应为 PJZ110_11.A"); return; }
        if (!prj.matches("[0-9]{5}")) { status.setText("PrjNum 必须为 5 位数字"); return; }
        if (!duid.matches("[0-9A-Fa-f]{64}")) { status.setText("DUID 必须为 64 位十六进制字符，拨号 *#6776# 获取"); return; }
        status.setText("正在查询官方降级包...");
        executor.execute(() -> {
            try {
                JSONObject result = DowngradeProtocol.query(ota, prj, duid, downCmcc.isChecked());
                runOnUiThread(() -> renderDowngrade(result));
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("查询失败: " + readable(error)));
            }
        });
    }

    private void queryEdl() {
        String version = edlVersion.getText().toString().trim();
        String date = edlDate.getText().toString().trim();
        if (!version.matches("[A-Za-z0-9]+_\\S+")) { status.setText("请输入 Realme 版本名，如 RMX3888_16.0.3.500(CN01)"); return; }
        if (!date.matches("[0-9]{12}")) { status.setText("日期前缀必须为 12 位数字，如 202601241320"); return; }
        int region = edlRegion.getSelectedItemPosition();
        status.setText("正在探测 EDL 包...");
        executor.execute(() -> {
            try {
                String link = EdlProtocol.query(version, region, date, message -> runOnUiThread(() -> status.setText(message)));
                runOnUiThread(() -> renderEdl(link));
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("查询失败: " + readable(error)));
            }
        });
    }

    private void renderOta(JSONObject result) {
        status.setText("查询成功，已解析官方返回的完整信息");
        LiquidGlassPanel overview = glass();
        overview.setOrientation(LinearLayout.VERTICAL);
        overview.setPadding(dp(16), dp(14), dp(16), dp(14));
        appendOtaResult(overview, result);
        results.addView(overview, margins(-1, -2, 10));
    }

    private void appendOtaResult(LinearLayout overview, JSONObject result) {
        String matchedRegion = result.optString("matchedRegion", "");
        if (!matchedRegion.isEmpty()) addPair(overview, "匹配地区", matchedRegion);
        overview.addView(label("版本信息", 17, 0xff20375b), margins(-1, 28, 4));
        addPair(overview, "系统版本", result.optString("version", "N/A"));
        addPair(overview, "OTA 版本", result.optString("otaVersion", "N/A"));
        addPair(overview, "发布时间", result.optString("publishedTime", "N/A"));
        addPair(overview, "安全补丁", result.optString("securityPatch", "N/A"));
        JSONArray components = result.optJSONArray("components");
        if (components != null) for (int i = 0; i < components.length(); i++) renderComponent(overview, components.optJSONObject(i), i + 1, result.optString("version", ""));
        String changelog = result.optString("changelog", "");
        if (!changelog.isEmpty() && !"N/A".equals(changelog)) {
            overview.addView(label("更新说明", 17, 0xff20375b), margins(-1, 28, 2));
            TextView changelogView = label(changelog, 13, 0xff596579);
            changelogView.setTextIsSelectable(true);
            changelogView.setLongClickable(true);
            changelogView.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);
            changelogView.setLinksClickable(true);
            overview.addView(changelogView, margins(-1, -2, 4));
            Button openChangelog = button("更新日志链接");
            openChangelog.setEnabled(changelog.startsWith("http"));
            openChangelog.setOnClickListener(v -> openUrl(changelog));
            overview.addView(openChangelog, margins(-1, 44, 4));
        }
    }

    private JSONObject queryRegional(String prefix, String region, String components,
                                     boolean antiQuery, boolean grayRelease, String guid) throws Exception {
        Exception last = null;
        for (String suffix : modelSuffixes(region)) {
            try {
                return OPlusProtocol.query(prefix, region, components, antiQuery, grayRelease, guid, suffix);
            } catch (Exception error) {
                last = error;
                if (!isNoVersion(error)) throw error;
            }
        }
        throw last == null ? new IllegalStateException("官方服务未返回可用版本") : last;
    }

    private void queryAllRegions(String prefix, String components, boolean antiQuery,
                                 boolean grayRelease, String guid, JSONArray matches,
                                 StringBuilder failures, Consumer<JSONObject> onMatch) {
        for (int i = 1; i < REGION_CODES.length; i++) {
            String region = REGION_CODES[i];
            try {
                JSONObject result;
                try {
                    result = queryRegional(prefix, region, components, antiQuery, grayRelease, guid);
                } catch (Exception firstError) {
                    if (!antiQuery || !isNoVersion(firstError)) throw firstError;
                    result = queryRegional(prefix, region, components, false, grayRelease, guid);
                }
                final JSONObject matchedResult = result;
                matchedResult.put("matchedRegion", REGION_LABELS[i]);
                matches.put(matchedResult);
                String found = REGION_LABELS[i];
                runOnUiThread(() -> {
                    status.setText("已匹配 " + found + "，系统结果已显示，继续检查其他地区...");
                    onMatch.accept(matchedResult);
                });
            } catch (Exception error) {
                if (!isNoVersion(error)) {
                    if (failures.length() > 0) failures.append("；");
                    failures.append(REGION_LABELS[i]).append(": ").append(readable(error));
                }
            }
        }
    }

    private String[] modelSuffixes(String region) {
        if ("in".equals(region)) return new String[]{"", "IN"};
        if ("eu".equals(region)) return new String[]{"EEA", ""};
        if ("ru".equals(region) || "tr".equals(region)) return new String[]{region.toUpperCase(Locale.ROOT), ""};
        if ("gl".equals(region)) return new String[]{""};
        if (REGION_CODES.length > 0) return new String[]{"", region.toUpperCase(Locale.ROOT)};
        return new String[]{""};
    }

    private void renderOtaVersions(JSONArray versions) {
        results.removeAllViews();
        status.setText("查询成功，找到 " + versions.length() + " 个官方版本");
        for (int i = 0; i < versions.length(); i++) {
            JSONObject version = versions.optJSONObject(i);
            if (version == null) continue;
            renderOta(version);
        }
    }

    private void renderComponent(LinearLayout parent, JSONObject c, int number, String systemVersion) {
        if (c == null) return;
        parent.addView(label("组件 " + number + "  ·  " + c.optString("name", "Unknown"), 16, 0xff20375b), margins(-1, 30, 2));
        addPair(parent, "组件版本", c.optString("version", "N/A"));
        addPair(parent, "文件大小", formatSize(c.optString("size", "N/A")));
        addPair(parent, "MD5", c.optString("md5", "N/A"));
        String link = c.optString("link", "");
        LinearLayout actions = new LinearLayout(this);
        Button download = button("下载组件");
        download.setEnabled(link.startsWith("http"));
        String resolvedSystemVersion = systemVersion;
        if (resolvedSystemVersion.isEmpty() || "N/A".equals(resolvedSystemVersion)) resolvedSystemVersion = c.optString("version", "");
        final String downloadName = formatSystemVersionName(resolvedSystemVersion);
        download.setOnClickListener(v -> startDownload(link, downloadName, ""));
        Button copy = button("复制链接");
        copy.setEnabled(link.startsWith("http"));
        copy.setOnClickListener(v -> copyText("下载链接", link));
        Button copyMd5 = button("复制 MD5");
        String md5 = c.optString("md5", "");
        copyMd5.setEnabled(!md5.isEmpty() && !"N/A".equalsIgnoreCase(md5));
        copyMd5.setOnClickListener(v -> copyText("MD5", md5));
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.addView(download, new LinearLayout.LayoutParams(-1, dp(46)));
        LinearLayout copies = new LinearLayout(this);
        copies.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams copiesLp = new LinearLayout.LayoutParams(-1, dp(46));
        copiesLp.topMargin = dp(8);
        copies.addView(copy, new LinearLayout.LayoutParams(0, dp(46), 1));
        LinearLayout.LayoutParams md5Lp = new LinearLayout.LayoutParams(0, dp(46), 1);
        md5Lp.setMarginStart(dp(8));
        copies.addView(copyMd5, md5Lp);
        actions.addView(copies, copiesLp);
        parent.addView(actions, margins(-1, 100, 8));
        long expiresAt = extractExpiryMillis(link);
        if (expiresAt > 0) addExpiryCountdown(parent, expiresAt);
    }

    private void renderDowngrade(JSONObject result) {
        status.setText("查询成功，找到官方降级包");
        LiquidGlassPanel head = glass();
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(16), dp(14), dp(16), dp(14));
        head.addView(label("降级包结果", 17, 0xff20375b));
        addPair(head, "查询版本", result.optString("queryVersion", "N/A"));
        results.addView(head, margins(-1, -2, 10));
        JSONArray packages = result.optJSONArray("packages");
        for (int i = 0; i < packages.length(); i++) {
            JSONObject pkg = packages.optJSONObject(i);
            if (pkg == null) continue;
            LiquidGlassPanel card = glass();
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(16), dp(14), dp(16), dp(14));
            card.addView(label("降级包 " + (i + 1), 17, 0xff20375b));
            String colorOs = pkg.optString("colorosVersion", "").trim();
            String android = pkg.optString("androidVersion", "").trim();
            String versionName = colorOs + (colorOs.isEmpty() || android.isEmpty() ? "" : " / ") + android;
            addPair(card, "OTA 版本", pkg.optString("otaVersion", "N/A"));
            if (!versionName.isEmpty()) addPair(card, "系统版本", versionName);
            String md5 = findMd5(pkg);
            addPair(card, "MD5", md5);
            addPair(card, "文件大小", formatSize(pkg.optString("fileSize", "")));
            String introduction = pkg.optString("versionIntroduction", "N/A");
            if (!introduction.isEmpty()) addPair(card, "说明", introduction);
            String link = findDownloadUrl(pkg);
            LinearLayout actions = new LinearLayout(this);
            Button download = button("下载此降级包");
            download.setEnabled(link.startsWith("http"));
            String downloadName = pkg.optString("otaVersion", "").trim();
            if (downloadName.isEmpty()) downloadName = pkg.optString("versionName", "").trim();
            if (downloadName.isEmpty()) downloadName = "oplus-downgrade";
            final String finalDownloadName = downloadName;
            download.setOnClickListener(v -> startDownload(link, "downgrade", finalDownloadName, true));
            Button copy = button("复制下载链接");
            copy.setEnabled(link.startsWith("http"));
            copy.setOnClickListener(v -> copyLink(link));
            Button copyMd5 = button("复制 MD5");
            copyMd5.setEnabled(true);
            copyMd5.setOnClickListener(v -> {
                if ("N/A".equalsIgnoreCase(md5.trim())) {
                    Toast.makeText(this, "该降级包未返回 MD5", Toast.LENGTH_SHORT).show();
                } else {
                    copyText("MD5", md5);
                }
            });
            actions.setOrientation(LinearLayout.HORIZONTAL);
            download.setTextSize(11);
            copy.setTextSize(11);
            copyMd5.setTextSize(11);
            actions.addView(download, new LinearLayout.LayoutParams(0, dp(48), 1));
            LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(0, dp(48), 1);
            copyLp.setMarginStart(dp(6));
            actions.addView(copy, copyLp);
            LinearLayout.LayoutParams md5Lp = new LinearLayout.LayoutParams(0, dp(48), 1);
            md5Lp.setMarginStart(dp(6));
            actions.addView(copyMd5, md5Lp);
            card.addView(actions, margins(-1, 52, 8));
            results.addView(card, margins(-1, -2, 10));
        }
    }

    private void renderEdl(String link) {
        status.setText("查询成功，找到 EDL 包");
        LiquidGlassPanel card = glass();
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.addView(label("EDL 刷机包", 17, 0xff20375b));
        LinearLayout actions = new LinearLayout(this);
        Button download = button("下载 EDL 包");
        download.setEnabled(link.startsWith("http"));
        download.setOnClickListener(v -> startDownload(link, "edl", ""));
        Button copy = button("复制下载链接");
        copy.setOnClickListener(v -> copyLink(link));
        actions.addView(download, new LinearLayout.LayoutParams(0, dp(46), 1));
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(0, dp(46), 1);
        copyLp.setMarginStart(dp(10));
        actions.addView(copy, copyLp);
        card.addView(actions, new LinearLayout.LayoutParams(-1, dp(46)));
        results.addView(card, margins(-1, -2, 10));
    }

    private String formatSize(String value) {
        try {
            double bytes = Double.parseDouble(value.trim());
            if (bytes <= 0) return value.isEmpty() ? "N/A" : value;
            if (bytes >= 1024 * 1024 * 1024) return value + " Byte (" + String.format(Locale.ROOT, "%.2f GB", bytes / 1024 / 1024 / 1024) + ")";
            return value + " Byte (" + String.format(Locale.ROOT, "%.2f MB", bytes / 1024 / 1024) + ")";
        } catch (Exception ignored) {
            return value.isEmpty() ? "N/A" : value;
        }
    }

    private void startDownload(String address, String name, String version) {
        startDownload(address, name, version, false);
    }

    private void startDownload(String address, String name, String version, boolean downgrade) {
        address = address.split("\\n", 2)[0].trim();
        if (!address.startsWith("http")) return;
        String extension = extensionFromUrl(address);
        String baseName = safeFilename(version, "");
        if (baseName.isEmpty()) baseName = safeFilename(name, "oplus-update");
        String filename = baseName.toLowerCase(Locale.ROOT).endsWith(extension) ? baseName : baseName + extension;
        java.io.File dir = new java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DsuManager");
        if (!dir.exists()) dir.mkdirs();
        java.io.File file = new java.io.File(dir, filename.replaceAll("[^a-zA-Z0-9._() -]", "_"));
        // OPPO固定使用16线程+8MB分片（最优配置）
        Intent intent = new Intent(this, DownloadService.class).setAction(DownloadService.ACTION_START)
                .putExtra(DownloadService.EXTRA_ADDRESS, address)
                .putExtra(DownloadService.EXTRA_OUTPUT, file.getAbsolutePath())
                .putExtra(DownloadService.EXTRA_PACKAGE, "OPlus OTA")
                .putExtra(DownloadService.EXTRA_OPLUS, true)
                .putExtra(DownloadService.EXTRA_DOWNGRADE, downgrade)
                .putExtra("download_threads", 16)
                .putExtra("download_chunk_mb", 8L);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
        showDownloadCard(file);
        status.setText("下载已开始: " + file.getName());
    }

    private String safeFilename(String value, String fallback) {
        String cleaned = value == null ? "" : value.trim().replaceAll("[^a-zA-Z0-9._() -]", "_");
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    private String normalizeDowngradeOta(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "_");
        if (normalized.isEmpty()) return normalized;
        if (normalized.indexOf('_') < 0) normalized += "_11.A";
        return normalized;
    }

    private String firstValue(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.optString(key, "").trim();
            if (!value.isEmpty() && !"N/A".equalsIgnoreCase(value) && !"null".equalsIgnoreCase(value)) return value;
        }
        return "N/A";
    }

    private String findDownloadUrl(JSONObject object) {
        String direct = firstValue(object, "downloadUrl", "downloadURL", "url", "link", "fileUrl", "fileURL");
        if (direct.startsWith("http")) return direct;
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            Object raw = object.opt(keys.next());
            if (raw instanceof JSONObject) {
                String nested = findDownloadUrl((JSONObject) raw);
                if (nested.startsWith("http")) return nested;
            }
            if (raw instanceof JSONArray) {
                JSONArray values = (JSONArray) raw;
                for (int i = 0; i < values.length(); i++) {
                    Object item = values.opt(i);
                    if (item instanceof JSONObject) {
                        String nested = findDownloadUrl((JSONObject) item);
                        if (nested.startsWith("http")) return nested;
                    }
                }
            }
        }
        return "";
    }

    private String findMd5(JSONObject object) {
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object raw = object.opt(key);
            if (key.toLowerCase(Locale.ROOT).contains("md5") && raw != null) {
                String value = String.valueOf(raw).trim();
                if (value.matches("[0-9a-fA-F]{32}")) return value;
            }
            if (raw instanceof JSONObject) {
                String value = findMd5((JSONObject) raw);
                if (!"N/A".equals(value)) return value;
            }
            if (raw instanceof JSONArray) {
                JSONArray values = (JSONArray) raw;
                for (int i = 0; i < values.length(); i++) {
                    Object item = values.opt(i);
                    if (item instanceof JSONObject) {
                        String value = findMd5((JSONObject) item);
                        if (!"N/A".equals(value)) return value;
                    }
                }
            }
        }
        return "N/A";
    }

    private String formatSystemVersionName(String value) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isEmpty() || "N/A".equalsIgnoreCase(cleaned)) return "";
        Matcher suffix = Pattern.compile("^(.+)_([A-Z]{2}[0-9]{2})$").matcher(cleaned);
        return suffix.matches() ? suffix.group(1) + " (" + suffix.group(2) + ")" : cleaned;
    }

    private boolean isNoVersion(Exception error) {
        String message = error == null ? "" : error.getMessage();
        return message != null && message.matches(".*(?:服务返回|responseCode)[ :]*2004.*");
    }

    private String extensionFromUrl(String address) {
        String path = address.split("[?#]", 2)[0];
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot <= slash || dot == path.length() - 1) return ".zip";
        String extension = path.substring(dot).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.]", "");
        return extension.matches("\\.[a-z0-9]{1,8}") ? extension : ".zip";
    }

    private void buildDownloadCard(LinearLayout parent) {
        downloadCard = glass();
        downloadCard.setOrientation(LinearLayout.VERTICAL);
        downloadCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        downloadCard.addView(label("下载任务", 17, 0xff20375b), margins(-1, 28, 2));
        downloadFileTitle = label("正在下载: 未开始", 13, 0xff20375b);
        downloadFileTitle.setSingleLine(true);
        downloadFileTitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        downloadCard.addView(downloadFileTitle, margins(-1, 28, 2));
        LinearLayout progressHeader = new LinearLayout(this);
        downloadStatus = label("等待下载", 13, 0xff596579);
        progressHeader.addView(downloadStatus, new LinearLayout.LayoutParams(0, dp(32), 1));
        downloadPercent = label("0%", 15, 0xff20375b);
        downloadPercent.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        progressHeader.addView(downloadPercent, new LinearLayout.LayoutParams(dp(58), dp(32)));
        downloadCard.addView(progressHeader);
        downloadProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        downloadProgress.setMax(100);
        downloadProgress.setProgressDrawable(getDrawable(R.drawable.progress_bar));
        downloadCard.addView(downloadProgress, margins(-1, 14, 5));
        downloadBytes = label("已下载 0 B / 总大小获取中", 12, 0xff596579);
        downloadCard.addView(downloadBytes, margins(-1, 24, 0));
        downloadPath = label("保存到: 未开始", 11, 0xff7a8798);
        downloadPath.setMaxLines(2);
        downloadCard.addView(downloadPath, margins(-1, -2, 6));
        LinearLayout actions = new LinearLayout(this);
        pauseDownloadButton = button("暂停");
        pauseDownloadButton.setOnClickListener(v -> {
            if ("重试".contentEquals(pauseDownloadButton.getText())) {
                sendDownloadCommand(DownloadService.ACTION_RESUME);
                pauseDownloadButton.setText("暂停");
                return;
            }
            downloadPaused = !downloadPaused;
            sendDownloadCommand(downloadPaused ? DownloadService.ACTION_PAUSE : DownloadService.ACTION_RESUME);
            pauseDownloadButton.setText(downloadPaused ? "继续" : "暂停");
        });
        cancelDownloadButton = button("取消");
        cancelDownloadButton.setOnClickListener(v -> {
            if ("关闭".contentEquals(cancelDownloadButton.getText())) { downloadCard.setVisibility(View.GONE); return; }
            sendDownloadCommand(DownloadService.ACTION_CANCEL);
            downloadCard.setVisibility(View.GONE);
        });
        actions.addView(pauseDownloadButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, dp(44), 1);
        cancelLp.setMarginStart(dp(10));
        actions.addView(cancelDownloadButton, cancelLp);
        downloadCard.addView(actions, new LinearLayout.LayoutParams(-1, dp(44)));
        parent.addView(downloadCard, margins(-1, -2, 10));
        downloadCard.setVisibility(View.GONE);
    }

    private void showDownloadCard(java.io.File file) {
        downloadCard.setVisibility(View.VISIBLE);
        downloadPaused = false;
        downloadFileTitle.setText("正在下载: " + file.getName());
        downloadPath.setText("保存到: " + file.getAbsolutePath());
        downloadStatus.setText("正在准备下载");
        downloadBytes.setText("已下载 0 B / 总大小获取中");
        speedWindowBytes = 0;
        speedWindowTime = 0;
        smoothedSpeed = 0;
        downloadProgress.setProgress(0);
        downloadPercent.setText("0%");
        pauseDownloadButton.setVisibility(View.VISIBLE);
        pauseDownloadButton.setText("暂停");
        cancelDownloadButton.setText("取消");
    }

    private void restoreDownloadCard() {
        android.content.SharedPreferences prefs = getSharedPreferences("rom_download", MODE_PRIVATE);
        String output = prefs.getString("output", "");
        if (output.isEmpty()) {
            downloadCard.setVisibility(View.GONE);
            return;
        }
        java.io.File file = new java.io.File(output);
        downloadCard.setVisibility(View.VISIBLE);
        downloadFileTitle.setText("正在下载: " + file.getName());
        downloadPath.setText("保存到: " + file.getAbsolutePath());
        String message = prefs.getString("message", "正在下载");
        downloadStatus.setText(message);
        long done = prefs.getLong("done", file.isFile() ? file.length() : 0);
        long total = prefs.getLong("total", -1);
        if (total > 0) updateDownloadProgress(done, total);
        else {
            downloadBytes.setText("已下载 " + formatBytes(done) + " / 总大小获取中");
            downloadPercent.setText("0%");
            downloadProgress.setProgress(0);
        }
        downloadPaused = prefs.getBoolean("paused", false);
        boolean finished = "下载完成".equals(message);
        boolean failed = message.startsWith("下载失败") || message.startsWith("下载地址无效");
        pauseDownloadButton.setVisibility(finished ? View.GONE : View.VISIBLE);
        pauseDownloadButton.setText(failed ? "重试" : downloadPaused ? "继续" : "暂停");
        cancelDownloadButton.setText(finished ? "关闭" : "取消");
        speedWindowBytes = 0;
        speedWindowTime = 0;
        smoothedSpeed = 0;
    }

    private void sendDownloadCommand(String action) {
        Intent intent = new Intent(this, DownloadService.class).setAction(action);
        try { startService(intent); } catch (Exception ignored) { }
    }

    private void updateDownloadProgress(long done, long total) {
        int percent = total > 0 ? (int) Math.min(100, done * 100 / total) : 0;
        long now = android.os.SystemClock.elapsedRealtime();
        if (speedWindowTime == 0 || done < speedWindowBytes) {
            speedWindowBytes = done;
            speedWindowTime = now;
        }
        long elapsed = now - speedWindowTime;
        long delta = done - speedWindowBytes;
        if (elapsed >= 2000 && delta >= 0) {
            long measured = delta * 1000L / elapsed;
            smoothedSpeed = smoothedSpeed == 0 ? measured : (smoothedSpeed * 3 + measured) / 4;
            speedWindowBytes = done;
            speedWindowTime = now;
        }
        downloadProgress.setProgress(percent);
        downloadPercent.setText(percent + "%");
        if (total > 0 && done < total) {
            if (smoothedSpeed > 0) {
                long remaining = (total - done) / smoothedSpeed;
                downloadStatus.setText("预计剩余 " + formatRemainingTime(remaining));
            } else if (downloadStatus.getText().toString().startsWith("预计剩余")) {
                downloadStatus.setText("正在下载");
            }
        }
        String speed = smoothedSpeed > 0 ? " · 速度 " + formatBytes(smoothedSpeed) + "/s" : "";
        downloadBytes.setText(total > 0
                ? "已下载 " + formatBytes(done) + " / 总大小 " + formatBytes(total) + speed
                : "已下载 " + formatBytes(done) + " / 总大小获取中" + speed);
    }

    private String formatRemainingTime(long seconds) {
        if (seconds < 0) return "--";
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainder = seconds % 60;
        if (hours > 0) return hours + " 小时 " + minutes + " 分钟";
        if (minutes > 0) return minutes + " 分钟";
        return Math.max(1, remainder) + " 秒";
    }

    private void copyLink(String link) {
        copyText("下载链接", link);
    }

    private void copyText(String label, String value) {
        String text = "下载链接".equals(label) ? cleanLink(value) : value == null ? "" : value.trim();
        if (text.isEmpty() || "N/A".equalsIgnoreCase(text)) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
        status.setText(label + "已复制");
        Toast.makeText(this, label + "已复制", Toast.LENGTH_SHORT).show();
    }

    private void openUrl(String link) {
        link = cleanLink(link);
        if (!link.startsWith("http")) return;
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)));
    }

    private String cleanLink(String link) {
        if (link == null) return "";
        int lineBreak = link.indexOf('\n');
        return (lineBreak >= 0 ? link.substring(0, lineBreak) : link).trim();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024d);
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.ROOT, "%.1f MB", bytes / 1024d / 1024d);
        return String.format(Locale.ROOT, "%.2f GB", bytes / 1024d / 1024d / 1024d);
    }

    private CheckBox checkBox(String text) {
        CheckBox box = new CheckBox(this);
        box.setText(text);
        box.setTextSize(13);
        box.setTextColor(0xff20375b);
        return box;
    }

    private void moveTabIndicator(int index, boolean animated) {
        if (tabIndicator == null || tabItems == null || tabItems.getChildCount() == 0) return;
        View target = tabItems.getChildAt(index);
        int width = dp(62);
        int targetLeft = tabItems.getLeft() + target.getLeft() + (target.getWidth() - width) / 2;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tabIndicator.getLayoutParams();
        lp.width = width;
        lp.height = dp(48);
        lp.leftMargin = tabIndicatorLeft < 0 ? targetLeft : tabIndicatorLeft;
        lp.topMargin = dp(8);
        tabIndicator.setLayoutParams(lp);
        if (!animated || tabIndicatorLeft < 0) { tabIndicatorLeft = targetLeft; lp.leftMargin = targetLeft; tabIndicator.setLayoutParams(lp); return; }
        int previous = tabIndicatorLeft;
        tabIndicatorLeft = targetLeft;
        android.animation.ValueAnimator flow = android.animation.ValueAnimator.ofFloat(0f, 1f);
        flow.setDuration(620);
        flow.setInterpolator(new android.view.animation.PathInterpolator(.18f, .78f, .22f, 1f));
        flow.addUpdateListener(a -> {
            float p = (Float) a.getAnimatedValue();
            float stretch = p < .24f ? p / .24f : p < .48f ? 1f : p < .80f ? 1f - (p - .48f) / .32f : 0f;
            float settle = p < .80f ? 0f : (p - .80f) / .20f;
            float travel = p < .43f ? 0f : (p - .43f) / .57f;
            tabIndicator.setTranslationX((targetLeft - previous) * travel);
            tabIndicator.setScaleX(1f + 1.18f * stretch + .07f * settle);
            tabIndicator.setScaleY(1f - .07f * stretch + .025f * settle);
        });
        flow.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                FrameLayout.LayoutParams settled = (FrameLayout.LayoutParams) tabIndicator.getLayoutParams();
                settled.leftMargin = targetLeft;
                tabIndicator.setTranslationX(0f);
                tabIndicator.setScaleX(1f);
                tabIndicator.setScaleY(1f);
                tabIndicator.setLayoutParams(settled);
            }
        });
        pulseTabNavigation();
        flow.start();
    }

    private boolean handleTabGesture(View view, MotionEvent event, int pressedTab) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                tabDragging = false;
                tabDragTarget = pressedTab;
                tabDragStartX = event.getRawX();
                if (tabLongPress != null) mainHandler.removeCallbacks(tabLongPress);
                tabLongPress = () -> {
                    tabDragging = true;
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                    if (tabIndicator != null) tabIndicator.setLiquidPressed(true);
                    previewTabTarget(tabDragStartX);
                };
                mainHandler.postDelayed(tabLongPress, android.view.ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!tabDragging && Math.abs(event.getRawX() - tabDragStartX) >= dp(12)) {
                    tabDragging = true;
                    if (tabLongPress != null) mainHandler.removeCallbacks(tabLongPress);
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                    if (tabIndicator != null) tabIndicator.setLiquidPressed(true);
                }
                if (tabDragging) previewTabTarget(event.getRawX());
                return true;
            case MotionEvent.ACTION_UP:
                if (tabLongPress != null) mainHandler.removeCallbacks(tabLongPress);
                if (tabDragging) {
                    if (tabIndicator != null) tabIndicator.setLiquidPressed(false);
                    selectTab(tabDragTarget < 0 ? pressedTab : tabDragTarget);
                } else {
                    Haptics.perform(view);
                    selectTab(pressedTab);
                }
                tabDragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (tabLongPress != null) mainHandler.removeCallbacks(tabLongPress);
                if (tabIndicator != null) tabIndicator.setLiquidPressed(false);
                tabDragging = false;
                return true;
            default:
                return true;
        }
    }

    private void previewTabTarget(float rawX) {
        if (tabItems == null || tabItems.getChildCount() == 0) return;
        int[] location = new int[2];
        tabItems.getLocationOnScreen(location);
        float itemWidth = tabItems.getWidth() / (float) tabItems.getChildCount();
        int target = Math.max(0, Math.min(tabItems.getChildCount() - 1,
                (int) ((rawX - location[0]) / itemWidth)));
        if (target == tabDragTarget) return;
        tabDragTarget = target;
        moveTabIndicator(target, true);
        if (typeTabs != null) {
            for (int i = 0; i < typeTabs.length; i++) typeTabs[i].setTextColor(i == target ? 0xff17334f : 0xfff8fbff);
        }
    }

    private long extractExpiryMillis(String link) {
        if (link == null) return -1;
        String value = link;
        int newline = value.indexOf('\n');
        if (newline >= 0) value = value.substring(0, newline);
        android.net.Uri uri = android.net.Uri.parse(value);
        String expires = uri.getQueryParameter("Expires");
        if (expires == null) expires = uri.getQueryParameter("x-oss-expires");
        try { return expires == null ? -1 : Long.parseLong(expires) * 1000L; }
        catch (Exception ignored) { return -1; }
    }

    private void addExpiryCountdown(LinearLayout parent, long expiresAt) {
        TextView expiry = label("动态链接有效期计算中", 11, 0xff8a6b22);
        parent.addView(expiry, margins(-1, 24, 4));
        Runnable updater = new Runnable() {
            @Override public void run() {
                long remaining = Math.max(0, (expiresAt - System.currentTimeMillis()) / 1000L);
                expiry.setText(remaining > 0 ? "动态链接剩余有效期 " + formatRemainingTime(remaining) : "动态链接已失效，请重新查询");
                if (remaining > 0) mainHandler.postDelayed(this, 1000L);
            }
        };
        updater.run();
    }

    private void pulseTabNavigation() {
        if (tabItems == null) return;
        if (tabPulse != null) tabPulse.cancel();
        tabItems.setPivotX(tabItems.getWidth() / 2f);
        tabItems.setPivotY(tabItems.getHeight() / 2f);
        tabPulse = android.animation.ValueAnimator.ofFloat(0f, 1f);
        tabPulse.setDuration(840);
        tabPulse.setInterpolator(new android.view.animation.PathInterpolator(.22f, .76f, .26f, 1f));
        tabPulse.addUpdateListener(animation -> {
            float progress = (Float) animation.getAnimatedValue();
            float swell = progress < .28f ? progress / .28f : progress < .55f ? 1f : 1f - (progress - .55f) / .45f;
            tabItems.setScaleX(1f + .032f * swell);
            tabItems.setScaleY(1f + .064f * swell);
        });
        tabPulse.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (animation != tabPulse) return;
                tabItems.setScaleX(1f);
                tabItems.setScaleY(1f);
                tabPulse = null;
            }
        });
        tabPulse.start();
    }

    @Override protected void onStart() {
        super.onStart();
        restoreDownloadCard();
        IntentFilter filter = new IntentFilter(DownloadService.ACTION_UPDATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(downloadReceiver, filter);
        if (!getSharedPreferences("rom_download", MODE_PRIVATE).getString("output", "").isEmpty()) {
            sendDownloadCommand(DownloadService.ACTION_QUERY);
        }
    }

    @Override protected void onStop() {
        super.onStop();
        try { unregisterReceiver(downloadReceiver); } catch (IllegalArgumentException ignored) { }
    }

    private void addPair(LinearLayout parent, String key, String value) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(0, dp(7), 0, dp(7));
        TextView title = label(key, 11, 0xff7a8798);
        TextView content = label(value, 14, 0xff334b66);
        content.setLineSpacing(dp(3), 1f);
        content.setTextIsSelectable(true);
        group.addView(title);
        group.addView(content, margins(-1, -2, 2));
        parent.addView(group);
    }

    // OPPO 页面使用 14dp 圆角的玻璃效果
    private LiquidGlassPanel glass() { return new LiquidGlassPanel(this, 14f); }

    private EditText field(String label, String hint) {
        EditText field = new EditText(this);
        field.setHint(label + "  " + hint);
        field.setSingleLine(true);
        field.setTextSize(14);
        field.setTextColor(0xff20375b);
        field.setHintTextColor(0xff7a8798);
        field.setPadding(dp(14), 0, dp(14), 0);
        field.setBackgroundResource(R.drawable.liquid_glass_panel);
        return field;
    }

    private Spinner spinner(String[] values) {
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values));
        spinner.setBackgroundColor(Color.TRANSPARENT);
        return spinner;
    }

    private LinearLayout wrappedSpinner(String title, Spinner spinner) {
        LiquidGlassPanel shell = glass();
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(10), dp(3), dp(8), 0);
        shell.addView(label(title, 10, 0xff7a8798));
        shell.addView(spinner, new LinearLayout.LayoutParams(-1, dp(42)));
        return shell;
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(0xff20375b);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackgroundResource(R.drawable.liquid_glass_panel);
        button.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) v.setScaleX(.98f);
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) v.setScaleX(1f);
            return false;
        });
        return button;
    }

    private TextView label(String value, int size, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setIncludeFontPadding(true);
        return text;
    }

    private LinearLayout.LayoutParams margins(int width, int height, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height < 0 ? height : dp(height));
        p.setMargins(0, 0, 0, dp(bottom));
        return p;
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    
    private android.graphics.drawable.Drawable createOPlusGradientBackground() {
        // OPPO 青蓝科技感：上部天蓝，中部青色，下部深蓝，左右渐变过渡
        android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] {
            createRadialGradient(0xffc6e5f0, 0x00000000, 0.5f, 0.0f),  // 顶部中心天蓝光晕
            createRadialGradient(0xffa5d4e0, 0x00000000, 0.2f, 0.3f),  // 左上青色光晕
            createRadialGradient(0xffa5d4e0, 0x00000000, 0.8f, 0.3f),  // 右上青色光晕
            createLinearGradient(0xffd0e8f2, 0xff7faab8, true),        // 主背景：上浅下深
            createRadialGradient(0xff6b99ad, 0x00000000, 0.5f, 0.8f)   // 底部中心深蓝光晕
        });
        return layers;
    }
    
    private android.graphics.drawable.GradientDrawable createLinearGradient(int startColor, int endColor, boolean vertical) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable(
            vertical ? android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM 
                     : android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            new int[] { startColor, endColor }
        );
        return gd;
    }
    
    private android.graphics.drawable.GradientDrawable createRadialGradient(int centerColor, int edgeColor, float centerX, float centerY) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setGradientType(android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT);
        gd.setColors(new int[] { centerColor, edgeColor });
        gd.setGradientCenter(centerX, centerY);
        gd.setGradientRadius(800);
        return gd;
    }

    private String readable(Exception error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage().replaceAll("\\s+", " "); }

    private static byte[] rsaOaep(byte[] input, String publicKeyBase64) throws Exception {
        PublicKey key = parseRsaPublicKey(publicKeyBase64);
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new OAEPParameterSpec("SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT));
        return cipher.doFinal(input);
    }

    private static PublicKey parseRsaPublicKey(String publicKeyBase64) throws Exception {
        byte[] encoded = Base64.getDecoder().decode(publicKeyBase64);
        KeyFactory factory = KeyFactory.getInstance("RSA");
        try {
            return factory.generatePublic(new X509EncodedKeySpec(encoded));
        } catch (Exception ignored) {
            DerReader sequence = new DerReader(encoded).sequence();
            BigInteger modulus;
            BigInteger exponent;
            if (sequence.peek() == 0x30) {
                sequence.sequence();
                byte[] bitString = sequence.bitString();
                DerReader rsaSequence = new DerReader(bitString).sequence();
                modulus = rsaSequence.integer();
                exponent = rsaSequence.integer();
            } else {
                modulus = sequence.integer();
                exponent = sequence.integer();
            }
            return factory.generatePublic(new RSAPublicKeySpec(modulus, exponent));
        }
    }

    private static final class DerReader {
        private final byte[] data;
        private int offset;

        DerReader(byte[] data) { this.data = data; }

        int peek() throws Exception {
            if (offset >= data.length) throw new IllegalArgumentException("Invalid RSA public key");
            return data[offset] & 0xff;
        }

        void expect(int tag) throws Exception {
            if ((readByte() & 0xff) != tag) throw new IllegalArgumentException("Invalid RSA public key");
        }

        DerReader sequence() throws Exception {
            expect(0x30);
            return child();
        }

        DerReader child() throws Exception {
            int length = length();
            if (length < 0 || offset + length > data.length) throw new IllegalArgumentException("Invalid RSA public key length");
            byte[] value = java.util.Arrays.copyOfRange(data, offset, offset + length);
            offset += length;
            return new DerReader(value);
        }

        BigInteger integer() throws Exception {
            expect(0x02);
            int length = length();
            if (length < 0 || offset + length > data.length) throw new IllegalArgumentException("Invalid RSA public key integer");
            BigInteger value = new BigInteger(1, java.util.Arrays.copyOfRange(data, offset, offset + length));
            offset += length;
            return value;
        }

        byte[] bitString() throws Exception {
            expect(0x03);
            int length = length();
            if (length < 1 || offset + length > data.length) throw new IllegalArgumentException("Invalid RSA public key bit string");
            int unusedBits = readByte() & 0xff;
            if (unusedBits != 0) throw new IllegalArgumentException("Invalid RSA public key bit string");
            byte[] value = java.util.Arrays.copyOfRange(data, offset, offset + length - 1);
            offset += length - 1;
            return value;
        }

        private int length() throws Exception {
            int first = readByte() & 0xff;
            if ((first & 0x80) == 0) return first;
            int count = first & 0x7f;
            if (count == 0 || count > 4) throw new IllegalArgumentException("Invalid RSA public key length");
            int result = 0;
            for (int i = 0; i < count; i++) result = (result << 8) | (readByte() & 0xff);
            return result;
        }

        private byte readByte() throws Exception {
            if (offset >= data.length) throw new IllegalArgumentException("Invalid RSA public key");
            return data[offset++];
        }
    }

    private static byte[] aesCtr(byte[] input, byte[] key, byte[] iv, boolean encrypt) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(input);
    }

    private static byte[] aesGcm(byte[] input, byte[] key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        return cipher.doFinal(input);
    }

    private static byte[] aesGcmDecrypt(byte[] dataWithTag, byte[] key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        return cipher.doFinal(dataWithTag);
    }

    private static byte[] random(int size) {
        byte[] value = new byte[size];
        new SecureRandom().nextBytes(value);
        return value;
    }

    private static String randomDeviceId() {
        StringBuilder out = new StringBuilder(64);
        for (byte item : random(32)) out.append(String.format(Locale.ROOT, "%02X", item));
        return out.toString();
    }

    private static String read(InputStream input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) out.write(buffer, 0, count);
        return out.toString("UTF-8");
    }

    private static String postJson(String url, JSONObject headers, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setDoOutput(true);
        Iterator<String> keys = headers.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            connection.setRequestProperty(key, headers.getString(key));
        }
        connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        int code = connection.getResponseCode();
        String raw = read(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
        if (code != 200) throw new IllegalStateException("HTTP " + code + ": " + raw);
        return raw;
    }

    private static final class OPlusProtocol {
        private static final String CN = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEApXYGXQpNL7gmMzzvajHaoZIHQQvBc2cOEhJc7/tsaO4sT0unoQnwQKfNQCuv7qC1Nu32eCLuewe9LSYhDXr9KSBWjOcCFXVXteLO9WCaAh5hwnUoP/5/Wz0jJwBA+yqs3AaGLA9wJ0+B2lB1vLE4FZNE7exUfwUc03fJxHG9nCLKjIZlrnAAHjRCd8mpnADwfkCEIPIGhnwq7pdkbamZcoZfZud1+fPsELviB9u447C6bKnTU4AaMcR9Y2/uI6TJUTcgyCp+ilgU0JxemrSIPFk3jbCbzamQ6Shkw/jDRzYoXpBRg/2QDkbq+j3ljInu0RHDfOeXf3VBfHSnQ66HCwIDAQAB";
         private static final String EU = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAh8/EThsK3f0WyyPgrtXb/D0Xni6UZNppaQHUqHWo976cybl92VxmehE0ISObnxERaOtrlYmTPIxkVC9MMueDvTwZ1l0KxevZVKU0sJRxNR9AFcw6D7k9fPzzpNJmhSlhpNbt3BEepdgibdRZbacF3NWy3ejOYWHgxC+I/Vj1v7QU5gD+1OhgWeRDcwuV4nGY1ln2lvkRj8EiJYXfkSq/wUI5AvPdNXdEqwou4FBcf6mD84G8pKDyNTQwwuk9lvFlcq4mRqgYaFg9DAgpDgqVK4NTJWM7tQS1GZuRA6PhupfDqnQExyBFhzCefHkEhcFywNyxlPe953NWLFWwbGvFKwIDAQAB";
        private static final String IN = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwYtghkzeStC9YvAwOQmWylbp74Tj8hhi3f9IlK7A/CWrGbLgzz/BeKxNb45zBN8pgaaEOwAJ1qZQV5G4nProWCPOP1ro1PkemFJvw/vzOOT5uN0ADnHDzZkZXCU/knxqUSfLcwQlHXsYhNsAm7uOKjY9YXF4zWzYN0eFPkML3Pj/zg7hl/ov9clB2VeyI1/blMHFfcNA/fvqDTENXcNBIhgJvXiCpLcZqp+aLZPC5AwY/sCb3j5jTWer0Rk0ZjQBZE1AncwYvUx4mA65U59cWpTyl4c47J29MsQ66hqWv6eBHlDNZSEsQpHePUqgsf7lmO5Wd7teB8ugQki2oz1Y5QIDAQAB";
        private static final String SG = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAkA980wxi+eTGcFDiw2I6RrUeO4jL/Aj3Yw4dNuW7tYt+O1sRTHgrzxPD9SrOqzz7G0KgoSfdFHe3JVLPN+U1waK+T0HfLusVJshDaMrMiQFDUiKajb+QKr+bXQhVofH74fjat+oRJ8vjXARSpFk4/41x5j1Bt/2bHoqtdGPcUizZ4whMwzap+hzVlZgs7BNfepo24PWPRujsN3uopl+8u4HFpQDlQl7GdqDYDjz2NOHdFQI2UpSf0aIeKCKOpSKF72KDEESpJVQsqO4nxMwEi2jMujQeCHyTCjBZ+W35RzwT9+0pyZv8FB3c7FYY9FdF/+lvfax5mvFEBd9jO+dpMQIDAQAB";
        private static final String ZERO_GUID = "0000000000000000000000000000000000000000000000000000000000000000";

        private static JSONObject query(String prefix, String region, String components, boolean antiQuery, boolean grayRelease, String guid, String modelSuffix) throws Exception {
            String base = prefix.split("_")[0];
            String model = region.equals("eu")
                    ? base + (modelSuffix.isEmpty() ? "EEA" : modelSuffix)
                    : (region.equals("ru") || region.equals("tr")
                    ? base + (modelSuffix.isEmpty() ? region.toUpperCase() : modelSuffix)
                    : base + modelSuffix);
            String host = region.equals("cn") || region.equals("cn_cmcc") ? "component-ota-cn.allawntech.com" : region.equals("eu") ? "component-ota-eu.allawnos.com" : region.equals("in") ? "component-ota-in.allawnos.com" : "component-ota-sg.allawnos.com";
            String[] langs = {"zh-CN", "zh-CN", "en-IN", "en-GB", "en-SG", "ru-RU", "tr-TR", "th-TH", "en-US", "zh-TW", "ms-MY", "vi-VN", "id-ID", "sa-SA", "en-MEA", "en-PH", "en-LA", "en-BR", "en-EU"};
            String[] carriers = {"10010111", "10011000", "00011011", "01000100", "01011010", "00110111", "01010001", "00111001", "10100111", "00011010", "00111000", "00111100", "00110011", "10000011", "10100110", "00111110", "10011010", "10011110", "10001101"};
            String headerRegion = region;
            int ri = 0;
            for (int i = 1; i < REGION_CODES.length; i++) if (REGION_CODES[i].equals(headerRegion)) ri = i - 1;
            String otaVersion = normalizeOtaVersion(prefix, guid);
            String publicKey = region.equals("cn") || region.equals("cn_cmcc") ? CN : region.equals("eu") ? EU : region.equals("in") ? IN : SG;
            String negotiation = region.equals("eu") ? "1615897067573" : region.equals("in") ? "1615896309308" : (region.equals("cn") || region.equals("cn_cmcc") ? "1615879139745" : "1615895993238");
            byte[] aes = random(32), iv = random(16);
            boolean preview = !guid.isEmpty();
            JSONObject request = new JSONObject().put("mode", "0").put("time", System.currentTimeMillis()).put("isRooted", "0").put("isLocked", true).put("type", "0").put("deviceId", preview ? guid.toLowerCase(Locale.ROOT) : ZERO_GUID).put("opex", new JSONObject().put("check", true));
            if (grayRelease) request.put("recruitId", "whoami");
            if (!components.isEmpty()) {
                JSONArray list = new JSONArray();
                for (String part : components.split(",")) {
                    int colon = part.indexOf(':');
                    if (colon > 0) list.put(new JSONObject().put("componentName", part.substring(0, colon).trim()).put("componentVersion", part.substring(colon + 1).trim()));
                }
                request.put("components", list);
            }
            JSONObject scene = new JSONObject().put("protectedKey", Base64.getEncoder().encodeToString(rsaOaep(Base64.getEncoder().encode(aes), publicKey))).put("version", String.valueOf(System.currentTimeMillis() * 1000000L + 86400000000000L)).put("negotiationVersion", negotiation);
            JSONObject headers = new JSONObject().put("language", langs[ri]).put("newLanguage", langs[ri]).put("androidVersion", "unknown").put("colorOSVersion", "unknown").put("romVersion", "unknown").put("infVersion", "1").put("otaVersion", otaVersion).put("model", model).put("mode", antiQuery ? "taste" : "manual").put("nvCarrier", carriers[ri]).put("pipelineKey", "ALLNET").put("operator", "ALLNET").put("companyId", "").put("version", "2").put("deviceId", randomDeviceId()).put("User-Agent", "okhttp/4.12.0").put("Accept-Encoding", "gzip").put("Content-Type", "application/json; charset=utf-8").put("protectedKey", new JSONObject().put("SCENE_1", scene).toString());
            JSONObject params = new JSONObject().put("cipher", Base64.getEncoder().encodeToString(aesCtr(request.toString().getBytes(StandardCharsets.UTF_8), aes, iv, true))).put("iv", Base64.getEncoder().encodeToString(iv));
            String raw = postJson("https://" + host + (preview ? "/update/v6" : "/update/v3"), headers, new JSONObject().put("params", params.toString()).toString());
            JSONObject envelope = new JSONObject(raw);
            if (envelope.optInt("responseCode") != 200) throw new IllegalStateException("服务返回 " + envelope.optInt("responseCode"));
            Object bodyValue = envelope.get("body");
            JSONObject encrypted = bodyValue instanceof JSONObject
                    ? (JSONObject) bodyValue
                    : new JSONObject(String.valueOf(bodyValue));
            String plain = new String(aesCtr(Base64.getDecoder().decode(encrypted.getString("cipher")), aes, Base64.getDecoder().decode(encrypted.getString("iv")), false), StandardCharsets.UTF_8);
            return parse(plain);
        }

        private static JSONObject parse(String raw) throws Exception {
            JSONObject body = new JSONObject(raw), out = new JSONObject();
            out.put("version", body.optString("realVersionName", body.optString("versionName", "N/A")))
                    .put("otaVersion", body.optString("realOtaVersion", body.optString("otaVersion", "N/A")))
                    .put("securityPatch", body.optString("securityPatch", "N/A"))
                    .put("publishedTime", body.optLong("publishedTime", 0) > 0 ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new java.util.Date(body.optLong("publishedTime"))) : "N/A");
            JSONObject description = body.optJSONObject("description");
            out.put("changelog", description == null ? "N/A" : description.optString("panelUrl", "N/A"));
            JSONArray components = new JSONArray(), source = body.optJSONArray("components");
            if (source != null) {
                for (int i = 0; i < source.length(); i++) {
                    JSONObject c = source.optJSONObject(i);
                    if (c == null) continue;
                    Object packetValue = c.opt("componentPackets");
                    JSONArray packetList = packetValue instanceof JSONArray
                            ? (JSONArray) packetValue
                            : new JSONArray().put(packetValue);
                    for (int packetIndex = 0; packetIndex < packetList.length(); packetIndex++) {
                        JSONObject p = packetList.optJSONObject(packetIndex);
                        if (p == null) continue;
                        String manual = p.optString("manualUrl", p.optString("url", "N/A"));
                        if (manual.contains("downloadCheck")) {
                            try { manual = resolveRedirect(manual); } catch (Exception ignored) { }
                        }
                        components.put(new JSONObject()
                                .put("name", c.optString("componentName", "Unknown") + (packetList.length() > 1 ? " #" + (packetIndex + 1) : ""))
                                .put("version", c.optString("componentVersion", "Unknown"))
                                .put("link", manual)
                                .put("originalLink", p.optString("manualUrl", manual))
                                .put("autoUrl", p.optString("url", "N/A"))
                                .put("size", p.optString("size", "N/A"))
                                .put("md5", p.optString("md5", "N/A")));
                    }
                }
            }
            out.put("components", components);
            return out;
        }

        private static String normalizeOtaVersion(String prefix, String guid) {
            String value = prefix.toUpperCase(Locale.ROOT);
            int underscores = 0;
            for (int i = 0; i < value.length(); i++) if (value.charAt(i) == '_') underscores++;
            if (underscores == 0) value += "_11.C";
            if (underscores <= 2) value += ".01_0001_197001010000";
            return value;
        }

        private static String resolveRedirect(String address) throws Exception {
            HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/112.0.0.0 Mobile Safari/537.36");
            connection.setRequestProperty("Accept", "application/json,text/html,*/*");
            connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            connection.setRequestProperty("userId", "oplus-ota|00000001");
            connection.setRequestProperty("Range", "bytes=0-");
            int code = connection.getResponseCode();
            String location = connection.getHeaderField("Location");
            if (code / 100 != 3 || location == null || location.isEmpty()) throw new IllegalStateException("动态链接解析失败 HTTP " + code);
            Uri uri = Uri.parse(location);
            String expires = uri.getQueryParameter("Expires");
            if (expires == null) expires = uri.getQueryParameter("x-oss-expires");
            String expiry = expires == null ? "" : "\n有效期至: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new java.util.Date(Long.parseLong(expires) * 1000L));
            return location + expiry;
        }
    }

    private static final class DowngradeProtocol {
        private static final String ENDPOINT = "https://downgrade.coloros.com/downgrade/query-v3";
        private static final long NEGOTIATION = 1636449646204L;
        private static final String KEY = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAmeQzr0TIbtwZFnDXgatg6xP9SlNBFho1NTdFQ27SKDF+dBEEfnG9BqRw0na0DUqtpWe2CUtldbU33nnJ0KB6z7y5f+89o9n8mJxIbh952gpskBxyrhCfpYHV5mt/n9Tkm8OcQWLRFou7/XITuZeZejfUTesQjpfOeCaeKyVSoKQc6WuH7NSYq6B37RMyEn/1+vo8XuHEKD84p29KGpyGI7ZeL85iOcwBmOD6+e4yideH2RatA1SzEv/9V8BflaFLAWDuPWUjA2WgfOvy5spYmp/MoMOX4P0d+AkJ9Ms6PUXEUBsbOACmaMFyLCLHmd18+UeGdJR/3I15sXKbJhKerwIDAQAB";

        private static JSONObject query(String ota, String prj, String duid, boolean cmcc) throws Exception {
            String version = ota.trim().toUpperCase(Locale.ROOT);
            byte[] key = random(32), iv = random(12);
            String protectedKey = Base64.getEncoder().encodeToString(rsaOaep(Base64.getEncoder().encodeToString(key).getBytes(StandardCharsets.US_ASCII), KEY));
            JSONObject device = new JSONObject().put("cipher", Base64.getEncoder().encodeToString(aesGcm(duid.getBytes(StandardCharsets.US_ASCII), key, iv))).put("iv", Base64.getEncoder().encodeToString(iv));
            JSONObject payload = new JSONObject().put("model", version.split("_")[0]).put("nvCarrier", cmcc ? "10011000" : "10010111").put("prjNum", prj).put("serialNo", "").put("otaVersion", version).put("deviceId", device);
            JSONObject cipherInfo = new JSONObject().put("downgrade-server", new JSONObject().put("negotiationVersion", NEGOTIATION).put("protectedKey", protectedKey).put("version", String.valueOf(System.currentTimeMillis() / 1000)));
            JSONObject headers = new JSONObject().put("Content-Type", "application/json; charset=UTF-8").put("cipherInfo", cipherInfo.toString()).put("deviceId", duid).put("Connection", "close");
            String raw = postJson(ENDPOINT, headers, payload.toString());
            JSONObject response = new JSONObject(raw);
            if (response.optInt("code") == 1004) throw new IllegalStateException("服务端返回 DUID 为空，该 DUID 无降级通道");
            JSONObject data;
            if (response.has("cipher")) {
                data = new JSONObject(new String(aesGcmDecrypt(Base64.getDecoder().decode(response.getString("cipher")), key, Base64.getDecoder().decode(response.getString("iv"))), StandardCharsets.UTF_8));
            } else {
                data = response;
            }
            JSONObject dataBlock = data.optJSONObject("data");
            JSONArray packages = dataBlock == null ? null : dataBlock.optJSONArray("downgradeVoList");
            if (packages == null || packages.length() == 0) throw new IllegalStateException("未找到可用的降级包");
            return new JSONObject().put("queryVersion", version).put("packages", packages);
        }
    }

    private static final class EdlProtocol {
        private static final String[] SERVERS = {"rms11.realme.net", "rms01.realme.net", "rms01.realme.net"};
        private static final String[] BUCKETS = {"domestic", "GDPR", "export"};

        private static String query(String versionName, int region, String date, Consumer<String> progress) throws Exception {
            String model = versionName.trim().split("_")[0];
            String cleaned = versionName.trim().replaceFirst("^[A-Za-z0-9]+_", "").replace("(", "").replace(")", "");
            String base = "https://" + SERVERS[region] + "/sw/" + model + BUCKETS[region] + "_11_" + cleaned + "_" + date.trim();
            for (int i = 0; i < 10000; i++) {
                if (i % 100 == 0) progress.accept("正在探测 EDL 序号 " + i + " / 9999");
                String url = base + String.format(Locale.ROOT, "%04d", i) + ".zip";
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(url).openConnection();
                    connection.setRequestMethod("HEAD");
                    connection.setConnectTimeout(4000);
                    connection.setReadTimeout(4000);
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)");
                    if (connection.getResponseCode() == 200) return url;
                } catch (Exception ignored) {
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }
            throw new IllegalStateException("未找到匹配的 EDL 包，请核对版本名、地区与日期");
        }
    }
}
