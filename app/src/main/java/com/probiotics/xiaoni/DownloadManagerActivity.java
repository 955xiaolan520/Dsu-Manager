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

    // ---------- v3.8.8 多任务实时状态（与 DownloadService 广播 / DownloadTaskStore 同源） ----------

    /** 单个任务的最新状态（广播增量更新，全量重渲染的数据源） */
    private static final class TaskState {
        String id = "";          // 输出文件绝对路径（唯一标识）
        String page = "";        // 来源页面 simpleName
        String pkg = "下载";      // 包类型文案
        String message = "";     // 最新状态行
        String output = "";      // 输出路径
        long done = -1, total = -1, speed = -1, eta = -1;
        boolean pending;         // 排队「待下载」
        boolean paused;
        boolean finished;        // 下载完成（短暂展示后由历史接管）
        boolean failed;
    }

    /** 单张任务卡的视图引用（增量刷新，避免整页重建闪烁） */
    private static final class CardViews {
        LinearLayout card;
        TextView title;
        TextView state;
        TextView percent;
        TextView bytes;
        TextView path;
        ProgressBar progress;
        Button pauseButton;
        Button cancelButton;
    }

    private final java.util.LinkedHashMap<String, TaskState> states = new java.util.LinkedHashMap<>();
    private final java.util.HashMap<String, CardViews> cards = new java.util.HashMap<>();

    // UI
    private LinearLayout activeHost;      // 任务卡列表容器
    private LinearLayout historyHost;     // 历史列表容器
    private TextView historySummary;      // 历史统计

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
        loadStatesFromStore();
        renderTasks();
        renderHistory();

        // 实时同步：接收下载服务广播（与通知栏同源，按任务 ID 增量更新对应卡片）
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                onDownloadUpdate(intent);
            }
        };
        // Android 14+（targetSdk 34+）必须指定导出标志，否则 SecurityException 崩溃（3.8.0 崩溃根因）
        IntentFilter filter = new IntentFilter(DownloadService.ACTION_UPDATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
        // 进入页面立即拉取全部任务状态
        startService(new Intent(this, DownloadService.class).setAction(DownloadService.ACTION_QUERY));
    }

    @Override protected void onResume() {
        super.onResume();
        // 回到页面时刷新历史（可能在其他页完成下载）
        loadHistory();
        loadStatesFromStore();
        renderTasks();
        renderHistory();
        startService(new Intent(this, DownloadService.class).setAction(DownloadService.ACTION_QUERY));
    }

    @Override protected void onDestroy() {
        try { if (receiver != null) unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) { }
        super.onDestroy();
    }

    // ---------- v3.8.8 多任务实时状态（广播增量更新 + store 对账） ----------

    /** 初始 / 回页对账：从 DownloadTaskStore 读取全部任务（服务可能在页面关闭期间结束任务） */
    private void loadStatesFromStore() {
        states.clear();
        for (DownloadTaskStore.Item item : DownloadTaskStore.all(this)) {
            TaskState s = new TaskState();
            s.id = item.id;
            s.page = item.page;
            s.pkg = item.pkg;
            s.output = item.output.getAbsolutePath();
            s.message = item.message;
            s.done = item.done;
            s.total = item.total;
            s.speed = item.speed;
            s.eta = item.eta;
            s.pending = item.isPending();
            s.paused = item.isPaused();
            states.put(s.id, s);
        }
    }

    /** 广播到达：按任务 ID 增量更新对应卡片；任务结束（取消/完成/失败）时移除并刷历史 */
    private void onDownloadUpdate(Intent intent) {
        if (intent == null) return;
        String taskId = intent.getStringExtra(DownloadService.EXTRA_TASK_ID);
        if (taskId == null) return;
        String state = intent.getStringExtra(DownloadService.EXTRA_STATE);
        long d = intent.getLongExtra(DownloadService.EXTRA_DONE, -1);
        long t = intent.getLongExtra(DownloadService.EXTRA_TOTAL, -1);
        long sp = intent.getLongExtra(DownloadService.EXTRA_SPEED, -1);
        long e = intent.getLongExtra(DownloadService.EXTRA_ETA, -1);
        if (state != null && state.startsWith("下载已取消")) {
            states.remove(taskId);
            renderTasks();
            return;
        }
        TaskState s = states.get(taskId);
        if (s == null) {
            // 未知任务（页面关闭期间新增）→ 全量对账
            loadStatesFromStore();
            renderTasks();
            return;
        }
        if (state != null) {
            s.message = state;
            if (state.startsWith("已暂停")) s.paused = true;
            else if (state.startsWith("正在下载")) {
                s.paused = false;
                s.pending = false;
            } else if (state.startsWith("排队等待中")) {
                s.pending = true;
            } else if ("下载完成".equals(state)) {
                s.finished = true;
                loadHistory();
                renderHistory();
            } else if (state.startsWith("下载失败") || state.startsWith("下载地址无效")) {
                s.failed = true;
            }
        }
        if (d >= 0) s.done = d;
        if (t >= 0) s.total = t;
        if (sp > 0) s.speed = sp;
        if (e >= 0) s.eta = e;
        updateCard(taskId);
    }

    /** 来源页面 simpleName → 中文标签 */
    private static String pageLabel(String page) {
        if ("RomActivity".equals(page)) return "小米";
        if ("VivoActivity".equals(page)) return "vivo";
        if ("OPlusOtaActivity".equals(page)) return "OPPO";
        return page == null || page.isEmpty() ? "本机" : page;
    }

    // ---------- 任务卡列表渲染（v3.8.8：每任务一张卡，最多 3 并行 + 待下载队列） ----------

    private void renderTasks() {
        activeHost.removeAllViews();
        cards.clear();
        if (states.isEmpty()) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(18), dp(16), dp(18), dp(16));
            card.setBackground(enhancedGlass(dp(24)));
            card.setElevation(dp(8));
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
        for (TaskState s : states.values()) {
            View cardView = taskCard(s);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
            cardLp.bottomMargin = dp(12);
            activeHost.addView(cardView, cardLp);
        }
    }

    private View taskCard(TaskState s) {
        CardViews views = new CardViews();
        views.card = new LinearLayout(this);
        views.card.setOrientation(LinearLayout.VERTICAL);
        views.card.setPadding(dp(18), dp(16), dp(18), dp(16));
        views.card.setBackground(enhancedGlass(dp(24)));
        views.card.setElevation(dp(8));

        // 标签行：来源（小米 / vivo / OPPO）+ 包类型 + 排队徽章
        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        TextView badgeView = new TextView(this);
        badgeView.setTextSize(11);
        badgeView.setTypeface(Typeface.DEFAULT_BOLD);
        badgeView.setGravity(Gravity.CENTER);
        badgeView.setPadding(dp(10), 0, dp(10), 0);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setCornerRadius(dp(11));
        if (s.pending) {
            badgeBg.setColor(0x268A94A6);
            badgeBg.setStroke(Math.max(1, dp(1)), 0x33000000);
            badgeView.setTextColor(0xff667383);
            badgeView.setText("◷ 待下载 · " + pageLabel(s.page));
        } else if (s.finished) {
            badgeBg.setColor(0x262FB47C);
            badgeBg.setStroke(Math.max(1, dp(1)), 0x33000000);
            badgeView.setTextColor(0xff1E8A60);
            badgeView.setText("✓ 已完成 · " + pageLabel(s.page));
        } else if (s.failed) {
            badgeBg.setColor(0x26C0392B);
            badgeBg.setStroke(Math.max(1, dp(1)), 0x33000000);
            badgeView.setTextColor(0xffB03024);
            badgeView.setText("✕ 失败 · " + pageLabel(s.page));
        } else {
            badgeBg.setColor(0x264472DE);
            badgeBg.setStroke(Math.max(1, dp(1)), 0x33000000);
            badgeView.setTextColor(0xFF3D6BD6);
            badgeView.setText("⇩ 下载中 · " + pageLabel(s.page));
        }
        badgeView.setBackground(badgeBg);
        meta.addView(badgeView, new LinearLayout.LayoutParams(-2, dp(22)));
        TextView pkgView = label(s.pkg == null || s.pkg.isEmpty() ? "ROM 下载" : s.pkg, 11.5f, TEAL_FAINT);
        pkgView.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        meta.addView(pkgView, new LinearLayout.LayoutParams(0, dp(24), 1));
        views.card.addView(meta, new LinearLayout.LayoutParams(-1, -2));

        String name = s.output.isEmpty() ? "ROM 文件" : new File(s.output).getName();
        String prefix = s.pending ? "待下载: " : s.finished ? "下载完成: "
                : s.failed ? "下载失败: " : s.paused ? "已暂停: " : "正在下载: ";
        views.title = label(prefix + name, 15.5f, TEAL_DARK);
        views.title.setTypeface(null, 1);
        views.title.setMaxLines(1);
        views.title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, dp(30));
        titleLp.topMargin = dp(4);
        views.card.addView(views.title, titleLp);

        // 状态（预计剩余 / 已暂停 / 排队中）+ 百分比
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        views.state = label(stateLine(s), 13, TEAL_SOFT);
        views.state.setMaxLines(1);
        views.state.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(views.state, new LinearLayout.LayoutParams(0, dp(42), 1));
        views.percent = label(percentText(s), 16, TEAL_TITLE);
        views.percent.setTypeface(null, 1);
        views.percent.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        head.addView(views.percent, new LinearLayout.LayoutParams(dp(72), dp(42)));
        views.card.addView(head, new LinearLayout.LayoutParams(-1, -2));

        views.progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        views.progress.setMax(100);
        views.progress.setProgress(percentValue(s));
        views.progress.setProgressDrawable(getDrawable(R.drawable.progress_bar));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, dp(16));
        barLp.setMargins(0, dp(2), 0, dp(6));
        views.card.addView(views.progress, barLp);

        views.bytes = label(bytesText(s), 12, TEAL_SOFT);
        views.bytes.setMaxLines(1);
        views.bytes.setEllipsize(android.text.TextUtils.TruncateAt.END);
        views.card.addView(views.bytes, new LinearLayout.LayoutParams(-1, dp(24)));

        views.path = label(s.output.isEmpty() ? "" : "保存到: " + s.output, 11, TEAL_FAINT);
        views.path.setMaxLines(2);
        views.path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams pathLp = new LinearLayout.LayoutParams(-1, -2);
        pathLp.bottomMargin = dp(4);
        views.card.addView(views.path, pathLp);

        // 操作按钮：暂停/继续 + 取消（每卡独立，指令带任务 ID）
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        views.pauseButton = actionButton(
                s.pending ? "◷ 排队中" : s.paused ? "▶ 继续" : s.finished ? "已完成" : "⏸ 暂停",
                0xFF4472DE, 0xFF6C9BF2);
        views.pauseButton.setEnabled(!s.pending && !s.finished);
        views.pauseButton.setOnClickListener(v -> {
            Haptics.perform(v);
            startService(new Intent(this, DownloadService.class)
                    .setAction(s.paused ? DownloadService.ACTION_RESUME : DownloadService.ACTION_PAUSE)
                    .putExtra(DownloadService.EXTRA_TASK_ID, s.id));
        });
        actions.addView(views.pauseButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        views.cancelButton = actionButton(s.finished ? "知道了" : "✕ 取消", 0xFFC0392B, 0xFFE07A6B);
        views.cancelButton.setOnClickListener(v -> {
            Haptics.perform(v);
            if (s.finished) {
                states.remove(s.id);
                renderTasks();
                return;
            }
            new android.app.AlertDialog.Builder(this)
                    .setTitle("取消下载")
                    .setMessage("确定取消「" + name + "」？已下载的部分文件将被删除。")
                    .setPositiveButton("取消下载", (d, w) -> {
                        Haptics.perform(v);
                        states.remove(s.id);
                        renderTasks();
                        startService(new Intent(this, DownloadService.class)
                                .setAction(DownloadService.ACTION_CANCEL)
                                .putExtra(DownloadService.EXTRA_TASK_ID, s.id));
                    })
                    .setNegativeButton("返回", null)
                    .show();
        });
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, dp(44), 1);
        cancelLp.leftMargin = dp(10);
        actions.addView(views.cancelButton, cancelLp);
        views.card.addView(actions, new LinearLayout.LayoutParams(-1, dp(44)));

        cards.put(s.id, views);
        return views.card;
    }

    /** 单卡增量刷新（广播到达，不重建整页） */
    private void updateCard(String taskId) {
        TaskState s = states.get(taskId);
        CardViews views = cards.get(taskId);
        if (s == null) {
            renderTasks();
            return;
        }
        if (views == null || views.title == null) {
            renderTasks();
            return;
        }
        String name = s.output.isEmpty() ? "ROM 文件" : new File(s.output).getName();
        String prefix = s.pending ? "待下载: " : s.finished ? "下载完成: "
                : s.failed ? "下载失败: " : s.paused ? "已暂停: " : "正在下载: ";
        views.title.setText(prefix + name);
        views.state.setText(stateLine(s));
        views.percent.setText(percentText(s));
        views.progress.setProgress(percentValue(s));
        views.bytes.setText(bytesText(s));
        views.path.setText(s.output.isEmpty() ? "" : "保存到: " + s.output);
        views.pauseButton.setText(s.pending ? "◷ 排队中" : s.paused ? "▶ 继续" : s.finished ? "已完成" : "⏸ 暂停");
        views.pauseButton.setEnabled(!s.pending && !s.finished);
        views.cancelButton.setText(s.finished ? "知道了" : "✕ 取消");
    }

    private static int percentValue(TaskState s) {
        return s.total > 0 && s.done >= 0
                ? (int) Math.max(0, Math.min(100, s.done * 100 / s.total)) : 0;
    }

    private static String percentText(TaskState s) {
        if (s.finished) return "100%";
        if (s.pending) return "排队";
        return s.total > 0 && s.done >= 0 ? percentValue(s) + "%" : "…";
    }

    /** 状态行文案（预计剩余 / 已暂停 / 排队等待 / 失败原因） */
    private String stateLine(TaskState s) {
        if (s.finished) return "下载完成 · 已保存到 Download/DsuManager";
        if (s.failed) return s.message == null || s.message.isEmpty() ? "下载失败" : s.message;
        if (s.pending) return s.message == null || s.message.isEmpty()
                ? "排队等待中（最多同时下载 3 个）" : s.message;
        if (s.paused) return "已暂停 · 点击「继续」断点续传";
        if (s.eta > 0 && s.total > s.done) return "预计剩余 " + formatRemainingTime(s.eta);
        if (s.message != null && (s.message.startsWith("正在准备") || s.message.endsWith("准备下载")))
            return "正在准备下载";
        return "正在连接下载节点...";
    }

    private String bytesText(TaskState s) {
        if (s.finished) {
            return "总大小 " + (s.total > 0 ? formatBytes(s.total) : formatBytes(s.done));
        }
        String speedPart = !s.paused && !s.pending && s.speed > 0
                ? " · 速度 " + formatBytes(s.speed) + "/s" : "";
        if (s.done >= 0 && s.total > 0) {
            return "已下载 " + formatBytes(s.done) + " / 总大小 " + formatBytes(s.total) + speedPart;
        }
        if (s.done >= 0) {
            return "已下载 " + formatBytes(s.done) + " / 总大小获取中" + speedPart;
        }
        return "正在获取下载信息...";
    }

    /** v3.8.6：字节格式化 —— 与 ROM 查询页下载框一致（MB 一位小数 / GB 两位小数） */
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.CHINA, "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.CHINA, "%.2f GB", bytes / 1073741824.0);
    }

    /** v3.8.6：剩余时间格式化 —— 与 ROM 查询页下载框一致（X 小时 X 分钟 / X 分钟 / X 秒） */
    private String formatRemainingTime(long seconds) {
        if (seconds < 0) return "--";
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainder = seconds % 60;
        if (hours > 0) return hours + " 小时 " + minutes + " 分钟";
        if (minutes > 0) return minutes + " 分钟";
        return Math.max(1, remainder) + " 秒";
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
