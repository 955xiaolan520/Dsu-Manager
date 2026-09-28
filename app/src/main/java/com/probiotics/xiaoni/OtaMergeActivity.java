package com.probiotics.xiaoni;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import com.topjohnwu.superuser.ipc.RootService;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.view.GravityCompat;

import org.apache.commons.lang3.StringUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class OtaMergeActivity extends BaseActivity {
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
    
    // Root 服务
    private IPrivilegedService privilegedService;
    private final ServiceConnection rootConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            privilegedService = IPrivilegedService.Stub.asInterface(service);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            privilegedService = null;
        }
    };
    
    // WakeLock 防止 CPU 休眠
    private PowerManager.WakeLock wakeLock;

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
                        // 通知栏也立即更新（移除节流，每次都更新）
                        activity.updateNotification(status, progress);
                        lastNotificationTime = System.currentTimeMillis();
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
        // 绑定 Root 服务
        RootService.bind(new Intent(this, PrivilegedRootService.class), rootConnection);
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
        // 解绑 Root 服务
        try {
            RootService.unbind(rootConnection);
        } catch (Exception ignored) {}
        // 注意：不在这里释放 WakeLock，因为任务可能还在后台运行
        // WakeLock 只在任务完成/取消/失败时由 finally 块释放
    }
    
    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
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
                "3. 自动识别 .zip、.bin 或所有 .img 文件\n" +
                "4. 点击「开始合并」等待完成");
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

        // 使用 ScrollView + TextView 方案（完全照搬 PayloadDumperActivity）
        logScroll = new ScrollView(this);
        logScroll.setVerticalScrollBarEnabled(true);
        logScroll.setScrollbarFadingEnabled(false);
        logScroll.setFillViewport(false);
        logScroll.setBackgroundResource(R.drawable.dark_liquid_glass);
        // 阻止父容器拦截触摸事件，允许滚动
        logScroll.setOnTouchListener((view, event) -> {
            ViewParent parent = view.getParent();
            if (parent != null) {
                int action = event.getActionMasked();
                parent.requestDisallowInterceptTouchEvent(action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL);
            }
            return false;
        });
        
        logText = new TextView(this);
        logText.setText("等待开始...");
        logText.setTextSize(14);
        logText.setTextColor(0xE6FFFFFF);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setPadding(dp(8), dp(8), dp(8), dp(8));
        // 不设置 setTextIsSelectable，否则会阻止滚动
        
        logScroll.addView(logText, new ScrollView.LayoutParams(-1, -2));
        
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

        // 获取 WakeLock 防止 CPU 休眠
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "XiaoniOTA:MergeWakeLock");
        wakeLock.acquire(2 * 60 * 60 * 1000L); // 最长持有 2 小时
        
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
                // 检查 payload_dumper 工具是否可用
                try {
                    copyPayloadDumper();
                    MergeSession.appendLog("✓ payload_dumper 工具已加载");
                } catch (Exception e) {
                    MergeSession.appendLog("✗ 错误: " + e.getMessage());
                    MergeSession.appendLog("\n━━━━━━━━━━━━━━━━━━━━");
                    MergeSession.appendLog("合并终止");
                    MergeSession.postStatus("payload_dumper 加载失败", 0);
                    // 强制更新通知栏
                    MergeSession.MAIN.post(() -> {
                        if (MergeSession.ui != null) {
                            MergeSession.ui.updateNotification("payload_dumper 加载失败", 0);
                        }
                    });
                    MergeSession.running = false;
                    if (MergeSession.ui != null) {
                        MergeSession.MAIN.post(() -> {
                            if (MergeSession.ui != null) {
                                MergeSession.ui.releaseWakeLock();
                                MergeSession.ui.applyButtons();
                            }
                        });
                    }
                    return;
                }
                
                File otaDir = new File(OTA_DIR);
                File patchDir = new File(PATCH_DIR);
                
                MergeSession.appendLogWithProgress(0, "初始化环境");
                MergeSession.appendLogWithProgress(5, "查找包文件");
                MergeSession.postStatus("查找包文件", 5);
                
                // 使用 Shell 命令列出文件（绕过 SELinux 限制）
                MergeSession.appendLog("检查 ota 目录: " + OTA_DIR);
                List<String> otaFiles = listFilesViaShell(OTA_DIR);
                MergeSession.appendLog("  文件总数: " + otaFiles.size());
                
                if (otaFiles.size() > 0) {
                    MergeSession.appendLog("  前5个文件:");
                    for (int i = 0; i < Math.min(5, otaFiles.size()); i++) {
                        MergeSession.appendLog("    - " + otaFiles.get(i));
                    }
                }
                
                // 自动识别完整包：zip、bin 或所有 img
                File fullOta = null;
                List<String> otaZips = new ArrayList<>();
                List<String> otaBins = new ArrayList<>();
                List<String> otaImgs = new ArrayList<>();
                
                for (String name : otaFiles) {
                    String lower = name.toLowerCase();
                    if (lower.endsWith(".zip")) otaZips.add(name);
                    else if (lower.endsWith(".bin")) otaBins.add(name);
                    else if (lower.endsWith(".img")) otaImgs.add(name);
                }
                
                MergeSession.appendLog("扫描结果:");
                MergeSession.appendLog("  .zip 文件: " + otaZips.size());
                MergeSession.appendLog("  .bin 文件: " + otaBins.size());
                MergeSession.appendLog("  .img 文件: " + otaImgs.size());
                
                if (otaZips.size() > 0) {
                    fullOta = new File(otaDir, otaZips.get(0));
                    MergeSession.appendLog("✓ 找到完整包 ZIP: " + fullOta.getName());
                } else if (otaBins.size() > 0) {
                    fullOta = new File(otaDir, otaBins.get(0));
                    MergeSession.appendLog("✓ 找到完整包 BIN: " + fullOta.getName());
                } else if (otaImgs.size() > 0) {
                    fullOta = new File(otaDir, otaImgs.get(0)); // 使用第一个 img 作为标识
                    MergeSession.appendLog("✓ 找到 " + otaImgs.size() + " 个 IMG 镜像");
                }
                
                // 自动识别增量包：zip、bin 或所有 img
                File patchZip = null;
                MergeSession.appendLog("检查 patch 目录: " + PATCH_DIR);
                List<String> patchFiles = listFilesViaShell(PATCH_DIR);
                MergeSession.appendLog("  文件总数: " + patchFiles.size());
                
                List<String> patchZips = new ArrayList<>();
                List<String> patchBins = new ArrayList<>();
                List<String> patchImgs = new ArrayList<>();
                
                for (String name : patchFiles) {
                    String lower = name.toLowerCase();
                    if (lower.endsWith(".zip")) patchZips.add(name);
                    else if (lower.endsWith(".bin")) patchBins.add(name);
                    else if (lower.endsWith(".img")) patchImgs.add(name);
                }
                
                if (patchZips.size() > 0) {
                    patchZip = new File(patchDir, patchZips.get(0));
                    MergeSession.appendLog("✓ 找到增量包 ZIP: " + patchZip.getName());
                } else if (patchBins.size() > 0) {
                    patchZip = new File(patchDir, patchBins.get(0));
                    MergeSession.appendLog("✓ 找到增量包 BIN: " + patchZip.getName());
                } else if (patchImgs.size() > 0) {
                    patchZip = new File(patchDir, patchImgs.get(0)); // 使用第一个 img 作为标识
                    MergeSession.appendLog("✓ 找到 " + patchImgs.size() + " 个增量包 IMG");
                }

                if (fullOta == null) {
                    MergeSession.appendLog("✗ 错误: ota 目录未找到 .zip、.bin 或 .img 文件");
                    MergeSession.appendLog("请将文件放到: " + OTA_DIR);
                    MergeSession.postStatus("合并失败: 找不到旧版完整包", 0);
                    return;
                }

                if (patchZip == null) {
                    MergeSession.appendLog("✗ 错误: patch 目录为空，找不到增量包");
                    MergeSession.appendLog("请将增量包（.zip/.bin/.img）放到: " + PATCH_DIR);
                    MergeSession.postStatus("合并失败: 找不到增量包", 0);
                    return;
                }
                
                // 判断完整包类型
                String fullPackageDesc;
                boolean otaAlreadyImages = fullOta.getName().endsWith(".img");
                if (otaAlreadyImages) {
                    fullPackageDesc = otaImgs.size() + " 个镜像文件";
                } else {
                    fullPackageDesc = fullOta.getName();
                }
                
                MergeSession.appendLogWithProgress(10, "准备完整包: " + fullPackageDesc);
                MergeSession.postStatus("准备完整包", 10);
                
                // 步骤1: 准备基础镜像
                File baseImagesDir = new File(WORK_DIR, "base_images");
                deleteDirectory(baseImagesDir);
                baseImagesDir.mkdirs();
                
                File[] baseImages;
                if (otaAlreadyImages) {
                    // ota 目录已经是 img 文件，直接复制
                    MergeSession.appendLogWithProgress(15, "复制已有镜像到 base_images/");
                    // 使用之前通过 Root 服务获取的文件列表
                    if (otaImgs.size() == 0) {
                        MergeSession.appendLog("✗ 错误: ota 目录没有找到 img 文件");
                        MergeSession.postStatus("合并失败", 0);
                        return;
                    }
                    File[] sourceImgs = new File[otaImgs.size()];
                    for (int i = 0; i < otaImgs.size(); i++) {
                        sourceImgs[i] = new File(otaDir, otaImgs.get(i));
                    }
                    copyFilesWithProgress(sourceImgs, baseImagesDir, 15, 45);
                    // 使用 Root 服务列出复制后的文件
                    List<String> copiedFiles = listFilesViaShell(baseImagesDir.getAbsolutePath());
                    List<String> imgFiles = new ArrayList<>();
                    for (String name : copiedFiles) {
                        if (name.toLowerCase().endsWith(".img")) {
                            imgFiles.add(name);
                        }
                    }
                    baseImages = new File[imgFiles.size()];
                    for (int i = 0; i < imgFiles.size(); i++) {
                        baseImages[i] = new File(baseImagesDir, imgFiles.get(i));
                    }
                } else {
                    // 需要从 zip/bin 提取
                    MergeSession.appendLogWithProgress(15, "正在提取完整包...");
                    MergeSession.postStatus("提取完整包", 15);
                    extractFullOtaWithProgress(fullOta, baseImagesDir);
                    baseImages = baseImagesDir.listFiles((dir, name) -> name.endsWith(".img"));
                }
                
                if (baseImages == null || baseImages.length == 0) {
                    MergeSession.appendLog("✗ 错误: 未能获取任何镜像");
                    MergeSession.postStatus("合并失败", 0);
                    return;
                }
                
                long totalSize = 0;
                for (File img : baseImages) {
                    totalSize += img.length();
                }
                MergeSession.appendLogWithProgress(45, String.format("✓ 已准备 %d 个镜像 (%dMB)", baseImages.length, totalSize / 1024 / 1024));

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
                
                // 步骤5: 验证结果（使用 Root 服务列出文件）
                List<String> mergedFileNames = listFilesViaShell(patchOutputDir.getAbsolutePath());
                List<String> mergedImgNames = new ArrayList<>();
                for (String name : mergedFileNames) {
                    if (name.toLowerCase().endsWith(".img")) {
                        mergedImgNames.add(name);
                    }
                }
                if (mergedImgNames.size() == 0) {
                    MergeSession.appendLog("错误: 增量合并失败");
                    MergeSession.postStatus("合并失败", 0);
                    return;
                }
                File[] mergedImages = new File[mergedImgNames.size()];
                for (int i = 0; i < mergedImgNames.size(); i++) {
                    mergedImages[i] = new File(patchOutputDir, mergedImgNames.get(i));
                }
                
                long mergedSize = 0;
                for (File img : mergedImages) {
                    mergedSize += img.length();
                }
                MergeSession.appendLogWithProgress(80, String.format("✓ 增量应用完成 %d 个镜像 (%dMB)", mergedImages.length, mergedSize / 1024 / 1024));
                
                MergeSession.appendLogWithProgress(82, "验证合并结果");
                MergeSession.postStatus("验证合并结果", 82);
                MergeSession.appendLogWithProgress(84, String.format("✓ 合并完成！共 %d 个镜像，总大小 %dMB", mergedImages.length, mergedSize / 1024 / 1024));

                // 步骤6: 移动合并后的镜像到 output 目录
                MergeSession.appendLogWithProgress(85, "整理输出文件...");
                MergeSession.postStatus("整理输出文件", 85);
                
                // 删除 old 目录
                File oldDirToDelete = new File(patchOutputDir, "old");
                if (oldDirToDelete.exists()) {
                    MergeSession.appendLogWithProgress(86, "删除 old 目录");
                    deleteDirectory(oldDirToDelete);
                }
                
                // 将合并后的目录重命名/移动到 output
                String outputDirName = patchZip.getName().replace(".zip", "_merged").replace(".bin", "_merged");
                File finalOutputDir = new File(OUTPUT_DIR, outputDirName);
                
                // 如果目标已存在，先删除
                if (finalOutputDir.exists()) {
                    deleteDirectory(finalOutputDir);
                }
                
                // 移动整个目录
                MergeSession.appendLogWithProgress(90, "移动到 output 目录");
                boolean moved = patchOutputDir.renameTo(finalOutputDir);
                
                if (!moved) {
                    throw new Exception("移动输出目录失败");
                }
                
                MergeSession.appendLogWithProgress(95, "✓ 输出完成");
                MergeSession.postStatus("✓ 合并完成！", 100);
                MergeSession.appendLog("\n━━━━━━━━━━━━━━━━━━━━");
                MergeSession.appendLog("✓ 合并完成！");
                MergeSession.appendLog("输出路径：" + finalOutputDir.getAbsolutePath());
                MergeSession.appendLog(String.format("镜像数量：%d 个", mergedImages.length));
                MergeSession.appendLog(String.format("总大小：%d MB", mergedSize / 1024 / 1024));
                MergeSession.appendLog("\n✓ 所有合并后的 img 文件已保存到 output 目录");

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
                
                // 释放 WakeLock
                if (MergeSession.ui != null) {
                    MergeSession.MAIN.post(() -> {
                        OtaMergeActivity activity = MergeSession.ui;
                        if (activity != null) {
                            activity.releaseWakeLock();
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
            // 使用 su 以 Root 权限执行 payload_dumper（绕过 SELinux）
            pb = new ProcessBuilder(
                "su", "-c",
                dumper.getAbsolutePath() + " " +
                patchFile.getAbsolutePath() + " " +
                "--source-dir " + oldDir.getAbsolutePath() + " " +
                "--out " + outputDir.getAbsolutePath()
            );
        } else {
            throw new Exception("增量包格式错误: " + patchFile.getName());
        }
        
        // 不合并流，分别读取 stdout 和 stderr
        MergeSession.currentProcess = pb.start();

        // 同时读取 stdout 和 stderr
        final int[] patchProgress = {65};
        final int[] partitionCount = {0};
        
        // stdout 读取线程
        Thread stdoutReader = new Thread(() -> {
            try {
                InputStream is = MergeSession.currentProcess.getInputStream();
                StringBuilder lineBuffer = new StringBuilder();
                int c;
                while ((c = is.read()) != -1) {
                    if (c == '\r' || c == '\n') {
                        if (lineBuffer.length() > 0) {
                            String line = lineBuffer.toString();
                            MergeSession.appendLog("[stdout] " + line);
                            
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
                if (lineBuffer.length() > 0) {
                    MergeSession.appendLog("[stdout] " + lineBuffer.toString());
                }
            } catch (Exception e) {
                MergeSession.appendLog("stdout 读取异常: " + e.getMessage());
            }
        });
        
        // stderr 读取线程
        Thread stderrReader = new Thread(() -> {
            try {
                InputStream is = MergeSession.currentProcess.getErrorStream();
                StringBuilder lineBuffer = new StringBuilder();
                int c;
                while ((c = is.read()) != -1) {
                    if (c == '\r' || c == '\n') {
                        if (lineBuffer.length() > 0) {
                            String line = lineBuffer.toString();
                            MergeSession.appendLog("[stderr] " + line);
                            
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
                if (lineBuffer.length() > 0) {
                    MergeSession.appendLog("[stderr] " + lineBuffer.toString());
                }
            } catch (Exception e) {
                MergeSession.appendLog("stderr 读取异常: " + e.getMessage());
            }
        });
        
        stdoutReader.start();
        stderrReader.start();

        // 使用带超时的 waitFor，避免无限阻塞
        boolean finished = MergeSession.currentProcess.waitFor(300, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            MergeSession.appendLog("[ERROR] payload_dumper 执行超时（300秒），强制终止");
            MergeSession.currentProcess.destroyForcibly();
            MergeSession.currentProcess.waitFor();
            throw new Exception("应用增量包超时");
        }
        
        int exitCode = MergeSession.currentProcess.exitValue();
        stdoutReader.join(5000);
        stderrReader.join(5000);
        
        if (exitCode != 0) {
            throw new Exception("应用增量包失败: exit code " + exitCode);
        }
    }

    private File copyPayloadDumper() throws Exception {
        // 直接从 native library 目录获取 payload_dumper
        // 这个目录默认可执行，避免 SELinux 和 noexec 问题
        String nativeLibDir = getApplicationInfo().nativeLibraryDir;
        File dumper = new File(nativeLibDir, "libpayload_dumper.so");
        
        if (!dumper.exists()) {
            throw new Exception(String.format(
                "找不到 payload_dumper 工具\n" +
                "路径: %s\n" +
                "架构: %s\n\n" +
                "请重新安装 APP",
                dumper.getAbsolutePath(),
                android.os.Build.SUPPORTED_ABIS[0]
            ));
        }
        
        if (!dumper.canExecute()) {
            throw new Exception(String.format(
                "payload_dumper 不可执行\n" +
                "路径: %s\n" +
                "架构: %s\n" +
                "文件大小: %d bytes\n\n" +
                "这是一个严重的系统问题，请联系开发者",
                dumper.getAbsolutePath(),
                android.os.Build.SUPPORTED_ABIS[0],
                dumper.length()
            ));
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
        // 使用 Root 服务复制文件（绕过 SELinux 限制）
        if (privilegedService != null) {
            boolean success = privilegedService.copyFile(src.getAbsolutePath(), dest.getAbsolutePath());
            if (!success) {
                throw new Exception("Root 服务复制失败: " + src.getName());
            }
        } else {
            // 回退到普通复制（可能失败）
            try (FileInputStream fis = new FileInputStream(src);
                 FileOutputStream fos = new FileOutputStream(dest)) {
                byte[] buffer = new byte[1024 * 1024]; // 1MB 缓冲区
                int len;
                while ((len = fis.read(buffer)) > 0) {
                    fos.write(buffer, 0, len);
                }
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
        if (privilegedService != null) {
            try {
                privilegedService.deleteFile(dir.getAbsolutePath());
            } catch (Exception e) {
                Log.e("OtaMerge", "deleteDirectory via root failed", e);
            }
        } else {
            // 回退到普通删除
            if (dir.isDirectory()) {
                List<String> fileNames = listFilesViaShell(dir.getAbsolutePath());
                for (String name : fileNames) {
                    deleteDirectory(new File(dir, name));
                }
            }
            dir.delete();
        }
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
        File dir = new File(OUTPUT_DIR);
        
        if (!dir.exists() || !dir.isDirectory()) {
            Toast.makeText(this, "目录不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 使用 Root 服务列出文件
        List<String> fileNames = listFilesViaShell(dir.getAbsolutePath());
        if (fileNames.size() == 0) {
            Toast.makeText(this, "目录为空", Toast.LENGTH_SHORT).show();
            return;
        }
        
        File file = new File(dir, fileNames.get(0));
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
            
            try {
                startActivity(Intent.createChooser(intent, "打开方式"));
            } catch (Exception e) {
                Toast.makeText(this, "没有可用的应用", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "无法访问文件", Toast.LENGTH_SHORT).show();
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
            // 使用 Root 服务列出文件
            List<String> fileNames = listFilesViaShell(OTA_DIR);
            List<String> imgNames = new ArrayList<>();
            for (String name : fileNames) {
                if (name.toLowerCase().endsWith(".img")) {
                    imgNames.add(name);
                }
            }
            
            if (imgNames.size() == 0) {
                throw new Exception("ota 目录没有找到 img 文件");
            }
            
            for (String imgName : imgNames) {
                File img = new File(OTA_DIR, imgName);
                copyFile(img, new File(outputDir, img.getName()));
            }
            return;
        }
        
        // ZIP/BIN: 使用 payload_dumper
        File dumper = copyPayloadDumper();
        
        MergeSession.appendLog("准备执行 payload_dumper");
        MergeSession.appendLog("命令: " + dumper.getAbsolutePath() + " " + fullPackage.getAbsolutePath() + " --out " + outputDir.getAbsolutePath());
        
        ProcessBuilder pb = new ProcessBuilder(
            dumper.getAbsolutePath(),
            fullPackage.getAbsolutePath(),
            "--out", outputDir.getAbsolutePath()
        );
        
        // 设置环境变量，强制 Rust 程序无缓冲输出
        Map<String, String> env = pb.environment();
        env.put("RUST_LOG", "info");
        env.put("RUST_BACKTRACE", "1");
        
        MergeSession.appendLog("启动进程并读取输出...");
        // 不合并流，分别读取 stdout 和 stderr
        MergeSession.currentProcess = pb.start();

        // 同时读取 stdout 和 stderr
        final int[] extractProgress = {15};
        final int[] partitionCount = {0};
        final int[] stdoutLineCount = {0};
        final int[] stderrLineCount = {0};
        
        // stdout 读取线程
        Thread stdoutReader = new Thread(() -> {
            try {
                MergeSession.appendLog("[DEBUG] stdout 读取线程已启动");
                InputStream is = MergeSession.currentProcess.getInputStream();
                StringBuilder lineBuffer = new StringBuilder();
                int c;
                while ((c = is.read()) != -1) {
                    if (c == '\r' || c == '\n') {
                        if (lineBuffer.length() > 0) {
                            String line = lineBuffer.toString();
                            stdoutLineCount[0]++;
                            MergeSession.appendLog("[stdout#" + stdoutLineCount[0] + "] " + line);
                            
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
                if (lineBuffer.length() > 0) {
                    stdoutLineCount[0]++;
                    MergeSession.appendLog("[stdout#" + stdoutLineCount[0] + "] " + lineBuffer.toString());
                }
                MergeSession.appendLog("[DEBUG] stdout 读取线程结束，共 " + stdoutLineCount[0] + " 行");
            } catch (Exception e) {
                MergeSession.appendLog("[ERROR] stdout 读取异常: " + e.getMessage());
            }
        });
        
        // stderr 读取线程
        Thread stderrReader = new Thread(() -> {
            try {
                MergeSession.appendLog("[DEBUG] stderr 读取线程已启动");
                InputStream is = MergeSession.currentProcess.getErrorStream();
                StringBuilder lineBuffer = new StringBuilder();
                int c;
                while ((c = is.read()) != -1) {
                    if (c == '\r' || c == '\n') {
                        if (lineBuffer.length() > 0) {
                            String line = lineBuffer.toString();
                            stderrLineCount[0]++;
                            MergeSession.appendLog("[stderr#" + stderrLineCount[0] + "] " + line);
                            
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
                if (lineBuffer.length() > 0) {
                    stderrLineCount[0]++;
                    MergeSession.appendLog("[stderr#" + stderrLineCount[0] + "] " + lineBuffer.toString());
                }
                MergeSession.appendLog("[DEBUG] stderr 读取线程结束，共 " + stderrLineCount[0] + " 行");
            } catch (Exception e) {
                MergeSession.appendLog("[ERROR] stderr 读取异常: " + e.getMessage());
            }
        });
        
        stdoutReader.start();
        stderrReader.start();
        MergeSession.appendLog("输出读取线程已启动，等待进程结束...");

        // 使用带超时的 waitFor，避免无限阻塞
        boolean finished = MergeSession.currentProcess.waitFor(300, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            MergeSession.appendLog("[ERROR] payload_dumper 执行超时（300秒），强制终止");
            MergeSession.currentProcess.destroyForcibly();
            MergeSession.currentProcess.waitFor();
            throw new Exception("提取完整包超时");
        }
        
        int exitCode = MergeSession.currentProcess.exitValue();
        stdoutReader.join(5000);
        stderrReader.join(5000);
        
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
            
            // 使用 Root 服务列出文件
            List<String> fileNames = listFilesViaShell(sourceDir.getAbsolutePath());
            List<String> imgNames = new ArrayList<>();
            for (String name : fileNames) {
                if (name.toLowerCase().endsWith(".img")) {
                    imgNames.add(name);
                }
            }
            
            if (imgNames.size() > 0) {
                for (int i = 0; i < imgNames.size(); i++) {
                    MergeSession.checkCancelled();
                    File file = new File(sourceDir, imgNames.get(i));
                    int progress = 85 + (11 * (i + 1) / imgNames.size());
                    MergeSession.appendLogWithProgress(progress, String.format("打包 %s (%d/%d)", file.getName(), i + 1, imgNames.size()));
                    
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
        // 格式：[进度%] 状态文本
        String contentText = progress > 0 ? String.format("[%d%%] %s", progress, status) : status;
        
        // 使用系统原生 Notification.Builder（和下载管理器一致）
        android.app.Notification.Builder builder = new android.app.Notification.Builder(this, MergeSession.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("OTA 增量包合并")
            .setContentText(contentText)
            .setOngoing(MergeSession.running && progress < 100)
            .setProgress(100, progress, false);

        // 点击通知跳转到当前 Activity
        android.content.Intent intent = new Intent(this, OtaMergeActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        android.app.PendingIntent pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        builder.setContentIntent(pendingIntent);

        // 使用系统原生 NotificationManager（和下载管理器一致）
        android.app.NotificationManager notificationManager = 
            (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            try {
                notificationManager.notify(MergeSession.NOTIFICATION_ID, builder.build());
            } catch (Exception ignored) { }
        }
    }

    /** 取消通知（任务完成后用户可手动清除，或 APP 退出时清理） */
    private void cancelNotification() {
        android.app.NotificationManager notificationManager = 
            (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.cancel(MergeSession.NOTIFICATION_ID);
        }
    }
    
    /** 使用 Root 服务列出目录中的文件（绕过 SELinux 限制） */
    private List<String> listFilesViaShell(String dirPath) {
        List<String> result = new ArrayList<>();
        try {
            // 使用 Root 服务
            if (privilegedService != null) {
                result = privilegedService.listFiles(dirPath);
            }
        } catch (Exception e) {
            Log.e("OtaMerge", "listFiles via root failed", e);
        }
        return result;
    }
}
