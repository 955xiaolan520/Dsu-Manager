package com.probiotics.xiaoni;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 下载管理二级页（ROM 更新中心标题右侧入口，图二）：
 *  - 当前任务卡：进度 / 速度 / 剩余时间 / 已下载大小，暂停 / 继续 / 取消（与通知栏、ROM 查询页三方同步）
 *  - 队列说明：新任务自动排队接续（单任务串行引擎）
 *  - 下载历史：完成 / 取消 / 失败徽章，完成后一键「打开文件位置」，可删除单条 / 一键清空
 *  - 全程液态玻璃效果框 + 全局振动反馈
 */
public final class DownloadManagerActivity extends Activity {

    // 蓝绿色系（青碧）：玻璃背景上高对比可见
    private static final int TEAL_TITLE = 0xff0e7d95;   // 标题蓝绿色
    private static final int TEAL_DARK = 0xff155e70;    // 卡片主文字
    private static final int TEAL_SOFT = 0xff4a7d8c;    // 次级文字
    private static final int TEAL_FAINT = 0xff6b8fa0;   // 弱化文字

    // 当前任务实时状态（与 DownloadService 广播 / 通知栏同一数据源）
    private String activeName = "";
    private String activeOutput = "";
    private String stateText = "";
    private long done = -1, total = -1, speed = -1, eta = -1;
    private boolean taskPaused;

    // UI
    private LinearLayout activeHost;      // 当前任务卡容器
    private LinearLayout historyHost;     // 历史列表容器
    private TextView historySummary;      // 历史统计
    private ProgressBar progressBar;
    private TextView percentLabel, bytesLabel, stateLabel;
    private Button pauseResumeButton;

    private final List<JSONObject> history = new ArrayList<>();
    private BroadcastReceiver receiver;

    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        // v3.8.2 修复：与其他二级页面对齐 —— 透明状态栏 + 内容避开系统栏（此前顶到状态栏）
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(14), dp(18), dp(28));
        scroll.addView(content);

        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundResource(R.drawable.liquid_backdrop);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                view.setPadding(0, bars.top, 0, 0);
            } else {
                view.setPadding(0, insets.getSystemWindowInsetTop(), 0, 0);
            }
            return insets;
        });
        root.addView(scroll, new android.widget.FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        // ---------- 标题栏（v3.8.2：标题绝对居中，返回箭头去框） ----------
        android.widget.FrameLayout title = new android.widget.FrameLayout(this);
        title.setBackgroundResource(R.drawable.liquid_glass_panel);
        title.setPadding(dp(6), 0, dp(6), 0);
        Button back = new Button(this, null, 0);
        back.setText("<");
        back.setAllCaps(false);
        back.setTextSize(20);
        back.setTypeface(null, 1);
        back.setTextColor(TEAL_TITLE);
        back.setGravity(Gravity.CENTER);
        back.setMinWidth(0);
        back.setMinHeight(0);
        back.setIncludeFontPadding(false);
        back.setPadding(0, 0, 0, 0);
        back.setBackground(null);   // 返回箭头不加框
        back.setStateListAnimator(null);
        back.setOnClickListener(v -> {
            Haptics.perform(v);
            finish();
            overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out);
        });
        android.widget.FrameLayout.LayoutParams backLp =
                new android.widget.FrameLayout.LayoutParams(dp(44), dp(52), Gravity.START | Gravity.CENTER_VERTICAL);
        title.addView(back, backLp);
        TextView heading = label("下载管理", 22, TEAL_TITLE);
        heading.setTypeface(null, 1);
        heading.setGravity(Gravity.CENTER);
        android.widget.FrameLayout.LayoutParams headLp =
                new android.widget.FrameLayout.LayoutParams(-2, dp(52), Gravity.CENTER);
        title.addView(heading, headLp);
        TextView badge = label("⇅ 同步中", 12, TEAL_TITLE);
        badge.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        badge.setPadding(dp(6), 0, dp(10), 0);
        android.widget.FrameLayout.LayoutParams badgeLp =
                new android.widget.FrameLayout.LayoutParams(-2, dp(52), Gravity.END | Gravity.CENTER_VERTICAL);
        title.addView(badge, badgeLp);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, dp(56));
        titleLp.bottomMargin = dp(12);
        content.addView(title, titleLp);

        // ---------- 队列说明条（增强玻璃） ----------
        TextView queueTip = label("任务队列 · 新任务自动排队接续 · 通知栏 / ROM 查询 / 本页三方实时同步",
                12, TEAL_SOFT);
        queueTip.setPadding(dp(14), dp(12), dp(14), dp(12));
        queueTip.setBackground(enhancedGlass(dp(18)));
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(-1, -2);
        tipLp.bottomMargin = dp(12);
        content.addView(queueTip, tipLp);

        // ---------- 当前任务卡 ----------
        TextView section = label("当前任务", 15.5f, TEAL_TITLE);
        section.setTypeface(null, 1);
        content.addView(section, new LinearLayout.LayoutParams(-1, dp(32)));
        activeHost = new LinearLayout(this);
        activeHost.setOrientation(LinearLayout.VERTICAL);
        content.addView(activeHost, new LinearLayout.LayoutParams(-1, -2));

        // ---------- 下载历史 ----------
        LinearLayout historyHead = new LinearLayout(this);
        historyHead.setOrientation(LinearLayout.HORIZONTAL);
        historyHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView historyTitle = label("下载历史", 15.5f, TEAL_TITLE);
        historyTitle.setTypeface(null, 1);
        historyHead.addView(historyTitle, new LinearLayout.LayoutParams(0, dp(40), 1));
        historySummary = label("", 12, TEAL_SOFT);
        historySummary.setGravity(Gravity.CENTER_VERTICAL);
        historyHead.addView(historySummary, new LinearLayout.LayoutParams(0, dp(38), 1));
        Button clear = smallGlassButton("清空");
        // v3.8.2 修复：「清空」两字完整显示 —— 按钮加宽加高 + 左右留白
        clear.setPadding(dp(14), 0, dp(14), 0);
        clear.setOnClickListener(v -> {
            Haptics.perform(v);
            if (history.isEmpty()) return;
            new android.app.AlertDialog.Builder(this)
                    .setTitle("清空下载历史")
                    .setMessage("确定删除全部 " + history.size() + " 条下载记录？（不会删除已下载的 ROM 文件）")
                    .setPositiveButton("清空", (d, w) -> {
                        Haptics.perform(v);
                        history.clear();
                        saveHistory();
                        renderHistory();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(dp(68), dp(36));
        historyHead.addView(clear, clearLp);
        LinearLayout.LayoutParams historyHeadLp = new LinearLayout.LayoutParams(-1, -2);
        historyHeadLp.topMargin = dp(14);
        content.addView(historyHead, historyHeadLp);

        historyHost = new LinearLayout(this);
        historyHost.setOrientation(LinearLayout.VERTICAL);
        content.addView(historyHost, new LinearLayout.LayoutParams(-1, -2));

        loadHistory();
        renderActive();
        renderHistory();

        // 实时同步：接收下载服务广播（与通知栏同源）
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String s = intent.getStringExtra(DownloadService.EXTRA_STATE);
                long d = intent.getLongExtra(DownloadService.EXTRA_DONE, -1);
                long t = intent.getLongExtra(DownloadService.EXTRA_TOTAL, -1);
                long sp = intent.getLongExtra(DownloadService.EXTRA_SPEED, -1);
                long e = intent.getLongExtra(DownloadService.EXTRA_ETA, -1);
                onDownloadUpdate(s, d, t, sp, e);
            }
        };
        // Android 14+（targetSdk 34+）必须指定导出标志，否则 SecurityException 崩溃（3.8.0 崩溃根因）
        IntentFilter filter = new IntentFilter(DownloadService.ACTION_UPDATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
        // 进入页面立即拉取当前状态
        startService(new Intent(this, DownloadService.class).setAction(DownloadService.ACTION_QUERY));
    }

    @Override protected void onResume() {
        super.onResume();
        // 回到页面时刷新历史（可能在其他页完成下载）
        loadHistory();
        renderHistory();
        startService(new Intent(this, DownloadService.class).setAction(DownloadService.ACTION_QUERY));
    }

    @Override protected void onDestroy() {
        try { if (receiver != null) unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) { }
        super.onDestroy();
    }

    // ---------- 实时状态（三方同步：本页 / 通知栏 / ROM 查询页） ----------

    private void onDownloadUpdate(String state, long d, long t, long sp, long e) {
        // v3.8.2 修复：任务取消 → 当前任务卡立即清空收起 + 历史即时刷新（此前一直挂在页面）
        if (state != null && state.startsWith("下载已取消")) {
            activeName = "";
            activeOutput = "";
            stateText = "";
            taskPaused = false;
            done = -1;
            total = -1;
            speed = -1;
            eta = -1;
            loadHistory();
            renderActive();
            renderHistory();
            return;
        }
        if (state != null) stateText = state;
        if (d >= 0) done = d;
        if (t >= 0) total = t;
        if (sp != -1) speed = sp;
        if (e != -1) eta = e;
        if (state != null) {
            taskPaused = state.contains("暂停");
            // 状态行里带文件名（正在下载: xxx.zip）
            if (state.startsWith("正在下载: ")) activeName = state.substring("正在下载: ".length());
            else if (state.startsWith("已暂停: ")) activeName = state.substring("已暂停: ".length());
        }
        // 从持久化状态读取输出路径；prefs 已清空 = 任务已结束，重置残留路径（v3.8.2）
        String saved = getSharedPreferences("rom_download", MODE_PRIVATE).getString("output", "");
        if (!saved.isEmpty()) activeOutput = saved;
        else if (!"下载完成".equals(state)) activeOutput = "";
        renderActive();
        if ("下载完成".equals(state) || state != null && state.startsWith("下载完成")) {
            loadHistory();
            renderHistory();
        }
    }

    // ---------- 当前任务卡渲染 ----------

    private void renderActive() {
        activeHost.removeAllViews();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(enhancedGlass(dp(24)));
        card.setElevation(dp(8));

        boolean hasTask = !activeOutput.isEmpty() || stateText.contains("下载")
                || (done >= 0 && total > 0 && !"下载已取消".equals(stateText));

        if (!hasTask) {
            // 空态玻璃卡
            TextView empty = label("当前没有下载任务", 15.5f, TEAL_DARK);
            empty.setGravity(Gravity.CENTER);
            empty.setTypeface(null, 1);
            card.addView(empty, new LinearLayout.LayoutParams(-1, dp(34)));
            TextView hint = label("去 ROM 更新中心选择机型开始下载，任务进度会实时同步到这里", 12.5f, TEAL_SOFT);
            hint.setGravity(Gravity.CENTER);
            card.addView(hint, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(12);
            activeHost.addView(card, lp);
            return;
        }

        // 文件名
        TextView name = label(activeName.isEmpty() ? "ROM 下载任务" : activeName, 15.5f, TEAL_DARK);
        name.setTypeface(null, 1);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        card.addView(name, new LinearLayout.LayoutParams(-1, dp(32)));

        // 状态 + 百分比
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        stateLabel = label(stateText.isEmpty() ? "正在连接下载节点..." : stateText, 12, TEAL_SOFT);
        stateLabel.setMaxLines(1);
        stateLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(stateLabel, new LinearLayout.LayoutParams(0, dp(42), 1));
        percentLabel = label(percentText(), 17, TEAL_TITLE);
        percentLabel.setTypeface(null, 1);
        percentLabel.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        head.addView(percentLabel, new LinearLayout.LayoutParams(dp(72), dp(42)));
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));

        // 进度条
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(percentValue());
        progressBar.setProgressDrawable(getDrawable(R.drawable.progress_bar));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, dp(16));
        barLp.setMargins(0, dp(2), 0, dp(6));
        card.addView(progressBar, barLp);

        // 大小 / 速度 / ETA
        bytesLabel = label(bytesText(), 12, TEAL_SOFT);
        bytesLabel.setMaxLines(1);
        card.addView(bytesLabel, new LinearLayout.LayoutParams(-1, dp(24)));

        // 保存路径
        if (!activeOutput.isEmpty()) {
            TextView path = label("保存到: " + activeOutput, 11, TEAL_FAINT);
            path.setMaxLines(2);
            path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            LinearLayout.LayoutParams pathLp = new LinearLayout.LayoutParams(-1, -2);
            pathLp.bottomMargin = dp(4);
            card.addView(path, pathLp);
        }

        // 操作按钮：暂停/继续 + 取消
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        pauseResumeButton = actionButton(taskPaused ? "▶ 继续" : "⏸ 暂停", 0xFF4472DE, 0xFF6C9BF2);
        pauseResumeButton.setOnClickListener(v -> {
            Haptics.perform(v);
            startService(new Intent(this, DownloadService.class)
                    .setAction(taskPaused ? DownloadService.ACTION_RESUME : DownloadService.ACTION_PAUSE));
        });
        actions.addView(pauseResumeButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        Button cancel = actionButton("✕ 取消", 0xFFC0392B, 0xFFE07A6B);
        cancel.setOnClickListener(v -> {
            Haptics.perform(v);
            new android.app.AlertDialog.Builder(this)
                    .setTitle("取消下载")
                    .setMessage("确定取消当前下载任务？已下载的部分文件将被删除。")
                    .setPositiveButton("取消下载", (d, w) -> {
                        Haptics.perform(v);
                        startService(new Intent(this, DownloadService.class)
                                .setAction(DownloadService.ACTION_CANCEL));
                    })
                    .setNegativeButton("返回", null)
                    .show();
        });
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, dp(44), 1);
        cancelLp.leftMargin = dp(10);
        actions.addView(cancel, cancelLp);
        card.addView(actions, new LinearLayout.LayoutParams(-1, dp(44)));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(12);
        activeHost.addView(card, lp);
    }

    private int percentValue() {
        return total > 0 && done >= 0 ? (int) Math.max(0, Math.min(100, done * 100 / total)) : 0;
    }

    private String percentText() {
        return total > 0 && done >= 0 ? percentValue() + "%" : "…";
    }

    private String bytesText() {
        StringBuilder sb = new StringBuilder();
        if (done >= 0 && total > 0) {
            sb.append(Aria2Downloader.formatBytes(done))
                    .append(" / ").append(Aria2Downloader.formatBytes(total));
        } else if (done >= 0) {
            sb.append("已下载 ").append(Aria2Downloader.formatBytes(done));
        }
        if (speed > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(Aria2Downloader.formatBytes(speed)).append("/s");
        }
        if (eta > 0 && !taskPaused) {
            sb.append(" · 剩余 ").append(Aria2Downloader.formatEta(eta));
        }
        return sb.length() > 0 ? sb.toString() : "正在获取下载信息...";
    }

    // ---------- 下载历史 ----------

    private void loadHistory() {
        history.clear();
        try {
            String raw = getSharedPreferences(DownloadService.HISTORY_PREFS, MODE_PRIVATE).getString("items", "");
            if (raw == null || raw.isEmpty()) return;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) history.add(arr.getJSONObject(i));
        } catch (Exception ignored) { }
    }

    private void saveHistory() {
        try {
            JSONArray arr = new JSONArray();
            for (JSONObject e : history) arr.put(e);
            getSharedPreferences(DownloadService.HISTORY_PREFS, MODE_PRIVATE).edit()
                    .putString("items", arr.toString()).apply();
        } catch (Exception ignored) { }
    }

    private void renderHistory() {
        if (historySummary != null) historySummary.setText(history.isEmpty() ? "" : "共 " + history.size() + " 条");
        historyHost.removeAllViews();
        if (history.isEmpty()) {
            TextView empty = label("暂无下载历史 · 下载完成后可在此一键打开文件位置", 12.5f, TEAL_SOFT);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(14), 0, dp(14));
            historyHost.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (int i = 0; i < history.size(); i++) {
            final JSONObject entry = history.get(i);
            // v3.8.2 修复：卡片之间的间距（此前未应用 LayoutParams，圆角框挤在一起）
            View cardView = historyCard(entry, i);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
            cardLp.topMargin = dp(12);
            cardLp.bottomMargin = i == history.size() - 1 ? dp(6) : 0;
            historyHost.addView(cardView, cardLp);
        }
    }

    private View historyCard(JSONObject entry, int index) {
        String status = entry.optString("status", "done");
        String name = entry.optString("name", "");
        long size = entry.optLong("size", 0);
        long time = entry.optLong("time", 0);
        String path = entry.optString("path", "");

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(enhancedGlass(dp(20)));
        card.setElevation(dp(5));

        // 第一行：状态徽章 + 时间
        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        TextView badgeView = new TextView(this);
        badgeView.setTextSize(11);
        badgeView.setTypeface(Typeface.DEFAULT_BOLD);
        badgeView.setGravity(Gravity.CENTER);
        badgeView.setPadding(dp(10), 0, dp(10), 0);
        int badgeBg, badgeFg;
        if ("done".equals(status)) {
            badgeBg = 0x262FB47C; badgeFg = 0xff1E8A60;
            badgeView.setText("✓ 已完成");
        } else if ("failed".equals(status)) {
            badgeBg = 0x26C0392B; badgeFg = 0xffB03024;
            badgeView.setText("✕ 失败");
        } else {
            badgeBg = 0x268A94A6; badgeFg = 0xff667383;
            badgeView.setText("已取消");
        }
        GradientDrawable badgeBgDrawable = new GradientDrawable();
        badgeBgDrawable.setCornerRadius(dp(11));
        badgeBgDrawable.setColor(badgeBg);
        badgeBgDrawable.setStroke(Math.max(1, dp(1)), 0x33000000);
        badgeView.setBackground(badgeBgDrawable);
        badgeView.setTextColor(badgeFg);
        meta.addView(badgeView, new LinearLayout.LayoutParams(-2, dp(22)));
        TextView timeView = label(time > 0 ? FMT.format(new Date(time)) : "", 11, TEAL_FAINT);
        timeView.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        meta.addView(timeView, new LinearLayout.LayoutParams(0, dp(24), 1));
        card.addView(meta, new LinearLayout.LayoutParams(-1, -2));

        // 文件名
        TextView nameView = label(name.isEmpty() ? "ROM 文件" : name, 14, TEAL_DARK);
        nameView.setTypeface(null, 1);
        nameView.setMaxLines(2);
        nameView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(-1, -2);
        nameLp.topMargin = dp(6);
        card.addView(nameView, nameLp);

        // 大小 + 路径
        String sizeText = size > 0 ? Aria2Downloader.formatBytes(size) : "";
        TextView detail = label((sizeText.isEmpty() ? "" : sizeText + " · ")
                + (path.isEmpty() ? "" : new File(path).getParent()), 11, TEAL_FAINT);
        detail.setMaxLines(1);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(-1, -2);
        detailLp.topMargin = dp(2);
        card.addView(detail, detailLp);

        // 操作：打开文件位置（仅已完成且文件存在）+ 删除记录
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        boolean fileExists = !path.isEmpty() && new File(path).isFile();
        if (fileExists) {
            Button locate = actionButton("📂 打开文件位置", 0xFF1E8A60, 0xFF2FB47C);
            locate.setOnClickListener(v -> {
                Haptics.perform(v);
                openFileLocation(path);
            });
            actions.addView(locate, new LinearLayout.LayoutParams(0, dp(40), 1.6f));
        }
        Button remove = actionButton("🗑 删除记录", 0xFF667383, 0xFF9FB0D4);
        remove.setOnClickListener(v -> {
            Haptics.perform(v);
            history.remove(entry);
            saveHistory();
            renderHistory();
        });
        LinearLayout.LayoutParams removeLp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        removeLp.leftMargin = dp(8);
        actions.addView(remove, removeLp);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, dp(40));
        actionsLp.topMargin = dp(10);
        card.addView(actions, actionsLp);
        return card;
    }

    /** 跳转到文件所在目录（DocumentsUI 目录定位），失败时复制路径到剪贴板 */
    private void openFileLocation(String path) {
        File file = new File(path);
        File dir = file.getParentFile();
        try {
            String base = "/storage/emulated/0/";
            String abs = dir != null ? dir.getAbsolutePath() : base + "Download/DsuManager";
            String docId = "primary:" + abs.replace(base, "");
            Uri uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "vnd.android.document/directory");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("rom_path", path));
            Toast.makeText(this, "系统文件管理器不支持定位，已复制文件路径", Toast.LENGTH_LONG).show();
        }
    }

    // ---------- 绘制工具 ----------

    private LiquidGlassPanel glass() {
        LiquidGlassPanel panel = new LiquidGlassPanel(this);
        return panel;
    }

    /**
     * 增强版液态玻璃框（三层，与品牌玻璃卡同构）：
     *  1. 底层：白→浅青 对角渐变（通透玻璃底）
     *  2. 中层：白色高光描边（玻璃边缘反光）
     *  3. 顶层：顶部横向高光带（玻璃表面反光）
     */
    private android.graphics.drawable.LayerDrawable enhancedGlass(int radiusPx) {
        GradientDrawable base = new GradientDrawable();
        base.setOrientation(GradientDrawable.Orientation.TL_BR);
        base.setColors(new int[]{0xF2FFFFFF, 0xE6E3F6FA, 0xD9D8ECF4});
        base.setCornerRadius(radiusPx);

        GradientDrawable strokeLayer = new GradientDrawable();
        strokeLayer.setColor(0x00000000);
        strokeLayer.setStroke(Math.max(1, dp(2)), 0xFFFFFFFF);
        strokeLayer.setCornerRadius(Math.max(0, radiusPx - Math.max(1, dp(1))));

        GradientDrawable highlight = new GradientDrawable();
        highlight.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
        highlight.setColors(new int[]{0x00FFFFFF, 0x73FFFFFF, 0x00FFFFFF});
        highlight.setCornerRadius(Math.max(0, radiusPx - dp(4)));

        android.graphics.drawable.LayerDrawable layer =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{base, strokeLayer, highlight});
        layer.setLayerInset(1, 1, 1, 1, 1);
        layer.setLayerInset(2, dp(3), dp(2), dp(3), (int) (radiusPx * 1.35f));
        return layer;
    }

    private TextView label(String text, float size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(0, 1.05f);
        return view;
    }

    private Button glassButton(String text, float size) {
        Button button = new Button(this, null, 0);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(size);
        button.setTextColor(TEAL_TITLE);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setIncludeFontPadding(false);
        button.setPadding(0, 0, 0, 0);
        button.setBackgroundResource(R.drawable.liquid_glass_back_button);
        button.setStateListAnimator(null);
        return button;
    }

    private Button smallGlassButton(String text) {
        Button button = glassButton(text, 12);
        GradientDrawable bg = new GradientDrawable();
        bg.setOrientation(GradientDrawable.Orientation.TL_BR);
        bg.setColors(new int[]{0xCCFFFFFF, 0x99E2E8F0});
        bg.setCornerRadius(dp(16));
        bg.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
        button.setBackground(bg);
        return button;
    }

    /** 操作按钮：对角渐变 + 白色高光描边（液态玻璃） */
    private Button actionButton(String text, int c1, int c2) {
        Button button = new Button(this, null, 0);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13.5f);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(Color.WHITE);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setIncludeFontPadding(false);
        button.setPadding(0, 0, 0, 0);
        button.setStateListAnimator(null);
        GradientDrawable bg = new GradientDrawable();
        bg.setOrientation(GradientDrawable.Orientation.TL_BR);
        bg.setColors(new int[]{c2, c1});
        bg.setCornerRadius(dp(20));
        bg.setStroke(Math.max(1, dp(1)), 0x59FFFFFF);
        button.setBackground(bg);
        button.setElevation(dp(4));
        return button;
    }

    private int dp(int value) {
        return (int) (getResources().getDisplayMetrics().density * value + 0.5f);
    }
}
