package com.probiotics.xiaoni;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.view.GravityCompat;

import org.apache.commons.lang3.StringUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class OtaMergeActivity extends Activity {
    private static final String BASE_DIR = "/storage/emulated/0/Download/DsuManager/Input";
    private static final String OTA_DIR = BASE_DIR + "/ota";
    private static final String PATCH_DIR = BASE_DIR + "/patch";
    private static final String OUTPUT_DIR = BASE_DIR + "/output";
    private static final String WORK_DIR = BASE_DIR + "/work";

    private TextView statusText;
    private ProgressBar progressBar;
    private TextView progressText;
    private TextView logText;
    private ScrollView logScroll;
    private Button startButton;
    private Button cancelButton;
    private Button cleanButton;
    private Button refreshButton;

    /**
     * 进程级合并会话：任务状态与 Activity 生命周期解耦。
     * 任务跑在进程级单线程池，离开页面、返回再进入、切到任何界面都会立即同步到最新进度；
     * 只有进程被强杀才丢失实时状态（由 SharedPreferences 快照兜底恢复）。
     */
    private static final class MergeSession {
        static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
        static final Handler MAIN = new Handler(Looper.getMainLooper());
        static final StringBuilder LOG = new StringBuilder();

        static volatile boolean running = false;
        static volatile boolean cancelRequested = false;
        static volatile int progress = 0;
        static volatile String status = "等待开始合并";
        static volatile int lastProgress = -1;
        static volatile Process currentProcess;
        static OtaMergeActivity ui;
        static volatile long lastNotificationTime = 0;

        static final int NOTIFICATION_ID = 1001;
        static final String CHANNEL_ID = "merge_progress";
        static final long NOTIFICATION_THROTTLE_MS = 500;

        /** 后台任务写日志：静态方法，通过 ui 动态更新当前可见 Activity 的控件 */
        static void appendLog(String message) {
            synchronized (LOG) {
                LOG.append(message).append("\n");
            }
            // 立即持久化，移除节流确保返回再进入时零延迟同步
            if (ui != null) {
                MAIN.post(() -> {
                    OtaMergeActivity activity = ui;
                    if (activity != null) {
                        activity.logText.append(message + "\n");
                        activity.logScroll.post(() -> activity.logScroll.fullScroll(View.FOCUS_DOWN));
                        activity.saveState();
                    }
                });
            }
        }

        /** 后台任务写日志+进度：同时更新状态文本与进度条（每次都同步状态框和通知栏） */
        static void appendLogWithProgress(int progress, String message) {
            String logLine = String.format("[%d%%] %s", progress, message);
            synchronized (LOG) {
                LOG.append(logLine).append("\n");
            }
            // 立即持久化
            if (ui != null) {
                MAIN.post(() -> {
                    OtaMergeActivity activity = ui;
                    if (activity != null) {
                        activity.logText.append(logLine + "\n");
                        activity.logScroll.post(() -> activity.logScroll.fullScroll(View.FOCUS_DOWN));
                        activity.saveState();
                    }
                });
            }
            // 移除节流：每次日志都立即更新状态框和通知栏
            lastProgress = progress;
            postStatus(message, progress);
        }

        /** 后台任务更新状态：进度条、状态文本、颜色、持久化、通知栏进度 */
        static void postStatus(String status, int progress) {
            MergeSession.status = status;
            MergeSession.progress = progress;
            if (ui != null) {
                MAIN.post(() -> {
                    OtaMergeActivity activity = ui;
                    if (activity != null) {
                        // 状态框立即更新（无节流）
                        activity.applyStatus(status, progress);
                        activity.saveState();
                        // 通知栏节流更新（500ms 内最多一次，避免系统节流导致不同步）
                        long now = System.currentTimeMillis();
                        if (now - lastNotificationTime >= NOTIFICATION_THROTTLE_MS || progress >= 100) {
                            lastNotificationTime = now;
                            activity.updateNotification(status, progress);
                        }
                    }
                });
            }
        }

        /** 取消检查点：可中断的长循环内抛出，任务尽快收敛退出 */
        static void checkCancelled() throws Exception {
            if (cancelRequested) {
                throw new Exception("已取消合并");
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        initDirectories();
        createNotificationChannel();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 绑定当前界面并立即显示进行到了哪一步
        MergeSession.ui = this;
        // 仅在进程重启（静态会话为空）时从持久化快照恢复
        if (MergeSession.LOG.length() == 0 && MergeSession.progress == 0 && "等待开始合并".equals(MergeSession.status)) {
            restoreFromPrefs();
        }
        syncFromSession();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveState();
        // 离开页面时解绑，停止向不可见 Activity 投递 UI 更新（节省资源）
        if (MergeSession.ui == this) {
            MergeSession.ui = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 只解绑界面；合并任务继续在进程级线程池运行，日志与进度实时写回会话
        if (MergeSession.ui == this) {
            MergeSession.ui = null;
        }
        saveState();
    }

    private void saveState() {
        String log;
        synchronized (MergeSession.LOG) {
            log = MergeSession.LOG.toString();
        }
        getSharedPreferences("OtaMerge", MODE_PRIVATE)
            .edit()
            .putBoolean("isRunning", MergeSession.running)
            .putInt("progress", MergeSession.progress)
            .putString("status", MergeSession.status)
            .putString("log", log)
            .apply();
    }

    private void restoreFromPrefs() {
        android.content.SharedPreferences prefs = getSharedPreferences("OtaMerge", MODE_PRIVATE);
        String log = prefs.getString("log", "");
        if (!log.isEmpty()) {
            synchronized (MergeSession.LOG) {
                MergeSession.LOG.append(log);
            }
        }
        MergeSession.progress = prefs.getInt("progress", 0);
        MergeSession.status = prefs.getString("status", "等待开始合并");
        MergeSession.lastProgress = MergeSession.progress;
    }

    /** 进入页面或点击「刷新状态」时调用：全量同步会话状态到界面。 */
    private void syncFromSession() {
        String log;
        synchronized (MergeSession.LOG) {
            log = MergeSession.LOG.toString();
        }
        logText.setText(log.isEmpty() ? "等待开始..." : log);
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        applyStatus(MergeSession.status, MergeSession.progress);
        applyButtons();
    }

    private void applyStatus(String status, int progress) {
        statusText.setText(status);
        progressBar.setProgress(progress);
        progressText.setText(progress + "%");
        if (progress == 100) {
            statusText.setTextColor(0xFF48BB78);
        } else if (status.contains("失败") || status.contains("✗")) {
            statusText.setTextColor(0xFFF56565);
        } else {
            statusText.setTextColor(0xFF0F1E36);
        }
    }

    private void applyButtons() {
        if (MergeSession.running) {
            startButton.setEnabled(false);
            startButton.setText("合并中...");
            cancelButton.setEnabled(true);
        } else {
            startButton.setEnabled(true);
            startButton.setText(MergeSession.progress == 100 ? "重新合并" : "开始合并");
            cancelButton.setEnabled(false);
        }
    }

    private void buildUi() {
        int statusBarHeight = getStatusBarHeight();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(createXiaomiGradientBackground());
        root.setPadding(0, statusBarHeight + dp(12), 0, 0);
        
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0);
        
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), dp(16));

        // 标题栏（液态玻璃）
        LiquidGlassPanel titlePanel = new LiquidGlassPanel(this, 24.0f);
        titlePanel.setOrientation(LinearLayout.HORIZONTAL);
        titlePanel.setGravity(Gravity.CENTER_VERTICAL);
        titlePanel.setPadding(dp(8), 0, dp(8), 0);
        
        Button backButton = new Button(this);
        backButton.setText("<");
        backButton.setTextSize(24);
        backButton.setTextColor(Color.WHITE);
        backButton.setBackgroundResource(R.drawable.liquid_glass_panel);
        backButton.setPadding(dp(12), 0, dp(12), 0);
        backButton.setOnClickListener(v -> finish());
        titlePanel.addView(backButton, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView title = new TextView(this);
        title.setText("OTA 增量包合并");
        title.setTextSize(20);
        title.setTextColor(0xFF0F1E36);
        title.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2);
        titleParams.weight = 1;
        titleParams.leftMargin = dp(12);
        titlePanel.addView(title, titleParams);

        content.addView(titlePanel, new LinearLayout.LayoutParams(-1, dp(56)));

        TextView subtitle = new TextView(this);
        subtitle.setText("支持 payload.bin 格式 · 小米 / OPPO / vivo 全机型");
        subtitle.setTextSize(13);
        subtitle.setTextColor(0xFF2C3E50);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
        subtitleParams.topMargin = dp(8);
        content.addView(subtitle, subtitleParams);

        // 使用步骤卡片（液态玻璃）
        LiquidGlassPanel stepsPanel = new LiquidGlassPanel(this, 16.0f);
        stepsPanel.setOrientation(LinearLayout.VERTICAL);
        stepsPanel.setPadding(dp(16), dp(14), dp(16), dp(14));
        
        TextView stepsTitle = new TextView(this);
        stepsTitle.setText("📋 使用步骤");
        stepsTitle.setTextSize(15);
        stepsTitle.setTextColor(0xFF0F1E36);
        stepsTitle.setTypeface(null, Typeface.BOLD);
        stepsPanel.addView(stepsTitle);

        TextView stepsContent = new TextView(this);
        stepsContent.setText("1. 旧版完整包 → /Download/DsuManager/Input/ota/\n" +
                "2. 增量包 → /Download/DsuManager/Input/patch/\n" +
                "3. 点击「开始合并」等待完成\n" +
                "4. 合并后镜像 → work/merged_images/");
        stepsContent.setTextSize(13);
        stepsContent.setTextColor(0xFF2C3E50);
        stepsContent.setLineSpacing(dp(4), 1.0f);
        LinearLayout.LayoutParams stepsTextParams = new LinearLayout.LayoutParams(-1, -2);
        stepsTextParams.topMargin = dp(8);
        stepsPanel.addView(stepsContent, stepsTextParams);

        LinearLayout.LayoutParams stepsPanelParams = new LinearLayout.LayoutParams(-1, -2);
        stepsPanelParams.topMargin = dp(12);
        content.addView(stepsPanel, stepsPanelParams);

        // 进度卡片（液态玻璃）
        LiquidGlassPanel progressPanel = new LiquidGlassPanel(this, 16.0f);
        progressPanel.setOrientation(LinearLayout.VERTICAL);
        progressPanel.setPadding(dp(16), dp(14), dp(16), dp(14));

        statusText = new TextView(this);
        statusText.setText("等待开始合并");
        statusText.setTextSize(16);
        statusText.setTextColor(0xFF0F1E36);
        statusText.setTypeface(null, Typeface.BOLD);
        progressPanel.addView(statusText);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        GradientDrawable progressBg = new GradientDrawable();
        progressBg.setCornerRadius(dp(8));
        progressBg.setColor(0x33000000);
        progressBar.setBackground(progressBg);
        GradientDrawable progressDrawable = new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{0xFF0BA5EC, 0xFF3B82F6}
        );
        progressDrawable.setCornerRadius(dp(8));
        progressBar.setProgressDrawable(new ClipDrawable(progressDrawable, Gravity.LEFT, ClipDrawable.HORIZONTAL));
        LinearLayout.LayoutParams progressBarParams = new LinearLayout.LayoutParams(-1, dp(10));
        progressBarParams.topMargin = dp(10);
        progressPanel.addView(progressBar, progressBarParams);

        progressText = new TextView(this);
        progressText.setText("0%");
        progressText.setTextSize(15);
        progressText.setTextColor(0xFF2C3E50);
        progressText.setGravity(GravityCompat.END);
        LinearLayout.LayoutParams progressTextParams = new LinearLayout.LayoutParams(-1, -2);
        progressTextParams.topMargin = dp(6);
        progressPanel.addView(progressText, progressTextParams);

        LinearLayout.LayoutParams progressPanelParams = new LinearLayout.LayoutParams(-1, -2);
        progressPanelParams.topMargin = dp(12);
        content.addView(progressPanel, progressPanelParams);

        // 按钮行1：开始合并 + 取消合并
        LinearLayout buttonRow1 = new LinearLayout(this);
        buttonRow1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams buttonRow1Params = new LinearLayout.LayoutParams(-1, -2);
        buttonRow1Params.topMargin = dp(12);

        startButton = new Button(this);
        startButton.setText("开始合并");
        startButton.setTextSize(16);
        startButton.setTextColor(Color.WHITE);
        startButton.setTypeface(null, Typeface.BOLD);
        startButton.setAllCaps(false);
        startButton.setBackgroundResource(R.drawable.button_teal);
        startButton.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            startMerge();
        });
        LinearLayout.LayoutParams startButtonParams = new LinearLayout.LayoutParams(0, dp(50));
        startButtonParams.weight = 1;
        buttonRow1.addView(startButton, startButtonParams);

        cancelButton = new Button(this);
        cancelButton.setText("取消合并");
        cancelButton.setTextSize(16);
        cancelButton.setTextColor(Color.WHITE);
        cancelButton.setTypeface(null, Typeface.BOLD);
        cancelButton.setAllCaps(false);
        cancelButton.setEnabled(false);
        cancelButton.setBackgroundResource(R.drawable.button_red);
        cancelButton.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            cancelMerge();
        });
        LinearLayout.LayoutParams cancelButtonParams = new LinearLayout.LayoutParams(0, dp(50));
        cancelButtonParams.weight = 1;
        cancelButtonParams.leftMargin = dp(8);
        buttonRow1.addView(cancelButton, cancelButtonParams);

        content.addView(buttonRow1, buttonRow1Params);

        // 按钮行2：清理工作区 + 刷新状态
        LinearLayout buttonRow2 = new LinearLayout(this);
        buttonRow2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams buttonRow2Params = new LinearLayout.LayoutParams(-1, -2);
        buttonRow2Params.topMargin = dp(8);

        cleanButton = new Button(this);
        cleanButton.setText("清理工作区");
        cleanButton.setTextSize(16);
        cleanButton.setTextColor(Color.WHITE);
        cleanButton.setTypeface(null, Typeface.BOLD);
        cleanButton.setAllCaps(false);
        cleanButton.setBackgroundResource(R.drawable.button_purple);
        cleanButton.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            cleanWorkspace();
        });
        LinearLayout.LayoutParams cleanButtonParams = new LinearLayout.LayoutParams(0, dp(50));
        cleanButtonParams.weight = 1;
        buttonRow2.addView(cleanButton, cleanButtonParams);

        refreshButton = new Button(this);
        refreshButton.setText("恢复默认");
        refreshButton.setTextSize(16);
        refreshButton.setTextColor(Color.WHITE);
        refreshButton.setTypeface(null, Typeface.BOLD);
        refreshButton.setAllCaps(false);
        refreshButton.setBackgroundResource(R.drawable.button_orange);
        refreshButton.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            resetUi();
        });
        LinearLayout.LayoutParams refreshButtonParams = new LinearLayout.LayoutParams(0, dp(50));
        refreshButtonParams.weight = 1;
        refreshButtonParams.leftMargin = dp(8);
        buttonRow2.addView(refreshButton, refreshButtonParams);

        content.addView(buttonRow2, buttonRow2Params);

        // 按钮行3：打开位置 + 复制日志
        LinearLayout buttonRow3 = new LinearLayout(this);
        buttonRow3.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams buttonRow3Params = new LinearLayout.LayoutParams(-1, -2);
        buttonRow3Params.topMargin = dp(8);

        Button openButton = new Button(this);
        openButton.setText("打开文件所在位置");
        openButton.setTextSize(14);
        openButton.setTextColor(Color.WHITE);
        openButton.setTypeface(null, Typeface.BOLD);
        openButton.setAllCaps(false);
        openButton.setBackgroundResource(R.drawable.button_blue);
        openButton.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            openFolder();
        });
        LinearLayout.LayoutParams openButtonParams = new LinearLayout.LayoutParams(0, dp(50));
        openButtonParams.weight = 1;
        buttonRow3.addView(openButton, openButtonParams);

        Button copyButton = new Button(this);
        copyButton.setText("复制全部日志");
        copyButton.setTextSize(14);
        copyButton.setTextColor(Color.WHITE);
        copyButton.setTypeface(null, Typeface.BOLD);
        copyButton.setAllCaps(false);
        copyButton.setBackgroundResource(R.drawable.button_blue);
        copyButton.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            copyLog();
        });
        LinearLayout.LayoutParams copyButtonParams = new LinearLayout.LayoutParams(0, dp(50));
        copyButtonParams.weight = 1;
        copyButtonParams.leftMargin = dp(8);
        buttonRow3.addView(copyButton, copyButtonParams);

        content.addView(buttonRow3, buttonRow3Params);

        // 日志卡片（液态玻璃）
        LiquidGlassPanel logPanel = new LiquidGlassPanel(this, 16.0f);
        logPanel.setOrientation(LinearLayout.VERTICAL);
        logPanel.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView logTitle = new TextView(this);
        logTitle.setText("📝 合并日志");
        logTitle.setTextSize(14);
        logTitle.setTextColor(0xFF0BA5EC);
        logTitle.setTypeface(null, Typeface.BOLD);
        logPanel.addView(logTitle);

        // 外层垂直滚动
        logScroll = new ScrollView(this);
        logScroll.setVerticalScrollBarEnabled(true);
        logScroll.setScrollbarFadingEnabled(false);
        logScroll.setBackgroundResource(R.drawable.dark_liquid_glass);

        // 内层水平滚动
        HorizontalScrollView logScrollHorizontal = new HorizontalScrollView(this);
        logScrollHorizontal.setHorizontalScrollBarEnabled(true);
        logScrollHorizontal.setScrollbarFadingEnabled(false);

        // TextView：纯滚动查看，不启用文本选择
        logText = new TextView(this);
        logText.setText("等待开始...");
        logText.setTextSize(12);
        logText.setTextColor(0xE6FFFFFF);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setPadding(dp(10), dp(10), dp(10), dp(10));
        logText.setHorizontallyScrolling(true);
        
        logScrollHorizontal.addView(logText, new HorizontalScrollView.LayoutParams(-2, -2));
        logScroll.addView(logScrollHorizontal, new ScrollView.LayoutParams(-1, -2));
        
        LinearLayout.LayoutParams logScrollParams = new LinearLayout.LayoutParams(-1, dp(400));
        logScrollParams.topMargin = dp(8);
        logPanel.addView(logScroll, logScrollParams);

        LinearLayout.LayoutParams logPanelParams = new LinearLayout.LayoutParams(-1, -2);
        logPanelParams.topMargin = dp(12);
        content.addView(logPanel, logPanelParams);

        scroll.addView(content);
        root.addView(scroll);
        setContentView(root);
    }

    private Drawable createXiaomiGradientBackground() {
        return new LayerDrawable(new Drawable[]{
            createRadialGradient(0xFFB8D4F8, 0, 0.5f, 0.0f),
            createRadialGradient(0xFF98C1E9, 0, 0.25f, 0.25f),
            createRadialGradient(0xFFA8CBE9, 0, 0.75f, 0.25f),
            createLinearGradient(0xFFC8D8E8, 0xFF697588, true),
            createRadialGradient(0xFF5A7A94, 0, 0.5f, 0.8f)
        });
    }

    private GradientDrawable createLinearGradient(int startColor, int endColor, boolean vertical) {
        GradientDrawable drawable = new GradientDrawable(
            vertical ? GradientDrawable.Orientation.TOP_BOTTOM : GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{startColor, endColor}
        );
        drawable.setDither(true);
        return drawable;
    }

    private GradientDrawable createRadialGradient(int centerColor, int edgeColor, float centerX, float centerY) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RADIAL_GRADIENT);
        drawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        drawable.setColors(new int[]{centerColor, edgeColor});
        drawable.setGradientCenter(centerX, centerY);
        drawable.setGradientRadius(1000);
        return drawable;
    }

    private int dp(int dp) {
        return (int) ((dp * getResources().getDisplayMetrics().density) + 0.5f);
    }

    private int getStatusBarHeight() {
        int identifier = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (identifier > 0) {
            return getResources().getDimensionPixelSize(identifier);
        }
        return 0;
    }

    private void initDirectories() {
        new File(OTA_DIR).mkdirs();
        new File(PATCH_DIR).mkdirs();
        new File(OUTPUT_DIR).mkdirs();
        new File(WORK_DIR).mkdirs();
    }

    private void startMerge() {
        if (MergeSession.running) return;

        MergeSession.running = true;
        MergeSession.cancelRequested = false;
        MergeSession.lastProgress = -1;
        MergeSession.progress = 0;
        MergeSession.status = "初始化环境";
        synchronized (MergeSession.LOG) {
            MergeSession.LOG.setLength(0);
        }
        logText.setText("");
        applyButtons();
        saveState();
        updateNotification("准备开始合并", 0);

        MergeSession.EXECUTOR.execute(() -> {
            try {
                File otaDir = new File(OTA_DIR);
                File patchDir = new File(PATCH_DIR);
                
                MergeSession.appendLogWithProgress(0, "初始化环境");
                MergeSession.appendLogWithProgress(5, "查找包文件");
                MergeSession.postStatus("查找包文件", 5);
                
                File[] otaFiles = otaDir.listFiles((dir, name) -> 
                    name.endsWith(".zip") || name.endsWith(".bin") || name.endsWith(".img"));
                File[] patchFiles = patchDir.listFiles((dir, name) -> 
                    name.endsWith(".zip") || name.endsWith(".bin") || name.endsWith(".img"));

                if (otaFiles == null || otaFiles.length == 0) {
                    MergeSession.appendLog("错误: ota 目录为空");
                    MergeSession.postStatus("合并失败: 找不到旧版完整包", 0);
                    return;
                }

                if (patchFiles == null || patchFiles.length == 0) {
                    MergeSession.appendLog("错误: patch 目录为空");
                    MergeSession.postStatus("合并失败: 找不到增量包", 0);
                    return;
                }

                File fullOta = otaFiles[0];
                File patchZip = patchFiles[0];
                
                // 判断完整包类型
                String fullPackageDesc;
                if (fullOta.getName().endsWith(".img")) {
                    File[] allImgs = otaDir.listFiles((dir, name) -> name.endsWith(".img"));
                    fullPackageDesc = allImgs != null ? allImgs.length + " 个镜像文件" : "镜像文件";
                } else {
                    fullPackageDesc = fullOta.getName();
                }
                
                MergeSession.appendLogWithProgress(10, "提取完整包: " + fullPackageDesc);
                MergeSession.appendLogWithProgress(11, "输出到: base_images/");
                MergeSession.appendLogWithProgress(15, "正在提取完整包...");
                MergeSession.postStatus("提取完整包", 15);
                
                // 步骤1: 提取完整包
                File baseImagesDir = new File(WORK_DIR, "base_images");
                deleteDirectory(baseImagesDir);
                baseImagesDir.mkdirs();
                
                extractFullOtaWithProgress(fullOta, baseImagesDir);
                
                File[] baseImages = baseImagesDir.listFiles((dir, name) -> name.endsWith(".img"));
                if (baseImages == null || baseImages.length == 0) {
                    MergeSession.appendLog("错误: 未能提取任何镜像");
                    MergeSession.postStatus("合并失败", 0);
                    return;
                }
                
                long totalSize = 0;
                for (File img : baseImages) {
                    totalSize += img.length();
                }
                MergeSession.appendLogWithProgress(45, String.format("✓ 已提取 %d 个镜像 (%dMB)", baseImages.length, totalSize / 1024 / 1024));

                // 步骤2: 创建增量包输出目录
                MergeSession.appendLogWithProgress(50, "准备增量合并环境");
                MergeSession.postStatus("准备增量合并环境", 50);
                
                String patchName = patchZip.getName().replaceAll("\\.(zip|bin)$", "");
                File patchOutputDir = new File(WORK_DIR, patchName);
                deleteDirectory(patchOutputDir);
                patchOutputDir.mkdirs();
                
                // 步骤3: 移动旧镜像（不需要复制，直接移动节省时间）
                File oldDir = new File(patchOutputDir, "old");
                oldDir.mkdirs();
                
                MergeSession.appendLogWithProgress(51, "移动基础镜像到 " + patchName + "/old/");
                moveFilesWithProgress(baseImages, oldDir, 51, 59);
                MergeSession.appendLogWithProgress(59, "✓ 基础镜像已移动");
                
                // 步骤4: 应用增量包
                MergeSession.appendLogWithProgress(60, "应用增量包: " + patchZip.getName());
                MergeSession.appendLogWithProgress(61, "输出到: " + patchName + "/");
                MergeSession.appendLogWithProgress(65, "正在应用增量包...");
                MergeSession.postStatus("应用增量包", 65);
                
                applyIncrementalPatch(patchZip, oldDir, patchOutputDir);
                
                // 步骤5: 验证结果
                File[] mergedImages = patchOutputDir.listFiles((dir, name) -> name.endsWith(".img"));
                if (mergedImages == null || mergedImages.length == 0) {
                    MergeSession.appendLog("错误: 增量合并失败");
                    MergeSession.postStatus("合并失败", 0);
                    return;
                }
                
                long mergedSize = 0;
                for (File img : mergedImages) {
                    mergedSize += img.length();
                }
                MergeSession.appendLogWithProgress(80, String.format("✓ 增量应用完成 %d 个镜像 (%dMB)", mergedImages.length, mergedSize / 1024 / 1024));
                
                MergeSession.appendLogWithProgress(82, "验证合并结果");
                MergeSession.postStatus("验证合并结果", 82);
                MergeSession.appendLogWithProgress(84, String.format("✓ 合并完成！共 %d 个镜像，总大小 %dMB", mergedImages.length, mergedSize / 1024 / 1024));

                // 步骤6: 打包
                MergeSession.appendLogWithProgress(85, "正在打包 ZIP（仅存储模式）...");
                MergeSession.postStatus("打包 ZIP", 85);
                
                String outputName = patchZip.getName().replace(".zip", "_merged.zip").replace(".bin", "_merged.zip");
                File outputFile = new File(OUTPUT_DIR, outputName);
                packImagesWithProgress(patchOutputDir, outputFile, mergedImages.length);

                MergeSession.postStatus("✓ 合并完成！", 100);
                MergeSession.appendLog("\n━━━━━━━━━━━━━━━━━━━━");
                MergeSession.appendLog("✓ 合并完成！");
                MergeSession.appendLog("输出路径：" + outputFile.getAbsolutePath());
                MergeSession.appendLog(String.format("文件大小：%d MB", outputFile.length() / 1024 / 1024));
                MergeSession.appendLog("\n✓ 所有 img 仅存储 ZIP 已完成，在 output 文件夹");

            } catch (Exception e) {
                e.printStackTrace();
                if (MergeSession.cancelRequested) {
                    // 用户主动取消：cancelMerge 已输出日志，这里只收敛状态
                    MergeSession.postStatus("已取消", MergeSession.progress);
                } else {
                    MergeSession.appendLog("\n━━━━━━━━━━━━━━━━━━━━");
                    MergeSession.appendLog("✗ 错误: " + e.getMessage());
                    MergeSession.postStatus("✗ 合并失败", MergeSession.progress);
                }
            } finally {
                MergeSession.running = false;
                saveState();
                if (MergeSession.ui != null) {
                    MergeSession.MAIN.post(() -> {
                        OtaMergeActivity activity = MergeSession.ui;
                        if (activity != null) {
                            activity.applyButtons();
                            // 更新通知为最终状态（完成/取消/失败），允许用户清除
                            activity.updateNotification(MergeSession.status, MergeSession.progress);
                        }
                    });
                }
            }
        });
    }

    private void applyIncrementalPatch(File patchFile, File oldDir, File outputDir) throws Exception {
        File dumper = copyPayloadDumper();
        
        ProcessBuilder pb;
        if (patchFile.getName().endsWith(".zip") || patchFile.getName().endsWith(".bin")) {
            // payload_dumper 会读取 patch.zip，从 oldDir 找旧镜像，输出到 outputDir
            pb = new ProcessBuilder(
                dumper.getAbsolutePath(),
                patchFile.getAbsolutePath(),
                "--source-dir", oldDir.getAbsolutePath(),
                "--out", outputDir.getAbsolutePath()
            );
        } else {
            throw new Exception("增量包格式错误: " + patchFile.getName());
        }
        
        pb.redirectErrorStream(true);
        MergeSession.currentProcess = pb.start();

        // 读取输出：逐字符读取，处理 \r 和 \n
        final int[] patchProgress = {65};
        final int[] partitionCount = {0};
        Thread outputReader = new Thread(() -> {
            try {
                InputStream is = MergeSession.currentProcess.getInputStream();
                StringBuilder lineBuffer = new StringBuilder();
                int c;
                while ((c = is.read()) != -1) {
                    if (c == '\r' || c == '\n') {
                        if (lineBuffer.length() > 0) {
                            String line = lineBuffer.toString();
                            MergeSession.appendLog(line);
                            
                            // 解析关键信息更新进度
                            if (line.contains("- Processing file:")) {
                                partitionCount[0]++;
                                patchProgress[0] = Math.min(79, 65 + partitionCount[0] / 2);
                                MergeSession.postStatus("应用增量 " + partitionCount[0], patchProgress[0]);
                            }
                            
                            lineBuffer.setLength(0);
                        }
                    } else {
                        lineBuffer.append((char) c);
                    }
                }
                // 处理最后一行
                if (lineBuffer.length() > 0) {
                    MergeSession.appendLog(lineBuffer.toString());
                }
            } catch (Exception e) {
                MergeSession.appendLog("读取输出异常: " + e.getMessage());
            }
        });
        outputReader.start();

        int exitCode = MergeSession.currentProcess.waitFor();
        outputReader.join(5000);
        
        if (exitCode != 0) {
            throw new Exception("应用增量包失败: exit code " + exitCode);
        }
    }

    private File copyPayloadDumper() throws Exception {
        File dumper = new File(getFilesDir(), "payload_dumper");
        if (!dumper.exists()) {
            String arch = android.os.Build.SUPPORTED_ABIS[0];
            String assetName = arch.contains("arm64") || arch.contains("aarch64") ? 
                "payload_dumper_arm64" : "payload_dumper";
            
            try (BufferedInputStream in = new BufferedInputStream(getAssets().open("otatools/" + assetName));
                 BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(dumper))) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) > 0) {
                    out.write(buffer, 0, len);
                }
            }
        }
        
        // 每次都检查并设置执行权限（某些系统可能权限丢失）
        if (!dumper.canExecute()) {
            boolean success = dumper.setExecutable(true, false);
            if (!success || !dumper.canExecute()) {
                // 如果 Java API 失败，尝试用 chmod 命令
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"chmod", "755", dumper.getAbsolutePath()});
                    p.waitFor();
                } catch (Exception e) {
                    throw new Exception("无法设置 payload_dumper 执行权限: " + e.getMessage());
                }
                
                // 再次检查
                if (!dumper.canExecute()) {
                    throw new Exception("payload_dumper 无法获得执行权限，请检查系统权限设置");
                }
            }
        }
        
        return dumper;
    }

    private long calculateCRC32(File file) throws Exception {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                crc.update(buffer, 0, len);
            }
        }
        return crc.getValue();
    }

    private void copyFile(File src, File dest) throws Exception {
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dest)) {
            byte[] buffer = new byte[1024 * 1024]; // 1MB 缓冲区，提升复制速度
            int len;
            while ((len = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
        }
    }

    private void cancelMerge() {
        MergeSession.cancelRequested = true;
        Process process = MergeSession.currentProcess;
        if (process != null) {
            process.destroy();
            MergeSession.appendLog("\n━━━━━━━━━━━━━━━━━━━━");
            MergeSession.appendLog("已取消合并");
        }
        MergeSession.running = false;
        saveState();
        applyButtons();
        applyStatus("已取消", MergeSession.progress);
    }

    private void cleanWorkspace() {
        new AlertDialog.Builder(this)
            .setTitle("清理工作区")
            .setMessage("确定要清理 work/ 目录吗？\n\n将删除：\n- 基础镜像 (base_images/)\n- 旧镜像副本 (old/)\n- 所有合并后的镜像文件\n\n注意：output/ 目录的 ZIP 文件不会被删除")
            .setNegativeButton("取消", null)
            .setPositiveButton("确定清理", (dialog, which) -> {
                MergeSession.EXECUTOR.execute(() -> {
                    try {
                        File workDir = new File(WORK_DIR);
                        if (workDir.exists()) {
                            deleteDirectory(workDir);
                            workDir.mkdirs();
                        }
                        MergeSession.appendLog("\n━━━━━━━━━━━━━━━━━━━━");
                        MergeSession.appendLog("✓ 工作区已清理");
                        MergeSession.progress = 0;
                        MergeSession.status = "等待开始合并";
                        MergeSession.lastProgress = -1;
                        saveState();
                        if (MergeSession.ui != null) {
                            MergeSession.MAIN.post(() -> {
                                OtaMergeActivity activity = MergeSession.ui;
                                if (activity != null && !activity.isDestroyed() && !activity.isFinishing()) {
                                    activity.applyStatus("等待开始合并", 0);
                                    new AlertDialog.Builder(activity).setTitle("清理完成").setMessage("work/ 目录已清空").setPositiveButton("确定", null).show();
                                }
                            });
                        }
                    } catch (Exception e) {
                        MergeSession.appendLog("\n✗ 清理失败：" + e.getMessage());
                    }
                });
            })
            .show();
    }

    private void deleteDirectory(File dir) {
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteDirectory(file);
                }
            }
        }
        dir.delete();
    }

    private void resetUi() {
        // 清空静态状态，避免 onResume 同步回来
        synchronized (MergeSession.LOG) {
            MergeSession.LOG.setLength(0);
        }
        MergeSession.status = "等待开始合并";
        MergeSession.progress = 0;
        
        // 恢复界面到默认状态
        logText.setText("等待开始...");
        progressBar.setProgress(0);
        progressText.setText("0%");
        statusText.setText("等待开始合并");
        statusText.setTextColor(0xFF0F1E36);
        startButton.setEnabled(true);
        startButton.setText("开始合并");
        cancelButton.setEnabled(false);
        
        // 清空持久化状态
        saveState();
    }

    private void openFolder() {
        Uri uri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:" + new File(WORK_DIR + "/merged_images").getAbsolutePath().replace("/storage/emulated/0/", "")
        );
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "vnd.android.document/directory");
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception e) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("path", WORK_DIR + "/merged_images"));
            Toast.makeText(this, "已复制路径: " + WORK_DIR + "/merged_images", Toast.LENGTH_LONG).show();
        }
    }

    private void copyLog() {
        String log = logText.getText().toString();
        if (log.isEmpty()) {
            Toast.makeText(this, "日志为空", Toast.LENGTH_SHORT).show();
        } else {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("merge_log", log));
            Toast.makeText(this, "已复制全部日志", Toast.LENGTH_SHORT).show();
        }
    }

    private void extractFullOtaWithProgress(File fullPackage, File outputDir) throws Exception {
        if (fullPackage.getName().endsWith(".img")) {
            File otaDir = new File(OTA_DIR);
            File[] allImgs = otaDir.listFiles((dir, name) -> name.endsWith(".img"));
            
            if (allImgs == null || allImgs.length == 0) {
                throw new Exception("ota 目录没有找到 img 文件");
            }
            
            for (File img : allImgs) {
                copyFile(img, new File(outputDir, img.getName()));
            }
            return;
        }
        
        // ZIP/BIN: 使用 payload_dumper
        File dumper = copyPayloadDumper();
        ProcessBuilder pb = new ProcessBuilder(
            dumper.getAbsolutePath(),
            fullPackage.getAbsolutePath(),
            "--out", outputDir.getAbsolutePath()
        );
        
        pb.redirectErrorStream(true);
        MergeSession.currentProcess = pb.start();

        // 读取输出：逐字符读取，处理 \r 和 \n
        final int[] extractProgress = {15};
        final int[] partitionCount = {0};
        Thread outputReader = new Thread(() -> {
            try {
                InputStream is = MergeSession.currentProcess.getInputStream();
                StringBuilder lineBuffer = new StringBuilder();
                int c;
                while ((c = is.read()) != -1) {
                    if (c == '\r' || c == '\n') {
                        if (lineBuffer.length() > 0) {
                            String line = lineBuffer.toString();
                            MergeSession.appendLog(line);
                            
                            // 解析关键信息更新进度
                            if (line.contains("- Processing file:")) {
                                partitionCount[0]++;
                                extractProgress[0] = Math.min(44, 15 + partitionCount[0]);
                                MergeSession.postStatus("提取分区 " + partitionCount[0], extractProgress[0]);
                            } else if (line.contains("- Found") && line.contains("partitions to extract")) {
                                MergeSession.appendLog("开始提取...");
                            }
                            
                            lineBuffer.setLength(0);
                        }
                    } else {
                        lineBuffer.append((char) c);
                    }
                }
                // 处理最后一行
                if (lineBuffer.length() > 0) {
                    MergeSession.appendLog(lineBuffer.toString());
                }
            } catch (Exception e) {
                MergeSession.appendLog("读取输出异常: " + e.getMessage());
            }
        });
        outputReader.start();

        int exitCode = MergeSession.currentProcess.waitFor();
        outputReader.join(5000);
        
        if (exitCode != 0) {
            throw new Exception("提取完整包失败: exit code " + exitCode);
        }
    }

    private void moveFilesWithProgress(File[] files, File destDir, int startProgress, int endProgress) throws Exception {
        int total = files.length;
        for (int i = 0; i < total; i++) {
            MergeSession.checkCancelled();
            File src = files[i];
            File dest = new File(destDir, src.getName());
            int progress = startProgress + (endProgress - startProgress) * i / total;
            
            // 每个文件都输出日志
            MergeSession.appendLogWithProgress(progress, "复制 " + src.getName());
            
            // 移动文件（重命名）
            if (!src.renameTo(dest)) {
                // 如果移动失败（可能跨分区），则复制后删除
                copyFile(src, dest);
                src.delete();
            }
        }
    }

    private void copyFilesWithProgress(File[] files, File destDir, int startProgress, int endProgress) throws Exception {
        int total = files.length;
        for (int i = 0; i < total; i++) {
            MergeSession.checkCancelled();
            File src = files[i];
            int progress = startProgress + (endProgress - startProgress) * i / total;
            MergeSession.appendLogWithProgress(progress, "复制 " + src.getName());
            copyFile(src, new File(destDir, src.getName()));
        }
    }

    private void packImagesWithProgress(File sourceDir, File outputFile, int totalFiles) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outputFile))) {
            zos.setLevel(ZipOutputStream.STORED);
            
            File[] files = sourceDir.listFiles((dir, name) -> name.endsWith(".img"));
            if (files != null) {
                for (int i = 0; i < files.length; i++) {
                    MergeSession.checkCancelled();
                    File file = files[i];
                    int progress = 85 + (11 * (i + 1) / files.length);
                    MergeSession.appendLogWithProgress(progress, String.format("打包 %s (%d/%d)", file.getName(), i + 1, files.length));
                    
                    ZipEntry entry = new ZipEntry(file.getName());
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(file.length());
                    entry.setCrc(calculateCRC32(file));
                    zos.putNextEntry(entry);
                    
                    try (FileInputStream fis = new FileInputStream(file)) {
                        byte[] buffer = new byte[1024 * 1024];
                        int len;
                        while ((len = fis.read(buffer)) > 0) {
                            zos.write(buffer, 0, len);
                        }
                    }
                    zos.closeEntry();
                }
            }
        }
    }

    /** 创建通知渠道（Android 8+ 必需） */
    private void createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            CharSequence name = "合并进度";
            String description = "显示 OTA 增量包合并进度";
            int importance = android.app.NotificationManager.IMPORTANCE_DEFAULT;
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                MergeSession.CHANNEL_ID, name, importance);
            channel.setDescription(description);
            android.app.NotificationManager notificationManager = getSystemService(android.app.NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    /** 更新通知栏进度（每次状态变化时调用） */
    private void updateNotification(String status, int progress) {
        androidx.core.app.NotificationCompat.Builder builder = new androidx.core.app.NotificationCompat.Builder(this, MergeSession.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("OTA 增量包合并")
            .setContentText(status)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(MergeSession.running && progress < 100)
            .setProgress(100, progress, false);

        // 点击通知跳转到当前 Activity
        android.content.Intent intent = new Intent(this, OtaMergeActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        android.app.PendingIntent pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        builder.setContentIntent(pendingIntent);

        androidx.core.app.NotificationManagerCompat notificationManager = 
            androidx.core.app.NotificationManagerCompat.from(this);
        notificationManager.notify(MergeSession.NOTIFICATION_ID, builder.build());
    }

    /** 取消通知（任务完成后用户可手动清除，或 APP 退出时清理） */
    private void cancelNotification() {
        androidx.core.app.NotificationManagerCompat notificationManager = 
            androidx.core.app.NotificationManagerCompat.from(this);
        notificationManager.cancel(MergeSession.NOTIFICATION_ID);
    }
}
