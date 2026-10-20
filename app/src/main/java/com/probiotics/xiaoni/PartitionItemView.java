package com.probiotics.xiaoni;

import android.graphics.Color;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ProgressBar;
import android.widget.Button;
import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.widget.Toast;

/**
 * 分区条目视图 - 优化版
 */
public class PartitionItemView extends LinearLayout {
    private TextView partNameText;
    private TextView partSizeText;
    private TextView hashText;
    private Button extractButton;
    private Button copyHashButton;
    private ProgressBar progressBar;
    private TextView progressText;
    private TextView speedText;
    private TextView activityText;
    private android.graphics.drawable.GradientDrawable progressFillDrawable;
    private LinearLayout progressPanel;
    private LinearLayout hashPanel;
    private LinearLayout pathPanel;
    private TextView pathText;
    
    private boolean isExtracting = false;
    private String partitionName;
    private String hash;
    private int partitionId;
    private OnExtractListener extractListener;
    
    public interface OnExtractListener {
        void onStartExtract(int partitionId, String partitionName);
        void onCancelExtract(int partitionId);
    }
    
    public PartitionItemView(Context context) {
        super(context);
        init(context);
    }
    
    public void setOnExtractListener(OnExtractListener listener) {
        this.extractListener = listener;
    }
    
    private void init(Context context) {
        setOrientation(VERTICAL);
        setPadding(dp(14), dp(12), dp(14), dp(12));
        setBackgroundResource(R.drawable.partition_item_bg_enhanced);
        
        // 顶部信息行
        LinearLayout topRow = new LinearLayout(context);
        topRow.setOrientation(HORIZONTAL);
        topRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        
        // 左侧信息
        LinearLayout leftInfo = new LinearLayout(context);
        leftInfo.setOrientation(VERTICAL);
        
        partNameText = new TextView(context);
        partNameText.setTextSize(15);
        partNameText.setTextColor(0xff0f1e36);
        partNameText.setTypeface(null, 1);
        leftInfo.addView(partNameText);
        
        partSizeText = new TextView(context);
        partSizeText.setTextSize(12);
        partSizeText.setTextColor(0xff6a7a8f);
        leftInfo.addView(partSizeText);
        
        LayoutParams leftLp = new LayoutParams(0, -2, 1);
        topRow.addView(leftInfo, leftLp);
        
        // 右侧按钮
        extractButton = new Button(context);
        extractButton.setText("提取");
        extractButton.setTextSize(13);
        extractButton.setTextColor(Color.WHITE);
        extractButton.setBackgroundResource(R.drawable.button_teal);
        extractButton.setAllCaps(false);
        extractButton.setMinWidth(0);
        extractButton.setMinHeight(0);
        extractButton.setPadding(dp(18), dp(6), dp(18), dp(6));
        extractButton.setOnClickListener(v -> {
            Haptics.perform(v);
            toggleExtract();
        });
        
        LayoutParams btnLp = new LayoutParams(dp(72), dp(36));
        btnLp.setMargins(dp(8), 0, 0, 0);
        topRow.addView(extractButton, btnLp);
        
        addView(topRow, new LayoutParams(-1, -2));
        
        // Hash 显示面板 - 液态玻璃效果 + 浅绿色
        hashPanel = new LinearLayout(context);
        hashPanel.setOrientation(HORIZONTAL);
        hashPanel.setGravity(android.view.Gravity.CENTER_VERTICAL);
        hashPanel.setPadding(dp(12), dp(10), dp(12), dp(10));
        hashPanel.setBackgroundResource(R.drawable.partition_expand_green_bg);
        hashPanel.setVisibility(GONE);
        
        LayoutParams hashPanelLp = new LayoutParams(-1, -2);
        hashPanelLp.setMargins(0, dp(10), 0, 0);
        
        hashText = new TextView(context);
        hashText.setTextSize(10);
        hashText.setTextColor(0xff5a6a7f);
        hashText.setTypeface(android.graphics.Typeface.MONOSPACE);
        
        LayoutParams hashTextLp = new LayoutParams(0, -2, 1);
        hashPanel.addView(hashText, hashTextLp);
        
        copyHashButton = new Button(context);
        copyHashButton.setText("复制");
        copyHashButton.setTextSize(13);
        copyHashButton.setTextColor(Color.WHITE);
        copyHashButton.setBackgroundResource(R.drawable.button_teal);
        copyHashButton.setAllCaps(false);
        copyHashButton.setMinWidth(0);
        copyHashButton.setMinHeight(0);
        copyHashButton.setPadding(dp(18), dp(6), dp(18), dp(6));
        copyHashButton.setOnClickListener(v -> {
            Haptics.perform(v);
            copyHash();
        });
        
        LayoutParams copyBtnLp = new LayoutParams(dp(72), dp(36));
        copyBtnLp.setMargins(dp(8), 0, 0, 0);
        hashPanel.addView(copyHashButton, copyBtnLp);
        
        addView(hashPanel, hashPanelLp);
        
        // 进度面板 - 液态玻璃效果 + 浅绿色
        progressPanel = new LinearLayout(context);
        progressPanel.setOrientation(VERTICAL);
        progressPanel.setPadding(dp(14), dp(12), dp(14), dp(12));
        progressPanel.setBackgroundResource(R.drawable.partition_expand_green_bg);
        progressPanel.setVisibility(GONE);
        
        LayoutParams progressPanelLp = new LayoutParams(-1, -2);
        progressPanelLp.setMargins(0, dp(10), 0, 0);
        
        // 进度条 - 更大更圆角
        progressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        android.graphics.drawable.GradientDrawable track = new android.graphics.drawable.GradientDrawable();
        track.setColor(0x665A718A);
        track.setCornerRadius(dp(20));
        track.setStroke(dp(1), 0x66FFFFFF);
        progressFillDrawable = new android.graphics.drawable.GradientDrawable();
        progressFillDrawable.setColor(0xFFB9E3F0);
        progressFillDrawable.setCornerRadius(dp(20));
        android.graphics.drawable.ClipDrawable progressClip = new android.graphics.drawable.ClipDrawable(
                progressFillDrawable, android.view.Gravity.LEFT,
                android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable progressDrawable = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{track, progressClip});
        progressDrawable.setId(0, android.R.id.background);
        progressDrawable.setId(1, android.R.id.progress);
        progressBar.setProgressDrawable(progressDrawable);
        
        // 进度信息行
        LinearLayout progressInfoRow = new LinearLayout(context);
        progressInfoRow.setOrientation(HORIZONTAL);
        progressInfoRow.setPadding(0, dp(6), 0, 0);
        
        progressText = new TextView(context);
        progressText.setText("0%");
        progressText.setTextSize(13);
        progressText.setTextColor(0xff1e5a9e); // 深蓝色
        progressText.setTypeface(null, 1);
        LayoutParams progressTextLp = new LayoutParams(0, -2, 1);
        progressInfoRow.addView(progressText, progressTextLp);
        
        speedText = new TextView(context);
        speedText.setText("速度: 计算中...");
        speedText.setTextSize(11);
        speedText.setTextColor(0xff1e5a9e); // 深蓝色
        speedText.setGravity(android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
        speedText.setSingleLine(true);
        progressInfoRow.addView(speedText);
        
        progressPanel.addView(progressInfoRow, new LayoutParams(-1, -2));
        progressPanel.addView(progressBar, new LayoutParams(-1, dp(16)));

        activityText = new TextView(context);
        activityText.setText("准备中");
        activityText.setTextSize(11);
        activityText.setTextColor(0xff5a6a7f);
        activityText.setPadding(0, dp(4), 0, 0);
        progressPanel.addView(activityText, new LayoutParams(-1, -2));
        
        addView(progressPanel, progressPanelLp);
        
        // 保存路径面板 - 液态玻璃效果
        pathPanel = new LinearLayout(context);
        pathPanel.setOrientation(VERTICAL);
        pathPanel.setPadding(dp(12), dp(10), dp(12), dp(10));
        pathPanel.setBackgroundResource(R.drawable.partition_expand_green_bg);
        pathPanel.setVisibility(GONE);
        
        LayoutParams pathPanelLp = new LayoutParams(-1, -2);
        pathPanelLp.setMargins(0, dp(8), 0, 0);
        
        TextView pathLabel = new TextView(context);
        pathLabel.setText("保存路径:");
        pathLabel.setTextSize(11);
        pathLabel.setTextColor(0xff6a7a8f);
        pathPanel.addView(pathLabel);
        
        pathText = new TextView(context);
        pathText.setTextSize(10);
        pathText.setTextColor(0xff0f1e36);
        pathText.setTypeface(android.graphics.Typeface.MONOSPACE);
        pathText.setPadding(0, dp(4), 0, 0);
        pathPanel.addView(pathText);
        
        addView(pathPanel, pathPanelLp);
    }
    
    public void setPartitionInfo(int id, String name, long sizeBytes, String hashValue) {
        this.partitionId = id;
        this.partitionName = name;
        this.hash = hashValue;
        partNameText.setText(name);
        partSizeText.setText(formatSize(sizeBytes));
        
        // 始终显示哈希面板
        if (hashValue != null && !hashValue.isEmpty()) {
            hashText.setText("OTA 清单 SHA256: " + hashValue);
            hashPanel.setVisibility(VISIBLE);
        } else {
            hashText.setText("OTA 清单 SHA256: 无");
            hashPanel.setVisibility(VISIBLE);
        }
    }
    
    /**
     * v3.9.3：第三个参数为阶段标记（来自 native 进度上报）——
     * 0 = 下载中（在线提取下载压缩数据），1 = 写入中（本地解压写盘）。
     * 在百分比旁显示阶段，让用户清楚当前在做什么，避免误以为卡住。
     */
    public void setProgress(int percent, float speedMBps, int phase) {
        percent = Math.max(0, Math.min(99, percent));
        progressBar.setIndeterminate(false);
        updateProgressColor(percent);
        progressBar.setProgress(percent);
        progressText.setText((phase == 1 ? "写入 " : "下载 ") + percent + "%");
        String rate = speedMBps < 0f ? "-- MB/s"
                : String.format(java.util.Locale.US, "%.2f MB/s", speedMBps);
        speedText.setText((phase == 1 ? "写入速度: " : "下载速度: ") + rate);
        activityText.setText(phase == 1 ? "正在写入镜像" : "正在远程读取 OTA 数据");
    }

    private void updateProgressColor(int percent) {
        int start = 0xFFB9E3F0; // 0% 浅色
        int end = 0xFF2347B8;   // 100% 深色
        float f = Math.max(0, Math.min(100, percent)) / 100f;
        int r = Math.round(android.graphics.Color.red(start)
                + (android.graphics.Color.red(end) - android.graphics.Color.red(start)) * f);
        int g = Math.round(android.graphics.Color.green(start)
                + (android.graphics.Color.green(end) - android.graphics.Color.green(start)) * f);
        int b = Math.round(android.graphics.Color.blue(start)
                + (android.graphics.Color.blue(end) - android.graphics.Color.blue(start)) * f);
        progressFillDrawable.setColor(android.graphics.Color.rgb(r, g, b));
    }

    /** 在线下载阶段使用确定百分比和固定速率位置，不启动滑动动画。 */
    public void setDownloading(int percent, float speedMBps) {
        setProgress(percent, speedMBps, 0);
    }

    public void setQueued() {
        progressBar.setIndeterminate(false);
        updateProgressColor(0);
        progressBar.setProgress(0);
        progressText.setText("排队中");
        speedText.setText("下载速度: -- MB/s");
        activityText.setText("等待并行任务调度");
    }

    public void setFinishing() {
        progressBar.setIndeterminate(false);
        updateProgressColor(99);
        progressBar.setProgress(99);
        progressText.setText("写入 99%");
        speedText.setText("写入速度: -- MB/s");
        activityText.setText("正在校验输出文件");
    }

    public void setSavingToTarget() {
        progressBar.setIndeterminate(false);
        updateProgressColor(99);
        progressBar.setProgress(99);
        progressText.setText("写入 99%");
        speedText.setText("写入速度: -- MB/s");
        activityText.setText("正在保存到下载目录");
    }
    
    /** 全选任务由页面级取消按钮统一控制，展开本行进度并避免重复启动单分区任务。 */
    public void beginBatchProgress() {
        progressPanel.setVisibility(VISIBLE);
        extractButton.setText("处理中");
        extractButton.setEnabled(false);
        progressBar.setIndeterminate(false);
        updateProgressColor(0);
        progressBar.setProgress(0);
        progressText.setText("排队中");
        speedText.setText("下载速度: -- MB/s");
        activityText.setText("等待并行任务调度");
        isExtracting = true;
    }

    private void toggleExtract() {
        if (isExtracting) {
            extractButton.setText("提取");
            extractButton.setBackgroundResource(R.drawable.button_teal);
            progressPanel.setVisibility(GONE);
            isExtracting = false;
            if (extractListener != null) {
                extractListener.onCancelExtract(partitionId);
            }
        } else {
            extractButton.setText("✕");
            extractButton.setBackgroundResource(R.drawable.button_red);
            progressPanel.setVisibility(VISIBLE);
            progressBar.setIndeterminate(false);
            updateProgressColor(0);
            progressBar.setProgress(0);
            progressText.setText("下载 0%");
            speedText.setText("下载速度: -- MB/s");
            activityText.setText("正在建立远程读取");
            isExtracting = true;
            if (extractListener != null) {
                extractListener.onStartExtract(partitionId, partitionName);
            }
        }
    }
    
    public void setComplete() {
        progressBar.setIndeterminate(false);
        updateProgressColor(100);
        extractButton.setText("✓");
        extractButton.setBackgroundResource(R.drawable.button_green);
        extractButton.setEnabled(false);
        progressBar.setProgress(100);
        progressText.setText("100%");
        speedText.setText("任务完成");
        activityText.setText("提取完成");
        isExtracting = false;
    }
    
    public void setError(String error) {
        progressBar.setIndeterminate(false);
        extractButton.setText("提取");
        extractButton.setBackgroundResource(R.drawable.button_teal);
        extractButton.setEnabled(true);
        progressPanel.setVisibility(GONE);
        isExtracting = false;
        Toast.makeText(getContext(), error, Toast.LENGTH_SHORT).show();
    }
    
    public void setOutputPath(String path) {
        if (path != null && !path.isEmpty()) {
            pathText.setText(path);
            pathPanel.setVisibility(VISIBLE);
        }
    }
    
    private void copyHash() {
        if (hash != null && !hash.isEmpty()) {
            ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Hash", hash);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(getContext(), "哈希值已复制", Toast.LENGTH_SHORT).show();
        }
    }
    
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
    
    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + 0.5f);
    }
}
