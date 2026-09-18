package com.probiotics.xiaoni;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v3.8.8 页面级多任务下载框（小米 / vivo / OPPO 查询页共用）。
 *
 * 页面下载区完全按「本页任务」渲染：
 *  - 本页发起的每个任务一张独立下载卡（进度 / 速度 / 剩余时间 / 暂停 / 取消各自独立）；
 *  - 其他页面（小米 / vivo / OPPO）发起的任务不在本页展开，仅显示折叠条
 *    「其他页面还有 N 个任务在下载 · 点击查看」，点击跳转下载管理页；
 *  - 数据源：DownloadService 的 ACTION_UPDATE 广播（带 EXTRA_PAGE / EXTRA_TASK_ID）
 *    与 DownloadTaskStore 持久化（回页对账 / 进程重启恢复）。
 */
public final class PageTaskCards {

    private final Activity activity;
    private final String page;                 // 本页 simpleName（任务过滤标记）
    private LinearLayout container;            // 任务卡容器（页面下载区）
    private ScrollView scroller;               // 新任务卡出现时滚动到可见（可空）
    private final Map<String, Card> cards = new LinkedHashMap<>();   // taskId → 卡片
    private LiquidGlassPanel othersBar;        // 其他页面任务折叠条

    public PageTaskCards(Activity activity, String page) {
        this.activity = activity;
        this.page = page;
    }

    /** 绑定任务卡容器（页面 buildUi 时在原单任务下载框的位置创建一个垂直 LinearLayout） */
    public void attach(LinearLayout taskContainer, ScrollView scroll) {
        this.container = taskContainer;
        this.scroller = scroll;
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + .5f);
    }

    private TextView label(String value, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setIncludeFontPadding(true);
        return view;
    }

    // ---------- 指令下发 ----------

    /** 发起新任务（ACTION_START，带本页标记；并行满 3 个时服务端自动排队「待下载」） */
    public void start(String address, File output, String pkg, int threads, long chunkMB) {
        if (address == null || output == null) return;
        Intent intent = new Intent(activity, DownloadService.class)
                .setAction(DownloadService.ACTION_START)
                .putExtra(DownloadService.EXTRA_ADDRESS, address)
                .putExtra(DownloadService.EXTRA_OUTPUT, output.getAbsolutePath())
                .putExtra(DownloadService.EXTRA_PACKAGE, pkg == null ? "下载" : pkg)
                .putExtra(DownloadService.EXTRA_PAGE, page)
                .putExtra("download_threads", threads)
                .putExtra("download_chunk_mb", chunkMB);
        if (Build.VERSION.SDK_INT >= 26) activity.startForegroundService(intent);
        else activity.startService(intent);
        // 卡片先按本地状态显示（服务广播到达后自动刷新为排队/下载中）
        addCard(output.getAbsolutePath(), output, pkg);
    }

    /** 按任务 ID 下发 暂停 / 继续 / 取消 / 重试 */
    public void command(String action, String taskId) {
        if (taskId == null) return;
        Intent intent = new Intent(activity, DownloadService.class)
                .setAction(action)
                .putExtra(DownloadService.EXTRA_TASK_ID, taskId)
                .putExtra(DownloadService.EXTRA_PAGE, page);
        try { activity.startService(intent); } catch (Exception ignored) { }
    }

    /** 查询全部任务状态（页面注册广播后调用，服务端全量回报各页面任务） */
    public void queryService() {
        try {
            activity.startService(new Intent(activity, DownloadService.class)
                    .setAction(DownloadService.ACTION_QUERY));
        } catch (Exception ignored) { }
    }

    // ---------- 广播处理（只处理本页任务，其他页只刷新折叠条计数） ----------

    public void onBroadcast(Intent intent) {
        if (intent == null || !DownloadService.ACTION_UPDATE.equals(intent.getAction())) return;
        String taskPage = intent.getStringExtra(DownloadService.EXTRA_PAGE);
        String taskId = intent.getStringExtra(DownloadService.EXTRA_TASK_ID);
        if (taskId == null) return;
        if (page.equals(taskPage)) {
            String state = intent.getStringExtra(DownloadService.EXTRA_STATE);
            long done = intent.getLongExtra(DownloadService.EXTRA_DONE, -1);
            long total = intent.getLongExtra(DownloadService.EXTRA_TOTAL, -1);
            Card card = cards.get(taskId);
            if (card == null) {
                card = addCard(taskId, new File(taskId), null);
                if (card == null) return;
            }
            updateCard(card, state, done, total,
                    intent.getLongExtra(DownloadService.EXTRA_SPEED, -1),
                    intent.getLongExtra(DownloadService.EXTRA_ETA, -1));
        }
        // 任何任务变化都可能改变其他页面任务计数 → 刷新折叠条
        renderOthersBar(DownloadTaskStore.countOthers(activity, page));
    }

    /** 回页 / 启动对账：从持久化全量渲染本页任务 + 折叠条 */
    public void resync() {
        List<DownloadTaskStore.Item> items = DownloadTaskStore.forPage(activity, page);
        // 移除已结束（不在 store 中）的卡片
        Map<String, DownloadTaskStore.Item> live = new HashMap<>();
        for (DownloadTaskStore.Item item : items) live.put(item.id, item);
        for (String id : new java.util.ArrayList<>(cards.keySet())) {
            if (!live.containsKey(id)) removeCard(id);
        }
        for (DownloadTaskStore.Item item : items) {
            Card card = cards.get(item.id);
            if (card == null) card = addCard(item.id, item.output, item.pkg);
            if (card != null) updateCard(card,
                    item.isPending() ? "排队等待中" : item.message,
                    item.done, item.total, item.speed, item.eta);
        }
        renderOthersBar(DownloadTaskStore.countOthers(activity, page));
    }

    // ---------- 卡片渲染 ----------

    private Card addCard(String taskId, File output, String pkg) {
        if (container == null || cards.containsKey(taskId)) return cards.get(taskId);
        Card card = new Card(taskId);
        card.panel = new LiquidGlassPanel(activity);
        card.panel.setOrientation(LinearLayout.VERTICAL);
        card.panel.setPadding(dp(16), dp(14), dp(16), dp(14));
        boolean first = cards.isEmpty();
        card.panel.setOnClickListener(v -> {
            Haptics.perform(v);
            activity.startActivity(new Intent(activity, DownloadManagerActivity.class));
        });

        TextView caption = label((pkg == null || pkg.isEmpty() ? "下载任务" : pkg) + "（本页）", 12, 0xff596579);
        card.panel.addView(caption, new LinearLayout.LayoutParams(-1, -2));
        card.title = label("正在下载: " + output.getName(), 13, 0xff20375b);
        card.title.setMaxLines(1);
        card.title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        card.panel.addView(card.title, new LinearLayout.LayoutParams(-1, dp(28)));

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        card.status = label("正在准备下载", 13, 0xff596579);
        card.status.setMaxLines(1);
        header.addView(card.status, new LinearLayout.LayoutParams(0, -2, 1));
        card.percent = label("0%", 15, 0xff20375b);
        card.percent.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        card.percent.setTypeface(null, 1);
        LinearLayout.LayoutParams percentLp = new LinearLayout.LayoutParams(dp(64), dp(42));
        percentLp.setMarginStart(dp(6));
        header.addView(card.percent, percentLp);
        card.panel.addView(header, new LinearLayout.LayoutParams(-1, -2));

        card.progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        card.progress.setMax(100);
        card.progress.setProgress(0);
        card.progress.setProgressDrawable(
                activity.getDrawable(R.drawable.progress_bar));
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(-1, dp(14));
        progressLp.setMargins(0, dp(2), 0, dp(6));
        card.panel.addView(card.progress, progressLp);

        card.bytes = label("已下载 0 B / 总大小获取中", 12, 0xff596579);
        card.bytes.setMaxLines(1);
        card.bytes.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.panel.addView(card.bytes, new LinearLayout.LayoutParams(-1, dp(24)));

        card.path = label("保存到: " + output.getAbsolutePath(), 11, 0xff8a94a6);
        card.path.setMaxLines(2);
        card.path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams pathLp = new LinearLayout.LayoutParams(-1, -2);
        pathLp.setMargins(0, dp(2), 0, dp(4));
        card.panel.addView(card.path, pathLp);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        card.pauseButton = actionButton("暂停");
        card.pauseButton.setOnClickListener(v -> {
            Haptics.perform(v);
            String text = card.pauseButton.getText().toString();
            if ("重试".equals(text) || "继续".equals(text)) {
                card.paused = false;
                card.pauseButton.setText("暂停");
                card.status.setText("正在继续下载");
                command(DownloadService.ACTION_RESUME, taskId);
                return;
            }
            card.paused = true;
            card.pauseButton.setText("继续");
            card.status.setText("正在暂停...");
            command(DownloadService.ACTION_PAUSE, taskId);
        });
        actions.addView(card.pauseButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        card.cancelButton = actionButton("取消");
        card.cancelButton.setOnClickListener(v -> {
            Haptics.perform(v);
            String text = card.cancelButton.getText().toString();
            if ("关闭".equals(text)) {
                removeCard(taskId);
                return;
            }
            removeCard(taskId);
            command(DownloadService.ACTION_CANCEL, taskId);
        });
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, dp(44), 1);
        cancelLp.setMargins(dp(10), 0, 0, 0);
        actions.addView(card.cancelButton, cancelLp);
        card.panel.addView(actions, new LinearLayout.LayoutParams(-1, dp(44)));

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.setMargins(0, dp(first ? 12 : 12), 0, dp(first ? 16 : 12));
        container.addView(card.panel, cardLp);
        cards.put(taskId, card);
        if (scroller != null) {
            scroller.post(() -> scroller.smoothScrollTo(0, Math.max(0, card.panel.getTop() - dp(8))));
        }
        return card;
    }

    private Button actionButton(String text) {
        Button button = new Button(activity);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(dp(4), 0, dp(4), 0);
        button.setBackgroundResource(R.drawable.liquid_glass_panel);
        return button;
    }

    /** 按广播 / store 快照刷新单张卡片 */
    private void updateCard(Card card, String state, long done, long total, long speed, long eta) {
        if (speed > 0) card.speed = speed;
        if (eta >= 0) card.eta = eta;
        boolean finished = false, failed = false;
        if (state != null) {
            if (state.startsWith("正在下载")) {
                card.title.setText("正在下载: " + fileName(card.id));
                card.paused = false;
                card.pauseButton.setText("暂停");
            } else if (state.startsWith("已暂停")) {
                card.paused = true;
                card.pauseButton.setText("继续");
                card.status.setText("已暂停，点击继续可断点续传");
            } else if (state.startsWith("排队等待中")) {
                card.status.setText(state);
            } else if ("下载完成".equals(state)) {
                finished = true;
            } else if (state.startsWith("下载失败") || state.startsWith("下载地址无效")) {
                failed = true;
                card.title.setText("下载失败: " + fileName(card.id));
                card.status.setText(state);
                card.pauseButton.setText("重试");
                card.cancelButton.setText("关闭");
            }
        }
        if (done >= 0 || total > 0) {
            int percent = total > 0 && done >= 0 ? (int) (done * 100 / total) : 0;
            card.progress.setProgress(finished ? 100 : percent);
            card.percent.setText((finished ? 100 : percent) + "%");
            StringBuilder bytes = new StringBuilder();
            bytes.append("已下载 ").append(formatBytes(Math.max(0, done)));
            if (total > 0) bytes.append(" / 总大小 ").append(formatBytes(total));
            else bytes.append(" / 总大小获取中");
            if (card.speed > 0 && !finished && !failed && !card.paused) {
                bytes.append(" · 速度 ").append(formatBytes(card.speed)).append("/s");
                if (card.eta > 0 && total > done) {
                    bytes.append(" · 剩余 ").append(formatEta(card.eta));
                }
            }
            card.bytes.setText(bytes.toString());
        }
        if (!finished && !failed && state != null
                && !state.startsWith("已暂停") && !state.startsWith("排队等待中")
                && !state.startsWith("正在下载") && !state.startsWith("正在准备")) {
            card.status.setText(state);
        }
        if (!finished && !failed && !card.paused && card.speed > 0 && total > done && done >= 0) {
            card.status.setText("预计剩余 " + formatEta(card.eta > 0 ? card.eta
                    : (card.speed > 0 ? (total - done) / card.speed : -1)));
        }
        if (finished) {
            card.title.setText("下载完成: " + fileName(card.id));
            card.status.setText("下载完成 · 已保存到 Download/DsuManager");
            card.progress.setProgress(100);
            card.percent.setText("100%");
            card.pauseButton.setVisibility(View.GONE);
            card.cancelButton.setText("关闭");
        }
    }

    private String fileName(String taskId) {
        String name = taskId;
        int slash = name.lastIndexOf('/');
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    private void removeCard(String taskId) {
        Card card = cards.remove(taskId);
        if (card != null && container != null) container.removeView(card.panel);
        if (cards.isEmpty()) renderOthersBar(DownloadTaskStore.countOthers(activity, page));
    }

    /** 其他页面任务折叠条：>0 时显示「还有 N 个任务在下载」，点击跳转下载管理页 */
    private void renderOthersBar(int othersCount) {
        if (container == null) return;
        if (othersCount <= 0) {
            if (othersBar != null) othersBar.setVisibility(View.GONE);
            if (cards.isEmpty()) container.setVisibility(View.GONE);
            return;
        }
        container.setVisibility(View.VISIBLE);
        if (othersBar == null) {
            othersBar = new LiquidGlassPanel(activity);
            othersBar.setOrientation(LinearLayout.HORIZONTAL);
            othersBar.setGravity(Gravity.CENTER_VERTICAL);
            othersBar.setPadding(dp(16), dp(12), dp(16), dp(12));
            othersBar.setOnClickListener(v -> {
                Haptics.perform(v);
                activity.startActivity(new Intent(activity, DownloadManagerActivity.class));
            });
            TextView text = label("", 13, 0xff20375b);
            othersBar.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            TextView arrow = label("查看 ›", 12, 0xff596579);
            othersBar.addView(arrow, new LinearLayout.LayoutParams(-2, -2));
            container.addView(othersBar);
        }
        TextView text = (TextView) othersBar.getChildAt(0);
        if (text != null) {
            text.setText("▾ 还有 " + othersCount + " 个任务在其他页面下载/排队（最多同时 3 个）");
        }
        othersBar.setVisibility(View.VISIBLE);
    }

    // ---------- 单张卡片 ----------

    private static final class Card {
        final String id;
        LiquidGlassPanel panel;
        TextView title;
        TextView status;
        TextView percent;
        ProgressBar progress;
        TextView bytes;
        TextView path;
        Button pauseButton;
        Button cancelButton;
        boolean paused;
        long speed = -1;
        long eta = -1;

        Card(String id) { this.id = id; }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private static String formatEta(long seconds) {
        if (seconds < 0) return "--";
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainder = seconds % 60;
        if (hours > 0) return hours + "小时" + minutes + "分";
        if (minutes > 0) return minutes + "分" + remainder + "秒";
        return Math.max(1, remainder) + "秒";
    }
}
