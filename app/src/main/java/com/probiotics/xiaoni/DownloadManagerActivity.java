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
import android.os.Environment;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
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
    private LinearLayout listHost;        // v3.9.15 统一任务列表容器（活跃任务 + 下载历史合并，按筛选标签过滤）
    private LinearLayout filterHost;      // v3.9.14 任务筛选标签行（全部/下载中/已完成/已暂停）
    private int taskFilter = 0;           // 当前筛选：0=全部 1=下载中 2=已完成 3=已暂停

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
        // v3.9.13 标题栏右上角「＋ 新建下载」（图一同款入口位置，液态玻璃蓝渐变胶囊）
        Button newDownload = new Button(this, null, 0);
        newDownload.setText("＋ 新建下载");
        newDownload.setAllCaps(false);
        newDownload.setTextSize(12.5f);
        newDownload.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD);
        newDownload.setTextColor(Color.WHITE);
        newDownload.setGravity(Gravity.CENTER);
        newDownload.setMinWidth(0);
        newDownload.setMinHeight(0);
        newDownload.setIncludeFontPadding(false);
        newDownload.setPadding(dp(14), 0, dp(14), 0);
        newDownload.setStateListAnimator(null);
        GradientDrawable newBg = new GradientDrawable();
        newBg.setOrientation(GradientDrawable.Orientation.TL_BR);
        newBg.setColors(new int[]{0xFF6C9BF2, 0xFF4472DE});
        newBg.setCornerRadius(dp(17));
        newBg.setStroke(Math.max(1, dp(1)), 0x59FFFFFF);
        newDownload.setBackground(newBg);
        newDownload.setElevation(dp(3));
        newDownload.setOnClickListener(v -> {
            Haptics.perform(v);
            showNewDownloadDialog();
        });
        android.widget.FrameLayout.LayoutParams newLp =
                new android.widget.FrameLayout.LayoutParams(-2, dp(34), Gravity.END | Gravity.CENTER_VERTICAL);
        newLp.rightMargin = dp(4);
        title.addView(newDownload, newLp);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, dp(56));
        titleLp.bottomMargin = dp(12);
        content.addView(title, titleLp);

        // ---------- v3.9.15 统一任务列表：筛选标签行直接作为列表头部 ----------
        // 删除「任务队列」提示卡与「当前任务 / 下载历史」两个小标题（与系统下载管理同构），
        // 四个胶囊真正过滤「活跃任务 + 下载历史」合并列表；清空按钮挪到列表尾部。
        filterHost = new LinearLayout(this);
        filterHost.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams filterLp = new LinearLayout.LayoutParams(-1, dp(32));
        filterLp.topMargin = dp(6);
        content.addView(filterHost, filterLp);
        listHost = new LinearLayout(this);
        listHost.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(-1, -2);
        listLp.topMargin = dp(16);
        content.addView(listHost, listLp);

        loadHistory();
        loadStatesFromStore();
        renderTasks();

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
        // v3.9.14：记录状态类别，类别切换（排队/下载/暂停/完成/失败）时整卡重建
        // （徽章与按钮样式随状态变色，增量刷新不覆盖 → 此前完成后徽章一直卡在「下载中」）
        int categoryBefore = categoryOf(s);
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
                // v3.9.15：完成即入历史（统一列表由历史卡接管，下方类别切换触发 renderTasks 全量刷新）
                loadHistory();
            } else if (state.startsWith("下载失败") || state.startsWith("下载地址无效")) {
                s.failed = true;
            }
        } else if (d >= 0) {
            // v3.9.2 防御：进度广播（无状态文案）持续到达 = 任务确实在下载，
            // 无论之前处于暂停/排队态一律清除（文案缺失不应卡死页面状态机）
            s.paused = false;
            s.pending = false;
        }
        if (d >= 0) s.done = d;
        if (t >= 0) s.total = t;
        if (sp > 0) s.speed = sp;
        if (e >= 0) s.eta = e;
        if (categoryOf(s) != categoryBefore) {
            renderTasks();
            return;
        }
        updateCard(taskId);
    }

    /** 来源页面 simpleName → 中文标签 */
    private static String pageLabel(String page) {
        if ("RomActivity".equals(page)) return "小米";
        if ("VivoActivity".equals(page)) return "vivo";
        if ("OPlusOtaActivity".equals(page)) return "OPPO";
        if ("DownloadManagerActivity".equals(page)) return "链接";   // v3.9.13 手动新建下载
        return page == null || page.isEmpty() ? "本机" : page;
    }

    // ---------- 任务卡列表渲染（v3.8.8：每任务一张卡，最多 3 并行 + 待下载队列） ----------

    /** 任务分类：1 下载中（含排队）/ 2 已完成 / 3 已暂停或失败 */
    private static int categoryOf(TaskState s) {
        if (s.finished) return 2;
        if (s.paused || s.failed) return 3;
        return 1;
    }

    private static final String[] FILTER_NAMES = {"全部", "下载中", "已完成", "已暂停"};

    /** v3.9.14 筛选标签行：4 个胶囊（带实时计数），选中蓝渐变白字、未选中白玻璃青字 */
    private void renderFilterTabs() {
        if (filterHost == null) return;
        filterHost.removeAllViews();
        // v3.9.15：计数覆盖「活跃任务 + 下载历史」合并视图（此前只统计活跃任务，「已完成」永远 0）
        int[] counts = computeCounts();
        for (int i = 0; i < FILTER_NAMES.length; i++) {
            final int index = i;
            Button tab = new Button(this, null, 0);
            tab.setText(FILTER_NAMES[i] + " " + counts[i]);
            tab.setAllCaps(false);
            tab.setTextSize(11.5f);
            if (taskFilter == i) tab.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD);
            tab.setTextColor(taskFilter == i ? Color.WHITE : TEAL_TITLE);
            tab.setGravity(Gravity.CENTER);
            tab.setMinWidth(0);
            tab.setMinHeight(0);
            tab.setIncludeFontPadding(false);
            tab.setPadding(0, 0, 0, 0);
            tab.setStateListAnimator(null);
            GradientDrawable bg = new GradientDrawable();
            bg.setOrientation(GradientDrawable.Orientation.TL_BR);
            if (taskFilter == i) bg.setColors(new int[]{0xFF6C9BF2, 0xFF4472DE});
            else bg.setColors(new int[]{0xCCFFFFFF, 0x99E2E8F0});
            bg.setCornerRadius(dp(15));
            bg.setStroke(Math.max(1, dp(1)), taskFilter == i ? 0x59FFFFFF : 0x66FFFFFF);
            tab.setBackground(bg);
            tab.setOnClickListener(v -> {
                if (taskFilter == index) return;
                Haptics.perform(v);
                taskFilter = index;
                renderTasks();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(30), 1);
            if (i > 0) lp.leftMargin = dp(8);
            filterHost.addView(tab, lp);
        }
    }

    /** v3.9.15：历史中是否已有该输出文件的记录（任务卡与历史卡去重，防止「全部」下重复显示） */
    private boolean historyHas(String output) {
        if (output == null || output.isEmpty()) return false;
        for (JSONObject e : history) {
            if (output.equals(e.optString("path"))) return true;
        }
        return false;
    }

    /** v3.9.15：各筛选标签实时计数（已完成 = 历史完成数 + 刚完成未入历史的任务） */
    private int[] computeCounts() {
        int downloading = 0, finished = 0, paused = 0, active = 0;
        for (TaskState s : states.values()) {
            if (s.finished && historyHas(s.output)) continue;   // 已入历史，由历史卡计数
            active++;
            int cat = categoryOf(s);
            if (cat == 1) downloading++;
            else if (cat == 2) finished++;
            else paused++;
        }
        int doneHistory = 0;
        for (JSONObject e : history) {
            if ("done".equals(e.optString("status"))) doneHistory++;
        }
        return new int[]{active + history.size(), downloading, finished + doneHistory, paused};
    }

    private void renderTasks() {
        renderFilterTabs();
        listHost.removeAllViews();
        cards.clear();
        // v3.9.15：活跃任务与下载历史合并为统一列表，按筛选标签真正过滤：
        //   全部 = 活跃任务（下载中/排队/暂停/失败）+ 全部历史；下载中 = 进行/排队任务；
        //   已完成 = 历史中 done 的文件；已暂停 = 暂停/失败任务。
        //   刚完成的任务写入历史后由历史卡接管（去重），不再重复渲染任务卡。
        List<TaskState> shownTasks = new ArrayList<>();
        for (TaskState s : states.values()) {
            if (s.finished && historyHas(s.output)) continue;   // 历史接管
            int cat = categoryOf(s);
            if (taskFilter == 1 && cat != 1) continue;
            if (taskFilter == 2 && cat != 2) continue;
            if (taskFilter == 3 && cat != 3) continue;
            shownTasks.add(s);
        }
        List<JSONObject> shownHistory = new ArrayList<>();
        if (taskFilter == 0 || taskFilter == 2) {
            for (JSONObject e : history) {
                if (taskFilter == 2 && !"done".equals(e.optString("status"))) continue;
                shownHistory.add(e);
            }
        }
        if (shownTasks.isEmpty() && shownHistory.isEmpty()) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(18), dp(18), dp(18), dp(18));
            card.setBackground(enhancedGlass(dp(24)));
            card.setElevation(dp(8));
            String emptyTitle = taskFilter == 1 ? "没有正在下载的任务"
                    : taskFilter == 2 ? "「已完成」分类下暂无文件"
                    : taskFilter == 3 ? "没有暂停或失败的任务"
                    : "当前没有下载任务";
            TextView empty = label(emptyTitle, 15.5f, TEAL_DARK);
            empty.setGravity(Gravity.CENTER);
            empty.setTypeface(null, 1);
            card.addView(empty, new LinearLayout.LayoutParams(-1, dp(34)));
            String emptyHint = taskFilter == 1 ? "新任务自动排队接续 · 最多同时下载 3 个"
                    : taskFilter == 2 ? "下载完成的文件会保存在 Download/DsuManager"
                    : taskFilter == 3 ? "暂停的任务点击「继续」可断点续传"
                    : "去 ROM 更新中心选择机型，或点右上角「新建下载」粘贴任意链接";
            TextView hint = label(emptyHint, 12.5f, TEAL_SOFT);
            hint.setGravity(Gravity.CENTER);
            hint.setLineSpacing(dp(3), 1.1f);
            card.addView(hint, new LinearLayout.LayoutParams(-1, -2));
            listHost.addView(card, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (TaskState s : shownTasks) {
            View cardView = taskCard(s);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
            cardLp.topMargin = dp(12);
            listHost.addView(cardView, cardLp);
        }
        for (JSONObject entry : shownHistory) {
            View cardView = historyCard(entry);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
            cardLp.topMargin = dp(12);
            listHost.addView(cardView, cardLp);
        }
        // v3.9.15：清空按钮挪到列表尾部（有历史时，全部 / 已完成筛选下显示）
        if ((taskFilter == 0 || taskFilter == 2) && !history.isEmpty()) {
            Button clear = smallGlassButton("🗑 清空全部记录");
            clear.setPadding(dp(16), 0, dp(16), 0);
            LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(-2, dp(36));
            clearLp.topMargin = dp(6);
            clearLp.gravity = Gravity.CENTER_HORIZONTAL;
            clear.setOnClickListener(v -> {
                Haptics.perform(v);
                if (history.isEmpty()) return;
                new android.app.AlertDialog.Builder(this)
                        .setTitle("清空下载记录")
                        .setMessage("确定删除全部 " + history.size() + " 条下载记录？（不会删除已下载的文件）")
                        .setPositiveButton("清空", (d, w) -> {
                            Haptics.perform(v);
                            history.clear();
                            saveHistory();
                            renderTasks();
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
            listHost.addView(clear, clearLp);
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

        // 操作按钮：暂停/继续/安装 + 取消（每卡独立，指令带任务 ID）
        // v3.9.14：任务完成的 APK → 主按钮变「⚙ 安装 APK」（绿渐变），点击直接免 root 安装
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        boolean isApk = s.output != null && s.output.toLowerCase(Locale.CHINA).endsWith(".apk");
        boolean installable = s.finished && isApk;
        views.pauseButton = actionButton(
                s.pending ? "◷ 排队中" : s.paused ? "▶ 继续"
                        : installable ? "⚙ 安装 APK" : s.finished ? "已完成" : "⏸ 暂停",
                installable ? 0xFF1E8A60 : 0xFF4472DE,
                installable ? 0xFF2FB47C : 0xFF6C9BF2);
        views.pauseButton.setEnabled(!s.pending && (!s.finished || installable));
        views.pauseButton.setOnClickListener(v -> {
            Haptics.perform(v);
            if (installable) {
                // v3.9.14：点击直接跳转安装（PackageInstaller 免 root，成功后自动清理安装包）
                UpdateCenter.installApk(DownloadManagerActivity.this, new File(s.output), false);
                return;
            }
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
        views.pauseButton.setText(s.pending ? "◷ 排队中" : s.paused ? "▶ 继续"
                : s.finished && s.output.toLowerCase(Locale.CHINA).endsWith(".apk")
                        ? "⚙ 安装 APK" : s.finished ? "已完成" : "⏸ 暂停");
        views.pauseButton.setEnabled(!s.pending && (!s.finished
                || s.output.toLowerCase(Locale.CHINA).endsWith(".apk")));
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

    // v3.9.15：renderHistory 已删除 —— 历史卡并入统一列表（renderTasks 按筛选标签渲染）

    private View historyCard(JSONObject entry) {
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

        // 操作：APK 完成且文件存在 → 「📦 安装 APK」；其他文件 → 「📂 打开文件位置」；+ 删除记录
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        boolean fileExists = !path.isEmpty() && new File(path).isFile();
        boolean installable = fileExists && "done".equals(status)
                && path.toLowerCase(Locale.CHINA).endsWith(".apk");
        if (installable) {
            // v3.9.14：下载的 APK 点击直接免 root 安装（成功后自动清理安装包）
            Button install = actionButton("📦 安装 APK", 0xFF1E8A60, 0xFF2FB47C);
            install.setOnClickListener(v -> {
                Haptics.perform(v);
                UpdateCenter.installApk(DownloadManagerActivity.this, new File(path), false);
            });
            actions.addView(install, new LinearLayout.LayoutParams(0, dp(40), 1.6f));
        } else if (fileExists) {
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
            renderTasks();
        });
        LinearLayout.LayoutParams removeLp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        removeLp.leftMargin = dp(8);
        actions.addView(remove, removeLp);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, dp(40));
        actionsLp.topMargin = dp(10);
        card.addView(actions, actionsLp);
        return card;
    }

    // ---------- v3.9.13 新建下载（手动输入链接，下载任意文件） ----------

    /** 「＋ 新建下载」弹窗：链接输入（剪贴板自动预填）+ 文件名实时预览，白卡胶囊按钮（与更新中心同款） */
    private void showNewDownloadDialog() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(22), dp(20), dp(22), dp(16));
        GradientDrawable bodyBg = new GradientDrawable();
        bodyBg.setColor(0xFFFFFFFF);
        bodyBg.setCornerRadius(dp(24));
        body.setBackground(bodyBg);

        TextView title = label("新建下载", 17.5f, 0xde000000);
        title.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD);
        body.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = label("粘贴任意 http / https 文件直链即可下载（GitHub 链接自动加速，无需 VPN），保存到 Download/DsuManager",
                12.5f, 0x8a000000);
        hint.setLineSpacing(dp(2), 1.1f);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.topMargin = dp(6);
        body.addView(hint, hintLp);

        final EditText input = new EditText(this);
        input.setTextSize(14f);
        input.setTextColor(0xff20375b);
        input.setHintTextColor(0x8a94a3b8);
        input.setHint("https://example.com/file.zip");
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        input.setMaxLines(1);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        GradientDrawable fieldBg = new GradientDrawable();
        fieldBg.setColor(0xFFF3F6FA);
        fieldBg.setCornerRadius(dp(14));
        fieldBg.setStroke(Math.max(1, dp(1)), 0x1A20375b);
        input.setBackground(fieldBg);
        input.setPadding(dp(12), dp(11), dp(12), dp(11));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(-1, -2);
        inputLp.topMargin = dp(14);
        body.addView(input, inputLp);

        // 文件名预览（随输入实时更新）
        final TextView preview = label("将自动识别文件名", 12f, 0x8a000000);
        preview.setMaxLines(1);
        preview.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(-1, dp(20));
        previewLp.topMargin = dp(8);
        body.addView(preview, previewLp);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                String url = s.toString().trim();
                preview.setText(url.isEmpty() ? "将自动识别文件名" : "保存为: " + manualFileName(url));
            }
        });
        // 剪贴板有链接时自动预填（省去长按粘贴）
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                    && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                String clip = text == null ? "" : text.toString().trim();
                if (clip.matches("(?i)https?://\\S+")) input.setText(clip);
            }
        } catch (Exception ignored) { }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button cancel = dialogPillButton("取消", 0xFFF1F3F7, 0xFF46536B, false);
        Button ok = dialogPillButton("开始下载", 0xFF4472DE, 0xFFFFFFFF, true);
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(46), 1));
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(0, dp(46), 1.15f);
        okLp.leftMargin = dp(10);
        actions.addView(ok, okLp);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, dp(46));
        actionsLp.topMargin = dp(14);
        body.addView(actions, actionsLp);

        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.setContentView(body);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            window.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.92),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();

        cancel.setOnClickListener(v -> {
            Haptics.perform(v);
            dialog.dismiss();
        });
        ok.setOnClickListener(v -> {
            Haptics.perform(v);
            String url = input.getText().toString().trim().replaceAll("\\s", "");
            if (!url.matches("(?i)https?://[^/\\s]+/.+")) {
                Toast.makeText(this, "链接无效，请输入完整的 http / https 下载地址", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            startManualDownload(url);
        });
    }

    /** 从链接推导文件名（URL 解码 + 去查询串 + 清理非法字符），取不到时按时间生成 */
    private static String manualFileName(String url) {
        String noQuery = url.split("[?#]")[0];
        int slash = noQuery.lastIndexOf('/');
        String name = slash >= 0 ? noQuery.substring(slash + 1) : "";
        try { name = java.net.URLDecoder.decode(name, "UTF-8"); } catch (Exception ignored) { }
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            name = "download_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date());
        }
        return name;
    }

    /** 提交手动下载任务（4 线程 + 16MB 分片，与 ROM 下载同参数；并行满 3 个自动排队接续） */
    private void startManualDownload(String url) {
        File directory = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "DsuManager");
        if (!directory.exists()) directory.mkdirs();
        File output = new File(directory, manualFileName(url));
        // 同名文件已存在 / 已是活动任务 → 自动追加 (1) (2)…（避免覆盖历史下载）
        String base = output.getName();
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        String ext = dot > 0 ? base.substring(dot) : "";
        int seq = 1;
        while (output.exists() || states.containsKey(output.getAbsolutePath())) {
            output = new File(directory, stem + " (" + (seq++) + ")" + ext);
        }
        final String id = output.getAbsolutePath();
        Intent intent = new Intent(this, DownloadService.class)
                .setAction(DownloadService.ACTION_START)
                .putExtra(DownloadService.EXTRA_ADDRESS, url)
                .putExtra(DownloadService.EXTRA_OUTPUT, id)
                .putExtra(DownloadService.EXTRA_PACKAGE, "链接下载")
                .putExtra(DownloadService.EXTRA_PAGE, "DownloadManagerActivity")
                .putExtra("download_threads", 4)
                .putExtra("download_chunk_mb", 16L);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
        } catch (Exception e) {
            Toast.makeText(this, "任务提交失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }
        // 乐观本地建卡（服务广播到达后自动刷新为排队 / 下载中）
        TaskState s = new TaskState();
        s.id = id;
        s.page = "DownloadManagerActivity";
        s.pkg = "链接下载";
        s.output = id;
        s.message = "正在准备下载";
        states.put(s.id, s);
        renderTasks();
        Toast.makeText(this, "已加入下载队列: " + output.getName(), Toast.LENGTH_SHORT).show();
    }

    /** 弹窗胶囊按钮（与更新中心同款：主按钮蓝渐变、次按钮浅灰） */
    private Button dialogPillButton(String text, int bg, int fg, boolean bold) {
        Button button = new Button(this, null, 0);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(bold ? 15f : 14.5f);
        if (bold) button.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD);
        button.setTextColor(fg);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setIncludeFontPadding(false);
        button.setPadding(0, 0, 0, 0);
        button.setStateListAnimator(null);
        GradientDrawable background = new GradientDrawable();
        if (bold) {
            background.setOrientation(GradientDrawable.Orientation.TL_BR);
            background.setColors(new int[]{0xFF6C9BF2, 0xFF4472DE});
        } else {
            background.setColor(bg);
        }
        background.setCornerRadius(dp(23));
        background.setStroke(Math.max(1, dp(1)), bold ? 0x59FFFFFF : 0x14000000);
        button.setBackground(background);
        return button;
    }

    // ---------- v3.9.15 打开文件位置：四级回退（修复 vivo 等设备上只复制路径不跳转） ----------

    /**
     * 跳转到文件所在目录（v3.9.15 重做为四级回退链，层层保底）：
     *  1. DocumentsUI 精确定位（ACTION_VIEW + vnd.android.document/directory，
     *     Google Files / AOSP 文件响应，直达文件所在目录）；
     *  2. 第三方文件管理器（file:// 目录 + resource/folder / inode/directory 专用 MIME，
     *     MT 管理器 / RE / ES 等注册，音乐网盘类不会出现；单个直接拉起，多个弹系统选择器）；
     *  3. 系统下载列表（DownloadManager.ACTION_VIEW_DOWNLOADS，几乎所有 ROM 都有，
     *     打开系统「下载」页可看到 Download/DsuManager 下的文件）；
     *  4. 复制文件路径（最终兜底）。
     * v3.9.14 只发第 1 路意图，无应用注册时 startActivity 直接抛异常 → 永远走复制路径。
     */
    private void openFileLocation(String path) {
        File file = new File(path);
        File dir = file.getParentFile();
        String abs = dir != null && dir.isDirectory()
                ? dir.getAbsolutePath()
                : Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        + "/DsuManager";
        // 1) DocumentsUI 精确定位（先 query 再启动，避免直接抛 ActivityNotFoundException）
        try {
            String docId = "primary:" + abs.replace("/storage/emulated/0/", "");
            Uri docUri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId);
            Intent doc = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(docUri, "vnd.android.document/directory")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (doc.resolveActivity(getPackageManager()) != null) {
                startActivity(doc);
                return;
            }
        } catch (Exception ignored) { }
        // 2) 第三方文件管理器（resource/folder 是目录专用 MIME，只有管理器注册）
        try {
            java.util.LinkedHashSet<String> packages = new java.util.LinkedHashSet<>();
            String[] folderMimes = {"resource/folder", "inode/directory"};
            for (String mime : folderMimes) {
                Intent probe = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(Uri.fromFile(new File(abs)), mime);
                for (android.content.pm.ResolveInfo ri
                        : getPackageManager().queryIntentActivities(probe, 0)) {
                    packages.add(ri.activityInfo.packageName);
                }
            }
            if (!packages.isEmpty()) {
                Intent target = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(Uri.fromFile(new File(abs)), "resource/folder");
                if (packages.size() == 1) {
                    startFolderIntent(target);
                } else {
                    startFolderIntent(Intent.createChooser(target, "选择文件管理器"));
                }
                return;
            }
        } catch (Exception ignored) { }
        // 3) 系统下载列表（绝大多数 ROM 自带，至少能看到下载的文件）
        try {
            startActivity(new Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS));
            Toast.makeText(this, "已打开系统下载列表", Toast.LENGTH_SHORT).show();
            return;
        } catch (Exception ignored) { }
        // 4) 复制路径兜底
        copyPathToClipboard(path);
    }

    /**
     * 拉起 file:// 目录意图：targetSdk 24+ 会触发 FileUriExposedException，
     * 临时放宽 VmPolicy（第三方管理器仅支持 file://，无 content:// 替代）。
     */
    private void startFolderIntent(Intent intent) {
        android.os.StrictMode.VmPolicy old = android.os.StrictMode.getVmPolicy();
        android.os.StrictMode.setVmPolicy(new android.os.StrictMode.VmPolicy.Builder().build());
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } finally {
            android.os.StrictMode.setVmPolicy(old);
        }
    }

    /** 复制完整文件路径到剪贴板（所有跳转失败的最终兜底） */
    private void copyPathToClipboard(String path) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("file_path", path));
            Toast.makeText(this, "已复制文件路径: " + path, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, path, Toast.LENGTH_LONG).show();
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
