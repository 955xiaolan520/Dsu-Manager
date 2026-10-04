package com.probiotics.xiaoni;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DNA · 分解 bin 独立二级页（v3.30.22）。
 * 流程：选文件 → 🔍开始解析（JNI 直读 payload.bin / OTA zip）
 *      → 弹窗勾选分区（仅名称 + 大小，不显示哈希）→ 底部「确定」即开始提取，
 *        日志实时显示正在提取的 img 与进度（页面不再展开分区列表）。
 * 使用 libpayload_extract_jni.so（PayloadExtractor / PayloadExtractNative 桥接），
 * 与本机分区的 payload 提取同一套 JNI，无需 root shell 逐行解析。
 */
public final class DnaBinActivity extends BaseActivity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService io =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "dna-bin");
                t.setDaemon(true);
                return t;
            });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);

    // 数据状态
    private String project;                       // 当前工程（输出目标）
    private String binPath;                       // 选中的 payload.bin / zip 绝对路径
    private PayloadExtractor extractor;           // JNI 句柄（解析成功后保留供提取用）
    private String openInput;                     // 实际打开的路径（无直读权限时为 cache 兜底）
    private final List<PayloadExtractor.PartitionInfo> partitions = new ArrayList<>();
    private final Set<String> checked = new LinkedHashSet<>();

    // UI
    private TextView projectName, projectOut;
    private LinearLayout sourceList;
    private TextView sourceCount, sourceEmpty;
    private TextView status;
    private FrameLayout progressTrack;
    private View progressFill;
    private TextView consoleText;
    private ScrollView consoleScroll, pageScroll;
    private Button parseBtn, runBtn;
    private CheckBox deleteSource;

    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + 0.5f); }
    private String t(String zh, String en) {
        return getResources().getConfiguration().locale.getLanguage().startsWith("zh") ? zh : en;
    }
    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        Haptics.onTouch(getWindow().getDecorView(), e);
        return super.dispatchTouchEvent(e);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0x00000000);
        getWindow().setNavigationBarColor(0x220b131f);
        project = DnaTools.currentProject(this);
        // v3.30.31：通知栏实时同步（Android 13+ 需运行时通知授权）
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 3407);
        ensureNoteChannel();
        buildUi();
        refreshSources();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        cancelNote();
        if (extractor != null) { try { extractor.close(); } catch (Exception ignored) {} }
    }

    // ================= 通知栏同步（v3.30.31：进度实时，不延迟） =================

    private static final String NOTE_CHANNEL = "dna_tools_progress";
    private static final int NOTE_ID = 3407;

    private void ensureNoteChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    NOTE_CHANNEL, "DNA 工具箱进度", android.app.NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("显示 DNA 分解 / 提取任务实时状态");
            channel.setShowBadge(false);
            getSystemService(android.app.NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private void notify(String text, boolean ongoing, boolean indeterminate, int progress, int max) {
        try {
            android.app.Notification.Builder b = new android.app.Notification.Builder(this, NOTE_CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(t("分解 BIN", "Unpack BIN"))
                    .setContentText(text)
                    .setOngoing(ongoing)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(!ongoing)
                    .setProgress(max, progress, indeterminate);
            Intent it = new Intent(this, DnaBinActivity.class);
            it.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            b.setContentIntent(android.app.PendingIntent.getActivity(this, NOTE_ID, it,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE));
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTE_ID, b.build());
        } catch (Exception ignored) {}
    }

    private void notifyDone(boolean success, String message) {
        notify((success ? "✓ " : "✗ ") + message, false, false, 0, 0);
    }

    private void cancelNote() {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTE_ID);
        } catch (Exception ignored) {}
    }

    // ================= UI 构建 =================

    private LinearLayout glassCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0x33FFFFFF);
        bg.setCornerRadius(dp(18));
        bg.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
        c.setBackground(bg);
        return c;
    }

    private void buildUi() {
        int sb = 0;
        int rid = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (rid > 0) sb = getResources().getDimensionPixelSize(rid);
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xffd7e3f0, 0xff8fa8c4}));
        root.setPadding(0, sb + dp(8), 0, 0);

        ScrollView page = new ScrollView(this);
        pageScroll = page;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(6), dp(16), dp(20));
        page.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        // ---- 标题栏 ----
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        Button back = pillButton("‹", 26, 0xff0f1e36, dp(46), dp(46));
        back.setOnClickListener(v -> { Haptics.perform(v); finish(); overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out); });
        bar.addView(back, new LinearLayout.LayoutParams(dp(46), dp(46)));
        TextView title = new TextView(this);
        title.setText(t("DNA · 分解 bin", "DNA · Extract bin"));
        title.setTextSize(19);
        title.setTypeface(null, 1);
        title.setTextColor(0xff0f1e36);
        title.setPadding(dp(12), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView badge = new TextView(this);
        badge.setText("🧬 PDNA");
        badge.setTextSize(12f);
        badge.setTypeface(null, 1);
        badge.setTextColor(0xff0f5c4f);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(12), 0, dp(12), 0);
        android.graphics.drawable.GradientDrawable badgeBg = new android.graphics.drawable.GradientDrawable();
        badgeBg.setCornerRadius(dp(18));
        badgeBg.setColor(0x73eafff5);
        badgeBg.setStroke(Math.max(1, dp(1)), 0x802f9c8f);
        badge.setBackground(badgeBg);
        bar.addView(badge, new LinearLayout.LayoutParams(-2, dp(36)));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, -2);
        barLp.bottomMargin = dp(12);
        content.addView(bar, barLp);

        // ---- 流程提示条 ----
        TextView flow = new TextView(this);
        flow.setText("① " + t("选择文件", "Pick") + "  →  ② " + t("开始解析", "Parse")
                + "  →  ③ " + t("弹窗勾选", "Select") + "  →  ④ " + t("确定提取", "Extract"));
        flow.setTextSize(12.5f);
        flow.setTextColor(0xff35A8C4);
        flow.setTypeface(null, 1);
        flow.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable flowBg = new android.graphics.drawable.GradientDrawable();
        flowBg.setColor(0x26FFFFFF);
        flowBg.setCornerRadius(dp(14));
        flow.setBackground(flowBg);
        flow.setPadding(dp(10), dp(9), dp(10), dp(9));
        content.addView(flow, new LinearLayout.LayoutParams(-1, -2));

        // ---- 工程卡 ----
        LinearLayout projCard = glassCard();
        projCard.setOnClickListener(v -> { Haptics.perform(v); showProjectPicker(); });
        LinearLayout.LayoutParams pcLp = new LinearLayout.LayoutParams(-1, -2);
        pcLp.topMargin = dp(10);
        content.addView(projCard, pcLp);
        TextView projLabel = new TextView(this);
        projLabel.setText(t("输出工程（点击切换）", "Output project (tap to switch)"));
        projLabel.setTextSize(11.5f);
        projLabel.setTextColor(0xff5a6b82);
        projCard.addView(projLabel, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout projRow = new LinearLayout(this);
        projRow.setOrientation(LinearLayout.HORIZONTAL);
        projRow.setGravity(Gravity.CENTER_VERTICAL);
        projectName = new TextView(this);
        projectName.setTextSize(15f);
        projectName.setTypeface(null, 1);
        projectName.setTextColor(0xff17334f);
        projectName.setSingleLine(true);
        projectName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        projRow.addView(projectName, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView arrow = new TextView(this);
        arrow.setText("▼");
        arrow.setTextSize(11f);
        arrow.setTextColor(0xff5a6b82);
        projRow.addView(arrow, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams prLp = new LinearLayout.LayoutParams(-1, -2);
        prLp.topMargin = dp(3);
        projCard.addView(projRow, prLp);
        projectOut = new TextView(this);
        projectOut.setTextSize(11f);
        projectOut.setTextColor(0xff5a6b82);
        projectOut.setSingleLine(true);
        projectOut.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams poLp = new LinearLayout.LayoutParams(-1, -2);
        poLp.topMargin = dp(2);
        projCard.addView(projectOut, poLp);

        // ---- 源文件卡 ----
        LinearLayout srcCard = glassCard();
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(-1, -2);
        scLp.topMargin = dp(10);
        content.addView(srcCard, scLp);
        LinearLayout srcHead = new LinearLayout(this);
        srcHead.setOrientation(LinearLayout.HORIZONTAL);
        srcHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView srcTitle = new TextView(this);
        srcTitle.setText(t("源文件", "Source"));
        srcTitle.setTextSize(14f);
        srcTitle.setTypeface(null, 1);
        srcTitle.setTextColor(0xff17334f);
        srcHead.addView(srcTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        sourceCount = new TextView(this);
        sourceCount.setTextSize(11.5f);
        sourceCount.setTextColor(0xff1f7d72);
        srcHead.addView(sourceCount, new LinearLayout.LayoutParams(-2, -2));
        Button browseBtn = pillButton("📂", 15, 0xff172b4d, dp(38), dp(32));
        browseBtn.setOnClickListener(v -> {
            Haptics.perform(v);
            FileBrowserDialog.show(this, t("选择 payload.bin / OTA zip", "Pick payload.bin / OTA zip"),
                    new String[]{"payload.bin", ".zip", ".zip2"}, DnaTools.WORK_ROOT,
                    path -> {
                        binPath = path;
                        renderSourceSelection();
                        log("📥 " + path.substring(path.lastIndexOf('/') + 1));
                    });
        });
        android.widget.LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(dp(38), dp(32));
        brLp.leftMargin = dp(8);
        srcHead.addView(browseBtn, brLp);
        srcCard.addView(srcHead, new LinearLayout.LayoutParams(-1, -2));

        sourceEmpty = new TextView(this);
        sourceEmpty.setText(t("工程内暂无 payload.bin / zip，点 📂 浏览选择文件", "No payload.bin / zip in project, tap 📂 to browse"));
        sourceEmpty.setTextSize(12f);
        sourceEmpty.setTextColor(0xff5a6b82);
        sourceEmpty.setPadding(dp(2), dp(8), 0, dp(4));
        srcCard.addView(sourceEmpty, new LinearLayout.LayoutParams(-1, -2));

        sourceList = new LinearLayout(this);
        sourceList.setOrientation(LinearLayout.VERTICAL);
        srcCard.addView(sourceList, new LinearLayout.LayoutParams(-1, -2));

        // ---- 手动路径输入框已移除（v3.30.23：已有 📂 浏览按钮，无需再显示路径框） ----

        // ---- 解析按钮（v3.30.22：分区选择移入弹窗，页面不再展开列表） ----
        parseBtn = gradientButton("🔍  " + t("开始解析", "Parse"), new int[]{0xFF35A8C4, 0xFF0E7D95}, dp(16));
        parseBtn.setOnClickListener(v -> { Haptics.perform(v); parseFile(); });
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(-1, dp(46));
        pbLp.topMargin = dp(12);
        content.addView(parseBtn, pbLp);

        // ---- 选项 ----
        deleteSource = new CheckBox(this);
        deleteSource.setText(t("提取后删除源文件", "Delete source after extraction"));
        deleteSource.setTextSize(12.5f);
        deleteSource.setTextColor(0xff17334f);
        deleteSource.setPadding(dp(2), 0, 0, 0);
        LinearLayout.LayoutParams dsLp = new LinearLayout.LayoutParams(-1, -2);
        dsLp.topMargin = dp(10);
        content.addView(deleteSource, dsLp);

        // ---- 状态 + 进度 ----
        status = new TextView(this);
        status.setTextSize(12.5f);
        status.setTextColor(0xff5a6b82);
        status.setPadding(dp(4), dp(10), dp(4), dp(2));
        content.addView(status, new LinearLayout.LayoutParams(-1, -2));

        progressTrack = new FrameLayout(this);
        progressTrack.setBackgroundResource(R.drawable.dna_progress_track);
        progressTrack.setVisibility(View.GONE);
        progressFill = new View(this);
        progressFill.setBackgroundResource(R.drawable.dna_progress_fill);
        progressTrack.addView(progressFill, new FrameLayout.LayoutParams(dp(84), android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(progressTrack, new LinearLayout.LayoutParams(-1, dp(12)));

        // ---- 提取入口（解析完成后弹窗选择，确定即提取；运行中为取消） ----
        runBtn = gradientButton("▶  " + t("选择分区并提取", "Select & Extract"), new int[]{0xFF2f9c8f, 0xFF1d6b46}, dp(18));
        runBtn.setOnClickListener(v -> {
            Haptics.perform(v);
            if (running.get()) { cancelFlag.set(true); log(t("正在取消 ...", "Cancelling...")); return; }
            if (extractor == null || partitions.isEmpty()) {
                toast(t("请先点「开始解析」", "Tap Parse first"));
                return;
            }
            showPartitionDialog();
        });
        LinearLayout.LayoutParams rbLp = new LinearLayout.LayoutParams(-1, dp(54));
        rbLp.topMargin = dp(10);
        rbLp.bottomMargin = dp(10);
        content.addView(runBtn, rbLp);

        // ---- 控制台 ----
        content.addView(buildConsole());
        renderProject();
        log(t("提示：选择文件 → 开始解析 → 弹窗勾选分区 → 确定提取", "Tip: pick → parse → select → extract"));
    }

    /** 浅色磨砂控制台卡（v3.30.25：弃用黑色日志框；可滚动 / 复制 / 清空） */
    private LinearLayout buildConsole() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xEdf4f8fc);
        bg.setCornerRadius(dp(18));
        bg.setStroke(Math.max(1, dp(1)), 0x99FFFFFF);
        card.setBackground(bg);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("▶ " + t("执行日志", "Console"));
        title.setTextSize(12.5f);
        title.setTypeface(null, 1);
        title.setTextColor(0xff5a6b82);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        Button copy = pillButton(t("复制", "Copy"), 11f, 0xff1f7d72, dp(52), dp(28));
        copy.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("log", consoleText.getText()));
            toast(t("日志已复制", "Log copied"));
        });
        head.addView(copy, new LinearLayout.LayoutParams(dp(52), dp(28)));
        Button clear = pillButton(t("清空", "Clear"), 11f, 0xffa33b3b, dp(52), dp(28));
        clear.setOnClickListener(v -> consoleText.setText(""));
        android.widget.LinearLayout.LayoutParams clLp = new LinearLayout.LayoutParams(dp(52), dp(28));
        clLp.leftMargin = dp(6);
        head.addView(clear, clLp);
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));

        consoleScroll = new ScrollView(this);
        consoleText = new TextView(this);
        consoleText.setTypeface(android.graphics.Typeface.MONOSPACE);
        consoleText.setTextSize(11f);
        consoleText.setTextColor(0xff2c3e57);
        consoleText.setLineSpacing(dp(2), 1f);
        // v3.30.25 修复滑动：移除 setTextIsSelectable（它会接管触摸事件导致 ScrollView 无法滚动）
        consoleText.setHorizontallyScrolling(false);
        consoleText.setPadding(dp(2), dp(4), dp(2), dp(6));
        consoleScroll.addView(consoleText, new ScrollView.LayoutParams(-1, -2));
        consoleScroll.setOnTouchListener((v, e) -> {
            // 手指在内层日志框上时，禁止外层页面 ScrollView 拦截；抬起后恢复
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    break;
            }
            return false;
        });
        card.addView(consoleScroll, new LinearLayout.LayoutParams(-1, dp(300)));
        return card;
    }

    private Button pillButton(String label, float size, int color, int w, int h) {
        Button b = new Button(this, null, 0);
        b.setText(label);
        b.setTextSize(size);
        b.setAllCaps(false);
        b.setTextColor(color);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, 0, 0, 0);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0x59FFFFFF);
        bg.setCornerRadius(Math.min(w, h) / 2);
        bg.setStroke(Math.max(1, dp(1)), 0x80FFFFFF);
        b.setBackground(bg);
        b.setStateListAnimator(null);
        return b;
    }

    private Button gradientButton(String label, int[] colors, int radius) {
        Button b = new Button(this, null, 0);
        b.setText(label);
        b.setTextSize(15f);
        b.setTypeface(null, 1);
        b.setAllCaps(false);
        b.setTextColor(android.graphics.Color.WHITE);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, 0, 0, 0);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TL_BR);
        bg.setColors(colors);
        bg.setCornerRadius(radius);
        bg.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
        b.setBackground(bg);
        b.setStateListAnimator(null);
        return b;
    }

    // ================= 数据加载 =================

    private void renderProject() {
        projectName.setText(project != null ? project : t("未选择工程", "No project"));
        projectOut.setText(project != null
                ? "➜ " + DnaTools.WORK_ROOT + "/" + project
                : t("点此选择要输出的工程", "Tap to pick an output project"));
    }

    /** 异步扫描工程内 payload.bin / zip（root 列目录带大小，不阻塞 UI） */
    private void refreshSources() {
        final String proj = project;
        io.execute(() -> {
            List<DnaTools.BrowseEntry> files = new ArrayList<>();
            if (proj != null) {
                for (DnaTools.BrowseEntry e : DnaTools.browseDir(DnaTools.WORK_ROOT + "/" + proj)) {
                    String n = e.getName().toLowerCase(Locale.ROOT);
                    if (e.isDir()) continue;
                    if (n.endsWith("payload.bin") || n.endsWith(".zip") || n.endsWith(".zip2"))
                        files.add(e);
                }
            }
            final List<DnaTools.BrowseEntry> result = files;
            main.post(() -> renderSources(result));
        });
    }

    private void renderSources(List<DnaTools.BrowseEntry> files) {
        sourceList.removeAllViews();
        sourceEmpty.setVisibility(files.isEmpty() ? View.VISIBLE : View.GONE);
        for (final DnaTools.BrowseEntry e : files) {
            final String fullPath = DnaTools.WORK_ROOT + "/" + project + "/" + e.getName();
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(10), dp(10), dp(10));
            android.graphics.drawable.GradientDrawable rb = new android.graphics.drawable.GradientDrawable();
            rb.setColor(0x22FFFFFF);
            rb.setCornerRadius(dp(14));
            rb.setStroke(Math.max(1, dp(1)), 0x33FFFFFF);
            row.setBackground(rb);
            row.setTag(fullPath);
            // 图标徽章
            FrameLayout icon = new FrameLayout(this);
            android.graphics.drawable.GradientDrawable ib = new android.graphics.drawable.GradientDrawable();
            ib.setCornerRadius(dp(11));
            ib.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TL_BR);
            ib.setColors(e.getName().toLowerCase(Locale.ROOT).endsWith("payload.bin")
                    ? new int[]{0xFF35A8C4, 0xFF0E7D95} : new int[]{0xFF8E6FC7, 0xFF2E86AB});
            icon.setBackground(ib);
            TextView ie = new TextView(this);
            ie.setText(e.getName().toLowerCase(Locale.ROOT).endsWith("payload.bin") ? "🧬" : "📦");
            ie.setTextSize(13);
            ie.setGravity(Gravity.CENTER);
            icon.addView(ie, new FrameLayout.LayoutParams(-1, -1));
            row.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));
            // 名称 + 路径
            LinearLayout textBox = new LinearLayout(this);
            textBox.setOrientation(LinearLayout.VERTICAL);
            textBox.setPadding(dp(10), 0, dp(8), 0);
            TextView name = new TextView(this);
            name.setText(e.getName());
            name.setTextSize(13.5f);
            name.setTypeface(null, 1);
            name.setTextColor(0xff17334f);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            textBox.addView(name, new LinearLayout.LayoutParams(-1, -2));
            TextView path = new TextView(this);
            path.setText(fullPath);
            path.setTextSize(10f);
            path.setTextColor(0xff8fa1b8);
            path.setSingleLine(true);
            path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            textBox.addView(path, new LinearLayout.LayoutParams(-1, -2));
            row.addView(textBox, new LinearLayout.LayoutParams(0, -2, 1f));
            // 大小 + 选中标记
            LinearLayout right = new LinearLayout(this);
            right.setOrientation(LinearLayout.VERTICAL);
            right.setGravity(Gravity.END);
            TextView size = new TextView(this);
            size.setText(fmtSize(e.getSize()));
            size.setTextSize(11.5f);
            size.setTypeface(null, 1);
            size.setTextColor(0xff2f9c8f);
            right.addView(size, new LinearLayout.LayoutParams(-2, -2));
            TextView check = new TextView(this);
            check.setText("✓");
            check.setTextSize(14);
            check.setTypeface(null, 1);
            check.setTextColor(0xff1f7d72);
            check.setVisibility(View.GONE);
            right.addView(check, new LinearLayout.LayoutParams(-2, -2));
            row.addView(right, new LinearLayout.LayoutParams(-2, -2));
            row.setOnClickListener(v -> {
                Haptics.perform(v);
                // v3.30.23：点选再点取消（toggle）
                if (fullPath.equals(binPath)) {
                    binPath = null;
                    log("⊘ " + t("已取消选择", "Deselected") + " " + e.getName());
                } else {
                    binPath = fullPath;
                    log("📥 " + e.getName() + " · " + fmtSize(e.getSize()));
                }
                renderSourceSelection();
            });
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(-1, -2);
            rLp.topMargin = dp(6);
            sourceList.addView(row, rLp);
        }
        renderSourceSelection();
    }

    /** 刷新源文件行选中态 */
    private void renderSourceSelection() {
        for (int i = 0; i < sourceList.getChildCount(); i++) {
            View c = sourceList.getChildAt(i);
            if (!(c instanceof LinearLayout) || !(c.getTag() instanceof String)) continue;
            boolean on = c.getTag().equals(binPath);
            android.graphics.drawable.GradientDrawable rb = new android.graphics.drawable.GradientDrawable();
            rb.setColor(on ? 0x3335A8C4 : 0x22FFFFFF);
            rb.setCornerRadius(dp(14));
            rb.setStroke(Math.max(1, dp(1)), on ? 0xFF35A8C4 : 0x33FFFFFF);
            c.setBackground(rb);
            LinearLayout right = (LinearLayout) ((LinearLayout) c).getChildAt(2);
            right.getChildAt(1).setVisibility(on ? View.VISIBLE : View.GONE);
        }
        int total = sourceList.getChildCount();
        sourceCount.setText(total == 0 ? "" : " " + (binPath != null ? 1 : 0) + "/" + total);
    }

    // ================= 解析（fastList → payload_dumper --list → JNI） =================

    /** nativeLibraryDir 下的 Rust 版 payload_dumper（root 链路，支持 bin/zip 直读） */
    private String dumperPath() {
        return new File(getApplicationInfo().nativeLibraryDir, "libpayload_dumper.so").getAbsolutePath();
    }

    /** 解析 payload_dumper --list 表格输出：「boot<空白>1.00 MB」每行一个分区 */
    private static List<PayloadExtractor.PartitionInfo> parseDumperList(String output) {
        List<PayloadExtractor.PartitionInfo> out = new ArrayList<>();
        if (output == null) return out;
        for (String raw : output.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("Partition Name") || line.startsWith("---")) continue;
            // 「name   1.00 MB」或「name   Unknown」
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^([A-Za-z0-9_.\\-]+)\\s+(.+)$").matcher(line);
            if (!m.matches()) continue;
            String name = m.group(1);
            if (name.equalsIgnoreCase("Partition")) continue;
            out.add(new PayloadExtractor.PartitionInfo(name, readableToBytes(m.group(2)), null));
        }
        return out;
    }

    /** 「1.00 MB / 465.29 GB / Unknown」→ 字节数（1024 进位；Unknown → 0） */
    private static long readableToBytes(String s) {
        if (s == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^([\\d.]+)\\s*(B|KB|MB|GB|TB)$", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(s.trim());
        if (!m.matches()) return 0;
        try {
            double v = Double.parseDouble(m.group(1));
            String u = m.group(2).toUpperCase(Locale.ROOT);
            long mul = 1;
            switch (u) {
                case "TB": mul = 1L << 40; break;
                case "GB": mul = 1L << 30; break;
                case "MB": mul = 1L << 20; break;
                case "KB": mul = 1L << 10; break;
            }
            return (long) (v * mul);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void parseFile() {
        if (running.get()) { toast(t("正在执行中", "Busy")); return; }
        if (binPath == null || binPath.isEmpty()) {
            toast(t("请先选择 payload.bin / OTA zip 文件", "Pick a payload.bin / OTA zip file first"));
            return;
        }
        final String path = binPath;
        log("🔍 " + t("开始解析", "Parse") + ": " + path);
        running.set(true);
        cancelFlag.set(false);
        parseBtn.setEnabled(false);
        status.setText(t("正在解析 ...", "Parsing..."));
        status.setTextColor(0xff5a6b82);
        notify(t("正在解析 ...", "Parsing..."), true, true, 0, 0);
        io.execute(() -> {
            try {
                if (extractor != null) { try { extractor.close(); } catch (Exception ignored) {} extractor = null; }
                String input = path;
                log("… " + t("正在读取 payload", "Reading payload") + " ...");
                // v3.30.35：解析链（毫秒级优先）：
                // ① root 放行（chmod+chown，供 Java/JNI 直读）
                // ② Java 直读 manifest（字段号已校准：partitions=13 / new_info=7）—— 裸 payload.bin
                // ③ payload_dumper --list（Rust，root，支持 OTA zip 直读）
                // ④ JNI 兜底
                com.topjohnwu.superuser.Shell.cmd(
                        "chmod 666 " + DnaTools.quote(path)
                                + "; chown " + android.os.Process.myUid() + " " + DnaTools.quote(path)
                                + "; true").exec();
                List<PayloadExtractor.PartitionInfo> parts = PayloadExtractor.fastListPartitions(input);
                final boolean incremental = PayloadExtractor.fastIsIncremental(input);
                PayloadExtractor.Metadata meta = null;
                if (parts == null || parts.isEmpty()) {
                    log("… " + t("改用 payload_dumper 解析", "payload_dumper fallback") + " ...");
                    DnaTools.Result r = DnaTools.run(this,
                            DnaTools.quote(dumperPath()) + " --list " + DnaTools.quote(path),
                            line -> kotlin.Unit.INSTANCE,   // 静默（日志只留汇总）
                            () -> cancelFlag.get(), 120000);
                    if (r.getSuccess()) parts = parseDumperList(r.getOutput());
                }
                if (parts == null || parts.isEmpty()) {
                    // 回退 3（异常格式）：JNI open + listPartitions
                    log("… " + t("改用 JNI 解析", "JNI fallback") + " ...");
                    PayloadExtractor ex = new PayloadExtractor();
                    boolean ok = ex.open(input);
                    if (!ok) {
                        File cache = new File(getCacheDir(), "bin_parse_input");
                        com.topjohnwu.superuser.Shell.cmd(
                                "cp -f " + DnaTools.quote(path) + " " + DnaTools.quote(cache.getAbsolutePath())
                                        + " && chmod 644 " + DnaTools.quote(cache.getAbsolutePath())).exec();
                        if (cache.isFile() && cache.length() > 0) {
                            input = cache.getAbsolutePath();
                            ex = new PayloadExtractor();
                            ok = ex.open(input);
                        }
                    }
                    if (!ok) throw new IllegalStateException(t("无法打开文件（损坏或非 payload 镜像）", "cannot open (corrupt or not a payload)"));
                    log("… " + t("正在读取分区表", "Reading partition table") + " ...");
                    parts = ex.listPartitions(true);
                    meta = ex.getMetadata();
                    extractor = ex;
                } else {
                    // 快速路径：extractPartition 自带 input 参数，无需 open 句柄
                    extractor = new PayloadExtractor();
                }
                final String finalInput = input;
                final List<PayloadExtractor.PartitionInfo> fParts = parts;
                final PayloadExtractor.Metadata fMeta = meta;
                main.post(() -> {
                    openInput = finalInput;
                    partitions.clear();
                    checked.clear();
                    if (fParts != null) partitions.addAll(fParts);
                    if (fMeta != null)
                        log("ℹ payload v" + (fMeta.getVersion() != null ? fMeta.getVersion() : "?")
                                + " · " + t("分区数", "partitions") + ": " + fMeta.getPartitionCount());
                    if (incremental)
                        log("⚠ " + t("检测到增量（delta）包，请使用「分解增量包」功能", "Delta payload detected, use the Incremental page"));
                    log("✓ " + t("解析完成", "Parsed") + " · " + partitions.size()
                            + t(" 个分区，请在弹窗勾选要提取的 img", " partitions, select img in dialog"));
                    status.setText("✓ " + t("解析完成", "Parsed") + " · " + partitions.size() + t(" 个分区", " partitions"));
                    status.setTextColor(0xff1d7a4f);
                    notifyDone(true, t("解析完成", "Parsed") + " · " + partitions.size() + t(" 个分区", " partitions"));
                    showPartitionDialog();
                });
            } catch (final Exception e) {
                final String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                main.post(() -> {
                    log("✗ " + t("解析失败", "Parse failed") + ": " + msg);
                    status.setText("✗ " + t("解析失败", "Parse failed"));
                    status.setTextColor(0xffa33b3b);
                    notifyDone(false, t("解析失败", "Parse failed"));
                    toast(t("解析失败", "Parse failed"));
                });
            } finally {
                main.post(() -> { running.set(false); parseBtn.setEnabled(true); });
            }
        });
    }

    // ================= 分区选择弹窗（v3.30.22） =================

    /** 解析完成后弹窗勾选分区（仅名称 + 大小，不显示哈希），底部「确定」即开始提取 */
    private void showPartitionDialog() {
        // v3.30.25 修复 BadTokenException：解析完成回调时页面可能已退出，此时不能再弹窗
        if (isFinishing() || isDestroyed()) return;
        if (partitions.isEmpty()) {
            toast(t("请先点「开始解析」", "Tap Parse first"));
            return;
        }
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.setCancelable(true);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(14));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xF2e9f0f7);
        bg.setCornerRadius(dp(24));
        bg.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
        panel.setBackground(bg);

        // 列表容器 + 渲染器（头部按钮也复用）
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        final Runnable[] render = new Runnable[1];

        // 标题
        TextView title = new TextView(this);
        title.setText("🧬 " + t("选择要提取的分区", "Select partitions to extract"));
        title.setTextSize(16);
        title.setTypeface(null, 1);
        title.setTextColor(0xff17334f);
        title.setPadding(dp(2), 0, 0, dp(10));
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));

        // 头部：计数 + 全选 / 清空
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        final TextView count = new TextView(this);
        count.setTextSize(12f);
        count.setTypeface(null, 1);
        count.setTextColor(0xff1f7d72);
        head.addView(count, new LinearLayout.LayoutParams(0, -2, 1f));
        Button allBtn = pillButton(t("全选", "All"), 11.5f, 0xff1f7d72, dp(52), dp(30));
        allBtn.setOnClickListener(v -> {
            Haptics.perform(v);
            checked.clear();
            for (PayloadExtractor.PartitionInfo p : partitions) checked.add(p.getName());
            render[0].run();
        });
        head.addView(allBtn, new LinearLayout.LayoutParams(dp(52), dp(30)));
        Button noneBtn = pillButton(t("清空", "None"), 11.5f, 0xffa33b3b, dp(52), dp(30));
        android.widget.LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(dp(52), dp(30));
        nLp.leftMargin = dp(6);
        head.addView(noneBtn, nLp);
        noneBtn.setOnClickListener(v -> {
            Haptics.perform(v);
            checked.clear();
            render[0].run();
        });
        panel.addView(head, new LinearLayout.LayoutParams(-1, -2));

        // 可滚动分区列表：圆点勾选 + 名称 + 大小（不显示哈希）
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        render[0] = () -> {
            list.removeAllViews();
            for (final PayloadExtractor.PartitionInfo p : partitions) {
                final boolean on = checked.contains(p.getName());
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(11), dp(10), dp(10), dp(10));
                android.graphics.drawable.GradientDrawable rb = new android.graphics.drawable.GradientDrawable();
                rb.setColor(on ? 0x3335A8C4 : 0x22FFFFFF);
                rb.setCornerRadius(dp(14));
                rb.setStroke(Math.max(1, dp(1)), on ? 0xFF35A8C4 : 0x33FFFFFF);
                row.setBackground(rb);
                // 勾选圆点
                View dot = new View(this);
                android.graphics.drawable.GradientDrawable db = new android.graphics.drawable.GradientDrawable();
                db.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                if (on) { db.setColor(0xFF35A8C4); db.setStroke(Math.max(1, dp(1)), 0xB3FFFFFF); }
                else { db.setColor(0x00000000); db.setStroke(Math.max(1, dp(1)), 0x668fa1b8); }
                dot.setBackground(db);
                row.addView(dot, new LinearLayout.LayoutParams(dp(18), dp(18)));
                // 名称
                TextView name = new TextView(this);
                name.setText(p.getName() + ".img");
                name.setTextSize(13.5f);
                name.setTypeface(null, on ? 1 : 0);
                name.setTextColor(on ? 0xff14524b : 0xff17334f);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                name.setPadding(dp(10), 0, dp(6), 0);
                row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
                // 大小
                TextView size = new TextView(this);
                size.setText(fmtSize(p.getSize()));
                size.setTextSize(11.5f);
                size.setTypeface(null, 1);
                size.setTextColor(0xff2f9c8f);
                row.addView(size, new LinearLayout.LayoutParams(-2, -2));
                row.setOnClickListener(v -> {
                    Haptics.perform(v);
                    if (checked.contains(p.getName())) checked.remove(p.getName());
                    else checked.add(p.getName());
                    render[0].run();
                });
                LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(-1, -2);
                rLp.topMargin = dp(6);
                list.addView(row, rLp);
            }
            count.setText(t("已选", "Selected") + " " + checked.size() + "/" + partitions.size());
        };
        render[0].run();

        // 底部：取消 + 确定（确定即开始提取）
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        Button cancel = pillButton(t("取消", "Cancel"), 13f, 0xff5a6b82, dp(76), dp(46));
        cancel.setOnClickListener(v -> { Haptics.perform(v); dialog.dismiss(); });
        btnRow.addView(cancel, new LinearLayout.LayoutParams(dp(76), dp(46)));
        Button ok = gradientButton("✓  " + t("确定", "Extract"), new int[]{0xFF2f9c8f, 0xFF1d6b46}, dp(16));
        ok.setOnClickListener(v -> {
            Haptics.perform(v);
            if (checked.isEmpty()) {
                toast(t("请先勾选要提取的分区", "Check partitions first"));
                return;
            }
            dialog.dismiss();
            extract();
        });
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        okLp.leftMargin = dp(10);
        btnRow.addView(ok, okLp);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(-1, -2);
        brLp.topMargin = dp(12);
        panel.addView(btnRow, brLp);

        dialog.setContentView(panel, new LinearLayout.LayoutParams(-1, dp(500)));
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        try {
            dialog.show();
            dialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.94f), dp(500));
        } catch (Exception e) {
            // v3.30.25：页面销毁竞态下静默放弃弹窗，不崩溃
        }
    }

    // ================= 提取（v3.30.36：payload_dumper root 二进制，彻底绕开 FUSE） =================

    private void extract() {
        if (project == null) {
            toast(t("请先选择输出工程", "Select an output project first"));
            showProjectPicker();
            return;
        }
        if (partitions.isEmpty() || openInput == null) {
            toast(t("请先点「开始解析」", "Tap Parse first"));
            parseFile();
            return;
        }
        if (checked.isEmpty()) {
            toast(t("请先勾选要提取的分区", "Check partitions to extract first"));
            return;
        }
        final List<String> ordered = new ArrayList<>();
        for (PayloadExtractor.PartitionInfo p : partitions)
            if (checked.contains(p.getName())) ordered.add(p.getName());
        final String outDir = DnaTools.WORK_ROOT + "/" + project;
        // v3.40.15：改回 JNI 提取（libpayload_extract_jni.so 多线程，实测 16.5G/61 分区 94s，
        // 比 root payload_dumper 逐分区快一个量级）。输入经 root chmod 底层真实路径放行
        // （/sdcard FUSE 权限根治），输出工程目录 /data/PDNA 递归放行后 app 直写。
        // 保留 v3.30.39 的逐分区顺序 UX：⏳ 正在提取 [i/n] → ✓ 提取完成，依次推进。
        log("▶ " + t("开始提取", "Extracting") + " " + ordered.size() + t(" 个分区 → ", " partition(s) → ") + project);
        running.set(true);
        cancelFlag.set(false);
        runBtn.setText("■  " + t("取消", "Cancel"));
        parseBtn.setEnabled(false);
        status.setText(t("正在提取 ...", "Extracting..."));
        status.setTextColor(0xff5a6b82);

        io.execute(() -> {
            final long startMs = System.currentTimeMillis();
            final String rawInput = binPath != null ? binPath : openInput;
            String jniInput = DnaTools.ensureJniReadable(this, rawInput,
                    msg -> { main.post(() -> log(msg)); return kotlin.Unit.INSTANCE; });
            if (jniInput == null) {
                main.post(() -> {
                    running.set(false);
                    parseBtn.setEnabled(true);
                    runBtn.setText("▶  " + t("选择分区并提取", "Select & Extract"));
                    log("✗ " + t("输入文件无法读取（root 授权异常？）", "Input unreadable (root?)"));
                    status.setText("✗ " + t("读取失败", "Read failed"));
                    status.setTextColor(0xffa33b3b);
                });
                return;
            }
            // 输出工程目录放行：/data/PDNA 为 root 属主，app（JNI）需可写
            com.topjohnwu.superuser.Shell.cmd(
                    "mkdir -p " + DnaTools.quote(outDir)
                            + "; chmod -R a+rwX " + DnaTools.quote(DnaTools.WORK_ROOT)).exec();
            int okCount = 0;
            String lastErr = null;
            for (int i = 0; i < ordered.size(); i++) {
                if (cancelFlag.get()) break;
                final String n = ordered.get(i);
                final int no = i + 1;
                final long token = 900000L + i;   // 仅用于 JNI 进度查询（本页不用），避让全局 token
                main.post(() -> {
                    log("⏳ " + t("正在提取", "Extracting") + " [" + no + "/" + ordered.size() + "] " + n + ".img");
                    status.setText("⏳ " + t("正在提取", "Extracting") + " " + n + ".img [" + no + "/" + ordered.size() + "]");
                });
                notify(t("正在提取", "Extracting") + " " + n + ".img [" + no + "/" + ordered.size() + "]",
                        true, false, 0, 0);
                try {
                    //noinspection ResultOfMethodCallIgnored
                    new File(outDir, n + ".img").delete();   // 清残留，防误判
                    new PayloadExtractor().extractPartition(jniInput, outDir, n, 4, false, token);
                    if (cancelFlag.get()) break;
                    long sz = new File(outDir, n + ".img").length();
                    if (sz > 0) {
                        final long size = sz;
                        main.post(() -> log("✓ " + n + ".img (" + fmtSizeShort(size) + ") " + t("提取完成", "extracted")));
                        okCount++;
                    } else {
                        lastErr = "file not generated";
                        final String msg = lastErr;
                        main.post(() -> log("✗ " + n + ".img " + t("提取失败", "failed") + ": " + msg));
                    }
                } catch (Throwable e) {
                    String m = e.getMessage();
                    if (m == null) m = e.toString();
                    lastErr = m;
                    final String msg = m;
                    main.post(() -> {
                        log("✗ " + n + ".img " + t("提取失败", "failed") + ": " + msg);
                        if (msg.contains("delta") || msg.toLowerCase(Locale.ROOT).contains("incremental")) {
                            log("  " + t("提示：增量包请用「分解增量包」", "Hint: use Incremental unpack"));
                        }
                    });
                }
            }
            // 兜底缓存副本用完即清（jniInput == rawInput 时无缓存）
            if (!jniInput.equals(rawInput)) {
                //noinspection ResultOfMethodCallIgnored
                new File(jniInput).delete();
            }
            final boolean cancelled = cancelFlag.get();
            final int ok = okCount;
            final String err = lastErr;
            final long elapsed = (System.currentTimeMillis() - startMs) / 1000;
            main.post(() -> {
                running.set(false);
                parseBtn.setEnabled(true);
                runBtn.setText("▶  " + t("选择分区并提取", "Select & Extract"));
                if (cancelled) {
                    log("■ " + t("已取消", "Cancelled"));
                    status.setText("■ " + t("已取消", "Cancelled"));
                    status.setTextColor(0xffa33b3b);
                    notifyDone(false, t("已取消", "Cancelled"));
                } else if (ok == ordered.size()) {
                    log("✓ " + t("提取完成，文件位于", "Extraction done, files at") + ": " + outDir);
                    log("ℹ " + t("耗时", "Time") + " " + elapsed + "s · " + ok + t(" 个镜像", " image(s)"));
                    status.setText("✓ " + t("提取完成", "Done") + " · " + ok);
                    status.setTextColor(0xff1d7a4f);
                    notifyDone(true, t("提取完成", "Done") + " · " + ok + t(" 个镜像", " image(s)"));
                    toast(t("提取完成", "Done"));
                    if (deleteSource != null && deleteSource.isChecked() && binPath != null) {
                        com.topjohnwu.superuser.Shell.cmd("rm -f " + DnaTools.quote(binPath)).exec();
                        log(t("已删除源文件", "Source deleted") + ": " + binPath);
                    }
                } else if (ok > 0) {
                    log("⚠ " + t("部分分区提取失败", "Some partitions failed") + ": "
                            + (ordered.size() - ok) + "/" + ordered.size());
                    status.setText("⚠ " + t("部分提取完成", "Partial") + " · " + ok + "/" + ordered.size());
                    status.setTextColor(0xffa0662d);
                    notifyDone(false, t("部分分区提取失败", "Some partitions failed") + " "
                            + (ordered.size() - ok) + "/" + ordered.size());
                } else {
                    log("✗ " + t("提取失败", "Extraction failed") + ": " + (err != null ? err : "unknown"));
                    status.setText("✗ " + t("提取失败", "Failed"));
                    status.setTextColor(0xffa33b3b);
                    notifyDone(false, t("提取失败", "Failed"));
                    toast(t("提取失败", "Failed"));
                }
                refreshSources();
            });
        });
    }

    /** dna 风格短格式：465.3M / 9.1G */
    private String fmtSizeShort(long bytes) {
        if (bytes < 1024) return bytes + "B";
        double v = bytes / 1024.0;
        String[] u = {"K", "M", "G", "T"};
        int i = 0;
        while (v >= 1024 && i < 3) { v /= 1024; i++; }
        return String.format(Locale.US, "%.1f%s", v, u[i]);
    }

    // ================= 工程 / 工具 =================

    private void showProjectPicker() {
        // v3.30.25：页面销毁后不弹窗
        if (isFinishing() || isDestroyed()) return;
        List<String> projects = DnaTools.listProjects();
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.setCancelable(true);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(16));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xF2e9f0f7);
        bg.setCornerRadius(dp(24));
        bg.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
        panel.setBackground(bg);
        TextView title = new TextView(this);
        title.setText(t("选择工程", "Select project"));
        title.setTextSize(16);
        title.setTypeface(null, 1);
        title.setTextColor(0xff17334f);
        title.setPadding(0, 0, 0, dp(10));
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));
        ScrollView ls = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ls.addView(list, new ScrollView.LayoutParams(-1, -2));
        if (projects.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(t("暂无工程，请先在 DNA 页新建工程", "No projects yet. Create one on DNA page"));
            empty.setTextSize(13);
            empty.setTextColor(0xff5a6b82);
            empty.setPadding(0, dp(8), 0, dp(8));
            list.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        }
        for (final String name : projects) {
            boolean cur = name.equals(project);
            TextView row = new TextView(this);
            row.setText((cur ? "● " : "○ ") + name);
            row.setTextSize(14);
            row.setTextColor(cur ? 0xff14524b : 0xff17334f);
            row.setSingleLine(true);
            row.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(13), dp(12), dp(13));
            android.graphics.drawable.GradientDrawable rb = new android.graphics.drawable.GradientDrawable();
            rb.setColor(cur ? 0x332f9c8f : 0x22FFFFFF);
            rb.setCornerRadius(dp(14));
            rb.setStroke(Math.max(1, dp(1)), cur ? 0x662f9c8f : 0x33FFFFFF);
            row.setBackground(rb);
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(-1, -2);
            rLp.bottomMargin = dp(6);
            list.addView(row, rLp);
            row.setOnClickListener(v -> {
                Haptics.perform(v);
                project = name;
                DnaTools.setCurrentProject(this, name);
                dialog.dismiss();
                renderProject();
                binPath = null;
                refreshSources();
                partitions.clear();
                checked.clear();
                log(t("已切换工程", "Project switched") + ": " + name);
            });
        }
        panel.addView(ls, new LinearLayout.LayoutParams(-1, 0, 1f));
        Button close = pillButton(t("关闭", "Close"), 13f, 0xff172b4d, -1, dp(44));
        close.setOnClickListener(v -> dialog.dismiss());
        panel.addView(close, new LinearLayout.LayoutParams(-1, dp(44)));
        dialog.setContentView(panel, new LinearLayout.LayoutParams(-1, dp(460)));
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        try {
            dialog.show();
            dialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.94f), dp(460));
        } catch (Exception e) {
            // v3.30.25：页面销毁竞态下静默放弃弹窗，不崩溃
        }
    }

    private void log(String line) {
        main.post(() -> {
            consoleText.append(line + "\n");
            consoleScroll.post(() -> consoleScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    /** 大小格式化：整数值省小数（392 KB / 8 MB / 1.5 GB） */
    private static String fmtSize(long b) {
        if (b < 1024) return b + " B";
        double kb = b / 1024.0;
        if (kb < 1024) return kb < 10 && kb != Math.floor(kb)
                ? String.format(Locale.US, "%.1f KB", kb) : String.format(Locale.US, "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return mb < 10 && mb != Math.floor(mb)
                ? String.format(Locale.US, "%.1f MB", mb) : String.format(Locale.US, "%.0f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }
}
