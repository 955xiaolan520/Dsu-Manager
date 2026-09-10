package com.probiotics.xiaoni;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class VivoActivity extends Activity {
    private static final String[] TAB_LABELS = {"手机", "平板", "手动输入", "穿戴设备"};
    private static final String TAB_PHONE = "手机";
    private static final String TAB_TABLET = "平板";
    private static final String TAB_MANUAL = "手动输入";
    private static final String TAB_WEARABLE = "穿戴设备";
    
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, List<String>> categoriesByTab = new HashMap<>();
    private final Map<String, List<Device>> devicesByTab = new HashMap<>();
    private JSONObject catalog;
    
    private Button[] typeTabs;
    private LinearLayout[] panels;
    private LiquidGlassIndicator tabIndicator;
    private LinearLayout tabRow;
    private LinearLayout tabItems;
    private android.animation.ValueAnimator tabPulse;
    private int tabIndicatorLeft = -1;
    private boolean tabDragging = false;
    private int tabDragTarget = -1;
    private float tabDragStartX = 0f;
    private Runnable tabLongPress;
    private android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    
    // 手机面板控件
    private Spinner phoneCategorySpinner;
    private Spinner phoneDeviceSpinner;
    private EditText phoneVersionInput;
    private Spinner phoneAndroidVersion;
    private Spinner phonePackageType;
    private Spinner phoneFullOrIncremental;
    private EditText phoneSerialInput;
    
    // 平板面板控件
    private Spinner tabletCategorySpinner;
    private Spinner tabletDeviceSpinner;
    private EditText tabletVersionInput;
    private Spinner tabletAndroidVersion;
    private Spinner tabletPackageType;
    private Spinner tabletFullOrIncremental;
    private EditText tabletSerialInput;
    
    // 手动输入面板控件
    private EditText manualPdInput;
    private EditText manualVInput;
    private EditText manualVersionInput;
    private Spinner manualAndroidVersion;
    private Spinner manualPackageType;
    private Spinner manualFullOrIncremental;
    private EditText manualSerialInput;
    
    // 穿戴设备面板控件
    private Spinner wearableCategorySpinner;
    private Spinner wearableDeviceSpinner;
    private EditText wearableVersionInput;
    private Spinner wearableAndroidVersion;
    private Spinner wearablePackageType;
    private Spinner wearableFullOrIncremental;
    private EditText wearableSerialInput;
    
    private TextView status;
    private LinearLayout results;
    private ScrollView pageScroll;
    private int currentTab = 0;
    
    // 下载相关组件
    private LiquidGlassPanel downloadCard;
    private TextView downloadFileTitle;
    private TextView downloadStatus;
    private TextView downloadPercent;
    private TextView downloadBytes;
    private TextView downloadPath;
    private ProgressBar downloadProgress;
    private Button pauseDownloadButton;
    private Button cancelDownloadButton;
    private boolean downloadPaused = false;
    private long speedWindowBytes = 0;
    private long speedWindowTime = 0;
    private long smoothedSpeed = 0;
    
    private final android.content.BroadcastReceiver downloadReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context context, Intent intent) {
            if (!DownloadService.ACTION_UPDATE.equals(intent.getAction())) return;
            if (downloadStatus == null || downloadProgress == null) return; // 防止未初始化
            
            String state = intent.getStringExtra(DownloadService.EXTRA_STATE);
            long done = intent.getLongExtra(DownloadService.EXTRA_DONE, -1);
            long total = intent.getLongExtra(DownloadService.EXTRA_TOTAL, -1);
            
            // 只在终止状态（完成/失败）时更新 downloadStatus
            if (state != null && ("下载完成".equals(state) || state.startsWith("下载失败") || state.startsWith("下载地址无效"))) {
                downloadStatus.setText(state);
                speedWindowBytes = 0;
                speedWindowTime = 0;
                smoothedSpeed = 0;
            }
            
            if (done >= 0) updateDownloadProgress(done, total);
            
            if ("下载完成".equals(state)) {
                downloadProgress.setProgress(100);
                downloadPercent.setText("100%");
                if (pauseDownloadButton != null) pauseDownloadButton.setVisibility(View.GONE);
                if (cancelDownloadButton != null) cancelDownloadButton.setText("关闭");
                clearDownloadState(); // 完成后清除持久化状态
            } else if (state != null && state.startsWith("下载失败")) {
                if (pauseDownloadButton != null) pauseDownloadButton.setText("重试");
                clearDownloadState(); // 失败后清除持久化状态
            }
        }
    };

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }
    
    private android.graphics.drawable.Drawable createVivoGradientBackground() {
        // Vivo 蓝绿渐变：顶部天蓝，中部青绿，下部深蓝绿，清新自然
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
        buildUi();
        loadCatalog();
    }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        Haptics.onTouch(getWindow().getDecorView(), event);
        return super.dispatchTouchEvent(event);
    }
    
    @Override public void onBackPressed() {
        finish();
        overridePendingTransition(R.anim.flip_in, R.anim.flip_out);
    }
    
    @Override protected void onResume() {
        super.onResume();
        if (downloadReceiver != null) {
            android.content.IntentFilter filter = new android.content.IntentFilter(DownloadService.ACTION_UPDATE);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(downloadReceiver, filter);
            }
            restoreDownloadState(); // 恢复下载状态
            sendDownloadCommand(DownloadService.ACTION_QUERY);
        }
    }
    
    @Override protected void onPause() {
        if (downloadReceiver != null) {
            try {
                unregisterReceiver(downloadReceiver);
            } catch (IllegalArgumentException ignored) {
                // 广播接收器可能已经注销
            }
        }
        super.onPause();
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(22));

        LiquidGlassPanel header = glass();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(5), 0, dp(8), 0);
        Button back = glassButton("<", 20);
        back.setOnClickListener(v -> {
            finish();
            overridePendingTransition(R.anim.flip_in, R.anim.flip_out);
        });
        header.addView(back, new LinearLayout.LayoutParams(dp(42), dp(48)));
        TextView title = label("vivo OTA 更新中心", 23, 0xff142037);
        title.setTypeface(null, 1);
        title.setGravity(Gravity.CENTER);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1));
        content.addView(header, margins(-1, 56, 0, 0, 16));

        TextView source = label("vivo / iQOO · OriginOS 官方系统更新", 12, 0xff596579);
        source.setPadding(dp(13), dp(9), dp(13), dp(9));
        source.setBackgroundResource(R.drawable.liquid_glass_panel);
        content.addView(source, margins(-1, -2, 0, 0, 16));

        android.widget.FrameLayout tabFrame = new android.widget.FrameLayout(this);
        tabFrame.setPadding(dp(2), dp(2), dp(2), dp(2));
        tabFrame.setBackgroundResource(R.drawable.navigation_glass_bg);
        
        // Tab 按钮行
        tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setGravity(Gravity.CENTER);
        tabRow.setClipChildren(false);
        tabFrame.addView(tabRow, new android.widget.FrameLayout.LayoutParams(-1, dp(64)));
        
        // 创建 tabItems 容器包裹所有 Tab 按钮，用于整体缩放动画
        tabItems = new LinearLayout(this);
        tabItems.setOrientation(LinearLayout.HORIZONTAL);
        tabItems.setGravity(Gravity.CENTER);
        tabItems.setClipChildren(false);
        tabRow.addView(tabItems, new LinearLayout.LayoutParams(-1, dp(64)));
        
        typeTabs = new Button[TAB_LABELS.length];
        for (int i = 0; i < TAB_LABELS.length; i++) {
            final int index = i;
            typeTabs[i] = createTabButton(TAB_LABELS[i]);
            typeTabs[i].setTextSize(12);
            typeTabs[i].setGravity(Gravity.CENTER);
            typeTabs[i].setTextColor(i == 0 ? 0xff17334f : 0xff5a6b82);
            typeTabs[i].setBackgroundColor(android.graphics.Color.TRANSPARENT);
            typeTabs[i].setPadding(0, 0, 0, 0);
            typeTabs[i].setOnTouchListener((v, event) -> handleTabGesture(v, event, index));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(64), 1);
            lp.setMargins(dp(2), 0, dp(2), 0);
            tabItems.addView(typeTabs[i], lp);
        }
        
        // 指示器（在按钮之后添加，确保正确的 z-order）
        tabIndicator = new LiquidGlassIndicator(this);
        tabIndicator.setElevation(dp(4));
        tabFrame.addView(tabIndicator, new android.widget.FrameLayout.LayoutParams(dp(62), dp(48)));
        
        content.addView(tabFrame, margins(-1, 68, 0, 0, 10));

        panels = new LinearLayout[4];
        panels[0] = buildPhonePanel();
        panels[1] = buildTabletPanel();
        panels[2] = buildManualPanel();
        panels[3] = buildWearablePanel();
        
        for (LinearLayout panel : panels) {
            content.addView(panel, new LinearLayout.LayoutParams(-1, -2));
        }

        // 下载卡片（在 panels 之后，查询按钮下方）
        downloadCard = glass();
        downloadCard.setOrientation(LinearLayout.VERTICAL);
        downloadCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        downloadCard.addView(label("下载", 17, 0xff20375b), margins(-1, -2, 0, 0, 8));
        
        downloadFileTitle = label("正在下载: 未开始", 14, 0xff20375b);
        downloadFileTitle.setSingleLine(true);
        downloadFileTitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        downloadCard.addView(downloadFileTitle, margins(-1, -2, 0, 0, 8));
        
        LinearLayout progressHeader = new LinearLayout(this);
        progressHeader.setOrientation(LinearLayout.HORIZONTAL);
        downloadStatus = label("预计剩余时间 --", 13, 0xff596579);
        progressHeader.addView(downloadStatus, new LinearLayout.LayoutParams(0, -2, 1));
        downloadPercent = label("0%", 15, 0xff20375b);
        downloadPercent.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        progressHeader.addView(downloadPercent, new LinearLayout.LayoutParams(-2, -2));
        downloadCard.addView(progressHeader, margins(-1, -2, 0, 0, 8));
        
        downloadProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        downloadProgress.setMax(100);
        if (Build.VERSION.SDK_INT >= 21) {
            downloadProgress.setProgressDrawable(getDrawable(R.drawable.progress_bar));
        } else {
            downloadProgress.setProgressDrawable(getResources().getDrawable(R.drawable.progress_bar));
        }
        downloadCard.addView(downloadProgress, margins(-1, 8, 8, 0, 8));
        
        downloadBytes = label("", 12, 0xff596579);
        downloadCard.addView(downloadBytes, margins(-1, -2, 0, 0, 6));
        downloadPath = label("", 11, 0xff7a8798);
        downloadPath.setMaxLines(2);
        downloadCard.addView(downloadPath, margins(-1, -2, 0, 0, 8));
        
        LinearLayout downloadActions = new LinearLayout(this);
        downloadActions.setOrientation(LinearLayout.HORIZONTAL);
        pauseDownloadButton = glassButton("暂停", 13);
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
        downloadActions.addView(pauseDownloadButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        
        View downloadSpacer = new View(this);
        downloadActions.addView(downloadSpacer, new LinearLayout.LayoutParams(dp(12), dp(44)));
        
        cancelDownloadButton = glassButton("取消", 13);
        cancelDownloadButton.setOnClickListener(v -> {
            if ("关闭".contentEquals(cancelDownloadButton.getText())) {
                downloadCard.setVisibility(View.GONE);
                clearDownloadState(); // 关闭时清除持久化状态
                return;
            }
            sendDownloadCommand(DownloadService.ACTION_CANCEL);
            downloadCard.setVisibility(View.GONE);
            clearDownloadState(); // 取消时清除持久化状态
        });
        downloadActions.addView(cancelDownloadButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        downloadCard.addView(downloadActions, margins(-1, 44, 0, 0, 0));
        content.addView(downloadCard, margins(-1, -2, 0, 0, 16));
        downloadCard.setVisibility(View.GONE);

        status = label("正在加载 vivo 设备分类...", 13, 0xff596579);
        status.setPadding(dp(12), dp(7), dp(12), dp(7));
        content.addView(status, margins(-1, -2, 0, 12, 0));

        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        content.addView(results, margins(-1, -2, 0, 0, 16));

        // 固定在页面底部的协议来源
        LiquidGlassPanel sourcePanel = glass();
        sourcePanel.setOrientation(LinearLayout.VERTICAL);
        sourcePanel.setPadding(dp(14), dp(10), dp(14), dp(10));
        TextView sourceInfo = label("协议来源: JerryTse-OSS 还有 GitHub用户 mytiantian001 公开的源码", 12, 0xff596579);
        sourceInfo.setGravity(Gravity.CENTER);
        sourceInfo.setLineSpacing(dp(3), 1f);
        sourcePanel.addView(sourceInfo);
        content.addView(sourcePanel, margins(-1, -2, 16, 0, 8));

        pageScroll = new ScrollView(this);
        pageScroll.setFillViewport(true);
        pageScroll.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        pageScroll.addView(content);
        
        FrameLayout root = new FrameLayout(this);
        root.setBackground(createVivoGradientBackground());
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                view.setPadding(0, bars.top, 0, 0);
            } else {
                view.setPadding(0, insets.getSystemWindowInsetTop(), 0, 0);
            }
            return insets;
        });
        root.addView(pageScroll, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        // 延迟初始化 Tab 指示器位置和初始状态
        tabRow.postDelayed(() -> {
            switchTab(0);  // 等待布局完成后再初始化
        }, 100);
    }

    private LinearLayout buildPhonePanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);

        phoneCategorySpinner = optionSpinner("选择设备系列");
        phoneCategorySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateDeviceList(TAB_PHONE, phoneCategorySpinner, phoneDeviceSpinner);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        panel.addView(wrapSpinner(phoneCategorySpinner), margins(-1, 52, 0, 0, 12));

        phoneDeviceSpinner = optionSpinner("选择具体机型");
        panel.addView(wrapSpinner(phoneDeviceSpinner), margins(-1, 52, 0, 0, 12));

        phoneVersionInput = input("系统版本，例如 16.1.21.17.W10");
        panel.addView(phoneVersionInput, margins(-1, 50, 0, 0, 12));

        // Android 版本和包类型放在同一行
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        phoneAndroidVersion = optionSpinner("安卓版本");
        ArrayAdapter<String> phoneAndroidAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"安卓 17", "安卓 16", "安卓 15", "安卓 14", "安卓 13", "安卓 12"});
        phoneAndroidAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        phoneAndroidVersion.setAdapter(phoneAndroidAdapter);
        row1.addView(wrapSpinner(phoneAndroidVersion), new LinearLayout.LayoutParams(0, dp(52), 1));
        
        View spacer1 = new View(this);
        row1.addView(spacer1, new LinearLayout.LayoutParams(dp(12), dp(52)));
        
        phonePackageType = optionSpinner("包类型");
        ArrayAdapter<String> phonePackageAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"正式版", "尝鲜包", "公测版", "内测版"});
        phonePackageAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        phonePackageType.setAdapter(phonePackageAdapter);
        row1.addView(wrapSpinner(phonePackageType), new LinearLayout.LayoutParams(0, dp(52), 1));
        panel.addView(row1, margins(-1, 52, 0, 0, 12));

        phoneFullOrIncremental = optionSpinner("包完整性");
        ArrayAdapter<String> phoneFullOrIncrementalAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"完整包", "增量包"});
        phoneFullOrIncrementalAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        phoneFullOrIncremental.setAdapter(phoneFullOrIncrementalAdapter);
        panel.addView(wrapSpinner(phoneFullOrIncremental), margins(-1, 52, 0, 0, 12));

        phoneSerialInput = input("序列号，可留空，默认 A0000000000000A");
        panel.addView(phoneSerialInput, margins(-1, 50, 0, 0, 12));

        Button queryBtn = glassButton("查询官方更新", 14);
        queryBtn.setOnClickListener(v -> executeQuery());
        panel.addView(queryBtn, margins(-1, 48, 0, 0, 12));

        return panel;
    }

    private LinearLayout buildTabletPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);

        tabletCategorySpinner = optionSpinner("选择设备系列");
        tabletCategorySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateDeviceList(TAB_TABLET, tabletCategorySpinner, tabletDeviceSpinner);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        panel.addView(wrapSpinner(tabletCategorySpinner), margins(-1, 52, 0, 0, 12));

        tabletDeviceSpinner = optionSpinner("选择具体机型");
        panel.addView(wrapSpinner(tabletDeviceSpinner), margins(-1, 52, 0, 0, 12));

        tabletVersionInput = input("系统版本，例如 16.1.21.17.W10");
        panel.addView(tabletVersionInput, margins(-1, 50, 0, 0, 12));

        // Android 版本和包类型放在同一行
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        tabletAndroidVersion = optionSpinner("安卓版本");
        ArrayAdapter<String> tabletAndroidAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"安卓 17", "安卓 16", "安卓 15", "安卓 14", "安卓 13", "安卓 12"});
        tabletAndroidAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        tabletAndroidVersion.setAdapter(tabletAndroidAdapter);
        row1.addView(wrapSpinner(tabletAndroidVersion), new LinearLayout.LayoutParams(0, dp(52), 1));
        
        View spacer1 = new View(this);
        row1.addView(spacer1, new LinearLayout.LayoutParams(dp(12), dp(52)));
        
        tabletPackageType = optionSpinner("包类型");
        ArrayAdapter<String> tabletPackageAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"正式版", "尝鲜包", "公测版", "内测版"});
        tabletPackageAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        tabletPackageType.setAdapter(tabletPackageAdapter);
        row1.addView(wrapSpinner(tabletPackageType), new LinearLayout.LayoutParams(0, dp(52), 1));
        panel.addView(row1, margins(-1, 52, 0, 0, 12));

        tabletFullOrIncremental = optionSpinner("包完整性");
        ArrayAdapter<String> tabletFullOrIncrementalAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"完整包", "增量包"});
        tabletFullOrIncrementalAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        tabletFullOrIncremental.setAdapter(tabletFullOrIncrementalAdapter);
        panel.addView(wrapSpinner(tabletFullOrIncremental), margins(-1, 52, 0, 0, 12));

        tabletSerialInput = input("序列号，可留空，默认 A0000000000000A");
        panel.addView(tabletSerialInput, margins(-1, 50, 0, 0, 12));

        Button queryBtn = glassButton("查询官方更新", 14);
        queryBtn.setOnClickListener(v -> executeQuery());
        panel.addView(queryBtn, margins(-1, 48, 0, 0, 12));

        return panel;
    }

    private LinearLayout buildManualPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);

        manualPdInput = input("PD 设备型号，例如 PD2454");
        panel.addView(manualPdInput, margins(-1, 50, 0, 0, 12));

        manualVInput = input("V 软件型号，例如 V2454A");
        panel.addView(manualVInput, margins(-1, 50, 0, 0, 12));

        manualVersionInput = input("系统版本，例如 16.1.21.17.W10");
        panel.addView(manualVersionInput, margins(-1, 50, 0, 0, 12));

        // Android 版本和包类型放在同一行
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        manualAndroidVersion = optionSpinner("安卓版本");
        manualAndroidVersion.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"安卓 17", "安卓 16", "安卓 15", "安卓 14", "安卓 13", "安卓 12"}));
        row1.addView(wrapSpinner(manualAndroidVersion), new LinearLayout.LayoutParams(0, dp(52), 1));
        
        View spacer1 = new View(this);
        row1.addView(spacer1, new LinearLayout.LayoutParams(dp(12), dp(52)));
        
        manualPackageType = optionSpinner("包类型");
        manualPackageType.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"正式版", "尝鲜包", "公测版", "内测版"}));
        row1.addView(wrapSpinner(manualPackageType), new LinearLayout.LayoutParams(0, dp(52), 1));
        panel.addView(row1, margins(-1, 52, 0, 0, 12));

        manualFullOrIncremental = optionSpinner("包完整性");
        manualFullOrIncremental.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"完整包", "增量包"}));
        panel.addView(wrapSpinner(manualFullOrIncremental), margins(-1, 52, 0, 0, 12));

        manualSerialInput = input("序列号，可留空，默认 A0000000000000A");
        panel.addView(manualSerialInput, margins(-1, 50, 0, 0, 12));

        Button queryBtn = glassButton("查询官方更新", 14);
        queryBtn.setOnClickListener(v -> executeQuery());
        panel.addView(queryBtn, margins(-1, 48, 0, 0, 12));

        return panel;
    }

    private LinearLayout buildWearablePanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);

        wearableCategorySpinner = optionSpinner("选择设备系列");
        wearableCategorySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateDeviceList(TAB_WEARABLE, wearableCategorySpinner, wearableDeviceSpinner);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        panel.addView(wrapSpinner(wearableCategorySpinner), margins(-1, 52, 0, 0, 12));

        wearableDeviceSpinner = optionSpinner("选择具体机型");
        panel.addView(wrapSpinner(wearableDeviceSpinner), margins(-1, 52, 0, 0, 12));

        wearableVersionInput = input("系统版本，例如 16.1.21.17.W10");
        panel.addView(wearableVersionInput, margins(-1, 50, 0, 0, 12));

        // Android 版本和包类型放在同一行
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        wearableAndroidVersion = optionSpinner("安卓版本");
        wearableAndroidVersion.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"安卓 17", "安卓 16", "安卓 15", "安卓 14", "安卓 13", "安卓 12"}));
        row1.addView(wrapSpinner(wearableAndroidVersion), new LinearLayout.LayoutParams(0, dp(52), 1));
        
        View spacer1 = new View(this);
        row1.addView(spacer1, new LinearLayout.LayoutParams(dp(12), dp(52)));
        
        wearablePackageType = optionSpinner("包类型");
        wearablePackageType.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"正式版", "尝鲜包", "公测版", "内测版"}));
        row1.addView(wrapSpinner(wearablePackageType), new LinearLayout.LayoutParams(0, dp(52), 1));
        panel.addView(row1, margins(-1, 52, 0, 0, 12));

        wearableFullOrIncremental = optionSpinner("包完整性");
        wearableFullOrIncremental.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"完整包", "增量包"}));
        panel.addView(wrapSpinner(wearableFullOrIncremental), margins(-1, 52, 0, 0, 12));

        wearableSerialInput = input("序列号，可留空，默认 A0000000000000A");
        panel.addView(wearableSerialInput, margins(-1, 50, 0, 0, 12));

        Button queryBtn = glassButton("查询官方更新", 14);
        queryBtn.setOnClickListener(v -> executeQuery());
        panel.addView(queryBtn, margins(-1, 48, 0, 0, 12));

        return panel;
    }

    private void switchTab(int index) {
        currentTab = index;
        for (int i = 0; i < typeTabs.length; i++) {
            boolean selected = i == index;
            // 对齐 OPPO 实现：使用 alpha + typeface(bold) + 文字颜色
            typeTabs[i].setAlpha(selected ? 1f : 0.55f);
            typeTabs[i].setTypeface(null, selected ? 1 : 0);
            typeTabs[i].setTextColor(selected ? 0xff17334f : 0xff5a6b82);
            panels[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            
            // 添加每个按钮独立的文字缩放动画
            animateButtonScale(typeTabs[i], selected);
        }
        
        moveTabIndicator(index, true);
        
        results.removeAllViews();
        if (index == 0) {
            status.setText("请选择设备系列和机型，输入系统版本后查询");
        } else if (index == 1) {
            status.setText("请选择平板设备系列和机型，输入系统版本后查询");
        } else if (index == 2) {
            status.setText("请输入 PD 和 V 型号，以及系统版本后查询");
        } else {
            status.setText("请选择穿戴设备系列和机型，输入系统版本后查询");
        }
    }
    
    // 每个按钮独立的文字缩放动画
    private void animateButtonScale(Button button, boolean selected) {
        float fromScale = button.getScaleX();
        float toScale = selected ? 1.08f : 1.0f;
        android.animation.ValueAnimator scaleAnim = android.animation.ValueAnimator.ofFloat(fromScale, toScale);
        scaleAnim.setDuration(280);
        scaleAnim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        scaleAnim.addUpdateListener(a -> {
            float scale = (Float) a.getAnimatedValue();
            button.setScaleX(scale);
            button.setScaleY(scale);
        });
        scaleAnim.start();
    }
    
    private void moveTabIndicator(int index, boolean animated) {
        if (tabIndicator == null || tabItems == null || tabItems.getChildCount() == 0) return;
        
        // 切换前先停止呼吸动画
        stopIndicatorBreathing();
        
        View target = tabItems.getChildAt(index);
        int width = dp(62);
        int targetLeft = tabItems.getLeft() + target.getLeft() + (target.getWidth() - width) / 2;
        android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) tabIndicator.getLayoutParams();
        lp.width = width;
        lp.height = dp(48);
        lp.leftMargin = tabIndicatorLeft < 0 ? targetLeft : tabIndicatorLeft;
        lp.topMargin = dp(8);
        tabIndicator.setLayoutParams(lp);
        if (!animated || tabIndicatorLeft < 0) { 
            tabIndicatorLeft = targetLeft; 
            lp.leftMargin = targetLeft; 
            tabIndicator.setLayoutParams(lp);
            startIndicatorBreathing(); // 启动呼吸动画
            return; 
        }
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
                android.widget.FrameLayout.LayoutParams settled = (android.widget.FrameLayout.LayoutParams) tabIndicator.getLayoutParams();
                settled.leftMargin = targetLeft;
                tabIndicator.setTranslationX(0f);
                tabIndicator.setScaleX(1f);
                tabIndicator.setScaleY(1f);
                tabIndicator.setLayoutParams(settled);
                startIndicatorBreathing(); // 动画结束后启动呼吸动画
            }
        });
        pulseTabNavigation(); // 触发 Tab 文字跟随收缩动画
        flow.start();
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
    
    private android.animation.ValueAnimator breathingAnimator;
    
    private void startIndicatorBreathing() {
        if (breathingAnimator != null) breathingAnimator.cancel();
        if (tabIndicator == null) return;
        
        breathingAnimator = android.animation.ValueAnimator.ofFloat(0.85f, 1.0f);
        breathingAnimator.setDuration(1200);
        breathingAnimator.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        breathingAnimator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        breathingAnimator.addUpdateListener(a -> {
            if (tabIndicator != null) {
                tabIndicator.setAlpha((Float) a.getAnimatedValue());
            }
        });
        breathingAnimator.start();
    }
    
    private void stopIndicatorBreathing() {
        if (breathingAnimator != null) {
            breathingAnimator.cancel();
            breathingAnimator = null;
        }
        if (tabIndicator != null) tabIndicator.setAlpha(1.0f);
    }
    
    private boolean handleTabGesture(View view, android.view.MotionEvent event, int pressedTab) {
        switch (event.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
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
            case android.view.MotionEvent.ACTION_MOVE:
                if (!tabDragging && Math.abs(event.getRawX() - tabDragStartX) >= dp(12)) {
                    tabDragging = true;
                    if (tabLongPress != null) mainHandler.removeCallbacks(tabLongPress);
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                    if (tabIndicator != null) tabIndicator.setLiquidPressed(true);
                }
                if (tabDragging) previewTabTarget(event.getRawX());
                return true;
            case android.view.MotionEvent.ACTION_UP:
                if (tabLongPress != null) mainHandler.removeCallbacks(tabLongPress);
                if (tabDragging) {
                    if (tabIndicator != null) tabIndicator.setLiquidPressed(false);
                    switchTab(tabDragTarget < 0 ? pressedTab : tabDragTarget);
                } else {
                    Haptics.perform(view);
                    switchTab(pressedTab);
                }
                tabDragging = false;
                return true;
            case android.view.MotionEvent.ACTION_CANCEL:
                if (tabLongPress != null) mainHandler.removeCallbacks(tabLongPress);
                if (tabIndicator != null) tabIndicator.setLiquidPressed(false);
                tabDragging = false;
                return true;
            default:
                return true;
        }
    }
    
    private void previewTabTarget(float rawX) {
        if (tabRow == null || tabRow.getChildCount() == 0) return;
        int[] location = new int[2];
        tabRow.getLocationOnScreen(location);
        float relativeX = rawX - location[0];
        int targetIndex = -1;
        for (int i = 0; i < tabRow.getChildCount(); i++) {
            View child = tabRow.getChildAt(i);
            if (relativeX >= child.getLeft() && relativeX < child.getRight()) {
                targetIndex = i;
                break;
            }
        }
        if (targetIndex >= 0 && targetIndex != tabDragTarget) {
            tabDragTarget = targetIndex;
            moveTabIndicator(targetIndex, false);
            for (int i = 0; i < typeTabs.length; i++) {
                typeTabs[i].setTextColor(i == targetIndex ? 0xff17334f : 0xff5a6b82);
            }
        }
    }

    private void loadCatalog() {
        executor.execute(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    getAssets().open("vivo_devices.txt"), StandardCharsets.UTF_8))) {
                JSONObject loaded = new JSONObject();
                String currentCategory = null;
                String currentTab = null;
                String line;
                
                while ((line = reader.readLine()) != null) {
                    java.util.regex.Matcher title = java.util.regex.Pattern.compile("<title>([^<]+)</title>").matcher(line);
                    if (title.find() && !title.group(1).trim().isEmpty()) {
                        String rawTitle = title.group(1).trim();
                        if (rawTitle.equals("手动输入 PD / V 代号")) {
                            currentTab = null;
                            continue;
                        }
                        if (rawTitle.equals("平板电脑")) {
                            currentTab = TAB_TABLET;
                            currentCategory = null;
                            continue;
                        }
                        if (rawTitle.equals("穿戴设备")) {
                            currentTab = TAB_WEARABLE;
                            currentCategory = null;
                            continue;
                        }
                        if (currentTab == null) {
                            currentTab = TAB_PHONE;
                        }
                        currentCategory = rawTitle.replaceFirst("^[Vv][Ii][Vv][Oo] ", "");
                        String key = currentTab + ":" + currentCategory;
                        if (!loaded.has(key)) {
                            loaded.put(key, new JSONArray());
                        }
                    }
                    
                    java.util.regex.Matcher option = java.util.regex.Pattern.compile(
                            "<option value=\\\"model_([^\\\"]+)\\\">([^<]+)</option>").matcher(line);
                    if (option.find() && currentTab != null) {
                        String value = option.group(1).trim();
                        String[] parts = value.split("_", 2);
                        if (parts.length == 2) {
                            // 平板电脑和穿戴设备没有分类，直接用设备类型作为分类
                            String category = currentCategory;
                            if (category == null) {
                                if (currentTab.equals(TAB_TABLET)) {
                                    category = "平板电脑";
                                } else if (currentTab.equals(TAB_WEARABLE)) {
                                    category = "穿戴设备";
                                }
                            }
                            if (category != null) {
                                String key = currentTab + ":" + category;
                                if (!loaded.has(key)) {
                                    loaded.put(key, new JSONArray());
                                }
                                loaded.getJSONArray(key).put(new JSONObject()
                                        .put("model", option.group(2).trim())
                                        .put("codename", parts[0])
                                        .put("model_sw_ver", parts[1]));
                            }
                        }
                    }
                }
                
                runOnUiThread(() -> {
                    catalog = loaded;
                    initializeTabData(TAB_PHONE, phoneCategorySpinner);
                    initializeTabData(TAB_TABLET, tabletCategorySpinner);
                    initializeTabData(TAB_WEARABLE, wearableCategorySpinner);
                    status.setText("请选择设备系列和机型，输入系统版本后查询");
                });
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("设备分类读取失败: " + error.getMessage()));
            }
        });
    }

    private void initializeTabData(String tab, Spinner categorySpinner) {
        if (catalog == null) return;
        List<String> categories = new ArrayList<>();
        try {
            java.util.Iterator<String> keys = catalog.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (key.startsWith(tab + ":")) {
                    String category = key.substring(tab.length() + 1);
                    categories.add(category);
                }
            }
            categoriesByTab.put(tab, categories);
            if (!categories.isEmpty()) {
                categorySpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, categories));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updateDeviceList(String tab, Spinner categorySpinner, Spinner deviceSpinner) {
        if (catalog == null) return;
        int selected = categorySpinner.getSelectedItemPosition();
        List<String> categories = categoriesByTab.get(tab);
        if (categories == null || selected < 0 || selected >= categories.size()) return;
        
        String selectedCategory = categories.get(selected);
        String key = tab + ":" + selectedCategory;
        
        try {
            List<String> deviceNames = new ArrayList<>();
            List<Device> devices = new ArrayList<>();
            JSONArray values = catalog.getJSONArray(key);
            for (int i = 0; i < values.length(); i++) {
                Device device = new Device(values.getJSONObject(i));
                devices.add(device);
                deviceNames.add(device.model);
            }
            devicesByTab.put(tab, devices);
            deviceSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, deviceNames));
            status.setText(selectedCategory + " · " + devices.size() + " 个机型");
        } catch (Exception error) {
            status.setText("机型列表加载失败: " + error.getMessage());
        }
    }

    private void executeQuery() {
        if (currentTab == 0) {
            queryPhoneTab();
        } else if (currentTab == 1) {
            queryTabletTab();
        } else if (currentTab == 2) {
            queryManualTab();
        } else if (currentTab == 3) {
            queryWearableTab();
        }
    }

    private void queryPhoneTab() {
        String version = phoneVersionInput.getText().toString().trim();
        if (version.isEmpty()) {
            status.setText("请输入当前系统版本");
            phoneVersionInput.requestFocus();
            return;
        }
        int selectedDevice = phoneDeviceSpinner.getSelectedItemPosition();
        List<Device> devices = devicesByTab.get(TAB_PHONE);
        if (devices == null || selectedDevice < 0 || selectedDevice >= devices.size()) {
            status.setText("请先选择机型");
            return;
        }
        Device device = devices.get(selectedDevice);
        queryDevice(device.model, device.codename, device.swVer, version, 
                    phoneAndroidVersion, phonePackageType, phoneFullOrIncremental, phoneSerialInput);
    }

    private void queryTabletTab() {
        String version = tabletVersionInput.getText().toString().trim();
        if (version.isEmpty()) {
            status.setText("请输入当前系统版本");
            tabletVersionInput.requestFocus();
            return;
        }
        int selectedDevice = tabletDeviceSpinner.getSelectedItemPosition();
        List<Device> devices = devicesByTab.get(TAB_TABLET);
        if (devices == null || selectedDevice < 0 || selectedDevice >= devices.size()) {
            status.setText("请先选择机型");
            return;
        }
        Device device = devices.get(selectedDevice);
        queryDevice(device.model, device.codename, device.swVer, version,
                    tabletAndroidVersion, tabletPackageType, tabletFullOrIncremental, tabletSerialInput);
    }

    private void queryManualTab() {
        String rawPd = manualPdInput.getText().toString().trim();
        String rawV = manualVInput.getText().toString().trim();
        String version = manualVersionInput.getText().toString().trim();
        
        if (rawPd.isEmpty() || rawV.isEmpty()) {
            status.setText("请输入 PD 和 V 型号");
            return;
        }
        if (version.isEmpty()) {
            status.setText("请输入当前系统版本");
            manualVersionInput.requestFocus();
            return;
        }
        
        String pd = normalizePd(rawPd);
        String v = normalizeV(rawV);
        manualPdInput.setText(pd);
        manualVInput.setText(v);
        
        queryDevice("手动输入设备", pd, v, version,
                    manualAndroidVersion, manualPackageType, manualFullOrIncremental, manualSerialInput);
    }

    private void queryWearableTab() {
        String version = wearableVersionInput.getText().toString().trim();
        if (version.isEmpty()) {
            status.setText("请输入当前系统版本");
            wearableVersionInput.requestFocus();
            return;
        }
        int selectedDevice = wearableDeviceSpinner.getSelectedItemPosition();
        List<Device> devices = devicesByTab.get(TAB_WEARABLE);
        if (devices == null || selectedDevice < 0 || selectedDevice >= devices.size()) {
            status.setText("请先选择机型");
            return;
        }
        Device device = devices.get(selectedDevice);
        queryDevice(device.model, device.codename, device.swVer, version,
                    wearableAndroidVersion, wearablePackageType, wearableFullOrIncremental, wearableSerialInput);
    }

    private void queryDevice(String modelName, String pd, String v, String version,
                            Spinner androidSpinner, Spinner packageSpinner, Spinner fullOrIncrementalSpinner, EditText serialEdit) {
        status.setText("正在查询 " + modelName + " 的官方更新...");
        results.removeAllViews();
        executor.execute(() -> {
            try {
                int android = 17 - androidSpinner.getSelectedItemPosition();
                String serialRaw = serialEdit.getText().toString().trim();
                String serial = serialRaw.isEmpty() ? "A0000000000000A" : serialRaw;
                VivoOtaClient.QueryChannel channel = VivoOtaClient.QueryChannel.values()[packageSpinner.getSelectedItemPosition()];
                boolean isFull = fullOrIncrementalSpinner.getSelectedItemPosition() == 0; // 0=完整包, 1=增量包
                // 根据当前 Tab 确定 isPhone 参数
                boolean isPhone = (currentTab == 0); // 0=手机, 1=平板, 2=手动输入, 3=穿戴设备
                if (currentTab == 2) {
                    // 手动输入默认按手机处理
                    isPhone = true;
                }
                VivoOtaClient.VivoResult result = new VivoOtaClient(this).query(
                        pd, v, version, android, isPhone, isFull, serial, channel);
                runOnUiThread(() -> renderResult(modelName, pd, v, result, android, channel, version, isFull));
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("查询失败: " + error.getMessage()));
            }
        });
    }

    private String normalizePd(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("PD") ? normalized : "PD" + normalized;
    }

    private String normalizeV(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("V") ? normalized : "V" + normalized;
    }

    private void renderResult(String modelName, String pd, String v, VivoOtaClient.VivoResult result,
                             int androidVer, VivoOtaClient.QueryChannel channel, String currentVersion, boolean isFull) {
        results.removeAllViews();
        
        // 如果没有完整下载链接但有文件名，则拼接下载链接
        final String downloadUrl;
        if (result.downloadUrl.isEmpty() && !result.filename.isEmpty()) {
            downloadUrl = "https://sysuptxdl.vivo.com.cn/upgrade/oem/files/" + result.filename;
            android.util.Log.d("VivoActivity", "拼接下载链接: " + downloadUrl);
        } else {
            downloadUrl = result.downloadUrl;
        }
        
        boolean available = !downloadUrl.isEmpty();
        status.setText(available ? "查询成功，发现可用更新" : "查询完成，当前没有可用更新");
        
        android.util.Log.d("VivoActivity", "最终下载链接: [" + downloadUrl + "], available: " + available);

        LiquidGlassPanel card = glass();
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView heading = label(modelName, 18, 0xff1b3157);
        heading.setTypeface(null, 1);
        card.addView(heading, margins(-1, -2, 0, 12, 0));

        addPair(card, "机型名称", modelName);
        
        // 两两并排显示：设备类型和内部型号
        addPairRow(card, "设备类型", "phone", "内部型号", v);
        
        // 两两并排显示：设备型号和当前版本
        addPairRow(card, "设备型号", pd, "当前版本", currentVersion);
        
        // 两两并排显示：安卓版本和包完整性
        addPairRow(card, "安卓版本", String.valueOf(androidVer), "包完整性", isFull ? "完整包" : "增量包");
        
        // 两两并排显示：查询模式和服务端目标版本
        String serverVersion = result.version.isEmpty() ? "未获取" : result.version;
        addPairRow(card, "查询模式", channel.name().toLowerCase(), "服务端目标版本", serverVersion);
        if (!result.filename.isEmpty()) {
            addPair(card, "文件名", result.filename);
        }
        if (!result.size.isEmpty()) {
            long sizeBytes = parseSizeBytes(result.size);
            String sizeDisplay = result.size;
            if (sizeBytes > 0) {
                long sizeMB = sizeBytes / (1024 * 1024);
                sizeDisplay = sizeBytes + " (" + sizeMB + " MB)";
            }
            addPair(card, "文件大小", sizeDisplay);
        }
        if (!result.md5.isEmpty()) {
            addPair(card, "MD5", result.md5);
        }
        if (!result.securityPatch.isEmpty()) {
            addPair(card, "安全补丁", result.securityPatch);
        }
        if (!result.updateTime.isEmpty()) {
            addPair(card, "更新时间", result.updateTime);
        }
        
        if (available) {
            LinearLayout buttonRow = new LinearLayout(this);
            buttonRow.setOrientation(LinearLayout.HORIZONTAL);
            
            Button download = glassButton("下载此版本", 13);
            download.setOnClickListener(v1 -> startDownload(downloadUrl, result.filename));
            buttonRow.addView(download, new LinearLayout.LayoutParams(0, dp(46), 1));
            
            LinearLayout spacer = new LinearLayout(this);
            buttonRow.addView(spacer, new LinearLayout.LayoutParams(dp(12), dp(46)));

            Button copyLink = glassButton("复制下载链接", 13);
            copyLink.setOnClickListener(v1 -> copyText("下载链接", downloadUrl));
            buttonRow.addView(copyLink, new LinearLayout.LayoutParams(0, dp(46), 1));
            
            card.addView(buttonRow, margins(-1, -2, 12, 0, 0));
        } else if (!result.version.isEmpty()) {
            // 有版本信息但无法拼接下载链接
            TextView hint = label("无法生成下载链接，可能需要手动查询", 12, 0xffff5722);
            hint.setPadding(dp(12), dp(10), dp(12), dp(10));
            card.addView(hint, margins(-1, -2, 10, 0, 0));
        } else {
            addPair(card, "查询结果", "当前版本已是最新，或暂无可用更新");
        }

        results.addView(card, margins(-1, -2, 0, 0, 16));
        pageScroll.post(() -> pageScroll.smoothScrollTo(0, results.getTop()));
    }

    private void addPair(LinearLayout parent, String key, String value) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(0, dp(8), 0, dp(8));
        TextView title = label(key, 11, 0xff7a8798);
        TextView content = label(value.isEmpty() ? "未知" : value, 14, 0xff334b66);
        content.setLineSpacing(dp(4), 1f);
        content.setTextIsSelectable(true);
        group.addView(title);
        group.addView(content, margins(-1, -2, 3, 0, 0));
        parent.addView(group);
    }

    private void addPairRow(LinearLayout parent, String key1, String value1, String key2, String value2) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, dp(8));
        
        // 左侧列
        LinearLayout leftGroup = new LinearLayout(this);
        leftGroup.setOrientation(LinearLayout.VERTICAL);
        TextView leftTitle = label(key1, 11, 0xff7a8798);
        TextView leftContent = label(value1.isEmpty() ? "未知" : value1, 14, 0xff334b66);
        leftContent.setLineSpacing(dp(4), 1f);
        leftContent.setTextIsSelectable(true);
        leftGroup.addView(leftTitle);
        leftGroup.addView(leftContent, margins(-1, -2, 3, 0, 0));
        
        // 右侧列
        LinearLayout rightGroup = new LinearLayout(this);
        rightGroup.setOrientation(LinearLayout.VERTICAL);
        TextView rightTitle = label(key2, 11, 0xff7a8798);
        TextView rightContent = label(value2.isEmpty() ? "未知" : value2, 14, 0xff334b66);
        rightContent.setLineSpacing(dp(4), 1f);
        rightContent.setTextIsSelectable(true);
        rightGroup.addView(rightTitle);
        rightGroup.addView(rightContent, margins(-1, -2, 3, 0, 0));
        
        row.addView(leftGroup, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(rightGroup, new LinearLayout.LayoutParams(0, -2, 1));
        parent.addView(row);
    }

    private long parseSizeBytes(String size) {
        try {
            return Long.parseLong(size.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private void copyText(String label, String value) {
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, value));
        android.widget.Toast.makeText(this, label + " 已复制", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void startDownload(String url, String name) {
        File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DsuManager");
        if (!directory.exists()) directory.mkdirs();
        String safeName = (name == null || name.isEmpty() ? "vivo-update.zip" : name)
                .replaceAll("[^a-zA-Z0-9._-]", "_");
        File output = new File(directory, safeName);
        
        // vivo固定使用4线程+16MB分片（最优配置）
        int threads = 4;
        long chunkSize = 16;
        
        showDownloadCard(output);
        
        Intent intent = new Intent(this, DownloadService.class)
                .setAction(DownloadService.ACTION_START)
                .putExtra(DownloadService.EXTRA_ADDRESS, url)
                .putExtra(DownloadService.EXTRA_OUTPUT, output.getAbsolutePath())
                .putExtra(DownloadService.EXTRA_PACKAGE, "vivo OTA")
                .putExtra("download_threads", threads)
                .putExtra("download_chunk_mb", chunkSize);
        
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private Button createTabButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(12);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(null, 1);
        button.setAllCaps(false);
        button.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        button.setStateListAnimator(null);
        button.setPadding(0, 0, 0, 0);
        button.setMinWidth(0);
        button.setMinHeight(0);
        return button;
    }

    private Button glassButton(String text, int textSize) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(textSize);
        button.setTextColor(0xff1a3356);
        button.setTypeface(null, 1);
        button.setBackgroundResource(R.drawable.liquid_glass_panel);
        button.setAllCaps(false);
        button.setStateListAnimator(null);
        return button;
    }

    private Spinner optionSpinner(String hint) {
        Spinner spinner = new Spinner(this);
        spinner.setPrompt(hint);
        return spinner;
    }

    private LinearLayout wrapSpinner(Spinner spinner) {
        LiquidGlassPanel wrap = glass();
        wrap.setPadding(dp(14), dp(3), dp(14), dp(3));
        wrap.addView(spinner, new LinearLayout.LayoutParams(-1, dp(46)));
        return wrap;
    }

    private EditText input(String hint) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setTextSize(14);
        edit.setTextColor(0xff1f3155);
        edit.setHintTextColor(0xff8795a8);
        edit.setPadding(dp(14), dp(13), dp(14), dp(13));
        edit.setBackgroundResource(R.drawable.liquid_glass_panel);
        return edit;
    }

    private TextView label(String text, int textSize, int color) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(textSize);
        label.setTextColor(color);
        return label;
    }

    private LinearLayout.LayoutParams margins(int width, int height, int top, int start, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height == -2 ? -2 : dp(height));
        params.topMargin = dp(top);
        if (start > 0) params.setMarginStart(dp(start));
        params.bottomMargin = dp(bottom);
        return params;
    }
    
    private void updateDownloadProgress(long done, long total) {
        if (total <= 0 || downloadProgress == null || downloadPercent == null || downloadBytes == null || downloadStatus == null) return;
        int percent = (int) ((done * 100) / total);
        downloadProgress.setProgress(percent);
        downloadPercent.setText(percent + "%");
        
        long now = System.currentTimeMillis();
        if (speedWindowTime > 0) {
            long deltaBytes = done - speedWindowBytes;
            long deltaTime = now - speedWindowTime;
            if (deltaTime > 0) {
                long instantSpeed = (deltaBytes * 1000) / deltaTime;
                smoothedSpeed = smoothedSpeed == 0 ? instantSpeed : (smoothedSpeed * 7 + instantSpeed) / 8;
            }
        }
        speedWindowBytes = done;
        speedWindowTime = now;
        
        // 计算预计剩余时间
        String etaStr = "--";
        if (smoothedSpeed > 0) {
            long remaining = total - done;
            long etaSeconds = remaining / smoothedSpeed;
            if (etaSeconds < 60) {
                etaStr = etaSeconds + "秒";
            } else if (etaSeconds < 3600) {
                etaStr = (etaSeconds / 60) + "分钟";
            } else {
                etaStr = (etaSeconds / 3600) + "小时";
            }
        }
        downloadStatus.setText("预计剩余时间 " + etaStr);
        
        String doneStr = formatBytes(done);
        String totalStr = formatBytes(total);
        String speedStr = smoothedSpeed > 0 ? formatBytes(smoothedSpeed) + "/s" : "计算中";
        downloadBytes.setText("已下载 " + doneStr + " / " + totalStr + " · " + speedStr);
    }
    
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
    
    private void sendDownloadCommand(String action) {
        Intent intent = new Intent(this, DownloadService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }
    
    private void showDownloadCard(File file) {
        downloadCard.setVisibility(View.VISIBLE);
        downloadFileTitle.setText("正在下载: " + file.getName());
        downloadPath.setText("保存到: " + file.getAbsolutePath());
        downloadStatus.setText("准备下载");
        downloadPercent.setText("0%");
        downloadBytes.setText("已下载 0 B / 总大小获取中");
        downloadProgress.setProgress(0);
        pauseDownloadButton.setText("暂停");
        pauseDownloadButton.setVisibility(View.VISIBLE);
        cancelDownloadButton.setText("取消");
        downloadPaused = false;
        speedWindowBytes = 0;
        speedWindowTime = 0;
        smoothedSpeed = 0;
        
        // 保存下载状态到 SharedPreferences
        saveDownloadState(file.getName(), file.getAbsolutePath());
    }
    
    private void saveDownloadState(String fileName, String filePath) {
        android.content.SharedPreferences prefs = getSharedPreferences("vivo_download", MODE_PRIVATE);
        prefs.edit()
            .putBoolean("is_downloading", true)
            .putString("file_name", fileName)
            .putString("file_path", filePath)
            .apply();
    }
    
    private void clearDownloadState() {
        android.content.SharedPreferences prefs = getSharedPreferences("vivo_download", MODE_PRIVATE);
        prefs.edit()
            .putBoolean("is_downloading", false)
            .remove("file_name")
            .remove("file_path")
            .apply();
    }
    
    private void restoreDownloadState() {
        android.content.SharedPreferences prefs = getSharedPreferences("vivo_download", MODE_PRIVATE);
        boolean isDownloading = prefs.getBoolean("is_downloading", false);
        
        if (isDownloading && downloadCard != null) {
            String fileName = prefs.getString("file_name", "");
            String filePath = prefs.getString("file_path", "");
            
            if (!fileName.isEmpty() && !filePath.isEmpty()) {
                downloadCard.setVisibility(View.VISIBLE);
                downloadFileTitle.setText("正在下载: " + fileName);
                downloadPath.setText("保存到: " + filePath);
                downloadStatus.setText("恢复下载中...");
                pauseDownloadButton.setVisibility(View.VISIBLE);
            }
        }
    }

    private static final class Device {
        final String model;
        final String codename;
        final String swVer;

        Device(JSONObject data) throws Exception {
            this.model = data.getString("model");
            this.codename = data.getString("codename");
            this.swVer = data.getString("model_sw_ver");
        }
    }
    
    // Vivo 页面使用 20dp 圆角的玻璃效果
    private LiquidGlassPanel glass() {
        return new LiquidGlassPanel(this, 20f);
    }
}
