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

    // ===== 同步自 VIVO-OTA-Tracker：IMEI 输入（手机/手动）与升级服务器域名选择（各面板） =====
    private EditText phoneImeiInput;
    private EditText manualImeiInput;
    private Spinner phoneDomainSpinner;
    private Spinner tabletDomainSpinner;
    private Spinner manualDomainSpinner;
    private Spinner wearableDomainSpinner;

    // ===== 同步自 VIVO-OTA-Tracker：查询历史（一键回填，最多 20 条，按时间降序） =====
    private static final String HISTORY_PREFS = "vivo_query_history";
    private static final int HISTORY_MAX = 20;
    private final List<org.json.JSONObject> historyEntries = new ArrayList<>();
    private Button historyButton;
    /** 查询历史下方展开容器（图三：点击「查询历史」就地展开，不再弹窗） */
    private LinearLayout historyExpandContainer;
    private Button refreshCatalogButton;

    /** 历史条目的查询条件签名（与上游 querySignature 一致：能唯一确定查询的输入字段）。 */
    private static String historySignature(org.json.JSONObject e) {
        return e.optBoolean("manualMode") + "|" + e.optString("manualCodename").trim() + "|"
                + e.optString("manualModelSwVer").trim() + "|" + e.optString("manualModelName").trim() + "|"
                + e.optString("querySoftwareVersion").trim() + "|" + e.optInt("androidVersion") + "|"
                + e.optString("deviceType").trim() + "|" + e.optBoolean("isFullPackage") + "|"
                + e.optString("queryChannel").trim() + "|" + e.optString("queryDomain").trim();
    }
    
    private TextView status;
    private LinearLayout results;
    private ScrollView pageScroll;
    private int currentTab = 0;
    
    // v3.8.8：多任务下载框（只渲染本页 vivo 任务，其他页任务折叠条提示）
    private PageTaskCards taskCards;
    private LinearLayout taskArea;

    private final android.content.BroadcastReceiver downloadReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context context, Intent intent) {
            if (taskCards != null) taskCards.onBroadcast(intent);
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
        loadHistory(); // 查询历史（同步自上游 HistoryCard）
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
        }
        // v3.8.8：回到本页与下载服务对账（本页 vivo 任务全量刷新 + 其他页折叠条计数）
        if (taskCards != null) {
            taskCards.resync();
            taskCards.queryService();
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

        // 查询历史（下方展开，不再弹窗）+ 在线刷新机型库（同步自上游 HistoryCard 与机型库在线更新）
        LinearLayout historyRow = new LinearLayout(this);
        historyRow.setOrientation(LinearLayout.HORIZONTAL);
        historyButton = glassButton("查询历史 (0)", 13);
        historyButton.setOnClickListener(v -> {
            Haptics.perform(v);
            toggleHistoryPanel();
        });
        historyRow.addView(historyButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        View historySpacer = new View(this);
        historyRow.addView(historySpacer, new LinearLayout.LayoutParams(dp(12), dp(46)));
        refreshCatalogButton = glassButton("刷新机型库", 13);
        refreshCatalogButton.setOnClickListener(v -> {
            Haptics.perform(v);
            refreshCatalog();
        });
        historyRow.addView(refreshCatalogButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        content.addView(historyRow, margins(-1, 46, 0, 0, 8));

        // 历史展开容器（默认收起；点击「查询历史」在正下方展开/收起）
        historyExpandContainer = new LinearLayout(this);
        historyExpandContainer.setOrientation(LinearLayout.VERTICAL);
        historyExpandContainer.setVisibility(View.GONE);
        content.addView(historyExpandContainer, margins(-1, -2, 0, 0, 16));

        // v3.8.8：多任务下载区（本页每个 vivo 任务一张独立卡片，其他页任务折叠条提示）
        taskArea = new LinearLayout(this);
        taskArea.setOrientation(LinearLayout.VERTICAL);
        taskArea.setVisibility(View.GONE);
        taskCards = new PageTaskCards(this, VivoActivity.class.getSimpleName());
        content.addView(taskArea, margins(-1, -2, 0, 0, 16));

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
        // v3.8.8：绑定多任务下载区（页面滚动联动 + 回页对账）
        if (taskCards != null) taskCards.attach(taskArea, pageScroll);

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

        // IMEI 输入 + 一键随机（同步自上游：手动填写 > 随机；留空由客户端自动生成）
        panel.addView(buildImeiRow("phone"), margins(-1, -2, 0, 0, 12));

        // 升级服务器域名（同步自上游 Domain 枚举：国行 sysupgrade.vivo.com.cn / 海外 asia-sysupgrade-api.vivoglobal.com）
        phoneDomainSpinner = buildDomainSpinner();
        panel.addView(wrapSpinner(phoneDomainSpinner), margins(-1, 52, 0, 0, 12));

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

        // 升级服务器域名（平板协议走 snp，不需要 IMEI）
        tabletDomainSpinner = buildDomainSpinner();
        panel.addView(wrapSpinner(tabletDomainSpinner), margins(-1, 52, 0, 0, 12));

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

        // IMEI 输入 + 一键随机（手动模式按手机协议查询，携带 IMEI）
        panel.addView(buildImeiRow("manual"), margins(-1, -2, 0, 0, 12));

        // 升级服务器域名
        manualDomainSpinner = buildDomainSpinner();
        panel.addView(wrapSpinner(manualDomainSpinner), margins(-1, 52, 0, 0, 12));

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

        // 升级服务器域名
        wearableDomainSpinner = buildDomainSpinner();
        panel.addView(wrapSpinner(wearableDomainSpinner), margins(-1, 52, 0, 0, 12));

        Button queryBtn = glassButton("查询官方更新", 14);
        queryBtn.setOnClickListener(v -> executeQuery());
        panel.addView(queryBtn, margins(-1, 48, 0, 0, 12));

        return panel;
    }

    /**
     * IMEI 输入行（图三）：输入框 + 「本机」「随机」双按钮（同步自上游 ImeiSource：DEVICE / RANDOM）。
     * 按钮统一玻璃胶囊样式：高度与输入框一致（50dp）、同款圆角与描边，修复与输入框视觉不齐的问题。
     */
    private LinearLayout buildImeiRow(String target) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        EditText imeiInput = input("IMEI，可留空自动生成（15 位数字）");
        imeiInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        imeiInput.setMaxLines(1);
        if ("manual".equals(target)) {
            manualImeiInput = imeiInput;
        } else {
            phoneImeiInput = imeiInput;
        }
        row.addView(imeiInput, new LinearLayout.LayoutParams(0, dp(50), 1));

        View spacer1 = new View(this);
        row.addView(spacer1, new LinearLayout.LayoutParams(dp(8), dp(50)));

        // 本机：读取设备 IMEI（子线程，读不到回退系统属性，仍读不到提示并随机）
        Button deviceBtn = imeiActionButton("本机");
        deviceBtn.setOnClickListener(v -> {
            Haptics.perform(v);
            executor.execute(() -> {
                String imei = com.mytiantian.updater.vivo.VivoImei.INSTANCE.readDeviceSync(getApplicationContext());
                runOnUiThread(() -> {
                    if (imei != null && imei.length() == 15) {
                        imeiInput.setText(imei);
                        status.setText("已填入本机 IMEI");
                    } else {
                        // Android 10+ 三方应用读不到 IMEI：自动随机并提示
                        imeiInput.setText(com.mytiantian.updater.vivo.VivoImei.INSTANCE.random());
                        status.setText("本机 IMEI 不可读（系统限制），已自动随机生成");
                    }
                });
            });
        });
        row.addView(deviceBtn, new LinearLayout.LayoutParams(dp(64), dp(50)));

        View spacer2 = new View(this);
        row.addView(spacer2, new LinearLayout.LayoutParams(dp(8), dp(50)));

        // 随机：一键生成 15 位随机 IMEI
        Button randomBtn = imeiActionButton("随机");
        randomBtn.setOnClickListener(v -> {
            Haptics.perform(v);
            imeiInput.setText(com.mytiantian.updater.vivo.VivoImei.INSTANCE.random());
        });
        row.addView(randomBtn, new LinearLayout.LayoutParams(dp(64), dp(50)));
        return row;
    }

    /** IMEI 行操作按钮：全胶囊形三层液态玻璃（渐变底 + 白描边 + 顶部高光），与输入框同高对齐 */
    private Button imeiActionButton(String text) {
        Button button = new Button(this, null, 0);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13.5f);
        button.setTypeface(null, 1);
        button.setTextColor(0xff0e7d95);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setIncludeFontPadding(false);
        button.setPadding(0, 0, 0, 0);
        button.setStateListAnimator(null);
        // 三层液态玻璃：白→浅青渐变底 + 2dp 白描边 + 顶部高光带，全胶囊圆角（高 50dp / 半径 25dp）
        android.graphics.drawable.GradientDrawable base = new android.graphics.drawable.GradientDrawable();
        base.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TL_BR);
        base.setColors(new int[]{0xF2FFFFFF, 0xE6E3F6FA, 0xD9D8ECF4});
        base.setCornerRadius(dp(25));
        android.graphics.drawable.GradientDrawable strokeLayer = new android.graphics.drawable.GradientDrawable();
        strokeLayer.setColor(0x00000000);
        strokeLayer.setStroke(Math.max(1, dp(2)), 0xFFFFFFFF);
        strokeLayer.setCornerRadius(dp(24));
        android.graphics.drawable.GradientDrawable highlight = new android.graphics.drawable.GradientDrawable();
        highlight.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT);
        highlight.setColors(new int[]{0x00FFFFFF, 0x73FFFFFF, 0x00FFFFFF});
        highlight.setCornerRadius(dp(20));
        android.graphics.drawable.LayerDrawable glass = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{base, strokeLayer, highlight});
        glass.setLayerInset(1, 1, 1, 1, 1);
        glass.setLayerInset(2, dp(3), dp(2), dp(3), dp(14));
        // 白色涟漪按压反馈
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                new android.content.res.ColorStateList(
                        new int[][]{{android.R.attr.state_pressed}}, new int[]{0x33FFFFFF}),
                glass, null));
        button.setElevation(dp(3));
        return button;
    }

    /** 升级服务器域名选择（同步自上游 Domain 枚举）。0=国行 CN，1=海外 GLOBAL。 */
    private Spinner buildDomainSpinner() {
        Spinner spinner = optionSpinner("升级服务器");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"国行服务器", "海外服务器"});
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinner.setAdapter(adapter);
        return spinner;
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
            try {
                // 机型库：内置 vivo_devices.json + 在线缓存合并（同步自上游 VivoDeviceDatabase）
                com.mytiantian.updater.vivo.VivoDeviceDatabase.INSTANCE.load(this);
                // 机型库已全自动获取（内置 JSON + 在线刷新），旧版本遗留的本地 TXT 清单一并清理
                cleanupLegacyTxt();
                JSONObject loaded = new JSONObject();
                for (String series : com.mytiantian.updater.vivo.VivoDeviceDatabase.INSTANCE.getSeries()) {
                    // 平板电脑 / 穿戴设备独立成系列，其余归入手机页签
                    String tab = "平板电脑".equals(series) ? TAB_TABLET
                            : ("穿戴设备".equals(series) ? TAB_WEARABLE : TAB_PHONE);
                    JSONArray arr = new JSONArray();
                    for (com.mytiantian.updater.vivo.VivoDevice d
                            : com.mytiantian.updater.vivo.VivoDeviceDatabase.INSTANCE.devicesOf(series)) {
                        arr.put(new JSONObject()
                                .put("model", d.getModel())
                                .put("codename", d.getCodename())
                                .put("model_sw_ver", d.getModel_sw_ver()));
                    }
                    loaded.put(tab + ":" + series, arr);
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

    /** 清理旧版本遗留的本地 vivo 设备清单 TXT（机型库已全自动在线获取，不再需要）。 */
    private void cleanupLegacyTxt() {
        try {
            File dir = new File(android.os.Environment
                    .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "DsuManager");
            if (dir == null || !dir.isDirectory()) return;
            String[] legacyNames = {"vivo设备清单.txt", "vivo 设备清单.txt", "vivo_devices.txt"};
            for (String name : legacyNames) {
                File legacy = new File(dir, name);
                if (legacy.isFile() && legacy.delete()) {
                    android.util.Log.d("VivoActivity", "已清理旧机型清单: " + name);
                }
            }
        } catch (Exception ignored) { }
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
                    phoneAndroidVersion, phonePackageType, phoneFullOrIncremental,
                    phoneSerialInput, phoneImeiInput, phoneDomainSpinner);
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
                    tabletAndroidVersion, tabletPackageType, tabletFullOrIncremental,
                    tabletSerialInput, null, tabletDomainSpinner);
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
                    manualAndroidVersion, manualPackageType, manualFullOrIncremental,
                    manualSerialInput, manualImeiInput, manualDomainSpinner);
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
                    wearableAndroidVersion, wearablePackageType, wearableFullOrIncremental,
                    wearableSerialInput, null, wearableDomainSpinner);
    }

    private void queryDevice(String modelName, String pd, String v, String version,
                            Spinner androidSpinner, Spinner packageSpinner, Spinner fullOrIncrementalSpinner,
                            EditText serialEdit, EditText imeiEdit, Spinner domainSpinner) {
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
                // IMEI：手动填写优先，留空由客户端自动生成（同步自上游 ImeiSource 逻辑）
                String imei = imeiEdit == null ? "" : imeiEdit.getText().toString().trim();
                // 升级服务器域名：0=国行 CN（默认），1=海外 GLOBAL（同步自上游 Domain 枚举）
                VivoOtaClient.Domain domain = (domainSpinner != null && domainSpinner.getSelectedItemPosition() == 1)
                        ? VivoOtaClient.Domain.GLOBAL : VivoOtaClient.Domain.CN;
                VivoOtaClient.VivoResult result = new VivoOtaClient(this).query(
                        pd, v, version, android, isPhone, isFull, serial, imei, channel, domain);
                final boolean manualMode = (currentTab == 2);
                final String domainName = domain.name();
                runOnUiThread(() -> {
                    renderResult(modelName, pd, v, result, android, channel, version, isFull, domainName);
                    saveHistory(modelName, pd, v, version, android, isFull, channel.name(), domainName, manualMode, result);
                });
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
                             int androidVer, VivoOtaClient.QueryChannel channel, String currentVersion,
                             boolean isFull, String domainName) {
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
            // v3.8.5：字节数 + 换算单位（1556685Byte (1.48 MB) 样式）
            String sizeDisplay = sizeBytes > 0 ? sizeBytesDisplay(sizeBytes) : result.size;
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
        addPair(card, "升级服务器", "GLOBAL".equals(domainName) ? "海外服务器" : "国行服务器");

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

        // 更新日志折叠区（下载 / 复制按钮下方）：默认收起，点击展开直接加载正文，不提供链接跳转
        if (!result.changelogUrl.isEmpty() && !"(Not found)".equals(result.changelogUrl)) {
            LinearLayout changelogHeader = new LinearLayout(this);
            changelogHeader.setOrientation(LinearLayout.HORIZONTAL);
            changelogHeader.setGravity(Gravity.CENTER_VERTICAL);
            changelogHeader.setPadding(dp(16), 0, dp(16), 0);
            changelogHeader.setBackgroundResource(R.drawable.liquid_glass_panel);
            TextView changelogTitle = label("▸ 更新日志", 14, 0xff155e70);
            changelogTitle.setTypeface(null, 1);
            changelogTitle.setGravity(Gravity.CENTER);
            changelogHeader.addView(changelogTitle, new LinearLayout.LayoutParams(0, dp(46), 1));
            TextView changelogHint = label("点击展开详情", 11, 0xff4a7d8c);
            changelogHint.setGravity(Gravity.CENTER_VERTICAL);
            changelogHeader.addView(changelogHint, new LinearLayout.LayoutParams(-2, dp(46)));

            LinearLayout changelogBody = new LinearLayout(this);
            changelogBody.setOrientation(LinearLayout.VERTICAL);
            changelogBody.setGravity(Gravity.CENTER_HORIZONTAL);
            changelogBody.setPadding(dp(16), dp(12), dp(16), dp(12));
            changelogBody.setBackgroundResource(R.drawable.liquid_glass_panel);
            changelogBody.setVisibility(View.GONE);
            TextView changelogText = label("正在加载更新日志...", 13, 0xff334b66);
            changelogText.setLineSpacing(dp(4), 1f);
            changelogText.setTextIsSelectable(true);
            changelogText.setGravity(Gravity.CENTER);
            changelogBody.addView(changelogText, new LinearLayout.LayoutParams(-1, -2));

            final boolean[] loaded = {false};
            changelogHeader.setOnClickListener(vh -> {
                Haptics.perform(vh);
                boolean expanding = changelogBody.getVisibility() != View.VISIBLE;
                changelogBody.setVisibility(expanding ? View.VISIBLE : View.GONE);
                changelogTitle.setText(expanding ? "▾ 更新日志" : "▸ 更新日志");
                changelogHint.setText(expanding ? "点击收起" : "点击展开详情");
                // 展开动画：轻微淡入
                if (expanding) {
                    changelogBody.setAlpha(0f);
                    changelogBody.animate().alpha(1f).setDuration(220).start();
                }
                if (expanding && !loaded[0]) {
                    loaded[0] = true;
                    executor.execute(() -> {
                        String content = null;
                        try {
                            content = new com.mytiantian.updater.vivo.VivoOtaClient(getApplicationContext())
                                    .fetchChangelog(result.changelogUrl);
                        } catch (Exception ignored) { }
                        final String text = (content == null || content.isEmpty())
                                ? "更新日志加载失败，请稍后重试" : content;
                        runOnUiThread(() -> changelogText.setText(text));
                    });
                }
            });
            card.addView(changelogHeader, margins(-1, 44, 12, 0, 0));
            card.addView(changelogBody, margins(-1, -2, 8, 0, 0));
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

    /**
     * v3.8.5：升级包大小显示为「1556685Byte (1.48 MB)」样式 ——
     * 原始字节数 + 换算单位（≥1GB 用 GB，其余用 MB），满足「字节数后面带多少G」的需求。
     */
    private String sizeBytesDisplay(long bytes) {
        if (bytes <= 0) return "";
        double gb = bytes / 1073741824.0;
        String converted = gb >= 1.0
                ? String.format(Locale.CHINA, "%.2f GB", gb)
                : String.format(Locale.CHINA, "%.2f MB", bytes / 1048576.0);
        return bytes + "Byte (" + converted + ")";
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

        // v3.8.8：多任务下载框（vivo 固定 4 线程 + 16MB 分片；并行满 3 个自动排队）
        if (taskCards == null) return;
        taskCards.start(url, output, "vivo OTA", 4, 16L);
        if (status != null) {
            status.setText("下载任务已提交: " + output.getName()
                    + "（并行中任务最多 3 个，超出的自动排队）");
        }
    }

    // ===== 同步自 VIVO-OTA-Tracker：更新日志 / 查询历史 / 在线刷新机型库 =====
    // 更新日志改为查询结果卡内的折叠区（renderResult 内联加载），不再使用弹窗与链接跳转。

    /** 查询成功后保存历史（同步自上游 QueryHistoryEntry 字段，同签名去重、上限 20 条）。 */
    private void saveHistory(String modelName, String pd, String v, String version, int android,
                             boolean isFull, String channelName, String domainName, boolean manualMode,
                             VivoOtaClient.VivoResult result) {
        try {
            org.json.JSONObject entry = new org.json.JSONObject()
                    .put("timestamp", System.currentTimeMillis())
                    .put("model", modelName)
                    .put("codename", pd)
                    .put("swVersion", version)
                    .put("model_sw_ver", v)
                    .put("resultVersion", result.version)
                    .put("fileSize", result.size)
                    .put("downloadUrl", result.downloadUrl)
                    .put("channel", channelName)
                    .put("querySoftwareVersion", version)
                    .put("manualMode", manualMode)
                    .put("manualCodename", manualMode ? pd : "")
                    .put("manualModelSwVer", manualMode ? v : "")
                    .put("manualModelName", manualMode ? modelName : "")
                    .put("androidVersion", android)
                    .put("deviceType", currentTab == 1 ? "tablet" : (currentTab == 3 ? "wearable" : "phone"))
                    .put("isFullPackage", isFull)
                    .put("queryChannel", channelName)
                    .put("queryDomain", domainName)
                    .put("changelogUrl", result.changelogUrl);
            // 同签名去重：同条件查询只保留最新一条（与上游一致）
            String sig = historySignature(entry);
            for (int i = historyEntries.size() - 1; i >= 0; i--) {
                if (sig.equals(historySignature(historyEntries.get(i)))) historyEntries.remove(i);
            }
            historyEntries.add(0, entry);
            while (historyEntries.size() > HISTORY_MAX) historyEntries.remove(historyEntries.size() - 1);
            persistHistory();
            historyButton.setText(historyExpandContainer != null
                    && historyExpandContainer.getVisibility() == View.VISIBLE
                    ? "收起历史 (" + historyEntries.size() + ")"
                    : "查询历史 (" + historyEntries.size() + ")");
            // 历史面板正展开时同步刷新条目
            if (historyExpandContainer != null
                    && historyExpandContainer.getVisibility() == View.VISIBLE) {
                renderHistoryPanel();
            }
        } catch (Exception ignored) { }
    }

    private void persistHistory() {
        try {
            JSONArray arr = new JSONArray();
            for (org.json.JSONObject e : historyEntries) arr.put(e);
            getSharedPreferences(HISTORY_PREFS, MODE_PRIVATE).edit()
                    .putString("entries", arr.toString()).apply();
        } catch (Exception ignored) { }
    }

    private void loadHistory() {
        try {
            String raw = getSharedPreferences(HISTORY_PREFS, MODE_PRIVATE).getString("entries", "");
            if (raw == null || raw.isEmpty()) return;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length() && historyEntries.size() < HISTORY_MAX; i++) {
                historyEntries.add(arr.getJSONObject(i));
            }
            historyButton.setText("查询历史 (" + historyEntries.size() + ")");
        } catch (Exception ignored) { }
    }

    /** 查询历史：点击「查询历史」在按钮正下方展开/收起（图三，不再弹窗）。 */
    private void toggleHistoryPanel() {
        if (historyExpandContainer == null) return;
        boolean expanding = historyExpandContainer.getVisibility() != View.VISIBLE;
        if (expanding) {
            renderHistoryPanel();
            historyExpandContainer.setVisibility(View.VISIBLE);
            historyExpandContainer.setAlpha(0f);
            historyExpandContainer.animate().alpha(1f).setDuration(220).start();
            historyButton.setText("收起历史 (" + historyEntries.size() + ")");
        } else {
            historyExpandContainer.setVisibility(View.GONE);
            historyButton.setText("查询历史 (" + historyEntries.size() + ")");
        }
    }

    /**
     * 查询历史面板（图三：就地展开，直接重显完整查询结果）：
     * 顶部操作提示条（加大加宽，文字完整显示）+ 每条历史完整结果玻璃卡（版本 / 大小 / 链接等）。
     */
    private void renderHistoryPanel() {
        if (historyExpandContainer == null) return;
        historyExpandContainer.removeAllViews();
        if (historyEntries.isEmpty()) {
            TextView empty = label("暂无查询历史 · 查询成功后自动记录", 13, 0xff6b8fa0);
            empty.setPadding(dp(16), dp(16), dp(16), dp(16));
            empty.setGravity(Gravity.CENTER);
            empty.setBackgroundResource(R.drawable.liquid_glass_panel);
            historyExpandContainer.addView(empty, margins(-1, -2, 0, 0, 0));
            return;
        }
        // 操作提示条：加大加宽（高 52dp），文字居中完整显示，「清空全部」按钮加宽
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(18), 0, dp(12), 0);
        head.setBackgroundResource(R.drawable.liquid_glass_panel);
        TextView tip = label("点击卡片回填 · 长按立即删除", 13.5f, 0xff155e70);
        tip.setTypeface(null, 1);
        tip.setGravity(Gravity.CENTER);   // v3.8.2 修复：提示文字居中
        head.addView(tip, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button clear = glassButton("清空全部", 13);
        clear.setOnClickListener(v -> {
            Haptics.perform(v);
            historyEntries.clear();
            persistHistory();
            historyButton.setText("查询历史 (0)");
            renderHistoryPanel();
        });
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(dp(102), dp(40));
        head.addView(clear, clearLp);
        historyExpandContainer.addView(head, margins(-1, 52, 0, 0, 10));

        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        for (int i = 0; i < historyEntries.size(); i++) {
            final org.json.JSONObject entry = historyEntries.get(i);
            // v3.8.2 修复：卡片间距（此前 LayoutParams 未应用，圆角框挤在一起）
            View cardView = historyResultCard(entry, fmt);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
            cardLp.topMargin = dp(12);
            historyExpandContainer.addView(cardView, cardLp);
        }
    }

    /** 历史完整结果卡：重显查询结果（机型 / 版本 / 大小 / 链接等，图三要求）。 */
    private View historyResultCard(org.json.JSONObject entry, java.text.SimpleDateFormat fmt) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(18), dp(14), dp(18), dp(14));
        row.setBackgroundResource(R.drawable.liquid_glass_panel);
        row.setElevation(dp(3));

        // 标题：机型 · 查询到的版本（15sp 加粗，完整显示）
        TextView title = label(entry.optString("model") + " · " + entry.optString("resultVersion"), 15, 0xff155e70);
        title.setTypeface(null, 1);
        row.addView(title, new LinearLayout.LayoutParams(-1, -2));

        // 元信息：时间 · 查询条件版本 · 包类型 · 服务器（12sp）
        String pkg = entry.optBoolean("isFullPackage", true) ? "完整包" : "增量包";
        String server = "GLOBAL".equals(entry.optString("queryDomain")) ? "海外服务器" : "国行服务器";
        TextView meta = label(fmt.format(new java.util.Date(entry.optLong("timestamp")))
                + " · " + entry.optString("swVersion")
                + " · " + pkg + " · " + server, 12, 0xff4a7d8c);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(-1, -2);
        metaLp.topMargin = dp(4);
        row.addView(meta, metaLp);

        String downloadUrl = entry.optString("downloadUrl", "");
        String fileSize = entry.optString("fileSize", "");

        // 结果明细区（重新显示一遍查询到的结果）
        if (!fileSize.isEmpty() || !downloadUrl.isEmpty()) {
            View divider = new View(this);
            divider.setBackgroundColor(0x1f2f6fd8);
            LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
            dividerLp.topMargin = dp(10);
            row.addView(divider, dividerLp);

            if (!fileSize.isEmpty()) {
                // v3.8.5：升级包大小显示「1556685Byte (1.48 MB)」样式（原始字节 + 换算 G/M）
                long sizeBytes = parseSizeBytes(fileSize);
                String sizeText = sizeBytes > 0 ? sizeBytesDisplay(sizeBytes) : fileSize;
                TextView sizeLine = label("升级包大小: " + sizeText, 12.5f, 0xff334b66);
                LinearLayout.LayoutParams sizeLp = new LinearLayout.LayoutParams(-1, -2);
                sizeLp.topMargin = dp(10);
                row.addView(sizeLine, sizeLp);
            }
            // v3.8.2 按需求调整：不再直接显示下载链接长文本，仅保留「复制下载链接」按钮
            if (!downloadUrl.isEmpty()) {
                Button copy = glassButton("复制下载链接", 12.5f);
                copy.setOnClickListener(v -> {
                    Haptics.perform(v);
                    copyText("下载链接", downloadUrl);
                });
                LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(-1, dp(40));
                copyLp.topMargin = dp(10);
                row.addView(copy, copyLp);
            }
        }

        row.setOnClickListener(v -> {
            Haptics.perform(v);
            backfillFromHistory(entry);
        });
        row.setOnLongClickListener(v -> {
            Haptics.perform(v);
            historyEntries.remove(entry);
            persistHistory();
            historyButton.setText("查询历史 (" + historyEntries.size() + ")");
            renderHistoryPanel();
            return true;
        });
        return row;
    }

    /** 历史条目一键回填（同步自上游）：切到对应页签并恢复全部查询条件。 */
    private void backfillFromHistory(org.json.JSONObject e) {
        boolean manual = e.optBoolean("manualMode");
        String deviceType = e.optString("deviceType", "phone");
        int tab = manual ? 2 : ("tablet".equals(deviceType) ? 1 : ("wearable".equals(deviceType) ? 3 : 0));
        switchTab(tab);
        String version = e.optString("querySoftwareVersion");
        int android = e.optInt("androidVersion", 15);
        int androidPos = Math.max(0, Math.min(5, 17 - android));
        int channelPos = 0;
        try {
            channelPos = VivoOtaClient.QueryChannel.valueOf(
                    e.optString("queryChannel", VivoOtaClient.QueryChannel.NORMAL.name())).ordinal();
        } catch (IllegalArgumentException ignored) { }
        boolean full = e.optBoolean("isFullPackage", true);
        int domainPos = "GLOBAL".equals(e.optString("queryDomain")) ? 1 : 0;
        if (tab == 2) {
            manualPdInput.setText(e.optString("manualCodename"));
            manualVInput.setText(e.optString("manualModelSwVer"));
            manualVersionInput.setText(version);
            manualAndroidVersion.setSelection(androidPos);
            manualPackageType.setSelection(channelPos);
            manualFullOrIncremental.setSelection(full ? 0 : 1);
            manualDomainSpinner.setSelection(domainPos);
            status.setText("已回填历史查询条件，可直接重新查询");
            return;
        }
        Spinner category = tab == 1 ? tabletCategorySpinner : (tab == 3 ? wearableCategorySpinner : phoneCategorySpinner);
        Spinner device = tab == 1 ? tabletDeviceSpinner : (tab == 3 ? wearableDeviceSpinner : phoneDeviceSpinner);
        EditText verInput = tab == 1 ? tabletVersionInput : (tab == 3 ? wearableVersionInput : phoneVersionInput);
        Spinner androidSp = tab == 1 ? tabletAndroidVersion : (tab == 3 ? wearableAndroidVersion : phoneAndroidVersion);
        Spinner pkgSp = tab == 1 ? tabletPackageType : (tab == 3 ? wearablePackageType : phonePackageType);
        Spinner fullSp = tab == 1 ? tabletFullOrIncremental : (tab == 3 ? wearableFullOrIncremental : phoneFullOrIncremental);
        Spinner domainSp = tab == 1 ? tabletDomainSpinner : (tab == 3 ? wearableDomainSpinner : phoneDomainSpinner);
        verInput.setText(version);
        androidSp.setSelection(androidPos);
        pkgSp.setSelection(channelPos);
        fullSp.setSelection(full ? 0 : 1);
        domainSp.setSelection(domainPos);
        // 机型回填：在当前页签的分类里找 codename + model_sw_ver 匹配的条目
        String tabKey = tab == 1 ? TAB_TABLET : (tab == 3 ? TAB_WEARABLE : TAB_PHONE);
        String pd = e.optString("codename");
        String swVer = e.optString("model_sw_ver");
        List<String> categories = categoriesByTab.get(tabKey);
        if (categories != null && catalog != null) {
            outer:
            for (int c = 0; c < categories.size(); c++) {
                JSONArray arr = catalog.optJSONArray(tabKey + ":" + categories.get(c));
                if (arr == null) continue;
                for (int d = 0; d < arr.length(); d++) {
                    org.json.JSONObject item = arr.optJSONObject(d);
                    if (item != null && pd.equals(item.optString("codename"))
                            && swVer.equals(item.optString("model_sw_ver"))) {
                        final int devPos = d;
                        final Spinner deviceSp = device;
                        category.setSelection(c, true);
                        // 等待分类联动刷新机型列表后再选中机型
                        deviceSp.postDelayed(() -> {
                            if (devPos < deviceSp.getCount()) deviceSp.setSelection(devPos, true);
                        }, 150);
                        break outer;
                    }
                }
            }
        }
        status.setText("已回填历史查询条件，可直接重新查询");
    }

    /** 在线刷新机型库（同步自上游 VivoDeviceDatabase.refresh）：拉取最新 vivo 国行机型并合并缓存。 */
    private void refreshCatalog() {
        if (refreshCatalogButton != null) refreshCatalogButton.setEnabled(false);
        status.setText("正在在线刷新机型库...");
        executor.execute(() -> {
            com.mytiantian.updater.vivo.VivoDeviceDatabase.RefreshResult r =
                    com.mytiantian.updater.vivo.VivoDeviceDatabase.INSTANCE.refresh(this);
            runOnUiThread(() -> {
                if (refreshCatalogButton != null) refreshCatalogButton.setEnabled(true);
                if (r == com.mytiantian.updater.vivo.VivoDeviceDatabase.RefreshResult.UPDATED) {
                    android.widget.Toast.makeText(VivoActivity.this, "机型库已更新", android.widget.Toast.LENGTH_SHORT).show();
                    loadCatalog();
                } else if (r == com.mytiantian.updater.vivo.VivoDeviceDatabase.RefreshResult.UNCHANGED) {
                    status.setText("机型库已是最新，无需更新");
                } else {
                    status.setText("机型库在线刷新失败，已保留本地数据");
                }
            });
        });
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

    private Button glassButton(String text, float textSize) {
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

    private TextView label(String text, float textSize, int color) {
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
    
    // v3.8.8：下载执行与状态持久化已全部收敛到 DownloadService（多任务版）+
    // PageTaskCards（页面多任务下载框）+ DownloadTaskStore（持久化对账），
    // 本页不再维护单任务下载状态。

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
