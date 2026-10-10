package com.probiotics.xiaoni;

import android.app.*;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.*;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Paint;
import android.graphics.BitmapShader;
import android.graphics.Shader;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.view.WindowManager;
import com.topjohnwu.superuser.ipc.RootService;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Calendar;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.io.*;
import java.net.HttpURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends BaseActivity {
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        Haptics.onTouch(getWindow().getDecorView(), event);
        return super.dispatchTouchEvent(event);
    }
    private TextView rootStatus, gsiStatus, detailText;
    private FrameLayout logoCard;
    private static final int PICK_IMAGE = 10;
    private static final int PICK_ZIP = 20;
    private static final int PICK_REPLACEMENT = 30;
    private static final int PICK_ROOTFS = 40;
    private static final int PICK_FASTBOOT_IMAGE = 50;
    private static final int REQUEST_FLASH_IMAGE_FILE = 60;
    private static final int REQUEST_OTG_SINGLE_IMAGE = 70;
    private static final int REQUEST_OTG_FULL_PACKAGE = 71;
    private static final int REQUEST_OTG_ADB_PUSH = 72;
    private String replacementPartition;
    private String replacementBackingImage;
    private String replacementSlot;
    private String replacementImagePath;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingLogoClick;
    private String installedZipName = "未记录 ZIP 名称";
    private long userdataSizeBytes = 16L * 1024L * 1024L * 1024L;
    private String pendingSizeLabel = "16 GB";
    private boolean keepScreenOn;
    private boolean rootServiceBound;
    private boolean english;
    private int languageMode;
    private boolean rootAuthorized;
    private boolean rootCheckInProgress;
    // v3.51.75: 缓存Root方法检测结果，避免每次进入检测页都执行耗时的shell命令
    private String cachedRootMethod = null;
    // v3.51.81: SoC数据库和native库支持
    static {
        try {
            System.loadLibrary("devcheck");
        } catch (Throwable e) {
            android.util.Log.e("MainActivity", "Failed to load libdevcheck.so", e);
        }
    }
    private JSONObject socsDatabase = null;
    private JSONObject currentSocInfo = null;
    private View[] actionButtons;
    private static final String DSU_SLOT = "dsu";
    private LinearLayout installPanel, installOptionsPanel;
    private FrameLayout pageHost;
    private ScrollView homeScroll;
    private FrameLayout bottomNavigation;
    private LinearLayout bottomNavigationItems;
    private View liquidIndicator;
    private int liquidIndicatorLeft = -1;
    private ValueAnimator liquidNavigationFlow;
    private ValueAnimator bottomNavigationPulse;
    private Runnable navigationHold;
    private boolean navigationDragging;
    private int navigationTarget;
    private float navigationStartX;
    private View navigationGestureView;
    private TextView embeddedUpdateStatus, embeddedReleaseNotes, embeddedDownloadHint;
    private ScrollView embeddedReleaseNotesScroll;
    private Button embeddedDownloadButton;
    private int currentTab;
    // CPU频率监控
    private Handler cpuFreqHandler;
    private Runnable cpuFreqUpdateTask;
    // v3.10.0：DNA 标签页内嵌「设置」子模式（设置入口在 DNA 页标题栏，返回时切回 DNA 主页）
    private boolean dnaSettingsMode;
    // v3.28.2：DNA 主页工具链状态 / 当前工程视图
    private TextView dnaStatusView;
    private TextView dnaProjectView;
    // v3.50.11：DNA 工具链文件下载状态（右上角显示）
    private TextView dnaToolsDownloadStatus;
    // v3.41.11：工具链云端下载防重入（下载中禁点）
    private final java.util.concurrent.atomic.AtomicBoolean dnaDownloading = new java.util.concurrent.atomic.AtomicBoolean(false);
    private LinearLayout imageManagementPanel;
    private FrameLayout contentRoot;   // 根布局（引导页淡入转场用）
    // OTG 页面相关
    private AdbManager adbManager;
    private EditText fastbootFilePathInput;
    private EditText fastbootImagePathInput;  // 用于 Fastboot 刷机面板的镜像路径输入
    private Button[] otgTabs;
    private LinearLayout otgTabItems;
    private LiquidGlassIndicator otgTabIndicator;
    private int otgTabIndicatorLeft = -1;
    private LinearLayout[] otgPanels;
    private int otgCurrentTab;
    private boolean otgTabDragging;
    private int otgTabDragTarget = -1;
    private float otgTabDragStartX;
    private Runnable otgTabLongPress;
    private android.animation.ValueAnimator otgTabPulse;
    private TextView installStage, installZipLabel;
    /** v3.9.5 分段进度：阶段行容器 + 阶段行索引（每个阶段一条独立 0-100% 进度条） */
    private LinearLayout stageHost;
    private final java.util.LinkedHashMap<String, StageRow> stageRows = new java.util.LinkedHashMap<>();
    private String currentStageId;
    private Button confirmInstallButton;
    private Button[] installSizeButtons;
    private EditText customInstallSizeInput;
    private Uri pendingInstallZip;
    private int selectedInstallSize = 1;
    private final java.util.concurrent.ExecutorService moreWorker = java.util.concurrent.Executors.newFixedThreadPool(2);
    private IPrivilegedService privilegedService;
    private final ServiceConnection rootConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            privilegedService = IPrivilegedService.Stub.asInterface(service);
            rootServiceBound = true;
            refreshRootStatus();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            privilegedService = null;
            rootServiceBound = false;
            setRootAuthorized(false);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        installCrashCatcher();
        showCrashIfAny();
        languageMode = getSharedPreferences("settings", MODE_PRIVATE).getInt("language_mode", 0);
        english = languageMode == 2 || (languageMode == 0 && Locale.getDefault().getLanguage().equals("en"));
         installedZipName = getPreferences(MODE_PRIVATE).getString("installed_zip_name", "未记录 ZIP 名称");
         keepScreenOn = getPreferences(MODE_PRIVATE).getBoolean("keep_screen_on", false);
         applyKeepScreenOn();
        getWindow().setStatusBarColor(0x00000000);
        getWindow().setNavigationBarColor(0x220b131f);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        // 初始化 ADB Manager
        adbManager = new AdbManager(this);
        new Thread(() -> adbManager.extractBinaries()).start();
        // v3.41.13：后台清理上次进程遗留的 JNI 提取整包副本 / OTA 解包目录（可达十几 G）
        DnaTools.INSTANCE.cleanStaleCaches(this);
        // v3.51.81: 加载 SoC 数据库
        loadSocsDatabase();
        buildUi();
        // 从引导页淡入进入：只淡入内容层（页面 + 底部导航），渐变背景常驻 → 任何 ROM 都不闪黑屏
        if (getIntent().getBooleanExtra("crossfade_entry", false)) {
            View[] fadeLayers = {pageHost, bottomNavigation};
            for (View layer : fadeLayers) {
                if (layer == null) continue;
                layer.setAlpha(0f);
                layer.post(() -> layer.animate().alpha(1f)
                        .setDuration(420L)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.2f))
                        .start());
            }
        }
        bindRootService();
        refreshRootStatus();
        // v3.9.1：启动自动检查新版本（每进程一次，静默；有新版本才弹窗）
        UpdateCenter.autoCheck(this);
    }

    @Override protected void onResume() {
        super.onResume();
        int selectedLanguage = getSharedPreferences("settings", MODE_PRIVATE).getInt("language_mode", 0);
        if (selectedLanguage != languageMode) {
            languageMode = selectedLanguage;
            english = languageMode == 2 || (languageMode == 0 && Locale.getDefault().getLanguage().equals("en"));
            buildUi();
        }
        // v3.41.22：GSI 状态无条件自动刷新（root 已授权时）—— 此前仅在语言变化分支里刷新，
        // 从设置页/其他页返回（语言未变）或 buildUi 静默重建后状态卡停在「正在读取」，
        // 看起来像检测失败，用户得手动点一次「检测 GSI 状态」才恢复
        if (rootAuthorized) refreshStatus();
        // v3.41.22：大缓存自动清理 —— root 属主副本可能高达十几 G，启动/返回路过即后台清
        autoDeepCleanIfNeeded();
        // 应用窗口透明设置
        boolean isTransparent = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("window_transparent_bg", false);
        applyWindowTransparency(isTransparent);
        bindRootService();
        if (!rootAuthorized) refreshRootStatus();
        // v3.51.77: 删除 refreshMorePage() 调用 - tab=4是检测页，不是终端页
        // if (currentTab == 4) refreshMorePage();
    }

    /** v3.41.22：可清理缓存超过 100M 时后台自动深度清理（全扫描白名单 + su 兜底），完成后提示 */
    private void autoDeepCleanIfNeeded() {
        new Thread(() -> {
            try {
                long cleanable = DnaTools.INSTANCE.cleanableBytes(this);
                if (cleanable <= 100L * 1024 * 1024) return;
                long freed = DnaTools.INSTANCE.deepCleanAll(this);
                if (freed > 0) {
                    runOnUiThread(() -> toast("♻ " + t("已自动清理缓存副本", "Auto-cleaned cache copies")
                            + " " + String.format(Locale.US, "%.2fG", freed / 1073741824f)));
                }
            } catch (Throwable ignored) { }
        }, "auto-deep-clean").start();
    }

    @Override protected void onPause() {
        super.onPause();
        // v3.51.68：保存当前页面位置
        getSharedPreferences("settings", MODE_PRIVATE).edit().putInt("last_tab", currentTab).apply();
    }

    private void installCrashCatcher() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                ex.printStackTrace(new java.io.PrintWriter(sw));
                java.io.File f = new java.io.File(getFilesDir(), "last_crash.txt");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                fos.write(("线程: " + thread.getName() + "\n\n" + sw.toString()).getBytes("UTF-8"));
                fos.close();
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(thread, ex);
        });
    }

    private void showCrashIfAny() {
        try {
            java.io.File f = new java.io.File(getFilesDir(), "last_crash.txt");
            if (!f.exists()) return;
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append("\n");
            r.close();
            f.delete();
            final String crash = sb.toString();
            android.widget.ScrollView sv = new android.widget.ScrollView(this);
            TextView tv = new TextView(this);
            tv.setPadding(dp(16), dp(48), dp(16), dp(16));
            tv.setTextSize(11);
            tv.setTextColor(0xffff3333);
            tv.setTextIsSelectable(true);
            tv.setText("上次崩溃堆栈 (长按复制):\n\n" + crash);
            sv.addView(tv);
            new AlertDialog.Builder(this)
                    .setTitle("检测到上次崩溃")
                    .setView(sv)
                    .setPositiveButton("关闭", null)
                    .show();
        } catch (Throwable ignored) { }
    }

    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }
    private TextView text(String value, int size, int color) { TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private String t(String chinese, String englishText) { return english ? englishText : chinese; }

    private void updateActionButtons() {
        if (actionButtons == null) return;
        for (View button : actionButtons) {
            button.setEnabled(rootAuthorized);
            button.setAlpha(rootAuthorized ? 1f : 0.45f);
        }
    }

    private void setRootAuthorized(boolean authorized) {
        rootAuthorized = authorized;
        if (rootStatus != null) {
            rootStatus.setText(authorized ? t("ROOT 已授权", "ROOT granted") : t("ROOT 失败", "ROOT unavailable"));
            rootStatus.setBackgroundResource(authorized ? R.drawable.root_status_bg : R.drawable.button_red);
        }
        updateActionButtons();
        if (authorized && detailText != null) refreshStatus();
    }

    private void bindRootService() {
        if (rootServiceBound || privilegedService != null) return;
        rootServiceBound = true;
        RootService.bind(new Intent(this, PrivilegedRootService.class), rootConnection);
        mainHandler.postDelayed(() -> {
            if (privilegedService == null) rootServiceBound = false;
        }, 1000);
    }

     private void buildUi() {
         currentTab = 0;
         LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
         content.setPadding(dp(18), dp(14), dp(18), dp(96));
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bars = insets.getSystemWindowInsetBottom();
            // v3.9.7：edge-to-edge（setDecorFitsSystemWindows(false)）下 adjustResize
            // 不再缩放窗口，键盘高度必须从 Type.ime() 单独取；取 max 兼容旧版
            // system window insets 已含 IME 的系统。键盘弹出时 content 底部内边距
            // 随之变大，ScrollView 才有余量把输入框滚到键盘上方。
            int ime = 0;
            if (Build.VERSION.SDK_INT >= 30)
                ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
            int bottom = Math.max(bars, ime);
             view.setPadding(dp(18), dp(14) + top, dp(18), dp(96) + bottom);
            return insets;
        });

         LinearLayout bar = new LinearLayout(this);
         bar.setGravity(Gravity.CENTER_VERTICAL);
         bar.setPadding(dp(14), 0, dp(10), 0);
         bar.setBackgroundResource(R.drawable.liquid_glass_panel);
          TextView title = text(t("Dsu 管理器", "Dsu Manager"), 28, Color.rgb(20,29,55));
        title.setTypeface(null, 1);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(58), 1));
         rootStatus = text(t("ROOT 检测中", "Checking ROOT"), 13, Color.WHITE);
        rootStatus.setGravity(Gravity.CENTER);
         rootStatus.setPadding(0, 0, 0, 0);
         rootStatus.setBackgroundResource(R.drawable.root_status_bg);
        rootStatus.setOnClickListener(v -> refreshRootStatus());
        bar.addView(rootStatus, new LinearLayout.LayoutParams(-2, dp(36)));
         LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, dp(58));
         content.addView(bar, barLp);

        // Logo 卡片：纯图片背景，不显示任何文字
        logoCard = new FrameLayout(this);
        logoCard.setClipToOutline(true);
        logoCard.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(28));
            }
        });
        
        // 背景图片：完整显示
        final ImageView bgImage = new ImageView(this);
        bgImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bgImage.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        try {
            Bitmap defaultBg = android.graphics.BitmapFactory.decodeResource(getResources(), R.drawable.logo_bg);
            if (defaultBg != null) {
                bgImage.setImageBitmap(defaultBg);
            } else {
                bgImage.setImageResource(R.drawable.logo_glass_bg);
            }
        } catch (Exception e) {
            bgImage.setImageResource(R.drawable.logo_glass_bg);
        }
        logoCard.addView(bgImage);
        
        // 4. 点击事件：双击恢复默认，单击选择图片
        final ImageView finalBgImage = bgImage;
        logoCard.setOnTouchListener((view, event) -> {
            if (event.getAction() != MotionEvent.ACTION_UP) return true;
            Haptics.perform(view);
            if (pendingLogoClick != null) {
                mainHandler.removeCallbacks(pendingLogoClick);
                pendingLogoClick = null;
                // 双击：恢复默认背景
                try {
                    Bitmap defaultBg = android.graphics.BitmapFactory.decodeResource(getResources(), R.drawable.logo_bg);
                    if (defaultBg != null) {
                        finalBgImage.setImageBitmap(defaultBg);
                    } else {
                        finalBgImage.setImageResource(R.drawable.logo_glass_bg);
                    }
                } catch (Exception e) {
                    finalBgImage.setImageResource(R.drawable.logo_glass_bg);
                }
                toast(t("已恢复默认背景图", "Default background restored"));
            } else {
                // 单击：选择图片
                pendingLogoClick = () -> { pendingLogoClick = null; chooseImage(finalBgImage); };
                mainHandler.postDelayed(pendingLogoClick, 280);
            }
            return true;
        });
         // Logo 卡片：纯图片，不显示任何状态
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, dp(170));
        cardLp.setMargins(0, dp(14), 0, 0);
        cardLp.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(logoCard, cardLp);
        
        // 提示文字：点击卡片更换背景图片（右下角显示）
        TextView changeBgHint = text(t("点击卡片更换背景图片", "Tap card to change background"), 11, 0xFF94A3B8);
        changeBgHint.setGravity(Gravity.END);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.setMargins(0, dp(6), dp(14), dp(10));
        content.addView(changeBgHint, hintLp);

         LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
          String[] labels = english
                  ? new String[]{"Check GSI status", "Install GSI", "Reboot to DSU", "Remove installed GSI", "Manage installed images"}
                  : new String[]{"检测 GSI 状态", "安装 GSI", "重启到 DSU", "撤销已安装 GSI", "管理已安装镜像"};
          String[] icons = {"🔍", "📥", "🔄", "🗑️", "🗂️"};
          String[] descs = english
                  ? new String[]{"Read DSU status", "Pick image & install", "Reboot into DSU", "Remove installed DSU", "Replace or export installed images"}
                  : new String[]{"读取 DSU 运行状态", "选择镜像安装 DSU", "重启进入动态系统", "清理已安装动态系统", "替换 / 导出已安装分区镜像"};
         // 液态玻璃功能卡片（图三效果）：五色语义化对角渐变 + 白色高光描边 + 大圆角 + 涟漪 + 振动反馈
         int[][] glassColors = {
                 {0xFF7FAAF5, 0xFF4C74DE, 0xFF3E63C9},   // 检测 GSI：蓝紫
                 {0xFF6FD9AE, 0xFF2FB47C, 0xFF1E8A60},   // 安装 GSI：青绿
                 {0xFFF3C777, 0xFFDB9A45, 0xFFB57623},   // 重启到 DSU：金棕
                 {0xFFDBA8F2, 0xFFB275E0, 0xFF8E52C6},   // 撤销 GSI：粉紫
                 {0xFF9FB0D4, 0xFF7284AB, 0xFF55678D}    // 管理镜像：蓝灰
         };
         actionButtons = new View[labels.length];
         // 排列方式：前 4 张两列网格（检测/安装 + 重启/撤销），第 5 张通栏横卡（管理镜像）
         LinearLayout[] gridRows = new LinearLayout[2];
         for (int i = 0; i < labels.length; i++) {
             View card = gsiGlassCard(icons[i], labels[i], descs[i], glassColors[i], i, i == labels.length - 1);
             actionButtons[i] = card;
             if (i < 4) {
                 int rowIndex = i / 2;
                 if (i % 2 == 0) {
                     gridRows[rowIndex] = new LinearLayout(this);
                     gridRows[rowIndex].setOrientation(LinearLayout.HORIZONTAL);
                     LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
                     rowLp.topMargin = dp(6);
                     list.addView(gridRows[rowIndex], rowLp);
                 }
                 LinearLayout.LayoutParams gridCardLp = new LinearLayout.LayoutParams(0, dp(108), 1f);
                 gridCardLp.setMargins(i % 2 == 0 ? 0 : dp(6), 0, i % 2 == 0 ? dp(6) : 0, 0);
                 gridRows[rowIndex].addView(card, gridCardLp);
             } else {
                 LinearLayout.LayoutParams wideLp = new LinearLayout.LayoutParams(-1, dp(74));
                 wideLp.topMargin = dp(6);
                 list.addView(card, wideLp);
             }
         }
         updateActionButtons();
          
          // 状态显示框：显示 GSI 状态和操作结果
          LinearLayout statusPanel = new LinearLayout(this);
          statusPanel.setOrientation(LinearLayout.VERTICAL);
          statusPanel.setPadding(dp(14), dp(10), dp(14), dp(10));
          statusPanel.setBackgroundResource(R.drawable.liquid_glass_panel);
          
          gsiStatus = text(rootAuthorized ? t("正在读取动态系统状态...", "Reading Dynamic System status...")
                   : t("需要 ROOT 权限", "ROOT access required"), 14, Color.rgb(77, 87, 105));
          gsiStatus.setTypeface(null, Typeface.BOLD);
          statusPanel.addView(gsiStatus, new LinearLayout.LayoutParams(-1, -2));
          
          detailText = text(t("点击操作后，结果会显示在这里。", "Results will appear here after an action."), 13, Color.rgb(77, 87, 105));
          detailText.setGravity(Gravity.TOP);
          detailText.setMinHeight(dp(50));
          LinearLayout.LayoutParams detailLp2 = new LinearLayout.LayoutParams(-1, -2);
          detailLp2.topMargin = dp(8);
          statusPanel.addView(detailText, detailLp2);
          
          LinearLayout.LayoutParams statusPanelLp = new LinearLayout.LayoutParams(-1, -2);
          statusPanelLp.setMargins(0, 0, 0, dp(4));
          content.addView(statusPanel, statusPanelLp);
         installOptionsPanel = new LinearLayout(this);
         installOptionsPanel.setOrientation(LinearLayout.VERTICAL);
         installOptionsPanel.setPadding(dp(12), dp(10), dp(12), dp(10));
          installOptionsPanel.setBackgroundResource(R.drawable.liquid_glass_panel);
         TextView installTitle = text(t("安装 GSI 参数", "Install GSI options"), 15, Color.rgb(20, 29, 55));
         installTitle.setTypeface(null, 1);
         installOptionsPanel.addView(installTitle, new LinearLayout.LayoutParams(-1, dp(34)));
         LinearLayout sizeRow = new LinearLayout(this);
         sizeRow.setOrientation(LinearLayout.HORIZONTAL);
         installSizeButtons = new Button[4];
         String[] installSizes = {"8 GB", "16 GB", "32 GB", "64 GB"};
         // v3.42.11：容量上限按剩余空间动态计算（参考 DSU-Sideloader），不再固定 128GB
         double maxGb = maxDsuSizeGb();
         for (int i = 0; i < installSizes.length; i++) {
             final int sizeIndex = i;
             Button sizeButton = new Button(this);
             installSizeButtons[i] = sizeButton;
             sizeButton.setText(installSizes[i]);
             sizeButton.setTextSize(12);
             sizeButton.setAllCaps(false);
             sizeButton.setMinWidth(0);
             sizeButton.setMinHeight(0);
             sizeButton.setPadding(0, 0, 0, 0);
             // 预设超过本机可分配上限 → 置灰禁用，避免选了装不上
             if (Integer.parseInt(installSizes[i].replace(" GB", "")) > maxGb) {
                 sizeButton.setEnabled(false);
                 sizeButton.setTextColor(Color.rgb(168, 175, 189));
             }
             sizeButton.setOnClickListener(v -> selectInstallSize(sizeIndex, installSizes[sizeIndex]));
             LinearLayout.LayoutParams sizeLp = new LinearLayout.LayoutParams(0, dp(44), 1);
             if (i > 0) sizeLp.setMargins(dp(5), 0, 0, 0);
             sizeRow.addView(sizeButton, sizeLp);
         }
         installOptionsPanel.addView(sizeRow, new LinearLayout.LayoutParams(-1, dp(44)));
         // v3.42.11：实时存储信息行 —— 剩余空间 + 自定义容量动态上限
         long freeBytesNow = dataFreeBytes();
         TextView storageInfo = text(t(
                 "剩余空间 " + (freeBytesNow >= 0 ? formatSizeBytesHuman(freeBytesNow) : "未知")
                         + " · 自定义容量上限 " + (int) maxGb + " GB",
                 "Free " + (freeBytesNow >= 0 ? formatSizeBytesHuman(freeBytesNow) : "unknown")
                         + " · custom size up to " + (int) maxGb + " GB"),
                 11, Color.rgb(90, 100, 120));
         LinearLayout.LayoutParams storageInfoLp = new LinearLayout.LayoutParams(-1, -2);
         storageInfoLp.topMargin = dp(6);
         installOptionsPanel.addView(storageInfo, storageInfoLp);
         customInstallSizeInput = new EditText(this);
          customInstallSizeInput.setHint(t("自定义容量 GB", "Custom size GB"));
          customInstallSizeInput.setSingleLine(true);
          customInstallSizeInput.setTextSize(13);
          customInstallSizeInput.setGravity(Gravity.CENTER);
          customInstallSizeInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
           customInstallSizeInput.setBackgroundResource(R.drawable.liquid_glass_panel);
          customInstallSizeInput.setOnFocusChangeListener((view, hasFocus) -> {
              String value = customInstallSizeInput.getText().toString().trim();
              setBottomNavigationVisible(!hasFocus);
              if (hasFocus) {
                  // v3.9.5：唤起键盘时把输入框滚动到可见区域（此前被键盘完全挡住，看不到输入内容）
                  view.postDelayed(() -> {
                      android.graphics.Rect rect = new android.graphics.Rect();
                      customInstallSizeInput.getHitRect(rect);
                      customInstallSizeInput.requestRectangleOnScreen(rect, false);
                  }, 250);
              }
              if (hasFocus || value.isEmpty()) return;
              try {
                  customInstallSizeInput.setText(formatCustomSize(parseCustomSize(value)) + " GB");
              } catch (NumberFormatException ignored) { }
          });
         Button customSizeButton = new Button(this);
         customSizeButton.setText(t("使用自定义", "Use custom"));
         customSizeButton.setTextSize(12);
         customSizeButton.setAllCaps(false);
         customSizeButton.setMinWidth(0);
         customSizeButton.setMinHeight(0);
         customSizeButton.setTextColor(Color.WHITE);
         customSizeButton.setBackgroundResource(R.drawable.button_teal);
          customSizeButton.setOnClickListener(v -> {
              Haptics.perform(v);
              applyCustomInstallSize();
          });
         LinearLayout customRow = new LinearLayout(this);
         customRow.setGravity(Gravity.CENTER_VERTICAL);
         customRow.addView(customInstallSizeInput, new LinearLayout.LayoutParams(0, dp(46), 1));
         LinearLayout.LayoutParams customButtonLp = new LinearLayout.LayoutParams(dp(108), dp(44));
         customButtonLp.setMargins(dp(6), 0, 0, 0);
         customRow.addView(customSizeButton, customButtonLp);
         LinearLayout.LayoutParams customRowLp = new LinearLayout.LayoutParams(-1, dp(52));
         customRowLp.setMargins(0, dp(8), 0, 0);
         installOptionsPanel.addView(customRow, customRowLp);
         LinearLayout zipRow = new LinearLayout(this);
         zipRow.setGravity(Gravity.CENTER_VERTICAL);
         Button chooseZipButton = new Button(this);
         chooseZipButton.setText(t("选择 ZIP", "Choose ZIP"));
         chooseZipButton.setTextSize(12);
         chooseZipButton.setAllCaps(false);
         chooseZipButton.setMinWidth(0);
         chooseZipButton.setMinHeight(0);
         chooseZipButton.setTextColor(Color.WHITE);
         chooseZipButton.setBackgroundResource(R.drawable.button_blue);
         chooseZipButton.setOnClickListener(v -> chooseZip());
         zipRow.addView(chooseZipButton, new LinearLayout.LayoutParams(dp(112), dp(44)));
         installZipLabel = text(t("尚未选择 ZIP", "No ZIP selected"), 12, Color.rgb(77, 87, 105));
         installZipLabel.setSingleLine(true);
         installZipLabel.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
         LinearLayout.LayoutParams zipLabelLp = new LinearLayout.LayoutParams(0, dp(44), 1);
         zipLabelLp.setMargins(dp(8), 0, 0, 0);
         zipRow.addView(installZipLabel, zipLabelLp);
         installOptionsPanel.addView(zipRow, new LinearLayout.LayoutParams(-1, dp(48)));
         confirmInstallButton = new Button(this);
         confirmInstallButton.setText(t("确定安装 ZIP", "Install ZIP"));
         confirmInstallButton.setTextSize(13);
         confirmInstallButton.setAllCaps(false);
         confirmInstallButton.setMinHeight(0);
         confirmInstallButton.setTextColor(Color.WHITE);
         confirmInstallButton.setEnabled(false);
         confirmInstallButton.setBackgroundResource(R.drawable.button_green);
         confirmInstallButton.setOnClickListener(v -> confirmInstallZip());
         LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(-1, dp(46));
         confirmLp.setMargins(0, dp(8), 0, 0);
         installOptionsPanel.addView(confirmInstallButton, confirmLp);
         installOptionsPanel.setVisibility(View.GONE);
          LinearLayout.LayoutParams installOptionsLp = new LinearLayout.LayoutParams(-1, -2);
          installOptionsLp.setMargins(0, dp(2), 0, 0);
          content.addView(installOptionsPanel, installOptionsLp);
         selectInstallSize(1, "16 GB");

         installPanel = new LinearLayout(this);
        installPanel.setOrientation(LinearLayout.VERTICAL);
        installPanel.setPadding(dp(14), dp(10), dp(14), dp(12));
          installPanel.setBackgroundResource(R.drawable.liquid_glass_panel);
         installStage = text(t("安装进度", "Installation progress"), 14, Color.rgb(40, 50, 70));
         installStage.setTypeface(null, 1);
        installPanel.addView(installStage, new LinearLayout.LayoutParams(-1, dp(26)));
        // v3.9.5：分段进度 —— 解析/解压/每镜像写入各自独立进度条，不再共用一个全局百分比
        stageHost = new LinearLayout(this);
        stageHost.setOrientation(LinearLayout.VERTICAL);
        installPanel.addView(stageHost, new LinearLayout.LayoutParams(-1, -2));
        installPanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(-1, -2);
          progressLp.setMargins(0, dp(4), 0, 0);
         content.addView(installPanel, progressLp);
         imageManagementPanel = new LinearLayout(this);
         imageManagementPanel.setOrientation(LinearLayout.VERTICAL);
          imageManagementPanel.setPadding(dp(16), dp(14), dp(16), dp(14));
            imageManagementPanel.setBackgroundResource(R.drawable.image_management_bg);
          imageManagementPanel.setVisibility(View.GONE);
          LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(-1, -2);
          listLp.setMargins(0, 0, 0, dp(12));
          content.addView(list, listLp);
          content.addView(imageManagementPanel, new LinearLayout.LayoutParams(-1, -2));
          homeScroll = new ScrollView(this);
          homeScroll.setFillViewport(true);
          homeScroll.setBackgroundColor(Color.TRANSPARENT);
          homeScroll.addView(content);
          pageHost = new FrameLayout(this);
          FrameLayout.LayoutParams homeParams = new FrameLayout.LayoutParams(-1, -1);
          // v3.51.68：恢复上次打开的页面，首次打开默认DSU页
          int lastTab = getSharedPreferences("settings", MODE_PRIVATE).getInt("last_tab", 0);
          currentTab = lastTab;
          
          // 根据lastTab初始化对应页面
          View initialPage;
          switch (lastTab) {
              case 0: initialPage = homeScroll; break;
              case 1: initialPage = wrapInScroll(buildDnaPage()); break;
              case 2: initialPage = wrapInScroll(buildRomPage()); break;
              case 3: initialPage = buildOtgPageWrapper(); break;
              case 4: initialPage = buildCheckPageLoading(); break;  // v3.51.80: 检测页异步加载
              default: initialPage = homeScroll; currentTab = 0; break;
          }
          pageHost.addView(initialPage, homeParams);
         FrameLayout root = new FrameLayout(this);
          root.setBackgroundResource(R.drawable.liquid_backdrop);
          root.setOnApplyWindowInsetsListener((view, insets) -> {
              view.setPadding(0, insets.getSystemWindowInsetTop(), 0, 0);
              if (bottomNavigation != null) {
                 ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) bottomNavigation.getLayoutParams();
                 margins.bottomMargin = dp(14) + insets.getSystemWindowInsetBottom();
                 bottomNavigation.setLayoutParams(margins);
             }
             return insets;
         });
         bottomNavigation = buildBottomNavigation();
         root.addView(pageHost, new FrameLayout.LayoutParams(-1, -1));
         root.addView(bottomNavigation, bottomNavigationParams());
         
         // v3.51.72: 初始化后更新底部导航高亮状态
         root.post(() -> animateNavigation(currentTab));
         root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
              android.graphics.Rect visibleFrame = new android.graphics.Rect();
              root.getWindowVisibleDisplayFrame(visibleFrame);
              boolean keyboardVisible = root.getRootView().getHeight() - visibleFrame.bottom > dp(120);
              setBottomNavigationVisible(!keyboardVisible);
              // v3.9.7：键盘弹出时手动把聚焦的输入框滚到键盘上方 ——
              // edge-to-edge 下窗口不缩放，requestRectangleOnScreen 感知不到键盘
              if (keyboardVisible) revealFocusedInputAboveKeyboard(visibleFrame.bottom);
          });
          root.post(() -> applySystemInsets(root, root.getRootWindowInsets()));
         contentRoot = root;   // 供引导页淡入转场使用
         setContentView(root);
         // launch 检测页只在用户点击「检测」后打开，避免 Native 检测器初始化失败
         // 时阻断主 APP 的启动链路。
         // v3.41.20：buildUi 重建后恢复状态卡 —— 此前语言切换触发重建时 rootStatus 永远
         // 停在「ROOT 检测中」、gsiStatus 停在「正在读取动态系统状态...」，看起来像 GSI 检测失败。
         // rootStatus 先按已知状态落文案，GSI 状态立即发起一次真实刷新。
         if (rootStatus != null) {
             rootStatus.setText(rootAuthorized ? t("ROOT 已授权", "ROOT granted") : t("ROOT 检测中", "Checking ROOT"));
             rootStatus.setBackgroundResource(rootAuthorized ? R.drawable.root_status_bg : R.drawable.button_blue);
         }
         updateActionButtons();
         if (rootAuthorized) refreshStatus();
         else if (!rootCheckInProgress) refreshRootStatus();
     }

     /** 液态玻璃功能卡片（图三）：对角三段渐变 + 白色高光描边 + 大圆角 + 白色涟漪 + 悬浮阴影；点击全局振动反馈 */
     private View gsiGlassCard(String icon, String label, String desc, int[] colors, int index, boolean wide) {
         LinearLayout card = new LinearLayout(this);
         card.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
         if (wide) card.setGravity(Gravity.CENTER_VERTICAL);
         card.setPadding(dp(16), dp(14), dp(16), dp(12));
         // 品牌液态玻璃框（与顶部 logo 框同构三层：品牌渐变底 + 白描边层 + 顶部高光带）
         GradientDrawable base = new GradientDrawable();
         base.setOrientation(GradientDrawable.Orientation.TL_BR);
         base.setColors(new int[]{colors[0], colors[1], colors[2]});
         base.setCornerRadius(dp(22));
         GradientDrawable strokeLayer = new GradientDrawable();
         strokeLayer.setColor(0x12000000);
         strokeLayer.setStroke(Math.max(1, dp(1)), 0xCFFFFFFF);
         strokeLayer.setCornerRadius(dp(21));
         GradientDrawable highlight = new GradientDrawable();
         highlight.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
         highlight.setColors(new int[]{0x00FFFFFF, 0x59FFFFFF, 0x00FFFFFF});
         highlight.setCornerRadius(dp(15));
         android.graphics.drawable.LayerDrawable glass =
                 new android.graphics.drawable.LayerDrawable(
                         new android.graphics.drawable.Drawable[]{base, strokeLayer, highlight});
         glass.setLayerInset(1, 1, 1, 1, 1);
         glass.setLayerInset(2, dp(2), dp(1), dp(2), dp(18));
         // 白色半透明涟漪叠加在玻璃层之上，提供按压反馈
         android.graphics.drawable.RippleDrawable ripple =
                 new android.graphics.drawable.RippleDrawable(
                         new android.content.res.ColorStateList(
                                 new int[][]{{android.R.attr.state_pressed}}, new int[]{0x40FFFFFF}),
                         glass, null);
         card.setBackground(ripple);
         card.setElevation(dp(8));
         card.setOnClickListener(v -> {
             Haptics.perform(v);
             action(index);
         });

         TextView iconView = new TextView(this);
         iconView.setText(icon);
         iconView.setTextSize(23);
         TextView title = new TextView(this);
         title.setText(label);
         title.setTextSize(14.5f);
         title.setTypeface(null, 1);
         title.setTextColor(Color.WHITE);
         TextView descView = new TextView(this);
         descView.setText(desc);
         descView.setTextSize(10.5f);
         descView.setTextColor(0xE6FFFFFF);
         descView.setLineSpacing(dp(2), 1f);

         if (wide) {
             // 通栏横卡（管理镜像）：圆形玻璃徽章图标在左，标题 + 描述在右
             FrameLayout badge = new FrameLayout(this);
             GradientDrawable badgeBg = new GradientDrawable();
             badgeBg.setShape(GradientDrawable.OVAL);
             badgeBg.setColors(new int[]{0x38FFFFFF, 0x1FFFFFFF});
             badgeBg.setOrientation(GradientDrawable.Orientation.TL_BR);
             badgeBg.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
             badge.setBackground(badgeBg);
             iconView.setGravity(Gravity.CENTER);
             badge.addView(iconView, new FrameLayout.LayoutParams(-1, -1));
             card.addView(badge, new LinearLayout.LayoutParams(dp(46), dp(46)));

             LinearLayout textBox = new LinearLayout(this);
             textBox.setOrientation(LinearLayout.VERTICAL);
             textBox.setPadding(dp(14), 0, 0, 0);
             textBox.addView(title, new LinearLayout.LayoutParams(-1, -2));
             LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(-1, -2);
             descLp.topMargin = dp(3);
             textBox.addView(descView, descLp);
             card.addView(textBox, new LinearLayout.LayoutParams(0, -2, 1f));
         } else {
             // 网格卡：图标在上，标题 + 描述在下
             card.addView(iconView, new LinearLayout.LayoutParams(-2, -2));
             LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
             titleLp.topMargin = dp(7);
             card.addView(title, titleLp);
             LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(-1, -2);
             descLp.topMargin = dp(3);
             card.addView(descView, descLp);
         }
         return card;
     }

      private FrameLayout.LayoutParams bottomNavigationParams() {
          FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, dp(64), Gravity.BOTTOM);
          params.setMargins(dp(30), 0, dp(30), dp(16));
         return params;
     }

      private FrameLayout buildBottomNavigation() {
          FrameLayout navigation = new FrameLayout(this);
         navigation.setClipChildren(false);
         navigation.setClipToPadding(false);
          navigation.setPadding(dp(2), dp(2), dp(2), dp(2));
           navigation.setBackgroundResource(R.drawable.navigation_glass_bg);
          navigation.setElevation(0f);
          LinearLayout items = new LinearLayout(this);
         items.setGravity(Gravity.CENTER);
         items.setClipChildren(false);
         items.setClipToPadding(false);
          navigation.addView(items, new FrameLayout.LayoutParams(-1, -1));
          bottomNavigationItems = items;
          // v3.51.67：调整底部导航顺序为 DSU | DNA | ROM | OTG | 检测
         addNavigationItem(items, t("DSU", "DSU"), 0, v -> selectTab(0));
           addNavigationItem(items, t("DNA", "DNA"), 1, v -> selectTab(1));
           addNavigationItem(items, t("ROM", "ROM"), 2, v -> selectTab(2));
           addNavigationItem(items, t("OTG", "OTG"), 3, v -> selectTab(3));
          addNavigationItem(items, t("检测", "Check"), 4, v -> selectTab(4));
          liquidIndicator = new LiquidGlassIndicator(this);
          liquidIndicator.setElevation(dp(4));
          navigation.addView(liquidIndicator, new FrameLayout.LayoutParams(dp(62), dp(48)));
         // v3.51.76: 不要硬编码tab=0，等待初始化后通过animateNavigation设置
         // navigation.post(() -> moveLiquidIndicator(0, false));
         return navigation;
     }

     private void addNavigationItem(LinearLayout navigation, String label, int tab, View.OnClickListener listener) {
         TextView item = new TextView(this);
         item.setText(label);
         item.setTextSize(12);
         item.setGravity(Gravity.CENTER);
         item.setSingleLine(true);
         item.setIncludeFontPadding(false);
         item.setEllipsize(null);
         // v3.51.76: 不要硬编码tab==0的高亮，初始化后通过animateNavigation统一设置
           item.setTextColor(0xfff8fbff);
          item.setShadowLayer(dp(2), 0, dp(1), 0x66233c50);
         item.setPadding(0, 0, 0, 0);
          item.setBackground(null);
          item.setTag(tab);
          item.setStateListAnimator(null);
          // 拉丁字符标签（如 ROM）字形重心偏上：微调下移 0.75dp，与中文标签视觉居中对齐
          boolean latinOnly = true;
          for (int i = 0; i < label.length(); i++) {
              if (label.charAt(i) >= 0x2E80) { latinOnly = false; break; }
          }
          item.setTranslationY(latinOnly ? getResources().getDisplayMetrics().density * 0.75f : 0f);
           item.setOnTouchListener((view, event) -> handleNavigationGesture(view, event, tab));
         LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(0, dp(60), 1);
          itemParams.setMargins(dp(2), 0, dp(2), 0);
          navigation.addView(item, itemParams);
      }

       private boolean handleNavigationGesture(View view, MotionEvent event, int pressedTab) {
          switch (event.getActionMasked()) {
               case MotionEvent.ACTION_DOWN:
                   pressLiquidIndicator(true);
                   navigationDragging = false;
                   navigationTarget = -1;
                   navigationStartX = event.getRawX();
                   navigationGestureView = view;
                   navigationHold = () -> {
                       navigationDragging = true;
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                        view.getParent().requestDisallowInterceptTouchEvent(true);
                        expandDraggingLens();
                         previewNavigationTarget(navigationStartX);
                   };
                  mainHandler.postDelayed(navigationHold, android.view.ViewConfiguration.getLongPressTimeout());
                  return true;
               case MotionEvent.ACTION_MOVE:
                   if (!navigationDragging
                           && Math.abs(event.getRawX() - navigationStartX) >= dp(12)) {
                       cancelNavigationHold();
                       navigationDragging = true;
                       view.getParent().requestDisallowInterceptTouchEvent(true);
                       expandDraggingLens();
                       previewNavigationTarget(event.getRawX());
                   }
                   if (navigationDragging) {
                       float rawX = event.getRawX();
                       previewNavigationTarget(rawX);
                   }
                  return true;
               case MotionEvent.ACTION_UP:
                   cancelNavigationHold();
                     boolean dragged = navigationDragging;
                     navigationDragging = false;
                     if (dragged) finishDraggingLens();
                      else Haptics.perform(view);
                   pressLiquidIndicator(false);
                     selectTab(dragged ? navigationTarget : pressedTab);
                   navigationGestureView = null;
                   return true;
              case MotionEvent.ACTION_CANCEL:
                  cancelNavigationHold();
                    pressLiquidIndicator(false);
                    navigationDragging = false;
                    navigationGestureView = null;
                   return true;
              default:
                  return true;
          }
      }

      private void cancelNavigationHold() {
          if (navigationHold != null) mainHandler.removeCallbacks(navigationHold);
          navigationHold = null;
      }

        private void previewNavigationTarget(float rawX) {
            if (bottomNavigationItems == null || bottomNavigationItems.getChildCount() == 0) return;
            int[] location = new int[2];
            bottomNavigationItems.getLocationOnScreen(location);
            float itemWidth = bottomNavigationItems.getWidth() / (float) bottomNavigationItems.getChildCount();
            int tab = Math.max(0, Math.min(bottomNavigationItems.getChildCount() - 1, (int) ((rawX - location[0]) / itemWidth)));
            if (tab == navigationTarget) return;
             boolean movedBetweenTabs = navigationTarget >= 0;
             navigationTarget = tab;
             if (movedBetweenTabs && navigationGestureView != null) {
                 navigationGestureView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
             }
             moveDraggingLens(tab, movedBetweenTabs);
            updateNavigationLabels(tab);
        }

         private void moveDraggingLens(int tab, boolean animate) {
            if (liquidIndicator == null || bottomNavigationItems == null || bottomNavigation == null) return;
            int lensWidth = dp(78);
            int lensHeight = dp(64);
            View target = bottomNavigationItems.getChildAt(tab);
            int targetLeft = bottomNavigationItems.getLeft() + target.getLeft()
                    + (target.getWidth() - lensWidth) / 2;
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) liquidIndicator.getLayoutParams();
            int startLeft = liquidIndicatorLeft < 0 ? targetLeft
                    : Math.round(params.leftMargin + liquidIndicator.getTranslationX());
            if (liquidNavigationFlow != null) {
                ValueAnimator previousFlow = liquidNavigationFlow;
                liquidNavigationFlow = null;
                previousFlow.cancel();
            }
            params.width = lensWidth;
            params.height = lensHeight;
            params.leftMargin = startLeft;
            params.topMargin = 0;
            liquidIndicator.setPivotX(lensWidth / 2f);
            liquidIndicator.setPivotY(lensHeight / 2f);
            liquidIndicator.setScaleY(1f);
            liquidIndicator.setTranslationX(0f);
           liquidIndicator.setLayoutParams(params);
           if (liquidIndicator instanceof LiquidGlassIndicator) {
               ((LiquidGlassIndicator) liquidIndicator).setLiquidPressed(true);
           }
           if (!animate || startLeft == targetLeft) {
                liquidIndicatorLeft = targetLeft;
                params.leftMargin = targetLeft;
                liquidIndicator.setScaleX(1.16f);
                liquidIndicator.setLayoutParams(params);
                return;
            }
            ValueAnimator slide = ValueAnimator.ofFloat(0f, 1f);
            liquidNavigationFlow = slide;
            slide.setDuration(540);
            slide.setInterpolator(new android.view.animation.PathInterpolator(.16f, .88f, .22f, 1f));
            slide.addUpdateListener(animation -> {
                float progress = (Float) animation.getAnimatedValue();
                float swell = 4f * progress * (1f - progress);
                liquidIndicator.setTranslationX((targetLeft - startLeft) * progress);
                liquidIndicator.setScaleX(1.16f + .30f * swell);
                liquidIndicator.setScaleY(1f - .035f * swell);
            });
            slide.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) {
                    if (animation != liquidNavigationFlow) return;
                   liquidIndicatorLeft = targetLeft;
                   FrameLayout.LayoutParams settled = (FrameLayout.LayoutParams) liquidIndicator.getLayoutParams();
                   settled.leftMargin = targetLeft;
                   liquidIndicator.setTranslationX(0f);
                   liquidIndicator.setScaleX(1.16f);
                   liquidIndicator.setScaleY(1f);
                   liquidIndicator.setLayoutParams(settled);
                   liquidNavigationFlow = null;
               }
           });
           pulseBottomNavigation();
           slide.start();
        }

        private void expandDraggingLens() {
            if (liquidIndicator == null) return;
             liquidIndicator.animate().cancel();
             liquidIndicator.animate().scaleX(1.16f).scaleY(1f)
                     .setDuration(220)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }

        private void finishDraggingLens() {
            if (liquidIndicator == null) return;
            int settleLeft = liquidIndicatorLeft;
            if (bottomNavigationItems != null && navigationTarget >= 0) {
                View target = bottomNavigationItems.getChildAt(navigationTarget);
                int lensWidth = dp(78);
                settleLeft = bottomNavigationItems.getLeft() + target.getLeft()
                        + (target.getWidth() - lensWidth) / 2;
            }
            if (liquidNavigationFlow != null) {
                ValueAnimator previousFlow = liquidNavigationFlow;
                liquidNavigationFlow = null;
                previousFlow.cancel();
            }
            int baseWidth = dp(62);
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) liquidIndicator.getLayoutParams();
             params.width = baseWidth;
             params.height = dp(48);
             params.leftMargin = settleLeft + (dp(78) - baseWidth) / 2;
             params.topMargin = dp(8);
             liquidIndicator.setScaleX(1.16f);
            liquidIndicator.setScaleY(1f);
            liquidIndicator.setTranslationX(0f);
            liquidIndicator.setLayoutParams(params);
            liquidIndicatorLeft = settleLeft;
             if (liquidIndicator instanceof LiquidGlassIndicator) {
                 ((LiquidGlassIndicator) liquidIndicator).setLiquidPressed(false);
             }
             liquidIndicator.animate().scaleX(1f).scaleY(1f)
                     .setDuration(240)
                     .setInterpolator(new android.view.animation.OvershootInterpolator(1.05f))
                     .start();
        }

     private Drawable glassBackground(boolean selected) {
         GradientDrawable background = new GradientDrawable();
         background.setColor(selected ? 0xff243b73 : 0x66ffffff);
         background.setCornerRadius(dp(24));
         background.setStroke(dp(1), selected ? 0x66243b73 : 0x99ffffff);
         return background;
     }

      private void pressLiquidIndicator(boolean pressed) {
           if (liquidIndicator == null) return;
           liquidIndicator.animate().cancel();
          liquidIndicator.animate()
                  .scaleX(pressed ? 1.16f : 1f)
                  .scaleY(pressed ? 1.16f : 1f)
                  .setDuration(pressed ? 110 : 260)
                  .setInterpolator(pressed
                          ? new android.view.animation.DecelerateInterpolator()
                          : new android.view.animation.OvershootInterpolator(1.4f))
                  .start();
          if (liquidIndicator instanceof LiquidGlassIndicator) {
              ((LiquidGlassIndicator) liquidIndicator).setLiquidPressed(pressed);
           }
       }

     private void scrollToTop() {
         if (homeScroll != null) homeScroll.smoothScrollTo(0, 0);
     }

     // v3.30.23：内嵌设置页时，系统返回键先回 DNA 主页，而不是直接退出应用
     @Override
     public void onBackPressed() {
         if (dnaSettingsMode) {
             Haptics.perform(getWindow().getDecorView());
             dnaSettingsMode = false;
             swapTabOne();
             return;
         }
         super.onBackPressed();
     }

        private void selectTab(int tab) {
             if (tab < 0 || tab > 4 || pageHost == null) return;
             Haptics.perform(getWindow().getDecorView());
             if (tab == currentTab) {
                 if (tab == 0) scrollToTop();  // DSU页滚动到顶部
                 if (tab == 4) pageHost.getChildAt(0).scrollTo(0, 0);  // 检测页滚动到顶部
                // v3.51.67：DNA 页内嵌设置模式时，再点 DNA 标签返回 DNA 主页
                 if (tab == 1 && dnaSettingsMode) {
                     dnaSettingsMode = false;
                     swapTabOne();
                     return;
                 }
                return;
            }
            dnaSettingsMode = false;
            View next;
            if (tab == 3) {
                // OTG 页：内容可滚动 + FAB 悬浮在视口右下角（不随内容滚动）
                FrameLayout otgRoot = new FrameLayout(this);
                ScrollView pageScroll = new ScrollView(this);
                pageScroll.setFillViewport(false);  // 改为 false，允许内层 ScrollView 滚动
                pageScroll.addView(buildOtgPage());
                otgRoot.addView(pageScroll, new FrameLayout.LayoutParams(-1, -1));

                partitionFab = buildPartitionFab();
                FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
                fabLp.bottomMargin = dp(84);
                fabLp.rightMargin = dp(16);
                otgRoot.addView(partitionFab, fabLp);
                // buildOtgPage 内部首次 selectOtgTab 时 FAB 尚未加入父容器，需在加入后按当前页补一次显示状态。
                partitionFab.setVisibility(otgCurrentTab == 0 ? View.VISIBLE : View.GONE);

                next = otgRoot;
            } else {
                // v3.51.73：明确每个tab的页面映射
                switch (tab) {
                    case 0: next = homeScroll; break;
                    case 1: next = buildDnaPage(); break;
                    case 2: next = buildRomPage(); break;
                    case 4: 
                        // v3.51.80: 检测页异步加载，先显示加载中
                        next = buildCheckPageLoading(); 
                        break;
                    default: next = homeScroll; break;
                }
                // DNA和ROM页需要包装ScrollView，检测页(tab=4)已经是ScrollView不需要包装
                if (tab == 1 || tab == 2) {
                    ScrollView pageScroll = new ScrollView(this);
                    pageScroll.setFillViewport(true);
                    pageScroll.addView(next);
                    next = pageScroll;
                }
            }
          pageHost.removeAllViews();
          FrameLayout.LayoutParams nextParams = new FrameLayout.LayoutParams(-1, -1);
           pageHost.addView(next, nextParams);
          // 每个页面不同的神级炸裂动画效果
          next.setAlpha(0f);
          if (tab == 0) {
              // DSU 页：从左侧滑入 + 3D翻转
              next.setTranslationX(-dp(300));
              next.setRotationY(90f);
              next.animate()
                  .alpha(1f)
                  .translationX(0f)
                  .rotationY(0f)
                  .setDuration(450)
                  .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f))
                  .start();
          } else if (tab == 1) {
              // DNA 页：从上方落下 + Z轴旋转
              next.setTranslationY(-dp(400));
              next.setRotation(180f);
              next.setScaleX(0.3f);
              next.setScaleY(0.3f);
              next.animate()
                  .alpha(1f)
                  .translationY(0f)
                  .rotation(0f)
                  .scaleX(1f)
                  .scaleY(1f)
                  .setDuration(500)
                  .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f))
                  .start();
          } else if (tab == 2) {
              // ROM页：从中心爆炸放大 + Y轴翻转
              next.setScaleX(0.1f);
              next.setScaleY(0.1f);
              next.setRotationY(-180f);
              next.animate()
                  .alpha(1f)
                  .scaleX(1f)
                  .scaleY(1f)
                  .rotationY(0f)
                  .setDuration(480)
                  .setInterpolator(new android.view.animation.DecelerateInterpolator(2.2f))
                  .start();
          } else if (tab == 3) {
              // OTG页：从底部弹起 + 弹性缩放
              next.setTranslationY(dp(400));
              next.setScaleX(0.7f);
              next.setScaleY(0.7f);
              next.animate()
                  .alpha(1f)
                  .translationY(0f)
                  .scaleX(1f)
                  .scaleY(1f)
                  .setDuration(470)
                  .setInterpolator(new android.view.animation.OvershootInterpolator(1.5f))
                  .start();
          } else if (tab == 4) {
              // 检测页：淡入缩放
              next.setScaleX(0.98f);
              next.setScaleY(0.98f);
              next.animate().alpha(1f).scaleX(1f).scaleY(1f)
                      .setDuration(280L)
                      .setInterpolator(new android.view.animation.DecelerateInterpolator(1.3f))
                      .start();
          }
          animateNavigation(tab);
           currentTab = tab;
       }

        /** v3.51.80: 检测页加载中占位符 */
        private View buildCheckPageLoading() {
            FrameLayout loading = new FrameLayout(this);
            loading.setBackgroundResource(R.drawable.panel_bg);
            
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER);
            content.setPadding(dp(20), dp(100), dp(20), dp(100));
            
            TextView loadingText = text("正在读取设备信息...", 16, 0xff999999);
            loadingText.setGravity(Gravity.CENTER);
            content.addView(loadingText);
            
            loading.addView(content, new FrameLayout.LayoutParams(-1, -1));
            
            // 后台异步构建真实页面
            new Thread(() -> {
                View checkPage = buildCheckPage();
                runOnUiThread(() -> {
                    if (pageHost != null && pageHost.getChildCount() > 0) {
                        View current = pageHost.getChildAt(0);
                        if (current == loading) {
                            // 只有当前还是loading页面时才替换
                            pageHost.removeAllViews();
                            pageHost.addView(checkPage, new FrameLayout.LayoutParams(-1, -1));
                        }
                    }
                });
            }).start();
            
            return loading;
        }
        
        /** v3.51.69：检测页 - 统一信息展示页面，无Tab切换 */
        private View buildCheckPage() {
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(false);
            scroll.setClipToPadding(false);
            scroll.setPadding(0, dp(8), 0, dp(92));
            // 页面由 MainActivity 根容器统一绘制玻璃色域；避免滚动子页重复铺底造成错位。
            scroll.setBackgroundColor(Color.TRANSPARENT);
            
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(16), dp(12), dp(16), dp(12));
            
             // v3.51.95: 设备信息卡片（添加跳转到关于手机）
             LinearLayout deviceCard = buildWhiteCard("设备", android.R.drawable.ic_menu_info_details, android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS);
            
            // v3.51.84: 设备名称 + 品牌Logo
            LinearLayout deviceHeader = new LinearLayout(this);
            deviceHeader.setOrientation(LinearLayout.HORIZONTAL);
            deviceHeader.setGravity(Gravity.CENTER_VERTICAL);
            
            // 品牌Logo
            int brandLogoId = getBrandLogoResource();
            if (brandLogoId != 0) {
                ImageView brandLogo = new ImageView(this);
                brandLogo.setImageResource(brandLogoId);
                brandLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
                LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(60), dp(60));
                logoLp.rightMargin = dp(12);
                deviceHeader.addView(brandLogo, logoLp);
            }
            
            // 设备名称（大字标题）- v3.51.76: 使用真实市场名称
            String deviceName = getMarketName();
            TextView deviceTitle = text(deviceName, 18, 0xff16805d);
            deviceTitle.setTypeface(null, Typeface.BOLD);
            deviceHeader.addView(deviceTitle, new LinearLayout.LayoutParams(0, -2, 1));
            
            deviceCard.addView(deviceHeader);
            addVerticalSpace(deviceCard, 12);
           
           addInfoRow(deviceCard, "型号", android.os.Build.MODEL, "", 0xff333333);
           addInfoRow(deviceCard, "产品", android.os.Build.PRODUCT, "", 0xff333333);
           addInfoRow(deviceCard, "设备", android.os.Build.DEVICE, "", 0xff333333);
           addInfoRow(deviceCard, "主板", android.os.Build.BOARD, "", 0xff333333);
           addInfoRow(deviceCard, "硬件", android.os.Build.HARDWARE, "", 0xff333333);
           addInfoRow(deviceCard, "制造商", android.os.Build.MANUFACTURER, "", 0xff333333);
           
           // 基带版本
           String baseband = getBasebandVersion();
           addInfoRow(deviceCard, "基带", baseband, "", 0xff333333);
           
           content.addView(deviceCard, new LinearLayout.LayoutParams(-1, -2));
           addVerticalSpace(content, 12);
            
             // v3.51.95: 操作系统卡片（跳转到关于手机）
             LinearLayout osCard = buildWhiteCard("操作系统", android.R.drawable.ic_menu_agenda, android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS);
           addInfoRow(osCard, "Android 版本", "Android " + android.os.Build.VERSION.RELEASE, "", 0xff333333);
           String securityPatch = android.os.Build.VERSION.SECURITY_PATCH;
           addInfoRow(osCard, "安全修补程序级别", securityPatch, "", 0xff333333);
           String versionNumber = android.os.Build.DISPLAY;
           addInfoRow(osCard, "版本号", versionNumber, "", 0xff333333);
           String kernelVersion = System.getProperty("os.version", "未知");
           addInfoRow(osCard, "内核", kernelVersion, "", 0xff333333);
           String arch = System.getProperty("os.arch", "未知");
           String bits = arch.contains("64") ? "64-bit" : "32-bit";
           addInfoRow(osCard, "架构", arch + " (" + bits + ")", "", 0xff333333);
           String abi = android.os.Build.SUPPORTED_ABIS.length > 0 ? android.os.Build.SUPPORTED_ABIS[0] : "unknown";
           addInfoRow(osCard, "指令集架构", abi, "", 0xff333333);
           String activeSlot = getActiveSlot();
           addInfoRow(osCard, "活动插槽", activeSlot, "", 0xff333333);
            content.addView(osCard, new LinearLayout.LayoutParams(-1, -2));
            addVerticalSpace(content, 12);
            
             // v3.51.96: 指纹信息卡片（跳转到指纹解锁设置）
             LinearLayout fingerprintCard = buildWhiteCard("指纹", android.R.drawable.ic_menu_info_details, "android.settings.FINGERPRINT_ENROLL");
            String fingerprint = android.os.Build.FINGERPRINT;
            addInfoRow(fingerprintCard, "完整指纹", fingerprint, "", 0xff333333);
            content.addView(fingerprintCard, new LinearLayout.LayoutParams(-1, -2));
            addVerticalSpace(content, 12);
             
             // v3.51.96: 更新信息卡片（跳转到关于手机，里面有系统更新选项）
             LinearLayout updateCard = buildWhiteCard("更新", android.R.drawable.ic_menu_rotate, android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS);
            addInfoRow(updateCard, "当前版本", "Android " + android.os.Build.VERSION.RELEASE, "", 0xff333333);
            addInfoRow(updateCard, "首批发行", getFirstApiLevel(), "", 0xff333333);
            
            boolean treble = isTrebleSupported();
            addInfoRow(updateCard, "Project Treble", treble ? "支持" : "不支持", "", treble ? 0xff16805d : 0xff999999);
            
            addInfoRow(updateCard, "Project Mainline", android.os.Build.VERSION.SDK_INT >= 29 ? "支持" : "不支持", "", 
                android.os.Build.VERSION.SDK_INT >= 29 ? 0xff16805d : 0xff999999);
            
            boolean dynamicPartitions = isDynamicPartitionsSupported();
            addInfoRow(updateCard, "动态分区", dynamicPartitions ? "支持" : "不支持", "", dynamicPartitions ? 0xff16805d : 0xff999999);
            
            boolean seamlessUpdate = isSeamlessUpdateSupported();
            addInfoRow(updateCard, "无缝更新", seamlessUpdate ? "支持" : "不支持", "", seamlessUpdate ? 0xff16805d : 0xff999999);
            
            String activeSlot2 = getActiveSlot();
            addInfoRow(updateCard, "活动插槽", activeSlot2, "", 0xff333333);
            
            content.addView(updateCard, new LinearLayout.LayoutParams(-1, -2));
            addVerticalSpace(content, 12);
             
             // v3.51.95: 安全卡片（跳转到安全设置）
             LinearLayout securityCard = buildWhiteCard("安全", android.R.drawable.ic_lock_lock, android.provider.Settings.ACTION_SECURITY_SETTINGS);
            addInfoRow(securityCard, "Root权限", rootAuthorized ? "设备已获得Root权限" : "未检测到Root", "", rootAuthorized ? 0xffF39C12 : 0xff16805d);
            
            // v3.51.78: Root方法异步检测，避免卡顿
            if (rootAuthorized) {
                if (cachedRootMethod != null) {
                    // 已有缓存，直接显示
                    addInfoRow(securityCard, "Root方法", cachedRootMethod, "", 0xff333333);
                } else {
                    // 先显示"检测中"，后台异步检测
                    LinearLayout rootMethodRow = new LinearLayout(this);
                    rootMethodRow.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
                    rowLp.topMargin = dp(8);
                    
                    TextView labelView = text("Root方法", 13, 0xff757575);
                    labelView.setTypeface(null, Typeface.BOLD);
                    labelView.setMaxLines(2);
                    LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(dp(100), -2);
                    labelLp.rightMargin = dp(8);
                    rootMethodRow.addView(labelView, labelLp);
                    
                    TextView valueView = text("检测中...", 13, 0xff999999);
                    valueView.setGravity(Gravity.END);
                    LinearLayout valueBox = new LinearLayout(this);
                    valueBox.setOrientation(LinearLayout.VERTICAL);
                    valueBox.setGravity(Gravity.END);
                    valueBox.addView(valueView, new LinearLayout.LayoutParams(-1, -2));
                    rootMethodRow.addView(valueBox, new LinearLayout.LayoutParams(0, -2, 1));
                    
                    securityCard.addView(rootMethodRow, rowLp);
                    
                    // 后台异步检测
                    new Thread(() -> {
                        String method = detectRootMethod();
                        runOnUiThread(() -> {
                            valueView.setText(method);
                            valueView.setTextColor(0xff333333);
                        });
                    }).start();
                }
            }
            
            String selinux = getSELinuxStatus();
           addInfoRow(securityCard, "SELinux", selinux, "", selinux.contains("Enforcing") ? 0xff16805d : 0xffF39C12);
           String avbStatus = getAVBStatus();
           addInfoRow(securityCard, "Verified Boot (AVB)", avbStatus, "", avbStatus.contains("绿色") || avbStatus.contains("Green") ? 0xff16805d : 0xffF39C12);
           String bootloaderLocked = getBootloaderLockStatus();
           addInfoRow(securityCard, "Bootloader锁定", bootloaderLocked, "", bootloaderLocked.contains("已锁定") ? 0xff16805d : 0xffF39C12);
           String bootloader = android.os.Build.BOOTLOADER;
           addInfoRow(securityCard, "Bootloader版本", bootloader.isEmpty() ? "未知" : bootloader, "", 0xff333333);
            content.addView(securityCard, new LinearLayout.LayoutParams(-1, -2));
            addVerticalSpace(content, 12);
            
             // v3.51.95: 处理器卡片（没有直接对应的系统设置，使用关于手机）
             LinearLayout cpuCard = buildWhiteCard("处理器", android.R.drawable.ic_menu_manage, android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS);
            
            // CPU名称和Logo区域
            LinearLayout cpuHeader = new LinearLayout(this);
            cpuHeader.setOrientation(LinearLayout.HORIZONTAL);
            cpuHeader.setGravity(Gravity.CENTER_VERTICAL);
            
            // v3.51.81: 使用真实品牌Logo
            int logoResId = getCpuLogoResource();
            if (logoResId != 0) {
                ImageView logoView = new ImageView(this);
                logoView.setImageResource(logoResId);
                logoView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(80), dp(80));
                logoLp.rightMargin = dp(16);
                cpuHeader.addView(logoView, logoLp);
            } else {
                // 备用：灰色占位块
                View logoPlaceholder = new View(this);
                GradientDrawable logoBg = new GradientDrawable();
                logoBg.setColor(0xFF333333);
                logoBg.setCornerRadius(dp(8));
                logoPlaceholder.setBackground(logoBg);
                LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(80), dp(80));
                logoLp.rightMargin = dp(16);
                cpuHeader.addView(logoPlaceholder, logoLp);
            }
            
            // v3.51.81: 使用SoC完整名称
            String cpuName = getSocFullName();
            TextView cpuNameView = text(cpuName, 20, 0xff16805d);
            cpuNameView.setTypeface(null, Typeface.BOLD);
            cpuHeader.addView(cpuNameView, new LinearLayout.LayoutParams(0, -2, 1));
            
            cpuCard.addView(cpuHeader);
            addVerticalSpace(cpuCard, 12);
            
            // 三个胶囊标签
            LinearLayout chipRow = new LinearLayout(this);
            chipRow.setOrientation(LinearLayout.HORIZONTAL);
            
            // v3.51.81: 使用真实制程工艺
            String fab = getSocFab();
            chipRow.addView(buildChip(fab));
            
            // 核心数
            int cores = Runtime.getRuntime().availableProcessors();
            chipRow.addView(buildChip(cores + " 核心"));
            
            // 位宽
            String cpuBits = System.getProperty("os.arch", "").contains("64") ? "64-bit" : "32-bit";
            chipRow.addView(buildChip(cpuBits));
            
            cpuCard.addView(chipRow);
            addVerticalSpace(cpuCard, 12);
            
            // CPU配置区域（灰色背景）
            LinearLayout cpuConfigBg = new LinearLayout(this);
            cpuConfigBg.setOrientation(LinearLayout.VERTICAL);
            cpuConfigBg.setPadding(dp(12), dp(10), dp(12), dp(10));
            cpuConfigBg.setBackgroundColor(0xFFF5F5F5);
            GradientDrawable configBg = new GradientDrawable();
            configBg.setColor(0xFFF5F5F5);
            configBg.setCornerRadius(dp(8));
            cpuConfigBg.setBackground(configBg);
            
            TextView cpuConfigTitle = text("CPU配置", 13, 0xff999999);
            cpuConfigBg.addView(cpuConfigTitle);
            addVerticalSpace(cpuConfigBg, 6);
            
            // CPU核心配置（简化版，因为无法读取实际频率）
            addCpuCore(cpuConfigBg, 0xff16805d, "性能核心 ×" + (cores > 4 ? cores-2 : cores/2), "高频");
            addVerticalSpace(cpuConfigBg, 4);
            addCpuCore(cpuConfigBg, 0xff4C8EF5, "效率核心 ×" + (cores > 4 ? 2 : cores/2), "低频");
            
            cpuCard.addView(cpuConfigBg, new LinearLayout.LayoutParams(-1, -2));
            addVerticalSpace(cpuCard, 12);
            
            // 左右两列信息
            LinearLayout twoColRow1 = new LinearLayout(this);
            twoColRow1.setOrientation(LinearLayout.HORIZONTAL);
            
            LinearLayout leftCol1 = new LinearLayout(this);
            leftCol1.setOrientation(LinearLayout.VERTICAL);
            TextView label1 = text("供应商", 13, 0xff999999);
            // v3.51.81: 使用SoC数据库的供应商信息
            TextView value1 = text(getSocVendor(), 15, 0xff333333);
            value1.setTypeface(null, Typeface.BOLD);
            leftCol1.addView(label1);
            leftCol1.addView(value1);
            
            LinearLayout rightCol1 = new LinearLayout(this);
            rightCol1.setOrientation(LinearLayout.VERTICAL);
            rightCol1.setGravity(Gravity.END);
            TextView label2 = text("硬件", 13, 0xff999999);
            label2.setGravity(Gravity.END);
            TextView value2 = text(android.os.Build.HARDWARE, 15, 0xff333333);
            value2.setTypeface(null, Typeface.BOLD);
            value2.setGravity(Gravity.END);
            rightCol1.addView(label2);
            rightCol1.addView(value2);
            
            twoColRow1.addView(leftCol1, new LinearLayout.LayoutParams(0, -2, 1));
            twoColRow1.addView(rightCol1, new LinearLayout.LayoutParams(0, -2, 1));
            cpuCard.addView(twoColRow1);
            addVerticalSpace(cpuCard, 12);
            
            // 第二行
            LinearLayout twoColRow2 = new LinearLayout(this);
            twoColRow2.setOrientation(LinearLayout.HORIZONTAL);
            
            LinearLayout leftCol2 = new LinearLayout(this);
            leftCol2.setOrientation(LinearLayout.VERTICAL);
            TextView label3 = text("架构", 13, 0xff999999);
            TextView value3 = text(System.getProperty("os.arch", "未知"), 15, 0xff333333);
            value3.setTypeface(null, Typeface.BOLD);
            leftCol2.addView(label3);
            leftCol2.addView(value3);
            
            LinearLayout rightCol2 = new LinearLayout(this);
            rightCol2.setOrientation(LinearLayout.VERTICAL);
            rightCol2.setGravity(Gravity.END);
            TextView label4 = text("ABI", 13, 0xff999999);
            label4.setGravity(Gravity.END);
            String cpuAbi = android.os.Build.SUPPORTED_ABIS.length > 0 ? android.os.Build.SUPPORTED_ABIS[0] : "unknown";
            TextView value4 = text(cpuAbi + " (" + cpuBits + ")", 15, 0xff333333);
            value4.setTypeface(null, Typeface.BOLD);
            value4.setGravity(Gravity.END);
            rightCol2.addView(label4);
            rightCol2.addView(value4);
            
            twoColRow2.addView(leftCol2, new LinearLayout.LayoutParams(0, -2, 1));
            twoColRow2.addView(rightCol2, new LinearLayout.LayoutParams(0, -2, 1));
            cpuCard.addView(twoColRow2);
            addVerticalSpace(cpuCard, 12);
            
            // 第三行
            LinearLayout twoColRow3 = new LinearLayout(this);
            twoColRow3.setOrientation(LinearLayout.HORIZONTAL);
            
            LinearLayout leftCol3 = new LinearLayout(this);
            leftCol3.setOrientation(LinearLayout.VERTICAL);
            TextView label5 = text("支持的ABIs", 13, 0xff999999);
            String[] allAbis = android.os.Build.SUPPORTED_ABIS;
            String abisText = allAbis.length > 0 ? allAbis[0] : "unknown";
            TextView value5 = text(abisText, 15, 0xff333333);
            value5.setTypeface(null, Typeface.BOLD);
            leftCol3.addView(label5);
            leftCol3.addView(value5);
            
            LinearLayout rightCol3 = new LinearLayout(this);
            rightCol3.setOrientation(LinearLayout.VERTICAL);
            rightCol3.setGravity(Gravity.END);
            TextView label6 = text("调频器", 13, 0xff999999);
            label6.setGravity(Gravity.END);
            TextView value6 = text("walt", 15, 0xff333333);
            value6.setTypeface(null, Typeface.BOLD);
            value6.setGravity(Gravity.END);
            rightCol3.addView(label6);
            rightCol3.addView(value6);
            
            twoColRow3.addView(leftCol3, new LinearLayout.LayoutParams(0, -2, 1));
            twoColRow3.addView(rightCol3, new LinearLayout.LayoutParams(0, -2, 1));
            cpuCard.addView(twoColRow3);
            
            content.addView(cpuCard, new LinearLayout.LayoutParams(-1, -2));
            addVerticalSpace(content, 12);
            
            // 内存卡片
            // v3.51.96: 内存卡片（跳转到应用管理，查看内存使用）
            LinearLayout memCard = buildWhiteCard("内存", android.R.drawable.ic_menu_sort_by_size, android.provider.Settings.ACTION_APPLICATION_SETTINGS);
           try {
               android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
               android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
               am.getMemoryInfo(mi);
               long total = mi.totalMem;
               long avail = mi.availMem;
               long used = total - avail;
               
               addInfoRow(memCard, "大小", formatBytes(total), "", 0xff333333);
               addVerticalSpace(memCard, 8);
               
               LinearLayout progressBar = buildProgressBar(used, total, 0xff16805d);
               memCard.addView(progressBar, new LinearLayout.LayoutParams(-1, dp(16)));
               
               addVerticalSpace(memCard, 6);
               
               LinearLayout legend = new LinearLayout(this);
               legend.setOrientation(LinearLayout.HORIZONTAL);
               legend.setGravity(Gravity.CENTER_VERTICAL);
               
               View usedDot = new View(this);
               GradientDrawable usedDrawable = new GradientDrawable();
               usedDrawable.setShape(GradientDrawable.OVAL);
               usedDrawable.setColor(0xff16805d);
               usedDot.setBackground(usedDrawable);
               legend.addView(usedDot, new LinearLayout.LayoutParams(dp(8), dp(8)));
               
               TextView usedText = text(" 已用 " + formatBytes(used), 11, 0xff757575);
               legend.addView(usedText);
               
               View availDot = new View(this);
               GradientDrawable availDrawable = new GradientDrawable();
               availDrawable.setShape(GradientDrawable.OVAL);
               availDrawable.setColor(0xFFE0E0E0);
               availDot.setBackground(availDrawable);
               LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
               dotLp.leftMargin = dp(16);
               legend.addView(availDot, dotLp);
               
               TextView availText = text(" 可用 " + formatBytes(avail), 11, 0xff757575);
               legend.addView(availText);
               
               memCard.addView(legend);
           } catch (Exception e) {
               addInfoRow(memCard, "读取失败", e.getMessage(), "", 0xffF39C12);
           }
           content.addView(memCard, new LinearLayout.LayoutParams(-1, -2));
           addVerticalSpace(content, 12);
           
            // 存储卡片
            // v3.51.95: 存储卡片（跳转到存储设置）
            LinearLayout storageCard = buildWhiteCard("存储", android.R.drawable.ic_menu_save, android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS);
           try {
               java.io.File dataDir = android.os.Environment.getDataDirectory();
               android.os.StatFs dataStat = new android.os.StatFs(dataDir.getPath());
               long dataTotal = dataStat.getTotalBytes();
               long dataAvail = dataStat.getAvailableBytes();
               long dataUsed = dataTotal - dataAvail;
               
               addInfoRow(storageCard, "内部存储", formatBytes(dataTotal), "", 0xff333333);
               addVerticalSpace(storageCard, 8);
               
               LinearLayout dataBar = buildProgressBar(dataUsed, dataTotal, 0xff4C8EF5);
               storageCard.addView(dataBar, new LinearLayout.LayoutParams(-1, dp(16)));
               
               addVerticalSpace(storageCard, 6);
               
               TextView dataInfo = text("已用 " + formatBytes(dataUsed) + " / 可用 " + formatBytes(dataAvail), 11, 0xff757575);
               storageCard.addView(dataInfo);
           } catch (Exception e) {
               addInfoRow(storageCard, "读取失败", e.getMessage(), "", 0xffF39C12);
           }
           content.addView(storageCard, new LinearLayout.LayoutParams(-1, -2));
           addVerticalSpace(content, 12);
           
            // 屏幕卡片
            // v3.51.95: 屏幕卡片（跳转到显示设置）
            LinearLayout screenCard = buildWhiteCard("屏幕", android.R.drawable.ic_menu_view, android.provider.Settings.ACTION_DISPLAY_SETTINGS);
           populateScreenInfo(screenCard);
           content.addView(screenCard, new LinearLayout.LayoutParams(-1, -2));
           addVerticalSpace(content, 12);
           
            // 运行时卡片
            // v3.51.95: 运行时卡片（跳转到应用管理）
            LinearLayout runtimeCard = buildWhiteCard("运行时", android.R.drawable.ic_menu_preferences, android.provider.Settings.ACTION_APPLICATION_SETTINGS);
           addInfoRow(runtimeCard, "虚拟机", "ART", "", 0xff333333);
           addInfoRow(runtimeCard, "ABI", android.os.Build.SUPPORTED_ABIS.length > 0 ? android.os.Build.SUPPORTED_ABIS[0] : "unknown", "", 0xff333333);
           boolean debuggable = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
           addInfoRow(runtimeCard, "调试", debuggable ? "可调试" : "已禁用", "", debuggable ? 0xffF39C12 : 0xff16805d);
           content.addView(runtimeCard, new LinearLayout.LayoutParams(-1, -2));
           addVerticalSpace(content, 12);
           
            // DRM卡片
            // v3.51.95: DRM卡片（跳转到关于手机）
            LinearLayout drmCard = buildWhiteCard("DRM", android.R.drawable.ic_menu_slideshow, android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS);
           addInfoRow(drmCard, "Widevine", getWidevineLevel(), "", 0xff16805d);
           addInfoRow(drmCard, "供应商", "Google", "", 0xff333333);
           addInfoRow(drmCard, "版本", getWidevineVersion(), "", 0xff333333);
           content.addView(drmCard, new LinearLayout.LayoutParams(-1, -2));
           
           scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
           return scroll;
       }
        
        // 检测页 Tab 手势处理（完全复制 OTG Tab 实现）
        
        
        // 辅助方法：构建白色卡片
        private LinearLayout buildWhiteCard(String title) {
            return buildWhiteCard(title, 0, null);
        }
        
        // v3.51.78: 带图标的卡片
        private LinearLayout buildWhiteCard(String title, int iconResId) {
            return buildWhiteCard(title, iconResId, null);
        }
        
        // v3.51.95: 带图标和设置按钮的卡片
        private LinearLayout buildWhiteCard(String title, int iconResId, String settingsAction) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(16), dp(14), dp(16), dp(14));
            // v3.51.92: 恢复白色卡片背景
            card.setBackgroundResource(R.drawable.white_card_bg);
            
            // 标题行（图标 + 文字 + 设置按钮）
            LinearLayout titleRow = new LinearLayout(this);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            
            if (iconResId != 0) {
                ImageView icon = new ImageView(this);
                icon.setImageResource(iconResId);
                // v3.51.91: 标题图标改为深蓝色
                icon.setColorFilter(0xff2C5F7C);
                LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(20), dp(20));
                iconLp.rightMargin = dp(8);
                titleRow.addView(icon, iconLp);
            }
            
            // v3.51.91: 标题颜色改为深蓝色
            TextView titleView = text(title, 16, 0xff2C5F7C);
            titleView.setTypeface(null, Typeface.BOLD);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1);
            titleRow.addView(titleView, titleLp);
            
            // v3.51.95: 添加设置图标按钮
            if (settingsAction != null) {
                ImageView settingsIcon = new ImageView(this);
                settingsIcon.setImageResource(android.R.drawable.ic_menu_preferences);
                settingsIcon.setColorFilter(0xff2C5F7C);
                settingsIcon.setPadding(dp(8), dp(8), dp(8), dp(8));
                LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(dp(36), dp(36));
                settingsIcon.setOnClickListener(v -> openSystemSettings(settingsAction));
                titleRow.addView(settingsIcon, settingsLp);
            }
            
            card.addView(titleRow);
            addVerticalSpace(card, 10);
            
            return card;
        }
        
        // v3.51.95: 打开系统设置页面
        private void openSystemSettings(String action) {
            try {
                Intent intent = new Intent(action);
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, "无法打开系统设置", Toast.LENGTH_SHORT).show();
            }
        }
        
        // 辅助方法：添加信息行
        private void addInfoRow(LinearLayout parent, String label, String value, String extra, int valueColor) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
            rowLp.topMargin = dp(8);
            
            // v3.51.91: 标签改为深灰蓝色
            TextView labelView = text(label, 13, 0xff5A7A8C);
            labelView.setTypeface(null, Typeface.BOLD);
            labelView.setMaxLines(2);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(dp(100), -2);
            labelLp.rightMargin = dp(8);
            row.addView(labelView, labelLp);
            
            // v3.51.76: 内容区域占据剩余空间，自动换行
            LinearLayout valueBox = new LinearLayout(this);
            valueBox.setOrientation(LinearLayout.VERTICAL);
            valueBox.setGravity(Gravity.END);
            
            // v3.51.91: 如果valueColor是默认灰色，改为深色
            int finalValueColor = (valueColor == 0xff333333) ? 0xff1A3A4A : valueColor;
            TextView valueView = text(value, 13, finalValueColor);
            valueView.setGravity(Gravity.END | Gravity.TOP);
            
            // v3.51.94: 只给value文字添加长按复制
            valueView.setOnLongClickListener(v -> {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                android.content.ClipData clip = android.content.ClipData.newPlainText(label, value);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "已复制: " + value, Toast.LENGTH_SHORT).show();
                return true;
            });
            
            // 使用weight=0，MATCH_PARENT填充valueBox宽度
            valueBox.addView(valueView, new LinearLayout.LayoutParams(-1, -2));
            
            if (!extra.isEmpty()) {
                // v3.51.91: 额外信息改为中灰色
                TextView extraView = text(extra, 11, 0xff6A8A9C);
                extraView.setGravity(Gravity.END);
                
                // v3.51.94: 额外信息也可以长按复制
                extraView.setOnLongClickListener(v -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    android.content.ClipData clip = android.content.ClipData.newPlainText(label, extra);
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(this, "已复制: " + extra, Toast.LENGTH_SHORT).show();
                    return true;
                });
                
                LinearLayout.LayoutParams extraLp = new LinearLayout.LayoutParams(-1, -2);
                extraLp.topMargin = dp(2);
                valueBox.addView(extraView, extraLp);
            }
            
            // valueBox占据剩余空间
            row.addView(valueBox, new LinearLayout.LayoutParams(0, -2, 1));
            parent.addView(row, rowLp);
        }
        
        // 辅助方法：构建芯片标签
        private View buildChip(String text) {
            TextView chip = new TextView(this);
            chip.setText(text);
            chip.setTextSize(11);
            chip.setTextColor(0xff16805d);
            chip.setTypeface(null, Typeface.BOLD);
            chip.setPadding(dp(10), dp(5), dp(10), dp(5));
            chip.setGravity(Gravity.CENTER);
            
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFFE8F5E9);
            bg.setCornerRadius(dp(12));
            chip.setBackground(bg);
            
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(8);
            chip.setLayoutParams(lp);
            
            return chip;
        }
        
        private void addCpuCore(LinearLayout parent, int color, String name, String freq) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            
            View colorBox = new View(this);
            GradientDrawable boxBg = new GradientDrawable();
            boxBg.setColor(color);
            boxBg.setCornerRadius(dp(2));
            colorBox.setBackground(boxBg);
            row.addView(colorBox, new LinearLayout.LayoutParams(dp(12), dp(12)));
            
            TextView nameView = text(name, 13, 0xff333333);
            nameView.setTypeface(null, Typeface.BOLD);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, -2, 1f);
            nameLp.leftMargin = dp(10);
            row.addView(nameView, nameLp);
            
            TextView freqView = text(freq, 13, 0xff757575);
            freqView.setGravity(Gravity.END);
            row.addView(freqView, new LinearLayout.LayoutParams(-2, -2));
            
            parent.addView(row);
        }
        private void addVerticalSpace(LinearLayout parent, int dp) {
            View space = new View(this);
            parent.addView(space, new LinearLayout.LayoutParams(-1, dp(dp)));
        }
        
        private LinearLayout buildCpuCard() {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(16), dp(14), dp(16), dp(14));
            card.setBackgroundResource(R.drawable.liquid_glass_panel);
            
            // 标题
            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            TextView title = text("CPU", 16, 0xff16805d);
            title.setTypeface(null, Typeface.BOLD);
            header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
            card.addView(header);
            
            addVerticalSpace(card, 10);
            
            // 架构 + 核心数
            String abi = android.os.Build.SUPPORTED_ABIS.length > 0 ? android.os.Build.SUPPORTED_ABIS[0] : "unknown";
            int cores = Runtime.getRuntime().availableProcessors();
            
            LinearLayout row1 = new LinearLayout(this);
            row1.setOrientation(LinearLayout.HORIZONTAL);
            addInfoPair(row1, "架构", abi);
            addInfoPair(row1, "核心", cores + " 核");
            card.addView(row1);
            
            addVerticalSpace(card, 8);
            
            // 硬件信息
            LinearLayout row2 = new LinearLayout(this);
            row2.setOrientation(LinearLayout.HORIZONTAL);
            addInfoPair(row2, "硬件", android.os.Build.HARDWARE);
            addInfoPair(row2, "型号", android.os.Build.MODEL);
            card.addView(row2);
            
            return card;
        }
        
        private LinearLayout buildMemoryCard() {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(16), dp(14), dp(16), dp(14));
            card.setBackgroundResource(R.drawable.liquid_glass_panel);
            
            TextView title = text("内存", 16, 0xff16805d);
            title.setTypeface(null, Typeface.BOLD);
            card.addView(title);
            
            try {
                android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                
                long total = mi.totalMem;
                long avail = mi.availMem;
                long used = total - avail;
                
                addVerticalSpace(card, 10);
                
                // 总大小
                TextView sizeText = text("大小: " + formatBytes(total), 14, 0xffffffff);
                card.addView(sizeText);
                
                addVerticalSpace(card, 8);
                
                // 进度条
                LinearLayout progressBar = buildProgressBar(used, total, 0xff16805d);
                card.addView(progressBar, new LinearLayout.LayoutParams(-1, dp(20)));
                
                addVerticalSpace(card, 6);
                
                // 已用/空闲
                LinearLayout legend = new LinearLayout(this);
                legend.setOrientation(LinearLayout.HORIZONTAL);
                TextView usedText = text("已用 " + formatBytes(used), 12, 0xccffffff);
                legend.addView(usedText, new LinearLayout.LayoutParams(0, -2, 1f));
                TextView availText = text("空闲 " + formatBytes(avail), 12, 0xccffffff);
                availText.setGravity(Gravity.END);
                legend.addView(availText, new LinearLayout.LayoutParams(0, -2, 1f));
                card.addView(legend);
                
            } catch (Throwable ignored) {}
            
            return card;
        }
        
        private LinearLayout buildStorageCard() {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(16), dp(14), dp(16), dp(14));
            card.setBackgroundResource(R.drawable.liquid_glass_panel);
            
            TextView title = text("存储", 16, 0xff16805d);
            title.setTypeface(null, Typeface.BOLD);
            card.addView(title);
            
            try {
                java.io.File path = android.os.Environment.getDataDirectory();
                android.os.StatFs stat = new android.os.StatFs(path.getPath());
                long blockSize = stat.getBlockSizeLong();
                long totalBlocks = stat.getBlockCountLong();
                long availBlocks = stat.getAvailableBlocksLong();
                long total = totalBlocks * blockSize;
                long avail = availBlocks * blockSize;
                long used = total - avail;
                
                addVerticalSpace(card, 10);
                
                TextView sizeText = text("大小: " + formatBytes(total), 14, 0xffffffff);
                card.addView(sizeText);
                
                addVerticalSpace(card, 8);
                
                // 进度条
                LinearLayout progressBar = buildProgressBar(used, total, 0xff4c8ef5);
                card.addView(progressBar, new LinearLayout.LayoutParams(-1, dp(20)));
                
                addVerticalSpace(card, 6);
                
                LinearLayout legend = new LinearLayout(this);
                legend.setOrientation(LinearLayout.HORIZONTAL);
                TextView usedText = text("已用 " + formatBytes(used), 12, 0xccffffff);
                legend.addView(usedText, new LinearLayout.LayoutParams(0, -2, 1f));
                TextView availText = text("空闲 " + formatBytes(avail), 12, 0xccffffff);
                availText.setGravity(Gravity.END);
                legend.addView(availText, new LinearLayout.LayoutParams(0, -2, 1f));
                card.addView(legend);
                
            } catch (Throwable ignored) {}
            
            return card;
        }
        
        private LinearLayout buildScreenCard() {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(16), dp(14), dp(16), dp(14));
            card.setBackgroundResource(R.drawable.liquid_glass_panel);
            
            TextView title = text("屏幕", 16, 0xff16805d);
            title.setTypeface(null, Typeface.BOLD);
            card.addView(title);
            
            addVerticalSpace(card, 10);
            
            try {
                android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                
                LinearLayout row1 = new LinearLayout(this);
                row1.setOrientation(LinearLayout.HORIZONTAL);
                addInfoPair(row1, "分辨率", dm.widthPixels + " × " + dm.heightPixels);
                addInfoPair(row1, "密度", dm.densityDpi + " dpi");
                card.addView(row1);
                
            } catch (Throwable ignored) {}
            
            return card;
        }
        
        private LinearLayout buildBatteryCard() {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(16), dp(14), dp(16), dp(14));
            card.setBackgroundResource(R.drawable.liquid_glass_panel);
            
            TextView title = text("电池", 16, 0xff16805d);
            title.setTypeface(null, Typeface.BOLD);
            card.addView(title);
            
            addVerticalSpace(card, 10);
            
            try {
                android.content.IntentFilter filter = new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED);
                android.content.Intent batteryStatus = registerReceiver(null, filter);
                if (batteryStatus != null) {
                    int level = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                    int scale = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
                    float pct = level * 100 / (float) scale;
                    
                    int status = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
                    String statusStr = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ? "充电中" : "放电中";
                    
                    LinearLayout row1 = new LinearLayout(this);
                    row1.setOrientation(LinearLayout.HORIZONTAL);
                    addInfoPair(row1, "电量", String.format(java.util.Locale.US, "%.0f%%", pct));
                    addInfoPair(row1, "状态", statusStr);
                    card.addView(row1);
                }
            } catch (Throwable ignored) {}
            
            return card;
        }
        
        private void addInfoPair(LinearLayout parent, String label, String value) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            
            TextView labelView = text(label, 12, 0xaaffffff);
            box.addView(labelView);
            
            TextView valueView = text(value, 14, 0xffffffff);
            valueView.setTypeface(null, Typeface.BOLD);
            LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(-1, -2);
            valueLp.topMargin = dp(4);
            box.addView(valueView, valueLp);
            
            parent.addView(box);
        }
        
        private LinearLayout buildProgressBar(long used, long total, int color) {
            LinearLayout bar = new LinearLayout(this);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            
            float usedRatio = used / (float) total;
            
            // 已用部分
            View usedPart = new View(this);
            GradientDrawable usedBg = new GradientDrawable();
            usedBg.setColor(color);
            usedBg.setCornerRadius(dp(10));
            usedPart.setBackground(usedBg);
            bar.addView(usedPart, new LinearLayout.LayoutParams(0, -1, usedRatio));
            
            // 空闲部分
            View freePart = new View(this);
            GradientDrawable freeBg = new GradientDrawable();
            freeBg.setColor(0x22ffffff);
            freeBg.setCornerRadius(dp(10));
            freePart.setBackground(freeBg);
            LinearLayout.LayoutParams freeLp = new LinearLayout.LayoutParams(0, -1, 1f - usedRatio);
            freeLp.leftMargin = dp(4);
            bar.addView(freePart, freeLp);
            
            return bar;
        }
         private void refreshMorePage() {
          if (pageHost == null) return;
          pageHost.removeAllViews();
          ScrollView pageScroll = new ScrollView(this);
          pageScroll.setFillViewport(true);
          pageScroll.addView(buildMorePage());
          pageHost.addView(pageScroll, new FrameLayout.LayoutParams(-1, -1));
      }

      // v3.10.0：DNA 标签页内部切换（DNA 主页 ⇄ 内嵌设置页），复用 tab 1 的落下动画
      private void swapTabOne() {
          if (pageHost == null) return;
          View next = dnaSettingsMode ? buildSettingsPage() : buildDnaPage();
          ScrollView pageScroll = new ScrollView(this);
          pageScroll.setFillViewport(true);
          pageScroll.addView(next);
          pageHost.removeAllViews();
          pageHost.addView(pageScroll, new FrameLayout.LayoutParams(-1, -1));
          next.setAlpha(0f);
          next.setTranslationY(-dp(300));
          next.animate()
              .alpha(1f)
              .translationY(0f)
              .setDuration(380)
              .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
              .start();
      }

      /** v3.28.1 DNA 工具箱主页（对齐原版 DNA 工具布局）：
       *  状态卡（工具链检测）+ 工程管理（切换 / 新建 / 删除 / 解压ROM）
       *  + 分解与提取（bin / br / dat / img / super）+ 合成与打包 + 格式转换 */
      private LinearLayout buildDnaPage() {
          LinearLayout page = new LinearLayout(this);
          page.setOrientation(LinearLayout.VERTICAL);
          page.setPadding(dp(18), dp(22), dp(18), dp(30));
          // 标题行：DNA 工具箱（左）+ 设置入口（右）——与 ROM 页「下载管理」同款蓝绿渐变胶囊
          LinearLayout titleRow = new LinearLayout(this);
          titleRow.setOrientation(LinearLayout.HORIZONTAL);
          titleRow.setGravity(Gravity.CENTER_VERTICAL);
          TextView title = text(t("DNA 工具箱", "DNA Toolbox"), 26, Color.WHITE);
          title.setTypeface(null, 1);
          titleRow.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
          Button settingsEntry = new Button(this, null, 0);
          settingsEntry.setText(t("⚙ 设置", "⚙ Settings"));
          settingsEntry.setAllCaps(false);
          settingsEntry.setTextSize(13.5f);
          settingsEntry.setTypeface(null, 1);
          settingsEntry.setTextColor(Color.WHITE);
          settingsEntry.setGravity(Gravity.CENTER);
          settingsEntry.setPadding(dp(18), 0, dp(18), 0);
          settingsEntry.setMinWidth(0);
          settingsEntry.setMinHeight(0);
          settingsEntry.setIncludeFontPadding(false);
          settingsEntry.setStateListAnimator(null);
          GradientDrawable settingsBg = new GradientDrawable();
          settingsBg.setOrientation(GradientDrawable.Orientation.TL_BR);
          settingsBg.setColors(new int[]{0xFF35A8C4, 0xFF0E7D95, 0xFF0A5F75});
          settingsBg.setCornerRadius(dp(22));
          settingsBg.setStroke(Math.max(1, dp(2)), 0xE6FFFFFF);
          settingsEntry.setBackground(settingsBg);
          settingsEntry.setElevation(dp(6));
          settingsEntry.setOnClickListener(v -> {
              Haptics.perform(v);
              dnaSettingsMode = true;
              swapTabOne();
          });
          titleRow.addView(settingsEntry, new LinearLayout.LayoutParams(-2, dp(44)));
          page.addView(titleRow, new LinearLayout.LayoutParams(-1, dp(48)));

          // v3.40.10：置顶提示卡（DNA 打包功能移植来源 + 测试范围声明）
          LinearLayout pinnedCard = new LinearLayout(this);
          pinnedCard.setOrientation(LinearLayout.VERTICAL);
          pinnedCard.setPadding(dp(14), dp(11), dp(14), dp(11));
          GradientDrawable pinnedBg = new GradientDrawable();
          pinnedBg.setOrientation(GradientDrawable.Orientation.TL_BR);
          pinnedBg.setColors(new int[]{0xFFFFD98A, 0xFFF2B04E});
          pinnedBg.setCornerRadius(dp(18));
          pinnedBg.setStroke(Math.max(1, dp(1)), 0x80FFFFFF);
          pinnedCard.setBackground(pinnedBg);
          pinnedCard.setElevation(dp(4));
          TextView pinnedTitle = text("📌 " + t("置顶提示", "Pinned"), 14, 0xff6b3d00);
          pinnedTitle.setTypeface(null, 1);
          pinnedTitle.setPadding(0, 0, 0, dp(5));
          pinnedCard.addView(pinnedTitle, new LinearLayout.LayoutParams(-1, -2));
          String[] pinnedLines = {
                  t("DNA 打包功能完全移植酷安用户「相见即是缘」的 20260530 版本 DNA",
                          "DNA packaging is fully ported from Coolapk user XiangJianJiShiYuan's DNA (20260530)"),
                  t("有什么没修复的 bug 可联系作者；没有 fb 模式，没有备用机的慎重一点点",
                          "Report unfixed bugs to the author; no fastboot mode, be careful without a backup device"),
                  t("基础功能测试目前没毛病；分解增量包、格式转换、其他功能里，除「去除 vbmeta 验证」「一键宽容 v2.0」外均未测试",
                          "Basics tested OK; incremental unpack / convert / more are untested except vbmeta removal & permissive v2.0")};
          for (String pl : pinnedLines) {
              TextView line = text(pl, 11, 0xff7a4a10);
              line.setLineSpacing(dp(2), 1f);
              line.setPadding(0, 0, 0, dp(3));
              pinnedCard.addView(line, new LinearLayout.LayoutParams(-1, -2));
          }
          LinearLayout.LayoutParams pinnedLp = new LinearLayout.LayoutParams(-1, -2);
          pinnedLp.topMargin = dp(10);
          page.addView(pinnedCard, pinnedLp);

          // 工具链状态卡（对齐原版「ROM工具未就绪 + 检测」）
          LinearLayout statusCard = new LinearLayout(this);
          statusCard.setOrientation(LinearLayout.VERTICAL);
          statusCard.setPadding(dp(14), dp(10), dp(12), dp(10));
          GradientDrawable statusBg = new GradientDrawable();
          statusBg.setColor(0xB3FFFFFF);
          statusBg.setCornerRadius(dp(18));
          statusBg.setStroke(Math.max(1, dp(1)), 0x99FFFFFF);
          statusCard.setBackground(statusBg);
          statusCard.setElevation(dp(4));
          
          // 第一行：ROOT 状态 + 工具链文件下载状态
          LinearLayout firstRow = new LinearLayout(this);
          firstRow.setOrientation(LinearLayout.HORIZONTAL);
          firstRow.setGravity(Gravity.CENTER_VERTICAL);
          LinearLayout statusText = new LinearLayout(this);
          statusText.setOrientation(LinearLayout.VERTICAL);
          TextView statusLabel = text(t("ROOT 状态", "ROOT Status"), 12, 0xff5a6b82);
          statusText.addView(statusLabel, new LinearLayout.LayoutParams(-1, -2));
          dnaStatusView = text(t("检测中 …", "Checking..."), 14, 0xff5a6b82);
          dnaStatusView.setTypeface(null, 1);
          statusText.addView(dnaStatusView, new LinearLayout.LayoutParams(-1, -2));
          firstRow.addView(statusText, new LinearLayout.LayoutParams(0, -2, 1f));
          
          // DNA 工具链下载状态显示
          LinearLayout toolsStatusBox = new LinearLayout(this);
          toolsStatusBox.setOrientation(LinearLayout.VERTICAL);
          toolsStatusBox.setGravity(Gravity.END);
          TextView toolsLabel = text(t("工具链文件", "Tools"), 11, 0xff5a6b82);
          toolsLabel.setGravity(Gravity.END);
          toolsStatusBox.addView(toolsLabel, new LinearLayout.LayoutParams(-2, -2));
          dnaToolsDownloadStatus = text(t("检测中", "Checking"), 13, 0xff5a6b82);
          dnaToolsDownloadStatus.setTypeface(null, 1);
          dnaToolsDownloadStatus.setGravity(Gravity.END);
          toolsStatusBox.addView(dnaToolsDownloadStatus, new LinearLayout.LayoutParams(-2, -2));
          firstRow.addView(toolsStatusBox, new LinearLayout.LayoutParams(-2, -2));
          statusCard.addView(firstRow, new LinearLayout.LayoutParams(-1, -2));
          
          // 第二行：下载工具 + 检测按钮
          LinearLayout secondRow = new LinearLayout(this);
          secondRow.setOrientation(LinearLayout.HORIZONTAL);
          secondRow.setGravity(Gravity.CENTER_VERTICAL);
          LinearLayout.LayoutParams secondRowLp = new LinearLayout.LayoutParams(-1, -2);
          secondRowLp.topMargin = dp(8);
          
          // v3.41.11：下载工具按钮（17 个 CLI 工具改为 GitHub Release 云端下载，APK 减重约 27M）
          Button downloadBtn = new Button(this, null, 0);
          downloadBtn.setText(t("下载工具", "Download"));
          downloadBtn.setAllCaps(false);
          downloadBtn.setTextSize(13);
          downloadBtn.setTypeface(null, 1);
          downloadBtn.setTextColor(0xff172b4d);
          downloadBtn.setGravity(Gravity.CENTER);
          downloadBtn.setPadding(0, 0, 0, 0);
          GradientDrawable downloadBg = new GradientDrawable();
          downloadBg.setColor(0x664CAF50);
          downloadBg.setCornerRadius(dp(16));
          downloadBg.setStroke(Math.max(1, dp(1)), 0x99FFFFFF);
          downloadBtn.setBackground(downloadBg);
          downloadBtn.setStateListAnimator(null);
          downloadBtn.setMinWidth(0);
          downloadBtn.setMinHeight(0);
          downloadBtn.setOnClickListener(v -> {
              Haptics.perform(v);
              downloadDnaTools();
          });
          secondRow.addView(downloadBtn, new LinearLayout.LayoutParams(0, dp(40), 1f));
          
          LinearLayout.LayoutParams checkLp = new LinearLayout.LayoutParams(0, dp(40), 1f);
          checkLp.leftMargin = dp(8);
          Button checkBtn = new Button(this, null, 0);
          checkBtn.setText(t("检测", "Check"));
          checkBtn.setAllCaps(false);
          checkBtn.setTextSize(13);
          checkBtn.setTypeface(null, 1);
          checkBtn.setTextColor(0xff172b4d);
          checkBtn.setGravity(Gravity.CENTER);
          checkBtn.setPadding(0, 0, 0, 0);
          GradientDrawable checkBg = new GradientDrawable();
          checkBg.setColor(0x6635A8C4);
          checkBg.setCornerRadius(dp(16));
          checkBg.setStroke(Math.max(1, dp(1)), 0x99FFFFFF);
          checkBtn.setBackground(checkBg);
          checkBtn.setStateListAnimator(null);
          checkBtn.setMinWidth(0);
          checkBtn.setMinHeight(0);
          checkBtn.setOnClickListener(v -> {
              Haptics.perform(v);
              showDnaToolchainReport();
          });
          secondRow.addView(checkBtn, checkLp);
          statusCard.addView(secondRow, secondRowLp);
          
          LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
          statusLp.topMargin = dp(4);
          statusLp.bottomMargin = dp(10);
          page.addView(statusCard, statusLp);

          // 工程管理卡（对齐原版首页：选择工程 / 新建 / 删除 / 解压ROM）
          LinearLayout projectCard = new LinearLayout(this);
          projectCard.setOrientation(LinearLayout.VERTICAL);
          projectCard.setPadding(dp(14), dp(12), dp(14), dp(12));
          GradientDrawable projectBg = new GradientDrawable();
          projectBg.setColor(0xB3FFFFFF);
          projectBg.setCornerRadius(dp(18));
          projectBg.setStroke(Math.max(1, dp(1)), 0x99FFFFFF);
          projectCard.setBackground(projectBg);
          projectCard.setElevation(dp(4));
          LinearLayout currentRow = new LinearLayout(this);
          currentRow.setOrientation(LinearLayout.HORIZONTAL);
          currentRow.setGravity(Gravity.CENTER_VERTICAL);
          TextView projectLabel = text(t("当前工程", "Project"), 12, 0xff5a6b82);
          currentRow.addView(projectLabel, new LinearLayout.LayoutParams(-2, -2));
          // v3.28.5：名字紧跟标签（权重1 左对齐），修复"名字偏右不居中"
          dnaProjectView = text("", 15, 0xff17334f);
          dnaProjectView.setTypeface(null, 1);
          dnaProjectView.setSingleLine(true);
          dnaProjectView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
          LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, -2, 1f);
          nameLp.leftMargin = dp(8);
          currentRow.addView(dnaProjectView, nameLp);
          Button switchBtn = new Button(this, null, 0);
          switchBtn.setText(t("切换工程", "Switch"));
          switchBtn.setAllCaps(false);
          switchBtn.setTextSize(12.5f);
          switchBtn.setTypeface(null, 1);
          switchBtn.setTextColor(0xff172b4d);
          // v3.28.7：显式居中 + 零内边距
          switchBtn.setGravity(Gravity.CENTER);
          switchBtn.setPadding(0, 0, 0, 0);
          GradientDrawable switchBg = new GradientDrawable();
          switchBg.setColor(0x6635A8C4);
          switchBg.setCornerRadius(dp(16));
          switchBg.setStroke(Math.max(1, dp(1)), 0x99FFFFFF);
          switchBtn.setBackground(switchBg);
          switchBtn.setStateListAnimator(null);
          switchBtn.setMinWidth(0);
          switchBtn.setMinHeight(0);
          switchBtn.setOnClickListener(v -> {
              Haptics.perform(v);
              showDnaProjectManager();
          });
          currentRow.addView(switchBtn, new LinearLayout.LayoutParams(-2, dp(40)));
          projectCard.addView(currentRow, new LinearLayout.LayoutParams(-1, dp(44)));
          // 操作按钮排：v3.28.4 整条内嵌液态玻璃槽（图标+文字竖排按钮完全嵌入，分隔线切分）
          LinearLayout opsStrip = new LinearLayout(this);
          opsStrip.setOrientation(LinearLayout.HORIZONTAL);
          opsStrip.setPadding(dp(3), dp(3), dp(3), dp(3));
          GradientDrawable stripBg = new GradientDrawable();
          stripBg.setColor(0x40FFFFFF);
          stripBg.setCornerRadius(dp(16));
          stripBg.setStroke(Math.max(1, dp(1)), 0x59FFFFFF);
          opsStrip.setBackground(stripBg);
          String[] opIcons = {"＋", "🗑", "📦", "🧩"};
          String[] ops = {t("新建", "New"), t("删除", "Del"), t("解压ROM", "ROM"), t("插件", "Plug")};
          for (int i = 0; i < ops.length; i++) {
              final int which = i;
              LinearLayout opBtn = new LinearLayout(this);
              opBtn.setOrientation(LinearLayout.VERTICAL);
              opBtn.setGravity(Gravity.CENTER);
              TextView opIcon = new TextView(this);
              opIcon.setText(opIcons[i]);
              opIcon.setTextSize(15);
              opIcon.setGravity(Gravity.CENTER);
              opBtn.addView(opIcon, new LinearLayout.LayoutParams(-1, -2));
              TextView opLabel = new TextView(this);
              opLabel.setText(ops[i]);
              opLabel.setTextSize(10.5f);
              opLabel.setTypeface(null, 1);
              opLabel.setTextColor(0xff17334f);
              opLabel.setGravity(Gravity.CENTER);
              opBtn.addView(opLabel, new LinearLayout.LayoutParams(-1, -2));
              GradientDrawable opBg = new GradientDrawable();
              opBg.setColor(0x00000000);
              opBg.setCornerRadius(dp(14));
              opBtn.setBackground(opBg);
              opBtn.setOnClickListener(v -> {
                  Haptics.perform(v);
                  if (which == 0) showDnaCreateProject();
                  else if (which == 1) showDnaDeleteProject();
                  else if (which == 2) {
                      // v3.30.20：解压 ROM 弹窗 → 独立二级页面
                      startActivity(new Intent(this, DnaUnzipActivity.class));
                      overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out);
                  }
                  else {
                      // v3.28.11：插件管理弹窗 → 独立二级页面
                      startActivity(new Intent(MainActivity.this, DnaModuleActivity.class));
                      overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out);
                  }
              });
              opsStrip.addView(opBtn, new LinearLayout.LayoutParams(0, dp(54), 1f));
              if (i < ops.length - 1) {
                  View opDivider = new View(this);
                  opDivider.setBackgroundColor(0x33173E5C);
                  LinearLayout.LayoutParams odLp = new LinearLayout.LayoutParams(Math.max(1, dp(1)), dp(28));
                  odLp.gravity = Gravity.CENTER_VERTICAL;
                  opsStrip.addView(opDivider, odLp);
              }
          }
          LinearLayout.LayoutParams opsLp = new LinearLayout.LayoutParams(-1, -2);
          opsLp.topMargin = dp(8);
          projectCard.addView(opsStrip, opsLp);
          LinearLayout.LayoutParams projLp = new LinearLayout.LayoutParams(-1, -2);
          projLp.bottomMargin = dp(12);
          page.addView(projectCard, projLp);
          refreshDnaProjectViews();

          // ===== 分解与提取 =====
          dnaSectionTitle(page, t("分解与提取", "Decompose & Extract"), 0xFF35A8C4);
          dnaMenuItem(page, "🧬", t("分解 bin", "Extract bin"), t("从 payload.bin / OTA zip 提取指定分区", "payload.bin / OTA zip → partitions"), DnaActivity.MODE_BIN, null, 0);
          dnaMenuItem(page, "⚡", t("分解增量包", "Incremental unpack"), t("delta 增量 OTA + 旧镜像目录 → 新 img", "delta OTA + old images → new img"), "dna_incremental", null, 0);
          dnaMenuItem(page, "🧩", t("分解 br", "Extract br"), t("解包 BR 文件", "Unpack brotli"), DnaActivity.MODE_EXTRACT, "br", 0);
          dnaMenuItem(page, "🧾", t("分解 dat", "Extract dat"), t("解包 DAT 文件", "Unpack dat"), DnaActivity.MODE_EXTRACT, "dat", 0);
          dnaMenuItem(page, "🧊", t("分解 img", "Extract img"), t("解包 IMG 文件（自动识别 erofs / ext4 / f2fs）", "Unpack image (erofs / ext4 / f2fs)"), DnaActivity.MODE_EXTRACT, "img", 0);
          dnaMenuItem(page, "🗂", t("分解 super", "Extract super"), t("解包 super.img 并提取指定分区", "super.img → partitions"), DnaActivity.MODE_SUPER_UNPACK, null, 1);

          // ===== 合成与打包 =====
          dnaSectionTitle(page, t("合成与打包", "Repack & Build"), 0xFF11998E);
          dnaMenuItem(page, "📦", t("合成 img-dat-br", "Build img-dat-br"), t("将工程目录重新打包成镜像", "Project dirs → image"), DnaActivity.MODE_REPACK, null, 2);
          dnaMenuItem(page, "🗜", t("合成 super.img", "Build super.img"), t("把 IMG 打包成 super.img（A / AB / VAB）", "IMGs → super.img"), DnaActivity.MODE_SUPER_PACK, null, 3);
          dnaMenuItem(page, "📦", t("合成 OTA 卡刷包", "Build OTA zip"), t("img 集 + 模板 → payload 签名完整 A/B 卡刷包", "imgs + template → signed A/B OTA zip"), "dna_ota_pack", null, 3);

          // ===== 格式转换 =====
          dnaSectionTitle(page, t("格式转换", "Convert"), 0xFFE07B39);
          dnaMenuItem(page, "🔁", t("img-simg 互转", "img ↔ sparse"), t("ext / erofs 转 sparse，sparse 转 raw", "raw ↔ sparse auto"), DnaActivity.MODE_SPARSE, null, 4);
          dnaMenuItem(page, "🧾", t("img-dat-br 转换", "img → dat / br"), t("镜像转卡刷 dat / br 格式", "img → dat / br"), DnaActivity.MODE_CONVERT, null, 4);
          dnaMenuItem(page, "⚡", t("zst-img 互转", "zst ↔ img"), t("zstd 多线程高速压缩 / 解压", "zstd compress / decompress"), DnaActivity.MODE_ZST, null, 4);
          dnaMenuItem(page, "🧩", t("合并 Sparse 分段", "Merge sparse chunks"), t("将分段 IMG 合并为完整 IMG", "Split images → one"), DnaActivity.MODE_CHUNK, null, 4);

          // ===== 其他功能（v3.30.11：原版 more.xml「其它功能」组，置于声明上方）=====
          dnaSectionTitle(page, t("其他功能", "More"), 0xFF8E6FD8);
          dnaMenuItem(page, "🛡", t("去除 vbmeta 验证", "Remove vbmeta"), t("读取 PDNA 根目录 img，去除 AVB 验证（输出 /sdcard/PDNA/out）", "PDNA root img → disable AVB (output /sdcard/PDNA/out)"), DnaActivity.MODE_VBMETA, null, 4);
          dnaMenuItem(page, "🔓", t("一键宽容 v2.0", "Permissive v2.0"), t("读取 PDNA 根目录 img，注入 SELinux 宽容（输出 /sdcard/PDNA/out）", "PDNA root img → permissive (output /sdcard/PDNA/out)"), DnaActivity.MODE_SELINUX, null, 4);
          dnaMenuItem(page, "🧬", t("合并 my_ 分区进 system", "Merge my_ into system"), t("分解 my 分区和 system 分区后再执行", "Extract my & system first"), DnaActivity.MODE_MERGE_MY, null, 4);
          dnaMenuItem(page, "🗂", t("合并分段 super", "Merge split super"), t("合并项目目录下的分段 super 文件", "super.img.N → super.img"), DnaActivity.MODE_MERGE_SUPER, null, 4);
          dnaMenuItem(page, "📦", t("合并其他分区进 system(内层)", "Merge partitions into system"), t("分解分区和 system 分区后再执行", "Extract partitions & system first"), DnaActivity.MODE_MERGE_PART, null, 4);

          // ===== 声明（v3.28.7：白玻璃卡 + 深红字，修复红底红字看不清）=====
          LinearLayout noticeCard = new LinearLayout(this);
          noticeCard.setOrientation(LinearLayout.VERTICAL);
          noticeCard.setPadding(dp(14), dp(12), dp(14), dp(12));
          GradientDrawable noticeBg = new GradientDrawable();
          noticeBg.setColor(0xE6FFFFFF);
          noticeBg.setCornerRadius(dp(18));
          noticeBg.setStroke(Math.max(1, dp(1)), 0x66FF8A8A);
          noticeCard.setBackground(noticeBg);
          noticeCard.setElevation(dp(4));
          TextView noticeTitle = text("⚠ " + t("声明", "Notice"), 14, 0xffC03A2B);
          noticeTitle.setTypeface(null, 1);
          noticeTitle.setPadding(0, 0, 0, dp(6));
          noticeCard.addView(noticeTitle, new LinearLayout.LayoutParams(-1, -2));
          String[] notices = {
                  t("本工具不会主动破坏手机系统，因使用者不当操作，造成的一切后果自行承担", "This tool will not actively break your system. Users are responsible for improper operations"),
                  t("一句话：爱用就用 不用就卸载", "Use it or uninstall it"),
                  t("如果发现在你手机上使用有问题，请联系我修复，QQ：1415370573", "Issues? Contact QQ: 1415370573")};
          for (String n : notices) {
              TextView line = text(n, 11, 0xff8a4a42);
              line.setLineSpacing(dp(2), 1f);
              line.setPadding(0, 0, 0, dp(4));
              noticeCard.addView(line, new LinearLayout.LayoutParams(-1, -2));
          }
          LinearLayout.LayoutParams noticeLp = new LinearLayout.LayoutParams(-1, -2);
          noticeLp.topMargin = dp(14);
          page.addView(noticeCard, noticeLp);

          // 底部说明
          TextView note = text(t("DNA 工具链来自原版 DNA 工具箱 · 工程 /sdcard/PDNA/ · 分解输出 /data/PDNA/（产物前缀 PDNA_）", "DNA toolchain from the original DNA Toolbox · /sdcard/PDNA/ + /data/PDNA/ (PDNA_ prefix)"), 12, 0xffdfe9f5);
          note.setPadding(dp(4), dp(12), dp(4), 0);
          page.addView(note, new LinearLayout.LayoutParams(-1, -2));
          // 异步检测工具链
          refreshDnaToolchain();
          return page;
      }

      /** DNA 分区小标题：彩色圆点 + 加粗白字（对齐原版分组标题） */
      private void dnaSectionTitle(LinearLayout page, String title, int color) {
          LinearLayout row = new LinearLayout(this);
          row.setOrientation(LinearLayout.HORIZONTAL);
          row.setGravity(Gravity.CENTER_VERTICAL);
          android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
          dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
          dot.setColor(color);
          row.addView(new View(this) {{ setBackground(dot); }}, new LinearLayout.LayoutParams(dp(8), dp(8)));
          TextView text = text(title, 16, Color.WHITE);
          text.setTypeface(null, 1);
          LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(-2, -2);
          textLp.leftMargin = dp(8);
          row.addView(text, textLp);
          // 分隔线
          View line = new View(this);
          line.setBackgroundColor(0x66FFFFFF);
          LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(0, Math.max(1, dp(1)), 1f);
          lineLp.leftMargin = dp(10);
          lineLp.gravity = Gravity.CENTER_VERTICAL;
          row.addView(line, lineLp);
          LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, dp(26));
          rowLp.bottomMargin = dp(8);
          rowLp.topMargin = dp(6);
          page.addView(row, rowLp);
      }

      /** DNA 功能卡片（白玻璃行卡 + 彩色徽章，对齐原版白卡布局） */
      private void dnaMenuItem(LinearLayout page, String icon, String title, String subtitle, String mode, String filter, int anim) {
          LinearLayout card = new LinearLayout(this);
          card.setOrientation(LinearLayout.HORIZONTAL);
          card.setGravity(Gravity.CENTER_VERTICAL);
          card.setPadding(dp(14), dp(11), dp(14), dp(11));
          GradientDrawable cardBg = new GradientDrawable();
          cardBg.setColor(0xB3FFFFFF);
          cardBg.setCornerRadius(dp(18));
          cardBg.setStroke(Math.max(1, dp(1)), 0x99FFFFFF);
          android.graphics.drawable.RippleDrawable ripple =
                  new android.graphics.drawable.RippleDrawable(
                          new android.content.res.ColorStateList(
                                  new int[][]{{android.R.attr.state_pressed}}, new int[]{0x3335A8C4}),
                          cardBg, null);
          card.setBackground(ripple);
          card.setElevation(dp(4));
          card.setOnClickListener(v -> {
              Haptics.perform(v);
              openDnaMode(mode, filter, anim);
          });
          // 彩色圆角徽章
          FrameLayout badge = new FrameLayout(this);
          GradientDrawable badgeBg = new GradientDrawable();
          badgeBg.setShape(GradientDrawable.OVAL);
          badgeBg.setOrientation(GradientDrawable.Orientation.TL_BR);
          badgeBg.setColors(new int[]{0xFF35A8C4, 0xFF0E7D95});
          badgeBg.setStroke(Math.max(1, dp(1)), 0xB3FFFFFF);
          badge.setBackground(badgeBg);
          TextView iconView = new TextView(this);
          iconView.setText(icon);
          iconView.setTextSize(17);
          iconView.setGravity(Gravity.CENTER);
          badge.addView(iconView, new FrameLayout.LayoutParams(-1, -1));
          card.addView(badge, new LinearLayout.LayoutParams(dp(42), dp(42)));
          // 标题 + 副标题
          LinearLayout textBox = new LinearLayout(this);
          textBox.setOrientation(LinearLayout.VERTICAL);
          textBox.setPadding(dp(12), 0, 0, 0);
          TextView titleView = new TextView(this);
          titleView.setText(title);
          titleView.setTextSize(14.5f);
          titleView.setTypeface(null, 1);
          titleView.setTextColor(0xff17334f);
          textBox.addView(titleView, new LinearLayout.LayoutParams(-1, -2));
          TextView subView = new TextView(this);
          subView.setText(subtitle);
          subView.setTextSize(11.5f);
          subView.setTextColor(0xff5a6b82);
          LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
          subLp.topMargin = dp(2);
          textBox.addView(subView, subLp);
          card.addView(textBox, new LinearLayout.LayoutParams(0, -2, 1f));
          // 箭头
          TextView arrow = new TextView(this);
          arrow.setText("›");
          arrow.setTextSize(22);
          arrow.setTextColor(0xff5a6b82);
          card.addView(arrow, new LinearLayout.LayoutParams(-2, -2));
          LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
          cardLp.bottomMargin = dp(8);
          page.addView(card, cardLp);
      }

      /** 打开 DNA 各功能页（不同功能差异化转场动画） */
      private void openDnaMode(String dnaMode, String filter, int index) {
          // v3.30.20：分解 bin 独立二级页（JNI 解析/提取，不走 DNA 工作台）
          if (DnaActivity.MODE_BIN.equals(dnaMode)) {
              startActivity(new Intent(this, DnaBinActivity.class));
              overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out);
              return;
          }
          // v3.30.36：分解增量包独立二级页（payload_dumper --source-dir，root 链路）
          if ("dna_incremental".equals(dnaMode)) {
              startActivity(new Intent(this, DnaIncrementalActivity.class));
              overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out);
              return;
          }
          // v3.43.0：合成 OTA 卡刷包独立二级页（delta_generator + OtaPacker 全链路）
          if ("dna_ota_pack".equals(dnaMode)) {
              startActivity(new Intent(this, DnaOtaActivity.class));
              overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out);
              return;
          }
          // v3.28.7：分解 super 门槛 —— 当前工程必须存在 super.img 才能进入（root 异步检测）
          if (DnaActivity.MODE_SUPER_UNPACK.equals(dnaMode)) {
              String cur = DnaTools.currentProject(this);
              if (cur == null) {
                  toast(t("请先选择工程（需包含 super.img）", "Select a project with super.img first"));
                  showDnaProjectManager();
                  return;
              }
              toast(t("正在检测 super.img ...", "Checking super.img..."));
              new Thread(() -> {
                  String path = DnaTools.WORK_ROOT + "/" + cur + "/super.img";
                  boolean has = RootShell.exec(
                          "[ -f " + DnaTools.quote(path) + " ] && echo __YES__ || echo __NO__",
                          10000, null).getStdout().contains("__YES__");
                  runOnUiThread(() -> {
                      if (isFinishing() || isDestroyed()) return;
                      if (has) {
                          // v3.30.29：分解 super 独立二级页（解析 → 弹窗勾选 → 提取，对齐分解 bin 交互）
                          Intent si = new Intent(this, DnaSuperActivity.class);
                          si.putExtra(DnaSuperActivity.EXTRA_SUPER, path);
                          startActivity(si);
                          overridePendingTransition(R.anim.flip_in, R.anim.flip_out);
                      } else {
                          Toast.makeText(this, t("当前工程未检测到 super.img\n请先解压 ROM 或导入 super.img", "No super.img in current project"), Toast.LENGTH_LONG).show();
                      }
                  });
              }, "dna-super-check").start();
              return;
          }
          startDnaMode(dnaMode, filter, index);
      }

      /** 启动 DNA 功能页（openDnaMode 检测通过后调用） */
      private void startDnaMode(String dnaMode, String filter, int index) {
          Intent intent = new Intent(this, DnaActivity.class);
          intent.putExtra(DnaActivity.EXTRA_MODE, dnaMode);
          if (filter != null) intent.putExtra(DnaActivity.EXTRA_FILTER, filter);
          startActivity(intent);
          switch (index) {
              case 1: overridePendingTransition(R.anim.flip_in, R.anim.flip_out); break;      // 分解 SUPER：3D 翻转
              case 2: overridePendingTransition(R.anim.explode_in, R.anim.explode_out); break; // 合成镜像：爆炸缩放
              case 3: overridePendingTransition(R.anim.slide_up_in, R.anim.slide_up_out); break; // 合成 SUPER：底部滑入
              default: overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out); break;    // 分解镜像 / 转换：缩放淡入
          }
      }

      /** 异步检测 DNA 工具链（root + 17 个二进制自检；v3.41.11 区分「未下载」与「无 ROOT」）
       *  v3.41.17：线程安全 —— 初始「检测中」提示经 runOnUiThread 上屏（修复从 dna-deep-check
       *  等子线程调用时 CalledFromWrongThreadException 崩溃）
       *  v3.50.12：同步更新右上角工具链文件下载状态
       *  v3.50.13：左边改为 ROOT 状态，右边显示工具链文件下载状态 */
      private void refreshDnaToolchain() {
          if (dnaStatusView == null) return;
          runOnUiThread(() -> {
              if (dnaStatusView == null || isFinishing() || isDestroyed()) return;
              dnaStatusView.setText(t("检测中 …", "Checking..."));
              dnaStatusView.setTextColor(0xff5a6b82);
              if (dnaToolsDownloadStatus != null) {
                  dnaToolsDownloadStatus.setText(t("检测中", "Checking"));
                  dnaToolsDownloadStatus.setTextColor(0xff5a6b82);
              }
          });
          new Thread(() -> {
              boolean ok = DnaTools.ensure(this) != null;
              boolean rootAvail = ok;
              if (!rootAvail) {
                  try { rootAvail = RootShell.INSTANCE.available(); } catch (Exception e) { rootAvail = false; }
              }
              final boolean rootOk = rootAvail;
              final boolean installed = DnaTools.toolsInstalled(this);
              runOnUiThread(() -> {
                  if (dnaStatusView == null) return;
                  // 左边显示 ROOT 状态
                  if (rootOk) {
                      dnaStatusView.setText(t("✓ 已授权", "✓ Granted"));
                      dnaStatusView.setTextColor(0xff1d7a4f);
                  } else {
                      dnaStatusView.setText(t("✗ 未授权", "✗ Denied"));
                      dnaStatusView.setTextColor(0xffa33b3b);
                  }
                  // 右边显示工具链文件下载状态
                  if (dnaToolsDownloadStatus != null) {
                      if (installed) {
                          dnaToolsDownloadStatus.setText(t("已下载", "Downloaded"));
                          dnaToolsDownloadStatus.setTextColor(0xff1d7a4f);
                      } else {
                          dnaToolsDownloadStatus.setText(t("未下载，不可用", "Not downloaded"));
                          dnaToolsDownloadStatus.setTextColor(0xffa33b3b);
                      }
                  }
              });
          }, "dna-toolchain-check").start();
      }

      /**
       * v3.41.16：工具链深度检测报告（「检测」按钮）——
       * ROOT 授权 / 工具包完整性（17 个逐一校验）/ 关键工具真实执行（dna·busybox·magiskboot）。
       * 文件存在 ≠ 可用：架构不符或下载截断的 ELF 只有真实执行才能暴露，这是 DNA 功能可用的关键判定。
       * 逐项检测、每完成一项立即上屏。
       */
      private void showDnaToolchainReport() {
          Dialog dialog = new Dialog(this);
          dialog.setCancelable(true);
          LinearLayout panel = new LinearLayout(this);
          panel.setOrientation(LinearLayout.VERTICAL);
          panel.setPadding(dp(20), dp(18), dp(20), dp(16));
          GradientDrawable bg = new GradientDrawable();
          bg.setColor(0xF2e9f0f7);
          bg.setCornerRadius(dp(24));
          bg.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
          panel.setBackground(bg);
          TextView title = text("🔍 " + t("工具链检测", "Toolchain Check"), 16, 0xff17334f);
          title.setTypeface(null, 1);
          title.setPadding(0, 0, 0, dp(10));
          panel.addView(title, new LinearLayout.LayoutParams(-1, -2));
          LinearLayout rows = new LinearLayout(this);
          rows.setOrientation(LinearLayout.VERTICAL);
          // v3.41.18：内容区包进 ScrollView（weight=1 占满剩余空间）——
          // ROOT/完整性/影响说明/功能自检逐项上屏后总高常超一屏，固定高度会把
          // 结论与「关闭」按钮挤出屏幕（文字看得见尾巴却无法查看）；超出部分上下滑动
          android.widget.ScrollView scroll = new android.widget.ScrollView(this);
          scroll.setFillViewport(true);
          scroll.setVerticalScrollBarEnabled(true);
          scroll.addView(rows, new LinearLayout.LayoutParams(-1, -2));
          panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
          TextView verdict = text(t("正在检测 …", "Checking..."), 13, 0xff5a6b82);
          verdict.setTypeface(null, 1);
          verdict.setPadding(0, dp(12), 0, dp(4));
          panel.addView(verdict, new LinearLayout.LayoutParams(-1, -2));
          // v3.41.19：底部双大按钮（替换单调的文字「关闭」）——
          // 左「关闭」（中性灰）+ 右上下文按钮：工具包不完整 →「下载工具」（绿），完整 →「重新检测」（青）
          LinearLayout btnRow = new LinearLayout(this);
          btnRow.setOrientation(LinearLayout.HORIZONTAL);
          Button closeBtn = new Button(this, null, 0);
          closeBtn.setText(t("关闭", "Close"));
          closeBtn.setAllCaps(false);
          closeBtn.setTextSize(14);
          closeBtn.setTypeface(null, 1);
          closeBtn.setTextColor(0xff172b4d);
          closeBtn.setGravity(Gravity.CENTER);
          closeBtn.setPadding(0, 0, 0, 0);
          GradientDrawable closeBg = new GradientDrawable();
          closeBg.setColor(0x669AA7B8);
          closeBg.setCornerRadius(dp(18));
          closeBg.setStroke(Math.max(1, dp(1)), 0x99FFFFFF);
          closeBtn.setBackground(closeBg);
          closeBtn.setStateListAnimator(null);
          closeBtn.setMinWidth(0);
          closeBtn.setMinHeight(0);
          closeBtn.setOnClickListener(v -> dialog.dismiss());
          LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(0, dp(46), 1f);
          closeLp.rightMargin = dp(5);
          btnRow.addView(closeBtn, closeLp);
          Button actionBtn = new Button(this, null, 0);
          actionBtn.setText("↻ " + t("重新检测", "Re-check"));
          actionBtn.setAllCaps(false);
          actionBtn.setTextSize(14);
          actionBtn.setTypeface(null, 1);
          actionBtn.setTextColor(0xff172b4d);
          actionBtn.setGravity(Gravity.CENTER);
          actionBtn.setPadding(0, 0, 0, 0);
          GradientDrawable actionBg = new GradientDrawable();
          actionBg.setColor(0x6635A8C4);
          actionBg.setCornerRadius(dp(18));
          actionBg.setStroke(Math.max(1, dp(1)), 0x99FFFFFF);
          actionBtn.setBackground(actionBg);
          actionBtn.setStateListAnimator(null);
          actionBtn.setMinWidth(0);
          actionBtn.setMinHeight(0);
          actionBtn.setOnClickListener(v -> {
              dialog.dismiss();
              showDnaToolchainReport();   // 重新跑一遍完整检测
          });
          LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(0, dp(46), 1f);
          actionLp.leftMargin = dp(5);
          btnRow.addView(actionBtn, actionLp);
          LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(-1, dp(46));
          btnRowLp.topMargin = dp(6);
          panel.addView(btnRow, btnRowLp);
          dialog.setContentView(panel, new LinearLayout.LayoutParams(-1, -2));
          // v3.41.18：高度从固定 420dp 改为屏高 72% 自适应（长内容时更长的可视区，超出部分滚动）
          android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
          showWide(dialog, Math.round(dm.heightPixels * 0.72f / dm.density));
          new Thread(() -> {
              // 1. ROOT 授权
              boolean rootOk;
              try { rootOk = RootShell.INSTANCE.available(); } catch (Exception e) { rootOk = false; }
              addCheckRow(rows, t("ROOT 授权", "ROOT"), rootOk,
                      rootOk ? "su 可用" : t("未授予 ROOT 权限", "no root grant"));
              // 2. 工具包完整性（17 个逐一：存在 + 大小>0 + 可执行）
              java.util.List<String> missing = DnaTools.INSTANCE.missingTools(this);
              boolean complete = missing.isEmpty();
              String compDetail = complete
                      ? "17/17 · " + fmtSize(DnaTools.INSTANCE.toolsBytes(this)) + t(" · 全部可执行", " · all executable")
                      : missing.size() >= 17
                      ? t("工具包未下载（点「下载工具」）", "not downloaded")
                      : t("缺失 ", "missing ") + missing.size() + ": " + android.text.TextUtils.join(", ", missing);
              addCheckRow(rows, t("工具包完整性", "Tools integrity"), complete, compDetail);
              // v3.41.17：工具包不完整时说明影响范围（哪些功能不能用、哪些不受影响）
              // 影响面与代码实际依赖一致：所有 DNA CLI 功能经 DnaTools.run→ensure（需工具包）；
              // bin 解析主链路为内置 Java/JNI 直读（可用），但提取走 payloadExtractCli→run（不可用）；
              // ROM 分区提取主链路为内置 PayloadExtractor（可用），payload_dumper 仅是兜底。
              if (!complete) {
                  addImpactNote(rows, "⚠ " + t("影响说明：工具包未就位", "Impact: tools missing") + "\n"
                          + t("不可用（依赖工具包）：", "Unavailable (needs tools):") + "\n"
                          + "· " + t("分解 bin（能解析选区，无法提取镜像）", "bin unpack (parse OK, extract fails)") + "\n"
                          + "· " + t("分解增量包 / br / dat / Dt", "incremental / br / dat / Dt unpack") + "\n"
                          + "· " + t("分解 / 合成 SUPER、合成 img-dat-br", "SUPER unpack/repack, repack img-dat-br") + "\n"
                          + "· " + t("img 格式转换、解压 ROM、插件", "convert / unzip ROM / plugins") + "\n"
                          + t("不受影响（内置组件）：", "Unaffected (built-in):") + "\n"
                          + "· " + t("ROM 分区提取 / OTG 助手", "ROM partition extract / OTG") + "\n"
                          + "· " + t("DSU / GSI 安装、终端、下载更新", "DSU / GSI / terminal / update") + "\n"
                          + t("→ 点状态卡「下载工具」补齐（约 15M）", "→ tap Download (~15M)"));
                  // 右下按钮「重新检测」→「下载工具」（绿），直接在报告里一键补齐
                  runOnUiThread(() -> {
                      if (isFinishing() || isDestroyed()) return;
                      actionBtn.setText("⬇ " + t("下载工具", "Download"));
                      GradientDrawable dlBg = new GradientDrawable();
                      dlBg.setColor(0x664CAF50);
                      dlBg.setCornerRadius(dp(18));
                      dlBg.setStroke(Math.max(1, dp(1)), 0x99FFFFFF);
                      actionBtn.setBackground(dlBg);
                      actionBtn.setOnClickListener(v -> {
                          dialog.dismiss();
                          downloadDnaTools();
                      });
                  });
              }
              // 3. 关键工具真实执行（dna 走 ensure 全链路：伪装目录 + 中转同步 + gettype）
              java.util.List<DnaTools.CheckItem> funcs = DnaTools.INSTANCE.functionalCheck(this);
              boolean allOk = rootOk && complete;
              for (DnaTools.CheckItem item : funcs) {
                  addCheckRow(rows, item.getName(), item.getOk(), item.getDetail());
                  allOk = allOk && item.getOk();
              }
              // 汇总结论 + 同步状态卡
              final boolean finalOk = allOk;
              runOnUiThread(() -> {
                  if (isFinishing() || isDestroyed()) return;
                  verdict.setText(finalOk
                          ? "✓ " + t("DNA 功能可用（分解 / 提取 / 打包均正常）", "DNA ready")
                          : "✗ " + t("DNA 功能不可用，请按上面失败项处理", "DNA NOT ready"));
                  verdict.setTextColor(finalOk ? 0xff1d7a4f : 0xffa33b3b);
              });
              refreshDnaToolchain();
          }, "dna-deep-check").start();
      }

      /** 检测报告单行（✓/✗ + 名称 + 右侧灰字详情），主线程上屏 */
      private void addCheckRow(LinearLayout rows, String name, boolean ok, String detail) {
          runOnUiThread(() -> {
              if (isFinishing() || isDestroyed()) return;
              LinearLayout row = new LinearLayout(this);
              row.setOrientation(LinearLayout.HORIZONTAL);
              row.setGravity(Gravity.CENTER_VERTICAL);
              row.setPadding(0, dp(5), 0, dp(5));
              TextView icon = text(ok ? "✓" : "✗", 15, ok ? 0xff1d7a4f : 0xffa33b3b);
              icon.setTypeface(null, 1);
              row.addView(icon, new LinearLayout.LayoutParams(-2, -2));
              TextView nameView = text(" " + name, 13, 0xff17334f);
              nameView.setTypeface(null, 1);
              row.addView(nameView, new LinearLayout.LayoutParams(-2, -2));
              TextView detailView = text(detail, 11, 0xff5a6b82);
              detailView.setGravity(Gravity.END);
              detailView.setPadding(dp(8), 0, 0, 0);
              row.addView(detailView, new LinearLayout.LayoutParams(0, -2, 1f));
              rows.addView(row, new LinearLayout.LayoutParams(-1, -2));
          });
      }

      /** 检测报告影响说明块（工具包缺失时：哪些功能不可用 / 哪些不受影响），主线程上屏 */
      private void addImpactNote(LinearLayout rows, String content) {
          runOnUiThread(() -> {
              if (isFinishing() || isDestroyed()) return;
              TextView note = text(content, 11, 0xff8a5a1d);
              note.setLineSpacing(dp(2), 1f);
              GradientDrawable noteBg = new GradientDrawable();
              noteBg.setColor(0x26F5A623);
              noteBg.setCornerRadius(dp(10));
              note.setBackground(noteBg);
              note.setPadding(dp(10), dp(8), dp(10), dp(8));
              LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, -2);
              noteLp.setMargins(0, dp(4), 0, dp(4));
              rows.addView(note, noteLp);
          });
      }

      /**
       * v3.41.11：云端下载 DNA 工具包（dna-tools-v1.zip ≈ 15M，GitHub Release + 镜像线路兜底）。
       * 弹窗显示进度条 + 实时日志；完成/失败后自动刷新工具链状态。
       */
      private void downloadDnaTools() {
          if (!dnaDownloading.compareAndSet(false, true)) {
              toast(t("正在下载中，请稍候 …", "Download in progress..."));
              return;
          }
          Dialog dialog = new Dialog(this);
          dialog.setCancelable(false);
          LinearLayout panel = new LinearLayout(this);
          panel.setOrientation(LinearLayout.VERTICAL);
          panel.setPadding(dp(20), dp(18), dp(20), dp(16));
          GradientDrawable bg = new GradientDrawable();
          bg.setColor(0xF2e9f0f7);
          bg.setCornerRadius(dp(24));
          bg.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
          panel.setBackground(bg);
          TextView title = text("⬇ " + t("下载工具", "Download Tools"), 16, 0xff17334f);
          title.setTypeface(null, 1);
          title.setPadding(0, 0, 0, dp(10));
          panel.addView(title, new LinearLayout.LayoutParams(-1, -2));
          ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
          bar.setMax(100);
          bar.setProgress(0);
          GradientDrawable barBg = new GradientDrawable();
          barBg.setColor(0x33000000);
          barBg.setCornerRadius(dp(6));
          GradientDrawable barFill = new GradientDrawable();
          barFill.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
          barFill.setColors(new int[]{0xFF35A8C4, 0xFF1d7a4f});
          barFill.setCornerRadius(dp(6));
          LayerDrawable barLayers = new LayerDrawable(new Drawable[]{barBg, new ClipDrawable(barFill, Gravity.LEFT, ClipDrawable.HORIZONTAL)});
          barLayers.setId(0, android.R.id.background);
          barLayers.setId(1, android.R.id.progress);
          bar.setProgressDrawable(barLayers);
          panel.addView(bar, new LinearLayout.LayoutParams(-1, dp(12)));
          TextView log = text(t("正在准备下载 …", "Preparing..."), 12, 0xff5a6b82);
          log.setPadding(0, dp(10), 0, 0);
          panel.addView(log, new LinearLayout.LayoutParams(-1, -2));
          dialog.setContentView(panel, new LinearLayout.LayoutParams(-1, -2));
          showWide(dialog, 420);
          final String[] lastLog = {""};
          new Thread(() -> {
              boolean ok = DnaTools.downloadTools(this,
                      line -> {
                          lastLog[0] = line;
                          runOnUiThread(() -> {
                              if (isFinishing() || isDestroyed()) return;
                              log.setText(line);
                          });
                          return kotlin.Unit.INSTANCE;
                      },
                      p -> {
                          runOnUiThread(() -> {
                              if (isFinishing() || isDestroyed()) return;
                              bar.setProgress(p);
                          });
                          return kotlin.Unit.INSTANCE;
                      });
              runOnUiThread(() -> {
                  dnaDownloading.set(false);
                  if (isFinishing() || isDestroyed()) return;
                  if (ok) {
                      dialog.dismiss();
                      Toast.makeText(this, t("工具下载部署完成", "Tools deployed"), Toast.LENGTH_LONG).show();
                      refreshDnaToolchain();
                      return;
                  }
                  // v3.41.15：失败保留弹窗显示具体原因 + 重试按钮（对齐 APP 更新弹窗交互，
                  // 「未上传工具包」与「网络失败」在日志里有明确区分，不再一律「检查网络」）
                  bar.setProgress(0);
                  log.setText(lastLog[0].isEmpty() ? t("下载失败", "Download failed") : lastLog[0]);
                  LinearLayout btnRow = new LinearLayout(this);
                  btnRow.setOrientation(LinearLayout.HORIZONTAL);
                  btnRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
                  btnRow.setPadding(0, dp(12), 0, 0);
                  Button retry = new Button(this, null, 0);
                  retry.setText(t("重试", "Retry"));
                  retry.setAllCaps(false);
                  retry.setTextSize(13.5f);
                  retry.setTypeface(null, 1);
                  retry.setTextColor(0xFFFFFFFF);
                  GradientDrawable retryBg = new GradientDrawable();
                  retryBg.setColor(0xFF1d7a4f);
                  retryBg.setCornerRadius(dp(16));
                  retry.setBackground(retryBg);
                  retry.setStateListAnimator(null);
                  retry.setMinWidth(0);
                  retry.setMinHeight(0);
                  retry.setPadding(dp(18), 0, dp(18), 0);
                  retry.setOnClickListener(v -> {
                      Haptics.perform(v);
                      dialog.dismiss();
                      downloadDnaTools();
                  });
                  btnRow.addView(retry, new LinearLayout.LayoutParams(-2, dp(40)));
                  Button close = new Button(this, null, 0);
                  close.setText(t("关闭", "Close"));
                  close.setAllCaps(false);
                  close.setTextSize(13.5f);
                  close.setTextColor(0xff5a6b82);
                  close.setBackground(null);
                  close.setStateListAnimator(null);
                  close.setMinWidth(0);
                  close.setMinHeight(0);
                  close.setPadding(dp(14), 0, dp(4), 0);
                  close.setOnClickListener(v -> dialog.dismiss());
                  LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-2, dp(40));
                  closeParams.setMargins(dp(6), 0, 0, 0);
                  btnRow.addView(close, closeParams);
                  panel.addView(btnRow, new LinearLayout.LayoutParams(-1, -2));
              });
          }, "dna-tools-download").start();
      }

      /** v3.41.12：存储清理字节数格式化（G/M/K） */
      private String fmtSize(long bytes) {
          if (bytes >= 1073741824L) return String.format(Locale.US, "%.2fG", bytes / 1073741824f);
          if (bytes >= 1048576L) return String.format(Locale.US, "%.1fM", bytes / 1048576f);
          return (bytes / 1024L) + "K";
      }

      /** 刷新 DNA 页当前工程显示 */
      private void refreshDnaProjectViews() {
          if (dnaProjectView == null) return;
          String current = DnaTools.currentProject(this);
          if (current != null) {
              dnaProjectView.setText(current);
              dnaProjectView.setTextColor(0xff17334f);
          } else {
              dnaProjectView.setText(t("未选择", "None"));
              dnaProjectView.setTextColor(0xffa33b3b);
          }
      }

      /** 工程切换对话框（玻璃风列表） */
      private void showDnaProjectManager() {
          java.util.List<String> projects = DnaTools.listProjects();
          Dialog dialog = new Dialog(this);
          dialog.setCancelable(true);
          LinearLayout panel = new LinearLayout(this);
          panel.setOrientation(LinearLayout.VERTICAL);
          panel.setPadding(dp(18), dp(16), dp(18), dp(16));
          GradientDrawable bg = new GradientDrawable();
          bg.setColor(0xF2e9f0f7);
          bg.setCornerRadius(dp(24));
          bg.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
          panel.setBackground(bg);
          TextView title = new TextView(this);
          title.setText(t("选择工程", "Select project"));
          title.setTextSize(16);
          title.setTypeface(null, 1);
          title.setTextColor(0xff17334f);
          title.setPadding(0, 0, 0, dp(10));
          panel.addView(title, new LinearLayout.LayoutParams(-1, -2));
          ScrollView listScroll = new ScrollView(this);
          LinearLayout list = new LinearLayout(this);
          list.setOrientation(LinearLayout.VERTICAL);
          listScroll.addView(list, new ScrollView.LayoutParams(-1, -2));
          if (projects.isEmpty()) {
              TextView empty = new TextView(this);
              empty.setText(t("暂无工程，请先新建", "No projects yet"));
              empty.setTextSize(13);
              empty.setTextColor(0xff5a6b82);
              empty.setPadding(0, dp(8), 0, dp(8));
              list.addView(empty, new LinearLayout.LayoutParams(-1, -2));
          }
          String current = DnaTools.currentProject(this);
          for (String name : projects) {
              TextView row = new TextView(this);
              row.setText((name.equals(current) ? "● " : "○ ") + name);
              row.setTextSize(14);
              row.setTextColor(0xff17334f);
              // v3.28.7：长工程名单行省略（修复弹窗文字溢出）
              row.setSingleLine(true);
              row.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
              row.setGravity(Gravity.CENTER_VERTICAL);
              row.setPadding(dp(10), dp(12), dp(10), dp(12));
              GradientDrawable rowBg = new GradientDrawable();
              rowBg.setColor(name.equals(current) ? 0x3335A8C4 : 0x22FFFFFF);
              rowBg.setCornerRadius(dp(14));
              row.setBackground(rowBg);
              LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
              rowLp.bottomMargin = dp(6);
              list.addView(row, rowLp);
              row.setOnClickListener(v -> {
                  Haptics.perform(v);
                  DnaTools.setCurrentProject(this, name);
                  refreshDnaProjectViews();
                  dialog.dismiss();
                  Toast.makeText(this, t("已切换工程", "Switched") + ": " + name, Toast.LENGTH_SHORT).show();
              });
          }
          panel.addView(listScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
          Button close = new Button(this, null, 0);
          close.setText(t("关闭", "Close"));
          close.setAllCaps(false);
          close.setTextColor(0xff172b4d);
          // v3.28.7：显式居中 + 零内边距
          close.setGravity(Gravity.CENTER);
          close.setPadding(0, 0, 0, 0);
          GradientDrawable closeBg = new GradientDrawable();
          closeBg.setColor(0x59FFFFFF);
          closeBg.setCornerRadius(dp(16));
          closeBg.setStroke(Math.max(1, dp(1)), 0x80FFFFFF);
          close.setBackground(closeBg);
          close.setStateListAnimator(null);
          close.setOnClickListener(v -> dialog.dismiss());
          panel.addView(close, new LinearLayout.LayoutParams(-1, dp(44)));
          dialog.setContentView(panel, new LinearLayout.LayoutParams(-1, dp(420)));
          showWide(dialog, 420);
      }

      /** 新建工程对话框（v3.28.7：修复输入法自动输入 bug + 玻璃圆角输入框美化） */
      private void showDnaCreateProject() {
          Dialog dialog = new Dialog(this);
          dialog.setCancelable(true);
          LinearLayout panel = new LinearLayout(this);
          panel.setOrientation(LinearLayout.VERTICAL);
          panel.setPadding(dp(20), dp(18), dp(20), dp(18));
          GradientDrawable bg = new GradientDrawable();
          bg.setColor(0xF6eef3f9);
          bg.setCornerRadius(dp(26));
          bg.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
          panel.setBackground(bg);
          // v3.28.7：标题行 —— 彩点 + 大标题 + 副说明，更醒目
          LinearLayout titleRow = new LinearLayout(this);
          titleRow.setOrientation(LinearLayout.HORIZONTAL);
          titleRow.setGravity(Gravity.CENTER_VERTICAL);
          View dot = new View(this);
          GradientDrawable dotBg = new GradientDrawable();
          dotBg.setShape(GradientDrawable.OVAL);
          dotBg.setColor(0xFF35A8C4);
          dot.setBackground(dotBg);
          titleRow.addView(dot, new LinearLayout.LayoutParams(dp(10), dp(10)));
          TextView title = new TextView(this);
          title.setText(t("新建工程", "New project"));
          title.setTextSize(18);
          title.setTypeface(null, 1);
          title.setTextColor(0xff12294a);
          LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-2, -2);
          titleLp.leftMargin = dp(8);
          titleRow.addView(title, titleLp);
          panel.addView(titleRow, new LinearLayout.LayoutParams(-1, -2));
          TextView hint = new TextView(this);
          hint.setText(t("工程名将添加 PDNA_ 前缀，创建于 /sdcard/PDNA/ 与 /data/PDNA/\n支持中英文、数字（特殊字符自动替换为 _）",
                  "Name gets PDNA_ prefix at /sdcard/PDNA/ and /data/PDNA/\nLetters, digits, CJK supported"));
          hint.setTextSize(11.5f);
          hint.setTextColor(0xff5a6b82);
          hint.setLineSpacing(dp(2), 1f);
          hint.setPadding(0, dp(6), 0, dp(10));
          panel.addView(hint, new LinearLayout.LayoutParams(-1, -2));
          // v3.28.7：玻璃圆角输入框（带描边，视觉更明显）
          EditText input = new EditText(this);
          input.setTextSize(15);
          input.setTextColor(0xff17334f);
          input.setHint(t("例如：我的工程 / MyROM", "e.g. MyROM"));
          input.setHintTextColor(0xff8fa1b8);
          input.setSingleLine(true);
          input.setGravity(Gravity.CENTER_VERTICAL);
          input.setPadding(dp(14), 0, dp(14), 0);
          GradientDrawable inputBg = new GradientDrawable();
          inputBg.setColor(0xFFFFFFFF);
          inputBg.setCornerRadius(dp(16));
          inputBg.setStroke(Math.max(1, dp(1)), 0x8035A8C4);
          input.setBackground(inputBg);
          // v3.28.7：移除字符级 InputFilter —— 与百度/三星等输入法组合时段落重组引发
          // “自动输入/文字重复”bug（juzjuzjuz…）；改为创建时统一清洗（createProject 内正则替换）
          panel.addView(input, new LinearLayout.LayoutParams(-1, dp(52)));
          Button create = new Button(this, null, 0);
          create.setText("✓  " + t("创建并使用", "Create & use"));
          create.setAllCaps(false);
          create.setTextSize(15);
          create.setTypeface(null, 1);
          create.setTextColor(Color.WHITE);
          create.setGravity(Gravity.CENTER);
          create.setPadding(0, 0, 0, 0);
          create.setBackgroundResource(R.drawable.button_green);
          create.setStateListAnimator(null);
          create.setOnClickListener(v -> {
              Haptics.perform(v);
              String name = input.getText().toString().trim();
              if (name.isEmpty()) {
                  Toast.makeText(this, t("请输入工程名", "Enter a name"), Toast.LENGTH_SHORT).show();
                  return;
              }
              kotlin.Pair<String, String> result = DnaTools.createProject(name);
              String created = result.getFirst();
              String error = result.getSecond();
              if (error == null && !created.isEmpty()) {
                  DnaTools.setCurrentProject(this, created);
                  refreshDnaProjectViews();
                  dialog.dismiss();
                  Toast.makeText(this, t("已创建工程", "Created") + ": " + created
                          + "\n/sdcard/PDNA/" + created + "  ·  /data/PDNA/" + created, Toast.LENGTH_LONG).show();
              } else {
                  Toast.makeText(this, t("创建失败", "Failed") + ": " + error, Toast.LENGTH_SHORT).show();
              }
          });
          LinearLayout.LayoutParams createLp = new LinearLayout.LayoutParams(-1, dp(46));
          createLp.topMargin = dp(10);
          panel.addView(create, createLp);
          dialog.setContentView(panel, new LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT));
          showWide(dialog, 380);
      }

      /** 删除工程对话框（多选） */
      private void showDnaDeleteProject() {
          java.util.List<String> projects = DnaTools.listProjects();
          if (projects.isEmpty()) {
              Toast.makeText(this, t("暂无工程", "No projects"), Toast.LENGTH_SHORT).show();
              return;
          }
          Dialog dialog = new Dialog(this);
          dialog.setCancelable(true);
          LinearLayout panel = new LinearLayout(this);
          panel.setOrientation(LinearLayout.VERTICAL);
          panel.setPadding(dp(18), dp(16), dp(18), dp(16));
          GradientDrawable bg = new GradientDrawable();
          bg.setColor(0xF2e9f0f7);
          bg.setCornerRadius(dp(24));
          bg.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
          panel.setBackground(bg);
          TextView title = new TextView(this);
          title.setText(t("删除工程（可多选）", "Delete projects (multi)"));
          title.setTextSize(16);
          title.setTypeface(null, 1);
          title.setTextColor(0xffa33b3b);
          title.setPadding(0, 0, 0, dp(10));
          panel.addView(title, new LinearLayout.LayoutParams(-1, -2));
          ScrollView listScroll = new ScrollView(this);
          LinearLayout list = new LinearLayout(this);
          list.setOrientation(LinearLayout.VERTICAL);
          listScroll.addView(list, new ScrollView.LayoutParams(-1, -2));
          java.util.List<android.widget.CheckBox> boxes = new java.util.ArrayList<>();
          for (String name : projects) {
              android.widget.CheckBox box = new CheckBox(this);
              box.setText(name);
              box.setTextSize(13.5f);
              box.setTextColor(0xff17334f);
              // v3.28.7：长工程名单行省略（修复文字溢出）
              box.setSingleLine(true);
              box.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
              box.setGravity(Gravity.CENTER_VERTICAL);
              boxes.add(box);
              list.addView(box, new LinearLayout.LayoutParams(-1, dp(40)));
          }
          panel.addView(listScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
          Button delete = new Button(this, null, 0);
          delete.setText(t("删除所选", "Delete selected"));
          delete.setAllCaps(false);
          delete.setTextColor(Color.WHITE);
          // v3.28.7：显式居中 + 零内边距
          delete.setGravity(Gravity.CENTER);
          delete.setPadding(0, 0, 0, 0);
          delete.setBackgroundResource(R.drawable.button_red);
          delete.setStateListAnimator(null);
          delete.setOnClickListener(v -> {
              Haptics.perform(v);
              String current = DnaTools.currentProject(this);
              int deleted = 0;
              for (android.widget.CheckBox box : boxes) {
                  if (box.isChecked()) {
                      String name = box.getText().toString();
                      if (DnaTools.deleteProject(name)) {
                          deleted++;
                          if (name.equals(current)) DnaTools.clearCurrentProject(this);
                      }
                  }
              }
              refreshDnaProjectViews();
              dialog.dismiss();
              Toast.makeText(this, t("已删除", "Deleted") + " " + deleted + t(" 个工程", " project(s)"), Toast.LENGTH_SHORT).show();
          });
          panel.addView(delete, new LinearLayout.LayoutParams(-1, dp(44)));
          dialog.setContentView(panel, new LinearLayout.LayoutParams(-1, dp(440)));
          showWide(dialog, 440);
      }

      /** 解压 ROM 对话框：工程根目录 zip 列表 + 手动路径（dna unzip → /sdcard/PDNA） */
      private void showDnaUnzipRom() {
          Dialog dialog = new Dialog(this);
          dialog.setCancelable(true);
          LinearLayout panel = new LinearLayout(this);
          panel.setOrientation(LinearLayout.VERTICAL);
          panel.setPadding(dp(18), dp(16), dp(18), dp(16));
          GradientDrawable bg = new GradientDrawable();
          bg.setColor(0xF2e9f0f7);
          bg.setCornerRadius(dp(24));
          bg.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
          panel.setBackground(bg);
          // ---- 标题行：渐变图标徽章 + 标题 + 副标题（v3.30.11 美化）----
          LinearLayout titleRow = new LinearLayout(this);
          titleRow.setOrientation(LinearLayout.HORIZONTAL);
          titleRow.setGravity(Gravity.CENTER_VERTICAL);
          FrameLayout badge = new FrameLayout(this);
          GradientDrawable badgeBg = new GradientDrawable();
          badgeBg.setShape(GradientDrawable.OVAL);
          badgeBg.setOrientation(GradientDrawable.Orientation.TL_BR);
          badgeBg.setColors(new int[]{0xFF35A8C4, 0xFF0E7D95});
          badgeBg.setStroke(Math.max(1, dp(1)), 0xB3FFFFFF);
          badge.setBackground(badgeBg);
          TextView badgeIcon = new TextView(this);
          badgeIcon.setText("📦");
          badgeIcon.setTextSize(17);
          badgeIcon.setGravity(Gravity.CENTER);
          badge.addView(badgeIcon, new FrameLayout.LayoutParams(-1, -1));
          titleRow.addView(badge, new LinearLayout.LayoutParams(dp(42), dp(42)));
          LinearLayout titleBox = new LinearLayout(this);
          titleBox.setOrientation(LinearLayout.VERTICAL);
          titleBox.setPadding(dp(12), 0, 0, 0);
          TextView title = new TextView(this);
          title.setText(t("解压 ROM", "Unzip ROM"));
          title.setTextSize(16.5f);
          title.setTypeface(null, 1);
          title.setTextColor(0xff17334f);
          titleBox.addView(title, new LinearLayout.LayoutParams(-1, -2));
          TextView subtitle = new TextView(this);
          subtitle.setText(t("解压 zip 自动创建 DNA_ 新工程", "Unzip zip, auto-create a DNA_ project"));
          subtitle.setTextSize(11.5f);
          subtitle.setTextColor(0xff5a6b82);
          LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
          subLp.topMargin = dp(2);
          titleBox.addView(subtitle, subLp);
          titleRow.addView(titleBox, new LinearLayout.LayoutParams(0, -2, 1f));
          panel.addView(titleRow, new LinearLayout.LayoutParams(-1, -2));
          // ---- zip 列表（玻璃行 + 选中 ✓ 高亮）----
          ScrollView listScroll = new ScrollView(this);
          LinearLayout list = new LinearLayout(this);
          list.setOrientation(LinearLayout.VERTICAL);
          listScroll.addView(list, new ScrollView.LayoutParams(-1, -2));
          java.util.List<String> zips = DnaTools.listProjectFiles(null, "zip");
          // v3.30.14：同时列出当前工程内的 zip（原版 findfile.sh zip1 只列 PDNA 根，工程内 zip 之前只能手动输入）
          java.util.List<String> projZips = new java.util.ArrayList<>();
          String curProj = DnaTools.currentProject(this);
          if (curProj != null) {
              for (String n : DnaTools.listProjectFiles(curProj, "zip")) {
                  if (!zips.contains(n)) projZips.add(n);
              }
          }
          final String[] chosen = {null};
          if (zips.isEmpty() && projZips.isEmpty()) {
              TextView empty = new TextView(this);
              empty.setText(t("/sdcard/PDNA 下没有 zip，请在下方输入完整路径", "No zip in /sdcard/PDNA, enter full path below"));
              empty.setTextSize(12.5f);
              empty.setTextColor(0xff5a6b82);
              empty.setPadding(0, dp(6), 0, dp(6));
              list.addView(empty, new LinearLayout.LayoutParams(-1, -2));
          }
          // v3.30.14：合并渲染 根目录 zip + 工程 zip（工程行显示 工程/前缀，tag 存完整路径）
          java.util.List<String[]> allZips = new java.util.ArrayList<>();
          for (String name : zips) allZips.add(new String[]{"🗂 " + name, DnaTools.WORK_ROOT + "/" + name});
          if (curProj != null) {
              for (String name : projZips) allZips.add(new String[]{"📂 " + curProj + "/" + name,
                      DnaTools.WORK_ROOT + "/" + curProj + "/" + name});
          }
          for (String[] entry : allZips) {
              final String disp = entry[0];
              final String fullPath = entry[1];
              LinearLayout row = new LinearLayout(this);
              row.setOrientation(LinearLayout.HORIZONTAL);
              row.setGravity(Gravity.CENTER_VERTICAL);
              row.setPadding(dp(11), dp(11), dp(11), dp(11));
              GradientDrawable rowBg = new GradientDrawable();
              rowBg.setColor(0x22FFFFFF);
              rowBg.setCornerRadius(dp(14));
              rowBg.setStroke(Math.max(1, dp(1)), 0x33FFFFFF);
              row.setBackground(rowBg);
              row.setTag(fullPath);
              TextView fileName = new TextView(this);
              fileName.setText(disp);
              fileName.setTextSize(13.5f);
              fileName.setTextColor(0xff17334f);
              // v3.28.7：长文件名单行省略（修复弹窗文字溢出）
              fileName.setSingleLine(true);
              fileName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
              row.addView(fileName, new LinearLayout.LayoutParams(0, -2, 1f));
              TextView check = new TextView(this);
              check.setText("✓");
              check.setTextSize(15);
              check.setTypeface(null, 1);
              check.setTextColor(0xff1f7d72);
              check.setVisibility(View.GONE);
              row.addView(check, new LinearLayout.LayoutParams(-2, -2));
              LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
              rowLp.bottomMargin = dp(6);
              list.addView(row, rowLp);
              row.setOnClickListener(v -> {
                  Haptics.perform(v);
                  chosen[0] = fullPath;
                  for (int i = 0; i < list.getChildCount(); i++) {
                      View child = list.getChildAt(i);
                      if (child instanceof LinearLayout && child.getTag() instanceof String) {
                          GradientDrawable rb = new GradientDrawable();
                          rb.setColor(0x22FFFFFF);
                          rb.setCornerRadius(dp(14));
                          rb.setStroke(Math.max(1, dp(1)), 0x33FFFFFF);
                          child.setBackground(rb);
                          ((LinearLayout) child).getChildAt(1).setVisibility(View.GONE);
                      }
                  }
                  GradientDrawable sel = new GradientDrawable();
                  sel.setColor(0x3335A8C4);
                  sel.setCornerRadius(dp(14));
                  sel.setStroke(Math.max(1, dp(1)), 0xFF35A8C4);
                  row.setBackground(sel);
                  check.setVisibility(View.VISIBLE);
              });
          }
          LinearLayout.LayoutParams lsLp = new LinearLayout.LayoutParams(-1, 0, 1f);
          lsLp.topMargin = dp(10);
          panel.addView(listScroll, lsLp);
          // ---- 手动路径输入（圆角玻璃框）+ 浏览按钮（v3.30.15：内置文件浏览器）----
          LinearLayout manRow = new LinearLayout(this);
          manRow.setOrientation(LinearLayout.HORIZONTAL);
          manRow.setGravity(Gravity.CENTER_VERTICAL);
          EditText manual = new EditText(this);
          manual.setTextSize(13f);
          manual.setTextColor(0xff17334f);
          manual.setHint(t("或输入 zip 绝对路径", "Or absolute zip path"));
          manual.setHintTextColor(0xff8fa1b8);
          // v3.28.6：垂直居中（修复文字与框不居中）
          manual.setGravity(Gravity.CENTER_VERTICAL);
          manual.setPadding(dp(12), 0, dp(12), 0);
          GradientDrawable inputBg = new GradientDrawable();
          inputBg.setColor(0x66FFFFFF);
          inputBg.setCornerRadius(dp(14));
          inputBg.setStroke(Math.max(1, dp(1)), 0x4DFFFFFF);
          manual.setBackground(inputBg);
          manRow.addView(manual, new LinearLayout.LayoutParams(0, dp(46), 1f));
          Button browse = new Button(this, null, 0);
          browse.setText("📂");
          browse.setTextSize(15);
          browse.setAllCaps(false);
          browse.setMinWidth(0);
          browse.setMinHeight(0);
          browse.setGravity(Gravity.CENTER);
          browse.setPadding(0, 0, 0, 0);
          browse.setTextColor(0xff172b4d);
          GradientDrawable browseBg = new GradientDrawable();
          browseBg.setColor(0x59FFFFFF);
          browseBg.setCornerRadius(dp(14));
          browseBg.setStroke(Math.max(1, dp(1)), 0x80FFFFFF);
          browse.setBackground(browseBg);
          browse.setStateListAnimator(null);
          browse.setOnClickListener(v -> {
              Haptics.perform(v);
              FileBrowserDialog.show(this, t("选择 ROM 压缩包", "Select ROM zip"),
                      new String[]{".zip", ".zip2"}, "/storage/emulated/0",
                      path -> manual.setText(path));
          });
          LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(dp(46), dp(46));
          brLp.leftMargin = dp(6);
          manRow.addView(browse, brLp);
          LinearLayout.LayoutParams manLp = new LinearLayout.LayoutParams(-1, -2);
          manLp.topMargin = dp(4);
          panel.addView(manRow, manLp);
          // ---- 目标路径提示条 ----
          // v3.30.16 修复：对齐原版 home.sh「dna unzip --delete $silence $DNA_DIR/$ZIP $DNA_DIR」，
          // 目标始终是工程根目录（$DNA_DIR），dna 会自动在根目录创建 DNA_<zip名> 新工程；
          // 之前解压到当前工程目录里是错误的（新工程被嵌进旧工程，且不参与工程名读取）
          String target = DnaTools.WORK_ROOT;
          TextView targetHint = new TextView(this);
          targetHint.setText("➜ " + t("解压到 ", "Extract to ") + target
                  + t("（自动创建 DNA_ 新工程）", " (auto-create DNA_ project)"));
          targetHint.setTextSize(11f);
          targetHint.setTextColor(0xff2f9c8f);
          targetHint.setSingleLine(true);
          targetHint.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
          targetHint.setPadding(dp(2), dp(8), dp(2), 0);
          panel.addView(targetHint, new LinearLayout.LayoutParams(-1, -2));
          // ---- 删除源文件开关（原版 home.sh：dna unzip --delete $silence，checkbox 值 0/1）----
          CheckBox deleteSource = new CheckBox(this);
          deleteSource.setText(t("解压后删除源 zip 文件", "Delete source zip after unzip"));
          deleteSource.setTextSize(12.5f);
          deleteSource.setTextColor(0xff17334f);
          deleteSource.setPadding(dp(2), 0, 0, 0);
          panel.addView(deleteSource, new LinearLayout.LayoutParams(-1, -2));
          // ---- 按钮行 ----
          LinearLayout btnRow = new LinearLayout(this);
          btnRow.setOrientation(LinearLayout.HORIZONTAL);
          btnRow.setPadding(0, dp(10), 0, 0);
          Button cancel = new Button(this, null, 0);
          cancel.setText(t("取消", "Cancel"));
          cancel.setAllCaps(false);
          cancel.setTextColor(0xff172b4d);
          // v3.28.7：显式居中 + 零内边距
          cancel.setGravity(Gravity.CENTER);
          cancel.setPadding(0, 0, 0, 0);
          GradientDrawable cancelBg = new GradientDrawable();
          cancelBg.setColor(0x59FFFFFF);
          cancelBg.setCornerRadius(dp(16));
          cancelBg.setStroke(Math.max(1, dp(1)), 0x80FFFFFF);
          cancel.setBackground(cancelBg);
          cancel.setStateListAnimator(null);
          cancel.setOnClickListener(v -> dialog.dismiss());
          btnRow.addView(cancel, new LinearLayout.LayoutParams(0, dp(46), 1f));
          Button start = new Button(this, null, 0);
          start.setText(t("开始解压", "Unzip"));
          start.setAllCaps(false);
          start.setTextColor(Color.WHITE);
          // v3.28.7：显式居中 + 零内边距
          start.setGravity(Gravity.CENTER);
          start.setPadding(0, 0, 0, 0);
          start.setBackgroundResource(R.drawable.button_green);
          start.setStateListAnimator(null);
          LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(0, dp(46), 1f);
          startLp.leftMargin = dp(8);
          btnRow.addView(start, startLp);
          panel.addView(btnRow, new LinearLayout.LayoutParams(-1, -2));
          start.setOnClickListener(v -> {
              Haptics.perform(v);
              String zip = !manual.getText().toString().trim().isEmpty()
                      ? manual.getText().toString().trim() : chosen[0];
              if (zip == null || zip.isEmpty()) {
                  Toast.makeText(this, t("请选择或输入 zip 路径", "Pick or enter a zip path"), Toast.LENGTH_SHORT).show();
                  return;
              }
              dialog.dismiss();
              // v3.30.16：解压目标固定为工程根目录，dna 自动创建 DNA_<zip名> 新工程（对齐原版 home.sh）
              // v3.30.14 修复：原版 home.sh 是 --delete $silence（checkbox 值 0/1），
              // 之前传 "false" 导致 dna 参数解析失败 → 立即退出码
              runDnaConsole(t("解压 ROM", "Unzip ROM"),
                      "dna unzip --delete " + (deleteSource.isChecked() ? "1" : "0") + " "
                              + DnaTools.quote(zip) + " " + DnaTools.quote(target));
          });
          dialog.setContentView(panel, new LinearLayout.LayoutParams(-1, dp(460)));
          showWide(dialog, 460);
      }

      /** v3.28.4：DNA 弹窗统一加宽到 94% 屏宽（修复"弹窗太窄"） */
      private void showWide(Dialog dialog, int heightDp) {
          dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
          dialog.show();
          dialog.getWindow().setLayout(
                  (int) (getResources().getDisplayMetrics().widthPixels * 0.94f), dp(heightDp));
      }

      /** DNA 命令控制台对话框：流式输出 + 取消（解压 ROM 等页面级任务） */
      private void runDnaConsole(String titleText, String command) {
          Dialog dialog = new Dialog(this);
          dialog.setCancelable(false);
          LinearLayout panel = new LinearLayout(this);
          panel.setOrientation(LinearLayout.VERTICAL);
          panel.setPadding(dp(16), dp(14), dp(16), dp(14));
          GradientDrawable bg = new GradientDrawable();
          // v3.30.28：控制台弹窗换浅色（对齐解压ROM页面，弃用黑色终端风）
          bg.setColor(0xF2e9f0f7);
          bg.setCornerRadius(dp(22));
          bg.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
          panel.setBackground(bg);
          // 标题行 + 复制日志按钮（v3.28.4）
          TextView console = new TextView(this);
          console.setTypeface(android.graphics.Typeface.MONOSPACE);
          console.setTextSize(11.5f);
          console.setTextColor(0xff2c3e57);
          console.setPadding(dp(8), dp(8), dp(8), dp(8));
          LinearLayout consoleHead = new LinearLayout(this);
          consoleHead.setOrientation(LinearLayout.HORIZONTAL);
          consoleHead.setGravity(Gravity.CENTER_VERTICAL);
          TextView title = new TextView(this);
          title.setText(titleText);
          title.setTextSize(15);
          title.setTypeface(null, 1);
          title.setTextColor(0xff17334f);
          consoleHead.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
          Button copyConsole = new Button(this, null, 0);
          copyConsole.setText("⧉ " + t("复制日志", "Copy"));
          copyConsole.setAllCaps(false);
          copyConsole.setTextSize(11.5f);
          copyConsole.setTypeface(null, 1);
          copyConsole.setTextColor(0xff1f7d72);
          copyConsole.setMinWidth(0);
          copyConsole.setMinHeight(0);
          // v3.28.7：显式居中
          copyConsole.setGravity(Gravity.CENTER);
          copyConsole.setPadding(dp(10), 0, dp(10), 0);
          GradientDrawable copyBg = new GradientDrawable();
          copyBg.setColor(0x55FFFFFF);
          copyBg.setCornerRadius(dp(15));
          copyBg.setStroke(Math.max(1, dp(1)), 0x55FFFFFF);
          copyConsole.setBackground(copyBg);
          copyConsole.setStateListAnimator(null);
          copyConsole.setOnClickListener(v -> {
              Haptics.perform(v);
              CharSequence text = console.getText();
              if (text.length() == 0) {
                  toast(t("暂无日志", "Nothing to copy"));
                  return;
              }
              android.content.ClipboardManager cm =
                      (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
              cm.setPrimaryClip(android.content.ClipData.newPlainText("DNA log", text));
              toast(t("已复制全部日志", "Log copied"));
          });
          consoleHead.addView(copyConsole, new LinearLayout.LayoutParams(-2, dp(30)));
          // v3.28.6：标题行与下方日志框保持距离感（6→10dp）
          consoleHead.setPadding(0, 0, 0, dp(10));
          panel.addView(consoleHead, new LinearLayout.LayoutParams(-1, -2));
          ScrollView scroll = new ScrollView(this);
          scroll.setOnTouchListener((sv, event) -> {
              int action = event.getActionMasked();
              sv.getParent().requestDisallowInterceptTouchEvent(
                      action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL);
              return false;
          });
          scroll.addView(console, new ScrollView.LayoutParams(-1, -2));
          panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
          Button stop = new Button(this, null, 0);
          stop.setText(t("后台运行 / 关闭", "Background / Close"));
          stop.setAllCaps(false);
          stop.setTextColor(0xff17334f);
          // v3.28.7：显式居中 + 零内边距
          stop.setGravity(Gravity.CENTER);
          stop.setPadding(0, 0, 0, 0);
          GradientDrawable stopBg = new GradientDrawable();
          stopBg.setColor(0x55FFFFFF);
          stopBg.setCornerRadius(dp(16));
          stopBg.setStroke(Math.max(1, dp(1)), 0x55FFFFFF);
          stop.setBackground(stopBg);
          stop.setStateListAnimator(null);
          stop.setOnClickListener(v -> {
              Haptics.perform(v);
              dialog.dismiss();
          });
          panel.addView(stop, new LinearLayout.LayoutParams(-1, dp(44)));
          dialog.setContentView(panel, new LinearLayout.LayoutParams(-1, dp(500)));
          showWide(dialog, 500);
          final java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean(false);
          new Thread(() -> {
              DnaTools.Result result = DnaTools.run(this, command,
                      line -> {
                          runOnUiThread(() -> {
                              console.append(line + "\n");
                              scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
                          });
                          return kotlin.Unit.INSTANCE;
                      },
                      cancel::get);
              runOnUiThread(() -> {
                  console.append((result.getSuccess() ? "\n✓ " : "\n✗ ") + result.getMessage() + "\n");
                  Toast.makeText(this, result.getSuccess() ? t("解压完成", "Done") : t("执行失败", "Failed"), Toast.LENGTH_SHORT).show();
              });
          }, "dna-console").start();
      }


      private void animateNavigation(int selectedTab) {
         if (bottomNavigation == null) return;
         moveLiquidIndicator(selectedTab, true);
         updateNavigationLabels(selectedTab);
      }

      private void updateNavigationLabels(int selectedTab) {
            ViewGroup items = bottomNavigationItems;
         if (items == null) return;
         for (int i = 0; i < items.getChildCount(); i++) {
              View item = items.getChildAt(i);
              boolean selected = i == selectedTab;
                  if (item instanceof TextView) {
                      TextView label = (TextView) item;
                      label.setTextColor(selected ? 0xff17334f : 0xfff8fbff);
                      label.setShadowLayer(dp(2), 0, dp(1), selected ? 0x66ffffff : 0x66233c50);
                      // 添加每个按钮独立的文字缩放动画
                      animateNavigationButtonScale(label, selected);
                  }
          }
     }
     
     // MainActivity 底部导航按钮独立的文字缩放动画
     private void animateNavigationButtonScale(TextView button, boolean selected) {
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

       private void moveLiquidIndicator(int selectedTab, boolean animated) {
          if (liquidIndicator == null || bottomNavigation == null) return;
          ViewGroup items = bottomNavigationItems;
          if (items.getChildCount() == 0) return;
          View target = items.getChildAt(selectedTab);
            int indicatorHeight = dp(48);
            int indicatorWidth = dp(62);
            int targetLeft = items.getLeft() + target.getLeft() + (target.getWidth() - indicatorWidth) / 2;
           FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) liquidIndicator.getLayoutParams();
            params.width = indicatorWidth;
            params.height = indicatorHeight;
              // 滑块精确垂直居中：导航栏 64dp + 上下 2dp 内边距，滑块 48dp
              // topMargin = (64 - 2*2 - 48) / 2 = 6dp → 滑块中心(32dp)与文字中心完全重合
              params.topMargin = dp(6);
           if (liquidIndicatorLeft < 0 || !animated) {
              liquidIndicatorLeft = targetLeft;
               params.leftMargin = targetLeft;
               liquidIndicator.setTranslationX(0f);
               liquidIndicator.setLayoutParams(params);
              return;
          }
          if (liquidNavigationFlow != null) {
              ValueAnimator previousFlow = liquidNavigationFlow;
              liquidNavigationFlow = null;
              previousFlow.cancel();
          }
           int previousLeft = liquidIndicatorLeft;
            params.leftMargin = previousLeft;
          liquidIndicator.setTranslationX(0f);
           liquidIndicator.setLayoutParams(params);
           liquidIndicatorLeft = targetLeft;
           if (previousLeft == targetLeft) {
               liquidIndicator.setScaleX(1f);
               liquidIndicator.setScaleY(1f);
               if (liquidIndicator instanceof LiquidGlassIndicator) {
                   ((LiquidGlassIndicator) liquidIndicator).setLiquidPressed(false);
               }
               return;
           }
            float direction = targetLeft >= previousLeft ? 1f : -1f;
            liquidIndicator.setPivotX(indicatorWidth / 2f);
             liquidIndicator.setPivotY(indicatorHeight / 2f);
           ValueAnimator flow = ValueAnimator.ofFloat(0f, 1f);
           liquidNavigationFlow = flow;
              flow.setDuration(620);
             flow.setInterpolator(new android.view.animation.PathInterpolator(.18f, .78f, .22f, 1f));
            flow.addUpdateListener(animation -> {
                float progress = (Float) animation.getAnimatedValue();
                float move = targetLeft - previousLeft;
                 float stretch = progress < .24f ? progress / .24f : progress < .48f ? 1f : progress < .80f ? 1f - (progress - .48f) / .32f : 0f;
                 float settle = progress < .80f ? 0f : (progress - .80f) / .20f;
                 float travel = progress < .43f ? 0f : (progress - .43f) / .57f;
                 liquidIndicator.setTranslationX(move * travel);
                 liquidIndicator.setScaleX(1f + 1.18f * stretch + .07f * settle);
                  liquidIndicator.setScaleY(1f - .07f * stretch + .025f * settle);
            });
           flow.addListener(new android.animation.AnimatorListenerAdapter() {
               @Override public void onAnimationEnd(android.animation.Animator animation) {
                   if (animation != liquidNavigationFlow) return;
                   FrameLayout.LayoutParams settled = (FrameLayout.LayoutParams) liquidIndicator.getLayoutParams();
                    settled.leftMargin = targetLeft;
                    liquidIndicator.setTranslationX(0f);
                    liquidIndicator.setScaleX(1f);
                     liquidIndicator.setScaleY(1f);
                    liquidIndicator.setPivotX(indicatorWidth / 2f);
                    liquidIndicator.setLayoutParams(settled);
                    if (liquidIndicator instanceof LiquidGlassIndicator) {
                        ((LiquidGlassIndicator) liquidIndicator).setLiquidPressed(false);
                    }
                    liquidNavigationFlow = null;
               }
           });
           pulseBottomNavigation();
           flow.start();
       }

       private LinearLayout buildRomPage() {
           LinearLayout page = new LinearLayout(this);
           page.setOrientation(LinearLayout.VERTICAL);
           page.setPadding(dp(18), dp(22), dp(18), dp(30));
           // 标题行：ROM 更新中心（左）+ 下载管理入口（右，图二）
           LinearLayout titleRow = new LinearLayout(this);
           titleRow.setOrientation(LinearLayout.HORIZONTAL);
           titleRow.setGravity(Gravity.CENTER_VERTICAL);
           TextView title = text(t("ROM 更新中心", "ROM Update Center"), 26, Color.WHITE);
           title.setTypeface(null, 1);
           titleRow.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
           Button downloadManager = new Button(this, null, 0);
           downloadManager.setText(t("⬇ 下载管理", "⬇ Downloads"));
           downloadManager.setAllCaps(false);
           downloadManager.setTextSize(13.5f);
           downloadManager.setTypeface(null, 1);
           downloadManager.setTextColor(Color.WHITE);
           downloadManager.setGravity(Gravity.CENTER);
           downloadManager.setPadding(dp(18), 0, dp(18), 0);
           downloadManager.setMinWidth(0);
           downloadManager.setMinHeight(0);
           downloadManager.setIncludeFontPadding(false);
           downloadManager.setStateListAnimator(null);
           // v3.8.2 修复：白色半透明玻璃底上白字看不清 → 蓝绿色实底渐变胶囊 + 白色粗体 + 白描边高光
           GradientDrawable dlMgrBg = new GradientDrawable();
           dlMgrBg.setOrientation(GradientDrawable.Orientation.TL_BR);
           dlMgrBg.setColors(new int[]{0xFF35A8C4, 0xFF0E7D95, 0xFF0A5F75});
           dlMgrBg.setCornerRadius(dp(22));
           dlMgrBg.setStroke(Math.max(1, dp(2)), 0xE6FFFFFF);
           downloadManager.setBackground(dlMgrBg);
           downloadManager.setElevation(dp(6));
           downloadManager.setOnClickListener(v -> {
               Haptics.perform(v);
               startActivity(new Intent(this, DownloadManagerActivity.class));
               // 下载管理：缩放 + 淡入转场
               overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out);
           });
           titleRow.addView(downloadManager, new LinearLayout.LayoutParams(-2, dp(44)));
           page.addView(titleRow, new LinearLayout.LayoutParams(-1, dp(48)));
           TextView subtitle = text(t("网盘解析与各品牌 ROM 工具", "Netdisk parsing and ROM tools"), 13, 0xffe5edf7);
            page.addView(subtitle, new LinearLayout.LayoutParams(-1, dp(34)));
             page.addView(romVendorCard("网盘分享解析", "多网盘链接解析 · aria2c 多线程下载", R.drawable.vendor_glass_yunx, "yunx"));
             page.addView(romVendorCard("小米 / Redmi / POCO", "HyperOS · Recovery / Fastboot · 多地区版本", R.drawable.vendor_glass_blue, "xiaomi"));
             page.addView(romVendorCard("vivo", "OriginOS · 官方系统更新查询", R.drawable.vendor_glass_purple, "vivo"));
              page.addView(romVendorCard("OPPO / 一加 / 真我", "ColorOS · 官方系统更新查询", R.drawable.vendor_glass_orange, "oppo"));
             page.addView(romVendorCard("提取镜像", "在线直链 / 本地 ROM 包 · payload.bin 分区镜像提取", R.drawable.vendor_glass_green, "extract"));
             page.addView(romVendorCard("OTA 合并工具", "通用增量包合并 · 小米 / OPPO / vivo 等全机型支持", R.drawable.vendor_glass_cyan, "otamerge"));
             return page;
        }

       private View romVendorCard(String heading, String detail, int glassBackground, String vendorId) {
           LinearLayout card = new LinearLayout(this);
           card.setOrientation(LinearLayout.VERTICAL);
           card.setPadding(dp(18), dp(16), dp(18), dp(16));
           // 品牌液态玻璃框（同首页 logo 框结构：品牌渐变底 + 白描边 + 顶部高光）
           card.setBackgroundResource(glassBackground);
           card.setElevation(dp(7));
           TextView title = text(heading, 19, Color.WHITE);
           title.setTypeface(null, 1);
           card.addView(title, new LinearLayout.LayoutParams(-1, dp(34)));
           TextView desc = text(detail, 13, 0xE6FFFFFF);
           card.addView(desc, new LinearLayout.LayoutParams(-1, dp(30)));
           Button enter = new Button(this, null, 0);
           enter.setText(t("进入查询", "Open lookup"));
           enter.setAllCaps(false);
           enter.setTextSize(14.5f);
           enter.setGravity(Gravity.CENTER);
           enter.setPadding(0, 0, 0, 0);
           enter.setMinWidth(0);
           enter.setMinHeight(0);
           enter.setIncludeFontPadding(false);
           enter.setTextColor(0xff172b4d);
           enter.setStateListAnimator(null);
           // 白色玻璃胶囊按钮（深色玻璃框上的高对比主操作）
           GradientDrawable enterBg = new GradientDrawable();
           enterBg.setOrientation(GradientDrawable.Orientation.TL_BR);
           enterBg.setColors(new int[]{0xFFFFFFFF, 0xE6E2E8F0});
           enterBg.setCornerRadius(dp(23));
           enterBg.setStroke(Math.max(1, dp(1)), 0xFFFFFFFF);
           enter.setBackground(enterBg);
           enter.setElevation(dp(5));
              enter.setOnClickListener(v -> {
                  Haptics.perform(v);
                   Intent intent = new Intent(this,
                           "oppo".equals(vendorId) ? OPlusLookupActivity.class
                                   : "vivo".equals(vendorId) ? VivoActivity.class
                                   : "extract".equals(vendorId) ? PayloadDumperActivity.class
                                   : "otamerge".equals(vendorId) ? OtaMergeActivity.class
                                   : "yunx".equals(vendorId) ? com.yunx.app.MainActivity.class
                                   : RomActivity.class);
                  if (!"extract".equals(vendorId) && !"otamerge".equals(vendorId) && !"yunx".equals(vendorId)) intent.putExtra("vendor", vendorId);
                  startActivity(intent);
                  // 不同厂商使用不同的炸裂转场动画
                  if ("oppo".equals(vendorId)) {
                     // OPPO: 爆炸式缩放 + 旋转进入
                     overridePendingTransition(R.anim.explode_in, R.anim.explode_out);
                 } else if ("vivo".equals(vendorId)) {
                    // Vivo: 3D 翻转进入
                    overridePendingTransition(R.anim.flip_in, R.anim.flip_out);
                } else if ("extract".equals(vendorId)) {
                    // 提取镜像: 底部滑入
                    overridePendingTransition(R.anim.slide_up_in, R.anim.slide_up_out);
                } else if ("otamerge".equals(vendorId)) {
                    // OTA合并: 缩放淡入
                    overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out);
                } else if ("yunx".equals(vendorId)) {
                    // 网盘解析：底部滑入
                    overridePendingTransition(R.anim.slide_up_in, R.anim.slide_up_out);
                } else {
                    // 小米: 缩放 + 淡入
                    overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out);
                }
            });
           card.addView(enter, new LinearLayout.LayoutParams(-1, dp(46)));
           LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
           lp.setMargins(0, 0, 0, dp(12));
           card.setOnClickListener(v -> enter.performClick());
           return addRomCardMargin(card, lp);
       }

       private View addRomCardMargin(View card, LinearLayout.LayoutParams lp) {
           card.setLayoutParams(lp);
           return card;
       }

        private void pulseBottomNavigation() {
           if (bottomNavigation == null) return;
           if (bottomNavigationPulse != null) {
               ValueAnimator previousPulse = bottomNavigationPulse;
               bottomNavigationPulse = null;
               previousPulse.cancel();
           }
           bottomNavigation.setPivotX(bottomNavigation.getWidth() / 2f);
           bottomNavigation.setPivotY(bottomNavigation.getHeight() / 2f);
           ValueAnimator pulse = ValueAnimator.ofFloat(0f, 1f);
           bottomNavigationPulse = pulse;
           pulse.setDuration(840);
           pulse.setInterpolator(new android.view.animation.PathInterpolator(.22f, .76f, .26f, 1f));
           pulse.addUpdateListener(animation -> {
               float progress = (Float) animation.getAnimatedValue();
               float swell = progress < .28f ? progress / .28f : progress < .55f ? 1f : 1f - (progress - .55f) / .45f;
               bottomNavigation.setScaleX(1f + .032f * swell);
               bottomNavigation.setScaleY(1f + .064f * swell);
           });
           pulse.addListener(new android.animation.AnimatorListenerAdapter() {
               @Override public void onAnimationEnd(android.animation.Animator animation) {
                   if (animation != bottomNavigationPulse) return;
                   bottomNavigation.setScaleX(1f);
                   bottomNavigation.setScaleY(1f);
                   bottomNavigationPulse = null;
               }
           });
            pulse.start();
        }

     private void applySystemInsets(View root, android.view.WindowInsets insets) {
         if (insets == null) return;
         int top = insets.getSystemWindowInsetTop();
         int bottom = insets.getSystemWindowInsetBottom();
         root.setPadding(0, top, 0, 0);
         if (bottomNavigation != null) {
             ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) bottomNavigation.getLayoutParams();
             margins.bottomMargin = dp(14) + bottom;
             bottomNavigation.setLayoutParams(margins);
         }
     }

     /**
      * v3.9.7：把当前聚焦的输入框滚动到键盘可见区域之上（含 14dp 余量）。
      * edge-to-edge 下窗口不缩放，需按「窗口坐标 - 键盘顶部」手动计算滚动量；
      * 配合 content 底部内边距随 IME 增大，ScrollView 有足够滚动余量。
      */
     private void revealFocusedInputAboveKeyboard(int visibleBottomInWindow) {
         View focused = getCurrentFocus();
         if (focused == null) return;
         ViewParent parent = focused.getParent();
         while (parent != null) {
             if (parent instanceof ScrollView) {
                 ScrollView scroll = (ScrollView) parent;
                 int[] location = new int[2];
                 focused.getLocationInWindow(location);
                 int focusedBottom = location[1] + focused.getHeight() + dp(14);
                 int overlap = focusedBottom - visibleBottomInWindow;
                 if (overlap > 0)
                     scroll.smoothScrollTo(0, Math.max(0, scroll.getScrollY() + overlap));
                 return;
             }
             parent = parent.getParent();
         }
     }

     private LinearLayout page(String title) {
         LinearLayout page = new LinearLayout(this);
         page.setOrientation(LinearLayout.VERTICAL);
         page.setPadding(dp(18), dp(14), dp(18), dp(96));
          // 背景由 Activity 根容器绘制一次；该滚动子页透明继承根色域。
         page.setOnApplyWindowInsetsListener((view, insets) -> {
             view.setPadding(dp(18), dp(14) + insets.getSystemWindowInsetTop(), dp(18), dp(96) + insets.getSystemWindowInsetBottom());
             return insets;
         });
          TextView heading = text(title, 28, Color.WHITE);
         heading.setTypeface(null, 1);
         heading.setPadding(dp(14), 0, dp(14), 0);
         page.addView(heading, new LinearLayout.LayoutParams(-1, dp(58)));
          return page;
       }

      private LinearLayout buildOtgPage() {
          LinearLayout content = new LinearLayout(this);
          content.setOrientation(LinearLayout.VERTICAL);
          content.setPadding(dp(18), dp(10), dp(18), dp(22));
          // 背景由 Activity 根容器绘制一次，避免子页再次铺底导致角落错位。

          // 顶部标题
          LinearLayout title = new LiquidGlassPanel(this, 14f);
          title.setOrientation(LinearLayout.VERTICAL);
          title.setGravity(Gravity.CENTER_VERTICAL);
          title.setPadding(dp(14), dp(8), dp(14), dp(8));
          TextView heading = new TextView(this);
          heading.setText(t("OTG 工具箱", "OTG Toolbox"));
          heading.setTextSize(22);
          heading.setTextColor(0xff142037);
          heading.setTypeface(null, 1);
          heading.setGravity(Gravity.CENTER);
          title.addView(heading, new LinearLayout.LayoutParams(-1, dp(32)));
          TextView subtitle = new TextView(this);
          subtitle.setText(t("本机分区 / 刷机助手", "Partitions / Flash Tool"));
          subtitle.setTextSize(11);
          subtitle.setTextColor(0xff2d4a66);
          subtitle.setGravity(Gravity.CENTER);
          title.addView(subtitle, new LinearLayout.LayoutParams(-1, dp(32)));
          LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, dp(82));
          titleLp.setMargins(0, 0, 0, dp(10));
          content.addView(title, titleLp);

          // Tab 选择器（完全复制 OPlusOtaActivity 的实现）
          FrameLayout tabs = new FrameLayout(this);
          tabs.setPadding(dp(2), dp(2), dp(2), dp(2));
          tabs.setBackgroundResource(R.drawable.navigation_glass_bg);
          otgTabItems = new LinearLayout(this);
          otgTabItems.setOrientation(LinearLayout.HORIZONTAL);
          otgTabItems.setGravity(Gravity.CENTER);
          otgTabItems.setClipChildren(false);
          tabs.addView(otgTabItems, new FrameLayout.LayoutParams(-1, dp(64)));
          
          String[] tabLabels = {t("本机分区", "Partitions"), t("刷机助手", "Flash Tool")};
          otgTabs = new Button[tabLabels.length];
          for (int i = 0; i < tabLabels.length; i++) {
              final int index = i;
              otgTabs[i] = new Button(this);
              otgTabs[i].setText(tabLabels[i]);
              otgTabs[i].setTextSize(12);
              otgTabs[i].setGravity(Gravity.CENTER);
              otgTabs[i].setTextColor(i == 0 ? 0xff17334f : 0xfff8fbff);
              otgTabs[i].setBackgroundColor(Color.TRANSPARENT);
              otgTabs[i].setPadding(0, 0, 0, 0);
              otgTabs[i].setOnTouchListener((v, event) -> handleOtgTabGesture(v, event, index));
              LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(0, dp(64), 1);
              tabLp.setMargins(dp(2), 0, dp(2), 0);
              otgTabItems.addView(otgTabs[i], tabLp);
          }
          otgTabIndicator = new LiquidGlassIndicator(this);
          otgTabIndicator.setElevation(dp(4));
          tabs.addView(otgTabIndicator, new FrameLayout.LayoutParams(dp(62), dp(48)));
          LinearLayout.LayoutParams tabsLp = new LinearLayout.LayoutParams(-1, dp(68));
          tabsLp.setMargins(0, 0, 0, dp(10));
          content.addView(tabs, tabsLp);

          // 内容面板
          otgPanels = new LinearLayout[tabLabels.length];
          otgPanels[0] = buildOtgPartitionPanel();     // 本机分区管理
          otgPanels[1] = buildOtgFastbootPanel();      // 刷机助手
          for (LinearLayout panel : otgPanels) {
              LinearLayout.LayoutParams panelLp = new LinearLayout.LayoutParams(-1, -2);
              panelLp.setMargins(0, 0, 0, dp(10));
              content.addView(panel, panelLp);
          }

          // 初始化：只显示第一个面板
          selectOtgTab(0);
          
          // 延迟初始化指示器位置
          otgTabIndicator.post(() -> moveOtgTabIndicator(0, false));

          return content;
      }

      private boolean handleOtgTabGesture(View view, MotionEvent event, int pressedTab) {
          switch (event.getActionMasked()) {
              case MotionEvent.ACTION_DOWN:
                  otgTabDragging = false;
                  otgTabDragTarget = pressedTab;
                  otgTabDragStartX = event.getRawX();
                  if (otgTabLongPress != null) mainHandler.removeCallbacks(otgTabLongPress);
                  otgTabLongPress = () -> {
                      otgTabDragging = true;
                      view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                      view.getParent().requestDisallowInterceptTouchEvent(true);
                      if (otgTabIndicator != null) otgTabIndicator.setLiquidPressed(true);
                      previewOtgTabTarget(otgTabDragStartX);
                  };
                  mainHandler.postDelayed(otgTabLongPress, android.view.ViewConfiguration.getLongPressTimeout());
                  return true;
              case MotionEvent.ACTION_MOVE:
                  if (!otgTabDragging && Math.abs(event.getRawX() - otgTabDragStartX) >= dp(12)) {
                      otgTabDragging = true;
                      if (otgTabLongPress != null) mainHandler.removeCallbacks(otgTabLongPress);
                      view.getParent().requestDisallowInterceptTouchEvent(true);
                      if (otgTabIndicator != null) otgTabIndicator.setLiquidPressed(true);
                  }
                  if (otgTabDragging) previewOtgTabTarget(event.getRawX());
                  return true;
              case MotionEvent.ACTION_UP:
                  if (otgTabLongPress != null) mainHandler.removeCallbacks(otgTabLongPress);
                  if (otgTabDragging) {
                      if (otgTabIndicator != null) otgTabIndicator.setLiquidPressed(false);
                      selectOtgTab(otgTabDragTarget < 0 ? pressedTab : otgTabDragTarget);
                  } else {
                      Haptics.perform(view);
                      selectOtgTab(pressedTab);
                  }
                  otgTabDragging = false;
                  return true;
              case MotionEvent.ACTION_CANCEL:
                  if (otgTabLongPress != null) mainHandler.removeCallbacks(otgTabLongPress);
                  if (otgTabIndicator != null) otgTabIndicator.setLiquidPressed(false);
                  otgTabDragging = false;
                  return true;
              default:
                  return true;
          }
      }

      private void previewOtgTabTarget(float rawX) {
          if (otgTabItems == null || otgTabItems.getChildCount() == 0) return;
          int[] location = new int[2];
          otgTabItems.getLocationOnScreen(location);
          float itemWidth = otgTabItems.getWidth() / (float) otgTabItems.getChildCount();
          int target = Math.max(0, Math.min(otgTabItems.getChildCount() - 1,
                  (int) ((rawX - location[0]) / itemWidth)));
          if (target == otgTabDragTarget) return;
          otgTabDragTarget = target;
          moveOtgTabIndicator(target, true);
          if (otgTabs != null) {
              for (int i = 0; i < otgTabs.length; i++) otgTabs[i].setTextColor(i == target ? 0xff17334f : 0xfff8fbff);
          }
      }

      private void selectOtgTab(int index) {
          for (int i = 0; i < otgPanels.length; i++) otgPanels[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
          for (int i = 0; i < otgTabs.length; i++) {
              otgTabs[i].setAlpha(i == index ? 1f : .55f);
              otgTabs[i].setTypeface(null, i == index ? 1 : 0);
              otgTabs[i].setTextColor(i == index ? 0xff17334f : 0xff1a2332);
          }
          moveOtgTabIndicator(index, true);
          otgCurrentTab = index;
          // 控制分区面板 FAB 显示/隐藏（仅在本机分区 tab 显示）
          if (partitionFab != null) {
              // OTG 现在只保留：本机分区（0）和刷机助手（1）。
              partitionFab.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
          }
      }

      private void moveOtgTabIndicator(int index, boolean animated) {
          if (otgTabIndicator == null || otgTabItems == null || otgTabItems.getChildCount() == 0) return;
          View target = otgTabItems.getChildAt(index);
          int width = dp(62);
          int targetLeft = otgTabItems.getLeft() + target.getLeft() + (target.getWidth() - width) / 2;
          FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) otgTabIndicator.getLayoutParams();
          lp.width = width;
          lp.height = dp(48);
          lp.leftMargin = otgTabIndicatorLeft < 0 ? targetLeft : otgTabIndicatorLeft;
          lp.topMargin = dp(8);
          otgTabIndicator.setLayoutParams(lp);
          if (!animated || otgTabIndicatorLeft < 0) { 
              otgTabIndicatorLeft = targetLeft; 
              lp.leftMargin = targetLeft; 
              otgTabIndicator.setLayoutParams(lp); 
              return; 
          }
          int previous = otgTabIndicatorLeft;
          otgTabIndicatorLeft = targetLeft;
          android.animation.ValueAnimator flow = android.animation.ValueAnimator.ofFloat(0f, 1f);
          flow.setDuration(620);
          flow.setInterpolator(new android.view.animation.PathInterpolator(.18f, .78f, .22f, 1f));
          flow.addUpdateListener(a -> {
              float p = (Float) a.getAnimatedValue();
              float stretch = p < .24f ? p / .24f : p < .48f ? 1f : p < .80f ? 1f - (p - .48f) / .32f : 0f;
              float settle = p < .80f ? 0f : (p - .80f) / .20f;
              float travel = p < .43f ? 0f : (p - .43f) / .57f;
              otgTabIndicator.setTranslationX((targetLeft - previous) * travel);
              otgTabIndicator.setScaleX(1f + 1.18f * stretch + .07f * settle);
              otgTabIndicator.setScaleY(1f - .07f * stretch + .025f * settle);
          });
          flow.addListener(new android.animation.AnimatorListenerAdapter() {
              @Override public void onAnimationEnd(android.animation.Animator animation) {
                  FrameLayout.LayoutParams settled = (FrameLayout.LayoutParams) otgTabIndicator.getLayoutParams();
                  settled.leftMargin = targetLeft;
                  otgTabIndicator.setTranslationX(0f);
                  otgTabIndicator.setScaleX(1f);
                  otgTabIndicator.setScaleY(1f);
                  otgTabIndicator.setLayoutParams(settled);
              }
          });
          pulseOtgTabNavigation();
          flow.start();
      }

      private void pulseOtgTabNavigation() {
          if (otgTabItems == null) return;
          if (otgTabPulse != null) otgTabPulse.cancel();
          otgTabItems.setPivotX(otgTabItems.getWidth() / 2f);
          otgTabItems.setPivotY(otgTabItems.getHeight() / 2f);
          otgTabPulse = android.animation.ValueAnimator.ofFloat(0f, 1f);
          otgTabPulse.setDuration(840);
          otgTabPulse.setInterpolator(new android.view.animation.PathInterpolator(.22f, .76f, .26f, 1f));
          otgTabPulse.addUpdateListener(animation -> {
              float progress = (Float) animation.getAnimatedValue();
              float swell = progress < .28f ? progress / .28f : progress < .55f ? 1f : 1f - (progress - .55f) / .45f;
              otgTabItems.setScaleX(1f + .032f * swell);
              otgTabItems.setScaleY(1f + .064f * swell);
          });
          otgTabPulse.addListener(new android.animation.AnimatorListenerAdapter() {
              @Override public void onAnimationEnd(android.animation.Animator animation) {
                  if (animation != otgTabPulse) return;
                  otgTabItems.setScaleX(1f);
                  otgTabItems.setScaleY(1f);
                  otgTabPulse = null;
              }
          });
          otgTabPulse.start();
      }

      private LinearLayout buildOtgDevicePanel() {
          LinearLayout panel = new LiquidGlassPanel(this, 14f);
          panel.setOrientation(LinearLayout.VERTICAL);
          panel.setPadding(dp(16), dp(16), dp(16), dp(96));  // 底部留出导航栏空间

          // 设备选择
          TextView deviceLabel = new TextView(this);
          deviceLabel.setText(t("选择设备", "Select Device"));
          deviceLabel.setTextSize(14);
          deviceLabel.setTextColor(0xff1a2332);
          panel.addView(deviceLabel);

          EditText deviceInput = new EditText(this);
          deviceInput.setHint(t("设备 ID", "Device ID"));
          deviceInput.setTextSize(14);
          deviceInput.setTextColor(0xff0d1824);
          deviceInput.setPadding(dp(12), dp(10), dp(12), dp(10));
          deviceInput.setBackgroundResource(R.drawable.liquid_glass_panel);
          LinearLayout.LayoutParams deviceLp = new LinearLayout.LayoutParams(-1, -2);
          deviceLp.topMargin = dp(6);
          panel.addView(deviceInput, deviceLp);

          // 路径输入
          EditText pathInput = new EditText(this);
          pathInput.setHint(t("路径 (默认: /sdcard)", "Path (default: /sdcard)"));
          pathInput.setText("/sdcard");
          pathInput.setTextSize(14);
          pathInput.setTextColor(0xff0d1824);
          pathInput.setPadding(dp(12), dp(10), dp(12), dp(10));
          pathInput.setBackgroundResource(R.drawable.liquid_glass_panel);
          LinearLayout.LayoutParams pathLp = new LinearLayout.LayoutParams(-1, -2);
          pathLp.topMargin = dp(10);
          panel.addView(pathInput, pathLp);

          // 浏览按钮
          Button browseButton = new Button(this);
          browseButton.setText(t("浏览文件", "Browse Files"));
          browseButton.setTextSize(14);
          browseButton.setTextColor(Color.WHITE);
          browseButton.setBackgroundResource(R.drawable.liquid_glass_panel);
          browseButton.setPadding(dp(20), dp(12), dp(20), dp(12));
          LinearLayout.LayoutParams browseLp = new LinearLayout.LayoutParams(-1, -2);
          browseLp.topMargin = dp(10);
          panel.addView(browseButton, browseLp);

          // 文件列表容器
          ScrollView fileScroll = new ScrollView(this);
          LinearLayout fileList = new LinearLayout(this);
          fileList.setOrientation(LinearLayout.VERTICAL);
          fileScroll.addView(fileList);
          LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, dp(300));
          scrollLp.topMargin = dp(16);
          panel.addView(fileScroll, scrollLp);

          browseButton.setOnClickListener(v -> {
              String deviceId = deviceInput.getText().toString().trim();
              String path = pathInput.getText().toString().trim();
              if (deviceId.isEmpty()) {
                  Toast.makeText(this, t("请输入设备 ID", "Please enter device ID"), Toast.LENGTH_SHORT).show();
                  return;
              }
              fileList.removeAllViews();
              new Thread(() -> {
                  java.util.List<String> files = adbManager.listFiles(deviceId, path);
                  runOnUiThread(() -> {
                      if (files.isEmpty()) {
                          TextView empty = new TextView(this);
                          empty.setText(t("无法读取目录", "Cannot read directory"));
                          empty.setTextSize(14);
                          empty.setTextColor(0xff1a2332);
                          empty.setGravity(Gravity.CENTER);
                          fileList.addView(empty);
                      } else {
                          for (String file : files) {
                              TextView fileItem = new TextView(this);
                              fileItem.setText(file);
                              fileItem.setTextSize(12);
                              fileItem.setTextColor(0xff0d1824);
                              fileItem.setPadding(dp(8), dp(6), dp(8), dp(6));
                              fileItem.setBackgroundResource(R.drawable.liquid_glass_panel);
                              LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(-1, -2);
                              itemLp.topMargin = dp(4);
                              fileList.addView(fileItem, itemLp);
                          }
                      }
                  });
              }).start();
          });

          return panel;
      }

      private LinearLayout buildOtgFilesAndAppsPanel() {
          LinearLayout content = new LinearLayout(this);
          content.setOrientation(LinearLayout.VERTICAL);
          content.setPadding(0, 0, 0, dp(96));  // 底部留出导航栏空间

          // 设备输入（共用）
          LinearLayout deviceCard = new LiquidGlassPanel(this, 14f);
          deviceCard.setOrientation(LinearLayout.VERTICAL);
          deviceCard.setPadding(dp(16), dp(14), dp(16), dp(14));

          TextView deviceLabel = new TextView(this);
          deviceLabel.setText(t("设备 ID", "Device ID"));
          deviceLabel.setTextSize(14);
          deviceLabel.setTextColor(0xff000000);
          deviceLabel.setTypeface(null, 1);
          deviceCard.addView(deviceLabel);

          EditText deviceInput = new EditText(this);
          deviceInput.setHint(t("输入设备 ID", "Enter device ID"));
          deviceInput.setTextSize(14);
          deviceInput.setTextColor(0xff000000);
          deviceInput.setPadding(dp(12), dp(10), dp(12), dp(10));
          deviceInput.setBackgroundResource(R.drawable.liquid_glass_panel);
          LinearLayout.LayoutParams deviceInputLp = new LinearLayout.LayoutParams(-1, -2);
          deviceInputLp.topMargin = dp(8);
          deviceCard.addView(deviceInput, deviceInputLp);

          LinearLayout.LayoutParams deviceCardLp = new LinearLayout.LayoutParams(-1, -2);
          deviceCardLp.bottomMargin = dp(10);
          content.addView(deviceCard, deviceCardLp);

          // 文件浏览区域
          LinearLayout fileCard = new LiquidGlassPanel(this, 14f);
          fileCard.setOrientation(LinearLayout.VERTICAL);
          fileCard.setPadding(dp(16), dp(14), dp(16), dp(14));

          TextView fileTitle = new TextView(this);
          fileTitle.setText(t("文件浏览", "File Browser"));
          fileTitle.setTextSize(16);
          fileTitle.setTextColor(0xff000000);
          fileTitle.setTypeface(null, 1);
          fileCard.addView(fileTitle);

          EditText pathInput = new EditText(this);
          pathInput.setHint(t("路径 (默认: /sdcard)", "Path (default: /sdcard)"));
          pathInput.setText("/sdcard");
          pathInput.setTextSize(14);
          pathInput.setTextColor(0xff000000);
          pathInput.setPadding(dp(12), dp(10), dp(12), dp(10));
          pathInput.setBackgroundResource(R.drawable.liquid_glass_panel);
          LinearLayout.LayoutParams pathLp = new LinearLayout.LayoutParams(-1, -2);
          pathLp.topMargin = dp(10);
          fileCard.addView(pathInput, pathLp);

          Button browseButton = new Button(this);
          browseButton.setText(t("📁 浏览文件", "📁 Browse Files"));
          browseButton.setTextSize(14);
          browseButton.setTextColor(0xff000000);
          browseButton.setBackgroundResource(R.drawable.liquid_glass_panel);
          browseButton.setPadding(dp(20), dp(12), dp(20), dp(12));
          LinearLayout.LayoutParams browseLp = new LinearLayout.LayoutParams(-1, -2);
          browseLp.topMargin = dp(10);
          fileCard.addView(browseButton, browseLp);

          LinearLayout.LayoutParams fileCardLp = new LinearLayout.LayoutParams(-1, -2);
          fileCardLp.bottomMargin = dp(10);
          content.addView(fileCard, fileCardLp);

          // 应用管理区域
          LinearLayout appCard = new LiquidGlassPanel(this, 14f);
          appCard.setOrientation(LinearLayout.VERTICAL);
          appCard.setPadding(dp(16), dp(14), dp(16), dp(14));

          TextView appTitle = new TextView(this);
          appTitle.setText(t("应用管理", "App Management"));
          appTitle.setTextSize(16);
          appTitle.setTextColor(0xff000000);
          appTitle.setTypeface(null, 1);
          appCard.addView(appTitle);

          Button listAppsButton = new Button(this);
          listAppsButton.setText(t("📦 列出已安装应用", "📦 List Apps"));
          listAppsButton.setTextSize(14);
          listAppsButton.setTextColor(0xff000000);
          listAppsButton.setBackgroundResource(R.drawable.liquid_glass_panel);
          listAppsButton.setPadding(dp(20), dp(12), dp(20), dp(12));
          LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(-1, -2);
          listLp.topMargin = dp(10);
          appCard.addView(listAppsButton, listLp);

          content.addView(appCard, new LinearLayout.LayoutParams(-1, -2));

          // 事件处理
          browseButton.setOnClickListener(v -> {
              String deviceId = deviceInput.getText().toString().trim();
              String path = pathInput.getText().toString().trim();
              if (deviceId.isEmpty()) {
                  Toast.makeText(this, t("请输入设备 ID", "Please enter device ID"), Toast.LENGTH_SHORT).show();
                  return;
              }
              Toast.makeText(this, t("浏览: ", "Browse: ") + path, Toast.LENGTH_SHORT).show();
              // TODO: 实现文件浏览
          });

          listAppsButton.setOnClickListener(v -> {
              String deviceId = deviceInput.getText().toString().trim();
              if (deviceId.isEmpty()) {
                  Toast.makeText(this, t("请输入设备 ID", "Please enter device ID"), Toast.LENGTH_SHORT).show();
                  return;
              }
              Toast.makeText(this, t("列出应用", "List apps"), Toast.LENGTH_SHORT).show();
              // TODO: 实现应用列表
          });

          return content;
      }

      private LinearLayout buildOtgAppsPanel() {
          LinearLayout panel = new LiquidGlassPanel(this, 14f);
          panel.setOrientation(LinearLayout.VERTICAL);
          panel.setPadding(dp(16), dp(16), dp(16), dp(16));

          // 设备选择
          EditText deviceInput = new EditText(this);
          deviceInput.setHint(t("设备 ID", "Device ID"));
          deviceInput.setTextSize(14);
          deviceInput.setTextColor(0xff0d1824);
          deviceInput.setPadding(dp(12), dp(10), dp(12), dp(10));
          deviceInput.setBackgroundResource(R.drawable.liquid_glass_panel);
          panel.addView(deviceInput);

          // 列出应用按钮
          Button listAppsButton = new Button(this);
          listAppsButton.setText(t("列出已安装应用", "List Installed Apps"));
          listAppsButton.setTextSize(14);
          listAppsButton.setTextColor(Color.WHITE);
          listAppsButton.setBackgroundResource(R.drawable.liquid_glass_panel);
          listAppsButton.setPadding(dp(20), dp(12), dp(20), dp(12));
          LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(-1, -2);
          listLp.topMargin = dp(10);
          panel.addView(listAppsButton, listLp);

          // 应用列表容器
          ScrollView appScroll = new ScrollView(this);
          LinearLayout appList = new LinearLayout(this);
          appList.setOrientation(LinearLayout.VERTICAL);
          appScroll.addView(appList);
          LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, dp(250));
          scrollLp.topMargin = dp(16);
          panel.addView(appScroll, scrollLp);

          listAppsButton.setOnClickListener(v -> {
              String deviceId = deviceInput.getText().toString().trim();
              if (deviceId.isEmpty()) {
                  Toast.makeText(this, t("请输入设备 ID", "Please enter device ID"), Toast.LENGTH_SHORT).show();
                  return;
              }
              appList.removeAllViews();
              new Thread(() -> {
                  java.util.List<String> packages = adbManager.listPackages(deviceId);
                  runOnUiThread(() -> {
                      if (packages.isEmpty()) {
                          TextView empty = new TextView(this);
                          empty.setText(t("未找到应用", "No apps found"));
                          empty.setTextSize(14);
                          empty.setTextColor(0xff1a2332);
                          empty.setGravity(Gravity.CENTER);
                          appList.addView(empty);
                      } else {
                          for (String pkg : packages) {
                              LinearLayout appItem = new LinearLayout(this);
                              appItem.setOrientation(LinearLayout.HORIZONTAL);
                              appItem.setPadding(dp(8), dp(6), dp(8), dp(6));
                              appItem.setBackgroundResource(R.drawable.liquid_glass_panel);
                              
                              TextView pkgName = new TextView(this);
                              pkgName.setText(pkg);
                              pkgName.setTextSize(12);
                              pkgName.setTextColor(0xff0d1824);
                              appItem.addView(pkgName, new LinearLayout.LayoutParams(0, -2, 1f));
                              
                              Button uninstallBtn = new Button(this);
                              uninstallBtn.setText(t("卸载", "Uninstall"));
                              uninstallBtn.setTextSize(10);
                              uninstallBtn.setTextColor(Color.WHITE);
                              uninstallBtn.setBackgroundResource(R.drawable.liquid_glass_panel);
                              uninstallBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
                              uninstallBtn.setOnClickListener(btn -> {
                                  new Thread(() -> {
                                      com.topjohnwu.superuser.Shell.Result result = adbManager.uninstallPackage(deviceId, pkg);
                                      runOnUiThread(() -> {
                                          if (result.isSuccess()) {
                                              Toast.makeText(this, t("卸载成功", "Uninstalled"), Toast.LENGTH_SHORT).show();
                                              appList.removeView(appItem);
                                          } else {
                                              Toast.makeText(this, t("卸载失败", "Failed"), Toast.LENGTH_SHORT).show();
                                          }
                                      });
                                  }).start();
                              });
                              appItem.addView(uninstallBtn, new LinearLayout.LayoutParams(-2, -2));
                              
                              LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(-1, -2);
                              itemLp.topMargin = dp(4);
                              appList.addView(appItem, itemLp);
                          }
                      }
                  });
              }).start();
          });

          // 安装 APK 区域
          TextView installLabel = new TextView(this);
          installLabel.setText(t("安装 APK", "Install APK"));
          installLabel.setTextSize(14);
          installLabel.setTextColor(0xff1a2332);
          installLabel.setTypeface(null, 1);
          LinearLayout.LayoutParams installTitleLp = new LinearLayout.LayoutParams(-1, -2);
          installTitleLp.topMargin = dp(16);
          panel.addView(installLabel, installTitleLp);

          EditText apkPathInput = new EditText(this);
          apkPathInput.setHint(t("APK 路径", "APK Path"));
          apkPathInput.setTextSize(14);
          apkPathInput.setTextColor(0xff0d1824);
          apkPathInput.setPadding(dp(12), dp(10), dp(12), dp(10));
          apkPathInput.setBackgroundResource(R.drawable.liquid_glass_panel);
          LinearLayout.LayoutParams apkLp = new LinearLayout.LayoutParams(-1, -2);
          apkLp.topMargin = dp(8);
          panel.addView(apkPathInput, apkLp);

          Button installButton = new Button(this);
          installButton.setText(t("安装", "Install"));
          installButton.setTextSize(14);
          installButton.setTextColor(Color.WHITE);
          installButton.setBackgroundResource(R.drawable.liquid_glass_panel);
          installButton.setPadding(dp(20), dp(12), dp(20), dp(12));
          installButton.setOnClickListener(v -> {
              String deviceId = deviceInput.getText().toString().trim();
              String apkPath = apkPathInput.getText().toString().trim();
              if (deviceId.isEmpty() || apkPath.isEmpty()) {
                  Toast.makeText(this, t("请填写完整信息", "Please fill all fields"), Toast.LENGTH_SHORT).show();
                  return;
              }
              new Thread(() -> {
                  com.topjohnwu.superuser.Shell.Result result = adbManager.installApk(deviceId, apkPath);
                  runOnUiThread(() -> {
                      if (result.isSuccess()) {
                          Toast.makeText(this, t("安装成功", "Installed"), Toast.LENGTH_SHORT).show();
                      } else {
                          Toast.makeText(this, t("安装失败", "Failed"), Toast.LENGTH_SHORT).show();
                      }
                  });
              }).start();
          });
          LinearLayout.LayoutParams installBtnLp = new LinearLayout.LayoutParams(-1, -2);
          installBtnLp.topMargin = dp(8);
          panel.addView(installButton, installBtnLp);

          return panel;
      }

      private LinearLayout buildOtgShellPanel() {
          LinearLayout panel = new LiquidGlassPanel(this, 14f);
          panel.setOrientation(LinearLayout.VERTICAL);
          panel.setPadding(dp(16), dp(16), dp(16), dp(16));

          // 设备选择
          EditText deviceInput = new EditText(this);
          deviceInput.setHint(t("设备 ID", "Device ID"));
          deviceInput.setTextSize(14);
          deviceInput.setTextColor(0xff0d1824);
          deviceInput.setPadding(dp(12), dp(10), dp(12), dp(10));
          deviceInput.setBackgroundResource(R.drawable.liquid_glass_panel);
          panel.addView(deviceInput);

          // 命令输入
          EditText commandInput = new EditText(this);
          commandInput.setHint(t("输入 Shell 命令", "Enter Shell Command"));
          commandInput.setTextSize(14);
          commandInput.setTextColor(0xff0d1824);
          commandInput.setPadding(dp(12), dp(10), dp(12), dp(10));
          commandInput.setBackgroundResource(R.drawable.liquid_glass_panel);
          LinearLayout.LayoutParams cmdLp = new LinearLayout.LayoutParams(-1, -2);
          cmdLp.topMargin = dp(10);
          panel.addView(commandInput, cmdLp);

          // 执行按钮
          Button executeButton = new Button(this);
          executeButton.setText(t("执行", "Execute"));
          executeButton.setTextSize(14);
          executeButton.setTextColor(Color.WHITE);
          executeButton.setBackgroundResource(R.drawable.liquid_glass_panel);
          executeButton.setPadding(dp(20), dp(12), dp(20), dp(12));
          LinearLayout.LayoutParams execLp = new LinearLayout.LayoutParams(-1, -2);
          execLp.topMargin = dp(10);
          panel.addView(executeButton, execLp);

          // 输出区域
          ScrollView outputScroll = new ScrollView(this);
          TextView outputText = new TextView(this);
          outputText.setTextSize(12);
          outputText.setTextColor(0xff0d1824);
          outputText.setTypeface(android.graphics.Typeface.MONOSPACE);
          outputText.setPadding(dp(12), dp(12), dp(12), dp(12));
          outputText.setBackgroundResource(R.drawable.liquid_glass_panel);
          outputScroll.addView(outputText);
          LinearLayout.LayoutParams outputLp = new LinearLayout.LayoutParams(-1, dp(300));
          outputLp.topMargin = dp(16);
          panel.addView(outputScroll, outputLp);

          executeButton.setOnClickListener(v -> {
              String deviceId = deviceInput.getText().toString().trim();
              String command = commandInput.getText().toString().trim();
              if (deviceId.isEmpty() || command.isEmpty()) {
                  Toast.makeText(this, t("请填写完整信息", "Please fill all fields"), Toast.LENGTH_SHORT).show();
                  return;
              }
              outputText.setText(t("执行中...", "Executing..."));
              new Thread(() -> {
                  com.topjohnwu.superuser.Shell.Result result = adbManager.execShell(deviceId, command);
                  runOnUiThread(() -> {
                      StringBuilder output = new StringBuilder();
                      output.append("$ ").append(command).append("\n\n");
                      if (result.isSuccess()) {
                          for (String line : result.getOut()) {
                              output.append(line).append("\n");
                          }
                      } else {
                          output.append(t("执行失败", "Failed")).append("\n");
                          for (String line : result.getErr()) {
                              output.append(line).append("\n");
                          }
                      }
                      outputText.setText(output.toString());
                  });
              }).start();
          });

          return panel;
      }


      // OTG 刷机助手变量
      private TextView flashDeviceText, flashProtocolText, flashLogText;
      private ScrollView flashLogScroll;
      private EditText flashCmdInput;
      private boolean flashDeviceConnected = false;
      private boolean flashRefreshing = false;
      private OtgFlashHelper otgFlashHelper;
      
      private LinearLayout buildOtgFastbootPanel() {
          ScrollView scroll = new ScrollView(this);
          LinearLayout content = new LinearLayout(this);
          content.setOrientation(LinearLayout.VERTICAL);
          content.setPadding(dp(4), dp(20), dp(4), dp(96));
          
          // 设备状态卡片（标题带刷新按钮）
          content.addView(buildFlashDeviceCard());
          
          // 输入命令卡片
          content.addView(buildFlashCommandCard());
          
          // 功能按钮网格
          content.addView(buildFlashActionsGrid());
          
          // 输出日志卡片
          content.addView(buildFlashLogCard());
          
          scroll.addView(content);
          
          // 初始化
          flashAppendLog("正在初始化...\n");
          flashRefreshDevices();
          
          LinearLayout wrapper = new LinearLayout(this);
          wrapper.setOrientation(LinearLayout.VERTICAL);
          wrapper.addView(scroll);
          return wrapper;
      }
      
      private LinearLayout buildFlashDeviceCard() {
          LinearLayout card = new LiquidGlassPanel(this, 20f);
          card.setOrientation(LinearLayout.VERTICAL);
          card.setPadding(dp(28), dp(24), dp(28), dp(24));
          
          // 渐变背景
          android.graphics.drawable.GradientDrawable gradient = new android.graphics.drawable.GradientDrawable(
              android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
              new int[]{0x12d1fae5, 0x08a7f3d0}
          );
          gradient.setCornerRadius(dp(20));
          card.setBackground(gradient);
          
          // 标题行（带刷新按钮）
          LinearLayout titleRow = new LinearLayout(this);
          titleRow.setOrientation(LinearLayout.HORIZONTAL);
          titleRow.setGravity(Gravity.CENTER_VERTICAL);
          
          TextView title = new TextView(this);
          title.setText(t("📱 设备状态", "📱 Device Status"));
          title.setTextSize(13);  // 缩小到13sp
          title.setTextColor(0xff000000);
          title.setTypeface(null, 1);
          LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1f);
          titleRow.addView(title, titleLp);
          
          Button btnRefresh = new Button(this);
          btnRefresh.setText(t("刷新", "Refresh"));
          btnRefresh.setTextSize(12);  // 缩小到12sp
          btnRefresh.setTextColor(0xff000000);
          btnRefresh.setBackground(createRoundRect(0x18000000, dp(10)));
          btnRefresh.setPadding(dp(16), dp(10), dp(16), dp(10));
          btnRefresh.setOnClickListener(v -> {
              Haptics.perform(v);  // 添加震动
              flashRefreshDevices();
          });
          titleRow.addView(btnRefresh);
          
          card.addView(titleRow);
          
          // 设备列表
          flashDeviceText = new TextView(this);
          flashDeviceText.setText(t("未找到 USB 设备", "No USB device"));
          flashDeviceText.setTextSize(12);  // 缩小到12sp
          flashDeviceText.setTextColor(0xff64748b);
          flashDeviceText.setPadding(0, dp(16), 0, 0);
          flashDeviceText.setMaxLines(8);
          flashDeviceText.setEllipsize(android.text.TextUtils.TruncateAt.END);
          card.addView(flashDeviceText);
          
          // 协议检测
          flashProtocolText = new TextView(this);
          flashProtocolText.setText("ADB: —\nFastboot: —");
          flashProtocolText.setTextSize(11);  // 缩小到11sp
          flashProtocolText.setTextColor(0xff94a3b8);
          flashProtocolText.setTypeface(android.graphics.Typeface.MONOSPACE);
          flashProtocolText.setPadding(0, dp(14), 0, 0);
          card.addView(flashProtocolText);
          
          LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
          lp.bottomMargin = dp(24);
          card.setLayoutParams(lp);
          return card;
      }
      
      private LinearLayout buildFlashCommandCard() {
          LinearLayout card = new LiquidGlassPanel(this, 20f);
          card.setOrientation(LinearLayout.VERTICAL);
          card.setPadding(dp(28), dp(24), dp(28), dp(24));
          
          // 渐变背景
          android.graphics.drawable.GradientDrawable gradient = new android.graphics.drawable.GradientDrawable(
              android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
              new int[]{0x12e0f2fe, 0x08bae6fd}
          );
          gradient.setCornerRadius(dp(20));
          card.setBackground(gradient);
          
          TextView title = new TextView(this);
          title.setText(t("⌨️ 输入命令", "⌨️ Input Command"));
          title.setTextSize(13);  // 缩小到13sp
          title.setTextColor(0xff000000);
          title.setTypeface(null, 1);
          card.addView(title);
          
          flashCmdInput = new EditText(this);
          flashCmdInput.setHint(t("例如: adb devices / fastboot getvar all", "e.g: adb devices / fastboot getvar all"));
          flashCmdInput.setTextSize(12);  // 缩小到12sp
          flashCmdInput.setTextColor(0xff000000);
          flashCmdInput.setHintTextColor(0xff94a3b8);
          flashCmdInput.setBackground(createRoundRect(0x18000000, dp(16)));
          flashCmdInput.setPadding(dp(20), dp(16), dp(20), dp(16));
          LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(-1, -2);
          inputLp.topMargin = dp(16);
          card.addView(flashCmdInput, inputLp);
          
          Button execBtn = new Button(this);
          execBtn.setText(t("执行命令", "Execute"));
          execBtn.setTextSize(13);  // 缩小到13sp
          execBtn.setTextColor(0xffffffff);
          execBtn.setTypeface(null, 1);
          execBtn.setBackground(createRoundRect(0xff3b82f6, dp(16)));
          execBtn.setOnClickListener(v -> {
              Haptics.perform(v);  // 添加震动
              executeFlashCommand();
          });
          LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-1, dp(56));
          btnLp.topMargin = dp(16);
          card.addView(execBtn, btnLp);
          
          LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
          lp.bottomMargin = dp(24);
          card.setLayoutParams(lp);
          return card;
      }
      
      private LinearLayout buildFlashActionsGrid() {
          LinearLayout grid = new LinearLayout(this);
          grid.setOrientation(LinearLayout.VERTICAL);
          
          // 第一行
          LinearLayout row1 = new LinearLayout(this);
          row1.setOrientation(LinearLayout.HORIZONTAL);
          row1.addView(createFlashActionBtn(t("读取分区表", "Read Partitions"), 0xfff59e0b, () -> flashReadPartitions()), new LinearLayout.LayoutParams(0, dp(70), 1));
          row1.addView(new android.view.View(this), new LinearLayout.LayoutParams(dp(16), 1));
          row1.addView(createFlashActionBtn(t("单分区刷写", "Flash Single"), 0xff10b981, () -> flashChooseSingle()), new LinearLayout.LayoutParams(0, dp(70), 1));
          grid.addView(row1, new LinearLayout.LayoutParams(-1, -2));
          
          // 第二行
          android.view.View space1 = new android.view.View(this);
          grid.addView(space1, new LinearLayout.LayoutParams(-1, dp(16)));
          
          LinearLayout row2 = new LinearLayout(this);
          row2.setOrientation(LinearLayout.HORIZONTAL);
          row2.addView(createFlashActionBtn(t("全量包刷写", "Flash Full"), 0xffef4444, () -> flashChooseFull()), new LinearLayout.LayoutParams(0, dp(70), 1));
          row2.addView(new android.view.View(this), new LinearLayout.LayoutParams(dp(16), 1));
          row2.addView(createFlashActionBtn(t("高级重启", "Advanced Reboot"), 0xff8b5cf6, () -> flashShowRebootMenu()), new LinearLayout.LayoutParams(0, dp(70), 1));
          grid.addView(row2, new LinearLayout.LayoutParams(-1, -2));
          
          // 第三行
          android.view.View space2 = new android.view.View(this);
          grid.addView(space2, new LinearLayout.LayoutParams(-1, dp(16)));
          
          LinearLayout row3 = new LinearLayout(this);
          row3.setOrientation(LinearLayout.HORIZONTAL);
          row3.addView(createFlashActionBtn(t("ADB 推送", "ADB Push"), 0xff06b6d4, () -> flashChoosePush()), new LinearLayout.LayoutParams(0, dp(70), 1));
          row3.addView(new android.view.View(this), new LinearLayout.LayoutParams(dp(16), 1));
          row3.addView(createFlashActionBtn(t("ADB 设备信息", "ADB Info"), 0xff64748b, () -> flashShowDeviceInfo()), new LinearLayout.LayoutParams(0, dp(70), 1));
          grid.addView(row3, new LinearLayout.LayoutParams(-1, -2));
          
          LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
          lp.bottomMargin = dp(24);
          grid.setLayoutParams(lp);
          return grid;
      }
      
      private Button createFlashActionBtn(String text, int color, Runnable onClick) {
          Button btn = new Button(this);
          btn.setText(text);
          btn.setTextSize(13);  // 缩小到13sp
          btn.setTextColor(0xffffffff);
          btn.setTypeface(null, 1);
          
          // 渐变背景
          android.graphics.drawable.GradientDrawable gradient = new android.graphics.drawable.GradientDrawable(
              android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
              new int[]{color, adjustBrightness(color, 0.8f)}
          );
          gradient.setCornerRadius(dp(16));
          btn.setBackground(gradient);
          
          btn.setOnClickListener(v -> {
              Haptics.perform(v);  // 添加震动
              onClick.run();
          });
          return btn;
      }
      
      private int adjustBrightness(int color, float factor) {
          int a = (color >> 24) & 0xff;
          int r = (int) (((color >> 16) & 0xff) * factor);
          int g = (int) (((color >> 8) & 0xff) * factor);
          int b = (int) ((color & 0xff) * factor);
          return (a << 24) | (r << 16) | (g << 8) | b;
      }
      
      private LinearLayout buildFlashLogCard() {
          LinearLayout card = new LiquidGlassPanel(this, 20f);
          card.setOrientation(LinearLayout.VERTICAL);
          card.setPadding(dp(28), dp(24), dp(28), dp(24));
          
          // 渐变背景
          android.graphics.drawable.GradientDrawable gradient = new android.graphics.drawable.GradientDrawable(
              android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
              new int[]{0x12fef3c7, 0x08fde68a}
          );
          gradient.setCornerRadius(dp(20));
          card.setBackground(gradient);
          
          LinearLayout logHeader = new LinearLayout(this);
          logHeader.setOrientation(LinearLayout.HORIZONTAL);
          logHeader.setGravity(Gravity.CENTER_VERTICAL);
          TextView title = new TextView(this);
          title.setText(t("📜 操作日志", "📜 Operation Log"));
          title.setTextSize(13);  // 缩小到13sp
          title.setTextColor(0xff000000);
          title.setTypeface(null, 1);
          logHeader.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
          Button copyLogButton = new Button(this);
          copyLogButton.setText(t("复制全部日志", "Copy All Logs"));
          copyLogButton.setTextSize(11);
          copyLogButton.setTextColor(0xff334155);
          copyLogButton.setAllCaps(false);
          copyLogButton.setMinHeight(0);
          copyLogButton.setMinimumHeight(0);
          copyLogButton.setPadding(dp(10), 0, dp(10), 0);
          copyLogButton.setBackground(createRoundRect(0x220f766e, dp(12)));
          copyLogButton.setOnClickListener(v -> {
              Haptics.perform(v);
              String content = flashLogText == null ? "" : flashLogText.getText().toString();
              android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
              if (clipboard != null) {
                  clipboard.setPrimaryClip(android.content.ClipData.newPlainText("操作日志", content));
                  Toast.makeText(this, t("已复制全部日志", "All logs copied"), Toast.LENGTH_SHORT).show();
              }
          });
          logHeader.addView(copyLogButton, new LinearLayout.LayoutParams(-2, dp(34)));
          card.addView(logHeader);
          
          // v3.41.11：BoundedScrollView（OTG 触摸模型，整条祖先链独占）替换裸 ScrollView
          flashLogScroll = new DnaActivity.BoundedScrollView(this, 0);
          flashLogScroll.setVerticalScrollBarEnabled(true);
          flashLogScroll.setScrollbarFadingEnabled(false);
          flashLogScroll.setFillViewport(false);
          flashLogScroll.setNestedScrollingEnabled(false);  // 禁用嵌套滚动

          flashLogText = new TextView(this);
          flashLogText.setText("");
          flashLogText.setTextSize(12);  // 缩小到12sp
          flashLogText.setTextColor(0xff475569);
          flashLogText.setTypeface(android.graphics.Typeface.MONOSPACE);
          flashLogText.setPadding(dp(18), dp(16), dp(18), dp(16));
          flashLogText.setBackground(createRoundRect(0x12000000, dp(14)));
          flashLogScroll.addView(flashLogText);
          
          LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, dp(200));  // 缩小到200dp
          scrollLp.topMargin = dp(16);
          card.addView(flashLogScroll, scrollLp);
          
          // 初始化 OtgFlashHelper
          otgFlashHelper = new OtgFlashHelper(this, flashLogText, flashLogScroll);
          
          LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
          card.setLayoutParams(lp);
          return card;
      }
      
      private void flashRefreshDevices() {
          if (otgFlashHelper == null) return;
          otgFlashHelper.refreshDevices(flashDeviceText, flashProtocolText);
      }
      
      private void startFlashDeviceMonitor() {
          // 已删除，改用手动刷新
      }
      
      private void executeFlashCommand() {
          String cmd = flashCmdInput.getText().toString().trim();
          if (cmd.isEmpty()) {
              Toast.makeText(this, t("请输入命令", "Enter command"), 0).show();
              return;
          }
          if (otgFlashHelper != null) otgFlashHelper.executeCommand(cmd);
      }
      
      private void flashAppendLog(String text) {
          flashLogText.append(text + "\n");
          flashLogScroll.post(() -> flashLogScroll.fullScroll(ScrollView.FOCUS_DOWN));
      }
      
      private List<OtgAssistantCore.PartitionInfo> parsedPartitions = new ArrayList<>();
      private String selectedPartition = null;

      private void flashReadPartitions() {
          if (otgFlashHelper != null) {
              otgFlashHelper.readPartitions();
          }
      }
      
      private void flashChooseSingle() {
          if (otgFlashHelper != null) {
              otgFlashHelper.chooseSinglePartition();
          }
      }
      
      private void flashChooseFull() {
          if (otgFlashHelper != null) {
              otgFlashHelper.chooseFirmwareDirectory();
          }
      }
      
      private void flashShowRebootMenu() {
          if (otgFlashHelper != null) {
              otgFlashHelper.showRebootMenu();
          }
      }
      
      private void flashChoosePush() {
          if (otgFlashHelper != null) {
              otgFlashHelper.chooseAdbPushFile();
          }
      }
      
      private void flashShowDeviceInfo() {
          if (otgFlashHelper != null) {
              otgFlashHelper.showAdbDeviceInfo();
          }
      }
      
      private android.graphics.drawable.GradientDrawable createRoundRect(int color, int radius) {
          android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
          drawable.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
          drawable.setColor(color);
          drawable.setCornerRadius(radius);
          return drawable;
      }
      
      private String execShell(String cmd) {
          try {
              com.topjohnwu.superuser.Shell.Result result = com.topjohnwu.superuser.Shell.cmd(cmd).exec();
              if (result.isSuccess()) {
                  StringBuilder sb = new StringBuilder();
                  for (String line : result.getOut()) {
                      sb.append(line).append("\n");
                  }
                  return sb.toString();
              }
              return "";
          } catch (Exception e) {
              return "Error: " + e.getMessage();
          }
      }
      
      private void flashDeleteOtaDir() {
          new Thread(() -> {
              try {
                  java.io.File otaRoot = new java.io.File(getFilesDir(), "ota");
                  if (otaRoot.exists()) deleteRecursive(otaRoot);
              } catch (Exception ignored) {}
          }).start();
      }

      private void deleteRecursive(java.io.File f) {
          if (f.isDirectory()) {
              java.io.File[] children = f.listFiles();
              if (children != null) for (java.io.File c : children) deleteRecursive(c);
          }
          f.delete();
      }

      private Button flashActionBtn(String text) {
          Button btn = new Button(this);
          btn.setText(text);
          btn.setTextSize(13);
          btn.setTextColor(0xff000000);
          btn.setTypeface(null, 1);
          btn.setBackgroundResource(R.drawable.liquid_glass_panel);
          btn.setPadding(0, dp(9), 0, dp(9));
          return btn;
      }

      private LinearLayout flashGridRow(View a, View b) {
          LinearLayout row = new LinearLayout(this);
          row.setOrientation(LinearLayout.HORIZONTAL);
          LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
          lp.bottomMargin = dp(6);
          row.setLayoutParams(lp);
          LinearLayout.LayoutParams lpA = new LinearLayout.LayoutParams(0, dp(44), 1f);
          lpA.rightMargin = dp(6);
          row.addView(a, lpA);
          LinearLayout.LayoutParams lpB = new LinearLayout.LayoutParams(0, dp(44), 1f);
          row.addView(b, lpB);
          return row;
      }

      private LinearLayout buildOtgPartitionPanel() {
          LinearLayout panel = new LiquidGlassPanel(this, 14f);
          panel.setOrientation(LinearLayout.VERTICAL);
          panel.setPadding(dp(16), dp(16), dp(16), dp(96));

          // 顶部模式切换按钮
          LinearLayout modeSwitch = new LinearLayout(this);
          modeSwitch.setOrientation(LinearLayout.HORIZONTAL);
          modeSwitch.setGravity(Gravity.CENTER);
          panel.addView(modeSwitch);

          Button[] modeButtons = new Button[2];
          String[] modeLabels = {t("📤 提取镜像", "📤 Extract"), t("📥 刷入镜像", "📥 Flash")};
          
          for (int i = 0; i < 2; i++) {
              final int index = i;
              Button btn = new Button(this);
              btn.setText(modeLabels[i]);
              btn.setTextSize(14);
              btn.setTextColor(i == 0 ? Color.WHITE : 0xff000000);
              btn.setBackgroundResource(i == 0 ? R.drawable.button_blue : R.drawable.liquid_glass_panel);
              btn.setPadding(dp(24), dp(12), dp(24), dp(12));
              btn.setTypeface(null, i == 0 ? 1 : 0);
              LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0, -2, 1f);
              if (i > 0) btnLp.leftMargin = dp(12);
              btn.setOnClickListener(v -> {
                  for (int j = 0; j < modeButtons.length; j++) {
                      modeButtons[j].setTextColor(j == index ? Color.WHITE : 0xff000000);
                      modeButtons[j].setBackgroundResource(j == index ? R.drawable.button_blue : R.drawable.liquid_glass_panel);
                      modeButtons[j].setTypeface(null, j == index ? 1 : 0);
                  }
                  rebuildPartitionList(index == 0);
              });
              modeButtons[i] = btn;
              modeSwitch.addView(btn, btnLp);
          }

          // 搜索框
          EditText searchBox = new EditText(this);
          searchBox.setHint(t("🔍 搜索分区名称", "🔍 Search partition"));
          searchBox.setTextSize(14);
          searchBox.setTextColor(0xff000000);
          searchBox.setPadding(dp(16), dp(12), dp(16), dp(12));
          searchBox.setBackgroundResource(R.drawable.liquid_glass_panel);
          searchBox.setSingleLine(true);
          searchBox.addTextChangedListener(new android.text.TextWatcher() {
              @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
              @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                  currentSearchQuery = s.toString();
                  refreshPartitionList();
              }
              @Override public void afterTextChanged(android.text.Editable s) {}
          });
          LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(-1, -2);
          searchLp.topMargin = dp(16);
          panel.addView(searchBox, searchLp);

          // 槽位筛选按钮组
          LinearLayout slotFilter = new LinearLayout(this);
          slotFilter.setOrientation(LinearLayout.HORIZONTAL);
          slotFilter.setGravity(Gravity.CENTER);
          LinearLayout.LayoutParams slotLp = new LinearLayout.LayoutParams(-1, -2);
          slotLp.topMargin = dp(12);
          panel.addView(slotFilter, slotLp);

          Button[] slotButtons = new Button[4];
          String[] slotLabels = {t("全部", "All"), t("槽位 A", "Slot A"), t("槽位 B", "Slot B"), t("其他", "Other")};
          
          for (int i = 0; i < 4; i++) {
              final int index = i;
              Button btn = new Button(this);
              btn.setText(slotLabels[i]);
              btn.setTextSize(12);
              btn.setTextColor(i == 0 ? 0xff000000 : 0xff2d3748);
              btn.setBackgroundResource(R.drawable.liquid_glass_panel);
              btn.setPadding(dp(16), dp(8), dp(16), dp(8));
              btn.setTypeface(null, i == 0 ? 1 : 0);
              LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0, -2, 1f);
              if (i > 0) btnLp.leftMargin = dp(8);
              btn.setOnClickListener(v -> {
                  for (int j = 0; j < slotButtons.length; j++) {
                      slotButtons[j].setTextColor(j == index ? 0xff000000 : 0xff2d3748);
                      slotButtons[j].setTypeface(null, j == index ? 1 : 0);
                  }
                  filterPartitionsBySlot(index);
              });
              slotButtons[i] = btn;
              slotFilter.addView(btn, btnLp);
          }

          // 本机系统高级重启：仅看已选与前三个操作按钮同排等宽显示。
          LinearLayout rebootActions = new LinearLayout(this);
          rebootActions.setOrientation(LinearLayout.VERTICAL);
          LinearLayout.LayoutParams rebootRowLp = new LinearLayout.LayoutParams(-1, -2);
          rebootRowLp.topMargin = dp(6);
          panel.addView(rebootActions, rebootRowLp);
          LinearLayout rebootRowOne = new LinearLayout(this);
          rebootRowOne.setOrientation(LinearLayout.HORIZONTAL);
          rebootActions.addView(rebootRowOne);
          Button viewSelectedBtn = buildCompactPartitionActionButton(t("仅看已选", "Show Selected"), true);
          viewSelectedBtn.setOnClickListener(v -> showOnlySelected());
          rebootRowOne.addView(viewSelectedBtn, new LinearLayout.LayoutParams(0, -2, 1f));
          addPartitionRebootButton(rebootRowOne, t("关机", "Shutdown"), "sync;svc power shutdown || reboot -p;", true, true);
          addPartitionRebootButton(rebootRowOne, t("重启", "Reboot"), "sync;svc power reboot || reboot;", true, true);
          addPartitionRebootButton(rebootRowOne, t("热重启", "Hot reboot"), "sync;am restart || busybox killall system_server;", true, true);
          LinearLayout rebootRowTwo = new LinearLayout(this);
          rebootRowTwo.setOrientation(LinearLayout.HORIZONTAL);
          LinearLayout.LayoutParams rebootRowTwoLp = new LinearLayout.LayoutParams(-1, -2);
          rebootRowTwoLp.topMargin = dp(3);
          rebootActions.addView(rebootRowTwo, rebootRowTwoLp);
          addPartitionRebootButton(rebootRowTwo, "FASTBOOT", "sync;reboot bootloader;", true, false);
          addPartitionRebootButton(rebootRowTwo, "RECOVERY", "sync;reboot recovery;", true, false);
          addPartitionRebootButton(rebootRowTwo, "9008\n(EDL)", "sync;reboot edl;", true, false);
          addPartitionRebootButton(rebootRowTwo, "FASTBOOT\nD", "sync;reboot fastboot;", true, false);

          // 分区列表标题（带打开位置按钮）
          LinearLayout partTitleRow = new LinearLayout(this);
          partTitleRow.setOrientation(LinearLayout.HORIZONTAL);
          partTitleRow.setGravity(Gravity.CENTER_VERTICAL);
          
          TextView partListTitle = new TextView(this);
          partListTitle.setText(t("分区列表", "Partition List"));
          partListTitle.setTextSize(14);
          partListTitle.setTypeface(null, 1);
          partListTitle.setTextColor(0xff000000);
          LinearLayout.LayoutParams partTitleLp = new LinearLayout.LayoutParams(0, -2, 1f);
          partTitleRow.addView(partListTitle, partTitleLp);
          
           // 提取模式打开位置；刷入模式选择 .img
          Button btnOpenFolder = new Button(this);
          partitionImagePickerButton = btnOpenFolder;
          btnOpenFolder.setText(t("📂 打开位置", "📂 Open"));
          btnOpenFolder.setTextSize(12);
          btnOpenFolder.setTextColor(0xff000000);
          btnOpenFolder.setBackgroundResource(R.drawable.liquid_glass_panel);  // 液态玻璃背景
          btnOpenFolder.setPadding(dp(12), dp(8), dp(12), dp(8));
          btnOpenFolder.setOnClickListener(v -> {
              Haptics.perform(v);  // 添加震动
              if (!currentExtractMode) {
                  choosePartitionImageFile();
                  return;
              }
              new Thread(() -> {
                  try {
                      // v3.30.17 修复：对齐提取输出目录（v3.30.13 已改为 /storage/emulated/0/PDNA/image），
                      // 之前指向旧目录 DsuManager/image → 报"目录为空或不存在"
                      String dirPath = "/storage/emulated/0/PDNA/image";
                      
                      // 使用 Shell 命令列出文件
                      String lsResult = execShell("ls -1 " + dirPath);
                      if (lsResult == null || lsResult.trim().isEmpty() || lsResult.contains("No such file")) {
                          runOnUiThread(() -> Toast.makeText(this, t("目录为空或不存在", "Directory empty or not found"), Toast.LENGTH_SHORT).show());
                          return;
                      }
                      
                      String[] fileNames = lsResult.trim().split("\n");
                      if (fileNames.length == 0) {
                          runOnUiThread(() -> Toast.makeText(this, t("目录为空", "Directory is empty"), Toast.LENGTH_SHORT).show());
                          return;
                      }
                      
                      // 使用第一个文件
                      File file = new File(dirPath, fileNames[0].trim());
                      if (!file.exists()) {
                          runOnUiThread(() -> Toast.makeText(this, t("文件不存在", "File not found"), Toast.LENGTH_SHORT).show());
                          return;
                      }
                      
                      runOnUiThread(() -> {
                          try {
                              android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                                  this, 
                                  getPackageName() + ".fileprovider", 
                                  file
                              );
                              
                              String mimeType;
                              String name = file.getName();
                              if (name.endsWith(".img")) {
                                  mimeType = "application/octet-stream";
                              } else if (name.endsWith(".zip")) {
                                  mimeType = "application/zip";
                              } else {
                                  mimeType = "*/*";
                              }
                              
                              Intent intent = new Intent(Intent.ACTION_VIEW);
                              intent.setDataAndType(uri, mimeType);
                              intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                              intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                              
                              startActivity(Intent.createChooser(intent, t("打开方式", "Open with")));
                          } catch (Exception e) {
                              Toast.makeText(this, t("无法打开文件管理器", "Cannot open file manager"), Toast.LENGTH_SHORT).show();
                          }
                      });
                  } catch (Exception e) {
                      runOnUiThread(() -> Toast.makeText(this, t("操作失败", "Operation failed"), Toast.LENGTH_SHORT).show());
                  }
              }).start();
          });
          partTitleRow.addView(btnOpenFolder);
          
          LinearLayout.LayoutParams partTitleRowLp = new LinearLayout.LayoutParams(-1, -2);
          partTitleRowLp.topMargin = dp(16);
          panel.addView(partTitleRow, partTitleRowLp);

           // 分区列表（固定高度400dp，可滚动）。使用平台 ScrollView，避免部分
           // Android 17 ROM 在 NestedScrollView 绘制空 ScrollBarDrawable 时崩溃。
          ScrollView partitionScroll = new ScrollView(this);
          // 关闭系统滚动条绘制，仅隐藏滚动条，不影响真实的上下滑动。
          partitionScroll.setVerticalScrollBarEnabled(false);
          partitionScroll.setFillViewport(true);
          partitionScroll.setNestedScrollingEnabled(true);
          partitionScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
          // 手指在分区列表内滑动时禁止外层页面拦截，抬手后恢复。
          partitionScroll.setOnTouchListener((view, event) -> {
              int action = event.getActionMasked();
              boolean disallow = action != android.view.MotionEvent.ACTION_UP
                      && action != android.view.MotionEvent.ACTION_CANCEL;
              ViewParent parent = view.getParent();
              while (parent != null) {
                  parent.requestDisallowInterceptTouchEvent(disallow);
                  if (parent instanceof View) parent = ((View) parent).getParent();
                  else break;
              }
              return false;
          });
          
          LinearLayout partitionList = new LinearLayout(this);
          partitionList.setOrientation(LinearLayout.VERTICAL);
          partitionScroll.addView(partitionList);
          LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, dp(400));
          scrollLp.topMargin = dp(8);
          panel.addView(partitionScroll, scrollLp);

          // 操作日志卡（固定高度200dp，可滚动，在底部）
          LinearLayout logCard = new LiquidGlassPanel(this, 10f);
          logCard.setOrientation(LinearLayout.VERTICAL);
          logCard.setPadding(dp(12), dp(10), dp(12), dp(10));
          
          // 添加渐变背景
          android.graphics.drawable.GradientDrawable logGradient = new android.graphics.drawable.GradientDrawable(
              android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
              new int[]{0x10fef3c7, 0x05fde68a}
          );
          logGradient.setCornerRadius(dp(10));
          logCard.setBackground(logGradient);
          
          LinearLayout.LayoutParams logCardLp = new LinearLayout.LayoutParams(-1, -2);
          logCardLp.topMargin = dp(12);
          panel.addView(logCard, logCardLp);

          TextView logTitle = new TextView(this);
          logTitle.setText(t("📜 操作日志", "📜 Operation Log"));
          logTitle.setTextSize(14);
          logTitle.setTypeface(null, 1);
          logTitle.setTextColor(0xff000000);
          logCard.addView(logTitle);

          partitionLogScroll = new ScrollView(this);
          partitionLogScroll.setVerticalScrollBarEnabled(true);
          partitionLogScroll.setScrollbarFadingEnabled(false);
          partitionLogScroll.setFillViewport(false);
          // 阻止父容器拦截触摸事件，允许滚动
          partitionLogScroll.setOnTouchListener((view, event) -> {
              ViewParent parent = view.getParent();
              if (parent != null) {
                  int action = event.getActionMasked();
                  parent.requestDisallowInterceptTouchEvent(action != android.view.MotionEvent.ACTION_UP && action != android.view.MotionEvent.ACTION_CANCEL);
              }
              return false;
          });
          
          partitionLogView = new TextView(this);
          partitionLogView.setText(t("等待操作...\n", "Waiting for operation...\n"));
          partitionLogView.setTextSize(12);
          partitionLogView.setTypeface(android.graphics.Typeface.MONOSPACE);
          partitionLogView.setTextColor(0xff000000);
          partitionLogView.setPadding(dp(10), dp(10), dp(10), dp(10));
          partitionLogView.setBackgroundResource(R.drawable.liquid_glass_panel);
          partitionLogScroll.addView(partitionLogView);
          LinearLayout.LayoutParams logScrollLp = new LinearLayout.LayoutParams(-1, dp(250));
          logScrollLp.topMargin = dp(10);
          logCard.addView(partitionLogScroll, logScrollLp);

          // 初始化分区列表
          loadPartitionList(partitionList, true);

          return panel;
      }

      private List<AdbManager.PartitionInfo> allPartitions = new ArrayList<>();
      private List<LinearLayout> partitionItemViews = new ArrayList<>();
      private LinearLayout partitionListContainer;
      private boolean currentExtractMode = true;
      private int currentSlotFilter = 0;
      private String currentSearchQuery = "";
      private Button executePartitionButton;
      private Button partitionFab;
      private Button partitionImagePickerButton;
      private TextView partitionLogView;
      private ScrollView partitionLogScroll;

      private void loadPartitionList(LinearLayout container, boolean extractMode) {
          this.partitionListContainer = container;
          this.currentExtractMode = extractMode;
          container.removeAllViews();
          partitionItemViews.clear();
          
          TextView loadingText = new TextView(this);
          loadingText.setText(t("正在读取分区信息...", "Loading partitions..."));
          loadingText.setTextSize(14);
          loadingText.setTextColor(0xff1a2332);
          loadingText.setGravity(Gravity.CENTER);
          loadingText.setPadding(dp(16), dp(32), dp(16), dp(32));
          container.addView(loadingText);
          
          // 后台读取分区
          new Thread(() -> {
              allPartitions = adbManager.getLocalPartitions();
              runOnUiThread(() -> {
                  container.removeAllViews();
                  if (allPartitions.isEmpty()) {
                      TextView emptyText = new TextView(this);
                      emptyText.setText(t("无法读取分区信息", "Failed to read partitions"));
                      emptyText.setTextSize(14);
                      emptyText.setTextColor(0xffaa0000);
                      emptyText.setGravity(Gravity.CENTER);
                      emptyText.setPadding(dp(16), dp(32), dp(16), dp(32));
                      container.addView(emptyText);
                  } else {
                      refreshPartitionList();
                  }
              });
          }).start();
      }

      private void refreshPartitionList() {
          if (partitionListContainer == null) return;
          partitionListContainer.removeAllViews();
          partitionItemViews.clear();
          
          for (AdbManager.PartitionInfo partition : allPartitions) {
              // 应用搜索过滤
              if (!currentSearchQuery.isEmpty() && !partition.name.toLowerCase().contains(currentSearchQuery.toLowerCase())) {
                  continue;
              }
              
              // 应用槽位过滤
              if (currentSlotFilter == 1 && !partition.slot.equals("A")) continue;
              if (currentSlotFilter == 2 && !partition.slot.equals("B")) continue;
              if (currentSlotFilter == 3 && !partition.slot.equals("其他")) continue;
              
              LinearLayout item = createPartitionItem(partition, currentExtractMode);
              partitionListContainer.addView(item);
              partitionItemViews.add(item);
          }
          
          if (partitionItemViews.isEmpty()) {
              TextView emptyText = new TextView(this);
              emptyText.setText(t("没有符合条件的分区", "No matching partitions"));
              emptyText.setTextSize(14);
              emptyText.setTextColor(0xff1a2332);
              emptyText.setGravity(Gravity.CENTER);
              emptyText.setPadding(dp(16), dp(32), dp(16), dp(32));
              partitionListContainer.addView(emptyText);
          }
      }

      private LinearLayout createPartitionItem(AdbManager.PartitionInfo partition, boolean extractMode) {
          LinearLayout item = new LiquidGlassPanel(this, 12f);
          item.setOrientation(LinearLayout.HORIZONTAL);
          item.setPadding(dp(16), dp(14), dp(16), dp(14));
          item.setGravity(Gravity.CENTER_VERTICAL);
          LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(-1, -2);
          itemLp.topMargin = dp(8);

          // 左侧文字信息
          LinearLayout textInfo = new LinearLayout(this);
          textInfo.setOrientation(LinearLayout.VERTICAL);
          item.addView(textInfo, new LinearLayout.LayoutParams(0, -2, 1f));

          TextView nameText = new TextView(this);
          nameText.setText(partition.name);
          nameText.setTextSize(16);
          nameText.setTextColor(0xff000000);
          nameText.setTypeface(null, 1);
          textInfo.addView(nameText);

          TextView infoText = new TextView(this);
          infoText.setText(partition.slot + " · " + t("大小: ", "Size: ") + partition.getSizeMB() + "MB");
          infoText.setTextSize(12);
          infoText.setTextColor(0xff2d3748);
          LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(-2, -2);
          infoLp.topMargin = dp(4);
          textInfo.addView(infoText, infoLp);

          TextView pathText = new TextView(this);
          pathText.setText(partition.blockDevice);
          pathText.setTextSize(10);
          pathText.setTextColor(0xff4a5568);
          LinearLayout.LayoutParams pathLp = new LinearLayout.LayoutParams(-2, -2);
          pathLp.topMargin = dp(2);
          textInfo.addView(pathText, pathLp);

          // 右侧勾选框
          android.widget.CheckBox checkBox = new android.widget.CheckBox(this);
          checkBox.setChecked(false);  // 显式设置为未选中
          checkBox.setTag(partition);
          item.addView(checkBox, new LinearLayout.LayoutParams(dp(40), dp(40)));

          item.setOnClickListener(v -> checkBox.setChecked(!checkBox.isChecked()));

          return item;
      }

      private void rebuildPartitionList(boolean extractMode) {
          currentExtractMode = extractMode;
          if (partitionFab != null) {
              partitionFab.setText(extractMode ?
                  t("⚡ 提取", "⚡ Extract") :
                  t("⚡ 刷入", "⚡ Flash"));
          }
          if (partitionImagePickerButton != null) {
              partitionImagePickerButton.setText(extractMode
                  ? t("📂 打开位置", "📂 Open")
                  : t("📥 选择 .img", "📥 Choose .img"));
          }
          refreshPartitionList();
      }

      private void filterPartitionsBySlot(int slotIndex) {
          currentSlotFilter = slotIndex;
          refreshPartitionList();
      }

      private void selectAllPartitions(boolean selectAll) {
          for (LinearLayout item : partitionItemViews) {
              android.widget.CheckBox checkBox = (android.widget.CheckBox) ((LinearLayout) item).getChildAt(1);
              checkBox.setChecked(selectAll);
          }
      }

      private void invertSelection() {
          for (LinearLayout item : partitionItemViews) {
              android.widget.CheckBox checkBox = (android.widget.CheckBox) ((LinearLayout) item).getChildAt(1);
              checkBox.setChecked(!checkBox.isChecked());
          }
      }

      private void showOnlySelected() {
          for (LinearLayout item : partitionItemViews) {
              android.widget.CheckBox checkBox = (android.widget.CheckBox) ((LinearLayout) item).getChildAt(1);
              item.setVisibility(checkBox.isChecked() ? View.VISIBLE : View.GONE);
          }
      }

      private void executePartitionOperation() {
          List<AdbManager.PartitionInfo> selectedPartitions = new ArrayList<>();
          for (LinearLayout item : partitionItemViews) {
              if (item.getVisibility() != View.VISIBLE) continue;
              android.widget.CheckBox checkBox = (android.widget.CheckBox) ((LinearLayout) item).getChildAt(1);
              if (checkBox.isChecked()) {
                  selectedPartitions.add((AdbManager.PartitionInfo) checkBox.getTag());
              }
          }
          
          if (selectedPartitions.isEmpty()) {
              Toast.makeText(this, t("请选择至少一个分区", "Please select at least one partition"), Toast.LENGTH_SHORT).show();
              return;
          }
          
          if (currentExtractMode) {
              extractPartitions(selectedPartitions);
          } else {
              flashPartitions(selectedPartitions);
          }
      }

      private void extractPartitions(List<AdbManager.PartitionInfo> partitions) {
          // v3.30.13：提取镜像保存到 /storage/emulated/0/PDNA/image（不再用 DsuManager 文件夹）
          String outputDir = "/storage/emulated/0/PDNA/image";
          
          // 构建分区列表提示（最多显示前8个）
          StringBuilder partListBuilder = new StringBuilder();
          int displayCount = Math.min(8, partitions.size());
          for (int i = 0; i < displayCount; i++) {
              partListBuilder.append("• ").append(partitions.get(i).name);
              if (partitions.get(i).sizeBytes > 0) {
                  long sizeMb = partitions.get(i).sizeBytes / (1024 * 1024);
                  partListBuilder.append(" (").append(sizeMb).append(" MB)");
              }
              partListBuilder.append("\n");
          }
          if (partitions.size() > displayCount) {
              partListBuilder.append("...(").append(t("共", "Total ")).append(partitions.size()).append(t("个", " partitions")).append(")");
          }
          
          new android.app.AlertDialog.Builder(this)
              .setTitle(t("确认提取", "Confirm Extract"))
              .setMessage(t("将提取以下分区:\n\n", "Extract the following partitions:\n\n") + 
                         partListBuilder.toString() + 
                         "\n" + t("保存到: ", "Save to: ") + outputDir)
              .setPositiveButton(t("提取", "Extract"), (dialog, which) -> {
                  new Thread(() -> {
                      runOnUiThread(() -> {
                          if (partitionLogView != null) partitionLogView.setText("");
                      });
                      partitionAppendLog(t("=== 开始提取分区 ===\n", "=== Start Extracting Partitions ===\n"));
                      partitionAppendLog(t("目标目录: ", "Target directory: ") + outputDir + "\n\n");
                      
                      com.topjohnwu.superuser.Shell.cmd("mkdir -p " + outputDir).exec();
                      int success = 0;
                      int failed = 0;
                      final int total = partitions.size();
                      
                      for (int i = 0; i < partitions.size(); i++) {
                          AdbManager.PartitionInfo partition = partitions.get(i);
                          String outputPath = outputDir + "/" + partition.name + ".img";
                          
                          final int currentIndex = i + 1;
                          partitionAppendLog(t("【", "[") + currentIndex + "/" + total + t("】开始提取", "] Start extracting ") + partition.name + t(" 分区\n", " partition\n"));
                          
                          // 执行 dd 命令，捕获标准输出和标准错误
                          com.topjohnwu.superuser.Shell.Result result = com.topjohnwu.superuser.Shell.cmd(
                              "dd if=" + partition.blockDevice + " of=" + outputPath + " 2>&1"
                          ).exec();
                          
                          // 输出 dd 的详细信息（包括 records in/out）
                          for (String line : result.getOut()) {
                              partitionAppendLog(line + "\n");
                          }
                          
                          if (result.isSuccess()) {
                              success++;
                              partitionAppendLog(t("- 已提取", "- Extracted ") + partition.name + 
                                               t(" 分区到：", " partition to: ") + outputPath + "\n\n");
                          } else {
                              failed++;
                              partitionAppendLog(t("- 提取", "- Extract ") + partition.name + t(" 失败\n\n", " failed\n\n"));
                          }
                      }
                      
                      final int finalSuccess = success;
                      final int finalFailed = failed;
                      partitionAppendLog(t("=== 提取完成 ===\n", "=== Extract Complete ===\n"));
                      partitionAppendLog(t("成功: ", "Success: ") + finalSuccess + "\n");
                      partitionAppendLog(t("失败: ", "Failed: ") + finalFailed + "\n");
                      
                      runOnUiThread(() -> {
                          new android.app.AlertDialog.Builder(this)
                              .setTitle(t("提取完成", "Extract Complete"))
                              .setMessage(t("成功: ", "Success: ") + finalSuccess + "\n" + 
                                        t("失败: ", "Failed: ") + finalFailed + "\n" +
                                        t("保存位置: ", "Location: ") + outputDir)
                              .setPositiveButton(t("确定", "OK"), null)
                              .show();
                      });
                  }).start();
              })
              .setNegativeButton(t("取消", "Cancel"), null)
              .show();
      }

      // 刷入模式：保存待刷入的分区
      private AdbManager.PartitionInfo pendingFlashPartition = null;
      private String pendingFlashImagePath = "";
      private String pendingFlashImageName = "";
      
      private void flashPartitions(List<AdbManager.PartitionInfo> partitions) {
          // 刷入模式只支持单个分区
          if (partitions.size() != 1) {
              Toast.makeText(this, t("刷入模式只能选择一个分区", "Flash mode: select only one partition"), Toast.LENGTH_SHORT).show();
              return;
          }
          if (pendingFlashImagePath == null || pendingFlashImagePath.isEmpty()) {
              Toast.makeText(this, t("请先点击上方“选择 .img”选择镜像", "Please choose a .img above first"), Toast.LENGTH_SHORT).show();
              return;
          }
          pendingFlashPartition = partitions.get(0);
          flashSinglePartitionWithPath(pendingFlashImagePath, pendingFlashImageName, pendingFlashPartition);
      }

      private void choosePartitionImageFile() {
          // v3.30.15：内置文件浏览器替换系统 SAF（层级深、找文件麻烦）
          FileBrowserDialog.show(this, t("选择 .img 镜像文件", "Select .img image"),
                  new String[]{".img"}, "/storage/emulated/0",
                  path -> preparePartitionImagePath(path));
      }
      
      private void preparePartitionImage(Uri fileUri) {
          // v3.30.13：优先解析本地真实路径（root dd 可直接读取，无需复制缓存），
          // 解析失败（非 primary 存储等）才回退复制到应用缓存
          String imagePath = resolveRealPath(fileUri);
          if (imagePath == null) imagePath = getPath(fileUri, "flash_temp.img");
          String name = displayName(fileUri);
          preparePartitionImagePath(imagePath, name);
      }

      /** v3.30.15：内置文件浏览器选中（真实绝对路径）—— root dd 直接读取 */
      private void preparePartitionImagePath(String imagePath) {
          preparePartitionImagePath(imagePath, new File(imagePath).getName());
      }

      private void preparePartitionImagePath(String imagePath, String selectedName) {
          if (selectedName == null || !selectedName.toLowerCase(Locale.ROOT).endsWith(".img")) {
              Toast.makeText(this, t("请选择 .img 镜像文件", "Please select a .img image"), Toast.LENGTH_SHORT).show();
              return;
          }
          if (imagePath == null || imagePath.isEmpty()) {
              Toast.makeText(this, t("无法获取文件路径", "Cannot get file path"), Toast.LENGTH_SHORT).show();
              return;
          }
          pendingFlashImagePath = imagePath;
          pendingFlashImageName = selectedName;
          partitionAppendLog("- " + t("已选择刷入镜像: ", "Selected flash image: ") + selectedName + "\n");
          partitionAppendLog("- " + t("刷入文件路径：", "Flash file path: ") + imagePath + "\n");
          Toast.makeText(this, t("已选择镜像，请在列表中选择一个分区后点击“刷入”", "Image selected; choose one partition and tap Flash"), Toast.LENGTH_LONG).show();
      }

      private void flashSinglePartitionWithPath(String imagePath, String selectedName, AdbManager.PartitionInfo partition) {
          if (partition == null || imagePath == null || imagePath.isEmpty()) return;
          
          new android.app.AlertDialog.Builder(this)
              .setTitle(t("⚠️ 确认刷入", "⚠️ Confirm Flash"))
              .setMessage(t("分区: ", "Partition: ") + partition.name + "\n" +
                         t("大小: ", "Size: ") + partition.getSizeMB() + " MB\n" +
                         t("设备: ", "Device: ") + partition.blockDevice + "\n\n" +
                         t("镜像文件: ", "Image file: ") + selectedName + "\n" + imagePath + "\n\n" +
                         t("⚠️ 刷入错误的镜像可能导致设备无法启动！", "⚠️ Flashing wrong image may brick your device!"))
              .setPositiveButton(t("确认刷入", "Confirm"), (dialog, which) -> {
                  new Thread(() -> {
                      runOnUiThread(() -> {
                          if (partitionLogView != null) partitionLogView.setText("");
                      });
                      
                      partitionAppendLog(t("=== 开始刷入分区 ===\n", "=== Start Flashing Partition ===\n"));
                      // v3.30.13：日志按原版风格 —— 您当前选择了xx分区 / 刷入文件路径 / 开始刷写
                      partitionAppendLog("- " + t("您当前选择了", "You selected ") + partition.name + t("分区\n", " partition\n"));
                      partitionAppendLog("- " + t("刷入文件路径：", "Flash file path: ") + imagePath + "\n\n");

                      partitionAppendLog("- " + t("开始刷写", "Start flashing ") + partition.name + t("分区\n", " partition\n"));
                      
                      // 执行 dd 命令
                      com.topjohnwu.superuser.Shell.Result result = com.topjohnwu.superuser.Shell.cmd(
                          "dd if=" + imagePath + " of=" + partition.blockDevice + " 2>&1"
                      ).exec();
                      
                      // 输出 dd 的详细信息
                      for (String line : result.getOut()) {
                          partitionAppendLog(line + "\n");
                      }
                      
                      if (result.isSuccess()) {
                          partitionAppendLog(t("\n✅ 已刷入 ", "\n✅ Flashed ") + partition.name + 
                                           t(" 分区成功\n", " partition successfully\n"));
                          partitionAppendLog(t("=== 刷入完成 ===\n", "=== Flash Complete ===\n"));
                          
                          runOnUiThread(() -> {
                              Toast.makeText(this, t("刷入成功", "Flash successful"), Toast.LENGTH_SHORT).show();
                          });
                      } else {
                          partitionAppendLog(t("\n❌ 刷入 ", "\n❌ Flash ") + partition.name + t(" 失败\n", " failed\n"));
                          partitionAppendLog(t("=== 刷入失败 ===\n", "=== Flash Failed ===\n"));
                          
                          runOnUiThread(() -> {
                              Toast.makeText(this, t("刷入失败", "Flash failed"), Toast.LENGTH_SHORT).show();
                          });
                      }
                  }).start();
              })
              .setNegativeButton(t("取消", "Cancel"), null)
              .show();
      }

      private void partitionAppendLog(String text) {
          if (partitionLogView == null) return;
          runOnUiThread(() -> {
              if (partitionLogView == null) return;
              partitionLogView.append(text);
              if (partitionLogScroll != null) partitionLogScroll.post(() -> partitionLogScroll.fullScroll(View.FOCUS_DOWN));
          });
      }

      private void addPartitionRebootButton(LinearLayout row, String label, String command, boolean needsConfirm, boolean iconAbove) {
          Button button = buildCompactPartitionActionButton(label, iconAbove);
          button.setOnClickListener(v -> {
              Haptics.perform(v);
              if (needsConfirm) {
                  new android.app.AlertDialog.Builder(this)
                      .setTitle(t("确认操作", "Confirm action"))
                      .setMessage(t(rebootDescription(label), rebootDescriptionEnglish(label)))
                      .setPositiveButton(t("确认", "Confirm"), (dialog, which) -> executePartitionReboot(command, label))
                      .setNegativeButton(t("取消", "Cancel"), null)
                      .show();
              } else {
                  executePartitionReboot(command, label);
              }
          });
          LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(0, -2, 1f);
          if (row.getChildCount() > 0) buttonLp.leftMargin = dp(8);
          row.addView(button, buttonLp);
      }

      private Button buildCompactPartitionActionButton(String label, boolean iconAbove) {
          Button button = new Button(this);
          button.setText(iconAbove ? "⚡\n" + label : "⚡ " + label);
          if (iconAbove) {
              button.setSingleLine(false);
              button.setGravity(Gravity.CENTER);
              button.setLines(2);
          }
          button.setTextSize(12);
          button.setTextColor(0xff000000);
          button.setBackgroundResource(R.drawable.liquid_glass_panel);
          button.setPadding(dp(16), dp(8), dp(16), dp(8));
          button.setMinWidth(0);
          button.setMinimumWidth(0);
          button.setAllCaps(false);
          return button;
      }

      private String rebootDescription(String label) {
          if (label.contains("关机")) return "正常关机";
          if (label.equals("重启")) return "正常重启";
          if (label.contains("热重启")) return "只重启系统界面而不重新引导系统（可能引发 Bug）";
          if (label.contains("RECOVERY")) return "重启到 Recovery 模式（俗称卡刷模式）";
          if (label.contains("FASTBOOTD")) return "重启到 FastbootD 模式";
          if (label.contains("FASTBOOT")) return "重启到 Fastboot 模式（俗称 USB 线刷模式）";
          if (label.contains("9008")) return "重启到 9008 模式，此模式仅限部分骁龙设备可用";
          return "设备可能立即重启。";
      }

      private String rebootDescriptionEnglish(String label) {
          if (label.contains("关机")) return "Normal shutdown";
          if (label.equals("重启")) return "Normal reboot";
          if (label.contains("热重启")) return "Restart the system interface without rebooting the system (may cause bugs)";
          if (label.contains("RECOVERY")) return "Reboot to Recovery mode";
          if (label.contains("FASTBOOTD")) return "Reboot to FastbootD mode";
          if (label.contains("FASTBOOT")) return "Reboot to Fastboot mode";
          if (label.contains("9008")) return "Reboot to 9008 (EDL), supported only on some Snapdragon devices";
          return "The device may reboot immediately.";
      }

      private void executePartitionReboot(String command, String label) {
          partitionAppendLog("\n=== " + label + " ===\n");
          partitionAppendLog("命令: " + command + "\n");
          new Thread(() -> {
              com.topjohnwu.superuser.Shell.Result rootCheck = com.topjohnwu.superuser.Shell.cmd("id").exec();
              String identity = String.join("\n", rootCheck.getOut());
              if (!rootCheck.isSuccess() || !identity.contains("uid=0")) {
                  partitionAppendLog("❌ ROOT 未授权，未执行操作。\n");
                  runOnUiThread(() -> Toast.makeText(this, t("ROOT 未授权", "ROOT permission unavailable"), Toast.LENGTH_SHORT).show());
                  return;
              }
              partitionAppendLog("ROOT 已确认，正在执行...\n");
              com.topjohnwu.superuser.Shell.Result result = com.topjohnwu.superuser.Shell.cmd(command + " 2>&1").exec();
              for (String line : result.getOut()) partitionAppendLog(line + "\n");
              if (!result.isSuccess()) {
                  partitionAppendLog("❌ 执行失败\n");
                  runOnUiThread(() -> Toast.makeText(this, t(label + "失败", label + " failed"), Toast.LENGTH_SHORT).show());
              } else {
                  partitionAppendLog("✅ 命令已发送\n");
              }
          }).start();
      }

      private Button buildPartitionFab() {
          Button fab = new Button(this);
          fab.setText(currentExtractMode ? t("⚡ 提取", "⚡ Extract") : t("⚡ 刷入", "⚡ Flash"));
          fab.setTextSize(14);
          fab.setTextColor(Color.WHITE);
          fab.setBackgroundResource(R.drawable.button_blue);
          fab.setPadding(dp(22), dp(14), dp(22), dp(14));
          fab.setTypeface(null, 1);
          fab.setStateListAnimator(null);
          fab.setElevation(dp(6));
          fab.setOnClickListener(v -> executePartitionOperation());
          return fab;
      }

      private LinearLayout buildMorePage() {
          LinearLayout content = new LinearLayout(this);
          content.setOrientation(LinearLayout.VERTICAL);
          content.setPadding(dp(20), dp(18), dp(20), dp(32));
          // 背景由 Activity 根容器绘制一次，子页保持透明以露出四角与底部色域。

          TextView title = new TextView(this);
          title.setText("更多功能");
          title.setTextSize(24);
           title.setTextColor(Color.WHITE);
          title.setTypeface(null, 1);
          title.setGravity(Gravity.CENTER_VERTICAL);
          title.setPadding(dp(12), 0, 0, 0);
           title.setBackgroundResource(R.drawable.liquid_glass_panel);
          content.addView(title, new LinearLayout.LayoutParams(-1, dp(58)));

          TextView intro = new TextView(this);
          intro.setText("工具箱\n扩展设备能力，独立管理 Linux 用户空间");
          intro.setTextSize(15);
           intro.setTextColor(0xffd8e7f4);
          intro.setPadding(dp(8), dp(22), 0, dp(12));
          content.addView(intro);
          content.addView(moreActionCard(android.R.drawable.ic_menu_manage, "ARM64 Linux 终端", "在线云端下载，支持 ROOT chroot 运行", 0xff198a9b,
                  () -> startActivity(new Intent(this, LinuxTerminalActivity.class))));
          content.addView(moreActionCard(android.R.drawable.ic_menu_upload, "本地安装 rootfs", "导入 .tar.gz 或 .tar.xz 压缩包作为本地终端环境", 0xff7651b5,
                  this::pickMoreRootfs));

          boolean found = false;
          for (LinuxImages.Image image : LinuxImages.ALL) {
              if (!LinuxImages.hasUsableShell(LinuxImages.environment(this, image))) continue;
              found = true;
              content.addView(moreEnvironmentCard(image));
          }
          TextView tip = new TextView(this);
          tip.setText(found ? "已安装环境可以直接进入终端。环境管理和软件包操作会在对应终端内完成。" : "云端镜像下载完成后，已安装环境会显示在这里。\n点击入口即可进入终端。");
          tip.setTextSize(13);
           tip.setTextColor(0xffb7c8d8);
          tip.setPadding(dp(8), dp(16), dp(8), 0);
          content.addView(tip);
          return content;
      }

      private LinearLayout moreActionCard(int icon, String heading, String subtitle, int color, Runnable action) {
          LinearLayout card = new LinearLayout(this);
          card.setGravity(Gravity.CENTER_VERTICAL);
          card.setPadding(dp(16), dp(13), dp(14), dp(13));
           card.setBackgroundResource(R.drawable.liquid_glass_panel);
          ImageView mark = new ImageView(this);
          mark.setImageResource(icon);
          mark.setColorFilter(Color.WHITE);
          mark.setPadding(dp(8), dp(8), dp(8), dp(8));
          mark.setBackgroundResource(color == 0xff7651b5 ? R.drawable.icon_tile_purple : R.drawable.icon_tile_teal);
          card.addView(mark, new LinearLayout.LayoutParams(dp(52), dp(52)));
          LinearLayout words = new LinearLayout(this);
          words.setOrientation(LinearLayout.VERTICAL);
          words.setPadding(dp(16), 0, dp(8), 0);
          TextView name = new TextView(this);
          name.setText(heading);
          name.setTextSize(16);
          name.setTextColor(0xff182b54);
          name.setTypeface(null, 1);
          words.addView(name, new LinearLayout.LayoutParams(-1, dp(32)));
          TextView detail = new TextView(this);
          detail.setText(subtitle);
          detail.setTextSize(12);
          detail.setTextColor(0xff5d6b84);
          words.addView(detail, new LinearLayout.LayoutParams(-1, dp(42)));
          card.addView(words, new LinearLayout.LayoutParams(0, dp(78), 1));
          TextView arrow = new TextView(this);
          arrow.setText("›");
          arrow.setTextSize(28);
          arrow.setTextColor(color);
          arrow.setGravity(Gravity.CENTER);
          card.addView(arrow, new LinearLayout.LayoutParams(dp(28), dp(56)));
          card.setOnClickListener(v -> action.run());
          LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(106));
          params.setMargins(0, dp(12), 0, 0);
          card.setLayoutParams(params);
          return card;
      }

      private LinearLayout moreEnvironmentCard(LinuxImages.Image image) {
          LinearLayout card = new LinearLayout(this);
          card.setOrientation(LinearLayout.VERTICAL);
           card.setPadding(dp(12), dp(14), dp(12), dp(14));
           card.setBackgroundResource(R.drawable.liquid_glass_panel);
          LinearLayout heading = new LinearLayout(this);
          heading.setGravity(Gravity.CENTER_VERTICAL);
          LinearLayout nameColumn = new LinearLayout(this);
          nameColumn.setOrientation(LinearLayout.VERTICAL);
          TextView name = new TextView(this);
          name.setText(image.name);
          name.setTextSize(18);
          name.setTextColor(0xff14233f);
          name.setTypeface(null, 1);
          nameColumn.addView(name, new LinearLayout.LayoutParams(-1, dp(30)));
          TextView status = new TextView(this);
          status.setText("已安装 · 可直接进入终端");
          status.setTextSize(13);
          status.setTextColor(0xff61708a);
          nameColumn.addView(status, new LinearLayout.LayoutParams(-1, dp(26)));
          heading.addView(nameColumn, new LinearLayout.LayoutParams(-1, dp(56)));
          card.addView(heading);
          LinearLayout actions = new LinearLayout(this);
          actions.setGravity(Gravity.CENTER_VERTICAL);
          Button enter = moreButton("进入终端", R.drawable.button_green, Color.WHITE);
          enter.setOnClickListener(v -> openMoreEnvironment(image));
          actions.addView(enter, new LinearLayout.LayoutParams(0, dp(44), 1));
          Button remove = moreButton("移除环境", R.drawable.remove_pill, 0xffc62828);
          remove.setOnClickListener(v -> confirmMoreRemove(image));
          LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(0, dp(44), 1);
          removeParams.setMargins(dp(10), 0, 0, 0);
          actions.addView(remove, removeParams);
          LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, dp(44));
          actionsParams.setMargins(0, dp(12), 0, 0);
          card.addView(actions, actionsParams);
          LinearLayout storage = new LinearLayout(this);
          storage.setGravity(Gravity.CENTER_VERTICAL);
          TextView usage = new TextView(this);
          usage.setText("正在计算存储占用...");
          usage.setTextSize(12);
          usage.setTextColor(0xff52617b);
           usage.setMaxLines(2);
           storage.addView(usage, new LinearLayout.LayoutParams(0, -2, 1));
          Button clear = moreButton("清理系统数据", R.drawable.remove_pill, 0xffc62828);
          clear.setOnClickListener(v -> confirmMoreClear(image));
           storage.addView(clear, new LinearLayout.LayoutParams(0, dp(44), .95f));
          Button archive = moreButton("清理下载包", R.drawable.remove_pill, 0xff7651b5);
          archive.setOnClickListener(v -> confirmMoreArchive(image));
           LinearLayout.LayoutParams archiveParams = new LinearLayout.LayoutParams(0, dp(44), .95f);
          archiveParams.setMargins(dp(6), 0, 0, 0);
          storage.addView(archive, archiveParams);
           LinearLayout.LayoutParams storageParams = new LinearLayout.LayoutParams(-1, -2);
          storageParams.setMargins(0, dp(12), 0, 0);
          card.addView(storage, storageParams);
          LinearLayout packages = new LinearLayout(this);
          packages.setGravity(Gravity.CENTER_VERTICAL);
          TextView packageCount = new TextView(this);
          packageCount.setText("正在读取用户安装包...");
          packageCount.setTextSize(12);
          packageCount.setTextColor(0xff52617b);
           packageCount.setMaxLines(2);
           packages.addView(packageCount, new LinearLayout.LayoutParams(0, -2, 1));
          Button packageButton = moreButton("查看并卸载", R.drawable.remove_pill, 0xff7651b5);
          packageButton.setOnClickListener(v -> showMorePackages(image));
           packages.addView(packageButton, new LinearLayout.LayoutParams(0, dp(44), .95f));
           LinearLayout.LayoutParams packagesParams = new LinearLayout.LayoutParams(-1, -2);
          packagesParams.setMargins(0, dp(10), 0, 0);
          card.addView(packages, packagesParams);
          moreWorker.execute(() -> {
              String size = "系统数据 " + formatBytes(LinuxImages.storageBytes(LinuxImages.environment(this, image))) + " · 下载包 " + formatBytes(LinuxImages.storageBytes(LinuxImages.archive(this, image)));
              java.util.List<String> packagesFound = morePackages(image);
              runOnUiThread(() -> { usage.setText(size); packageCount.setText("用户安装包：" + packagesFound.size() + " 个"); });
          });
          LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
          params.setMargins(0, dp(14), 0, 0);
          card.setLayoutParams(params);
          return card;
      }

      private Button moreButton(String value, int background, int color) {
          Button button = new Button(this);
          button.setText(value);
          button.setTextSize(11);
          button.setAllCaps(false);
          button.setTextColor(color);
          button.setMinWidth(0);
          button.setMinHeight(0);
          button.setBackgroundResource(background);
          return button;
      }

      private java.util.List<String> morePackages(LinuxImages.Image image) {
          java.util.List<String> result = new java.util.ArrayList<>();
          java.io.File record = new java.io.File(LinuxImages.environment(this, image), "var/lib/linux-dsu/user-packages");
          try { for (String line : java.nio.file.Files.readAllLines(record.toPath(), java.nio.charset.StandardCharsets.UTF_8)) { String value = line.trim(); if (!value.isEmpty() && !result.contains(value)) result.add(value); } } catch (Exception ignored) { }
          return result;
      }

      private void showMorePackages(LinuxImages.Image image) {
          moreWorker.execute(() -> { java.util.List<String> packages = morePackages(image); runOnUiThread(() -> { String message = packages.isEmpty() ? "没有检测到用户安装包。" : android.text.TextUtils.join("\n", packages); new AlertDialog.Builder(this).setTitle(image.name + " 用户安装包").setMessage(message).setPositiveButton("关闭", null).show(); }); });
      }

      private void confirmMoreClear(LinuxImages.Image image) {
          new AlertDialog.Builder(this).setTitle("清理系统数据").setMessage("会清空已解压系统数据，保留下载包，后续可以从下载包恢复。").setNegativeButton("取消", null).setPositiveButton("清理", (dialog, which) -> moreWorker.execute(() -> { removeMoreFiles(LinuxImages.environment(this, image), null); runOnUiThread(() -> { if (currentTab == 3) selectTab(3); }); })).show();
      }

      private void confirmMoreArchive(LinuxImages.Image image) {
          new AlertDialog.Builder(this).setTitle("清理下载包").setMessage("会删除下载压缩包，已安装系统数据会保留。").setNegativeButton("取消", null).setPositiveButton("清理", (dialog, which) -> moreWorker.execute(() -> { deleteMoreTree(LinuxImages.archive(this, image)); runOnUiThread(() -> { if (currentTab == 3) selectTab(3); }); })).show();
      }

      private void confirmMoreRemove(LinuxImages.Image image) {
          new AlertDialog.Builder(this).setTitle("移除终端环境").setMessage("将删除 " + image.name + " 的全部系统数据和下载包。").setNegativeButton("取消", null).setPositiveButton("移除", (dialog, which) -> moreWorker.execute(() -> {
              LinuxTerminalActivity.stopRunningEnvironment(image.id());
              String root = LinuxImages.environment(this, image).getAbsolutePath().replace("'", "'\\''");
              String archive = LinuxImages.archive(this, image).getAbsolutePath().replace("'", "'\\''");
              try {
                  java.lang.Process process = new ProcessBuilder("/system/bin/su", "-c", "rm -rf '" + root + "' '" + archive + "'").start();
                  process.waitFor();
              } catch (Exception ignored) { }
              runOnUiThread(() -> { if (currentTab == 3) selectTab(3); Toast.makeText(this, "环境已移除", Toast.LENGTH_SHORT).show(); });
          })).show();
      }

      private void removeMoreFiles(java.io.File root, java.io.File archive) {
          if (root != null) deleteMoreTree(root);
          if (archive != null) deleteMoreTree(archive);
      }

      private boolean deleteMoreTree(java.io.File file) {
          if (file == null || !file.exists()) return true;
          if (file.isDirectory()) { java.io.File[] children = file.listFiles(); if (children != null) for (java.io.File child : children) deleteMoreTree(child); }
          try { return java.nio.file.Files.deleteIfExists(file.toPath()); } catch (Exception ignored) { return false; }
      }

      private void openMoreEnvironment(LinuxImages.Image image) {
          Intent intent = new Intent(this, LinuxTerminalActivity.terminalActivity(image.id()));
          intent.putExtra("open_image", image.id());
          intent.putExtra("open_terminal", true);
          intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
          startActivity(intent);
      }

      private void pickMoreRootfs() {
          // v3.30.15：内置文件浏览器替换系统 SAF
          FileBrowserDialog.show(this, t("选择 Rootfs 镜像", "Select rootfs"),
                  new String[]{".gz", ".xz", ".tgz", ".tar", ".img"}, "/storage/emulated/0",
                  path -> {
                      Intent intent = new Intent(this, LinuxTerminalActivity.class);
                      intent.putExtra("local_install", true);
                      intent.setData(Uri.fromFile(new File(path)));
                      startActivity(intent);
                  });
      }

      private View buildSalarySummaryCard() {
          LiquidGlassPanel card = new LiquidGlassPanel(this);
          card.setOrientation(LinearLayout.VERTICAL);
          card.setPadding(dp(14), dp(12), dp(14), dp(12));
          TextView title = text("📈  " + t("本月工资概览", "Monthly Salary Overview"), 14, 0xff17334f);
          title.setTypeface(null, Typeface.BOLD);
          card.addView(title, new LinearLayout.LayoutParams(-1, dp(28)));
          String month = new java.text.SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Calendar.getInstance().getTime());
          SharedPreferences salary = getSharedPreferences("salary", MODE_PRIVATE);
          float gross = salary.getFloat("income_" + month, 0f);
          float net = salary.getFloat("net_" + month, 0f);
          float overtime = salary.getFloat("overtime_" + month, 0f);
          TextView summary = text(String.format(Locale.getDefault(), "应发 ¥%.2f   实发 ¥%.2f   加班 %.1fh", gross, net, overtime), 12, 0xff52617b);
          card.addView(summary, new LinearLayout.LayoutParams(-1, dp(24)));
          float max = Math.max(1f, Math.max(gross, Math.max(net, overtime * 100f)));
          addSalaryBar(card, "应发", gross, max, 0xff2e9fbc);
          addSalaryBar(card, "实发", net, max, 0xff4d78c7);
          addSalaryBar(card, "加班", overtime * 100f, max, 0xffc77943);
          card.setOnClickListener(v -> {
              Haptics.perform(v);
              startActivity(new Intent(this, SalaryActivity.class));
              overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
          });
          return card;
      }

      private void addSalaryBar(LinearLayout parent, String label, float value, float max, int color) {
          LinearLayout row = new LinearLayout(this);
          row.setGravity(Gravity.CENTER_VERTICAL);
          TextView labelView = text(label, 11, 0xff52617b);
          row.addView(labelView, new LinearLayout.LayoutParams(dp(34), dp(22)));
          FrameLayout track = new FrameLayout(this);
          GradientDrawable trackBg = new GradientDrawable();
          trackBg.setColor(0x33FFFFFF);
          trackBg.setCornerRadius(dp(8));
          track.setBackground(trackBg);
          View fill = new View(this);
          GradientDrawable fillBg = new GradientDrawable();
          fillBg.setColor(color);
          fillBg.setCornerRadius(dp(8));
          fill.setBackground(fillBg);
          float ratio = Math.max(0f, Math.min(1f, value / max));
          FrameLayout.LayoutParams fillLp = new FrameLayout.LayoutParams(0, -1);
          fillLp.width = Math.max(dp(3), (int) (dp(170) * ratio));
          track.addView(fill, fillLp);
          LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(0, dp(10), 1f);
          trackLp.setMargins(dp(6), 0, dp(8), 0);
          row.addView(track, trackLp);
          TextView valueView = text(String.format(Locale.getDefault(), "%.1f", value), 10, 0xff52617b);
          row.addView(valueView, new LinearLayout.LayoutParams(dp(44), dp(22)));
          parent.addView(row, new LinearLayout.LayoutParams(-1, dp(24)));
      }

      private LinearLayout buildSettingsPage() {
         LinearLayout page = page(t("设置", "Settings"));

         // 在标题栏添加透明开关
         TextView heading = (TextView) page.getChildAt(0);
         LinearLayout titleBar = new LinearLayout(this);
         titleBar.setOrientation(LinearLayout.HORIZONTAL);
         titleBar.setGravity(Gravity.CENTER_VERTICAL);
         titleBar.setPadding(dp(14), 0, dp(14), 0);
         page.removeViewAt(0);
         // v3.10.0：设置页现从 DNA 页进入，标题栏最左加返回按钮
         Button backToDna = new Button(this);
         backToDna.setText("<");
         backToDna.setTextSize(20);
         backToDna.setMinWidth(0);
         backToDna.setMinHeight(0);
         backToDna.setAllCaps(false);
         backToDna.setTypeface(null, 1);
         backToDna.setTextColor(0xff17334f);
         backToDna.setBackgroundResource(R.drawable.liquid_glass_panel);
         backToDna.setStateListAnimator(null);
         backToDna.setOnClickListener(v -> {
             Haptics.perform(v);
             dnaSettingsMode = false;
             swapTabOne();
         });
         titleBar.addView(backToDna, new LinearLayout.LayoutParams(dp(42), dp(48)));
         titleBar.addView(heading, new LinearLayout.LayoutParams(0, dp(58), 1));
         
         // 透明开关（右侧）
         Switch transparencySwitch = new Switch(this);
         transparencySwitch.setText(t("透明", "Trans"));
         transparencySwitch.setTextColor(Color.WHITE);
         transparencySwitch.setTextSize(13);
         boolean isTransparent = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("window_transparent_bg", false);
         transparencySwitch.setChecked(isTransparent);
         transparencySwitch.setOnCheckedChangeListener((button, checked) -> {
             getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("window_transparent_bg", checked).apply();
             applyWindowTransparency(checked);
         });
         titleBar.addView(transparencySwitch, new LinearLayout.LayoutParams(-2, -2));
         page.addView(titleBar, 0, new LinearLayout.LayoutParams(-1, dp(58)));
         
         LinearLayout card = new LinearLayout(this);
         card.setOrientation(LinearLayout.VERTICAL);
         card.setPadding(dp(14), dp(10), dp(14), dp(10));
          card.setBackgroundResource(R.drawable.liquid_glass_panel);
         TextView language = text(t("语言", "Language"), 16, Color.rgb(20, 29, 55));
         language.setTypeface(null, 1);
         card.addView(language, new LinearLayout.LayoutParams(-1, dp(38)));
         RadioGroup group = new RadioGroup(this);
         group.setOrientation(RadioGroup.VERTICAL);
         group.setPadding(dp(8), dp(4), dp(8), dp(4));
          group.setBackgroundResource(R.drawable.liquid_glass_panel);
         RadioButton system = new RadioButton(this);
         system.setId(100);
         system.setText(t("系统语言", "Use system language"));
         RadioButton chinese = new RadioButton(this);
         chinese.setId(101);
         chinese.setText("中文");
         RadioButton englishButton = new RadioButton(this);
         englishButton.setId(102);
         englishButton.setText("English");
         system.setTextSize(14);
         chinese.setTextSize(14);
         englishButton.setTextSize(14);
         group.addView(system, new RadioGroup.LayoutParams(-1, dp(48)));
         group.addView(chinese, new RadioGroup.LayoutParams(-1, dp(48)));
         group.addView(englishButton, new RadioGroup.LayoutParams(-1, dp(48)));
         group.check(languageMode == 2 ? 102 : languageMode == 1 ? 101 : 100);
         system.setOnClickListener(v -> saveLanguage(0));
         chinese.setOnClickListener(v -> saveLanguage(1));
         englishButton.setOnClickListener(v -> saveLanguage(2));
          card.addView(group, new LinearLayout.LayoutParams(-1, dp(160)));
           LinearLayout.LayoutParams languageParams = new LinearLayout.LayoutParams(-1, -2);
           languageParams.setMargins(0, 0, 0, dp(18));
           page.addView(card, languageParams);
          LinearLayout screenCard = new LinearLayout(this);
          screenCard.setGravity(Gravity.CENTER_VERTICAL);
          screenCard.setPadding(dp(14), dp(6), dp(10), dp(6));
          screenCard.setBackgroundResource(R.drawable.liquid_glass_panel);
          Switch keepScreen = new Switch(this);
          keepScreen.setText(t("保持亮屏", "Keep screen on"));
          keepScreen.setTextColor(Color.rgb(20, 29, 55));
          keepScreen.setTextSize(14);
          keepScreen.setChecked(keepScreenOn);
          keepScreen.setOnCheckedChangeListener((button, checked) -> {
              keepScreenOn = checked;
              getPreferences(MODE_PRIVATE).edit().putBoolean("keep_screen_on", checked).apply();
              applyKeepScreenOn();
          });
          screenCard.addView(keepScreen, new LinearLayout.LayoutParams(-1, dp(48)));
          LinearLayout.LayoutParams screenParams = new LinearLayout.LayoutParams(-1, dp(60));
          screenParams.setMargins(0, 0, 0, dp(18));
          page.addView(screenCard, screenParams);

          // v3.41.12：存储清理卡 —— root 属主的提取/刷机缓存副本不计入系统「缓存」统计
          // （设置里显示 0B、清除缓存按钮灰色），只能在此清理
          LinearLayout cleanCard = new LinearLayout(this);
          cleanCard.setOrientation(LinearLayout.VERTICAL);
          cleanCard.setPadding(dp(14), dp(10), dp(14), dp(10));
          cleanCard.setBackgroundResource(R.drawable.liquid_glass_panel);
          TextView cleanTitle = text("🧹 " + t("存储清理", "Storage Clean"), 15, Color.rgb(20, 29, 55));
          cleanTitle.setTypeface(null, 1);
          cleanCard.addView(cleanTitle, new LinearLayout.LayoutParams(-1, -2));
          TextView cleanDesc = text(t("清理 ROM 提取 / 刷机产生的缓存副本（root 属主文件不计入系统缓存统计，系统「清除缓存」清不掉）",
                  "Clean extraction & flashing cache copies (root-owned files not counted as system cache)"), 11, 0xff5a6b82);
          cleanDesc.setPadding(0, dp(4), 0, dp(8));
          cleanCard.addView(cleanDesc, new LinearLayout.LayoutParams(-1, -2));
          Button cleanBtn = new Button(this, null, 0);
          cleanBtn.setText(t("计算中 …", "Scanning..."));
          cleanBtn.setAllCaps(false);
          cleanBtn.setTextSize(13.5f);
          cleanBtn.setTypeface(null, 1);
          cleanBtn.setTextColor(0xff172b4d);
          cleanBtn.setGravity(Gravity.CENTER);
          cleanBtn.setPadding(0, 0, 0, 0);
          GradientDrawable cleanBg = new GradientDrawable();
          cleanBg.setColor(0x664CAF50);
          cleanBg.setCornerRadius(dp(16));
          cleanBg.setStroke(Math.max(1, dp(1)), 0x99FFFFFF);
          cleanBtn.setBackground(cleanBg);
          cleanBtn.setStateListAnimator(null);
          cleanBtn.setMinWidth(0);
          cleanBtn.setMinHeight(0);
          cleanBtn.setEnabled(false);
          cleanCard.addView(cleanBtn, new LinearLayout.LayoutParams(-1, dp(42)));
          LinearLayout.LayoutParams cleanParams = new LinearLayout.LayoutParams(-1, -2);
          cleanParams.setMargins(0, 0, 0, dp(18));
          page.addView(cleanCard, cleanParams);
          final Button fCleanBtn = cleanBtn;
          // 后台统计可清理空间（extracted 目录文件多，stat 遍历勿卡 UI）
          new Thread(() -> {
              final long bytes = DnaTools.INSTANCE.cleanableBytes(this);
              runOnUiThread(() -> {
                  if (isFinishing() || isDestroyed()) return;
                  fCleanBtn.setEnabled(true);
                  fCleanBtn.setText(bytes > 0
                          ? t("深度清理（可释放 ", "Clean (frees ") + fmtSize(bytes) + t("）", ")")
                          : t("无可清理缓存", "Nothing to clean"));
              });
          }, "clean-scan").start();
          cleanBtn.setOnClickListener(v -> {
              Haptics.perform(v);
              fCleanBtn.setEnabled(false);
              fCleanBtn.setText(t("正在清理 …", "Cleaning..."));
              new Thread(() -> {
                  // v3.41.22：deepClean + deepCleanAll 双保险（点名清单 + 全扫描白名单，su 兜底 root 属主文件）
                  DnaTools.INSTANCE.deepClean(this);
                  final long freed = DnaTools.INSTANCE.deepCleanAll(this);
                  runOnUiThread(() -> {
                      if (isFinishing() || isDestroyed()) return;
                      fCleanBtn.setEnabled(true);
                      fCleanBtn.setText(t("深度清理", "Clean"));
                      Toast.makeText(this, freed > 0
                              ? t("✓ 已释放 ", "✓ Freed ") + fmtSize(freed)
                              : t("缓存已清理", "Cache is clean"), Toast.LENGTH_LONG).show();
                  });
              }, "clean-run").start();
          });
          
          // v3.30.23：「关于 Dsu 管理器」入口已并入「特别鸣谢」页（ThanksActivity）
          // v3.30.21：特别鸣谢入口（对齐原版 DNA thanks 声明：不分先后，如有遗忘望提醒）
          LiquidGlassPanel thanksCard = new LiquidGlassPanel(this);
          thanksCard.setGravity(Gravity.CENTER_VERTICAL);
          thanksCard.setPadding(dp(14), dp(6), dp(10), dp(6));
          Button thanksButton = new Button(this);
          thanksButton.setText("❤  " + t("特别鸣谢", "Credits"));
          thanksButton.setAllCaps(false);
          thanksButton.setTextColor(0xff1a3356);
          thanksButton.setTextSize(15);
          thanksButton.setTypeface(null, 1);
          thanksButton.setBackgroundColor(Color.TRANSPARENT);
          thanksButton.setOnClickListener(v -> {
              Haptics.perform(v);
              Intent thanksIntent = new Intent(this, ThanksActivity.class);
              startActivity(thanksIntent);
              overridePendingTransition(R.anim.flip_in, R.anim.flip_out);
          });
          thanksCard.addView(thanksButton, new LinearLayout.LayoutParams(-1, dp(48)));
          LinearLayout.LayoutParams creditParams = new LinearLayout.LayoutParams(-1, dp(60));
          creditParams.setMargins(0, 0, 0, dp(18));
          page.addView(thanksCard, creditParams);
          
          // v3.51.97：工资工时记账入口
          LiquidGlassPanel salaryCard = new LiquidGlassPanel(this);
          salaryCard.setGravity(Gravity.CENTER_VERTICAL);
          salaryCard.setPadding(dp(14), dp(6), dp(10), dp(6));
          Button salaryButton = new Button(this);
          salaryButton.setText("💰  " + t("工资工时记账", "Salary Tracker"));
          salaryButton.setAllCaps(false);
          salaryButton.setTextColor(0xff1a3356);
          salaryButton.setTextSize(15);
          salaryButton.setTypeface(null, 1);
          salaryButton.setBackgroundColor(Color.TRANSPARENT);
          salaryButton.setOnClickListener(v -> {
              Haptics.perform(v);
              Intent salaryIntent = new Intent(this, SalaryActivity.class);
              startActivity(salaryIntent);
              overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
          });
          salaryCard.addView(salaryButton, new LinearLayout.LayoutParams(-1, dp(48)));
          LinearLayout.LayoutParams salaryParams = new LinearLayout.LayoutParams(-1, dp(60));
          salaryParams.setMargins(0, 0, 0, dp(18));
          page.addView(salaryCard, salaryParams);
          View salarySummary = buildSalarySummaryCard();
          LinearLayout.LayoutParams salarySummaryParams = new LinearLayout.LayoutParams(-1, -2);
          salarySummaryParams.setMargins(0, -dp(8), 0, dp(18));
          page.addView(salarySummary, salarySummaryParams);
          
         TextView updateTitle = text(t("更新", "Updates"), 16, Color.rgb(35, 126, 91));
         updateTitle.setTypeface(null, 1);
         updateTitle.setPadding(dp(14), 0, dp(14), 0);
         updateTitle.setBackgroundResource(R.drawable.settings_update_title);
         LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, dp(38));
         titleParams.setMargins(0, dp(18), 0, dp(8));
         page.addView(updateTitle, titleParams);
         embeddedUpdateStatus = text(t("点击检查最新版本。", "Tap check for the latest release."), 13, Color.rgb(80, 88, 105));
         embeddedUpdateStatus.setPadding(dp(14), dp(10), dp(14), dp(10));
          embeddedUpdateStatus.setBackgroundResource(R.drawable.liquid_glass_panel);
          LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, dp(56));
          statusParams.setMargins(0, 0, 0, dp(10));
          page.addView(embeddedUpdateStatus, statusParams);
          embeddedReleaseNotes = text("", 13, Color.rgb(80, 88, 105));
          embeddedReleaseNotes.setGravity(Gravity.TOP | Gravity.START);
          embeddedReleaseNotes.setPadding(dp(14), dp(10), dp(14), dp(10));
          // v3.41.11：BoundedScrollView（OTG 触摸模型，整条祖先链独占）替换裸 ScrollView
          embeddedReleaseNotesScroll = new DnaActivity.BoundedScrollView(this, 0);
          embeddedReleaseNotesScroll.setFillViewport(false);
          embeddedReleaseNotesScroll.setVerticalScrollBarEnabled(true);
          embeddedReleaseNotesScroll.setBackgroundResource(R.drawable.liquid_glass_panel);
          embeddedReleaseNotesScroll.addView(embeddedReleaseNotes, new ScrollView.LayoutParams(-1, -2));
          embeddedReleaseNotesScroll.setVisibility(View.GONE);
          LinearLayout.LayoutParams notesParams = new LinearLayout.LayoutParams(-1, dp(180));
          notesParams.setMargins(0, 0, 0, dp(10));
           page.addView(embeddedReleaseNotesScroll, notesParams);
         Button check = new Button(this);
         check.setText(t("检查更新", "Check for updates"));
         check.setAllCaps(false);
          check.setBackgroundResource(R.drawable.liquid_glass_panel);
         check.setOnClickListener(v -> checkEmbeddedUpdates());
          LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(-1, dp(52));
          checkParams.setMargins(0, 0, 0, dp(10));
          page.addView(check, checkParams);
         embeddedDownloadButton = new Button(this);
         embeddedDownloadButton.setText(t("下载最新 APK", "Download latest APK"));
         embeddedDownloadButton.setAllCaps(false);
         embeddedDownloadButton.setTextColor(Color.WHITE);
         embeddedDownloadButton.setBackgroundResource(R.drawable.button_green);
         embeddedDownloadButton.setVisibility(View.GONE);
          LinearLayout.LayoutParams downloadParams = new LinearLayout.LayoutParams(-1, dp(52));
          downloadParams.setMargins(0, 0, 0, dp(10));
          page.addView(embeddedDownloadButton, downloadParams);
         embeddedDownloadHint = text(t("下载地址是 GitHub，网页打不开时可以使用魔法下载最新版。", "Download address: GitHub. Use a proxy or VPN when GitHub cannot be opened."), 12, Color.rgb(80, 88, 105));
         embeddedDownloadHint.setPadding(dp(14), dp(8), dp(14), dp(8));
          embeddedDownloadHint.setBackgroundResource(R.drawable.liquid_glass_panel);
         embeddedDownloadHint.setVisibility(View.GONE);
          LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, dp(58));
          hintParams.setMargins(0, 0, 0, dp(10));
          page.addView(embeddedDownloadHint, hintParams);
         TextView thanksTitle = text(t("感谢", "Acknowledgements"), 16, Color.rgb(181, 103, 39));
         thanksTitle.setTypeface(null, 1);
         thanksTitle.setPadding(dp(14), 0, dp(14), 0);
         thanksTitle.setBackgroundResource(R.drawable.settings_thanks_title);
         LinearLayout.LayoutParams thanksTitleParams = new LinearLayout.LayoutParams(-1, dp(38));
         thanksTitleParams.setMargins(0, dp(18), 0, dp(8));
         page.addView(thanksTitle, thanksTitleParams);
          TextView thanks = text(t("感谢酷安用户及 GitHub 用户 yangFenTuoZi 开发 Dsu 功能修改 img 无损替换功能。\n如有侵权，请联系作者，我们会及时删除相关内容。", "Thanks to Coolapk user and GitHub user yangFenTuoZi for developing the Dsu img lossless replacement feature.\nIf any content infringes your rights, please contact the author and it will be removed promptly."), 14, Color.rgb(80, 88, 105));
          thanks.setPadding(dp(14), dp(12), dp(14), dp(12));
           thanks.setBackgroundResource(R.drawable.liquid_glass_panel);
          LinearLayout.LayoutParams thanksParams = new LinearLayout.LayoutParams(-1, dp(92));
          thanksParams.setMargins(0, 0, 0, dp(10));
          page.addView(thanks, thanksParams);
           TextView version = text(t("Dsu 管理器 " + BuildConfig.VERSION_NAME, "Dsu Manager " + BuildConfig.VERSION_NAME), 13, Color.rgb(110, 118, 135));
          version.setPadding(dp(14), 0, dp(14), 0);
           version.setBackgroundResource(R.drawable.liquid_glass_panel);
          page.addView(version, new LinearLayout.LayoutParams(-1, dp(46)));
         // 终端从底部导航移入设置：保留原有 Linux 环境管理与 rootfs 导入能力。
         TextView terminalTitle = text(t("终端", "Terminal"), 16, Color.rgb(20, 29, 55));
         terminalTitle.setTypeface(null, 1);
         terminalTitle.setPadding(dp(4), dp(14), dp(4), dp(6));
         page.addView(terminalTitle, new LinearLayout.LayoutParams(-1, dp(48)));
         page.addView(moreActionCard(android.R.drawable.ic_menu_manage, "ARM64 Linux 终端", "在线云端下载，支持 ROOT chroot 运行", 0xff198a9b,
                 () -> startActivity(new Intent(this, LinuxTerminalActivity.class))));
         page.addView(moreActionCard(android.R.drawable.ic_menu_upload, "本地安装 rootfs", "导入 .tar.gz 或 .tar.xz 压缩包作为本地终端环境", 0xff7651b5,
                 this::pickMoreRootfs));
         boolean terminalFound = false;
         for (LinuxImages.Image image : LinuxImages.ALL) {
             if (!LinuxImages.hasUsableShell(LinuxImages.environment(this, image))) continue;
             terminalFound = true;
             page.addView(moreEnvironmentCard(image));
         }
         TextView terminalHint = text(terminalFound
                 ? "已安装环境可以直接进入终端，也可以在这里清理系统数据、下载包和用户安装包。"
                 : "云端镜像下载完成后，已安装的 Linux 环境会显示在这里。", 13, 0xff5d6b84);
         terminalHint.setPadding(dp(8), dp(12), dp(8), dp(12));
         page.addView(terminalHint, new LinearLayout.LayoutParams(-1, -2));
         return page;
      }

      private void checkEmbeddedUpdates() {
          embeddedUpdateStatus.setText(t("正在检查更新...", "Checking for updates..."));
          embeddedReleaseNotesScroll.setVisibility(View.GONE);
          embeddedDownloadButton.setVisibility(View.GONE);
          embeddedDownloadHint.setVisibility(View.GONE);
          new Thread(() -> {
              HttpURLConnection connection = null;
              try {
                   connection = (HttpURLConnection) new java.net.URL("https://api.github.com/repos/955xiaolan520/Dsu-Manager/releases/latest").openConnection();
                   connection.setRequestMethod("GET");
                   connection.setConnectTimeout(10000);
                   connection.setReadTimeout(10000);
                   connection.setRequestProperty("Accept", "application/vnd.github+json");
                    connection.setRequestProperty("User-Agent", "Dsu-Manager-Android/" + BuildConfig.VERSION_NAME);
                   connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
                   connection.setUseCaches(false);
                   int responseCode = connection.getResponseCode();
                   if (responseCode < 200 || responseCode >= 300) throw new IOException("GitHub HTTP " + responseCode);
                   StringBuilder body = new StringBuilder();
                  try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                      String line;
                      while ((line = reader.readLine()) != null) body.append(line);
                  }
                  JSONObject release = new JSONObject(body.toString());
                  String version = release.optString("tag_name", "").replaceFirst("^v", "");
                   String notes = trimReleaseNotes(release.optString("body", ""));
                  String url = release.optString("html_url", "https://github.com/955xiaolan520/Dsu-Manager/releases");
                  JSONArray assets = release.optJSONArray("assets");
                  if (assets != null) for (int i = 0; i < assets.length(); i++) {
                      JSONObject asset = assets.optJSONObject(i);
                      if (asset != null && asset.optString("name", "").endsWith(".apk") && !asset.optString("name", "").contains("debug")) {
                          url = asset.optString("browser_download_url", url);
                          break;
                      }
                  }
                  final String finalVersion = version;
                  final String finalNotes = notes;
                  final String finalUrl = url;
                  mainHandler.post(() -> {
                       boolean newer = isVersionNewer(finalVersion, BuildConfig.VERSION_NAME);
                       embeddedUpdateStatus.setText(newer ? t("发现新版本: " + finalVersion, "New version available: " + finalVersion) : t("当前已是最新版本: " + BuildConfig.VERSION_NAME, "You are using the latest version: " + BuildConfig.VERSION_NAME));
                      embeddedReleaseNotes.setText(t("更新内容:\n", "Release notes:\n") + (finalNotes.isEmpty() ? t("暂无更新说明。", "No release notes.") : finalNotes));
                      embeddedReleaseNotesScroll.setVisibility(View.VISIBLE);
                      if (newer) {
                          embeddedDownloadButton.setVisibility(View.VISIBLE);
                          embeddedDownloadHint.setVisibility(View.VISIBLE);
                          embeddedDownloadButton.setOnClickListener(v -> downloadEmbeddedApk(finalUrl));
                      }
                  });
              } catch (Exception error) {
                  mainHandler.post(() -> embeddedUpdateStatus.setText(t("检查更新失败: ", "Update check failed: ") + error.getMessage()));
              } finally {
                  if (connection != null) connection.disconnect();
              }
          }).start();
      }

       private boolean isVersionNewer(String candidate, String current) {
          try {
              String[] a = candidate.split("\\."), b = current.split("\\.");
              for (int i = 0; i < Math.max(a.length, b.length); i++) {
                  int left = i < a.length ? Integer.parseInt(a[i]) : 0;
                  int right = i < b.length ? Integer.parseInt(b[i]) : 0;
                  if (left != right) return left > right;
              }
          } catch (NumberFormatException ignored) { }
           return false;
       }

       private String trimReleaseNotes(String notes) {
           String normalized = notes == null ? "" : notes.replace("\\r\\n", "\n").replace("\\n", "\n").trim();
           StringBuilder visible = new StringBuilder();
           for (String line : normalized.split("\\r?\\n")) {
               String compact = line.trim().toLowerCase(Locale.ROOT).replace(" ", "");
               if (compact.equals("##安装说明") || compact.equals("##installation") || compact.equals("##校验") || compact.equals("##verification") || compact.equals("##文件说明") || compact.equals("##filelist") || compact.equals("##files")) break;
               if (visible.length() > 0) visible.append('\n');
               visible.append(line);
           }
           return visible.toString().trim();
       }

      private void downloadEmbeddedApk(String url) {
          embeddedDownloadButton.setEnabled(false);
          embeddedDownloadButton.setText(t("正在下载...", "Downloading..."));
          new Thread(() -> {
              try {
                  HttpURLConnection connection = (HttpURLConnection) new java.net.URL(url).openConnection();
                  connection.setConnectTimeout(15000);
                  connection.setReadTimeout(30000);
                  connection.setInstanceFollowRedirects(true);
                  File apk = new File(getCacheDir(), "dsu-manager-update.apk");
                  try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(apk)) {
                      byte[] buffer = new byte[8192];
                      int count;
                      while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                  }
                  mainHandler.post(() -> {
                      embeddedDownloadButton.setEnabled(true);
                      embeddedDownloadButton.setText(t("安装已下载 APK", "Install downloaded APK"));
                      embeddedDownloadButton.setOnClickListener(v -> installApk(apk));
                      installApk(apk);   // v3.9.13 下载完自动免 root 安装
                  });
                  connection.disconnect();
              } catch (Exception error) {
                  mainHandler.post(() -> {
                      embeddedDownloadButton.setEnabled(true);
                      embeddedDownloadButton.setText(t("下载最新 APK", "Download latest APK"));
                      embeddedUpdateStatus.setText(t("下载失败: ", "Download failed: ") + error.getMessage());
                  });
              }
          }).start();
      }

      private void installApk(File apk) {
          // v3.9.13：统一走 UpdateCenter 的 PackageInstaller 会话式免 root 自动安装（应用商店级体验）
          UpdateCenter.installApk(this, apk, UpdateCenter.isEnglish(this));
      }

     private void saveLanguage(int mode) {
         getSharedPreferences("settings", MODE_PRIVATE).edit().putInt("language_mode", mode).apply();
         languageMode = mode;
         // v3.41.20：补「系统语言」分支 —— 此前选系统语言（mode=0）时 english 恒 false，
         // 英文系统下界面仍是中文（与 onCreate 的初始化逻辑不一致）
         english = mode == 2 || (mode == 0 && Locale.getDefault().getLanguage().equals("en"));
         buildUi();
     }

     private void applyKeepScreenOn() {
        if (keepScreenOn) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void applyWindowTransparency(boolean transparent) {
        if (transparent) {
            try {
                // 获取壁纸管理器
                android.app.WallpaperManager wallpaperManager = android.app.WallpaperManager.getInstance(this);
                android.app.WallpaperInfo wallpaperInfo = wallpaperManager.getWallpaperInfo();
                
                // 检查是否为动态壁纸
                if (wallpaperInfo != null && wallpaperInfo.getPackageName() != null) {
                    // 动态壁纸：添加 FLAG_SHOW_WALLPAPER
                    getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
                } else {
                    // 静态壁纸：获取壁纸 Drawable 并设置为窗口背景
                    android.graphics.drawable.Drawable wallpaperDrawable = wallpaperManager.getDrawable();
                    getWindow().setBackgroundDrawable(wallpaperDrawable);
                }
                
                // 设置窗口背景为透明（必须）
                getWindow().getDecorView().setBackgroundResource(android.R.color.transparent);
            } catch (Exception e) {
                e.printStackTrace();
                // 如果获取壁纸失败，设置透明背景
                getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            }
        } else {
            // 恢复默认背景
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
            getWindow().setBackgroundDrawableResource(R.drawable.liquid_backdrop);
            getWindow().getDecorView().setBackgroundResource(R.drawable.liquid_backdrop);
        }
    }

     private void refreshStatus(){
         if (!rootAuthorized) {
             gsiStatus.setText(t("需要 ROOT 权限", "ROOT access required"));
             detailText.setText(t("请先授予 ROOT 权限后使用操作中心。", "Grant ROOT access before using the action center."));
             return;
         }
         detailText.setText(t("正在读取 GSI 状态...", "Reading GSI status..."));
        new Thread(() -> {
            String raw = runPrivilegedResult("/system/bin/gsi_tool", "status");
            String status = formatGsiStatus(raw);
            runOnUiThread(() -> {
                // v3.41.20：状态卡显示走 localizedStatus —— 此前直接 setText 中文，
                // 英文模式下首页显示中文状态（内部比较仍用中文常量，逻辑不变）
                gsiStatus.setText(localizedStatus(status));
                 if (status.equals("GSI 已成功安装，等待启动")) {
                     showInstalledGsiSummary();
                 } else {
                     detailText.setText(t("GSI: ", "GSI: ") + localizedStatus(status));
                 }
            });
        }).start();
    }
     private void refreshRootStatus(){
         if (rootAuthorized || rootCheckInProgress) return;
         rootCheckInProgress = true;
         rootStatus.setText(t("ROOT 检测中", "Checking ROOT"));
        new Thread(() -> {
            RootResult root = checkRoot();
            runOnUiThread(() -> {
                 rootCheckInProgress = false;
                 setRootAuthorized(root.authorized);
            });
        }).start();
    }
    private static class RootResult { final boolean authorized; final String message; RootResult(boolean ok, String text){ authorized=ok; message=text; } }
     private RootResult checkRoot(){
         if (privilegedService != null) return new RootResult(true, t("ROOT 已授权", "ROOT granted"));
         CommandResult result = runCommand("/system/bin/su", "-c", "id");
        String output = result.output.trim();
         if(result.exitCode == 0 && output.contains("uid=0")) return new RootResult(true, t("ROOT 已授权", "ROOT granted"));
         return new RootResult(false, t("ROOT 检测失败", "ROOT check failed"));
    }
     private String localizedStatus(String status) {
         if (!english) return status;
         if (status.equals("未安装")) return "Not installed";
         if (status.equals("空闲（未运行 GSI）")) return "Idle (GSI not running)";
         if (status.equals("运行中")) return "Running";
         if (status.equals("GSI 已成功安装，等待启动")) return "GSI installed, waiting to boot";
         return status;
     }
     private String formatGsiStatus(String raw){
         if(raw == null || raw.trim().isEmpty()) return "未安装";
        String[] lines = raw.trim().split("\\r?\\n");
        for (String line : lines) {
            String value = line.trim();
            if (value.isEmpty() || value.matches("\\[\\d+\\].*")) continue;
            if(value.equalsIgnoreCase("normal")) return "空闲（未运行 GSI）";
            if(value.equalsIgnoreCase("running")) return "运行中";
            if(value.equalsIgnoreCase("installed")) return "GSI 已成功安装，等待启动";
        }
        return "未安装";
    }
    private static class CommandResult { final String output; final int exitCode; CommandResult(String text, int code){ output=text; exitCode=code; } }
    private CommandResult runCommand(String... cmd){
        try {
            java.lang.Process p=Runtime.getRuntime().exec(cmd);
            BufferedReader out=new BufferedReader(new InputStreamReader(p.getInputStream()));
            BufferedReader err=new BufferedReader(new InputStreamReader(p.getErrorStream()));
            StringBuilder b=new StringBuilder(); String line;
            while((line=out.readLine())!=null)b.append(line).append('\n');
            while((line=err.readLine())!=null)b.append(line).append('\n');
            int code=p.waitFor(); return new CommandResult(b.toString().trim(),code);
        } catch(Exception e){ return new CommandResult(e.getMessage()==null?"命令执行失败":e.getMessage(),-1); }
    }
    private String run(String... cmd){ return runCommand(cmd).output; }
      private void action(int index){
          if (!rootAuthorized) {
              toast(t("请先授予 ROOT 权限", "Grant ROOT access first"));
              return;
          }
          switch (index) {
              case 0:
                  refreshStatus();
                  toast(t("已刷新 GSI 状态", "GSI status refreshed"));
                  return;
               case 1:
                   installOptionsPanel.setVisibility(installOptionsPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                   return;
              case 2:
                  confirm(t("重启到 DSU", "Reboot to DSU"), t("设备将重启到已安装的 Dynamic System。", "The device will reboot into the installed Dynamic System."), this::bootDsu);
                  return;
              case 3:
                  confirm(t("撤销 GSI", "Remove GSI"), t("将清理当前已安装的动态系统。", "The installed Dynamic System will be removed."), this::wipeDsu);
                  return;
               case 4:
                   if (imageManagementPanel.getVisibility() == View.VISIBLE) imageManagementPanel.setVisibility(View.GONE); else showImageManagement();
                   return;
              default:
          }
      }
     private void customSize(){
         setBottomNavigationVisible(false);
         EditText input = new EditText(this);
         input.setHint(t("例如 24 或 24.5", "For example, 24 or 24.5"));
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(24), dp(4), dp(24), 0);
        box.addView(input, new LinearLayout.LayoutParams(-1, dp(56)));
        // v3.42.11：上限随剩余空间动态计算（不再固定 128GB），弹窗内展示实时可用空间
        long freeBytes = dataFreeBytes();
        AlertDialog dialog = new AlertDialog.Builder(this)
                 .setTitle(t("自定义 userdata 容量", "Custom userdata size"))
                 .setMessage(t("剩余空间 " + (freeBytes >= 0 ? formatSizeBytesHuman(freeBytes) : "未知")
                                 + "，最大可分配 " + (int) maxDsuSizeGb() + " GB（与 DSU-Sideloader 算法一致：预留 4GB 解包空间后对半分配）",
                         "Free " + (freeBytes >= 0 ? formatSizeBytesHuman(freeBytes) : "unknown")
                                 + ", up to " + (int) maxDsuSizeGb() + " GB (same as DSU-Sideloader: 4GB reserved, then split in half)"))
                .setView(box)
                 .setPositiveButton(t("选择 GSI 安装包", "Choose GSI package"), null)
                 .setNegativeButton(t("取消", "Cancel"), null)
                .create();
        dialog.setOnShowListener(ignored -> {
            input.requestFocus();
            // v3.9.5：改用 ADJUST_PAN —— 对话框无滚动容器，RESIZE 不生效时键盘直接盖住输入框；
            // PAN 让窗口整体上移，输入内容和光标始终可见
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String value = input.getText().toString().trim();
                try {
                     double gb = Double.parseDouble(value.replace(',', '.'));
                    if (gb <= 0 || gb > maxDsuSizeGb()) throw new NumberFormatException();
                    dialog.dismiss();
                     userdataSizeBytes = Math.round(gb * 1024d * 1024d * 1024d);
                     pendingSizeLabel = value + " GB";
                     chooseZip();
                } catch (NumberFormatException error) {
                     input.setError(t("请输入 0 到 " + (int) maxDsuSizeGb() + " 之间的容量",
                             "Enter a size between 0 and " + (int) maxDsuSizeGb()));
                }
            });
        });
         dialog.setOnDismissListener(ignored -> mainHandler.postDelayed(() -> setBottomNavigationVisible(true), 350));
         dialog.show();
        input.postDelayed(() -> {
            input.requestFocus();
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }, 200);
    }
     private void chooseZip() {
         // 检查存储空间
         if (!checkStorageSpaceWithWarning()) {
             return;
         }
         pickGsiZipViaBrowser();
     }

     /** v3.30.15：内置文件浏览器选择 GSI 安装包（替换系统 SAF） */
     private void pickGsiZipViaBrowser() {
         FileBrowserDialog.show(this, t("选择 GSI 安装包", "Select GSI zip"),
                 new String[]{".zip", ".zip2"}, "/storage/emulated/0", path -> {
                     pendingInstallZip = Uri.fromFile(new File(path));
                     installedZipName = new File(path).getName();
                     getPreferences(MODE_PRIVATE).edit().putString("installed_zip_name", installedZipName).apply();
                     installZipLabel.setText(installedZipName);
                     confirmInstallButton.setEnabled(true);
                 });
     }
     
     private boolean checkStorageSpaceWithWarning() {
         try {
             android.os.StatFs statFs = new android.os.StatFs("/data");
             long totalBytes = statFs.getTotalBytes();
             long freeBytes = statFs.getAvailableBytes();
             float freePercent = (float) freeBytes / totalBytes * 100;
             
             if (freePercent < 40) {
                 String message = t(
                     "您的设备可用存储空间少于 40%，安装过程可能会报错，请释放更多空间后重试。\n\n" +
                     "总容量: " + formatSizeBytesHuman(totalBytes) + "\n" +
                     "可用: " + formatSizeBytesHuman(freeBytes) + " (" + String.format("%.1f%%", freePercent) + ")",
                     "Your device has less than 40% free storage. Installation may fail. Please free up more space.\n\n" +
                     "Total: " + formatSizeBytesHuman(totalBytes) + "\n" +
                     "Available: " + formatSizeBytesHuman(freeBytes) + " (" + String.format("%.1f%%", freePercent) + ")"
                 );
                 
                 final boolean[] shouldContinue = {false};
                 AlertDialog dialog = new AlertDialog.Builder(this)
                     .setTitle(t("可用存储空间不足", "Insufficient Storage"))
                     .setMessage(message)
                     .setPositiveButton(t("仍要继续", "Continue Anyway"), (d, w) -> {
                         shouldContinue[0] = true;
                         d.dismiss();
                         // 继续选择文件（v3.30.15：内置文件浏览器）
                         pickGsiZipViaBrowser();
                     })
                     .setNegativeButton(t("取消", "Cancel"), null)
                     .create();
                 dialog.show();
                 return false;
             }
             return true;
         } catch (Exception e) {
             e.printStackTrace();
             return true; // 如果检查失败，允许继续
         }
     }
     private void selectInstallSize(int index, String label){
         selectedInstallSize = index;
         pendingSizeLabel = label;
         userdataSizeBytes = parseSizeBytes(label);
         for (int i = 0; i < installSizeButtons.length; i++) {
             installSizeButtons[i].setTextColor(i == index ? Color.WHITE : Color.rgb(40, 50, 70));
             installSizeButtons[i].setBackgroundResource(i == index ? R.drawable.button_teal : R.drawable.rounded_panel);
         }
     }
     private void applyCustomInstallSize(){
          try {
              double gb = parseCustomSize(customInstallSizeInput.getText().toString());
              if (gb <= 0 || gb > maxDsuSizeGb()) throw new NumberFormatException();
              selectedInstallSize = -1;
              pendingSizeLabel = formatCustomSize(gb) + " GB";
              userdataSizeBytes = Math.round(gb * 1024d * 1024d * 1024d);
              customInstallSizeInput.setText(pendingSizeLabel);
               for (Button button : installSizeButtons) { button.setTextColor(Color.rgb(40, 50, 70)); button.setBackgroundResource(R.drawable.liquid_glass_panel); }
          } catch (NumberFormatException error) {
              customInstallSizeInput.setError(t("请输入 0 到 " + (int) maxDsuSizeGb() + " 之间的容量（按剩余空间自动计算）",
                      "Enter a size between 0 and " + (int) maxDsuSizeGb() + " (auto-calculated from free space)"));
          }
      }
      private double parseCustomSize(String value) {
          String normalized = value.trim().replace(",", ".").replaceAll("(?i)\\s*gb\\s*$", "");
          return Double.parseDouble(normalized);
      }
      private String formatCustomSize(double gb) {
          return gb == Math.rint(gb) ? String.format(Locale.US, "%.0f", gb) : String.format(Locale.US, "%.2f", gb).replaceAll("0+$", "").replaceAll("\\.$", "");
      }
      private void setBottomNavigationVisible(boolean visible) {
          if (bottomNavigation != null) bottomNavigation.setVisibility(visible ? View.VISIBLE : View.GONE);
      }
     private void confirmInstallZip(){
         if (pendingInstallZip == null) { toast(t("请先选择 ZIP 安装包", "Choose a ZIP package first")); return; }
         installOptionsPanel.setVisibility(View.GONE);
         installWithDsuSideloaderFlow(pendingInstallZip);
     }
     private void chooseImage(ImageView bgImageView){
         // v3.50.18：内置文件浏览器 + 裁剪预览界面（用户自己调整显示区域）
         FileBrowserDialog.show(this, t("选择 Logo 图片", "Select logo image"),
                 new String[]{".png", ".jpg", ".jpeg", ".webp", ".bmp"}, "/storage/emulated/0",
                 path -> {
                     File target = new File(getCacheDir(), "logo.img");
                     com.topjohnwu.superuser.Shell.cmd(
                             "cp -f " + DnaTools.quote(path) + " " + DnaTools.quote(target.getAbsolutePath())
                     ).exec();
                     Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(target.getAbsolutePath());
                     if (bitmap != null) {
                         showImageCropDialog(bitmap, bgImageView);
                     } else {
                         toast(t("无法读取图片", "Cannot read image"));
                     }
                 });
     }
     
     /** v3.50.23：彻底修复按钮溢出的图片裁剪对话框 */
     private void showImageCropDialog(Bitmap bitmap, ImageView targetImageView) {
         Dialog dialog = new Dialog(this);
         dialog.setCancelable(true);
         
         LinearLayout panel = new LinearLayout(this);
         panel.setOrientation(LinearLayout.VERTICAL);
         panel.setPadding(dp(18), dp(16), dp(18), dp(14));
         GradientDrawable bg = new GradientDrawable();
         bg.setColor(0xF2e9f0f7);
         bg.setCornerRadius(dp(20));
         panel.setBackground(bg);
         
         TextView title = new TextView(this);
         title.setText(t("调整", "Crop"));
         title.setTextSize(15f);
         title.setTypeface(null, 1);
         title.setTextColor(0xff17334f);
         panel.addView(title, new LinearLayout.LayoutParams(-1, -2));
         
         // 预览容器
         FrameLayout previewContainer = new FrameLayout(this);
         LinearLayout.LayoutParams containerLp = new LinearLayout.LayoutParams(-1, dp(160));
         containerLp.topMargin = dp(10);
         
         // 背景图片
         ImageView preview = new ImageView(this);
         preview.setScaleType(ImageView.ScaleType.MATRIX);
         preview.setImageBitmap(bitmap);
         preview.setClipToOutline(true);
         preview.setOutlineProvider(new ViewOutlineProvider() {
             @Override public void getOutline(View view, android.graphics.Outline outline) {
                 outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(16));
             }
         });
         
         // 手势处理
         final android.graphics.Matrix matrix = new android.graphics.Matrix();
         final android.graphics.Matrix savedMatrix = new android.graphics.Matrix();
         
         preview.post(() -> {
             int viewWidth = preview.getWidth();
             int viewHeight = preview.getHeight();
             int imgWidth = bitmap.getWidth();
             int imgHeight = bitmap.getHeight();
             float scale = Math.max((float)viewWidth / imgWidth, (float)viewHeight / imgHeight);
             matrix.setScale(scale, scale);
             matrix.postTranslate((viewWidth - imgWidth * scale) / 2f, (viewHeight - imgHeight * scale) / 2f);
             preview.setImageMatrix(matrix);
         });
         
         final int NONE = 0, DRAG = 1, ZOOM = 2;
         final int[] mode = {NONE};
         final android.graphics.PointF start = new android.graphics.PointF();
         final android.graphics.PointF mid = new android.graphics.PointF();
         final float[] oldDist = {1f};
         
         preview.setOnTouchListener((v, event) -> {
             int action = event.getActionMasked();
             switch (action) {
                 case MotionEvent.ACTION_DOWN:
                     savedMatrix.set(matrix);
                     start.set(event.getX(), event.getY());
                     mode[0] = DRAG;
                     break;
                 case MotionEvent.ACTION_POINTER_DOWN:
                     if (event.getPointerCount() == 2) {
                         oldDist[0] = spacing(event);
                         if (oldDist[0] > 10f) {
                             savedMatrix.set(matrix);
                             midPoint(mid, event);
                             mode[0] = ZOOM;
                         }
                     }
                     break;
                 case MotionEvent.ACTION_MOVE:
                     if (mode[0] == DRAG && event.getPointerCount() == 1) {
                         matrix.set(savedMatrix);
                         matrix.postTranslate(event.getX() - start.x, event.getY() - start.y);
                     } else if (mode[0] == ZOOM && event.getPointerCount() == 2) {
                         float newDist = spacing(event);
                         if (newDist > 10f) {
                             matrix.set(savedMatrix);
                             float scale = newDist / oldDist[0];
                             matrix.postScale(scale, scale, mid.x, mid.y);
                         }
                     }
                     preview.setImageMatrix(matrix);
                     break;
                 case MotionEvent.ACTION_UP:
                 case MotionEvent.ACTION_POINTER_UP:
                     savedMatrix.set(matrix);
                     mode[0] = NONE;
                     break;
             }
             return true;
          });
          
          previewContainer.addView(preview, new FrameLayout.LayoutParams(-1, -1));
          
          panel.addView(previewContainer, containerLp);
         
         TextView hint = new TextView(this);
         hint.setText(t("缩放·拖动", "Zoom"));
         hint.setTextSize(10f);
         hint.setTextColor(0xff5a6b82);
         hint.setGravity(Gravity.CENTER);
         LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
         hintLp.topMargin = dp(6);
         panel.addView(hint, hintLp);
         
         // 按钮行：极简布局
         LinearLayout btnRow = new LinearLayout(this);
         btnRow.setOrientation(LinearLayout.HORIZONTAL);
         LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(-1, -2);
         btnRowLp.topMargin = dp(12);
         
          Button cancel = new Button(this, null, 0);
          cancel.setText(t("取消", "Cancel"));
          cancel.setAllCaps(false);
          cancel.setTextSize(15);
          cancel.setGravity(Gravity.CENTER);
          cancel.setTextColor(0xff5a6b82);
          cancel.setPadding(0, 0, 0, 0);
          cancel.setMinWidth(0);
          cancel.setMinimumWidth(0);
          GradientDrawable cancelBg = new GradientDrawable();
          cancelBg.setColor(0x18000000);
          cancelBg.setCornerRadius(dp(8));
          cancel.setBackground(cancelBg);
          cancel.setStateListAnimator(null);
          cancel.setOnClickListener(v -> dialog.dismiss());
          LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, dp(36), 1f);
          cancelLp.rightMargin = dp(8);
          btnRow.addView(cancel, cancelLp);
          
          Button confirm = new Button(this, null, 0);
          confirm.setText(t("确定", "Confirm"));
          confirm.setAllCaps(false);
          confirm.setTextSize(15);
          confirm.setGravity(Gravity.CENTER);
          confirm.setTextColor(Color.WHITE);
          confirm.setPadding(0, 0, 0, 0);
          confirm.setMinWidth(0);
          confirm.setMinimumWidth(0);
          GradientDrawable confirmBg = new GradientDrawable();
          confirmBg.setCornerRadius(dp(8));
          confirmBg.setColors(new int[]{0xFF4C74DE, 0xFF2563EB});
          confirmBg.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
          confirm.setBackground(confirmBg);
          confirm.setStateListAnimator(null);
          confirm.setOnClickListener(v -> {
              dialog.dismiss();
              Bitmap adjusted = Bitmap.createBitmap(preview.getWidth(), preview.getHeight(), Bitmap.Config.ARGB_8888);
              android.graphics.Canvas canvas = new android.graphics.Canvas(adjusted);
              canvas.drawBitmap(bitmap, matrix, null);
              targetImageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
              targetImageView.setImageBitmap(adjusted);
              toast("✓");
          });
          LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(0, dp(36), 1f);
          btnRow.addView(confirm, confirmLp);
         panel.addView(btnRow, btnRowLp);
         
         dialog.setContentView(panel);
         Window w = dialog.getWindow();
         if (w != null) {
             w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
             w.setLayout((int)(getResources().getDisplayMetrics().widthPixels * 0.85f), -2);
         }
         dialog.show();
     }
     
     /** 手势辅助方法：计算两指距离 */
     private float spacing(MotionEvent event) {
         float x = event.getX(0) - event.getX(1);
         float y = event.getY(0) - event.getY(1);
         return (float) Math.sqrt(x * x + y * y);
     }
     
     /** 手势辅助方法：计算两指中点 */
     private void midPoint(android.graphics.PointF point, MotionEvent event) {
         float x = event.getX(0) + event.getX(1);
         float y = event.getY(0) + event.getY(1);
         point.set(x / 2f, y / 2f);
     }
     @Override protected void onActivityResult(int r,int c,Intent d){ super.onActivityResult(r,c,d); if(c!=RESULT_OK)return; if((r==402||r==403||r==404) && otgFlashHelper != null){ otgFlashHelper.onActivityResult(r,c,d); return; } if(d==null)return; Uri u=d.getData(); if(r==PICK_IMAGE){ String path=getPath(u,"logo.img"); if(!path.isEmpty()){ Bitmap bitmap=android.graphics.BitmapFactory.decodeFile(path); if(bitmap!=null) { logoCard.setBackground(new RoundedCropDrawable(bitmap, dp(28), -dp(15))); logoCard.setClipToOutline(true); } } } else if(r==PICK_ZIP){ pendingInstallZip = u; installedZipName = displayName(u); getPreferences(MODE_PRIVATE).edit().putString("installed_zip_name", installedZipName).apply(); installZipLabel.setText(installedZipName); confirmInstallButton.setEnabled(true); } else if(r==PICK_REPLACEMENT && replacementPartition != null){ replaceImage(u, replacementPartition); } else if(r==PICK_ROOTFS){ Intent intent = new Intent(this, LinuxTerminalActivity.class); intent.putExtra("local_install", true); intent.setData(u); startActivity(intent); } else if(r==PICK_FASTBOOT_IMAGE){ String path=getPath(u,"fastboot.img"); if(!path.isEmpty()){ if(fastbootImagePathInput != null) fastbootImagePathInput.setText(path); else if(fastbootFilePathInput != null) fastbootFilePathInput.setText(path); } } else if(r==REQUEST_FLASH_IMAGE_FILE){ preparePartitionImage(u); } else if(r==REQUEST_OTG_SINGLE_IMAGE){ String path=getPath(u,"otg_single.img"); if(!path.isEmpty() && otgFlashHelper != null){ otgFlashHelper.prepareSingleImage(path); } } else if(r==REQUEST_OTG_FULL_PACKAGE){ String path=getPath(u,"otg_full.zip"); if(!path.isEmpty() && otgFlashHelper != null){ otgFlashHelper.extractAndScanOta(path); } } else if(r==REQUEST_OTG_ADB_PUSH){ String path=getPath(u,"otg_push_file"); if(!path.isEmpty() && otgFlashHelper != null){ otgFlashHelper.prepareAdbPush(path); } } }

     private String displayName(Uri uri){
         try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
             if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
         } catch (Exception ignored) { }
         return uri.getLastPathSegment() == null ? t("未命名 ZIP", "Unnamed ZIP") : uri.getLastPathSegment();
     }
    private String getPath(Uri u,String name){ try { InputStream in=getContentResolver().openInputStream(u); File f=new File(getCacheDir(),name); FileOutputStream out=new FileOutputStream(f); byte[] b=new byte[8192]; int n; while((n=in.read(b))>0)out.write(b,0,n); in.close();out.close();return f.getAbsolutePath(); }catch(Exception e){return ""; } }

    /** v3.30.13：解析 SAF Uri 的本地真实路径（/storage/emulated/0/...），root 可直接读取；
     *  非 primary 存储或文件不存在时返回 null（调用方回退缓存复制） */
    private String resolveRealPath(Uri uri) {
        try {
            if (!"content".equals(uri.getScheme())) return null;
            if (!"com.android.externalstorage.documents".equals(uri.getAuthority())) return null;
            String docId = android.provider.DocumentsContract.getDocumentId(uri);
            String[] split = docId.split(":");
            if (split.length >= 2 && "primary".equals(split[0])) {
                String p = Environment.getExternalStorageDirectory() + "/" + split[1];
                return new File(p).isFile() ? p : null;
            }
        } catch (Exception ignored) { }
        return null;
    }
    private void installWithDsuSideloaderFlow(Uri source){
         stagesClear();
         installStage.setText(t("正在安装 GSI", "Installing GSI"));
         stageBegin("copy", t("解析安装包", "Parsing package"));
        new Thread(() -> {
            String path = "";
            try {
                path = copyInstallZip(source);
                if (path.isEmpty()) {
                     runOnUiThread(() -> finishProgress(t("无法读取 GSI 安装包", "Unable to read the GSI package")));
                    return;
                }
                runOnUiThread(() -> {
                     stageDone("copy", t("解析安装包", "Parsing package"));
                      detailText.setText(t("正在将 ZIP 内的 img 镜像直接写入 DSU\nuserdata: ", "Writing ZIP images directly to DSU\nuserdata: ") + pendingSizeLabel);
                });
                String result = installZipThroughRootService(path);
                runOnUiThread(() -> finishProgress(result));
             } catch(Exception e){ runOnUiThread(() -> finishProgress(t("安装准备失败: ", "Installation preparation failed: ")+e.getMessage())); }
            finally { if (!path.isEmpty()) new File(path).delete(); }
        }).start();
    }
    private String copyInstallZip(Uri source) {
        File target = new File(getCacheDir(), "dsu-install.zip");
        // v3.9.5：解析阶段独立进度条（0-100% 真实字节推进）
        long total = -1;
        try (Cursor cursor = getContentResolver().query(source, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) total = cursor.getLong(0);
        } catch (Exception ignored) { }
        // v3.30.15：file:// 来源（内置文件浏览器）——app 无直读权限时经 su cat 流式读取（零额外占用）；
        // 可直读时仍走 Java 直读（同一路径 faster）
        boolean fileScheme = "file".equals(source.getScheme());
        String filePath = fileScheme ? source.getPath() : null;
        if (fileScheme && filePath != null) {
            File f = new File(filePath);
            if (f.isFile()) total = f.length();
            if (!f.canRead()) filePath = null; // 无权限 → 走 su cat
        }
        final String suPath = fileScheme && filePath == null ? source.getPath() : null;
        try (InputStream input = suPath != null ? RootShell.INSTANCE.openStream(suPath)
                : (filePath != null ? new java.io.FileInputStream(filePath)
                : getContentResolver().openInputStream(source));
             FileOutputStream output = new FileOutputStream(target)) {
            if (input == null) return "";
            byte[] buffer = new byte[1024 * 1024];
            int count;
            long copied = 0;
            long lastUiAt = 0;
            int lastPct = -1;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                copied += count;
                if (total > 0) {
                    int pct = (int) Math.min(100, copied * 100 / total);
                    long now = System.currentTimeMillis();
                    if (pct != lastPct && now - lastUiAt >= 150) {
                        lastPct = pct;
                        lastUiAt = now;
                        final int p = pct;
                        runOnUiThread(() -> stageUpdate("copy", p));
                    }
                }
            }
            return target.getAbsolutePath();
        } catch (Exception e) {
            return "";
        }
    }
    
    private volatile DSUInstaller currentInstaller;
    
    private String installZipThroughRootService(String path) {
        IPrivilegedService service = privilegedService;
        if (service == null) return t("ROOT Installer 尚未连接，请先完成 ROOT 授权后重试", "ROOT installer is not connected. Grant ROOT access and try again.");
        
        final String[] result = {null};
        
        // 清空进度UI
        runOnUiThread(() -> {
            if (stageHost != null) stageHost.removeAllViews();
            partitionProgressViews.clear(); // 清空所有分区进度条
        });
        
        // 创建安装器 (使用 Kotlin 版本)
        long userdataSize = (userdataSizeBytes + 511L) & ~511L;
        currentInstaller = new DSUInstaller(
            service,
            userdataSize,
            path,
            error -> { result[0] = error; return kotlin.Unit.INSTANCE; },
            (progress, partition) -> { updateInstallProgress(partition, progress); return kotlin.Unit.INSTANCE; },
            () -> { result[0] = t("GSI 已安装并启用 DSU，请点击 '重启到 DSU' 进入系统", "GSI installed and DSU enabled. Tap 'Reboot to DSU' to enter the system."); return kotlin.Unit.INSTANCE; }
        );
        
        // 在后台线程执行安装
        Thread installThread = new Thread(() -> {
            currentInstaller.startInstallation();
        });
        installThread.start();
        
        // 等待完成
        try {
            installThread.join();
        } catch (InterruptedException e) {
            currentInstaller.cancel();
            return t("安装被中断", "Installation interrupted");
        }
        
        currentInstaller = null;
        return result[0];
    }
    
    /**
     * 更新统一的安装进度
     * @param partition 当前分区名称
     * @param progress 进度 0.0 - 1.0
     */
    private final java.util.HashMap<String, View> partitionProgressViews = new java.util.HashMap<>(); // 每个分区一个进度条
    
    private void updateInstallProgress(String partition, float progress) {
        runOnUiThread(() -> {
            View progressView = partitionProgressViews.get(partition);
            
            if (progressView == null && stageHost != null) {
                // 为该分区创建新的进度条
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(16), dp(12), dp(16), dp(12));
                
                // 创建圆角背景
                android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
                background.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                background.setColor(0xF0FFFFFF); // 半透明白色
                background.setCornerRadius(dp(16)); // 16dp 圆角
                row.setBackground(background);
                
                // 添加阴影效果
                if (android.os.Build.VERSION.SDK_INT >= 21) {
                    row.setElevation(dp(4));
                }
                
                LinearLayout topRow = new LinearLayout(this);
                topRow.setOrientation(LinearLayout.HORIZONTAL);
                
                TextView label = new TextView(this);
                label.setId(android.R.id.text1);
                label.setText(partition);
                label.setTextSize(16);
                label.setTextColor(0xFF333333); // 深灰色
                label.setTypeface(null, android.graphics.Typeface.BOLD);
                LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                label.setLayoutParams(labelParams);
                topRow.addView(label);
                
                TextView pct = new TextView(this);
                pct.setId(android.R.id.text2);
                pct.setText(String.format(Locale.US, "%d%%", (int)(progress * 100)));
                pct.setTextSize(16);
                pct.setTextColor(0xFF333333); // 深灰色
                pct.setTypeface(null, android.graphics.Typeface.BOLD);
                topRow.addView(pct);
                
                row.addView(topRow);
                
                ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                bar.setId(android.R.id.progress);
                bar.setIndeterminate(false);
                bar.setMax(100);
                bar.setProgress((int)(progress * 100));
                
                // 圆角进度条背景
                android.graphics.drawable.GradientDrawable progressBg = new android.graphics.drawable.GradientDrawable();
                progressBg.setCornerRadius(dp(4)); // 圆角
                progressBg.setColor(0xFFE0E0E0); // 浅灰色背景
                
                // 圆角进度条前景（渐变色）
                android.graphics.drawable.GradientDrawable progressFg = new android.graphics.drawable.GradientDrawable();
                progressFg.setCornerRadius(dp(4));
                progressFg.setColors(new int[]{0xFF64B5F6, 0xFF1976D2}); // 浅蓝到深蓝渐变
                progressFg.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT);
                
                android.graphics.drawable.ClipDrawable clip = new android.graphics.drawable.ClipDrawable(
                    progressFg, android.view.Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL);
                
                android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(
                    new android.graphics.drawable.Drawable[]{progressBg, clip});
                layers.setId(0, android.R.id.background);
                layers.setId(1, android.R.id.progress);
                
                bar.setProgressDrawable(layers);
                
                LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(8));
                barParams.topMargin = dp(8);
                bar.setLayoutParams(barParams);
                row.addView(bar);
                
                // 添加间距
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rowParams.bottomMargin = dp(12);
                row.setLayoutParams(rowParams);
                
                stageHost.addView(row);
                partitionProgressViews.put(partition, row);
                progressView = row;
            }
            
            // 更新进度
            if (progressView != null) {
                TextView pct = progressView.findViewById(android.R.id.text2);
                ProgressBar bar = progressView.findViewById(android.R.id.progress);
                
                if (pct != null) pct.setText(String.format(Locale.US, "%d%%", (int)(progress * 100)));
                if (bar != null) {
                    bar.setIndeterminate(false);
                    bar.setMax(100);
                    bar.setProgress((int)(progress * 100));
                }
            }
        });
    }
    
    /**
     * 检查是否应该安装该文件
     */

     /** v3.9.6：gsid INSTALL_* 状态码 → 可读诊断（含分区尺寸与 /data 可用空间） */
     private String gsiStatusDetail(String partition, int status, long sizeBytes) {
         long freeBytes = 0;
         try { freeBytes = new android.os.StatFs("/data").getAvailableBytes(); } catch (Exception ignored) { }
         String need = formatSizeBytesHuman(sizeBytes);
         String free = formatSizeBytesHuman(Math.max(0, freeBytes));
         if (status == 2)
             return t("创建分区失败: " + partition + "（错误 2：存储空间不足）。该分区需要约 " + need + "，/data 当前可用 " + free + "，请清理空间后重试",
                     "Failed to create partition: " + partition + " (error 2: no space). It needs about " + need + "; /data has " + free + " free.");
         if (status == 3)
             return t("创建分区失败: " + partition + "（错误 3：剩余空间低于安全阈值）。A/B 设备需额外预留约一个 super 分区的空间；该分区需 " + need + "，/data 可用 " + free,
                     "Failed to create partition: " + partition + " (error 3: below safe free-space threshold). A/B devices must also reserve about one super partition; it needs " + need + ", " + free + " free.");
         if (status == 1)
             return t("创建分区失败: " + partition + "（错误 1：底层镜像分配失败，常见于存储空间不足或碎片化）。该分区需 " + need + "，/data 可用 " + free + "，请清理空间后重试",
                     "Failed to create partition: " + partition + " (error 1: backing image allocation failed, usually low or fragmented storage). It needs " + need + ", " + free + " free.");
         return t("创建分区失败: " + partition + "（错误 " + status + "）",
                 "Failed to create partition: " + partition + " (error " + status + ")");
     }

     private String formatSizeBytesHuman(long bytes) {
         if (bytes >= 1024L * 1024L * 1024L)
             return String.format(Locale.US, "%.1f GB", bytes / 1073741824d);
         if (bytes >= 1024L * 1024L)
             return String.format(Locale.US, "%.1f MB", bytes / 1048576d);
         return bytes + " B";
     }
    private ParcelFileDescriptor sharedMemoryFd(SharedMemory memory) throws Exception {
        try {
            return (ParcelFileDescriptor) SharedMemory.class.getMethod("getFdDup").invoke(memory);
        } catch (NoSuchMethodException ignored) {
            int fd = (Integer) SharedMemory.class.getDeclaredMethod("getFd").invoke(memory);
            return ParcelFileDescriptor.fromFd(fd);
        }
    }
    /**
     * v3.42.11：对齐 DSU-Sideloader 源码 StorageUtils.getAllocInfo() 的官方算法
     * （不再自创预留比例）：
     *   availGiB = /data 剩余字节 ÷ 1024³（整除取整）
     *   availGiB ≥ 6 时再减 4GB 预留（GSI 解包 / 打包 gz 的临时空间）
     *   最大可分配 = availGiB ÷ 2（整除）—— DSU userdata 与主系统共享 /data，各留一半
     * 例：剩余 201GB → (201 - 4) ÷ 2 = 98GB（与 DSU-Sideloader 显示一致）。
     * 检测失败回退旧上限 128GB。
     */
    private double maxDsuSizeGb() {
        try {
            long availableBytes = new android.os.StatFs(
                    android.os.Environment.getDataDirectory().getAbsolutePath()).getAvailableBytes();
            long availGiB = availableBytes / (1024L * 1024L * 1024L);
            if (availGiB >= 6) availGiB -= 4;   // 固定预留 4GB（官方注释：totally arbitrary number）
            return availGiB / 2;
        } catch (Exception e) {
            return 128;
        }
    }

    /** /data 分区实时剩余空间（字节）；检测失败返回 -1 */
    private long dataFreeBytes() {
        try {
            return new android.os.StatFs("/data").getAvailableBytes();
        } catch (Exception e) {
            return -1;
        }
    }
    private long parseSizeBytes(String size){
        try {
            double gigabytes = Double.parseDouble(size.replace("GB", "").trim());
            if (gigabytes > 0 && gigabytes <= maxDsuSizeGb()) {
                return (long) (gigabytes * 1024d * 1024d * 1024d);
            }
        } catch (Exception ignored) { }
        return 16L * 1024L * 1024L * 1024L;
    }

    // ---------- v3.9.5 分段进度 API：每阶段一条独立 0-100% 进度条 ----------

    /** 单个阶段行：左侧标签 + 右侧百分比，下方细进度条 */
    private static final class StageRow {
        TextView label;
        TextView pct;
        ProgressBar bar;
    }

    /** 清空全部阶段行（新安装/新替换开始时调用） */
    private void stagesClear() {
        if (stageHost != null) stageHost.removeAllViews();
        stageRows.clear();
        currentStageId = null;
    }

    /** 新增一个阶段行并立即置为当前活跃阶段（0%） */
    private void stageBegin(String id, String label) {
        if (stageHost == null) return;
        currentStageId = id;
        StageRow row = stageRows.get(id);
        if (row == null) {
            row = new StageRow();
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(-1, -2);
            boxLp.topMargin = dp(7);
            stageHost.addView(box, boxLp);
            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            row.label = text(label, 13, Color.rgb(40, 50, 70));
            row.label.setSingleLine(true);
            row.label.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            head.addView(row.label, new LinearLayout.LayoutParams(0, dp(20), 1f));
            row.pct = text("0%", 13, Color.rgb(77, 87, 105));
            LinearLayout.LayoutParams pctLp = new LinearLayout.LayoutParams(-2, dp(20));
            pctLp.leftMargin = dp(8);
            head.addView(row.pct, pctLp);
            box.addView(head, new LinearLayout.LayoutParams(-1, dp(20)));
            row.bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            row.bar.setMax(100);
            row.bar.setProgressDrawable(getDrawable(R.drawable.progress_bar));
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, dp(6));
            barLp.topMargin = dp(3);
            box.addView(row.bar, barLp);
            stageRows.put(id, row);
        } else {
            row.label.setTextColor(Color.rgb(40, 50, 70));
            row.label.setText(label);
            row.pct.setText("0%");
            row.bar.setProgress(0);
        }
        if (installPanel != null) installPanel.setVisibility(View.VISIBLE);
    }

    /** 更新当前阶段百分比（0-100） */
    private void stageUpdate(String id, int pct) {
        StageRow row = stageRows.get(id);
        if (row == null) return;
        int clamped = Math.max(0, Math.min(100, pct));
        row.pct.setText(clamped + "%");
        row.bar.setProgress(clamped);
        if (installPanel != null) installPanel.setVisibility(View.VISIBLE);
    }

    /** 阶段完成：✓ 100% */
    private void stageDone(String id, String label) {
        StageRow row = stageRows.get(id);
        if (row == null) return;
        row.label.setText(label);
        row.pct.setText("✓");
        row.pct.setTextColor(0xff1f8a4c);
        row.bar.setProgress(100);
    }

    /** 阶段失败：✗ 红色 */
    private void stageFail(String id, String label) {
        StageRow row = stageRows.get(id);
        if (row == null) return;
        row.label.setText(label);
        row.label.setTextColor(0xffb3261e);
        row.pct.setText("✗");
        row.pct.setTextColor(0xffb3261e);
    }

    /** 兼容旧调用：从结果消息自动判定成败（安装流程用） */
    private void finishProgress(String message){
        boolean success = message.contains("已安装并启用") || message.contains("DSU 已启动") || message.contains("替换完成")
                || message.contains("installed and DSU enabled") || message.contains("replacement complete");
        finishProgress(message, success);
    }

    private void finishProgress(String message, boolean success){
         boolean replacement = message.contains("替换 ") || message.contains("替换完成")
                 || message.contains("replacement complete") || message.contains("replacement failed");
         installStage.setText(replacement ? (success ? t("替换完成", "Replacement complete") : t("替换失败", "Replacement failed"))
                 : (success ? t("安装完成", "Installation complete") : t("安装失败", "Installation failed")));
          detailText.setText(message);
          toast(message);
          if (success) {
              if (currentStageId != null) stageDone(currentStageId, t("完成", "Done"));
          } else if (currentStageId != null) {
              stageFail(currentStageId, t("失败", "Failed"));
          }
          if (success && !replacement) {
              gsiStatus.setText(t("GSI 已成功安装，等待启动", "GSI installed, waiting to boot"));
              showInstalledGsiSummary();
              // v3.9.7：全部完成后自动收起进度面板（保留 1.2 秒展示 ✓ 收尾状态）
              mainHandler.postDelayed(() -> {
                  installPanel.setVisibility(View.GONE);
                  stagesClear();
                  installStage.setText(t("安装进度", "Installation progress"));
              }, 1200);
          }
    }
    private CommandResult runPrivilegedCommand(String... args){
        StringBuilder command=new StringBuilder(); for(String arg:args) command.append(quote(arg)).append(' ');
        return runCommand("/system/bin/su","-c",command.toString().trim());
    }
    private String runPrivilegedResult(String... args){ StringBuilder command=new StringBuilder(); for(String arg:args) command.append(quote(arg)).append(' '); return run("/system/bin/su","-c",command.toString().trim()); }
    private String quote(String value){ return "'"+value.replace("'","'\\''")+"'"; }
     private void runPrivileged(String... args){ String result=runPrivilegedResult(args); toast(result.isEmpty()?"操作已发送":result); }
     private void wipeDsu(){
         new Thread(() -> {
             String cleanupBeforeError = "";
             try {
                 if (privilegedService != null) cleanupBeforeError = privilegedService.cleanupDsuBackingImages();
             } catch (Exception exception) {
                 cleanupBeforeError = exception.getMessage() == null ? exception.toString() : exception.getMessage();
             }
             boolean removed = false;
             try {
                 if (privilegedService != null) removed = privilegedService.remove();
             } catch (Exception ignored) { }
             CommandResult result = runPrivilegedCommand("/system/bin/gsi_tool", "wipe");
             boolean success = (removed || result.exitCode == 0)
                     && cleanupBeforeError.isEmpty();
             final String cleanupFailure = cleanupBeforeError;
             runOnUiThread(() -> {
                 if (success) {
                     installPanel.setVisibility(View.GONE);
                     stagesClear();
                      installStage.setText(t("安装进度", "Installation progress"));
                      detailText.setText(t("GSI 状态\n未安装", "GSI status\nNot installed"));
                      gsiStatus.setText(t("未安装", "Not installed"));
                     toast(t("DSU 已撤销", "DSU removed"));
                 } else {
                     String detail = cleanupFailure.isEmpty() ? result.output : cleanupFailure;
                     String message = detail.isEmpty() ? t("撤销 DSU 失败", "Failed to remove DSU") : t("撤销 DSU 失败: ", "Failed to remove DSU: ") + detail;
                    detailText.setText(message);
                    toast(message);
                }
            });
        }).start();
    }
     private void showImageManagement(){
         imageManagementPanel.setVisibility(View.VISIBLE);
         imageManagementPanel.setAlpha(0f);
         imageManagementPanel.setTranslationY(dp(8));
         imageManagementPanel.animate().alpha(1f).translationY(0f).setDuration(220).start();
         imageManagementPanel.removeAllViews();
        TextView loading = text(t("正在读取已安装镜像...", "Reading installed images..."), 13, Color.rgb(77, 87, 105));
        imageManagementPanel.addView(loading, new LinearLayout.LayoutParams(-1, dp(42)));
        new Thread(() -> {
            String raw = "";
            try { if (privilegedService != null) raw = privilegedService.listDsuImages(); }
            catch (Exception ignored) { }
            String result = raw;
            runOnUiThread(() -> renderImageManagement(result));
        }).start();
    }
    private void renderImageManagement(String raw){
        imageManagementPanel.removeAllViews();
        TextView title = text(t("已安装镜像", "Installed images"), 15, Color.rgb(20, 29, 55));
        title.setTypeface(null, 1);
        imageManagementPanel.addView(title, new LinearLayout.LayoutParams(-1, dp(34)));
         if (raw == null || raw.trim().isEmpty()) {
             imageManagementPanel.addView(text(t("无法读取 DSU 镜像目录，或当前设备未提供可访问的镜像文件。", "Unable to read the DSU image directory, or no accessible image files are available."), 13, Color.rgb(77, 87, 105)));
             return;
         }
         if (raw.startsWith("EMPTY|")) {
             String[] empty = raw.split("\\|", 3);
             imageManagementPanel.addView(text(empty.length == 3 ? empty[1] + "\n" + t("目录: ", "Directory: ") + empty[2] : t("暂无已安装镜像", "No installed images"), 13, Color.rgb(77, 87, 105)));
             return;
         }
         boolean hasImage = false;
         for (String line : raw.split("\\r?\\n")) {
              String[] parts = line.split("\\|", -1);
            if (parts.length == 2 && parts[0].equals("ROOT_OK")) {
                imageManagementPanel.addView(text(parts[1], 13, Color.rgb(77, 87, 105)));
                continue;
            }
             if (parts.length < 3) continue;
             hasImage = true;
             String name = parts[0];
            long bytes;
            try { bytes = Long.parseLong(parts[1]); } catch (NumberFormatException e) { bytes = -1; }
            LinearLayout row = new LinearLayout(this);
             row.setGravity(Gravity.CENTER_VERTICAL);
              row.setPadding(dp(12), dp(8), dp(10), dp(8));
             row.setBackgroundResource(R.drawable.liquid_glass_panel);
             row.setAlpha(0f);
             row.setTranslationY(dp(4));
             String sizeText = parts.length > 3 ? parts[3] : formatBytes(bytes);
             TextView label = text(name + "\n" + t("大小: ", "Size: ") + sizeText, 13, Color.rgb(40, 50, 70));
            row.addView(label, new LinearLayout.LayoutParams(0, dp(58), 1));
            Button replace = new Button(this);
             replace.setText(t("替换", "Replace"));
            replace.setTextSize(12);
            replace.setAllCaps(false);
             replace.setMinHeight(0);
             replace.setMinWidth(0);
             replace.setTextColor(Color.WHITE);
              replace.setBackgroundResource(R.drawable.button_blue);
                String backingImage = parts.length > 4 ? parts[4] : name;
                String backingSlot = parts.length > 5 ? parts[5] : "";
                 replace.setOnClickListener(v -> {
                     Haptics.perform(v);
                     chooseReplacement(name, backingImage, backingSlot, parts[2]);
                 });
             row.addView(replace, new LinearLayout.LayoutParams(dp(82), dp(44)));
              LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, dp(76));
              rowLp.setMargins(0, dp(8), 0, 0);
              imageManagementPanel.addView(row, rowLp);
             row.animate().alpha(1f).translationY(0f).setStartDelay(Math.min(180, imageManagementPanel.getChildCount() * 24L)).setDuration(180).start();
         }
         if (!hasImage) {
             imageManagementPanel.addView(text(t("当前没有可管理的镜像文件\n", "No manageable image files are available\n") + raw, 12, Color.rgb(77, 87, 105)));
         }
        TextView hint = text(t("替换完成后点击“重启到 DSU”使更新后的分区生效。", "Tap \"Reboot to DSU\" after replacement to apply the updated partition."), 12, Color.rgb(110, 118, 135));
        imageManagementPanel.addView(hint, new LinearLayout.LayoutParams(-1, dp(42)));
    }
    
    // Enhanced Root Detection - detect KernelSU/Magisk/SuperSU version
    private String detectRootMethod() {
        if (!rootAuthorized) return "未检测到Root";
        
        // v3.51.75: 使用缓存避免重复执行耗时的shell命令
        if (cachedRootMethod != null) {
            return cachedRootMethod;
        }
        
        try {
            // Check KernelSU
            com.topjohnwu.superuser.Shell.Result result = com.topjohnwu.superuser.Shell.cmd("ksud -V").exec();
            if (result.isSuccess() && !result.getOut().isEmpty()) {
                String output = result.getOut().get(0).trim();
                if (!output.isEmpty() && !output.contains("not found")) {
                    cachedRootMethod = "KernelSU v" + output;
                    return cachedRootMethod;
                }
            }
            
            // Check Magisk - try both version command and version code
            result = com.topjohnwu.superuser.Shell.cmd("magisk -v").exec();
            if (result.isSuccess() && !result.getOut().isEmpty()) {
                String versionName = result.getOut().get(0).trim();
                result = com.topjohnwu.superuser.Shell.cmd("magisk -V").exec();
                if (result.isSuccess() && !result.getOut().isEmpty()) {
                    String versionCode = result.getOut().get(0).trim();
                    if (!versionName.isEmpty() && !versionCode.isEmpty()) {
                        cachedRootMethod = "Magisk " + versionName + " (" + versionCode + ")";
                        return cachedRootMethod;
                    }
                }
            }
            
            // Check APatch
            result = com.topjohnwu.superuser.Shell.cmd("apd --version").exec();
            if (result.isSuccess() && !result.getOut().isEmpty()) {
                String ver = result.getOut().get(0).trim();
                if (!ver.isEmpty() && !ver.contains("not found")) {
                    cachedRootMethod = "APatch " + ver;
                    return cachedRootMethod;
                }
            }
            
            // Check SuperSU
            result = com.topjohnwu.superuser.Shell.cmd("su --version").exec();
            if (result.isSuccess() && !result.getOut().isEmpty()) {
                String ver = result.getOut().get(0).trim();
                if (!ver.isEmpty() && !ver.contains("not found")) {
                    cachedRootMethod = "SuperSU " + ver;
                    return cachedRootMethod;
                }
            }
            
            cachedRootMethod = "通用Root (未识别具体方法)";
            return cachedRootMethod;
        } catch (Exception e) {
            cachedRootMethod = "Root已授权 (检测异常)";
            return cachedRootMethod;
        }
    }
    
    // Get SELinux status with Chinese translation
    private String getSELinuxStatus() {
        try {
            com.topjohnwu.superuser.Shell.Result result = com.topjohnwu.superuser.Shell.cmd("getenforce").exec();
            if (result.isSuccess() && !result.getOut().isEmpty()) {
                String status = result.getOut().get(0).trim();
                switch (status) {
                    case "Enforcing":
                        return "Enforcing (强制模式)";
                    case "Permissive":
                        return "Permissive (宽容模式)";
                    case "Disabled":
                        return "Disabled (已禁用)";
                    default:
                        return status;
                }
            }
        } catch (Exception e) {}
        return "Unknown (未知)";
    }
    
    // Get AVB (Android Verified Boot) status
    private String getAVBStatus() {
        try {
            // Use reflection to access SystemProperties
            Class<?> sysProps = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method getMethod = sysProps.getMethod("get", String.class, String.class);
            
            String avbVersion = (String) getMethod.invoke(null, "ro.boot.avb_version", "");
            String verityMode = (String) getMethod.invoke(null, "ro.boot.veritymode", "");
            String verifiedBootState = (String) getMethod.invoke(null, "ro.boot.verifiedbootstate", "");
            
            StringBuilder status = new StringBuilder();
            
            if (!avbVersion.isEmpty()) {
                status.append("AVB ").append(avbVersion);
            } else {
                status.append("AVB 状态未知");
            }
            
            if (!verifiedBootState.isEmpty()) {
                switch (verifiedBootState) {
                    case "green":
                        status.append(" - 绿色 (完全验证)");
                        break;
                    case "yellow":
                        status.append(" - 黄色 (已解锁)");
                        break;
                    case "orange":
                        status.append(" - 橙色 (自定义系统)");
                        break;
                    case "red":
                        status.append(" - 红色 (验证失败)");
                        break;
                    default:
                        status.append(" - ").append(verifiedBootState);
                }
            } else if (!verityMode.isEmpty()) {
                status.append(" - ").append(verityMode);
            }
            
            return status.toString();
        } catch (Exception e) {
            return "AVB 检测失败";
        }
    }
    
    // Get Bootloader lock status
    private String getBootloaderLockStatus() {
        try {
            // Use reflection to access SystemProperties
            Class<?> sysProps = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method getMethod = sysProps.getMethod("get", String.class, String.class);
            
            String unlocked = (String) getMethod.invoke(null, "ro.boot.flash.locked", "");
            String bootloaderStatus = (String) getMethod.invoke(null, "ro.boot.bootloader", "");
            
            if (unlocked.equals("0")) {
                return "已解锁 (Unlocked)";
            } else if (unlocked.equals("1")) {
                return "已锁定 (Locked)";
            }
            
            // Alternative check
            String verifiedBootState = (String) getMethod.invoke(null, "ro.boot.verifiedbootstate", "");
            if (verifiedBootState.equals("green")) {
                return "已锁定 (Locked)";
            } else if (verifiedBootState.equals("orange") || verifiedBootState.equals("yellow")) {
                return "已解锁 (Unlocked)";
            }
            
            return "未知";
        } catch (Exception e) {
            return "检测失败";
        }
    }
    
    // Get active slot (A/B partition)
    private String getActiveSlot() {
        try {
            Class<?> sysProps = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method getMethod = sysProps.getMethod("get", String.class, String.class);
            String slot = (String) getMethod.invoke(null, "ro.boot.slot_suffix", "");
            if (slot.isEmpty()) {
                return "单分区 (Non-A/B)";
            } else {
                return slot.replace("_", "").toUpperCase();
            }
        } catch (Exception e) {
            return "未知";
        }
    }
    
    // Get Widevine DRM level
    private String getWidevineLevel() {
        try {
            android.media.MediaDrm drm = new android.media.MediaDrm(new java.util.UUID(0xEDEF8BA979D64ACEL, 0xA3C827DCD51D21EDL));
            String level = drm.getPropertyString("securityLevel");
            drm.release();
            return level;
        } catch (Exception e) {
            return "未知";
        }
    }
    
    // Get Widevine version
    private String getWidevineVersion() {
        try {
            android.media.MediaDrm drm = new android.media.MediaDrm(new java.util.UUID(0xEDEF8BA979D64ACEL, 0xA3C827DCD51D21EDL));
            String version = drm.getPropertyString("version");
            drm.release();
            return version;
        } catch (Exception e) {
            return "未知";
        }
    }
    
    // Get baseband version
    private String getBasebandVersion() {
        try {
            Class<?> sysProps = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method getMethod = sysProps.getMethod("get", String.class, String.class);
            String baseband = (String) getMethod.invoke(null, "gsm.version.baseband", "");
            if (baseband.isEmpty()) {
                // 尝试其他属性
                baseband = (String) getMethod.invoke(null, "ro.baseband", "");
            }
            return baseband.isEmpty() ? "未知" : baseband;
        } catch (Exception e) {
            return "未知";
        }
    }
    
    // v3.51.76: 获取真实机型名称（通过.market.name属性）
    private String getMarketName() {
        try {
            Class<?> sysProps = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method getMethod = sysProps.getMethod("get", String.class, String.class);
            
            // 尝试常见的market.name属性
            String[] marketProps = {
                "ro.vendor.oplus.market.name",
                "ro.vivo.market.name",
                "ro.oppo.market.name",
                "ro.product.marketname",
                "ro.product.device.marketname",
                "ro.miui.ui.version.name"  // 小米MIUI
            };
            
            for (String prop : marketProps) {
                String marketName = (String) getMethod.invoke(null, prop, "");
                if (!marketName.isEmpty()) {
                    return marketName;
                }
            }
            
            // 如果没有找到market.name，返回 制造商 + 型号
            return android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
        } catch (Exception e) {
            return android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
        }
    }
    
    // v3.51.78: 检测Project Treble支持
    private boolean isTrebleSupported() {
        try {
            Class<?> sysProps = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method getMethod = sysProps.getMethod("get", String.class, String.class);
            String treble = (String) getMethod.invoke(null, "ro.treble.enabled", "false");
            return "true".equals(treble);
        } catch (Exception e) {
            return false;
        }
    }
    
    // v3.51.78: 检测动态分区支持
    private boolean isDynamicPartitionsSupported() {
        try {
            Class<?> sysProps = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method getMethod = sysProps.getMethod("get", String.class, String.class);
            String dynamic = (String) getMethod.invoke(null, "ro.boot.dynamic_partitions", "false");
            return "true".equals(dynamic);
        } catch (Exception e) {
            return false;
        }
    }
    
    // v3.51.78: 检测无缝更新(A/B分区)支持
    private boolean isSeamlessUpdateSupported() {
        try {
            Class<?> sysProps = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method getMethod = sysProps.getMethod("get", String.class, String.class);
            String abUpdate = (String) getMethod.invoke(null, "ro.build.ab_update", "false");
            return "true".equals(abUpdate);
        } catch (Exception e) {
            return false;
        }
    }
    
    // v3.51.78: 获取首批发行Android版本
    private String getFirstApiLevel() {
        try {
            // DEVICE_INITIAL_SDK_INT是API 28+才有的字段，使用反射访问
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                java.lang.reflect.Field field = android.os.Build.VERSION.class.getField("DEVICE_INITIAL_SDK_INT");
                int firstApi = field.getInt(null);
                if (firstApi > 0) {
                    return "Android " + getAndroidVersionName(firstApi);
                }
            }
        } catch (Exception e) {
        }
        return "未知";
    }
    
    // v3.51.78: 根据API Level获取Android版本名
    private String getAndroidVersionName(int apiLevel) {
        switch (apiLevel) {
            case 35: return "15 (Vanilla Ice Cream)";
            case 34: return "14 (Upside Down Cake)";
            case 33: return "13 (Tiramisu)";
            case 32: return "12L (Snow Cone v2)";
            case 31: return "12 (Snow Cone)";
            case 30: return "11 (Red Velvet Cake)";
            case 29: return "10 (Quince Tart)";
            case 28: return "9 (Pie)";
            case 27: return "8.1 (Oreo)";
            case 26: return "8.0 (Oreo)";
            default: return String.valueOf(apiLevel);
        }
    }
    
    // v3.51.78: 获取CPU名称
    // v3.51.79: 获取CPU完整型号名称
    private String getCpuName() {
        String hardware = android.os.Build.HARDWARE;
        
        // 尝试从系统属性读取更友好的名称
        try {
            Class<?> sysProps = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method getMethod = sysProps.getMethod("get", String.class, String.class);
            
            // 尝试常见的CPU名称属性
            String[] cpuProps = {
                "ro.soc.model",
                "ro.board.platform",
                "ro.product.board",
                "ro.hardware.chipname"
            };
            
            for (String prop : cpuProps) {
                String cpuModel = (String) getMethod.invoke(null, prop, "");
                if (!cpuModel.isEmpty() && !cpuModel.equalsIgnoreCase(hardware)) {
                    return formatCpuName(cpuModel);
                }
            }
        } catch (Exception e) {
        }
        
        // 根据Hardware字段推断CPU型号
        return formatCpuName(hardware);
    }
    
    // v3.51.79: 格式化CPU名称
    private String formatCpuName(String rawName) {
        rawName = rawName.trim();
        
        // Qualcomm Snapdragon系列
        if (rawName.toLowerCase().startsWith("sm") || rawName.toLowerCase().contains("qcom")) {
            if (rawName.equalsIgnoreCase("sm8750")) {
                return "Qualcomm Snapdragon 8 Elite";
            } else if (rawName.equalsIgnoreCase("sm8650")) {
                return "Qualcomm Snapdragon 8 Gen 3";
            } else if (rawName.equalsIgnoreCase("sm8550")) {
                return "Qualcomm Snapdragon 8 Gen 2";
            } else if (rawName.equalsIgnoreCase("sm8450")) {
                return "Qualcomm Snapdragon 8 Gen 1";
            } else if (rawName.equalsIgnoreCase("sm8350")) {
                return "Qualcomm Snapdragon 888";
            } else if (rawName.startsWith("sm")) {
                return "Qualcomm " + rawName.toUpperCase();
            }
        }
        
        // MediaTek系列
        if (rawName.toLowerCase().startsWith("mt")) {
            if (rawName.equalsIgnoreCase("mt6989")) {
                return "MediaTek Dimensity 9400";
            } else if (rawName.equalsIgnoreCase("mt6989")) {
                return "MediaTek Dimensity 9300";
            }
            return "MediaTek " + rawName.toUpperCase();
        }
        
        // Exynos系列
        if (rawName.toLowerCase().contains("exynos")) {
            return "Samsung " + rawName;
        }
        
        return rawName;
    }
    
    // v3.51.78: 获取CPU供应商
    private String getCpuVendor() {
        String hardware = android.os.Build.HARDWARE.toLowerCase();
        if (hardware.contains("qcom") || hardware.contains("sm")) {
            return "Qualcomm";
        } else if (hardware.contains("exynos")) {
            return "Samsung";
        } else if (hardware.contains("mt") || hardware.contains("mediatek")) {
            return "MediaTek";
        } else if (hardware.contains("kirin")) {
            return "HiSilicon";
        } else if (hardware.contains("unisoc") || hardware.contains("spreadtrum")) {
            return "Unisoc";
        }
        return "未知";
    }
    
    // Get detailed screen information
    private void populateScreenInfo(LinearLayout card) {
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            Display display = wm.getDefaultDisplay();
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            display.getRealMetrics(metrics);
            android.graphics.Point size = new android.graphics.Point();
            display.getRealSize(size);
            
            // Current resolution and refresh rate
            addInfoRow(card, "当前分辨率", size.x + " x " + size.y, "", 0xff333333);
            addInfoRow(card, "刷新率", String.format("%.1f Hz", display.getRefreshRate()), "", 0xff333333);
            
            // Screen size
            float xdpi = metrics.xdpi;
            float ydpi = metrics.ydpi;
            if (xdpi > 0 && ydpi > 0) {
                float widthInches = size.x / xdpi;
                float heightInches = size.y / ydpi;
                float diagonalInches = (float) Math.sqrt(widthInches * widthInches + heightInches * heightInches);
                float diagonalMm = diagonalInches * 25.4f;
                addInfoRow(card, "屏幕尺寸", String.format("%.2f\"", diagonalInches), String.format("%.0f mm", diagonalMm), 0xff333333);
            }
            
            // DPI
            String densityName = "hdpi";
            if (metrics.densityDpi <= 120) densityName = "ldpi";
            else if (metrics.densityDpi <= 160) densityName = "mdpi";
            else if (metrics.densityDpi <= 240) densityName = "hdpi";
            else if (metrics.densityDpi <= 320) densityName = "xhdpi";
            else if (metrics.densityDpi <= 480) densityName = "xxhdpi";
            else if (metrics.densityDpi <= 640) densityName = "xxxhdpi";
            addInfoRow(card, "密度", metrics.densityDpi + " dpi", densityName, 0xff333333);
            
            // Aspect ratio
            int gcd = gcd(size.x, size.y);
            addInfoRow(card, "宽高比", (size.x / gcd) + ":" + (size.y / gcd), "", 0xff333333);
            
            // Supported modes
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Display.Mode[] modes = display.getSupportedModes();
                
                // Refresh rates
                java.util.Set<String> rates = new java.util.TreeSet<>();
                for (Display.Mode mode : modes) {
                    rates.add(String.format("%.1f Hz", mode.getRefreshRate()));
                }
                if (!rates.isEmpty()) {
                    addInfoRow(card, "支持的刷新率", String.join(", ", rates), "", 0xff757575);
                }
                
                // Resolutions
                java.util.Set<String> resolutions = new java.util.TreeSet<>((a, b) -> {
                    int w1 = Integer.parseInt(a.split(" x ")[0]);
                    int w2 = Integer.parseInt(b.split(" x ")[0]);
                    return Integer.compare(w2, w1);
                });
                for (Display.Mode mode : modes) {
                    resolutions.add(mode.getPhysicalWidth() + " x " + mode.getPhysicalHeight());
                }
                if (!resolutions.isEmpty()) {
                    addInfoRow(card, "支持的分辨率", String.join(", ", resolutions), "", 0xff757575);
                }
            }
            
            // HDR
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    Display.HdrCapabilities hdrCaps = display.getHdrCapabilities();
                    int[] hdrTypes = hdrCaps.getSupportedHdrTypes();
                    java.util.List<String> hdrList = new java.util.ArrayList<>();
                    for (int type : hdrTypes) {
                        switch (type) {
                            case Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION:
                                hdrList.add("Dolby Vision");
                                break;
                            case Display.HdrCapabilities.HDR_TYPE_HDR10:
                                hdrList.add("HDR10");
                                break;
                            case Display.HdrCapabilities.HDR_TYPE_HLG:
                                hdrList.add("HLG");
                                break;
                            case Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS:
                                hdrList.add("HDR10+");
                                break;
                        }
                    }
                    if (!hdrList.isEmpty()) {
                        addInfoRow(card, "HDR支持", String.join(", ", hdrList), "", 0xff16805d);
                    }
                } catch (Exception e) {}
            }
            
            // Wide color gamut
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    addInfoRow(card, "广色域", display.isWideColorGamut() ? "是" : "否", "", 0xff333333);
                } catch (Exception e) {}
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    // 辅助方法：将页面包装在ScrollView中
    private View wrapInScroll(View page) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(page);
        return scroll;
    }
    
    // 辅助方法：构建OTG页面包装器（带FAB）
    private View buildOtgPageWrapper() {
        FrameLayout otgRoot = new FrameLayout(this);
        ScrollView pageScroll = new ScrollView(this);
        pageScroll.setFillViewport(false);
        pageScroll.addView(buildOtgPage());
        otgRoot.addView(pageScroll, new FrameLayout.LayoutParams(-1, -1));
        
        partitionFab = buildPartitionFab();
        FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        fabLp.bottomMargin = dp(84);
        fabLp.rightMargin = dp(16);
        otgRoot.addView(partitionFab, fabLp);
        partitionFab.setVisibility(otgCurrentTab == 0 ? View.VISIBLE : View.GONE);
        
        return otgRoot;
    }
    
    private int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }
    
    private String formatBytes(long bytes){
         if (bytes < 0) return t("大小读取失败", "Size unavailable");
         if (bytes == 0) return t("大小未知", "Unknown size");
        if (bytes >= 1024L * 1024L * 1024L) return String.format(java.util.Locale.US, "%.2f GB", bytes / 1073741824d);
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576d);
    }

    
    // 读取CPU核心数量
    private int getCpuCoreCount() {
        try {
            return Runtime.getRuntime().availableProcessors();
        } catch (Exception e) {
            return 8;  // 默认8核
        }
    }
    
    // 读取单个CPU核心当前频率（KHz）
    private int getCpuFrequency(int core) {
        try {
            String path = "/sys/devices/system/cpu/cpu" + core + "/cpufreq/scaling_cur_freq";
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(path));
            String freq = reader.readLine();
            reader.close();
            return Integer.parseInt(freq.trim());
        } catch (Exception e) {
            return 0;
        }
    }
    
    // 读取CPU核心最大频率（KHz）
    private int getCpuMaxFrequency(int core) {
        try {
            String path = "/sys/devices/system/cpu/cpu" + core + "/cpufreq/cpuinfo_max_freq";
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(path));
            String freq = reader.readLine();
            reader.close();
            return Integer.parseInt(freq.trim());
        } catch (Exception e) {
            return 3000000;  // 默认3GHz
        }
    }
    
    // 更新CPU频率柱状图
    private void updateCpuFrequencyBars(LinearLayout container) {
        if (container == null) return;
        
        runOnUiThread(() -> {
            int coreCount = getCpuCoreCount();
            
            // 首次构建所有核心视图
            if (container.getChildCount() == 0) {
                for (int i = 0; i < coreCount; i++) {
                    LinearLayout coreRow = new LinearLayout(this);
                    coreRow.setOrientation(LinearLayout.HORIZONTAL);
                    coreRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    coreRow.setPadding(0, dp(4), 0, dp(4));
                    coreRow.setTag("cpu_core_" + i);
                    
                    // CPU标签
                    TextView coreLabel = text("CPU" + i, 11, 0xff757575);
                    coreLabel.setTypeface(android.graphics.Typeface.MONOSPACE);
                    coreLabel.setTag("label");
                    LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(dp(50), -2);
                    coreRow.addView(coreLabel, labelLp);
                    
                    // 频率柱状图容器
                    FrameLayout barContainer = new FrameLayout(this);
                    barContainer.setTag("bar_container");
                    LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(0, dp(14), 1);
                    barLp.setMargins(dp(8), 0, dp(8), 0);
                    
                    // 背景条
                    View bgBar = new View(this);
                    GradientDrawable bgDrawable = new GradientDrawable();
                    bgDrawable.setColor(0xFFE8E8E8);
                    bgDrawable.setCornerRadius(dp(7));
                    bgBar.setBackground(bgDrawable);
                    barContainer.addView(bgBar, new FrameLayout.LayoutParams(-1, -1));
                    
                    // 频率条
                    View freqBar = new View(this);
                    freqBar.setTag("freq_bar");
                    GradientDrawable freqDrawable = new GradientDrawable();
                    freqDrawable.setColor(0xff16805d);
                    freqDrawable.setCornerRadius(dp(7));
                    freqBar.setBackground(freqDrawable);
                    FrameLayout.LayoutParams freqBarLp = new FrameLayout.LayoutParams(0, -1);
                    barContainer.addView(freqBar, freqBarLp);
                    
                    coreRow.addView(barContainer, barLp);
                    
                    // 频率数值
                    TextView freqLabel = text("0 MHz", 11, 0xff333333);
                    freqLabel.setTag("freq_text");
                    freqLabel.setTypeface(android.graphics.Typeface.MONOSPACE);
                    LinearLayout.LayoutParams freqLabelLp = new LinearLayout.LayoutParams(dp(85), -2);
                    freqLabel.setGravity(android.view.Gravity.END);
                    coreRow.addView(freqLabel, freqLabelLp);
                    
                    container.addView(coreRow);
                }
            }
            
            // 更新所有核心频率
            for (int i = 0; i < Math.min(coreCount, container.getChildCount()); i++) {
                View coreRow = container.getChildAt(i);
                if (coreRow == null) continue;
                
                int currentFreq = getCpuFrequency(i);
                int maxFreq = getCpuMaxFrequency(i);
                float ratio = maxFreq > 0 ? (float) currentFreq / maxFreq : 0;
                
                // 更新频率条宽度和颜色（带动画）
                FrameLayout barContainer = coreRow.findViewWithTag("bar_container");
                if (barContainer != null) {
                    View freqBar = barContainer.findViewWithTag("freq_bar");
                    if (freqBar != null) {
                        // 颜色渐变：绿色(低频) -> 蓝色(中频) -> 橙色(高频) -> 红色(超频)
                        int color;
                        if (ratio < 0.3f) {
                            color = 0xff16805d;  // 绿色
                        } else if (ratio < 0.6f) {
                            color = 0xff4C8EF5;  // 蓝色
                        } else if (ratio < 0.85f) {
                            color = 0xffF39C12;  // 橙色
                        } else {
                            color = 0xffE74C3C;  // 红色
                        }
                        
                        GradientDrawable drawable = (GradientDrawable) freqBar.getBackground();
                        drawable.setColor(color);
                        
                        // 平滑动画更新宽度
                        int targetWidth = (int) (barContainer.getWidth() * ratio);
                        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) freqBar.getLayoutParams();
                        if (lp.width != targetWidth) {
                            freqBar.animate()
                                .scaleX(ratio)
                                .setDuration(300)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                                .start();
                            lp.width = targetWidth;
                            freqBar.setLayoutParams(lp);
                        }
                    }
                }
                
                // 更新频率文本
                TextView freqText = coreRow.findViewWithTag("freq_text");
                if (freqText != null) {
                    String text = currentFreq > 0 ? String.format("%d MHz", currentFreq / 1000) : "离线";
                    freqText.setText(text);
                }
            }
        });
    }
    
    // 启动CPU频率监控
    private void startCpuFrequencyMonitor(LinearLayout container) {
        if (cpuFreqHandler == null) {
            cpuFreqHandler = new Handler(android.os.Looper.getMainLooper());
        }
        
        cpuFreqUpdateTask = new Runnable() {
            @Override
            public void run() {
                if (container != null && container.isAttachedToWindow()) {
                    updateCpuFrequencyBars(container);
                    cpuFreqHandler.postDelayed(this, 1000);  // 每秒更新
                }
            }
        };
        
        cpuFreqHandler.postDelayed(cpuFreqUpdateTask, 1000);
    }
    
    // 停止CPU频率监控
    private void stopCpuFrequencyMonitor() {
        if (cpuFreqHandler != null && cpuFreqUpdateTask != null) {
            cpuFreqHandler.removeCallbacks(cpuFreqUpdateTask);
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCpuFrequencyMonitor();
    }
      private void chooseReplacement(String targetName, String backingImage, String backingSlot, String imagePath){
          replacementPartition = targetName;
          replacementBackingImage = backingImage;
          replacementSlot = backingSlot;
         replacementImagePath = imagePath;
         // v3.30.15：内置文件浏览器替换系统 SAF
         FileBrowserDialog.show(this, t("选择替换镜像", "Select replacement image"),
                 new String[]{".img", ".raw"}, "/storage/emulated/0",
                 path -> {
                     if (replacementPartition != null)
                         replaceImage(Uri.fromFile(new File(path)), replacementPartition);
                 });
      }
       private void replaceImage(Uri source, String partition){
          String selectedName = displayName(source).toLowerCase(java.util.Locale.US);
          if (!selectedName.endsWith(".img") && !selectedName.endsWith(".raw")) {
               toast(t("请选择 .img 或 .raw 镜像文件", "Choose an .img or .raw image file"));
              return;
          }
           String targetPartition = partition;
           String targetBackingImage = replacementBackingImage == null
                   ? targetPartition : replacementBackingImage;
           String targetSlot = replacementSlot == null || replacementSlot.isEmpty()
                   ? DSU_SLOT : replacementSlot;
          String sourcePartition = partitionBaseName(selectedName);
          String expectedPartition = partitionBaseName(targetPartition);
          if (!sourcePartition.equals(expectedPartition)) {
              toast(t("镜像分区名不匹配：需要 " + expectedPartition + ".img，实际为 " + selectedName,
                      "Partition name mismatch: expected " + expectedPartition + ".img, got " + selectedName));
              return;
          }
         // v3.9.7：替换链路完全按 DSU Sideloader Plus 原版（反编译 smali）执行：
         // 校验分区名 → root 服务 replaceDsuBackingImage（内部 createBackingImage →
         // mapImageDevice → copyFileToBlockDevice → unmapImageDevice，失败清理）
         stagesClear();
         installStage.setText(t("正在替换 " + targetPartition, "Replacing " + targetPartition));
         stageBegin("check", t("校验镜像 " + targetPartition, "Verifying image"));
           new Thread(() -> {
               ParcelFileDescriptor fd = null;
               String error = "";
               boolean success = false;
                try {
                    runOnUiThread(() -> stageDone("check", t("校验镜像 " + targetPartition, "Verifying image")));
                    if (privilegedService == null) error = "ROOT service unavailable";
                    if (error.isEmpty()) {
                        runOnUiThread(() -> stageBegin("write",
                                t("写入 " + targetPartition + " 镜像", "Writing " + targetPartition + " image")));
                        // 直接打开全新 fd，不 peek 文件头 —— 偏移天然为 0，
                        // root 侧 AutoCloseInputStream 从文件头读起，与 smali 行为一致
                        fd = getContentResolver().openFileDescriptor(source, "r");
                        long imageSize = replacementSize(source, fd);
                        if (imageSize <= 0) error = "无法确定镜像大小";
                        else error = privilegedService.replaceDsuBackingImage(
                                targetSlot, targetBackingImage, fd, imageSize, true, replaceProgressCallback);
                        success = error != null && error.isEmpty();
                    }
                } catch (Exception exception) { error = exception.getMessage() == null ? exception.toString() : exception.getMessage(); }
               finally {
                   if (fd != null) try { fd.close(); } catch (Exception ignored) { }
               }
               final String operationError = error;
                String message = success ? t("替换 " + targetPartition + " 完成，请点击“重启到 DSU”使其生效", "Replacement of " + targetPartition + " complete. Tap \"Reboot to DSU\" to apply it.")
                        : t("替换 " + targetPartition + " 失败：" + operationError, "Failed to replace " + targetPartition + ": " + operationError);
              boolean result = success;
              runOnUiThread(() -> {
                  finishProgress(message, result);
                  if (result) {
                      showImageManagement();
                      mainHandler.postDelayed(() -> installPanel.setVisibility(View.GONE), 1200);
                  }
              });
          }).start();
      }
     /** v3.9.5：root 服务写入进度的 binder 回调（“写入”阶段真实百分比） */
     private final IRootInstallCallback.Stub replaceProgressCallback = new IRootInstallCallback.Stub() {
         @Override public void onStage(String stage, int progress) {
             runOnUiThread(() -> stageUpdate("write", Math.max(0, Math.min(100, progress))));
         }
         @Override public void onFinished(boolean success, String message) { }
     };
      private String partitionBaseName(String name) {
          String normalized = name == null ? "" : name.toLowerCase(java.util.Locale.US);
          if (normalized.endsWith(".img") || normalized.endsWith(".raw"))
              normalized = normalized.substring(0, normalized.lastIndexOf('.'));
          if (normalized.endsWith("_gsi"))
              normalized = normalized.substring(0, normalized.length() - 4);
          return normalized;
      }
      private void showInstalledGsiSummary() {
          String packageName = installedZipName == null || installedZipName.trim().isEmpty()
                  ? t("未记录安装包名称", "Package name unavailable") : installedZipName;
          detailText.setText(t("GSI 状态\nGSI 已成功安装，等待启动\n安装包:\n", "GSI status\nGSI installed, waiting to boot\nPackage:\n") + packageName);
      }
     private long replacementSize(Uri uri, ParcelFileDescriptor fd) {
         try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
             if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0);
         } catch (Exception ignored) { }
         try { return fd == null ? -1 : fd.getStatSize(); } catch (Exception ignored) { return -1; }
     }
    private void bootDsu(){
        IPrivilegedService service = privilegedService;
         if (service == null) { toast(t("ROOT Installer 尚未连接", "ROOT installer is not connected")); return; }
        new Thread(() -> {
            try {
                if (!service.isInstalled()) {
                     runOnUiThread(() -> toast(t("启动 DSU 失败：当前没有已安装的 DSU", "Unable to boot DSU: no DSU is installed")));
                    return;
                }
                if (!service.setEnable(true, true)) {
                     runOnUiThread(() -> toast(t("启动 DSU 失败：无法设置 DSU 启动状态", "Unable to boot DSU: could not enable DSU")));
                    return;
                }
                if (!service.boot()) {
                     runOnUiThread(() -> toast(t("启动 DSU 失败：系统拒绝重启到 DSU", "Unable to boot DSU: system rejected reboot")));
                    return;
                }
            } catch (Exception e) {
                 runOnUiThread(() -> toast(t("启动 DSU 失败: ", "Unable to boot DSU: ") + e.getMessage()));
                return;
            }
             runOnUiThread(() -> toast(t("设备正在重启到 DSU", "Device is rebooting to DSU")));
        }).start();
    }
    
      /** v3.50.16：简化版景深背景 —— 全图模糊 + 文字区域半透明遮罩 + 下方清晰区域 */
      /** 
       * v3.50.17：真正的景深效果 Drawable —— 基于亮度分离前景/背景
       * 远景（天空）模糊，近景（人物）清晰，无黑色遮罩
       */
      private static final class SimpleBokehDrawable extends Drawable {
         private final Bitmap result;  // 最终合成的图片
         private final float radius;
         private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
         
         SimpleBokehDrawable(android.content.Context ctx, Bitmap original, float radius) {
             this.radius = radius;
             this.result = createBokehEffect(ctx, original);
         }
         
         /** 创建景深效果：背景模糊 + 前景清晰 */
         private Bitmap createBokehEffect(android.content.Context ctx, Bitmap src) {
             int w = src.getWidth();
             int h = src.getHeight();
             
             // 1. 创建全图模糊版本
             Bitmap blurred = createBlurredBitmap(ctx, src, 25f);
             if (blurred == src) return src; // 模糊失败，返回原图
             
             // 2. 创建结果图
             Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
             
             // 3. 逐像素混合：根据亮度决定使用模糊还是清晰
             int[] srcPixels = new int[w * h];
             int[] blurPixels = new int[w * h];
             src.getPixels(srcPixels, 0, w, 0, 0, w, h);
             blurred.getPixels(blurPixels, 0, w, 0, 0, w, h);
             
             int[] outPixels = new int[w * h];
             for (int i = 0; i < srcPixels.length; i++) {
                 int pixel = srcPixels[i];
                 int r = (pixel >> 16) & 0xFF;
                 int g = (pixel >> 8) & 0xFF;
                 int b = pixel & 0xFF;
                 
                 // 计算亮度
                 int brightness = (r * 299 + g * 587 + b * 114) / 1000;
                 
                 // 根据亮度混合模糊和清晰版本
                 if (brightness > 140) {
                     // 亮度高（人物）→ 使用清晰版本
                     outPixels[i] = srcPixels[i];
                 } else if (brightness > 100) {
                     // 过渡区域 → 混合
                     float ratio = (brightness - 100) / 40f;
                     outPixels[i] = blendPixels(blurPixels[i], srcPixels[i], ratio);
                 } else {
                     // 亮度低（背景）→ 使用模糊版本
                     outPixels[i] = blurPixels[i];
                 }
             }
             
             result.setPixels(outPixels, 0, w, 0, 0, w, h);
             blurred.recycle();
             return result;
         }
         
         /** 混合两个像素 */
         private int blendPixels(int bg, int fg, float ratio) {
             int a = (int)(((bg >>> 24) * (1 - ratio) + (fg >>> 24) * ratio));
             int r = (int)((((bg >> 16) & 0xFF) * (1 - ratio) + ((fg >> 16) & 0xFF) * ratio));
             int g = (int)((((bg >> 8) & 0xFF) * (1 - ratio) + ((fg >> 8) & 0xFF) * ratio));
             int b = (int)(((bg & 0xFF) * (1 - ratio) + (fg & 0xFF) * ratio));
             return (a << 24) | (r << 16) | (g << 8) | b;
         }
         
         private Bitmap createBlurredBitmap(android.content.Context ctx, Bitmap src, float blurRadius) {
             try {
                 android.renderscript.RenderScript rs = android.renderscript.RenderScript.create(ctx);
                 android.renderscript.Allocation input = android.renderscript.Allocation.createFromBitmap(rs, src);
                 android.renderscript.Allocation output = android.renderscript.Allocation.createTyped(rs, input.getType());
                 android.renderscript.ScriptIntrinsicBlur script = android.renderscript.ScriptIntrinsicBlur.create(rs, android.renderscript.Element.U8_4(rs));
                 script.setRadius(blurRadius);
                 script.setInput(input);
                 script.forEach(output);
                 Bitmap blurred = Bitmap.createBitmap(src.getWidth(), src.getHeight(), src.getConfig());
                 output.copyTo(blurred);
                 rs.destroy();
                 return blurred;
             } catch (Exception e) {
                 return src;
             }
         }
         
         @Override public void draw(Canvas canvas) {
             RectF bounds = new RectF(getBounds());
             int saveCount = canvas.save();
             
             // 裁剪圆角
             android.graphics.Path path = new android.graphics.Path();
             path.addRoundRect(bounds, radius, radius, android.graphics.Path.Direction.CW);
             canvas.clipPath(path);
             
             float scale = Math.max(bounds.width() / result.getWidth(), bounds.height() / result.getHeight());
             float width = result.getWidth() * scale;
             float height = result.getHeight() * scale;
             float left = bounds.left + (bounds.width() - width) / 2f;
             float top = bounds.top + (bounds.height() - height) / 2f;
             
             android.graphics.Rect src = new android.graphics.Rect(0, 0, result.getWidth(), result.getHeight());
             RectF dst = new RectF(left, top, left + width, top + height);
             
             // 绘制合成后的图片
             canvas.drawBitmap(result, src, dst, paint);
             
             canvas.restoreToCount(saveCount);
             
             // 边框
             paint.setStyle(Paint.Style.STROKE);
             paint.setStrokeWidth(1.5f);
             paint.setColor(0xCFFFFFFF);
             canvas.drawRoundRect(bounds.left + .75f, bounds.top + .75f, bounds.right - .75f, bounds.bottom - .75f, radius, radius, paint);
             paint.setStyle(Paint.Style.FILL);
         }
         
         @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
         @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
         @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
      }
      
      private static final class RoundedCropDrawable extends Drawable {
         private final Bitmap bitmap;
         private final float radius;
         private final float verticalOffset; // v3.50.14：垂直偏移（负数上移，正数下移）
         private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
         RoundedCropDrawable(Bitmap bitmap, float radius){ this(bitmap, radius, 0f); }
         RoundedCropDrawable(Bitmap bitmap, float radius, float verticalOffset){
             this.bitmap = bitmap;
             this.radius = radius;
             this.verticalOffset = verticalOffset;
         }
         @Override public void draw(Canvas canvas){
             RectF bounds = new RectF(getBounds());
             float scale = Math.max(bounds.width() / bitmap.getWidth(), bounds.height() / bitmap.getHeight());
             float width = bitmap.getWidth() * scale;
             float height = bitmap.getHeight() * scale;
             float left = bounds.left + (bounds.width() - width) / 2f;
             // v3.50.14：应用垂直偏移，让人物上移
             float top = bounds.top + (bounds.height() - height) / 2f + verticalOffset;
             BitmapShader shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
             android.graphics.Matrix matrix = new android.graphics.Matrix();
             matrix.setScale(scale, scale);
             matrix.postTranslate(left, top);
             shader.setLocalMatrix(matrix);
              paint.setShader(shader);
              canvas.drawRoundRect(bounds, radius, radius, paint);
              paint.setShader(null);
              paint.setStyle(Paint.Style.STROKE);
              paint.setStrokeWidth(1.5f);
              paint.setColor(0xCFFFFFFF);
              canvas.drawRoundRect(bounds.left + .75f, bounds.top + .75f, bounds.right - .75f, bounds.bottom - .75f, radius, radius, paint);
              paint.setStyle(Paint.Style.FILL);
         }
         @Override public void setAlpha(int alpha){ paint.setAlpha(alpha); }
         @Override public void setColorFilter(android.graphics.ColorFilter filter){ paint.setColorFilter(filter); }
         @Override public int getOpacity(){ return android.graphics.PixelFormat.TRANSLUCENT; }
     }
     private void confirm(String t,String m,final Runnable r){ new AlertDialog.Builder(this).setTitle(t).setMessage(m).setPositiveButton("继续",(d,w)->r.run()).setNegativeButton("取消",null).show(); }
     private void toast(String s){ Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
     
     /** v3.10.61：圆形安全评分视图（中心显示分数，外圈彩色进度环） */
     private static class SecurityScoreView extends View {
         private Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
         private int score = 0;
         private int targetScore = 0;
         private android.animation.ValueAnimator animator;
         
         SecurityScoreView(android.content.Context ctx) {
             super(ctx);
         }
         
         void setScore(int s) {
             targetScore = Math.max(0, Math.min(100, s));
             if (animator != null) animator.cancel();
             animator = android.animation.ValueAnimator.ofInt(score, targetScore);
             animator.setDuration(1200);
             animator.setInterpolator(new android.view.animation.DecelerateInterpolator(2f));
             animator.addUpdateListener(a -> {
                 score = (int) a.getAnimatedValue();
                 invalidate();
             });
             animator.start();
         }
         
         @Override
         protected void onDraw(Canvas canvas) {
             super.onDraw(canvas);
             int w = getWidth();
             int h = getHeight();
             int cx = w / 2;
             int cy = h / 2;
             int radius = Math.min(w, h) / 2 - 40;
             
             // 背景圆环（浅灰）
             paint.setStyle(Paint.Style.STROKE);
             paint.setStrokeWidth(28);
             paint.setColor(0x22ffffff);
             canvas.drawCircle(cx, cy, radius, paint);
             
             // 彩色进度环（根据分数变色）
             paint.setStrokeCap(Paint.Cap.ROUND);
             int color = score >= 80 ? 0xff16805d : score >= 60 ? 0xfff39c12 : 0xffbd4a4a;
             paint.setColor(color);
             float sweep = score * 3.6f;
             canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, -90, sweep, false, paint);
             
             // 中心分数文字
             paint.setStyle(Paint.Style.FILL);
             paint.setTextAlign(Paint.Align.CENTER);
             paint.setColor(0xffffffff);
             paint.setTextSize(84);
             paint.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
             canvas.drawText(String.valueOf(score), cx, cy + 28, paint);
             
             // 底部"安全评分"标签
             paint.setTextSize(24);
             paint.setColor(0xccffffff);
             canvas.drawText("安全评分", cx, cy + 70, paint);
         }
         
         @Override
         protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
             int size = MeasureSpec.getSize(widthMeasureSpec);
             setMeasuredDimension(size, size);
         }
      }
      
      /** v3.51.81: 加载 SoC 数据库 */
      private void loadSocsDatabase() {
          try {
              InputStream is = getAssets().open("socs.json");
              byte[] buffer = new byte[is.available()];
              is.read(buffer);
              is.close();
              String json = new String(buffer, "UTF-8");
              socsDatabase = new JSONObject(json);
              
              // v3.51.82: 输出调试信息
              android.util.Log.d("SoC-Debug", "=== Device Info ===");
              android.util.Log.d("SoC-Debug", "HARDWARE: " + android.os.Build.HARDWARE);
              android.util.Log.d("SoC-Debug", "BOARD: " + android.os.Build.BOARD);
              android.util.Log.d("SoC-Debug", "DEVICE: " + android.os.Build.DEVICE);
              android.util.Log.d("SoC-Debug", "PRODUCT: " + android.os.Build.PRODUCT);
              android.util.Log.d("SoC-Debug", "MODEL: " + android.os.Build.MODEL);
              android.util.Log.d("SoC-Debug", "MANUFACTURER: " + android.os.Build.MANUFACTURER);
              if (android.os.Build.VERSION.SDK_INT >= 31) {
                  android.util.Log.d("SoC-Debug", "SOC_MANUFACTURER: " + android.os.Build.SOC_MANUFACTURER);
                  android.util.Log.d("SoC-Debug", "SOC_MODEL: " + android.os.Build.SOC_MODEL);
              }
              
              // 根据当前硬件查找SoC信息
              lookupCurrentSoc();
          } catch (Throwable e) {
              android.util.Log.e("MainActivity", "Failed to load socs.json", e);
          }
      }
      
      /** v3.51.81: 查找当前设备的SoC信息 */
      private void lookupCurrentSoc() {
          if (socsDatabase == null) return;
          try {
              // v3.51.82: 优先使用BOARD（通常是精确的SoC型号，如SM8550）
              String[] keys = {
                  android.os.Build.BOARD,                                      // 优先：SM8550、exynos1080等
                  android.os.Build.SOC_MANUFACTURER + " " + android.os.Build.SOC_MODEL,
                  getCpuName(),
                  android.os.Build.HARDWARE                                     // 最后：qcom、exynos等通用名
              };
              
              // v3.51.86: 输出所有尝试的键
              android.util.Log.d("SoC-Lookup", "=== Trying keys ===");
              for (int i = 0; i < keys.length; i++) {
                  android.util.Log.d("SoC-Lookup", "[" + i + "] " + keys[i]);
              }
              
              for (String key : keys) {
                  if (key == null || key.isEmpty()) continue;
                  
                  android.util.Log.d("SoC-Lookup", "Trying key: '" + key + "'");
                  
                  // 1. 精确匹配
                  if (socsDatabase.has(key)) {
                      currentSocInfo = socsDatabase.getJSONObject(key);
                      android.util.Log.d("SoC-Lookup", "✓ MATCHED (exact): " + key);
                      return;
                  }
                  
                  // 2. 大小写不敏感匹配
                  java.util.Iterator<String> iter = socsDatabase.keys();
                  while (iter.hasNext()) {
                      String dbKey = iter.next();
                      if (dbKey.equalsIgnoreCase(key)) {
                          currentSocInfo = socsDatabase.getJSONObject(dbKey);
                          android.util.Log.d("SoC-Lookup", "✓ MATCHED (case-insensitive): " + dbKey);
                          return;
                      }
                  }
                  
                  // 3. 去空格匹配
                  String normalized = key.replaceAll("\\s+", "").toLowerCase();
                  if (normalized.length() < 3) continue; // 太短的跳过模糊匹配
                  
                  iter = socsDatabase.keys();
                  while (iter.hasNext()) {
                      String dbKey = iter.next();
                      String normalizedKey = dbKey.replaceAll("\\s+", "").toLowerCase();
                      if (normalizedKey.equals(normalized)) {
                          currentSocInfo = socsDatabase.getJSONObject(dbKey);
                          android.util.Log.d("SoC-Lookup", "✓ MATCHED (normalized): " + dbKey);
                          return;
                      }
                  }
                  
                  android.util.Log.d("SoC-Lookup", "✗ Not matched: " + key);
              }
              android.util.Log.w("SoC-Lookup", "✗✗✗ SoC NOT FOUND after trying all keys");
          } catch (Throwable e) {
              android.util.Log.e("MainActivity", "Failed to lookup SoC info", e);
          }
      }
      
      /** v3.51.81: 获取SoC制程工艺 */
      private String getSocFab() {
          if (currentSocInfo != null) {
              try {
                  String fab = currentSocInfo.optString("FAB", "").trim();
                  // v3.51.88: 通用键的FAB为空，尝试从处理器型号推断制程
                  if (!fab.isEmpty()) return fab;
              } catch (Throwable ignored) {}
          }
          // v3.51.88: 从getCpuName()返回的处理器型号推断制程
          return inferFabFromCpuName(getCpuName());
      }
      
      /** v3.51.88: 根据处理器型号推断制程工艺 */
      private String inferFabFromCpuName(String cpuName) {
          String lower = cpuName.toLowerCase();
          
          // v3.51.89: 输出调试信息
          android.util.Log.d("FAB-Infer", "Trying to infer FAB from: '" + cpuName + "' (lowercase: '" + lower + "')");
          
          // Qualcomm Snapdragon
          if (lower.contains("8 elite") || lower.contains("8elite")) {
              android.util.Log.d("FAB-Infer", "Matched: 8 Elite → 3nm");
              return "3 nm";
          }
          if (lower.contains("8 gen 3") || lower.contains("sm8650")) {
              android.util.Log.d("FAB-Infer", "Matched: 8 Gen 3 → 4nm");
              return "4 nm";
          }
          if (lower.contains("8 gen 2") || lower.contains("sm8550")) {
              android.util.Log.d("FAB-Infer", "Matched: 8 Gen 2 → 4nm");
              return "4 nm";
          }
          if (lower.contains("8 gen 1") || lower.contains("sm8450")) {
              android.util.Log.d("FAB-Infer", "Matched: 8 Gen 1 → 4nm");
              return "4 nm";
          }
          if (lower.contains("888") || lower.contains("sm8350")) {
              android.util.Log.d("FAB-Infer", "Matched: 888 → 5nm");
              return "5 nm";
          }
          if (lower.contains("870") || lower.contains("865")) {
              android.util.Log.d("FAB-Infer", "Matched: 870/865 → 7nm");
              return "7 nm";
          }
          
          // MediaTek Dimensity
          if (lower.contains("9400")) return "3 nm";
          if (lower.contains("9300") || lower.contains("9200")) return "4 nm";
          if (lower.contains("9000")) return "4 nm";
          if (lower.contains("8200") || lower.contains("8100")) return "5 nm";
          
          // Samsung Exynos
          if (lower.contains("2400") || lower.contains("2500")) return "4 nm";
          if (lower.contains("2200") || lower.contains("2100")) return "5 nm";
          
          // Apple (如果有)
          if (lower.contains("a18") || lower.contains("a17")) return "3 nm";
          if (lower.contains("a16") || lower.contains("a15")) return "4 nm";
          
          android.util.Log.w("FAB-Infer", "No match found, returning '未知'");
          return "未知";
      }
      
      /** v3.51.81: 获取SoC完整名称 */
      private String getSocFullName() {
          if (currentSocInfo != null) {
              try {
                  String name = currentSocInfo.optString("NAME", "").trim();
                  if (!name.isEmpty()) return name;
              } catch (Throwable ignored) {}
          }
          return getCpuName();
      }
      
      /** v3.51.81: 获取SoC供应商 */
      private String getSocVendor() {
          if (currentSocInfo != null) {
              try {
                  String vendor = currentSocInfo.optString("VENDOR", "").trim();
                  if (!vendor.isEmpty()) return vendor;
              } catch (Throwable ignored) {}
          }
          return getCpuVendor();
      }
      
      /** v3.51.84: 根据手机品牌获取Logo资源ID（自动识别） */
      private int getBrandLogoResource() {
          String manufacturer = android.os.Build.MANUFACTURER.toLowerCase();
          String brand = android.os.Build.BRAND.toLowerCase();
          String model = android.os.Build.MODEL.toLowerCase();
          
          // 优先判断manufacturer
          if (manufacturer.contains("xiaomi") || brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco")) {
              return getResources().getIdentifier("ic_xiaomi", "drawable", getPackageName());
          }
          if (manufacturer.contains("oneplus") || brand.contains("oneplus")) {
              return getResources().getIdentifier("ic_oneplus", "drawable", getPackageName());
          }
          if (manufacturer.contains("oppo") || brand.contains("oppo") || brand.contains("realme")) {
              // realme是OPPO子品牌，但有独立Logo
              if (brand.contains("realme")) {
                  return getResources().getIdentifier("ic_realme", "drawable", getPackageName());
              }
              return getResources().getIdentifier("ic_oppo", "drawable", getPackageName());
          }
          if (manufacturer.contains("vivo") || brand.contains("vivo") || brand.contains("iqoo")) {
              return getResources().getIdentifier("ic_vivo", "drawable", getPackageName());
          }
          if (manufacturer.contains("huawei") || brand.contains("huawei") || brand.contains("honor")) {
              if (brand.contains("honor")) {
                  return getResources().getIdentifier("ic_honor", "drawable", getPackageName());
              }
              return getResources().getIdentifier("ic_huawei", "drawable", getPackageName());
          }
          if (manufacturer.contains("samsung") || brand.contains("samsung")) {
              return getResources().getIdentifier("ic_samsung", "drawable", getPackageName());
          }
          if (manufacturer.contains("google") || brand.contains("google") || brand.contains("pixel")) {
              return getResources().getIdentifier("ic_google", "drawable", getPackageName());
          }
          if (manufacturer.contains("motorola") || brand.contains("motorola") || brand.contains("moto")) {
              return getResources().getIdentifier("ic_motorola", "drawable", getPackageName());
          }
          if (manufacturer.contains("sony") || brand.contains("sony")) {
              return getResources().getIdentifier("ic_sony", "drawable", getPackageName());
          }
          if (manufacturer.contains("lg") || brand.contains("lg")) {
              return getResources().getIdentifier("ic_lg", "drawable", getPackageName());
          }
          if (manufacturer.contains("asus") || brand.contains("asus") || brand.contains("zenfone") || brand.contains("rog")) {
              return getResources().getIdentifier("ic_asus", "drawable", getPackageName());
          }
          if (manufacturer.contains("nokia") || brand.contains("nokia")) {
              return getResources().getIdentifier("ic_nokia", "drawable", getPackageName());
          }
          if (manufacturer.contains("lenovo") || brand.contains("lenovo")) {
              return getResources().getIdentifier("ic_lenovo", "drawable", getPackageName());
          }
          if (manufacturer.contains("zte") || brand.contains("zte") || brand.contains("nubia")) {
              return getResources().getIdentifier("ic_zte", "drawable", getPackageName());
          }
          if (manufacturer.contains("meizu") || brand.contains("meizu")) {
              return getResources().getIdentifier("ic_meizu", "drawable", getPackageName());
          }
          if (manufacturer.contains("htc") || brand.contains("htc")) {
              return getResources().getIdentifier("ic_htc", "drawable", getPackageName());
          }
          
          // 没有匹配的品牌，返回0（不显示Logo）
          return 0;
      }
      
      /** v3.51.81: 根据供应商/硬件名获取Logo资源ID */
      private int getCpuLogoResource() {
          String hardware = android.os.Build.HARDWARE.toLowerCase();
          String vendor = getSocVendor().toLowerCase();
          String cpuName = getCpuName().toLowerCase();
          
          // 优先判断hardware（最准确）
          // 高通骁龙
          if (hardware.contains("qcom") || hardware.contains("sm") || hardware.contains("sdm")) {
              return getResources().getIdentifier("ic_snapdragon", "drawable", getPackageName());
          }
          // 联发科
          if (hardware.contains("mt") && !hardware.contains("exynos")) {
              return getResources().getIdentifier("ic_mediatek", "drawable", getPackageName());
          }
          // 三星Exynos
          if (hardware.contains("exynos") || hardware.contains("s5e")) {
              return getResources().getIdentifier("ic_exynos", "drawable", getPackageName());
          }
          // 华为麒麟
          if (hardware.contains("kirin") || hardware.contains("hi36") || hardware.contains("hi37")) {
              return getResources().getIdentifier("ic_kirin", "drawable", getPackageName());
          }
          // 紫光展锐
          if (hardware.contains("unisoc") || hardware.contains("ums") || hardware.contains("spreadtrum")) {
              return getResources().getIdentifier("ic_unisoc", "drawable", getPackageName());
          }
          // 瑞芯微
          if (hardware.contains("rk") || hardware.contains("rockchip")) {
              return getResources().getIdentifier("ic_rockchip", "drawable", getPackageName());
          }
          // 全志
          if (hardware.contains("sun") && (hardware.contains("50") || hardware.contains("8"))) {
              return getResources().getIdentifier("ic_allwinner", "drawable", getPackageName());
          }
          
          // 再判断vendor
          if (vendor.contains("qualcomm")) {
              return getResources().getIdentifier("ic_snapdragon", "drawable", getPackageName());
          }
          if (vendor.contains("mediatek")) {
              return getResources().getIdentifier("ic_mediatek", "drawable", getPackageName());
          }
          if (vendor.contains("samsung")) {
              return getResources().getIdentifier("ic_exynos", "drawable", getPackageName());
          }
          if (vendor.contains("hisilicon")) {
              return getResources().getIdentifier("ic_kirin", "drawable", getPackageName());
          }
          if (vendor.contains("unisoc")) {
              return getResources().getIdentifier("ic_unisoc", "drawable", getPackageName());
          }
          if (vendor.contains("rockchip")) {
              return getResources().getIdentifier("ic_rockchip", "drawable", getPackageName());
          }
          if (vendor.contains("allwinner")) {
              return getResources().getIdentifier("ic_allwinner", "drawable", getPackageName());
          }
          if (vendor.contains("intel")) {
              return getResources().getIdentifier("ic_intel", "drawable", getPackageName());
          }
          if (vendor.contains("amd")) {
              return getResources().getIdentifier("ic_amd", "drawable", getPackageName());
          }
          if (vendor.contains("amlogic")) {
              return getResources().getIdentifier("ic_amlogic", "drawable", getPackageName());
          }
          if (vendor.contains("marvell")) {
              return getResources().getIdentifier("ic_marvell", "drawable", getPackageName());
          }
          if (vendor.contains("realtek")) {
              return getResources().getIdentifier("ic_realtek", "drawable", getPackageName());
          }
          
          // 最后判断CPU名称
          if (cpuName.contains("snapdragon") || cpuName.contains("qualcomm")) {
              return getResources().getIdentifier("ic_snapdragon", "drawable", getPackageName());
          }
          
          // 默认通用CPU图标
          return getResources().getIdentifier("ic_cpu_light", "drawable", getPackageName());
      }
      
      /** v3.10.62：六边形雷达图（环境检测可视化） */
}
