package com.probiotics.xiaoni;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.AdapterView;
import android.widget.ImageView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RomActivity extends Activity {
    private static final String DEVICES_URL = "https://raw.githubusercontent.com/HegeKen/HyperData/main/devices.json";
    private static final String RECENT_URL = "https://raw.githubusercontent.com/HegeKen/HyperData/main/index.json";
    private static final String FANS_DEVICE_URL = "https://data.hyperos.fans/devices/";
    private static final String DEVICE_URL = "https://raw.githubusercontent.com/HegeKen/HyperData/main/devices/";
    private static final String DEVICE_CDN_URL = "https://cdn.jsdelivr.net/gh/HegeKen/HyperData@main/devices/";
    private static final String MI_FIRMWARE_HYPEROS4_URL = "https://mifirmware.com/xiaomi-hyperos-4/";
    private static final String MI_FIRMWARE_DEVICE_URL = "https://mifirmware.com/%s-firmware/";
    private static final String XM_HYPEROS_URL = "https://xmfirmwareupdater.com/hyperos/";
    private static final String XM_HYPEROS_ARCHIVE_URL = "https://xmfirmwareupdater.com/archive/hyperos/";
    private static final String XIAOMI_DOWNLOAD_BASE = "https://bigota.d.miui.com/";
    // 设备清单在线数据源：HyperOS.fans 主源 + HyperData 官方仓库双镜像（全部自动获取，不再内置 TXT）
    private static final String FANS_DEVICES_LIST_URL = "https://data.hyperos.fans/devices.json";
    private static final String DEVICES_CDN_LIST_URL = "https://cdn.jsdelivr.net/gh/HegeKen/HyperData@main/devices.json";
    // 官方系列分类（固定顺序展示，最新机型在各分类内排最后）
    private static final String[] CATEGORY_ORDER = {
            "全部设备",
            "Xiaomi 数字系列",
            "Xiaomi Civi 系列",
            "Xiaomi MIX Fold / Flip 系列",
            "小米平板系列",
            "Redmi 数字 / A 系列",
            "Redmi Note 系列",
            "Redmi K / Turbo 系列",
            "POCO 系列",
    };
    private final List<Device> devices = new ArrayList<>();
    private TextView status;
    private LinearLayout results;
    private EditText search;
    private Spinner category;   // 设备分类下拉（全部设备 + 各系列，图七/图八）
    private Spinner region;
    private Spinner node;
    private EditText customFilename;
    private LiquidGlassPanel downloadCard;
    private ProgressBar downloadProgress;
    private TextView downloadFileTitle;
    private TextView downloadStatus;
    private TextView downloadPercent;
    private TextView downloadBytes;
    private TextView downloadPath;
    private Button pauseDownloadButton;
    private Button cancelDownloadButton;
    private LinearLayout downloadActionsRow;
    private ScrollView pageScroll;
    private volatile boolean pauseDownload;
    private volatile boolean cancelDownload;
    private volatile boolean downloadFailed;
    private volatile long lastAriaEta = -1;   // aria2c 实测剩余时间（秒）
    private boolean awaitingPermission;
    private String pendingAddress;
    private File pendingOutput;
    private String pendingPackageType;
    private final Object downloadLock = new Object();
    private SharedPreferences downloadPrefs;
    private long lastProgressBytes;
    private long lastProgressTime;
    private long speedWindowBytes;
    private long speedWindowTime;
    private long smoothedSpeed;
    private long persistedDone;
    private long persistedTotal;
    private int preservedDownloadScrollY;
    private boolean preserveDownloadScroll;
    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!DownloadService.ACTION_UPDATE.equals(intent.getAction())) return;
            String state = intent.getStringExtra(DownloadService.EXTRA_STATE);
            long done = intent.getLongExtra(DownloadService.EXTRA_DONE, -1);
            long total = intent.getLongExtra(DownloadService.EXTRA_TOTAL, -1);
            long speed = intent.getLongExtra(DownloadService.EXTRA_SPEED, -1);
            long eta = intent.getLongExtra(DownloadService.EXTRA_ETA, -1);
            if (speed > 0) smoothedSpeed = speed;   // aria2c 实测速度直供 UI
            if (eta >= 0) lastAriaEta = eta;
            if (state != null && ("下载完成".equals(state)
                    || state.startsWith("下载失败")
                    || state.startsWith("已暂停")
                    || state.startsWith("下载已取消")
                    || state.startsWith("正在下载"))) {
                downloadStatus.setText(state);
            }
            // 通知栏侧「暂停/继续」操作 → 同步 APP 内按钮状态
            if (state != null && state.startsWith("已暂停")) {
                pauseDownload = true;
                if (pauseDownloadButton != null) pauseDownloadButton.setText("继续");
            } else if (state != null && state.startsWith("正在下载")) {
                pauseDownload = false;
                downloadFailed = false;
                if (pauseDownloadButton != null) pauseDownloadButton.setText("暂停");
            }
            if (done >= 0 && total > 0) updateDownloadProgress(done, total);
            if ("下载完成".equals(state)) {
                downloadProgress.setProgress(100);
                downloadPercent.setText("100%");
                downloadActionsRow.setVisibility(View.GONE);
                clearDownloadState();
            } else if (state != null && state.startsWith("下载已取消")) {
                // 通知栏侧「取消」→ APP 内下载框同步关闭
                cancelDownload = true;
                downloadActionsRow.setVisibility(View.GONE);
                downloadCard.setVisibility(View.GONE);
                clearDownloadState();
            }
        }
    };
    private LinearLayout versionBody;
    private Button versionToggle;
    private Device queriedDevice;
    private int queryGeneration;
    private final List<String> categoryNames = new ArrayList<>();   // 当前可用的系列分类（按官方顺序）
    // 分类下拉 + 列表/宫格视图切换（图七/图八）
    private boolean gridMode = false;              // false=列表视图，true=宫格视图
    private Button listButton;                     // 列表切换按钮（供 styleViewToggle 刷新）
    private Button gridButton;                     // 宫格切换按钮（供 styleViewToggle 刷新）
    private String selectedCategoryName = "Xiaomi 数字系列";   // 首次进入默认显示 Xiaomi 数字系列
    private boolean defaultSeriesApplied = false;  // 数字系列默认值只在首次加载清单时应用

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    
    private android.graphics.drawable.Drawable createXiaomiGradientBackground() {
        // 小米 蓝绿渐变：与 Vivo 相同，顶部天蓝，中部青绿，下部深蓝绿，清新自然
        android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] {
            createRadialGradient(0xffb8d4e8, 0x00000000, 0.5f, 0.0f),  // 顶部中心天蓝光晕
            createRadialGradient(0xff98c4d9, 0x00000000, 0.25f, 0.25f), // 左上青蓝光晕
            createRadialGradient(0xffa8ccd9, 0x00000000, 0.75f, 0.25f), // 右上青蓝光晕
            createLinearGradient(0xffc8dce8, 0xff6a8fa8, true),        // 主背景：上浅蓝下深蓝绿
            createRadialGradient(0xff5a7d94, 0x00000000, 0.5f, 0.8f)   // 底部中心深蓝绿光晕
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

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0x00000000);
        getWindow().setNavigationBarColor(0x220b131f);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        downloadPrefs = getSharedPreferences("rom_download", MODE_PRIVATE);
        gridMode = downloadPrefs.getBoolean("device_view_grid", false);
        buildUi();
        loadDevices();
        restoreDownloadState();
    }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        Haptics.onTouch(getWindow().getDecorView(), event);
        return super.dispatchTouchEvent(event);
    }
    
    @Override public void onBackPressed() {
        finish();
        overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out);
    }

    private TextView label(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setIncludeFontPadding(true);
        return view;
    }

    private void buildUi() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(18));

        LiquidGlassPanel title = glass();
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(8), 0, dp(8), 0);
        Button back = new Button(this);
        back.setText("<");
        back.setTextSize(20);
        back.setMinWidth(0);
        back.setMinHeight(0);
        back.setAllCaps(false);
        back.setTypeface(null, 1);
        back.setTextColor(0xff1a3356);
        back.setBackgroundResource(R.drawable.liquid_glass_panel);
        back.setStateListAnimator(null);
        back.setOnClickListener(v -> {
            finish();
            overridePendingTransition(R.anim.flip_in, R.anim.flip_out);
        });
        title.addView(back, new LinearLayout.LayoutParams(dp(42), dp(48)));
        TextView heading = new TextView(this);
        heading.setText("ROM 查询");
        heading.setTextSize(23);
        heading.setTextColor(0xff1a3356);
        heading.setTypeface(null, 1);
        heading.setGravity(Gravity.CENTER);
        title.addView(heading, new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, dp(56));
        titleLp.setMargins(0, 0, 0, dp(16));
        content.addView(title, titleLp);

        TextView source = label("设备清单自动获取：HyperOS.fans 主源 · HyperData 镜像 · 最新机型在前", 12, 0xff596579);
        source.setPadding(dp(12), dp(10), dp(12), dp(10));
        source.setBackgroundResource(R.drawable.liquid_glass_panel);
        LinearLayout.LayoutParams sourceLp = new LinearLayout.LayoutParams(-1, -2);
        sourceLp.setMargins(0, 0, 0, dp(10));
        content.addView(source, sourceLp);

        // 设备分类 + 下载节点并排（全部设备与各系列合并进下拉框，像选择版本一样，图七/图八）
        LinearLayout rowA = new LinearLayout(this);
        rowA.setOrientation(LinearLayout.HORIZONTAL);
        category = createSpinnerInRow("设备分类", new String[]{"全部设备"});
        category.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String name = position >= 0 && position < categoryNames.size()
                        ? categoryNames.get(position) : "全部设备";
                if (!name.equals(selectedCategoryName)) {
                    selectedCategoryName = name;
                    filterDevices();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        rowA.addView((View) category.getParent(), new LinearLayout.LayoutParams(0, dp(52), 1));
        rowA.addView(spacer(dp(12)), new LinearLayout.LayoutParams(dp(12), dp(52)));
        node = createSpinnerInRow("下载节点", new String[]{"阿里云", "CDN.ORG", "HugeOTA", "BigOTA", "BN"});
        rowA.addView((View) node.getParent(), new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout.LayoutParams rowALp = new LinearLayout.LayoutParams(-1, dp(52));
        rowALp.setMargins(0, dp(6), 0, 0);
        content.addView(rowA, rowALp);

        // 版本类型（正式版）+ 自定义下载文件名并排一行
        LinearLayout rowB = new LinearLayout(this);
        rowB.setOrientation(LinearLayout.HORIZONTAL);
        region = createSpinnerInRow("版本类型", new String[]{"正式版", "Beta", "演示机", "国际版", "EEA", "俄罗斯", "台湾", "印度", "印尼", "土耳其", "开发预览"});
        region.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (queriedDevice != null) loadRecent(queriedDevice);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        rowB.addView((View) region.getParent(), new LinearLayout.LayoutParams(0, dp(52), 1));
        rowB.addView(spacer(dp(12)), new LinearLayout.LayoutParams(dp(12), dp(52)));
        customFilename = new EditText(this);
        customFilename.setSingleLine(true);
        customFilename.setHint("自定义下载文件名（可选）");
        customFilename.setTextSize(14);
        customFilename.setPadding(dp(14), 0, dp(14), 0);
        customFilename.setBackgroundResource(R.drawable.liquid_glass_panel);
        rowB.addView(customFilename, new LinearLayout.LayoutParams(0, dp(52), 1.25f));
        LinearLayout.LayoutParams rowBLp = new LinearLayout.LayoutParams(-1, dp(52));
        rowBLp.setMargins(0, dp(10), 0, 0);
        content.addView(rowB, rowBLp);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("搜索机型或设备代号，例如 nuwa");
        search.setPadding(dp(14), 0, dp(14), 0);
        search.setBackgroundResource(R.drawable.liquid_glass_panel);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filterDevices(); }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(-1, dp(50));
        searchLp.setMargins(0, dp(10), 0, dp(8));
        content.addView(search, searchLp);

        // 统计信息（左）+ 列表/宫格视图切换（右下角，图七）
        LinearLayout rowC = new LinearLayout(this);
        rowC.setOrientation(LinearLayout.HORIZONTAL);
        rowC.setGravity(Gravity.CENTER_VERTICAL);
        status = label("正在加载设备数据...", 13, 0xff596579);
        status.setMaxLines(2);
        status.setLineSpacing(0, 1.05f);
        rowC.addView(status, new LinearLayout.LayoutParams(0, -2, 1));

        LiquidGlassPanel viewToggle = glass();
        viewToggle.setOrientation(LinearLayout.HORIZONTAL);
        viewToggle.setPadding(dp(2), dp(3), dp(2), dp(3));
        listButton = new Button(this);
        listButton.setText("☰ 列表");
        styleViewToggle(listButton, !gridMode);
        listButton.setOnClickListener(v -> {
            Haptics.perform(v);
            if (!gridMode) return;
            gridMode = false;
            downloadPrefs.edit().putBoolean("device_view_grid", false).apply();
            styleViewToggle(listButton, true);
            styleViewToggle(gridButton, false);
            filterDevices();
        });
        viewToggle.addView(listButton, new LinearLayout.LayoutParams(0, dp(36), 1));
        gridButton = new Button(this);
        gridButton.setText("▦ 宫格");
        styleViewToggle(gridButton, gridMode);
        gridButton.setOnClickListener(v -> {
            Haptics.perform(v);
            if (gridMode) return;
            gridMode = true;
            downloadPrefs.edit().putBoolean("device_view_grid", true).apply();
            styleViewToggle(listButton, false);
            styleViewToggle(gridButton, true);
            filterDevices();
        });
        viewToggle.addView(gridButton, new LinearLayout.LayoutParams(0, dp(36), 1));
        LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(dp(118), dp(40));
        toggleLp.leftMargin = dp(10);
        rowC.addView(viewToggle, toggleLp);
        content.addView(rowC, new LinearLayout.LayoutParams(-1, -2));

        downloadCard = glass();
        downloadCard.setOrientation(LinearLayout.VERTICAL);
        downloadCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView downloadTitle = label("下载", 18, 0xff20375b);
        downloadTitle.setTypeface(null, 1);
        downloadCard.addView(downloadTitle, new LinearLayout.LayoutParams(-1, dp(30)));
        downloadFileTitle = label("正在下载: 文件名", 13, 0xff20375b);
        downloadFileTitle.setMaxLines(1);
        downloadFileTitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        downloadCard.addView(downloadFileTitle, new LinearLayout.LayoutParams(-1, dp(28)));
        LinearLayout progressHeader = new LinearLayout(this);
        progressHeader.setGravity(Gravity.CENTER_VERTICAL);
        downloadStatus = label("预计剩余 --", 13, 0xff596579);
        downloadStatus.setMaxLines(1);
        downloadStatus.setEllipsize(null);
        downloadStatus.setGravity(Gravity.CENTER_VERTICAL);
        downloadStatus.setLineSpacing(0, 1.05f);
        progressHeader.addView(downloadStatus, new LinearLayout.LayoutParams(0, -2, 1));
        downloadPercent = label("0%", 15, 0xff20375b);
        downloadPercent.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        downloadPercent.setTypeface(null, 1);
        LinearLayout.LayoutParams percentLp = new LinearLayout.LayoutParams(dp(64), dp(42));
        percentLp.setMarginStart(dp(6));
        progressHeader.addView(downloadPercent, percentLp);
        downloadCard.addView(progressHeader, new LinearLayout.LayoutParams(-1, -2));
        downloadProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        downloadProgress.setMax(100);
        downloadProgress.setProgress(0);
        downloadProgress.setProgressDrawable(getDrawable(R.drawable.progress_bar));
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(-1, dp(14));
        progressLp.setMargins(0, dp(2), 0, dp(6));
        downloadCard.addView(downloadProgress, progressLp);
        downloadBytes = label("已下载 0 B / 总大小获取中", 12, 0xff596579);
        downloadBytes.setMaxLines(1);
        downloadBytes.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams bytesLp = new LinearLayout.LayoutParams(-1, dp(24));
        bytesLp.setMargins(0, dp(2), 0, 0);
        downloadCard.addView(downloadBytes, bytesLp);
        downloadPath = label("保存到: 未开始", 11, 0xff8a94a6);
        downloadPath.setMaxLines(2);
        downloadPath.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams pathLp = new LinearLayout.LayoutParams(-1, -2);
        pathLp.setMargins(0, dp(2), 0, dp(4));
        downloadCard.addView(downloadPath, pathLp);
        downloadActionsRow = new LinearLayout(this);
        downloadActionsRow.setOrientation(LinearLayout.HORIZONTAL);
        downloadActionsRow.setGravity(Gravity.CENTER_VERTICAL);
        pauseDownloadButton = downloadButton("暂停");
        pauseDownloadButton.setOnClickListener(v -> {
            Haptics.perform(v);
            if (downloadFailed) {
                restartDownload();
                return;
            }
            pauseDownload = !pauseDownload;
            pauseDownloadButton.setText(pauseDownload ? "继续" : "暂停");
            if (pauseDownload) {
                sendDownloadCommand(DownloadService.ACTION_PAUSE);
                downloadStatus.setText("正在暂停...");
                saveDownloadState(true, "正在暂停...");
            } else {
                continueDownload();
            }
        });
        cancelDownloadButton = downloadButton("取消");
        cancelDownloadButton.setOnClickListener(v -> {
            Haptics.perform(v);
            cancelDownload = true;
            sendDownloadCommand(DownloadService.ACTION_CANCEL);
            downloadActionsRow.setVisibility(View.GONE);
            downloadCard.setVisibility(View.GONE);
            clearDownloadState();
            status.setText("正在取消下载并清理缓存...");
        });
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(0, dp(44), 1);
        downloadActionsRow.addView(pauseDownloadButton, new LinearLayout.LayoutParams(actionLp));
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, dp(44), 1);
        cancelLp.setMargins(dp(10), 0, 0, 0);
        downloadActionsRow.addView(cancelDownloadButton, cancelLp);
        downloadCard.addView(downloadActionsRow, new LinearLayout.LayoutParams(-1, dp(44)));
        LinearLayout.LayoutParams downloadCardLp = new LinearLayout.LayoutParams(-1, -2);
        downloadCardLp.setMargins(0, dp(12), 0, dp(16));
        content.addView(downloadCard, downloadCardLp);
        downloadCard.setVisibility(View.GONE);
        downloadActionsRow.setVisibility(View.GONE);

        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setPadding(0, dp(6), 0, 0);
        content.addView(results, new LinearLayout.LayoutParams(-1, -2));
        
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        scroll.addView(content);
        
        FrameLayout root = new FrameLayout(this);
        root.setBackground(createXiaomiGradientBackground());
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
        
        pageScroll = scroll;
        setContentView(root);
    }

    private void sendDownloadCommand(String action) {
        Intent intent = new Intent(this, DownloadService.class).setAction(action);
        try { startService(intent); } catch (Exception ignored) { }
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(DownloadService.ACTION_UPDATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(downloadReceiver, filter);
    }

    @Override protected void onStop() {
        super.onStop();
        try { unregisterReceiver(downloadReceiver); } catch (IllegalArgumentException ignored) { }
    }

    private Spinner optionSpinner(LinearLayout parent, String prompt, String[] options) {
        Spinner spinner = new Spinner(this);
        spinner.setPrompt(prompt);
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, options));
        spinner.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        LiquidGlassPanel shell = glass();
        shell.setPadding(dp(10), 0, dp(10), 0);
        shell.setOrientation(LinearLayout.HORIZONTAL);
        shell.addView(spinner, new LinearLayout.LayoutParams(0, dp(46), 1));
        shell.addView(dropdownArrow(), new LinearLayout.LayoutParams(dp(22), dp(46)));
        LinearLayout.LayoutParams shellLp = new LinearLayout.LayoutParams(-1, dp(50));
        shellLp.setMargins(0, dp(14), 0, 0);
        parent.addView(shell, shellLp);
        return spinner;
    }
    
    // 创建用于并排显示的 Spinner（不自动添加到 root）
    private Spinner createSpinnerInRow(String prompt, String[] options) {
        Spinner spinner = new Spinner(this);
        spinner.setPrompt(prompt);
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, options));
        spinner.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        LiquidGlassPanel shell = glass();
        shell.setPadding(dp(10), 0, dp(10), 0);
        shell.setOrientation(LinearLayout.HORIZONTAL);
        shell.addView(spinner, new LinearLayout.LayoutParams(0, dp(46), 1));
        shell.addView(dropdownArrow(), new LinearLayout.LayoutParams(dp(22), dp(46)));
        return spinner;
    }

    private ImageView dropdownArrow() {
        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.ic_dropdown_arrow);
        arrow.setContentDescription("展开选项");
        arrow.setScaleType(ImageView.ScaleType.CENTER);
        return arrow;
    }

    /** 行内占位空隙 */
    private View spacer(int widthDp) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(widthDp), 1));
        return view;
    }

    private Button downloadButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(dp(4), 0, dp(4), 0);
        button.setBackgroundResource(R.drawable.liquid_glass_panel);
        return button;
    }

    private void setDownloadControls(boolean visible) {
        downloadActionsRow.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void showDownloadCard(String message) {
        showDownloadCard(message, true);
    }

    private void showDownloadCard(String message, boolean scrollToCard) {
        downloadCard.setVisibility(View.VISIBLE);
        downloadCard.setBackgroundResource(R.drawable.liquid_glass_panel);
        downloadActionsRow.setVisibility(View.VISIBLE);
        downloadStatus.setText("下载");
        if (pageScroll != null) {
            final int target = scrollToCard
                    ? Math.max(0, downloadCard.getTop() - dp(8))
                    : preservedDownloadScrollY;
            pageScroll.post(() -> pageScroll.scrollTo(0, target));
        }
    }

    /**
     * 加载设备清单：完全在线自动获取（不再内置 TXT）。
     * 数据源依次尝试：HyperOS.fans 主源 → HyperData GitHub → jsDelivr CDN 镜像；
     * 成功后写入本地缓存，下次启动秒开；全部失败时回退缓存数据。
     */
    private void loadDevices() {
        String cached = downloadPrefs.getString("device_catalog_v2", "");
        if (!cached.isEmpty()) {
            try {
                JSONObject cachedRoot = new JSONObject(cached);
                if (applyDeviceCatalog(cachedRoot)) {
                    status.setText("已加载缓存清单，正在在线刷新...");
                }
            } catch (Exception ignored) { }
        }
        new Thread(() -> {
            String[] sources = {FANS_DEVICES_LIST_URL, DEVICES_URL, DEVICES_CDN_LIST_URL};
            for (String source : sources) {
                try {
                    String body = request(source);
                    JSONObject root = new JSONObject(body);
                    if (!root.keys().hasNext()) continue;
                    downloadPrefs.edit().putString("device_catalog_v2", body).apply();
                    runOnUiThread(() -> applyDeviceCatalog(root));
                    return;
                } catch (Exception ignored) { }
            }
            runOnUiThread(() -> {
                if (devices.isEmpty()) status.setText("设备清单获取失败，请检查网络后重试");
                else status.setText("在线刷新失败，当前显示缓存清单");
            });
        }, "device-catalog").start();
    }

    /**
     * 解析 HyperData / HyperOS.fans 设备清单 JSON，按系列归类并排序（最新机型最后）。
     */
    private boolean applyDeviceCatalog(JSONObject root) {
        if (root == null) return false;
        List<Device> parsed = new ArrayList<>();
        java.util.Iterator<String> groupKeys = root.keys();
        int index = 0;
        while (groupKeys.hasNext()) {
            String groupKey = groupKeys.next();
            JSONObject group = root.optJSONObject(groupKey);
            if (group == null) continue;
            String brand = group.optString("brand", groupKey);
            JSONArray groupDevices = group.optJSONArray("devices");
            if (groupDevices == null) continue;
            for (int i = 0; i < groupDevices.length(); i++) {
                JSONObject item = groupDevices.optJSONObject(i);
                if (item == null) continue;
                String code = item.optString("code", "").trim().toLowerCase(Locale.ROOT);
                if (code.isEmpty()) continue;
                JSONObject nameObj = item.optJSONObject("name");
                JSONObject seriesObj = item.optJSONObject("series");
                Device device = new Device(code, nameObj == null ? new JSONObject() : nameObj);
                device.nameZh = nameObj == null ? code : nameObj.optString("zh", code);
                device.nameEn = nameObj == null ? code : nameObj.optString("en", code);
                device.series = seriesObj == null ? "" : seriesObj.optString("zh", "");
                device.group = groupKey;
                device.brand = brand;
                device.category = classifyDevice(groupKey, brand, device.series, device.nameZh);
                device.osScore = maxOsScore(item.optJSONArray("supports"));
                device.androidScore = maxAndroidScore(item.optJSONArray("android"));
                device.fileIndex = index++;
                parsed.add(device);
            }
        }
        if (parsed.isEmpty()) return false;
        devices.clear();
        devices.addAll(parsed);
        // 最新机型排在最前（图三）：最新支持的 OS 大版本 > Android 版本 > 清单顺序（越大越新的靠前）
        devices.sort((left, right) -> {
            int byOs = Float.compare(right.osScore, left.osScore);
            if (byOs != 0) return byOs;
            int byAndroid = Float.compare(right.androidScore, left.androidScore);
            if (byAndroid != 0) return byAndroid;
            return Integer.compare(right.fileIndex, left.fileIndex);
        });
        buildCategoryChips();
        filterDevices();
        return true;
    }

    /** 系列 → 官方九大分类 */
    private String classifyDevice(String groupKey, String brand, String series, String name) {
        String value = series == null ? "" : series;
        String lower = value.toLowerCase(Locale.ROOT);
        if (value.contains("平板") || lower.contains("pad")) return "小米平板系列";
        if (value.contains("Civi") || lower.contains("civi")) return "Xiaomi Civi 系列";
        if (value.contains("MIX") || lower.contains("mix") || lower.contains("fold") || lower.contains("flip"))
            return "Xiaomi MIX Fold / Flip 系列";
        if ("poco".equalsIgnoreCase(groupKey) || value.contains("POCO") || lower.contains("poco")) return "POCO 系列";
        if (value.contains("Note")) return "Redmi Note 系列";
        if (value.contains("Turbo") || value.contains("K系列") || value.contains("K 系列"))
            return "Redmi K / Turbo 系列";
        if ("redmi".equalsIgnoreCase(groupKey) || "REDMI".equalsIgnoreCase(brand)
                || lower.startsWith("redmi")) return "Redmi 数字 / A 系列";
        return "Xiaomi 数字系列";
    }

    /** supports 数组里的最大 OS 版本，如 OS3.0 → 3.0 */
    private float maxOsScore(JSONArray supports) {
        float best = 0f;
        if (supports == null) return best;
        Pattern pattern = Pattern.compile("OS\\s*(\\d+(?:\\.\\d+)?)");
        for (int i = 0; i < supports.length(); i++) {
            Matcher matcher = pattern.matcher(supports.optString(i, "").toUpperCase(Locale.ROOT));
            if (matcher.find()) {
                try { best = Math.max(best, Float.parseFloat(matcher.group(1))); } catch (Exception ignored) { }
            }
        }
        return best;
    }

    /** android 数组里的最大 Android 版本，如 15.0 → 15 */
    private float maxAndroidScore(JSONArray androidVersions) {
        float best = 0f;
        if (androidVersions == null) return best;
        for (int i = 0; i < androidVersions.length(); i++) {
            try { best = Math.max(best, Float.parseFloat(androidVersions.optString(i, "0"))); }
            catch (Exception ignored) { }
        }
        return best;
    }

    /** 刷新设备分类下拉框（全部设备 + 实际存在的系列，像选择版本一样的下拉） */
    private void buildCategoryChips() {
        if (category == null) return;
        java.util.Set<String> present = new HashSet<>();
        for (Device device : devices) present.add(device.category);
        categoryNames.clear();
        for (String name : CATEGORY_ORDER) {
            if ("全部设备".equals(name) || present.contains(name)) categoryNames.add(name);
        }
        if (!categoryNames.contains(selectedCategoryName)) {
            // 首次进入默认显示 Xiaomi 数字系列；之后用户选择失效时回退到全部设备
            if (!defaultSeriesApplied && categoryNames.contains("Xiaomi 数字系列")) {
                selectedCategoryName = "Xiaomi 数字系列";
            } else {
                selectedCategoryName = "全部设备";
            }
        }
        defaultSeriesApplied = true;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, categoryNames);
        category.setAdapter(adapter);
        int position = categoryNames.indexOf(selectedCategoryName);
        if (position >= 0) category.setSelection(position, false);
    }

    private void filterDevices() {
        if (results == null) return;
        queryGeneration++;
        queriedDevice = null;
        results.removeAllViews();
        if (devices.isEmpty()) {
            status.setText("设备清单加载中，请稍候...");
            return;
        }
        String query = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        String currentCode = android.os.Build.DEVICE.toLowerCase(Locale.ROOT);
        List<Device> matched = new ArrayList<>();
        for (Device device : devices) {
            if (!"全部设备".equals(selectedCategoryName) && !selectedCategoryName.equals(device.category)) continue;
            if (!query.isEmpty()
                    && !device.nameZh.toLowerCase(Locale.ROOT).contains(query)
                    && !device.nameEn.toLowerCase(Locale.ROOT).contains(query)
                    && !device.code.contains(query)) continue;
            matched.add(device);
        }
        int shown = 0;
        boolean exactCodeShown = false;
        LinearLayout gridRow = null;
        // 列表模式：顶部系列标题卡片（图八：分类名 + 机型数 · 最新在后）
        if (!gridMode && !matched.isEmpty()) {
            LiquidGlassPanel header = glass();
            header.setOrientation(LinearLayout.VERTICAL);
            header.setPadding(dp(16), dp(12), dp(16), dp(10));
            header.setBackgroundResource(R.drawable.liquid_glass_panel);
            TextView catTitle = label(selectedCategoryName, 18, 0xff1f3155);
            catTitle.setTypeface(null, 1);
            header.addView(catTitle, new LinearLayout.LayoutParams(-1, -2));
            TextView catCount = label(matched.size() + " 款机型 · 最新在前", 12, 0xff65738b);
            LinearLayout.LayoutParams ccLp = new LinearLayout.LayoutParams(-1, -2);
            ccLp.topMargin = dp(2);
            header.addView(catCount, ccLp);
            LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(-1, -2);
            headerLp.bottomMargin = dp(10);
            results.addView(header, headerLp);
        }
        for (Device device : matched) {
            boolean current = device.code.equals(currentCode);
            if (gridMode) {
                if (shown % 2 == 0) {
                    gridRow = new LinearLayout(this);
                    gridRow.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
                    rowLp.bottomMargin = dp(10);
                    results.addView(gridRow, rowLp);
                }
                addDeviceTile(gridRow, device, current, shown % 2 == 1);
            } else {
                addDeviceCard(device, current);
            }
            if (!query.isEmpty() && device.code.equalsIgnoreCase(query)) exactCodeShown = true;
            shown++;
        }
        String manualCode = normalizeDeviceCode(query);
        if (!manualCode.isEmpty() && !exactCodeShown) {
            JSONObject manualName = new JSONObject();
            try { manualName.put("zh", "直接查询设备代号 " + manualCode); } catch (Exception ignored) { }
            Device manualDevice = new Device(manualCode, manualName);
            manualDevice.nameZh = "直接查询设备代号 " + manualCode;
            manualDevice.nameEn = manualCode;
            manualDevice.category = "全部设备";
            addDeviceCard(manualDevice, manualCode.equals(currentCode));
            shown++;
        }
        String scope = "全部设备".equals(selectedCategoryName) ? "全部系列" : selectedCategoryName;
        status.setText(query.isEmpty()
                ? scope + " · 共 " + matched.size() + " 台 · 最新机型在前"
                : "“" + query + "” 匹配 " + matched.size() + " 台设备");
    }

    private String normalizeDeviceCode(String value) {
        String code = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return code.matches("[a-z0-9][a-z0-9._-]{1,63}") ? code : "";
    }

    /** 视图切换按钮样式：选中项蓝色填充，未选中半透玻璃 */
    private void styleViewToggle(Button button, boolean active) {
        button.setAllCaps(false);
        button.setTextSize(12.5f);
        button.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(0, 0, 0, 0);
        button.setStateListAnimator(null);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        if (active) {
            bg.setColor(0xff2f6fd8);
            button.setTextColor(0xffffffff);
        } else {
            bg.setColor(0x00000000);
            button.setTextColor(0xff41628f);
        }
        button.setBackground(bg);
    }

    /** 列表视图卡片（图三）：左侧系列标签徽章 + 机型名/代号 + 右侧「查看」按钮 */
    private void addDeviceCard(Device device, boolean current) {
        LiquidGlassPanel card = glass();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(9), dp(9), dp(9));
        card.setBackgroundResource(R.drawable.liquid_glass_panel);

        // 左侧系列标签徽章（图三）：分类名小圆角标签
        String badgeText = device.category == null || device.category.isEmpty()
                ? device.series : device.category;
        if (badgeText != null && !badgeText.isEmpty()) {
            TextView badge = new TextView(this);
            badge.setText(badgeText);
            badge.setTextSize(10.5f);
            badge.setTextColor(0xff3d6fe0);
            badge.setTypeface(null, 1);
            badge.setMaxLines(1);
            badge.setPadding(dp(8), dp(4), dp(8), dp(4));
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setColor(0x1a2f6fd8);
            badgeBg.setCornerRadius(dp(9));
            badgeBg.setStroke(Math.max(1, dp(1)), 0x2e2f6fd8);
            badge.setBackground(badgeBg);
            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(-2, -2);
            badgeLp.rightMargin = dp(10);
            card.addView(badge, badgeLp);
        }

        // 中间信息：机型名 + 代号
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView name = label(device.nameZh + (current ? " · 本机" : ""), 15, 0xff1f3155);
        name.setTypeface(null, 1);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(name, new LinearLayout.LayoutParams(-1, -2));
        String subtitle = device.code + (device.series.isEmpty() ? "" : " · " + device.series);
        TextView code = label(subtitle, 12, 0xff65738b);
        code.setTextSize(11.5f);
        code.setMaxLines(1);
        code.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams codeLp = new LinearLayout.LayoutParams(-1, -2);
        codeLp.topMargin = dp(2);
        info.addView(code, codeLp);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, -2, 1);
        card.addView(info, infoLp);

        // 右侧「查看」按钮（图三）
        Button query = new Button(this);
        query.setText("查看");
        query.setAllCaps(false);
        query.setTextSize(13.5f);
        query.setTypeface(null, 1);
        query.setTextColor(0xff1a4a8a);
        query.setMinWidth(0);
        query.setMinHeight(0);
        query.setPadding(dp(16), 0, dp(16), 0);
        query.setStateListAnimator(null);
        query.setBackgroundResource(R.drawable.liquid_glass_panel);
        query.setOnClickListener(v -> {
            Haptics.perform(v);
            v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).withEndAction(() ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(110).start()).start();
            loadRecent(device);
        });
        card.addView(query, new LinearLayout.LayoutParams(-2, dp(40)));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(8));
        results.addView(card, lp);
    }

    /** 宫格视图磁贴：双列紧凑卡片，整块点击即查询 */
    private void addDeviceTile(LinearLayout row, Device device, boolean current, boolean right) {
        LiquidGlassPanel tile = glass();
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(12), dp(12), dp(12), dp(12));
        tile.setBackgroundResource(R.drawable.liquid_glass_panel);
        tile.setOnClickListener(v -> {
            Haptics.perform(v);
            v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(90).withEndAction(() ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(110).start()).start();
            loadRecent(device);
        });
        TextView name = label(device.nameZh + (current ? " ·本机" : ""), 14, 0xff1f3155);
        name.setTextSize(13.5f);
        name.setTypeface(null, 1);
        name.setMaxLines(2);
        name.setLineSpacing(0, 1.05f);
        name.setMinLines(2);
        tile.addView(name, new LinearLayout.LayoutParams(-1, -2));
        TextView code = label(device.code, 11, 0xff65738b);
        code.setMaxLines(1);
        code.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams codeLp = new LinearLayout.LayoutParams(-1, -2);
        codeLp.topMargin = dp(4);
        tile.addView(code, codeLp);
        if (!device.series.isEmpty()) {
            TextView series = label(device.series, 10, 0xff3f79c8);
            series.setMaxLines(1);
            series.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams seriesLp = new LinearLayout.LayoutParams(-1, -2);
            seriesLp.topMargin = dp(2);
            tile.addView(series, seriesLp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        if (right) lp.leftMargin = dp(8); else lp.rightMargin = dp(8);
        row.addView(tile, lp);
    }

    private void loadRecent(Device device) {
        Haptics.perform(results);
        final String selectedVersionType = region.getSelectedItem() == null ? "正式版" : region.getSelectedItem().toString();
        final String sourceCode = normalizeSourceDeviceCode(device.code);
        status.setText("正在查询 " + device.name.optString("zh", device.code) + " · " + selectedVersionType + "...");
        queriedDevice = device;
        final int generation = ++queryGeneration;
        results.removeAllViews();

        String cached = downloadPrefs.getString("query_v5_" + sourceCode, "");
        if (!cached.isEmpty()) {
            try {
                JSONObject cachedResult = new JSONObject(cached);
                showVersions(device, cachedResult, selectedVersionType);
                status.setText("已显示缓存结果，正在刷新 " + device.name.optString("zh", device.code) + "...");
             } catch (Exception ignored) {
                 downloadPrefs.edit().remove("query_v5_" + sourceCode).apply();
            }
        }
         new Thread(() -> {
             ExecutorService queryPool = Executors.newFixedThreadPool(4);
             try {
                 // HyperOS.fans is the primary source. HyperData mirrors are the fast same-data fallback.
                 Future<JSONObject> fansFuture = queryPool.submit(() -> queryHyperData(FANS_DEVICE_URL + sourceCode + ".json"));
                 Future<JSONObject> rawFuture = queryPool.submit(() -> queryHyperData(DEVICE_URL + sourceCode + ".json"));
                 Future<JSONObject> cdnFuture = queryPool.submit(() -> queryHyperData(DEVICE_CDN_URL + sourceCode + ".json"));
                 JSONObject resultRoot = firstHyperData(fansFuture, rawFuture, cdnFuture);
                 if (!hasUsableBranches(resultRoot)) {
                     Future<JSONObject> miListFuture = queryPool.submit(() -> queryMiList(sourceCode));
                     Future<JSONObject> miDeviceFuture = queryPool.submit(() -> queryMiDevice(sourceCode));
                     mergeMiBranches(resultRoot.optJSONArray("branches"), miListFuture.get().optJSONArray("branches"), true);
                     mergeMiBranches(resultRoot.optJSONArray("branches"), miDeviceFuture.get().optJSONArray("branches"), true);
                 }
                 applyCachedMd5(sourceCode, resultRoot);
                 cacheMd5(sourceCode, resultRoot);
                 downloadPrefs.edit().putString("query_v5_" + sourceCode, resultRoot.toString()).apply();
                 final JSONObject finalResult = resultRoot;
                 runOnUiThread(() -> {
                     if (generation == queryGeneration) {
                         showVersions(device, finalResult, selectedVersionType);
                         enrichXmMd5InBackground(device, finalResult, selectedVersionType, generation, sourceCode);
                     }
                 });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (generation == queryGeneration) {
                        status.setText("查询暂时无法连接网络，请检查网络后重试");
                    }
                });
            } finally {
                queryPool.shutdownNow();
            }
         }).start();
     }

     private JSONObject queryMiList(String sourceCode) throws org.json.JSONException {
         JSONObject parsed = emptyBranches();
         try { parseMiFirmwareTable(request(MI_FIRMWARE_HYPEROS4_URL), sourceCode, parsed.optJSONArray("branches"), true); }
         catch (Exception ignored) { }
         return parsed;
     }

     private JSONObject queryMiDevice(String sourceCode) throws org.json.JSONException {
         JSONObject parsed = emptyBranches();
         try { parseMiFirmwareDevicePage(request(String.format(Locale.ROOT, MI_FIRMWARE_DEVICE_URL, sourceCode)), sourceCode, parsed.optJSONArray("branches")); }
         catch (Exception ignored) { }
         return parsed;
     }

     private JSONObject queryHyperData(String address) {
         try { return normalizeHyperData(new JSONObject(request(address))); }
         catch (Exception ignored) { return emptyBranchesUnchecked(); }
     }

     private JSONObject firstHyperData(Future<JSONObject>... futures) {
         long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
         while (System.nanoTime() < deadline) {
             try {
                 for (Future<JSONObject> future : futures) {
                     if (!future.isDone()) continue;
                     JSONObject value = future.get();
                     if (hasUsableBranches(value)) {
                         for (Future<JSONObject> other : futures) if (other != future) other.cancel(true);
                         return value;
                     }
                 }
                 Thread.sleep(20L);
             } catch (Exception ignored) { }
         }
         for (Future<JSONObject> future : futures) future.cancel(true);
         return emptyBranchesUnchecked();
     }

     private JSONObject emptyBranchesUnchecked() {
         try { return emptyBranches(); } catch (org.json.JSONException impossible) { return new JSONObject(); }
     }

     private JSONObject emptyBranches() throws org.json.JSONException {
         JSONObject root = new JSONObject();
         root.put("branches", new JSONArray());
         return root;
     }

     private boolean hasUsableBranches(JSONObject root) {
         JSONArray branches = root == null ? null : root.optJSONArray("branches");
         if (branches == null) return false;
         for (int i = 0; i < branches.length(); i++) {
             JSONObject branch = branches.optJSONObject(i);
             JSONObject roms = branch == null ? null : branch.optJSONObject("roms");
             if (roms != null && roms.length() > 0) return true;
         }
         return false;
     }

    private void enrichXmMd5InBackground(Device device, JSONObject result, String selectedVersionType, int generation, String sourceCode) {
        new Thread(() -> {
            mergeXmMd5(result, sourceCode);
            cacheMd5(sourceCode, result);
            runOnUiThread(() -> {
                if (generation == queryGeneration) showVersions(device, result, selectedVersionType);
            });
        }, "xm-md5-enrichment").start();
    }

    private String normalizeSourceDeviceCode(String value) {
        if ("neza".equalsIgnoreCase(value)) return "nezha";
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private void showVersions(Device device, JSONObject deviceData, String selectedVersionType) {
        JSONArray branches = deviceData.optJSONArray("branches");
        if (branches == null || branches.length() == 0) {
            status.setText("暂未找到 " + device.name.optString("zh", device.code) + " 的更新记录");
            return;
        }
        status.setText(device.name.optString("zh", device.code) + " · " + selectedVersionType);
        results.removeAllViews();
        versionBody = new LinearLayout(this);
        versionBody.setOrientation(LinearLayout.VERTICAL);
        LiquidGlassPanel versionHeader = glass();
        versionHeader.setOrientation(LinearLayout.VERTICAL);
        versionHeader.setPadding(dp(14), dp(10), dp(14), dp(10));
        TextView queried = label("查询机型: " + device.name.optString("zh", device.code) + "\n设备代号: " + device.code, 15, 0xff20375b);
        queried.setPadding(0, dp(4), 0, dp(4));
        versionHeader.addView(queried, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout headerActions = new LinearLayout(this);
        versionToggle = new Button(this);
        versionToggle.setText("收起最新系统");
        versionToggle.setAllCaps(false);
        versionToggle.setBackgroundResource(R.drawable.liquid_glass_panel);
        versionToggle.setOnClickListener(v -> {
            Haptics.perform(v);
            boolean expanded = versionBody.getVisibility() == View.VISIBLE;
            versionBody.setVisibility(expanded ? View.GONE : View.VISIBLE);
            versionToggle.setText(expanded ? "展开最新系统" : "收起最新系统");
        });
        LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(0, dp(44), 1);
        toggleLp.setMargins(0, dp(8), 0, 0);
        headerActions.addView(versionToggle, toggleLp);
        Button close = new Button(this);
        close.setText("关闭");
        close.setAllCaps(false);
        close.setBackgroundResource(R.drawable.liquid_glass_panel);
        close.setOnClickListener(v -> {
            Haptics.perform(v);
            queriedDevice = null;
            versionBody = null;
            versionToggle = null;
            filterDevices();
        });
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(0, dp(44), 1);
        closeLp.setMargins(dp(8), dp(8), 0, 0);
        headerActions.addView(close, closeLp);
        versionHeader.addView(headerActions, new LinearLayout.LayoutParams(-1, -2));
        results.addView(versionHeader, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(-1, -2);
        bodyLp.setMargins(0, dp(12), 0, 0);
        results.addView(versionBody, bodyLp);
        Button back = new Button(this);
        back.setText("返回设备列表");
        back.setAllCaps(false);
        back.setOnClickListener(v -> { Haptics.perform(v); filterDevices(); });
        back.setBackgroundResource(R.drawable.liquid_glass_panel);
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(-1, dp(44));
        backLp.setMargins(0, dp(12), 0, 0);
        results.addView(back, backLp);
        Set<String> displayedVersions = new HashSet<>();
        boolean hasSelectedBranch = false;
        for (int i = 0; i < branches.length(); i++) {
            JSONObject branch = branches.optJSONObject(i);
            if (branch != null && ("1".equals(branch.optString("show", "1")) || isSpecialBranch(branch))
                    && matchesVersionType(branch, selectedVersionType)) {
                hasSelectedBranch = true;
                break;
            }
        }
        for (int i = 0; i < branches.length(); i++) {
            JSONObject branch = branches.optJSONObject(i);
            if (branch == null || (!"1".equals(branch.optString("show", "1")) && !isSpecialBranch(branch))) continue;
            if (hasSelectedBranch && !matchesVersionType(branch, selectedVersionType)) continue;
            JSONObject roms = branch.optJSONObject("roms");
            if (roms == null) continue;
            JSONArray versions = roms.names();
            if (versions == null) continue;
            List<String> sortedVersions = new ArrayList<>();
            for (int j = 0; j < versions.length(); j++) {
                String key = versions.optString(j);
                if (roms.optJSONObject(key) != null) sortedVersions.add(key);
            }
            sortedVersions.sort((left, right) -> roms.optJSONObject(right).optString("release")
                    .compareTo(roms.optJSONObject(left).optString("release")));
            for (int j = 0; j < sortedVersions.size(); j++) {
                String version = sortedVersions.get(j);
                JSONObject item = roms.optJSONObject(version);
                if (item == null) continue;
                String versionKey = branch.optString("branchCode", "") + ":" + version;
                if (!displayedVersions.add(versionKey)) continue;
                String recovery = item.optString("recovery", "");
                String fastboot = item.optString("fastboot", "");
                LiquidGlassPanel row = glass();
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(20), dp(18), dp(20), dp(18));
                 JSONObject branchName = branch.optJSONObject("name");
                 String branchLabel = deviceBranchName(device, branchName == null ? "" : branchName.optString("zh", ""));
                TextView versionText = label(branchLabel + "\n" + version, 15, 0xff20375b);
                versionText.setPadding(0, dp(4), 0, dp(4));
                row.addView(versionText, new LinearLayout.LayoutParams(-1, -2));
                 addInfo(row, "区域", displayRegion(branch.optString("region", "未知")), 0xff376b9e);
                addInfo(row, "Android", item.optString("android", "未知"), 0xff5c4c9b);
                addInfo(row, "发布时间", item.optString("release", "未知"), 0xffa25b36);
                addInfo(row, "安全补丁", item.optString("aspatch", "未知"), 0xff8a6b22);
                addInfo(row, "MD5", firstValue(item, "md5", "md5sum", "md5_hash", "checksum"), 0xff287b62);
                LinearLayout actions = new LinearLayout(this);
                actions.setOrientation(LinearLayout.VERTICAL);
                LiquidGlassPanel actionShell = glass();
                actionShell.setPadding(dp(8), dp(8), dp(8), dp(8));
                actionShell.setOrientation(LinearLayout.HORIZONTAL);
                actionShell.setVisibility(View.VISIBLE);
                LinearLayout.LayoutParams actionShellLp = new LinearLayout.LayoutParams(-1, -2);
                actionShellLp.setMargins(0, dp(10), 0, 0);
                row.addView(actionShell, actionShellLp);
                actions.setVisibility(View.VISIBLE);
                addDownloadButton(actions, "Recovery", recovery, version);
                addDownloadButton(actions, "Fastboot", fastboot, version);
                actionShell.addView(actions, new LinearLayout.LayoutParams(-1, -2));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.setMargins(0, dp(6), 0, dp(20));
                versionBody.addView(row, lp);
            }
        }
        if (versionBody.getChildCount() == 0) status.setText("当前设备暂无“" + selectedVersionType + "”更新记录");
    }

    private void addInfo(LinearLayout row, String name, String value, int color) {
        TextView info = label(name + ": " + value, 12, color);
        info.setPadding(0, dp(3), 0, dp(3));
        row.addView(info, new LinearLayout.LayoutParams(-1, -2));
    }

    private String firstValue(JSONObject item, String... keys) {
        if (item == null) return "数据源未提供";
        for (String key : keys) {
            String value = item.optString(key, "").trim();
            if (value.matches("(?i)[0-9a-f]{32}")) return value;
        }
        return "数据源未提供";
    }

    private String displayBranchName(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return "HyperOS";
        if (normalized.contains("beta")) return "Beta";
        if (normalized.contains("stable") || normalized.contains("正式")) return "正式版";
        if (normalized.contains("testing") || normalized.contains("test")) return "开发测试版";
        return value.trim();
    }

    private String deviceBranchName(Device device, String branchName) {
        String displayType = displayBranchName(branchName);
        String deviceName = device.name.optString("zh", device.code).trim();
        if ("Beta".equals(displayType)) return deviceName + " Beta版";
        if ("正式版".equals(displayType)) return deviceName + " 正式版";
        return deviceName + " " + displayType;
    }

    private String displayRegion(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("cn".equals(normalized) || normalized.contains("china") || normalized.contains("中国")) return "中国大陆";
        if ("global".equals(normalized) || normalized.contains("global")) return "国际版";
        if ("eea".equals(normalized) || normalized.contains("europe")) return "EEA";
        if ("in".equals(normalized) || normalized.contains("india")) return "印度";
        if ("id".equals(normalized) || normalized.contains("indonesia")) return "印度尼西亚";
        if ("ru".equals(normalized) || normalized.contains("russia")) return "俄罗斯";
        if ("tw".equals(normalized) || normalized.contains("taiwan")) return "中国台湾";
        if ("tr".equals(normalized) || normalized.contains("turkey")) return "土耳其";
        return value == null || value.trim().isEmpty() ? "未知" : value.trim();
    }

    private void cacheMd5(String deviceCode, JSONObject root) {
        if (downloadPrefs == null) return;
        SharedPreferences.Editor editor = downloadPrefs.edit();
        JSONArray branches = root.optJSONArray("branches");
        if (branches == null) return;
        for (int i = 0; i < branches.length(); i++) {
            JSONObject branch = branches.optJSONObject(i);
            if (branch == null) continue;
            String branchCode = branch.optString("branchCode", "");
            JSONObject roms = branch.optJSONObject("roms");
            if (roms == null) continue;
            JSONArray versions = roms.names();
            if (versions == null) continue;
            for (int j = 0; j < versions.length(); j++) {
                String version = versions.optString(j);
                String md5 = firstValue(roms.optJSONObject(version), "md5", "md5sum", "md5_hash", "checksum");
                if (md5.matches("(?i)[0-9a-f]{32}")) {
                    editor.putString("md5:" + deviceCode + ":" + branchCode + ":" + version, md5);
                }
            }
        }
        editor.apply();
    }

    private void applyCachedMd5(String deviceCode, JSONObject root) {
        if (downloadPrefs == null) return;
        JSONArray branches = root.optJSONArray("branches");
        if (branches == null) return;
        for (int i = 0; i < branches.length(); i++) {
            JSONObject branch = branches.optJSONObject(i);
            if (branch == null) continue;
            String branchCode = branch.optString("branchCode", "");
            JSONObject roms = branch.optJSONObject("roms");
            if (roms == null) continue;
            JSONArray versions = roms.names();
            if (versions == null) continue;
            for (int j = 0; j < versions.length(); j++) {
                String version = versions.optString(j);
                JSONObject item = roms.optJSONObject(version);
                if (item == null || !firstValue(item, "md5", "md5sum", "md5_hash", "checksum").equals("数据源未提供")) continue;
                String cached = downloadPrefs.getString("md5:" + deviceCode + ":" + branchCode + ":" + version, "");
                if (cached.matches("(?i)[0-9a-f]{32}")) {
                    try { item.put("md5", cached); } catch (org.json.JSONException ignored) { }
                }
            }
        }
    }

    private boolean matchesVersionType(JSONObject branch, String selected) {
        String name = branch.optJSONObject("name") == null ? "" : branch.optJSONObject("name").optString("zh", "");
        String englishName = branch.optJSONObject("name") == null ? "" : branch.optJSONObject("name").optString("en", "");
        String normalized = name.toLowerCase(Locale.ROOT);
        String normalizedEnglish = englishName.toLowerCase(Locale.ROOT);
        String branchCode = branch.optString("branchCode", "").toLowerCase(Locale.ROOT);
        String regionCode = branch.optString("region", "").toLowerCase(Locale.ROOT);
        if ("正式版".equals(selected)) {
            return (name.contains("正式版") || normalized.contains("stable")
                    || normalizedEnglish.contains("stable") || branchCode.contains("stable"))
                    && !isBetaBranch(normalized, normalizedEnglish, branchCode);
        }
        if ("Beta".equals(selected)) return isBetaBranch(normalized, normalizedEnglish, branchCode);
        if ("演示机版本".equals(selected)) return name.contains("演示机");
        if ("开发者预览".equals(selected)) return name.contains("开发") || name.contains("预览");
        if ("国际版".equals(selected)) return name.contains("国际") || normalized.contains("global") || "global".equals(regionCode);
        if ("EEA".equals(selected)) return name.contains("EEA") || normalized.contains("eea") || "eea".equals(regionCode);
        if ("俄罗斯".equals(selected)) return name.contains("俄罗斯") || normalized.contains("russia") || "ru".equals(regionCode);
        if ("中国台湾".equals(selected)) return name.contains("台湾") || normalized.contains("taiwan") || "tw".equals(regionCode);
        if ("印度".equals(selected)) return name.contains("印度正式") || normalized.contains("india") || "in".equals(regionCode);
        if ("印度尼西亚".equals(selected)) return name.contains("印度尼西亚") || normalized.contains("indonesia") || "id".equals(regionCode);
        if ("土耳其".equals(selected)) return name.contains("土耳其") || normalized.contains("turkey") || "tr".equals(regionCode);
        return false;
    }

    private boolean isBetaBranch(String normalizedName, String normalizedEnglishName, String branchCode) {
        return normalizedName.contains("beta") || normalizedName.contains("测试")
                || normalizedEnglishName.contains("beta") || normalizedEnglishName.contains("test")
                || branchCode.contains("beta");
    }

    private boolean isSpecialBranch(JSONObject branch) {
        String name = branch.optJSONObject("name") == null ? ""
                : branch.optJSONObject("name").optString("zh", "");
        return name.contains("演示机") || name.contains("开发") || name.contains("预览");
    }

    private void mergeMiFirmwareData(JSONObject target, String deviceCode) {
        try {
            JSONObject miData = new JSONObject();
            miData.put("branches", new JSONArray());
            String hyperOs4 = request(MI_FIRMWARE_HYPEROS4_URL);
            parseMiFirmwareTable(hyperOs4, deviceCode, miData.optJSONArray("branches"), true);
            String devicePage = String.format(Locale.ROOT, MI_FIRMWARE_DEVICE_URL, deviceCode);
            parseMiFirmwareDevicePage(request(devicePage), deviceCode, miData.optJSONArray("branches"));
            mergeMiBranches(target.optJSONArray("branches"), miData.optJSONArray("branches"), true);
        } catch (Exception ignored) {
            // HyperData remains available when MiFirmware is unavailable.
        }
    }

    private void mergeMissingHyperData(JSONObject target, JSONObject hyperData) throws org.json.JSONException {
        JSONArray targetBranches = target.optJSONArray("branches");
        JSONArray hyperBranches = hyperData.optJSONArray("branches");
        if (targetBranches == null || hyperBranches == null) return;
        for (int i = 0; i < hyperBranches.length(); i++) {
            JSONObject sourceBranch = hyperBranches.optJSONObject(i);
            if (sourceBranch == null) continue;
            JSONObject sourceCopy = new JSONObject(sourceBranch.toString());
            JSONObject sourceName = sourceCopy.optJSONObject("name");
            String hyperName = sourceName == null ? "" : sourceName.optString("zh", "");
            String hyperType = hyperName.toLowerCase(Locale.ROOT).contains("beta") ? "beta" : "stable";
            sourceCopy.put("name", new JSONObject().put("zh",
                    "beta".equals(hyperType) ? "Beta" : "Stable"));
            sourceCopy.put("branchCode", "hyper-" + sourceCopy.optString("region", "cn") + "-" + hyperType);
            JSONObject roms = sourceCopy.optJSONObject("roms");
            if (roms != null) {
                JSONArray versions = roms.names();
                if (versions != null) {
                    for (int j = 0; j < versions.length(); j++) {
                        JSONObject item = roms.optJSONObject(versions.optString(j));
                        removeMd5Fields(item);
                        if (item != null && item.optString("aspatch", "").trim().isEmpty()) {
                            String patch = findSecurityPatch(item);
                            if (patch.isEmpty()) patch = findSecurityPatch(sourceCopy);
                            if (!patch.isEmpty()) item.put("aspatch", patch);
                        }
                    }
                }
            }
            JSONArray one = new JSONArray();
            one.put(sourceCopy);
            mergeMiBranches(targetBranches, one, false);
        }
    }

    private JSONObject normalizeHyperData(JSONObject source) throws org.json.JSONException {
        JSONObject normalizedRoot = new JSONObject();
        JSONArray normalizedBranches = new JSONArray();
        JSONArray sourceBranches = source.optJSONArray("branches");
        if (sourceBranches == null) {
            normalizedRoot.put("branches", normalizedBranches);
            return normalizedRoot;
        }
        for (int i = 0; i < sourceBranches.length(); i++) {
            JSONObject branch = sourceBranches.optJSONObject(i);
            if (branch == null) continue;
            JSONObject copy = new JSONObject(branch.toString());
            JSONObject name = copy.optJSONObject("name");
            String branchName = name == null ? "" : name.optString("zh", "");
             String normalizedName = branchName.toLowerCase(Locale.ROOT);
             String branchCode = copy.optString("branchCode", "").toLowerCase(Locale.ROOT);
             boolean beta = normalizedName.contains("beta") || normalizedName.contains("测试")
                     || branchCode.contains("beta");
            boolean stable = normalizedName.contains("stable") || normalizedName.contains("正式")
                    || normalizedName.contains("release") || branchCode.contains("stable")
                    || branchCode.contains("release");
            String regionCode = copy.optString("region", "cn").trim().toLowerCase(Locale.ROOT);
            String kind = beta ? "beta" : stable ? "stable" : "other";
            copy.put("branchCode", "hyper-" + regionCode + "-" + kind + "-" + i);
            copy.put("name", new JSONObject().put("zh", beta ? "Beta" : stable ? "Stable" : branchName));
            copy.put("show", "1");
            JSONObject roms = copy.optJSONObject("roms");
            if (roms != null) {
                JSONArray versions = roms.names();
                if (versions != null) {
                    for (int j = 0; j < versions.length(); j++) {
                        JSONObject item = roms.optJSONObject(versions.optString(j));
                        removeMd5Fields(item);
                        if (item != null && item.optString("aspatch", "").trim().isEmpty()) {
                            String patch = findSecurityPatch(item);
                            if (!patch.isEmpty()) item.put("aspatch", patch);
                        }
                    }
                }
            }
            normalizedBranches.put(copy);
        }
        normalizedRoot.put("branches", normalizedBranches);
        return normalizedRoot;
    }

    private void mergeMiBranches(JSONArray targetBranches, JSONArray sourceBranches, boolean primary) throws org.json.JSONException {
        if (targetBranches == null || sourceBranches == null) return;
        for (int i = 0; i < sourceBranches.length(); i++) {
            JSONObject sourceBranch = sourceBranches.optJSONObject(i);
            if (sourceBranch == null) continue;
            String region = sourceBranch.optString("region", "cn");
            String sourceName = sourceBranch.optJSONObject("name") == null ? "" : sourceBranch.optJSONObject("name").optString("zh", "");
            JSONObject targetBranch = findMatchingBranch(targetBranches, region, sourceName);
            if (targetBranch == null) {
                targetBranches.put(sourceBranch);
                continue;
            }
            JSONObject targetRoms = targetBranch.optJSONObject("roms");
            JSONObject sourceRoms = sourceBranch.optJSONObject("roms");
            if (targetRoms == null || sourceRoms == null) continue;
            JSONArray versions = sourceRoms.names();
            if (versions == null) continue;
            for (int j = 0; j < versions.length(); j++) {
                String version = versions.optString(j);
                JSONObject sourceItem = sourceRoms.optJSONObject(version);
                if (sourceItem == null) continue;
                JSONObject targetItem = targetRoms.optJSONObject(version);
                if (targetItem == null) targetRoms.put(version, sourceItem);
                else if (!primary) mergeMissingFields(targetItem, sourceItem);
            }
        }
    }

    private void mergeMiItem(JSONObject target, JSONObject source) throws org.json.JSONException {
        String recovery = source.optString("recovery", "").trim();
        String fastboot = source.optString("fastboot", "").trim();
        if (recovery.startsWith("http://") || recovery.startsWith("https://")) target.put("recovery", recovery);
        if (fastboot.startsWith("http://") || fastboot.startsWith("https://")) target.put("fastboot", fastboot);
        if (!source.optString("release", "").trim().isEmpty()) target.put("release", source.optString("release"));
    }

    private void mergeMissingFields(JSONObject target, JSONObject source) throws org.json.JSONException {
        JSONArray names = source.names();
        if (names == null) return;
        for (int i = 0; i < names.length(); i++) {
            String key = names.optString(i);
            if (isMd5Field(key)) continue;
            String sourceValue = source.optString(key, "").trim();
            if (isSecurityPatchField(key)) {
                if (target.optString("aspatch", "").trim().isEmpty() && !sourceValue.isEmpty()) {
                    target.put("aspatch", source.opt(key));
                }
            } else if (target.optString(key, "").trim().isEmpty() && !sourceValue.isEmpty()) {
                target.put(key, source.opt(key));
            }
        }
        if (target.optString("aspatch", "").trim().isEmpty()) {
            String patch = findSecurityPatch(source);
            if (!patch.isEmpty()) target.put("aspatch", patch);
        }
    }

    private boolean isMd5Field(String key) {
        return "md5".equalsIgnoreCase(key) || "md5sum".equalsIgnoreCase(key)
                || "md5_hash".equalsIgnoreCase(key) || "checksum".equalsIgnoreCase(key);
    }

    private boolean isSecurityPatchField(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.equals("aspatch") || normalized.equals("securitypatch")
                || normalized.equals("securitypatchdate") || normalized.equals("securitypatchlevel")
                || normalized.equals("patchdate") || normalized.equals("patchlevel")
                || (normalized.contains("security") && normalized.contains("patch"));
    }

    private String findSecurityPatch(JSONObject item) {
        if (item == null) return "";
        JSONArray names = item.names();
        if (names == null) return "";
        for (int i = 0; i < names.length(); i++) {
            String key = names.optString(i);
            Object value = item.opt(key);
            if (isSecurityPatchField(key) && value instanceof String && !((String) value).trim().isEmpty()) {
                return ((String) value).trim();
            }
            if (value instanceof JSONObject) {
                String nested = findSecurityPatch((JSONObject) value);
                if (!nested.isEmpty()) return nested;
            }
        }
        return "";
    }

    private void removeMd5Fields(JSONObject item) {
        if (item == null) return;
        item.remove("md5");
        item.remove("md5sum");
        item.remove("md5_hash");
        item.remove("checksum");
    }

    private JSONObject findMatchingBranch(JSONArray branches, String region, String miName) {
        boolean miBeta = miName.toLowerCase(Locale.ROOT).contains("beta");
            boolean miStable = miName.toLowerCase(Locale.ROOT).contains("stable")
                    || miName.contains("正式") || miName.contains("稳定")
                    || miName.toLowerCase(Locale.ROOT).contains("release");
        for (int i = 0; i < branches.length(); i++) {
            JSONObject branch = branches.optJSONObject(i);
            if (branch == null || !region.equalsIgnoreCase(branch.optString("region", ""))) continue;
            String name = branch.optJSONObject("name") == null ? "" : branch.optJSONObject("name").optString("zh", "");
            String normalized = name.toLowerCase(Locale.ROOT);
            boolean branchBeta = normalized.contains("beta") || branch.optString("branchCode", "").toLowerCase(Locale.ROOT).contains("beta");
            boolean branchStable = normalized.contains("stable") || name.contains("正式") || name.contains("稳定")
                    || normalized.contains("release")
                    || branch.optString("branchCode", "").toLowerCase(Locale.ROOT).contains("stable");
            if ((miBeta && branchBeta) || (miStable && branchStable)) return branch;
        }
        if (!miBeta && !miStable) return null;
        return null;
    }

    private void parseMiFirmwareTable(String html, String deviceCode, JSONArray branches, boolean hyperOs4) {
        Matcher rowMatcher = Pattern.compile("(?is)<tr\\b[^>]*>(.*?)</tr>").matcher(html);
        Pattern cellPattern = Pattern.compile("(?is)<td\\b[^>]*>(.*?)</td>");
        while (rowMatcher.find()) {
            ArrayList<String> cells = new ArrayList<>();
            Matcher cellMatcher = cellPattern.matcher(rowMatcher.group(1));
            while (cellMatcher.find()) cells.add(cellMatcher.group(1));
            if (cells.size() < 11) continue;
            String codename = cleanHtmlCell(cells.get(3));
            if (!deviceCode.equalsIgnoreCase(codename)) continue;
            String region = normalizeMiRegion(cleanHtmlCell(cells.get(2)));
            String branchName = cleanHtmlCell(cells.get(4));
            String packageType = cleanHtmlCell(cells.get(5));
            String version = cleanHtmlCell(cells.get(6));
            String android = cleanHtmlCell(cells.get(7));
            String release = cleanHtmlCell(cells.get(9));
            String href = firstHref(cells.get(10));
            if (version.isEmpty()) continue;
            try {
                JSONObject branch = findOrCreateMiBranch(branches, region, branchName, "mi-" + region + "-" + branchName.toLowerCase(Locale.ROOT));
                JSONObject roms = branch.optJSONObject("roms");
                JSONObject item = roms.optJSONObject(version);
                if (item == null) item = new JSONObject();
                item.put("os", version);
                item.put("android", android);
                item.put("release", release);
                if (packageType.toLowerCase(Locale.ROOT).contains("fastboot")) item.put("fastboot", href);
                else item.put("recovery", href);
                roms.put(version, item);
            } catch (Exception ignored) { }
        }
    }

    private void parseMiFirmwareDevicePage(String html, String deviceCode, JSONArray branches) {
        Matcher sectionMatcher = Pattern.compile("(?is)<h[1-4][^>]*>(.*?)</h[1-4]>(.*?)(?=<h[1-4]|</article>|$)").matcher(html);
        while (sectionMatcher.find()) {
            String heading = cleanHtmlCell(sectionMatcher.group(1));
            String section = sectionMatcher.group(2);
            String version = labeledValue(section, "HyperOS");
            if (version.isEmpty()) continue;
            String android = labeledValue(section, "Android");
            String type = labeledValue(section, "File Type");
            String release = labeledValue(section, "Release Date");
            String href = firstHref(section);
            if (href.isEmpty()) continue;
            try {
                String branchName = type.toLowerCase(Locale.ROOT).contains("beta") ? "Beta" : "Stable";
                JSONObject branch = findOrCreateMiBranch(branches, "cn", branchName, "mi-cn-" + branchName.toLowerCase(Locale.ROOT));
                JSONObject roms = branch.optJSONObject("roms");
                JSONObject item = roms.optJSONObject(version);
                if (item == null) item = new JSONObject();
                item.put("os", version);
                item.put("android", android);
                item.put("release", release);
                if (type.toLowerCase(Locale.ROOT).contains("fastboot") || heading.toLowerCase(Locale.ROOT).contains("fastboot")) item.put("fastboot", href);
                else item.put("recovery", href);
                roms.put(version, item);
            } catch (Exception ignored) { }
        }
    }

    private JSONObject findOrCreateMiBranch(JSONArray branches, String region, String name, String branchCode) throws org.json.JSONException {
        for (int i = 0; i < branches.length(); i++) {
            JSONObject branch = branches.optJSONObject(i);
            if (branch != null && branchCode.equals(branch.optString("branchCode"))) return branch;
        }
        JSONObject branch = new JSONObject();
        branch.put("name", new JSONObject().put("zh", name));
        branch.put("branchCode", branchCode);
        branch.put("region", region);
        branch.put("show", "1");
        branch.put("roms", new JSONObject());
        branches.put(branch);
        return branch;
    }

    private String labeledValue(String html, String label) {
        Matcher matcher = Pattern.compile("(?is)" + Pattern.quote(label) + "\\s*:?\\s*</?[^>]*>\\s*([^<]+)").matcher(html);
        return matcher.find() ? cleanHtmlCell(matcher.group(1)) : "";
    }

    private String firstHref(String html) {
        Matcher matcher = Pattern.compile("(?is)<a\\b[^>]*href=[\\\"']([^\\\"']+)[\\\"']").matcher(html);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private String normalizeMiRegion(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.contains("eea") || normalized.contains("europe")) return "eea";
        if (normalized.contains("global")) return "global";
        if (normalized.contains("india")) return "in";
        if (normalized.contains("indonesia")) return "id";
        if (normalized.contains("russia")) return "ru";
        if (normalized.contains("taiwan")) return "tw";
        return "cn";
    }

    private String cleanHtmlCell(String value) {
        return value.replaceAll("(?is)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private void removeXfuIndexes(JSONObject target) {
        JSONArray names = target.names();
        if (names == null) return;
        for (int i = 0; i < names.length(); i++) {
            JSONObject item = target.optJSONObject(names.optString(i));
            if (item == null) continue;
            item.remove("xfuFastbootIndex");
            item.remove("xfuRecoveryIndex");
        }
    }

    private void appendXfuBranch(JSONArray branches, String sourceName, JSONObject xfuRoms) throws org.json.JSONException {
        if (xfuRoms.length() == 0) return;
        JSONObject targetBranch = new JSONObject();
        String normalized = sourceName.toLowerCase(Locale.ROOT);
        String region = normalized.contains("eea") ? "eea" : normalized.contains("global") ? "global"
                : normalized.contains("india") ? "in" : normalized.contains("russia") ? "ru"
                : normalized.contains("taiwan") ? "tw" : "cn";
        JSONArray names = xfuRoms.names();
        if (names == null) return;
        for (int i = names.length() - 1; i >= 0; i--) {
            String version = names.optString(i);
            JSONObject xfuItem = xfuRoms.optJSONObject(version);
            JSONObject existing = findVersionInRegion(branches, region, version);
            if (xfuItem != null && existing != null) {
                mergeFields(existing, xfuItem);
                xfuRoms.remove(version);
            }
        }
        if (xfuRoms.length() == 0) return;
        String branchCode = "xfu-" + region + "-" + (normalized.contains("beta") ? "beta" : "stable");
         targetBranch.put("name", new JSONObject().put("zh", displayBranchName(sourceName)));
        targetBranch.put("branchCode", branchCode);
        targetBranch.put("region", region);
        targetBranch.put("show", "1");
        targetBranch.put("roms", new JSONObject());
        branches.put(targetBranch);
        JSONObject roms = targetBranch.optJSONObject("roms");
        names = xfuRoms.names();
        if (names == null) return;
        for (int i = 0; i < names.length(); i++) {
            String version = names.optString(i);
            JSONObject xfuItem = xfuRoms.optJSONObject(version);
            if (xfuItem != null) roms.put(version, xfuItem);
        }
    }

    private JSONObject findVersionInRegion(JSONArray branches, String region, String version) {
        for (int i = 0; i < branches.length(); i++) {
            JSONObject branch = branches.optJSONObject(i);
            if (branch == null || !region.equalsIgnoreCase(branch.optString("region", ""))) continue;
            JSONObject roms = branch.optJSONObject("roms");
            JSONObject item = roms == null ? null : roms.optJSONObject(version);
            if (item != null) return item;
        }
        return null;
    }

    private void mergeFields(JSONObject target, JSONObject source) throws org.json.JSONException {
        JSONArray names = source.names();
        if (names == null) return;
        for (int i = 0; i < names.length(); i++) {
            String key = names.optString(i);
            if (!"aspatch".equals(key) || target.optString(key, "").isEmpty()) target.put(key, source.opt(key));
        }
    }

    private JSONObject findHyperDataRom(JSONArray branches, String version) {
        for (int i = 0; i < branches.length(); i++) {
            JSONObject branch = branches.optJSONObject(i);
            if (branch == null) continue;
            JSONObject roms = branch.optJSONObject("roms");
            JSONObject item = roms == null ? null : roms.optJSONObject(version);
            if (item != null) return item;
        }
        return null;
    }

    private void copyIfMissing(JSONObject target, JSONObject source, String key) {
        if (target.optString(key, "").trim().isEmpty() && !source.optString(key, "").trim().isEmpty()) {
            try { target.put(key, source.optString(key)); } catch (org.json.JSONException ignored) { }
        }
    }

    private String resolveXfuUrl(String href) {
        if (href.startsWith("http://") || href.startsWith("https://")) return href;
        return "https://xmfirmwareupdater.com" + href.replace(" ", "%20");
    }

    private JSONObject parseXfuDetail(String html) {
        JSONObject result = new JSONObject();
        String text = html.replaceAll("(?is)<script[^>]*>.*?</script>|<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replaceAll("\\s+", " ");
        Matcher packageMatcher = Pattern.compile("Package\\s+Name\\s*:?\\s*([^ ]+)", Pattern.CASE_INSENSITIVE).matcher(text);
        Matcher md5Matcher = Pattern.compile("MD5(?:\\s+Checksum)?\\s*:?\\s*([0-9a-f]{32})", Pattern.CASE_INSENSITIVE).matcher(text);
        try {
            if (packageMatcher.find()) result.put("package", packageMatcher.group(1).trim());
            if (md5Matcher.find()) result.put("md5", md5Matcher.group(1).trim());
        } catch (org.json.JSONException ignored) {
        }
        return result;
    }

    private void mergeXmMd5(JSONObject target, String deviceCode) {
        try {
            Set<String> versions = new HashSet<>();
            JSONArray branches = target.optJSONArray("branches");
            if (branches == null) return;
            for (int i = 0; i < branches.length(); i++) {
                JSONObject branch = branches.optJSONObject(i);
                JSONObject roms = branch == null ? null : branch.optJSONObject("roms");
                JSONArray names = roms == null ? null : roms.names();
                if (names == null) continue;
                for (int j = 0; j < names.length(); j++) versions.add(names.optString(j));
            }
            if (versions.isEmpty()) return;
            collectXmMd5(request(XM_HYPEROS_URL + deviceCode + "/"), versions, target);
            collectXmMd5(request(XM_HYPEROS_ARCHIVE_URL + deviceCode + "/"), versions, target);
        } catch (Exception ignored) {
            // XM only enriches MD5; MiFirmware and HyperData remain usable when it is unavailable.
        }
    }

    private void collectXmMd5(String html, Set<String> versions, JSONObject target) {
        Matcher rowMatcher = Pattern.compile("(?is)<tr\\b[^>]*>(.*?)</tr>").matcher(html);
        while (rowMatcher.find()) {
            String row = rowMatcher.group(1);
            Matcher versionMatcher = Pattern.compile("(?i)\\b(OS[234]\\.[0-9.]+\\.[A-Z0-9]+)\\b").matcher(cleanHtmlCell(row));
            if (!versionMatcher.find()) continue;
            String version = versionMatcher.group(1);
            if (!versions.contains(version)) continue;
            String detailPath = firstHref(row);
            if (detailPath.isEmpty()) continue;
            try {
                JSONObject detail = parseXfuDetail(request(resolveXfuUrl(detailPath)));
                String md5 = detail.optString("md5", "").trim();
                if (md5.matches("(?i)[0-9a-f]{32}")) putXmMd5(target, version, md5, detail.optString("package", ""));
            } catch (Exception ignored) { }
        }
    }

    private void putXmMd5(JSONObject target, String version, String md5, String packageName) {
        JSONArray branches = target.optJSONArray("branches");
        if (branches == null) return;
        boolean fastboot = packageName.toLowerCase(Locale.ROOT).endsWith(".tgz");
        for (int i = 0; i < branches.length(); i++) {
            JSONObject branch = branches.optJSONObject(i);
            JSONObject roms = branch == null ? null : branch.optJSONObject("roms");
            JSONObject item = roms == null ? null : roms.optJSONObject(version);
            if (item == null) continue;
            String packageUrl = item.optString(fastboot ? "fastboot" : "recovery", "");
            if (packageUrl.isEmpty() || packageName.isEmpty() || packageUrl.contains(packageName)) {
                try { item.put("md5", md5); } catch (org.json.JSONException ignored) { }
            }
        }
    }

    private void prependXfuBranch(JSONArray branches, JSONObject roms, String name, String branchCode) {
        if (roms.length() == 0) return;
        try {
            JSONObject branch = new JSONObject();
            branch.put("name", new JSONObject().put("zh", name));
            branch.put("branchCode", branchCode);
            branch.put("region", "cn");
            branch.put("show", "1");
            branch.put("roms", roms);
            JSONArray reordered = new JSONArray();
            reordered.put(branch);
            for (int i = 0; i < branches.length(); i++) reordered.put(branches.opt(i));
            while (branches.length() > 0) branches.remove(branches.length() - 1);
            for (int i = 0; i < reordered.length(); i++) branches.put(reordered.opt(i));
        } catch (org.json.JSONException ignored) {
        }
    }

    private void addDownloadButton(LinearLayout actions, String title, String file, String version) {
        LinearLayout packageRow = new LinearLayout(this);
        Button download = new Button(this);
        download.setText(title);
        download.setAllCaps(false);
        download.setBackgroundResource(R.drawable.liquid_glass_panel);
        download.setOnClickListener(v -> {
            Haptics.perform(v);
            if (file.isEmpty()) return;
            downloadRom(title, file, version);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1);
        download.setEnabled(!file.isEmpty());
        packageRow.addView(download, lp);
        Button copy = new Button(this);
        copy.setText("复制链接");
        copy.setAllCaps(false);
        copy.setBackgroundResource(R.drawable.liquid_glass_panel);
        copy.setEnabled(!file.isEmpty());
        copy.setContentDescription("复制 " + title + " 下载链接");
        copy.setOnClickListener(v -> {
            Haptics.perform(v);
            String address = downloadUrl(file, version);
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(title, address));
            status.setText(title + " 下载链接已复制");
        });
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(0, dp(44), 1);
        copyLp.setMargins(dp(12), 0, 0, 0);
        packageRow.addView(copy, copyLp);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        if (actions.getChildCount() > 0) rowLp.topMargin = dp(14);
        actions.addView(packageRow, rowLp);
    }

    private void downloadRom(String packageType, String file, String version) {
        String address = downloadUrl(file, version);
        File downloadDir = new File(Environment.getExternalStorageDirectory(), "Download/DsuManager");
        if (!downloadDir.exists() && !downloadDir.mkdirs() && !downloadDir.isDirectory()) {
            showDownloadCard("无法创建下载目录，请检查存储权限");
            return;
        }
        String filename = customFilename == null ? "" : customFilename.getText().toString().trim();
        filename = filename.replaceAll("[\\p{Cntrl}\\/\\\\:*?\"<>|]", "_").trim();
        while (filename.startsWith(".")) filename = filename.substring(1).trim();
        String sourceName = file.substring(file.lastIndexOf('/') + 1);
        int queryStart = sourceName.indexOf('?');
        if (queryStart >= 0) sourceName = sourceName.substring(0, queryStart);
        String extension = sourceName.toLowerCase(Locale.ROOT).endsWith(".tgz") ? ".tgz" : ".zip";
        if (!sourceName.toLowerCase(Locale.ROOT).endsWith(".zip")
                && !sourceName.toLowerCase(Locale.ROOT).endsWith(".tgz")) {
            sourceName += extension;
        }
        final String downloadFile = filename.isEmpty()
                ? sourceName
                : (filename.toLowerCase(Locale.ROOT).endsWith(extension) ? filename : filename + extension);
        File output = new File(downloadDir, downloadFile);
        downloadFileTitle.setText("正在下载: " + output.getName());
        downloadStatus.setText("下载");
        downloadPath.setText("保存到: " + output.getAbsolutePath());
        downloadBytes.setText("已下载 0 B / 总大小获取中");
        downloadPercent.setText("0%");
        downloadProgress.setProgress(0);
        pauseDownloadButton.setText("暂停");
        pauseDownloadButton.setEnabled(true);
        pendingAddress = address;
        pendingOutput = output;
        pendingPackageType = packageType;
        persistedDone = 0;
        persistedTotal = -1;
        saveDownloadState(false, "准备下载...");
        if (!hasStorageAccess()) {
            awaitingPermission = true;
            showDownloadCard(packageType + " 需要“所有文件访问”权限才能保存 ROM");
            promptStorageAccess();
            return;
        }
        startDownload();
    }

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") == PackageManager.PERMISSION_GRANTED;
    }

    private void promptStorageAccess() {
        new AlertDialog.Builder(this)
                .setTitle("需要存储权限")
                .setMessage("下载文件将保存到\n/storage/emulated/0/Download/DsuManager/\n\n请授予本应用“所有文件访问”权限，否则无法写入该目录。")
                .setCancelable(false)
                .setPositiveButton("去授权", (dialog, which) -> {
                    if (Build.VERSION.SDK_INT >= 30) {
                        try {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        } catch (Exception fallback) {
                            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        }
                    } else {
                        requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"}, 4001);
                    }
                })
                .setNegativeButton("取消", (dialog, which) -> {
                    awaitingPermission = false;
                    pendingAddress = null;
                    pendingOutput = null;
                    pendingPackageType = null;
                    downloadCard.setVisibility(View.GONE);
                    downloadActionsRow.setVisibility(View.GONE);
                    status.setText("已取消下载");
                })
                .show();
    }

    @Override public void onResume() {
        super.onResume();
        if (awaitingPermission && pendingOutput != null && hasStorageAccess()) {
            awaitingPermission = false;
            startDownload();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 4001 && awaitingPermission && pendingOutput != null && hasStorageAccess()) {
            awaitingPermission = false;
            startDownload();
        }
    }

    private void startDownload() {
        String address = pendingAddress;
        File output = pendingOutput;
        String packageType = pendingPackageType;
        if (address == null || output == null) return;
        // 通知权限统一在引导页（APP 首页引导）授权，此处不再弹出任何授权弹窗
        boolean retrying = downloadFailed;
        pauseDownload = false;
        cancelDownload = false;
        downloadFailed = false;
        lastProgressBytes = 0;
        lastProgressTime = 0;
        speedWindowBytes = 0;
        speedWindowTime = 0;
        smoothedSpeed = 0;
        if (pageScroll != null) preservedDownloadScrollY = pageScroll.getScrollY();
        boolean scrollToCard = !preserveDownloadScroll && !retrying && preservedDownloadScrollY <= 0;
        preserveDownloadScroll = false;
        showDownloadCard("下载", scrollToCard);
        startDownloadService(address, output, packageType, true);
    }

    private void saveDownloadState(boolean paused, String message) {
        if (downloadPrefs == null || pendingAddress == null || pendingOutput == null) return;
        downloadPrefs.edit()
                .putString("address", pendingAddress)
                .putString("output", pendingOutput.getAbsolutePath())
                .putString("package", pendingPackageType == null ? "下载" : pendingPackageType)
                .putBoolean("paused", paused)
                .putString("message", message == null ? "下载中" : message)
                .putLong("done", persistedDone)
                .putLong("total", persistedTotal)
                .apply();
    }

    private void clearDownloadState() {
        if (downloadPrefs != null) {
            downloadPrefs.edit()
                    .remove("address")
                    .remove("output")
                    .remove("package")
                    .remove("paused")
                    .remove("message")
                    .remove("done")
                    .remove("total")
                    .apply();
        }
    }

    private void restoreDownloadState() {
        if (downloadPrefs == null) return;
        String address = downloadPrefs.getString("address", "");
        String outputPath = downloadPrefs.getString("output", "");
        if (address.isEmpty() || outputPath.isEmpty()) return;
        File output = new File(outputPath);
        File partial = new File(output.getParentFile(), output.getName() + ".download");
        File control = new File(output.getParentFile(), output.getName() + ".aria2");
        if (!output.isFile() && !partial.isFile() && !control.isFile()) {
            clearDownloadState();
            return;
        }
        pendingAddress = address;
        pendingOutput = output;
        pendingPackageType = downloadPrefs.getString("package", "下载");
        downloadPath.setText("保存到: " + output.getAbsolutePath());
        downloadCard.setVisibility(View.VISIBLE);
        downloadActionsRow.setVisibility(View.VISIBLE);
        preservedDownloadScrollY = pageScroll == null ? 0 : pageScroll.getScrollY();
        if (output.isFile()) {
            Aria2Downloader.deleteCheckpointFiles(output);
            downloadStatus.setText("下载完成");
            downloadPercent.setText("100%");
            downloadProgress.setProgress(100);
            downloadActionsRow.setVisibility(View.GONE);
            clearDownloadState();
            return;
        }
        boolean paused = downloadPrefs.getBoolean("paused", true);
        String message = downloadPrefs.getString("message", "等待恢复下载");
        long[] savedProgress = Aria2Downloader.readSavedProgress(output);
        long savedDone = savedProgress[0] >= 0 ? savedProgress[0] : downloadPrefs.getLong("done", 0);
        long savedTotal = savedProgress[1] > 0 ? savedProgress[1] : downloadPrefs.getLong("total", -1);
        persistedDone = savedDone;
        persistedTotal = savedTotal;
        pauseDownload = paused;
        pauseDownloadButton.setText(paused ? "继续" : "暂停");
        downloadFileTitle.setText("正在下载: " + output.getName());
        downloadStatus.setText(paused ? "已暂停，点击继续可断点续传" : "下载");
        if (savedTotal > 0) updateDownloadProgress(savedDone, savedTotal);
        if (!paused) startDownloadService(address, output, pendingPackageType, false);
    }

    private void startDownloadService(String address, File output, String packageType, boolean newTask) {
        // 小米固定使用16线程+8MB分片（最优配置）
        Intent intent = new Intent(this, DownloadService.class)
                .setAction(newTask ? DownloadService.ACTION_START : DownloadService.ACTION_RESUME)
                .putExtra(DownloadService.EXTRA_ADDRESS, address)
                .putExtra(DownloadService.EXTRA_OUTPUT, output.getAbsolutePath())
                .putExtra(DownloadService.EXTRA_PACKAGE, packageType)
                .putExtra("download_threads", 16)
                .putExtra("download_chunk_mb", 8L);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
    }

    // 下载执行已全部收敛到 DownloadService + Aria2Downloader（aria2c 多线程引擎），
    // Activity 仅负责 UI 展示与指令下发。

    private void updateDownloadProgress(long downloaded, long total) {
        int percent = total > 0 ? (int) (downloaded * 100 / total) : 0;
        persistedDone = downloaded;
        persistedTotal = total;
        downloadProgress.setProgress(percent);
        downloadPercent.setText(total > 0 ? percent + "%" : "--");
        long now = android.os.SystemClock.elapsedRealtime();
        lastProgressBytes = downloaded;
        if (speedWindowTime == 0 || downloaded < speedWindowBytes) {
            speedWindowBytes = downloaded;
            speedWindowTime = now;
        }
        long elapsed = now - speedWindowTime;
        long delta = downloaded - speedWindowBytes;
        // aria2c 速度广播（每秒）优先；无广播时回退到字节增量测速
        if (elapsed >= 2000 && delta >= 0) {
            long measured = delta * 1000L / elapsed;
            if (measured > 0) smoothedSpeed = smoothedSpeed == 0 ? measured : (smoothedSpeed * 3 + measured) / 4;
            speedWindowBytes = downloaded;
            speedWindowTime = now;
        }
        if (!pauseDownload && total > downloaded) {
            long remainingSeconds = lastAriaEta >= 0 ? lastAriaEta
                    : (smoothedSpeed > 0 ? (total - downloaded) / smoothedSpeed : -1);
            downloadStatus.setText("预计剩余 " + formatRemainingTime(remainingSeconds));
        }
        String speed = smoothedSpeed > 0 ? " · " + formatBytes(smoothedSpeed) + "/s" : "";
        downloadBytes.setText(total > 0
                ? "已下载 " + formatBytes(downloaded) + " / 总大小 " + formatBytes(total) + speed
                : "已下载 " + formatBytes(downloaded) + " / 总大小获取中" + speed);
        saveDownloadState(pauseDownload, downloadStatus.getText().toString());
    }

    private void continueDownload() {
        if (pageScroll != null) preservedDownloadScrollY = pageScroll.getScrollY();
        sendDownloadCommand(DownloadService.ACTION_RESUME);
        runOnUiThread(() -> {
            pauseDownloadButton.setText("暂停");
            downloadStatus.setText("正在继续下载...");
            saveDownloadState(false, "正在继续下载...");
        });
    }

    private void restartDownload() {
        if (pendingAddress == null || pendingOutput == null) return;
        startDownload();
    }

    private String downloadUrl(String file, String version) {
        String value = file == null ? "" : file.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) {
            int schemeEnd = value.indexOf("://");
            int pathStart = schemeEnd < 0 ? -1 : value.indexOf('/', schemeEnd + 3);
            if (pathStart >= 0) value = value.substring(pathStart);
            else value = "/" + value.substring(schemeEnd + 3);
        }
        value = value.replaceFirst("^/+", "");
        if (value.startsWith("OS") || value.startsWith("V")) return downloadBase() + value;
        String directory = version == null ? "" : version.trim();
        String path = directory.isEmpty() ? value : directory + "/" + value;
        String lowerPath = path.toLowerCase(Locale.ROOT);
        if (!lowerPath.endsWith(".zip") && !lowerPath.endsWith(".tgz")) path += ".zip";
        String address = downloadBase() + path;
        return address;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
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

    private String downloadBase() {
        String selected = node == null || node.getSelectedItem() == null ? "阿里云" : node.getSelectedItem().toString();
        if ("CDN.ORG".equals(selected)) return "https://cdnorg.d.miui.com/";
        if ("BN".equals(selected) || "BigOTA".equals(selected)) return "https://bigota.d.miui.com/";
        if ("HugeOTA".equals(selected)) return "https://hugeota.d.miui.com/";
        if ("阿里云".equals(selected)) return "https://bkt-sgp-miui-ota-update-alisgp.oss-ap-southeast-1.aliyuncs.com/";
        return XIAOMI_DOWNLOAD_BASE;
    }

    private String request(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
         connection.setConnectTimeout(4000);
         connection.setReadTimeout(8000);
        connection.setRequestProperty("User-Agent", "Dsu-Manager-ROM/" + BuildConfig.VERSION_NAME);
        try {
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) throw new java.io.IOException("HTTP " + connection.getResponseCode());
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            return body.toString();
        } finally {
            connection.disconnect();
        }
    }

    private static final class Device {
        final String code;
        final JSONObject name;
        String nameZh = "";
        String nameEn = "";
        String series = "";
        String group = "";
        String brand = "";
        String category = "";
        float osScore = 0f;       // 最新支持的 HyperOS 大版本
        float androidScore = 0f;  // 最新 Android 版本
        int fileIndex = 0;        // 清单顺序（越大越新）
        Device(String code, JSONObject name) { this.code = code; this.name = name == null ? new JSONObject() : name; }
    }
    
    // 小米页面使用默认 28dp 圆角的玻璃效果
    private LiquidGlassPanel glass() { return new LiquidGlassPanel(this); }
}
