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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        initDirectories();
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
        logScroll.setOnTouchListener((view, event) -> {
            ViewParent parent = view.getParent();
            if (parent != null) {
                int action = event.getActionMasked();
                parent.requestDisallowInterceptTouchEvent(
                    action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL
                );
            }
            return false;
        });

        logText = new TextView(this);
        logText.setText("等待开始...");
        logText.setTextSize(12);
        logText.setTextColor(0xE6FFFFFF);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setPadding(dp(10), dp(10), dp(10), dp(10));
        logText.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        logText.setOnLongClickListener(v -> {
            copyLog();
            return true;
        });

        logScroll.addView(logText, new FrameLayout.LayoutParams(-1, -2));
        
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
        appendLog("已创建必要目录:\n- " + OTA_DIR + "\n- " + PATCH_DIR + "\n- " + OUTPUT_DIR + "\n- " + WORK_DIR);
    }

    private void startMerge() {
        if (isRunning) return;

        startButton.setEnabled(false);
        cancelButton.setEnabled(true);
        isRunning = true;
        logBuilder.setLength(0);
        logText.setText("");
        updateStatus("开始合并...", 0);

        executor.execute(() -> {
            try {
                File otaDir = new File(OTA_DIR);
                File patchDir = new File(PATCH_DIR);
                
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

                appendLog("找到旧版包: " + otaFiles[0].getName());
                appendLog("找到增量包: " + patchFiles[0].getName());

                updateStatus("解压旧版完整包...", 10);
                File otaWorkDir = new File(WORK_DIR, "ota");
                extractPackage(otaFiles[0], otaWorkDir);

                updateStatus("解压增量包...", 30);
                File patchWorkDir = new File(WORK_DIR, "patch");
                extractPackage(patchFiles[0], patchWorkDir);

                updateStatus("提取 payload.bin...", 50);
                File otaPayload = findPayload(otaWorkDir);
                File patchPayload = findPayload(patchWorkDir);

                if (otaPayload == null || patchPayload == null) {
                    appendLog("错误: 找不到 payload.bin");
                    updateStatus("合并失败: 缺少 payload.bin", 0);
                    return;
                }

                updateStatus("正在合并镜像...", 60);
                File mergedDir = new File(WORK_DIR, "merged_images");
                mergedDir.mkdirs();
                mergePayload(otaPayload, patchPayload, mergedDir);

                updateStatus("打包输出文件...", 90);
                String outputName = patchFiles[0].getName().replace(".zip", "_merged.zip");
                File outputFile = new File(OUTPUT_DIR, outputName);
                packImages(mergedDir, outputFile);

                updateStatus("✓ 合并完成！", 100);
                appendLog("\n━━━━━━━━━━━━━━━━━━━━");
                appendLog("✓ 合并完成！");
                appendLog("输出文件: " + outputFile.getAbsolutePath());
                appendLog("文件大小: " + (outputFile.length() / 1024 / 1024) + " MB");
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
                });
            }
        });
    }

    private void extractPackage(File packageFile, File targetDir) throws Exception {
        targetDir.mkdirs();
        appendLog("解压: " + packageFile.getName());

        if (packageFile.getName().endsWith(".zip")) {
            try (ZipInputStream zis = new ZipInputStream(new FileInputStream(packageFile))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    File file = new File(targetDir, entry.getName());
                    if (entry.isDirectory()) {
                        file.mkdirs();
                    } else {
                        file.getParentFile().mkdirs();
                        try (FileOutputStream fos = new FileOutputStream(file)) {
                            byte[] buffer = new byte[8192];
                            int len;
                            while ((len = zis.read(buffer)) > 0) {
                                fos.write(buffer, 0, len);
                            }
                        }
                    }
                    zis.closeEntry();
                }
            }
        } else {
            File dest = new File(targetDir, packageFile.getName());
            copyFile(packageFile, dest);
        }
    }

    private File findPayload(File dir) {
        File payload = new File(dir, "payload.bin");
        if (payload.exists()) return payload;

        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    File found = findPayload(f);
                    if (found != null) return found;
                } else if (f.getName().equals("payload.bin")) {
                    return f;
                }
            }
        }
        return null;
    }

    private void mergePayload(File otaPayload, File patchPayload, File outputDir) throws Exception {
        appendLog("使用 payload_dumper 合并...");
        
        File dumper = copyPayloadDumper();
        
        ProcessBuilder pb = new ProcessBuilder(
            dumper.getAbsolutePath(),
            "--base", otaPayload.getAbsolutePath(),
            "--patch", patchPayload.getAbsolutePath(),
            "--out", outputDir.getAbsolutePath()
        );
        pb.redirectErrorStream(true);
        currentProcess = pb.start();

        BufferedReader reader = new BufferedReader(new InputStreamReader(currentProcess.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null) {
            appendLog(line);
        }

        int exitCode = currentProcess.waitFor();
        if (exitCode != 0) {
            throw new Exception("payload_dumper 执行失败: " + exitCode);
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

    private void packImages(File sourceDir, File outputFile) throws Exception {
        appendLog("打包镜像到: " + outputFile.getName());
        
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outputFile))) {
            zos.setLevel(ZipOutputStream.STORED);
            
            File[] files = sourceDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile()) {
                        ZipEntry entry = new ZipEntry(file.getName());
                        entry.setMethod(ZipEntry.STORED);
                        entry.setSize(file.length());
                        entry.setCrc(calculateCRC32(file));
                        zos.putNextEntry(entry);
                        
                        try (FileInputStream fis = new FileInputStream(file)) {
                            byte[] buffer = new byte[8192];
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
            byte[] buffer = new byte[8192];
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
        appendLog("\n━━━━━━━━━━━━━━━━━━━━");
        appendLog("🔄 刷新状态中...");
        
        File outputDir = new File(OUTPUT_DIR);
        File[] outputs = outputDir.listFiles();
        
        if (outputs != null && outputs.length > 0) {
            appendLog("✓ 找到合并文件：" + outputs[0].getName());
            updateStatus("✓ 合并完成", 100);
        } else {
            File mergedDir = new File(WORK_DIR, "merged_images");
            if (mergedDir.exists() && mergedDir.listFiles() != null && mergedDir.listFiles().length > 0) {
                appendLog("✓ 发现未打包的镜像文件");
                updateStatus("等待打包", 90);
            } else {
                appendLog("✗ 未发现合并结果");
                updateStatus("等待开始合并", 0);
            }
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
        });
    }

    private void appendLog(String message) {
        logBuilder.append(message).append(StringUtils.LF);
        mainHandler.post(() -> {
            logText.append(message + StringUtils.LF);
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }
}
