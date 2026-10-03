package com.probiotics.xiaoni;

import android.app.*;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.*;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
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
    private LinearLayout logoCard;
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
            if (rootAuthorized) refreshStatus();
        }
        // 应用窗口透明设置
        boolean isTransparent = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("window_transparent_bg", false);
        applyWindowTransparency(isTransparent);
        bindRootService();
        if (!rootAuthorized) refreshRootStatus();
        if (currentTab == 4) refreshMorePage();
    }

    @Override protected void onPause() {
        super.onPause();
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

        logoCard = new LinearLayout(this);
        logoCard.setOrientation(LinearLayout.VERTICAL);
        logoCard.setPadding(dp(28), dp(22), dp(28), dp(20));
         logoCard.setBackgroundResource(R.drawable.logo_glass_bg);
        logoCard.setClipToOutline(true);
        logoCard.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(28));
            }
        });
         logoCard.setOnTouchListener((view, event) -> {
             if (event.getAction() != MotionEvent.ACTION_UP) return true;
             Haptics.perform(view);
             if (pendingLogoClick != null) {
                 mainHandler.removeCallbacks(pendingLogoClick);
                 pendingLogoClick = null;
                   logoCard.setBackgroundResource(R.drawable.logo_glass_bg);
                  toast(t("已恢复默认背景图", "Default background restored"));
             } else {
                 pendingLogoClick = () -> { pendingLogoClick = null; chooseImage(); };
                 mainHandler.postDelayed(pendingLogoClick, 280);
             }
             return true;
         });
         gsiStatus = text(rootAuthorized ? t("正在读取动态系统状态...", "Reading Dynamic System status...")
                  : t("需要 ROOT 权限", "ROOT access required"), 21, Color.WHITE);
         gsiStatus.setTypeface(null, 1);
         logoCard.addView(gsiStatus, new LinearLayout.LayoutParams(-1, dp(52)));
         TextView hint = text(t("点击卡片更换背景图片", "Tap to change background image"), 12, 0xB8FFFFFF);
         logoCard.addView(hint);
         FrameLayout logoMark = new FrameLayout(this);
         TextView logoDepth = text("小你", 32, 0x66101A44);
         logoDepth.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
         logoDepth.setTypeface(null, 1);
         logoDepth.setTranslationX(dp(4));
         logoDepth.setTranslationY(dp(5));
         logoMark.addView(logoDepth, new FrameLayout.LayoutParams(-1, dp(50)));
         TextView logoExtrusion = text("小你", 32, 0xFF183B91);
         logoExtrusion.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
         logoExtrusion.setTypeface(null, 1);
         logoExtrusion.setTranslationX(dp(2));
         logoExtrusion.setTranslationY(dp(2));
         logoMark.addView(logoExtrusion, new FrameLayout.LayoutParams(-1, dp(50)));
         TextView logoFace = text("小你", 32, Color.WHITE);
         logoFace.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
         logoFace.setTypeface(null, 1);
         logoFace.setShadowLayer(dp(2), 0, dp(1), 0xCC07142E);
         logoMark.addView(logoFace, new FrameLayout.LayoutParams(-1, dp(50)));
         LinearLayout.LayoutParams logoMarkLp = new LinearLayout.LayoutParams(-1, dp(50));
         logoMarkLp.gravity = Gravity.RIGHT;
         logoCard.addView(logoMark, logoMarkLp);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, dp(170));
        cardLp.setMargins(0, dp(14), 0, dp(16));
        cardLp.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(logoCard, cardLp);

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
          detailText = text(t("点击操作后，结果会显示在这里。", "Results will appear here after an action."), 13, Color.rgb(77, 87, 105));
          detailText.setGravity(Gravity.TOP);
          detailText.setPadding(dp(14), dp(10), dp(14), dp(10));
          detailText.setMinHeight(dp(72));
          detailText.setBackgroundResource(R.drawable.liquid_glass_panel);
           LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(-1, -2);
          detailLp.setMargins(0, 0, 0, dp(4));
         content.addView(detailText, detailLp);
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
             sizeButton.setOnClickListener(v -> selectInstallSize(sizeIndex, installSizes[sizeIndex]));
             LinearLayout.LayoutParams sizeLp = new LinearLayout.LayoutParams(0, dp(44), 1);
             if (i > 0) sizeLp.setMargins(dp(5), 0, 0, 0);
             sizeRow.addView(sizeButton, sizeLp);
         }
         installOptionsPanel.addView(sizeRow, new LinearLayout.LayoutParams(-1, dp(44)));
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
          pageHost.addView(homeScroll, homeParams);
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
          addNavigationItem(items, t("首页", "Home"), 0, v -> selectTab(0));
         addNavigationItem(items, t("设置", "Settings"), 1, v -> selectTab(1));
           addNavigationItem(items, t("ROM", "ROM"), 2, v -> selectTab(2));
           addNavigationItem(items, t("OTG", "OTG"), 3, v -> selectTab(3));
           addNavigationItem(items, t("终端", "Terminal"), 4, v -> selectTab(4));
          liquidIndicator = new LiquidGlassIndicator(this);
          liquidIndicator.setElevation(dp(4));
          navigation.addView(liquidIndicator, new FrameLayout.LayoutParams(dp(62), dp(48)));
         navigation.post(() -> moveLiquidIndicator(0, false));
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
           item.setTextColor(tab == 0 ? 0xff17334f : 0xfff8fbff);
          item.setShadowLayer(dp(2), 0, dp(1), tab == 0 ? 0x55ffffff : 0x66233c50);
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

        private void selectTab(int tab) {
             if (tab == currentTab) {
                 if (tab == 0) scrollToTop();
                 if (tab == 4) refreshMorePage();
                return;
            }
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
                next = tab == 0 ? homeScroll : tab == 1 ? buildSettingsPage() : tab == 2 ? buildRomPage() : buildMorePage();
                if (tab != 0) {
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
              // 首页：从左侧滑入 + 3D翻转
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
              // 设置页：从上方落下 + Z轴旋转
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
          } else {
              // 更多页：从右侧弹入 + X轴翻转
              next.setTranslationX(dp(300));
              next.setRotationX(-90f);
              next.setScaleY(0.5f);
              next.animate()
                  .alpha(1f)
                  .translationX(0f)
                  .rotationX(0f)
                  .scaleY(1f)
                  .setDuration(460)
                  .setInterpolator(new android.view.animation.DecelerateInterpolator(1.9f))
                  .start();
          }
          animateNavigation(tab);
           currentTab = tab;
       }

       private void refreshMorePage() {
          if (pageHost == null) return;
          pageHost.removeAllViews();
          ScrollView pageScroll = new ScrollView(this);
          pageScroll.setFillViewport(true);
          pageScroll.addView(buildMorePage());
          pageHost.addView(pageScroll, new FrameLayout.LayoutParams(-1, -1));
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
           page.setBackgroundResource(R.drawable.liquid_backdrop);
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
           TextView subtitle = text(t("小米 / Redmi / POCO 系统更新查询与下载", "Xiaomi / Redmi / POCO update lookup and downloads"), 13, 0xffe5edf7);
            page.addView(subtitle, new LinearLayout.LayoutParams(-1, dp(34)));
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
                                   : RomActivity.class);
                  if (!"extract".equals(vendorId) && !"otamerge".equals(vendorId)) intent.putExtra("vendor", vendorId);
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
          page.setBackgroundResource(R.drawable.liquid_backdrop);
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
          content.setBackgroundResource(R.drawable.liquid_backdrop);

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
          
          TextView title = new TextView(this);
          title.setText(t("📜 输出日志", "📜 Output Log"));
          title.setTextSize(13);  // 缩小到13sp
          title.setTextColor(0xff000000);
          title.setTypeface(null, 1);
          card.addView(title);
          
          flashLogScroll = new ScrollView(this);
          flashLogScroll.setVerticalScrollBarEnabled(true);
          flashLogScroll.setScrollbarFadingEnabled(false);
          flashLogScroll.setFillViewport(false);
          flashLogScroll.setNestedScrollingEnabled(false);  // 禁用嵌套滚动
          // 阻止父容器拦截触摸事件，允许滚动
          flashLogScroll.setOnTouchListener((view, event) -> {
              ViewParent parent = view.getParent();
              if (parent != null) {
                  int action = event.getActionMasked();
                  parent.requestDisallowInterceptTouchEvent(action != android.view.MotionEvent.ACTION_UP && action != android.view.MotionEvent.ACTION_CANCEL);
              }
              return false;
          });
          
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
          if (flashRefreshing) return;
          flashRefreshing = true;
          flashAppendLog("\n正在扫描设备...\n");
          new Thread(() -> {
              java.util.List<OtgAssistantCore.DeviceInfo> devices = OtgAssistantCore.INSTANCE.listUsbDevices(this);
              OtgAssistantCore.ProtocolDeviceStatus protocol = OtgAssistantCore.INSTANCE.detectProtocolDevices(this);
              StringBuilder usbText = new StringBuilder();
              int shown = 0;
              for (OtgAssistantCore.DeviceInfo dev : devices) {
                  if (shown++ >= 8) break;
                  usbText.append(String.format("VID:%04x PID:%04x %s\n", dev.getVendorId(), dev.getProductId(), dev.getDeviceName()));
              }
              final int finalDeviceCount = devices.size();
              final String deviceListText = finalDeviceCount > 0 ? usbText.toString() : t("未找到 USB 设备", "No USB device");
              final String adbStatus = protocol.getAdb();
              final String fbStatus = protocol.getFastboot();
              runOnUiThread(() -> {
                  flashDeviceText.setText(deviceListText);
                  flashProtocolText.setText("ADB: " + adbStatus + "\nFastboot: " + fbStatus);
                  if (finalDeviceCount > 0) {
                      flashAppendLog("发现 " + finalDeviceCount + " 个设备\n");
                  } else {
                      flashAppendLog("未发现设备\n");
                  }
                  flashAppendLog("协议检测：ADB=" + !"未发现设备".equals(adbStatus) + "，Fastboot=" + !"未发现设备".equals(fbStatus) + "\n");
                  flashDeviceConnected = !"未发现设备".equals(adbStatus) || !"未发现设备".equals(fbStatus);
                  flashRefreshing = false;
              });
          }).start();
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
          flashAppendLog("\n$ " + cmd);
          new Thread(() -> {
              String toolPath = cmd.startsWith("fastboot")
                      ? OtgAssistantCore.INSTANCE.getFastbootPath(this)
                      : OtgAssistantCore.INSTANCE.getAdbPath(this);
              OtgAssistantCore.CommandResult result = OtgAssistantCore.INSTANCE.executeCommandDetailed(
                      toolPath, cmd, 30000, line -> {
                          runOnUiThread(() -> flashAppendLog(line));
                          return kotlin.Unit.INSTANCE;
                      });
              runOnUiThread(() -> flashAppendLog("退出码: " + result.getExitCode()));
          }).start();
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
                      String dirPath = "/storage/emulated/0/DsuManager/image";
                      
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
          String outputDir = "/storage/emulated/0/DsuManager/image";
          
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
          Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
          intent.setType("application/octet-stream");
          intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
              "application/octet-stream", "application/img", "image/*", "*/*"
          });
          intent.addCategory(Intent.CATEGORY_OPENABLE);
          startActivityForResult(Intent.createChooser(intent,
              t("选择 .img 镜像文件", "Select .img image")),
              REQUEST_FLASH_IMAGE_FILE);
      }
      
      private void preparePartitionImage(Uri fileUri) {
          String selectedName = displayName(fileUri);
          if (selectedName == null || !selectedName.toLowerCase(Locale.ROOT).endsWith(".img")) {
              Toast.makeText(this, t("请选择 .img 镜像文件", "Please select a .img image"), Toast.LENGTH_SHORT).show();
              return;
          }
          String imagePath = getPath(fileUri, "flash_temp.img");
          
          if (imagePath.isEmpty()) {
              Toast.makeText(this, t("无法获取文件路径", "Cannot get file path"), Toast.LENGTH_SHORT).show();
              return;
          }
          pendingFlashImagePath = imagePath;
          pendingFlashImageName = selectedName;
          partitionAppendLog(t("已选择刷入镜像: ", "Selected flash image: ") + selectedName + "\n" + imagePath + "\n");
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
                      partitionAppendLog(t("分区: ", "Partition: ") + partition.name + "\n");
                      partitionAppendLog(t("镜像: ", "Image: ") + imagePath + "\n\n");
                      
                      partitionAppendLog(t("- 开始刷入 ", "- Start flashing ") + partition.name + t(" 分区\n", " partition\n"));
                      
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
           content.setBackgroundResource(R.drawable.liquid_backdrop);

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
          Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
          intent.addCategory(Intent.CATEGORY_OPENABLE);
          intent.setType("*/*");
          intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/gzip", "application/x-gzip", "application/x-xz", "application/octet-stream"});
          startActivityForResult(intent, PICK_ROOTFS);
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
          
          // 关于入口 - 液态玻璃效果卡片
          LiquidGlassPanel aboutCard = new LiquidGlassPanel(this);
          aboutCard.setGravity(Gravity.CENTER_VERTICAL);
          aboutCard.setPadding(dp(14), dp(6), dp(10), dp(6));
          Button aboutButton = new Button(this);
          aboutButton.setText(t("关于 Dsu 管理器", "About Dsu Manager"));
          aboutButton.setAllCaps(false);
          aboutButton.setTextColor(0xff1a3356);
          aboutButton.setTextSize(15);
          aboutButton.setTypeface(null, 1);
          aboutButton.setBackgroundColor(Color.TRANSPARENT);
          aboutButton.setOnClickListener(v -> {
              Haptics.perform(v);
              Intent intent = new Intent(this, AboutActivity.class);
              startActivity(intent);
              overridePendingTransition(R.anim.flip_in, R.anim.flip_out);
          });
          aboutCard.addView(aboutButton, new LinearLayout.LayoutParams(-1, dp(48)));
          LinearLayout.LayoutParams aboutParams = new LinearLayout.LayoutParams(-1, dp(60));
          aboutParams.setMargins(0, 0, 0, dp(18));
          page.addView(aboutCard, aboutParams);
          
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
          embeddedReleaseNotesScroll = new ScrollView(this);
          embeddedReleaseNotesScroll.setFillViewport(false);
          embeddedReleaseNotesScroll.setVerticalScrollBarEnabled(true);
          embeddedReleaseNotesScroll.setBackgroundResource(R.drawable.liquid_glass_panel);
          embeddedReleaseNotesScroll.setOnTouchListener((view, event) -> {
              ViewParent parent = view.getParent();
              if (parent != null) {
                  int action = event.getActionMasked();
                  parent.requestDisallowInterceptTouchEvent(action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL);
              }
              return false;
          });
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
         english = mode == 2;
         buildUi();
     }

      private LinearLayout buildAboutPage() {
         LinearLayout page = page(t("关于 Dsu 管理器", "About Dsu Manager"));
         TextView about = text(t("Dsu GSI管理器\n\n功能说明\n本应用的 GSI 安装流程参考并使用了 DSU-Sideloader 项目的相关方案。\n\n支持安装 DSU 镜像的 img 无损替换。\n支持 system、system_ext、product、vendor、odm、my_preload 等镜像。\n替换修改后的 img 镜像之后直接开机，无需重新过开机引导。直接开机使用修复 bug 后的 Dsu 系统。\n\n使用安卓系统：\n/system/priv-app/DynamicSystemInstallationService/DynamicSystemInstallationService.apk\n/system/bin/gsi_tool\n/system/bin/gsid\n\n安装功能参考 DSU-Sideloader 项目：\nhttps://github.com/VegaBobo/DSU-Sideloader\n\n特别感谢酷安用户及 GitHub 用户 yangFenTuoZi 开发 Dsu 功能修改 img 无损替换功能。\n如有侵权，请联系作者，我们会及时删除相关内容。\n\n作者：小你可兰\n管理器版本：3.5.8", "Dsu GSI Manager\n\nFeatures\nThe GSI installation flow uses the DSU-Sideloader project approach.\n\nSupports lossless replacement of img files for installed DSU images.\nSupports system, system_ext, product, vendor, odm, my_preload and other images.\nThe device can boot directly after replacing a modified img image without repeating the setup wizard.\n\nAndroid system components:\n/system/priv-app/DynamicSystemInstallationService/DynamicSystemInstallationService.apk\n/system/bin/gsi_tool\n/system/bin/gsid\n\nInstallation reference:\nhttps://github.com/VegaBobo/DSU-Sideloader\n\nSpecial thanks to Coolapk user and GitHub user yangFenTuoZi for developing the Dsu img lossless replacement feature.\nIf any content infringes your rights, please contact the author and it will be removed promptly.\n\nAuthor: Xiaonikelan\nManager version: 3.5.8"), 15, Color.rgb(53, 66, 94));
          about.setText(about.getText().toString().replace("3.5.8", BuildConfig.VERSION_NAME));
          about.setGravity(Gravity.TOP);
         about.setPadding(dp(18), dp(18), dp(18), dp(18));
          about.setBackgroundResource(R.drawable.liquid_glass_panel);
         page.addView(about, new LinearLayout.LayoutParams(-1, -2));
         return page;
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

    private void showAboutDialog() {
           String about = t("Dsu GSI管理器\n\n功能说明\n本应用的 GSI 安装流程参考并使用了 DSU-Sideloader 项目的相关方案。\n\n支持安装 DSU 镜像的 img 无损替换。\n支持 system、system_ext、product、vendor、odm、my_preload 等镜像。\n替换修改后的 img 镜像之后直接开机，无需重新过开机引导。直接开机使用修复 bug 后的 Dsu 系统。\n\n使用安卓系统：\n/system/priv-app/DynamicSystemInstallationService/DynamicSystemInstallationService.apk\n/system/bin/gsi_tool\n/system/bin/gsid\n\n安装功能参考 DSU-Sideloader 项目：\nhttps://github.com/VegaBobo/DSU-Sideloader\n\n特别感谢酷安用户及 GitHub 用户 yangFenTuoZi 开发 Dsu 功能修改 img 无损替换功能。\n如有侵权，请联系作者，我们会及时删除相关内容。\n\n作者：小你可兰\n管理器版本：3.5.8",
                "Dsu GSI Manager\n\nFeatures\nThe GSI installation flow uses the DSU-Sideloader project approach.\n\nSupports lossless replacement of img files for installed DSU images.\nSupports system, system_ext, product, vendor, odm, my_preload and other images.\nThe device can boot directly after replacing a modified img image without repeating the setup wizard.\n\nAndroid system components:\n/system/priv-app/DynamicSystemInstallationService/DynamicSystemInstallationService.apk\n/system/bin/gsi_tool\n/system/bin/gsid\n\nInstallation reference:\nhttps://github.com/VegaBobo/DSU-Sideloader\n\nSpecial thanks to Coolapk user and GitHub user yangFenTuoZi for developing the Dsu img lossless replacement feature.\nIf any content infringes your rights, please contact the author and it will be removed promptly.\n\nAuthor: Xiaonikelan\nManager version: 3.5.8");
        about = about.replace("3.5.8", BuildConfig.VERSION_NAME);
        new AlertDialog.Builder(this)
                .setTitle(t("关于 Dsu 管理器", "About Dsu Manager"))
                .setMessage(about)
                .setPositiveButton(t("确定", "OK"), null)
                .show();
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
                gsiStatus.setText(status);
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
        AlertDialog dialog = new AlertDialog.Builder(this)
                 .setTitle(t("自定义 userdata 容量", "Custom userdata size"))
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
                    if (gb <= 0 || gb > 128) throw new NumberFormatException();
                    dialog.dismiss();
                     userdataSizeBytes = Math.round(gb * 1024d * 1024d * 1024d);
                     pendingSizeLabel = value + " GB";
                     chooseZip();
                } catch (NumberFormatException error) {
                     input.setError(t("请输入 0 到 128 之间的容量", "Enter a size between 0 and 128"));
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
         Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
         i.setType("application/zip");
         i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/octet-stream"});
         i.addCategory(Intent.CATEGORY_OPENABLE);
         startActivityForResult(i, PICK_ZIP);
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
                         // 继续选择文件
                         Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                         i.setType("application/zip");
                         i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/octet-stream"});
                         i.addCategory(Intent.CATEGORY_OPENABLE);
                         startActivityForResult(i, PICK_ZIP);
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
              if (gb <= 0 || gb > 128) throw new NumberFormatException();
              selectedInstallSize = -1;
              pendingSizeLabel = formatCustomSize(gb) + " GB";
              userdataSizeBytes = Math.round(gb * 1024d * 1024d * 1024d);
              customInstallSizeInput.setText(pendingSizeLabel);
               for (Button button : installSizeButtons) { button.setTextColor(Color.rgb(40, 50, 70)); button.setBackgroundResource(R.drawable.liquid_glass_panel); }
          } catch (NumberFormatException error) {
              customInstallSizeInput.setError(t("请输入 0 到 128 之间的容量", "Enter a size between 0 and 128"));
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
     private void chooseImage(){ Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("image/*"); i.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(i,PICK_IMAGE); }
     @Override protected void onActivityResult(int r,int c,Intent d){ super.onActivityResult(r,c,d); if(c!=RESULT_OK||d==null)return; Uri u=d.getData(); if(r==PICK_IMAGE){ String path=getPath(u,"logo.img"); if(!path.isEmpty()){ Bitmap bitmap=android.graphics.BitmapFactory.decodeFile(path); if(bitmap!=null) { logoCard.setBackground(new RoundedCropDrawable(bitmap, dp(28))); logoCard.setClipToOutline(true); } } } else if(r==PICK_ZIP){ pendingInstallZip = u; installedZipName = displayName(u); getPreferences(MODE_PRIVATE).edit().putString("installed_zip_name", installedZipName).apply(); installZipLabel.setText(installedZipName); confirmInstallButton.setEnabled(true); } else if(r==PICK_REPLACEMENT && replacementPartition != null){ replaceImage(u, replacementPartition); } else if(r==PICK_ROOTFS){ Intent intent = new Intent(this, LinuxTerminalActivity.class); intent.putExtra("local_install", true); intent.setData(u); startActivity(intent); } else if(r==PICK_FASTBOOT_IMAGE){ String path=getPath(u,"fastboot.img"); if(!path.isEmpty()){ if(fastbootImagePathInput != null) fastbootImagePathInput.setText(path); else if(fastbootFilePathInput != null) fastbootFilePathInput.setText(path); } } else if(r==REQUEST_FLASH_IMAGE_FILE){ preparePartitionImage(u); } else if(r==REQUEST_OTG_SINGLE_IMAGE){ String path=getPath(u,"otg_single.img"); if(!path.isEmpty() && otgFlashHelper != null){ otgFlashHelper.prepareSingleImage(path); } } else if(r==REQUEST_OTG_FULL_PACKAGE){ String path=getPath(u,"otg_full.zip"); if(!path.isEmpty() && otgFlashHelper != null){ otgFlashHelper.extractAndScanOta(path); } } else if(r==REQUEST_OTG_ADB_PUSH){ String path=getPath(u,"otg_push_file"); if(!path.isEmpty() && otgFlashHelper != null){ otgFlashHelper.prepareAdbPush(path); } } }

     private String displayName(Uri uri){
         try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
             if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
         } catch (Exception ignored) { }
         return uri.getLastPathSegment() == null ? t("未命名 ZIP", "Unnamed ZIP") : uri.getLastPathSegment();
     }
    private String getPath(Uri u,String name){ try { InputStream in=getContentResolver().openInputStream(u); File f=new File(getCacheDir(),name); FileOutputStream out=new FileOutputStream(f); byte[] b=new byte[8192]; int n; while((n=in.read(b))>0)out.write(b,0,n); in.close();out.close();return f.getAbsolutePath(); }catch(Exception e){return "";} }
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
        try (InputStream input = getContentResolver().openInputStream(source);
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
    private long parseSizeBytes(String size){
        try {
            double gigabytes = Double.parseDouble(size.replace("GB", "").trim());
            if (gigabytes > 0 && gigabytes <= 128) {
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
    private String formatBytes(long bytes){
         if (bytes < 0) return t("大小读取失败", "Size unavailable");
         if (bytes == 0) return t("大小未知", "Unknown size");
        if (bytes >= 1024L * 1024L * 1024L) return String.format(java.util.Locale.US, "%.2f GB", bytes / 1073741824d);
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576d);
    }
      private void chooseReplacement(String targetName, String backingImage, String backingSlot, String imagePath){
          replacementPartition = targetName;
          replacementBackingImage = backingImage;
          replacementSlot = backingSlot;
         replacementImagePath = imagePath;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_REPLACEMENT);
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
      private static final class RoundedCropDrawable extends Drawable {
         private final Bitmap bitmap;
         private final float radius;
         private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
         RoundedCropDrawable(Bitmap bitmap, float radius){ this.bitmap = bitmap; this.radius = radius; }
         @Override public void draw(Canvas canvas){
             RectF bounds = new RectF(getBounds());
             float scale = Math.max(bounds.width() / bitmap.getWidth(), bounds.height() / bitmap.getHeight());
             float width = bitmap.getWidth() * scale;
             float height = bitmap.getHeight() * scale;
             float left = bounds.left + (bounds.width() - width) / 2f;
             float top = bounds.top + (bounds.height() - height) / 2f;
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
}
