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
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
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

    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private StringBuilder logBuilder = new StringBuilder();
    private boolean isRunning = false;
    private Process currentProcess;
    private int lastProgress = -1;  // 记录上次的进度，避免重复更新

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        initDirectories();
        restoreState(); // 在 UI 构建后恢复状态
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 恢复状态和日志
        android.content.SharedPreferences prefs = getSharedPreferences("OtaMerge", MODE_PRIVATE);
        int progress = prefs.getInt("progress", 0);
        String status = prefs.getString("status", "等待开始合并");
        String savedLog = prefs.getString("log", "");
        boolean wasRunning = prefs.getBoolean("isRunning", false);
        
        // 恢复进度条和状态
        progressBar.setProgress(progress);
        progressText.setText(progress + "%");
        statusText.setText(status);
        
        // 恢复日志（每次都恢复，因为 UI 是重新创建的）
        if (!savedLog.isEmpty()) {
            logBuilder = new StringBuilder(savedLog);
            logText.setText(savedLog);
        }
        
        // 恢复按钮状态
        if (wasRunning && progress < 100) {
            isRunning = true;
            startButton.setEnabled(false);
            startButton.setText("合并中...");
            cancelButton.setEnabled(true);
        } else {
            isRunning = false;
            startButton.setEnabled(true);
            startButton.setText(progress == 100 ? "重新合并" : "开始合并");
            cancelButton.setEnabled(false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveState();
    }

    private void saveState() {
        getSharedPreferences("OtaMerge", MODE_PRIVATE)
            .edit()
            .putBoolean("isRunning", isRunning)
            .putInt("progress", progressBar.getProgress())
            .putString("status", statusText.getText().toString())
            .putString("log", logBuilder.toString())
            .apply();
    }

    private void restoreState() {
        android.content.SharedPreferences prefs = getSharedPreferences("OtaMerge", MODE_PRIVATE);
        boolean wasRunning = prefs.getBoolean("isRunning", false);
        int progress = prefs.getInt("progress", 0);
        String status = prefs.getString("status", "等待开始合并");
        String log = prefs.getString("log", "");
        
        // 恢复显示
        progressBar.setProgress(progress);
        progressText.setText(progress + "%");
        statusText.setText(status);
        
        // 只在日志为空时才恢复（避免覆盖正在运行的日志）
        if (!log.isEmpty() && logText.getText().toString().isEmpty()) {
            logBuilder = new StringBuilder(log);
            logText.setText(log);
        }
        
        // 只在真正运行时恢复按钮状态
        if (wasRunning && progress < 100) {
            isRunning = true;
            startButton.setEnabled(false);
            startButton.setText("合并中...");
            cancelButton.setEnabled(true);
        } else {
            // 已完成或失败，清除运行状态
            isRunning = false;
            startButton.setEnabled(true);
            startButton.setText(progress == 100 ? "重新合并" : "开始合并");
            cancelButton.setEnabled(false);
            if (progress >= 100 || status.contains("失败")) {
                // 清除保存的运行状态
                getSharedPreferences("OtaMerge", MODE_PRIVATE).edit().putBoolean("isRunning", false).apply();
            }
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
        startButton.setOnClickListener(v -> startMerge());
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
        cancelButton.setOnClickListener(v -> cancelMerge());
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
        cleanButton.setOnClickListener(v -> cleanWorkspace());
        LinearLayout.LayoutParams cleanButtonParams = new LinearLayout.LayoutParams(0, dp(50));
        cleanButtonParams.weight = 1;
        buttonRow2.addView(cleanButton, cleanButtonParams);

        refreshButton = new Button(this);
        refreshButton.setText("刷新状态");
        refreshButton.setTextSize(16);
        refreshButton.setTextColor(Color.WHITE);
        refreshButton.setTypeface(null, Typeface.BOLD);
        refreshButton.setAllCaps(false);
        refreshButton.setBackgroundResource(R.drawable.button_orange);
        refreshButton.setOnClickListener(v -> refreshStatus());
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
        openButton.setOnClickListener(v -> openFolder());
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
        copyButton.setOnClickListener(v -> copyLog());
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

        logScroll = new ScrollView(this);
        logScroll.setVerticalScrollBarEnabled(true);
        logScroll.setScrollbarFadingEnabled(false);
        logScroll.setBackgroundResource(R.drawable.dark_liquid_glass);
        logScroll.setFillViewport(false);
        // 关键：阻止父容器拦截触摸事件
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
        logText.setTextSize(12);
        logText.setTextColor(0xE6FFFFFF);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setPadding(dp(10), dp(10), dp(10), dp(10));
        // 水平滚动支持
        logText.setHorizontallyScrolling(true);
        
        logScroll.addView(logText, new ScrollView.LayoutParams(-1, -2));
        
        LinearLayout.LayoutParams logScrollParams = new LinearLayout.LayoutParams(-1, dp(300));
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
        if (isRunning) return;

        startButton.setEnabled(false);
        cancelButton.setEnabled(true);
        isRunning = true;
        logBuilder.setLength(0);
        logText.setText("");

        executor.execute(() -> {
            try {
                File otaDir = new File(OTA_DIR);
                File patchDir = new File(PATCH_DIR);
                
                appendLogWithProgress(0, "初始化环境");
                appendLogWithProgress(5, "查找包文件");
                updateStatus("查找包文件", 5);
                
                File[] otaFiles = otaDir.listFiles((dir, name) -> 
                    name.endsWith(".zip") || name.endsWith(".bin") || name.endsWith(".img"));
                File[] patchFiles = patchDir.listFiles((dir, name) -> 
                    name.endsWith(".zip") || name.endsWith(".bin") || name.endsWith(".img"));

                if (otaFiles == null || otaFiles.length == 0) {
                    appendLog("错误: ota 目录为空");
                    updateStatus("合并失败: 找不到旧版完整包", 0);
                    return;
                }

                if (patchFiles == null || patchFiles.length == 0) {
                    appendLog("错误: patch 目录为空");
                    updateStatus("合并失败: 找不到增量包", 0);
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
                
                appendLogWithProgress(10, "提取完整包: " + fullPackageDesc);
                appendLogWithProgress(11, "输出到: base_images/");
                appendLogWithProgress(15, "正在提取完整包...");
                updateStatus("提取完整包", 15);
                
                // 步骤1: 提取完整包
                File baseImagesDir = new File(WORK_DIR, "base_images");
                deleteDirectory(baseImagesDir);
                baseImagesDir.mkdirs();
                
                extractFullOtaWithProgress(fullOta, baseImagesDir);
                
                File[] baseImages = baseImagesDir.listFiles((dir, name) -> name.endsWith(".img"));
                if (baseImages == null || baseImages.length == 0) {
                    appendLog("错误: 未能提取任何镜像");
                    updateStatus("合并失败", 0);
                    return;
                }
                
                long totalSize = 0;
                for (File img : baseImages) {
                    totalSize += img.length();
                }
                appendLogWithProgress(45, String.format("✓ 已提取 %d 个镜像 (%dMB)", baseImages.length, totalSize / 1024 / 1024));

                // 步骤2: 创建增量包输出目录
                appendLogWithProgress(50, "准备增量合并环境");
                updateStatus("准备增量合并环境", 50);
                
                String patchName = patchZip.getName().replaceAll("\\.(zip|bin)$", "");
                File patchOutputDir = new File(WORK_DIR, patchName);
                deleteDirectory(patchOutputDir);
                patchOutputDir.mkdirs();
                
                // 步骤3: 移动旧镜像（不需要复制，直接移动节省时间）
                File oldDir = new File(patchOutputDir, "old");
                oldDir.mkdirs();
                
                appendLogWithProgress(51, "移动基础镜像到 " + patchName + "/old/");
                moveFilesWithProgress(baseImages, oldDir, 51, 59);
                appendLogWithProgress(59, "✓ 基础镜像已移动");
                
                // 步骤4: 应用增量包
                appendLogWithProgress(60, "应用增量包: " + patchZip.getName());
                appendLogWithProgress(61, "输出到: " + patchName + "/");
                appendLogWithProgress(65, "正在应用增量包...");
                updateStatus("应用增量包", 65);
                
                applyIncrementalPatch(patchZip, oldDir, patchOutputDir);
                
                // 步骤5: 验证结果
                File[] mergedImages = patchOutputDir.listFiles((dir, name) -> name.endsWith(".img"));
                if (mergedImages == null || mergedImages.length == 0) {
                    appendLog("错误: 增量合并失败");
                    updateStatus("合并失败", 0);
                    return;
                }
                
                long mergedSize = 0;
                for (File img : mergedImages) {
                    mergedSize += img.length();
                }
                appendLogWithProgress(80, String.format("✓ 增量应用完成 %d 个镜像 (%dMB)", mergedImages.length, mergedSize / 1024 / 1024));
                
                appendLogWithProgress(82, "验证合并结果");
                updateStatus("验证合并结果", 82);
                appendLogWithProgress(84, String.format("✓ 合并完成！共 %d 个镜像，总大小 %dMB", mergedImages.length, mergedSize / 1024 / 1024));

                // 步骤6: 打包
                appendLogWithProgress(85, "正在打包 ZIP（仅存储模式）...");
                updateStatus("打包 ZIP", 85);
                
                String outputName = patchZip.getName().replace(".zip", "_merged.zip").replace(".bin", "_merged.zip");
                File outputFile = new File(OUTPUT_DIR, outputName);
                packImagesWithProgress(patchOutputDir, outputFile, mergedImages.length);

                updateStatus("✓ 合并完成！", 100);
                appendLog("\n━━━━━━━━━━━━━━━━━━━━");
                appendLog("✓ 合并完成！");
                appendLog("输出路径：" + outputFile.getAbsolutePath());
                appendLog(String.format("文件大小：%d MB", outputFile.length() / 1024 / 1024));
                appendLog("\n✓ 所有 img 仅存储 ZIP 已完成，在 output 文件夹");

            } catch (Exception e) {
                appendLog("\n━━━━━━━━━━━━━━━━━━━━");
                appendLog("✗ 错误: " + e.getMessage());
                updateStatus("✗ 合并失败", progressBar.getProgress());
                e.printStackTrace();
            } finally {
                mainHandler.post(() -> {
                    startButton.setEnabled(true);
                    startButton.setText(progressBar.getProgress() == 100 ? "重新合并" : "重试合并");
                    cancelButton.setEnabled(false);
                    isRunning = false;
                    saveState();
                });
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
        currentProcess = pb.start();

        BufferedReader reader = new BufferedReader(new InputStreamReader(currentProcess.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.trim().isEmpty()) {
                appendLog(line);
            }
        }

        int exitCode = currentProcess.waitFor();
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
            dumper.setExecutable(true);
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
        if (currentProcess != null) {
            currentProcess.destroy();
            appendLog("\n━━━━━━━━━━━━━━━━━━━━");
            appendLog("已取消合并");
        }
        isRunning = false;
        startButton.setEnabled(true);
        cancelButton.setEnabled(false);
        updateStatus("已取消", progressBar.getProgress());
    }

    private void cleanWorkspace() {
        new AlertDialog.Builder(this)
            .setTitle("清理工作区")
            .setMessage("确定要清理 work/ 目录吗？\n\n将删除：\n- 基础镜像 (base_images/)\n- 旧镜像副本 (old/)\n- 所有合并后的镜像文件\n\n注意：output/ 目录的 ZIP 文件不会被删除")
            .setNegativeButton("取消", null)
            .setPositiveButton("确定清理", (dialog, which) -> {
                executor.execute(() -> {
                    try {
                        File workDir = new File(WORK_DIR);
                        if (workDir.exists()) {
                            deleteDirectory(workDir);
                            workDir.mkdirs();
                        }
                        appendLog("\n━━━━━━━━━━━━━━━━━━━━");
                        appendLog("✓ 工作区已清理");
                        mainHandler.post(() -> {
                            progressBar.setProgress(0);
                            progressText.setText("0%");
                            statusText.setText("等待开始合并");
                            new AlertDialog.Builder(this).setTitle("清理完成").setMessage("work/ 目录已清空").setPositiveButton("确定", null).show();
                        });
                    } catch (Exception e) {
                        appendLog("\n✗ 清理失败：" + e.getMessage());
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

    private void refreshStatus() {
        // 重置所有状态到初始
        logText.setText("等待开始...");
        progressBar.setProgress(0);
        statusText.setText("等待开始合并");
        startButton.setEnabled(true);
        startButton.setText("开始合并");
        cancelButton.setEnabled(false);
        isRunning = false;
        
        // 如果有进程在运行，先终止
        if (currentProcess != null && currentProcess.isAlive()) {
            currentProcess.destroy();
            currentProcess = null;
        }
        
        appendLog("\n━━━━━━━━━━━━━━━━━━━━");
        appendLog("🔄 已重置状态");
        
        // 检查现有文件
        File outputDir = new File(OUTPUT_DIR);
        File[] outputs = outputDir.listFiles();
        
        if (outputs != null && outputs.length > 0) {
            appendLog("✓ 发现已完成的合并文件：" + outputs[0].getName());
        } else {
            appendLog("📁 output 目录为空，可以开始新的合并");
        }
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

    private void updateStatus(String status, int progress) {
        mainHandler.post(() -> {
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
            // 每次更新都保存状态
            saveState();
        });
    }

    private void appendLogWithProgress(int progress, String message) {
        String logLine = String.format("[%d%%] %s", progress, message);
        logBuilder.append(logLine).append(StringUtils.LF);
        mainHandler.post(() -> {
            logText.append(logLine + StringUtils.LF);
        });
        // 只在进度真正变化时才更新状态（避免频繁UI更新）
        if (progress != lastProgress) {
            updateStatus(message, progress);
            lastProgress = progress;
        }
    }

    private void appendLog(String message) {
        logBuilder.append(message).append(StringUtils.LF);
        mainHandler.post(() -> {
            logText.append(message + StringUtils.LF);
        });
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
        currentProcess = pb.start();

        BufferedReader reader = new BufferedReader(new InputStreamReader(currentProcess.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.trim().isEmpty()) {
                appendLog(line);
            }
        }

        int exitCode = currentProcess.waitFor();
        if (exitCode != 0) {
            throw new Exception("提取完整包失败: exit code " + exitCode);
        }
    }

    private void moveFilesWithProgress(File[] files, File destDir, int startProgress, int endProgress) throws Exception {
        int total = files.length;
        int lastReportedProgress = -1;
        for (int i = 0; i < total; i++) {
            File src = files[i];
            File dest = new File(destDir, src.getName());
            int progress = startProgress + (endProgress - startProgress) * i / total;
            
            // 每个文件都输出日志
            appendLogWithProgress(progress, "复制 " + src.getName());
            
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
            File src = files[i];
            int progress = startProgress + (endProgress - startProgress) * i / total;
            appendLogWithProgress(progress, "复制 " + src.getName());
            copyFile(src, new File(destDir, src.getName()));
        }
    }

    private void packImagesWithProgress(File sourceDir, File outputFile, int totalFiles) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outputFile))) {
            zos.setLevel(ZipOutputStream.STORED);
            
            File[] files = sourceDir.listFiles((dir, name) -> name.endsWith(".img"));
            if (files != null) {
                for (int i = 0; i < files.length; i++) {
                    File file = files[i];
                    int progress = 85 + (11 * (i + 1) / files.length);
                    appendLogWithProgress(progress, String.format("打包 %s (%d/%d)", file.getName(), i + 1, files.length));
                    
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


    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }
}
