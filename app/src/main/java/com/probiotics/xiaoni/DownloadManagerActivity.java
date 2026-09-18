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

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(28));
        scroll.addView(content);
        setContentView(scroll);
        scroll.setBackgroundResource(R.drawable.liquid_backdrop);

        // ---------- 标题栏（玻璃） ----------
        LiquidGlassPanel title = glass();
        title.setOrientation(LinearLayout.HORIZONTAL);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(8), 0, dp(8), 0);
        Button back = glassButton("<", 20);
        back.setOnClickListener(v -> {
            Haptics.perform(v);
            finish();
            overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out);
        });
        title.addView(back, new LinearLayout.LayoutParams(dp(42), dp(48)));
        TextView heading = label("下载管理", 23, 0xff1a3356);
        heading.setTypeface(null, 1);
        title.addView(heading, new LinearLayout.LayoutParams(0, dp(52), 1));
        TextView badge = label("⇅ 同步中", 12, 0xff3b82f6);
        badge.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        badge.setPadding(dp(10), 0, dp(10), 0);
        title.addView(badge, new LinearLayout.LayoutParams(-2, dp(52)));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, dp(56));
        titleLp.bottomMargin = dp(12);
        content.addView(title, titleLp);

        // ---------- 队列说明条（玻璃） ----------
        TextView queueTip = label("任务队列 · 新任务自动排队接续 · 通知栏 / ROM 查询 / 本页三方实时同步",
                12, 0xff596579);
        queueTip.setPadding(dp(12), dp(10), dp(12), dp(10));
        queueTip.setBackgroundResource(R.drawable.liquid_glass_panel);
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(-1, -2);
        tipLp.bottomMargin = dp(12);
        content.addView(queueTip, tipLp);

        // ---------- 当前任务卡 ----------
        TextView section = label("当前任务", 15, 0xff1a3356);
        section.setTypeface(null, 1);
        content.addView(section, new LinearLayout.LayoutParams(-1, dp(30)));
        activeHost = new LinearLayout(this);
        activeHost.setOrientation(LinearLayout.VERTICAL);
        content.addView(activeHost, new LinearLayout.LayoutParams(-1, -2));

        // ---------- 下载历史 ----------
        LinearLayout historyHead = new LinearLayout(this);
        historyHead.setOrientation(LinearLayout.HORIZONTAL);
        historyHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView historyTitle = label("下载历史", 15, 0xff1a3356);
        historyTitle.setTypeface(null, 1);
        historyHead.addView(historyTitle, new LinearLayout.LayoutParams(0, dp(38), 1));
        historySummary = label("", 12, 0xff596579);
        historySummary.setGravity(Gravity.CENTER_VERTICAL);
        historyHead.addView(historySummary, new LinearLayout.LayoutParams(0, dp(38), 1));
        Button clear = smallGlassButton("清空");
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
        historyHead.addView(clear, new LinearLayout.LayoutParams(-2, dp(32)));
        content.addView(historyHead, new LinearLayout.LayoutParams(-1, -2));

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
        registerReceiver(receiver, new IntentFilter(DownloadService.ACTION_UPDATE));
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
        if (receiver != null) unregisterReceiver(receiver);
        super.onDestroy();
    }

    // ---------- 实时状态（三方同步：本页 / 通知栏 / ROM 查询页） ----------

    private void onDownloadUpdate(String state, long d, long t, long sp, long e) {
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
        // 从持久化状态读取输出路径
        String saved = getSharedPreferences("rom_download", MODE_PRIVATE).getString("output", "");
        if (!saved.isEmpty()) activeOutput = saved;
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
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackgroundResource(R.drawable.liquid_glass_panel);
        card.setElevation(dp(6));

        boolean hasTask = !activeOutput.isEmpty() || stateText.contains("下载")
                || (done >= 0 && total > 0 && !"下载已取消".equals(stateText));

        if (!hasTask) {
            // 空态玻璃卡
            TextView empty = label("当前没有下载任务", 15, 0xff20375b);
            empty.setGravity(Gravity.CENTER);
            empty.setTypeface(null, 1);
            card.addView(empty, new LinearLayout.LayoutParams(-1, dp(32)));
            TextView hint = label("去 ROM 更新中心选择机型开始下载，任务进度会实时同步到这里", 12, 0xff596579);
            hint.setGravity(Gravity.CENTER);
            card.addView(hint, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(12);
            activeHost.addView(card, lp);
            return;
        }

        // 文件名
        TextView name = label(activeName.isEmpty() ? "ROM 下载任务" : activeName, 15.5f, 0xff20375b);
        name.setTypeface(null, 1);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        card.addView(name, new LinearLayout.LayoutParams(-1, dp(30)));

        // 状态 + 百分比
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        stateLabel = label(stateText.isEmpty() ? "正在连接下载节点..." : stateText, 12, 0xff596579);
        stateLabel.setMaxLines(1);
        stateLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(stateLabel, new LinearLayout.LayoutParams(0, dp(40), 1));
        percentLabel = label(percentText(), 16, 0xff20375b);
        percentLabel.setTypeface(null, 1);
        percentLabel.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        head.addView(percentLabel, new LinearLayout.LayoutParams(dp(66), dp(40)));
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));

        // 进度条
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(percentValue());
        progressBar.setProgressDrawable(getDrawable(R.drawable.progress_bar));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, dp(14));
        barLp.setMargins(0, dp(2), 0, dp(6));
        card.addView(progressBar, barLp);

        // 大小 / 速度 / ETA
        bytesLabel = label(bytesText(), 12, 0xff596579);
        bytesLabel.setMaxLines(1);
        card.addView(bytesLabel, new LinearLayout.LayoutParams(-1, dp(22)));

        // 保存路径
        if (!activeOutput.isEmpty()) {
            TextView path = label("保存到: " + activeOutput, 11, 0xff8a94a6);
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
            TextView empty = label("暂无下载历史 · 下载完成后可在此一键打开文件位置", 12.5f, 0xff8a94a6);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(14), 0, dp(14));
            historyHost.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (int i = 0; i < history.size(); i++) {
            final JSONObject entry = history.get(i);
            historyHost.addView(historyCard(entry, i));
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
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackgroundResource(R.drawable.liquid_glass_panel);
        card.setElevation(dp(4));

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
        TextView timeView = label(time > 0 ? FMT.format(new Date(time)) : "", 11, 0xff8a94a6);
        timeView.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        meta.addView(timeView, new LinearLayout.LayoutParams(0, dp(22), 1));
        card.addView(meta, new LinearLayout.LayoutParams(-1, -2));

        // 文件名
        TextView nameView = label(name.isEmpty() ? "ROM 文件" : name, 14, 0xff20375b);
        nameView.setTypeface(null, 1);
        nameView.setMaxLines(2);
        nameView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(-1, -2);
        nameLp.topMargin = dp(6);
        card.addView(nameView, nameLp);

        // 大小 + 路径
        String sizeText = size > 0 ? Aria2Downloader.formatBytes(size) : "";
        TextView detail = label((sizeText.isEmpty() ? "" : sizeText + " · ")
                + (path.isEmpty() ? "" : new File(path).getParent()), 11, 0xff8a94a6);
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

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
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
        button.setTextColor(0xff1a3356);
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
