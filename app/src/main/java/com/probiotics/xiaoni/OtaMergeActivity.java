package com.probiotics.xiaoni;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
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
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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

    private TextView tvStatus;
    private ProgressBar progressBar;
    private TextView tvProgress;
    private TextView tvLog;
    private ScrollView scrollViewLog;
    private Button btnStart;
    private Button btnCancel;
    private Button btnClean;
    private Button btnRefresh;
    private Button btnOpenFolder;
    private Button btnCopyLog;

    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private StringBuilder logBuilder = new StringBuilder();
    private boolean isRunning = false;
    private Process currentProcess;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUI();
        initDirectories();
    }

    private void buildUI() {
        // 主容器
        ScrollView mainScroll = new ScrollView(this);
        mainScroll.setBackgroundColor(Color.parseColor("#EDF2F7"));
        
        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(dp(16), dp(16), dp(16), dp(16));

        // 返回按钮 + 标题
        LinearLayout headerLayout = new LinearLayout(this);
        headerLayout.setOrientation(LinearLayout.HORIZONTAL);
        headerLayout.setGravity(Gravity.CENTER_VERTICAL);
        
        Button btnBack = new Button(this);
        btnBack.setText("<");
        btnBack.setTextSize(20);
        btnBack.setBackgroundColor(Color.TRANSPARENT);
        btnBack.setOnClickListener(v -> finish());
        headerLayout.addView(btnBack, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView titleText = new TextView(this);
        titleText.setText("OTA 增量包合并");
        titleText.setTextSize(22);
        titleText.setTextColor(Color.parseColor("#1A202C"));
        titleText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2);
        titleParams.weight = 1;
        headerLayout.addView(titleText, titleParams);

        View spacer = new View(this);
        headerLayout.addView(spacer, new LinearLayout.LayoutParams(dp(48), dp(48)));

        mainLayout.addView(headerLayout);

        // 副标题
        TextView subtitle = new TextView(this);
        subtitle.setText("支持 payload.bin 格式 · 小米 / OPPO / vivo 全机型");
        subtitle.setTextSize(13);
        subtitle.setTextColor(Color.parseColor("#718096"));
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
        subtitleParams.topMargin = dp(8);
        subtitleParams.bottomMargin = dp(20);
        mainLayout.addView(subtitle, subtitleParams);

        // 使用步骤卡片
        LinearLayout stepsCard = createCard();
        TextView stepsTitle = new TextView(this);
        stepsTitle.setText("📋 使用步骤");
        stepsTitle.setTextSize(15);
        stepsTitle.setTextColor(Color.parseColor("#2D3748"));
        LinearLayout.LayoutParams stepsTitleParams = new LinearLayout.LayoutParams(-1, -2);
        stepsTitleParams.bottomMargin = dp(12);
        stepsCard.addView(stepsTitle, stepsTitleParams);

        TextView stepsContent = new TextView(this);
        stepsContent.setText("1. 旧版完整包 → /Download/DsuManager/Input/ota/\n" +
                "2. 增量包 → /Download/DsuManager/Input/patch/\n" +
                "3. 点击「开始合并」等待完成\n" +
                "4. 合并后镜像 → work/merged_images/");
        stepsContent.setTextSize(13);
        stepsContent.setTextColor(Color.parseColor("#4A5568"));
        stepsContent.setLineSpacing(dp(4), 1.0f);
        stepsCard.addView(stepsContent);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.bottomMargin = dp(16);
        mainLayout.addView(stepsCard, cardParams);

        // 进度卡片
        LinearLayout progressCard = createCard();
        tvStatus = new TextView(this);
        tvStatus.setText("等待开始合并");
        tvStatus.setTextSize(15);
        tvStatus.setTextColor(Color.parseColor("#2D3748"));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.bottomMargin = dp(12);
        progressCard.addView(tvStatus, statusParams);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        LinearLayout.LayoutParams pbParams = new LinearLayout.LayoutParams(-1, dp(8));
        pbParams.bottomMargin = dp(8);
        progressCard.addView(progressBar, pbParams);

        tvProgress = new TextView(this);
        tvProgress.setText("0%");
        tvProgress.setTextSize(14);
        tvProgress.setTextColor(Color.parseColor("#718096"));
        tvProgress.setGravity(Gravity.END);
        progressCard.addView(tvProgress);

        mainLayout.addView(progressCard, cardParams);

        // 按钮行1
        LinearLayout buttonRow1 = new LinearLayout(this);
        buttonRow1.setOrientation(LinearLayout.HORIZONTAL);
        
        btnStart = createButton("开始合并", "#48BB78");
        btnStart.setOnClickListener(v -> startMerge());
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(0, dp(52));
        btnParams.weight = 1;
        btnParams.rightMargin = dp(6);
        buttonRow1.addView(btnStart, btnParams);

        btnCancel = createButton("取消合并", "#F56565");
        btnCancel.setEnabled(false);
        btnCancel.setOnClickListener(v -> cancelMerge());
        LinearLayout.LayoutParams btnParams2 = new LinearLayout.LayoutParams(0, dp(52));
        btnParams2.weight = 1;
        btnParams2.leftMargin = dp(6);
        buttonRow1.addView(btnCancel, btnParams2);

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.bottomMargin = dp(12);
        mainLayout.addView(buttonRow1, rowParams);

        // 按钮行2
        LinearLayout buttonRow2 = new LinearLayout(this);
        buttonRow2.setOrientation(LinearLayout.HORIZONTAL);

        btnClean = createButton("清理工作区", "#9F7AEA");
        btnClean.setOnClickListener(v -> cleanWorkspace());
        LinearLayout.LayoutParams btnParams3 = new LinearLayout.LayoutParams(0, dp(52));
        btnParams3.weight = 1;
        btnParams3.rightMargin = dp(6);
        buttonRow2.addView(btnClean, btnParams3);

        btnRefresh = createButton("刷新状态", "#ED8936");
        btnRefresh.setOnClickListener(v -> refreshStatus());
        LinearLayout.LayoutParams btnParams4 = new LinearLayout.LayoutParams(0, dp(52));
        btnParams4.weight = 1;
        btnParams4.leftMargin = dp(6);
        buttonRow2.addView(btnRefresh, btnParams4);

        LinearLayout.LayoutParams rowParams2 = new LinearLayout.LayoutParams(-1, -2);
        rowParams2.bottomMargin = dp(12);
        mainLayout.addView(buttonRow2, rowParams2);

        // 按钮行3
        LinearLayout buttonRow3 = new LinearLayout(this);
        buttonRow3.setOrientation(LinearLayout.HORIZONTAL);

        btnOpenFolder = createButton("打开文件所在位置", "#4299E1");
        btnOpenFolder.setOnClickListener(v -> openFolder());
        LinearLayout.LayoutParams btnParams5 = new LinearLayout.LayoutParams(0, dp(52));
        btnParams5.weight = 1;
        btnParams5.rightMargin = dp(6);
        buttonRow3.addView(btnOpenFolder, btnParams5);

        btnCopyLog = createButton("复制全部日志", "#4299E1");
        btnCopyLog.setOnClickListener(v -> copyLog());
        LinearLayout.LayoutParams btnParams6 = new LinearLayout.LayoutParams(0, dp(52));
        btnParams6.weight = 1;
        btnParams6.leftMargin = dp(6);
        buttonRow3.addView(btnCopyLog, btnParams6);

        LinearLayout.LayoutParams rowParams3 = new LinearLayout.LayoutParams(-1, -2);
        rowParams3.bottomMargin = dp(16);
        mainLayout.addView(buttonRow3, rowParams3);

        // 日志卡片
        LinearLayout logCard = createCard();
        TextView logTitle = new TextView(this);
        logTitle.setText("📝 合并日志");
        logTitle.setTextSize(15);
        logTitle.setTextColor(Color.parseColor("#2D3748"));
        LinearLayout.LayoutParams logTitleParams = new LinearLayout.LayoutParams(-1, -2);
        logTitleParams.bottomMargin = dp(12);
        logCard.addView(logTitle, logTitleParams);

        // 使用 PayloadDumper 的日志框实现方式
        scrollViewLog = new ScrollView(this);
        scrollViewLog.setVerticalScrollBarEnabled(true);
        scrollViewLog.setScrollbarFadingEnabled(false);
        scrollViewLog.setFillViewport(false);
        scrollViewLog.setBackgroundColor(Color.parseColor("#F7FAFC"));
        scrollViewLog.setPadding(dp(12), dp(12), dp(12), dp(12));
        
        // 关键：阻止父容器拦截触摸事件，允许滚动
        scrollViewLog.setOnTouchListener((view, event) -> {
            ViewParent parent = view.getParent();
            if (parent != null) {
                int action = event.getActionMasked();
                parent.requestDisallowInterceptTouchEvent(
                    action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL
                );
            }
            return false;
        });

        tvLog = new TextView(this);
        tvLog.setText("等待开始...");
        tvLog.setTextSize(12);
        tvLog.setTextColor(Color.parseColor("#2D3748"));
        tvLog.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvLog.setPadding(0, 0, 0, 0);
        
        // 长按复制日志
        tvLog.setOnLongClickListener(v -> {
            copyLog();
            return true;
        });

        scrollViewLog.addView(tvLog, new ScrollView.LayoutParams(-1, -2));
        logCard.addView(scrollViewLog, new LinearLayout.LayoutParams(-1, dp(350)));
        mainLayout.addView(logCard);

        mainScroll.addView(mainLayout);
        setContentView(mainScroll);
    }

    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.WHITE);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        return card;
    }

    private Button createButton(String text, String color) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setBackgroundColor(Color.parseColor(color));
        btn.setTextSize(15);
        btn.setAllCaps(false);
        return btn;
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private void initDirectories() {
        new File(OTA_DIR).mkdirs();
        new File(PATCH_DIR).mkdirs();
        new File(OUTPUT_DIR).mkdirs();
        new File(WORK_DIR).mkdirs();
        addLog("已创建必要目录:\n- " + OTA_DIR + "\n- " + PATCH_DIR + "\n- " + OUTPUT_DIR + "\n- " + WORK_DIR);
    }

    private void startMerge() {
        if (isRunning) return;

        btnStart.setEnabled(false);
        btnCancel.setEnabled(true);
        isRunning = true;
        logBuilder.setLength(0);
        updateStatus("开始合并...", 0);

        executor.execute(() -> {
            try {
                // 检查文件
                File otaDir = new File(OTA_DIR);
                File patchDir = new File(PATCH_DIR);
                
                File[] otaFiles = otaDir.listFiles((dir, name) -> 
                    name.endsWith(".zip") || name.endsWith(".bin") || name.endsWith(".img"));
                File[] patchFiles = patchDir.listFiles((dir, name) ->
                    name.endsWith(".zip") || name.endsWith(".bin") || name.endsWith(".img"));

                if (otaFiles == null || otaFiles.length == 0) {
                    addLog("错误: ota 目录为空");
                    updateStatus("合并失败: 找不到旧版完整包", 0);
                    return;
                }

                if (patchFiles == null || patchFiles.length == 0) {
                    addLog("错误: patch 目录为空");
                    updateStatus("合并失败: 找不到增量包", 0);
                    return;
                }

                addLog("找到旧版包: " + otaFiles[0].getName());
                addLog("找到增量包: " + patchFiles[0].getName());

                // 解压旧版包
                updateStatus("解压旧版完整包...", 10);
                File otaWorkDir = new File(WORK_DIR, "ota");
                extractPackage(otaFiles[0], otaWorkDir);

                // 解压增量包
                updateStatus("解压增量包...", 30);
                File patchWorkDir = new File(WORK_DIR, "patch");
                extractPackage(patchFiles[0], patchWorkDir);

                // 提取 payload.bin
                updateStatus("提取 payload.bin...", 50);
                File otaPayload = findPayload(otaWorkDir);
                File patchPayload = findPayload(patchWorkDir);

                if (otaPayload == null || patchPayload == null) {
                    addLog("错误: 找不到 payload.bin");
                    updateStatus("合并失败: 缺少 payload.bin", 0);
                    return;
                }

                // 使用 payload_dumper 合并
                updateStatus("正在合并镜像...", 60);
                File mergedDir = new File(WORK_DIR, "merged_images");
                mergedDir.mkdirs();
                
                mergePayload(otaPayload, patchPayload, mergedDir);

                // 打包输出
                updateStatus("打包输出文件...", 90);
                String outputName = patchFiles[0].getName().replace(".zip", "_merged.zip");
                File outputFile = new File(OUTPUT_DIR, outputName);
                packImages(mergedDir, outputFile);

                updateStatus("合并完成!", 100);
                addLog("输出文件: " + outputFile.getAbsolutePath());

            } catch (Exception e) {
                addLog("错误: " + e.getMessage());
                updateStatus("合并失败", progressBar.getProgress());
                e.printStackTrace();
            } finally {
                mainHandler.post(() -> {
                    btnStart.setEnabled(true);
                    btnCancel.setEnabled(false);
                    isRunning = false;
                });
            }
        });
    }

    private void extractPackage(File packageFile, File targetDir) throws Exception {
        targetDir.mkdirs();
        addLog("解压: " + packageFile.getName());

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
            // 直接复制 .bin 或 .img
            File dest = new File(targetDir, packageFile.getName());
            copyFile(packageFile, dest);
        }
    }

    private File findPayload(File dir) {
        File payload = new File(dir, "payload.bin");
        if (payload.exists()) return payload;

        // 递归查找
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
        addLog("使用 payload_dumper 合并...");
        
        // 从 assets 复制 payload_dumper
        File dumper = copyPayloadDumper();
        
        // 执行合并命令
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
            addLog(line);
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
        addLog("打包镜像到: " + outputFile.getName());
        
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outputFile))) {
            zos.setLevel(ZipOutputStream.STORED); // 仅存储,不压缩
            
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
            addLog("已取消合并");
        }
        isRunning = false;
        btnStart.setEnabled(true);
        btnCancel.setEnabled(false);
        updateStatus("已取消", progressBar.getProgress());
    }

    private void cleanWorkspace() {
        new AlertDialog.Builder(this)
            .setTitle("清理工作区")
            .setMessage("确定要删除 work 目录下的所有文件吗？")
            .setPositiveButton("确定", (dialog, which) -> {
                executor.execute(() -> {
                    try {
                        File workDir = new File(WORK_DIR);
                        deleteDirectory(workDir);
                        workDir.mkdirs();
                        addLog("工作区已清理");
                        updateStatus("工作区已清理", 0);
                        mainHandler.post(() -> {
                            progressBar.setProgress(0);
                            tvProgress.setText("0%");
                        });
                    } catch (Exception e) {
                        addLog("清理失败: " + e.getMessage());
                    }
                });
            })
            .setNegativeButton("取消", null)
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
        addLog("刷新状态...");
        
        // 检查输出目录
        File outputDir = new File(OUTPUT_DIR);
        File[] outputs = outputDir.listFiles((dir, name) -> name.endsWith("_merged.zip"));
        
        if (outputs != null && outputs.length > 0) {
            addLog("找到合并文件: " + outputs[0].getName());
            updateStatus("合并完成", 100);
        } else {
            // 检查是否有临时文件
            File mergedDir = new File(WORK_DIR, "merged_images");
            if (mergedDir.exists() && mergedDir.listFiles() != null && mergedDir.listFiles().length > 0) {
                addLog("发现未打包的镜像文件");
                updateStatus("等待打包", 90);
            } else {
                addLog("未发现合并结果");
                updateStatus("等待开始合并", 0);
            }
        }
    }

    private void openFolder() {
        try {
            File folder = new File(WORK_DIR, "merged_images");
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri uri = Uri.parse("content://com.android.externalstorage.documents/document/primary:Download/DsuManager/Input/work/merged_images");
            intent.setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR);
            startActivity(intent);
        } catch (Exception e) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("path", WORK_DIR + "/merged_images"));
            Toast.makeText(this, "已复制路径: " + WORK_DIR + "/merged_images", Toast.LENGTH_LONG).show();
        }
    }

    private void copyLog() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("log", logBuilder.toString()));
        Toast.makeText(this, "已复制日志", Toast.LENGTH_SHORT).show();
    }

    private void updateStatus(String status, int progress) {
        mainHandler.post(() -> {
            tvStatus.setText(status);
            progressBar.setProgress(progress);
            tvProgress.setText(progress + "%");
        });
    }

    private void addLog(String message) {
        logBuilder.append(message).append("\n");
        mainHandler.post(() -> {
            tvLog.setText(logBuilder.toString());
            scrollViewLog.post(() -> scrollViewLog.fullScroll(View.FOCUS_DOWN));
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
