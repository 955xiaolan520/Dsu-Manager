package com.probiotics.xiaoni;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DNA · 合成 OTA 卡刷包独立二级页（v3.43.0；v3.43.1 无模板从零生成；v3.43.2 三种元数据来源）。
 * 对齐 snowwolf725/Payload_Repack_Tool 的 delta_generator 链路：
 * img 集 → 未签名 payload.bin → 导出哈希 → Java PKCS#1 v1.5 签名（prehashed，AOSP testkey）
 * → 注入签名 → payload_properties.txt → 组装 zip → whole-file 签名
 * （signapk -w 格式，TWRP/recovery 可刷）。
 *
 * 元数据来源三模式（v3.43.2，决定 metadata 里的机型信息）：
 * - system.img：挂载 img 目录的 system.img 读目标机 build.prop（给其他手机打包）；
 * - 本机 getprop：给正在使用的这台手机打包；
 * - 模板：继承既有 OTA.zip 的 metadata 与安装脚本。
 *
 * 全程 root CLI（libdelta_generator.so，pie 静态可执行）：root 直读 img 集、
 * root 直写 payload；签名与 zip 重建在 Java 层完成（{@link OtaPacker}）。
 */
public final class DnaOtaActivity extends BaseActivity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService io =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "dna-ota");
                t.setDaemon(true);
                return t;
            });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);

    private static final String NOTE_CHANNEL = "dna_ota_channel";
    private static final int NOTE_ID = 4022;

    // 数据
    private String project;          // 输出工程
    private String tplPath;          // 模板 OTA.zip（提供 metadata/脚本骨架）
    private String imgDir;           // img 集目录（分区镜像 *.img 所在目录）
    private String keyPk8Path;        // 自定义私钥 .pk8 / PEM（v3.43.4，null = testkey）
    private String keyCertPath;       // 自定义证书 .x509.pem / DER
    private int ramLimitMb;           // delta_generator 内存上限（MB，0 = 不限）

    // UI
    private TextView projectName, projectOut;
    private TextView tplText, imgDirText, status, imgCount;
    private FrameLayout progressTrack;
    private View progressFill;
    private TextView consoleText;
    private ScrollView consoleScroll;
    private Button runBtn, tplPickBtn, imgPickBtn;
    private EditText deviceInput;      // 从零生成时的设备代号（留空 = 自动检测）
    private LinearLayout advBox;       // 高级覆盖区（v3.43.3，可展开）
    private EditText fpInput, incInput, tsInput, splInput;   // 手动覆盖：指纹/版本/时间戳/SPL
    private LinearLayout tplRowBox;    // 模板选择行（仅 tpl 模式显示）
    private TextView devLabel;         // 设备代号标签（非 tpl 模式显示）
    private EditText zipNameInput;     // 最终卡刷包 zip 文件名（v3.43.4）
    private TextView keyStatusText;    // 签名密钥状态行（v3.43.4）
    private final Map<String, Button> ramChips = new LinkedHashMap<>();  // 内存上限 chips
    // v3.43.2 元数据来源三模式：tpl=模板继承 / local=本机 getprop / system=目标机 system.img
    private String metaSource = "system";
    private final Map<String, Button> srcChips = new LinkedHashMap<>();
    private TextView srcHintText;     // 模式说明行（随 chip 切换刷新）

    /** 元数据来源 chip（单选高亮） */
    private void addSrcChip(LinearLayout parent, String key, String label) {
        Button b = pillButton(label, 13.5f, 0xff1f5f8f, dp(106), dp(38));
        b.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(106), dp(38));
        if (srcChips.size() > 0) lp.leftMargin = dp(6);
        b.setOnClickListener(v -> {
            Haptics.perform(v);
            if (metaSource.equals(key)) return;
            metaSource = key;
            refreshSrcUi();
        });
        srcChips.put(key, b);
        parent.addView(b, lp);
    }

    /** 按当前 metaSource 刷新 chip 高亮 / 说明文案 / 行显隐 */
    private void refreshSrcUi() {
        for (Map.Entry<String, Button> e : srcChips.entrySet()) {
            boolean on = e.getKey().equals(metaSource);
            e.getValue().setAlpha(on ? 1f : 0.45f);
        }
        boolean tpl = "tpl".equals(metaSource);
        if (tplRowBox != null) tplRowBox.setVisibility(tpl ? View.VISIBLE : View.GONE);
        if (devLabel != null) devLabel.setVisibility(tpl ? View.GONE : View.VISIBLE);
        if (deviceInput != null) deviceInput.setVisibility(tpl ? View.GONE : View.VISIBLE);
        if (advBox != null) advBox.setVisibility(View.GONE);   // 切模式时收起高级覆盖
        if (srcHintText != null) {
            String h;
            switch (metaSource) {
                case "tpl":
                    h = t("继承模板 OTA.zip 的 metadata 与安装脚本（同机型原厂包最佳）",
                            "Inherit metadata & scripts from a template OTA.zip (same-device stock zip preferred)");
                    break;
                case "local":
                    h = t("读取本机 getprop（给本机打包用；代号可手动覆盖）",
                            "Read local getprop (for this device; codename can be overridden)");
                    break;
                default:
                    h = t("从 img 目录的 system.img 读目标机 build.prop（给其他手机打包用；"
                            + "支持 ext4 / erofs / sparse，代号可手动覆盖）",
                            "Read target build.prop from system.img in the img dir (for other devices; "
                                    + "ext4 / erofs / sparse supported, codename overridable)");
            }
            srcHintText.setText(h);
        }
        if (deviceInput != null) {
            deviceInput.setHint("system".equals(metaSource)
                    ? t("留空 = 从 system.prop 读取", "blank = from system build.prop")
                    : t("留空 = 自动检测（getprop）", "blank = auto (getprop)"));
        }
    }

    /**
     * 高级覆盖输入框（label + ro 参数来源说明 + EditText，追加到 parent）。
     * v3.43.4：label 下方加一行「留空 = 自动读取哪条 ro 属性」的说明（写明取值顺序）。
     */
    private EditText advField(LinearLayout parent, String label, String roHint, String hint) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(12.5f);
        tv.setTypeface(null, 1);
        tv.setTextColor(0xff3d5a7a);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(-1, -2);
        tvLp.topMargin = dp(8);
        parent.addView(tv, tvLp);
        if (roHint != null) {
            TextView ro = new TextView(this);
            ro.setText(roHint);
            ro.setTextSize(11f);
            ro.setTextColor(0xff6b84a3);
            LinearLayout.LayoutParams roLp = new LinearLayout.LayoutParams(-1, -2);
            roLp.topMargin = dp(1);
            parent.addView(ro, roLp);
        }
        EditText et = new EditText(this);
        et.setTextSize(13.5f);
        et.setTextColor(0xff17334f);
        et.setHintTextColor(0xff8fa3bb);
        et.setHint(hint);
        et.setSingleLine(true);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0x26FFFFFF);
        bg.setCornerRadius(dp(10));
        bg.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
        et.setBackground(bg);
        et.setPadding(dp(10), dp(9), dp(10), dp(9));
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(-1, -2);
        etLp.topMargin = dp(4);
        parent.addView(et, etLp);
        return et;
    }

    /** 内存上限 chip（单选高亮，v3.43.4） */
    private void addRamChip(LinearLayout parent, final int mb, String label) {
        Button b = pillButton(label, 12f, 0xff7a4a1f, dp(56), dp(30));
        b.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(56), dp(30));
        if (ramChips.size() > 0) lp.leftMargin = dp(5);
        b.setOnClickListener(v -> {
            Haptics.perform(v);
            ramLimitMb = mb;
            refreshRamChips();
        });
        ramChips.put(String.valueOf(mb), b);
        parent.addView(b, lp);
    }

    private void refreshRamChips() {
        for (Map.Entry<String, Button> e : ramChips.entrySet()) {
            boolean on = Integer.parseInt(e.getKey()) == ramLimitMb;
            e.getValue().setAlpha(on ? 1f : 0.45f);
        }
    }

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
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0x00000000);
        getWindow().setNavigationBarColor(0x220b131f);
        project = DnaTools.currentProject(this);
        createNoteChannel();
        buildUi();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        cancelNote();
    }

    // ================= 通知 =================

    private void createNoteChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    NOTE_CHANNEL, "DNA OTA 打包进度", android.app.NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("显示 OTA 卡刷包打包任务实时状态");
            channel.setShowBadge(false);
            getSystemService(android.app.NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private void notify(String text, boolean ongoing, boolean indeterminate, int progress, int max) {
        try {
            android.app.Notification.Builder b = new android.app.Notification.Builder(this, NOTE_CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(t("合成 OTA 卡刷包", "Build OTA zip"))
                    .setContentText(text)
                    .setOngoing(ongoing)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(!ongoing)
                    .setProgress(max, progress, indeterminate);
            Intent it = new Intent(this, DnaOtaActivity.class);
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

    // ================= UI =================

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
        bg.setCornerRadius(Math.min(Math.max(w, 1), Math.max(h, 1)) / 2);
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

        consoleScroll = new DnaActivity.BoundedScrollView(this, 0);
        consoleText = new TextView(this);
        consoleText.setTypeface(android.graphics.Typeface.MONOSPACE);
        consoleText.setTextSize(11.5f);
        consoleText.setTextColor(0xff2c3e57);
        consoleText.setLineSpacing(dp(2), 1f);
        consoleText.setHorizontallyScrolling(false);
        consoleText.setPadding(dp(2), dp(4), dp(2), dp(6));
        consoleScroll.addView(consoleText, new ScrollView.LayoutParams(-1, -2));
        card.addView(consoleScroll, new LinearLayout.LayoutParams(-1, dp(300)));
        return card;
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
        title.setText(t("DNA · 合成 OTA 卡刷包", "DNA · Build OTA zip"));
        title.setTextSize(19);
        title.setTypeface(null, 1);
        title.setTextColor(0xff0f1e36);
        title.setPadding(dp(12), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView badge = new TextView(this);
        badge.setText("📦 OTA");
        badge.setTextSize(12f);
        badge.setTypeface(null, 1);
        badge.setTextColor(0xff8a5a00);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(12), 0, dp(12), 0);
        android.graphics.drawable.GradientDrawable badgeBg = new android.graphics.drawable.GradientDrawable();
        badgeBg.setCornerRadius(dp(18));
        badgeBg.setColor(0x73fff4e0);
        badgeBg.setStroke(Math.max(1, dp(1)), 0x80c8963c);
        badge.setBackground(badgeBg);
        bar.addView(badge, new LinearLayout.LayoutParams(-2, dp(36)));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, -2);
        barLp.bottomMargin = dp(12);
        content.addView(bar, barLp);

        // ---- 置顶风险警告（v3.43.4：从未真机验证，不劝退但必须醒目） ----
        TextView warn = new TextView(this);
        warn.setText("⚠ " + t("此功能从未在真机验证过 —— 不要乱打包：约 80% 概率卡死手机（变砖）。"
                        + "分区必须 ≥ 官方 OTA 包，刷入前务必先备份。",
                "NEVER verified on a real device — do NOT pack casually: ~80% chance of bricking. "
                        + "Partitions must cover the stock OTA set; backup before flashing."));
        warn.setTextSize(13f);
        warn.setTypeface(null, 1);
        warn.setTextColor(0xffB71C1C);
        warn.setGravity(Gravity.CENTER);
        warn.setLineSpacing(dp(2), 1f);
        android.graphics.drawable.GradientDrawable warnBg = new android.graphics.drawable.GradientDrawable();
        warnBg.setColor(0x33FFD9D9);
        warnBg.setCornerRadius(dp(14));
        warnBg.setStroke(Math.max(1, dp(1)), 0x80D32F2F);
        warn.setBackground(warnBg);
        warn.setPadding(dp(12), dp(10), dp(12), dp(10));
        content.addView(warn, new LinearLayout.LayoutParams(-1, -2));

        // ---- 流程提示条 ----
        TextView flow = new TextView(this);
        flow.setText("① " + t("选 img 目录", "img dir") + "  →  ② " + t("合成卡刷包", "Build zip")
                + "   ·   " + t("模板可选（不选 = 从零生成）", "template optional"));
        flow.setTextSize(13f);
        flow.setTextColor(0xff9A6A1E);
        flow.setTypeface(null, 1);
        flow.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable flowBg = new android.graphics.drawable.GradientDrawable();
        flowBg.setColor(0x26FFFFFF);
        flowBg.setCornerRadius(dp(14));
        flow.setBackground(flowBg);
        flow.setPadding(dp(10), dp(9), dp(10), dp(9));
        LinearLayout.LayoutParams flowLp = new LinearLayout.LayoutParams(-1, -2);
        flowLp.topMargin = dp(8);
        content.addView(flow, flowLp);

        // ---- 工程卡 ----
        LinearLayout projCard = glassCard();
        projCard.setOnClickListener(v -> { Haptics.perform(v); showProjectPicker(); });
        LinearLayout.LayoutParams pcLp = new LinearLayout.LayoutParams(-1, -2);
        pcLp.topMargin = dp(10);
        content.addView(projCard, pcLp);
        TextView projLabel = new TextView(this);
        projLabel.setText("📤 " + t("输出工程（点击切换）", "Output project (tap to switch)"));
        projLabel.setTextSize(13f);
        projLabel.setTextColor(0xff4a5f78);
        projCard.addView(projLabel, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout projRow = new LinearLayout(this);
        projRow.setOrientation(LinearLayout.HORIZONTAL);
        projRow.setGravity(Gravity.CENTER_VERTICAL);
        projectName = new TextView(this);
        projectName.setTextSize(15.5f);
        projectName.setTypeface(null, 1);
        projectName.setTextColor(0xff17334f);
        projectName.setSingleLine(true);
        projectName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        projRow.addView(projectName, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView arrow = new TextView(this);
        arrow.setText("▼");
        arrow.setTextSize(12f);
        arrow.setTextColor(0xff4a5f78);
        projRow.addView(arrow, new LinearLayout.LayoutParams(-2, -2));
        projCard.addView(projRow, new LinearLayout.LayoutParams(-1, -2));
        projectOut = new TextView(this);
        projectOut.setTextSize(12f);
        projectOut.setTextColor(0xff4a5f78);
        projectOut.setSingleLine(true);
        projectOut.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        projCard.addView(projectOut, new LinearLayout.LayoutParams(-1, -2));
        // ---- 最终 zip 命名（v3.43.4） ----
        TextView zipLabel = new TextView(this);
        zipLabel.setText("🏷 " + t("卡刷包文件名（输出到工程目录）", "Output zip filename (in project dir)"));
        zipLabel.setTextSize(13f);
        zipLabel.setTextColor(0xff4a5f78);
        LinearLayout.LayoutParams zipLabelLp = new LinearLayout.LayoutParams(-1, -2);
        zipLabelLp.topMargin = dp(8);
        projCard.addView(zipLabel, zipLabelLp);
        zipNameInput = new EditText(this);
        zipNameInput.setTextSize(13.5f);
        zipNameInput.setTextColor(0xff17334f);
        zipNameInput.setHintTextColor(0xff8fa3bb);
        zipNameInput.setHint(t("留空 = dna_ota_时间戳.zip", "blank = dna_ota_<timestamp>.zip"));
        zipNameInput.setSingleLine(true);
        android.graphics.drawable.GradientDrawable zipBg = new android.graphics.drawable.GradientDrawable();
        zipBg.setColor(0x26FFFFFF);
        zipBg.setCornerRadius(dp(10));
        zipBg.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
        zipNameInput.setBackground(zipBg);
        zipNameInput.setPadding(dp(10), dp(9), dp(10), dp(9));
        LinearLayout.LayoutParams zipLp = new LinearLayout.LayoutParams(-1, -2);
        zipLp.topMargin = dp(4);
        projCard.addView(zipNameInput, zipLp);
        renderProject();

        // ---- 元数据来源卡（三模式，v3.43.2） ----
        LinearLayout tplCard = glassCard();
        LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(-1, -2);
        tcLp.topMargin = dp(10);
        content.addView(tplCard, tcLp);
        TextView srcLabel = new TextView(this);
        srcLabel.setText("🧬 " + t("元数据来源（决定写入卡刷包的机型信息）",
                "Metadata source (device info written into the OTA zip)"));
        srcLabel.setTextSize(13f);
        srcLabel.setTextColor(0xff4a5f78);
        tplCard.addView(srcLabel, new LinearLayout.LayoutParams(-1, -2));

        // 三选 chip 行
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.LinearLayout.LayoutParams chipRowLp = new LinearLayout.LayoutParams(-1, dp(38));
        chipRowLp.topMargin = dp(6);
        addSrcChip(chipRow, "system", t("📦 system.img", "system.img"));
        addSrcChip(chipRow, "local", t("📱 本机", "local"));
        addSrcChip(chipRow, "tpl", t("🧬 模板", "template"));
        tplCard.addView(chipRow, chipRowLp);

        // 模式说明行
        srcHintText = new TextView(this);
        srcHintText.setTextSize(12.5f);
        srcHintText.setTextColor(0xff4a6484);
        srcHintText.setLineSpacing(dp(2), 1f);
        tplCard.addView(srcHintText, new LinearLayout.LayoutParams(-1, -2));

        // ---- 模板选择行（仅 tpl 模式显示） ----
        tplRowBox = new LinearLayout(this);
        tplRowBox.setOrientation(LinearLayout.HORIZONTAL);
        tplRowBox.setGravity(Gravity.CENTER_VERTICAL);
        tplPickBtn = pillButton("📂 " + t("选择模板", "Pick"), 13f, 0xff1f5f8f, dp(96), dp(38));
        tplPickBtn.setOnClickListener(v -> { Haptics.perform(v); pickTemplate(); });
        // 长按清除模板
        tplPickBtn.setOnLongClickListener(v -> {
            Haptics.perform(v);
            tplPath = null;
            tplText.setText(t("未选择", "Not set"));
            log("🧬 " + t("已清除模板", "Template cleared"));
            toast(t("已清除模板", "Template cleared"));
            return true;
        });
        tplRowBox.addView(tplPickBtn, new LinearLayout.LayoutParams(dp(96), dp(38)));
        tplText = new TextView(this);
        tplText.setTextSize(12.5f);
        tplText.setTextColor(0xff1f5f8f);
        // v3.43.4：路径多行完整显示（此前单行截断，模板路径被挤压看不全）
        tplText.setSingleLine(false);
        tplText.setMaxLines(3);
        tplText.setPadding(dp(10), 0, 0, 0);
        tplText.setText(t("未选择（长按「选择模板」可清除）", "Not set (long-press Pick to clear)"));
        tplRowBox.addView(tplText, new LinearLayout.LayoutParams(0, -2, 1f));
        android.widget.LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(-1, dp(38));
        trLp.topMargin = dp(6);
        tplCard.addView(tplRowBox, trLp);

        // ---- 设备代号（system/local 模式使用；留空 = 自动） ----
        devLabel = new TextView(this);
        devLabel.setTextSize(12.5f);
        devLabel.setTypeface(null, 1);
        devLabel.setTextColor(0xff3d5a7a);
        // v3.43.4：写明自动读取的 ro 参数（此前标签为空）
        devLabel.setText("📱 " + t("设备代号 pre-device · 留空 = 自动读取 ro.product.device",
                "Device codename pre-device · blank = auto from ro.product.device"));
        android.widget.LinearLayout.LayoutParams devLabelLp = new LinearLayout.LayoutParams(-1, -2);
        devLabelLp.topMargin = dp(8);
        tplCard.addView(devLabel, devLabelLp);
        deviceInput = new EditText(this);
        deviceInput.setTextSize(13.5f);
        deviceInput.setTextColor(0xff17334f);
        deviceInput.setHintTextColor(0xff8fa3bb);
        deviceInput.setSingleLine(true);
        android.graphics.drawable.GradientDrawable devBg = new android.graphics.drawable.GradientDrawable();
        devBg.setColor(0x26FFFFFF);
        devBg.setCornerRadius(dp(10));
        devBg.setStroke(Math.max(1, dp(1)), 0x55FFFFFF);
        deviceInput.setBackground(devBg);
        deviceInput.setPadding(dp(10), dp(8), dp(10), dp(8));
        android.widget.LinearLayout.LayoutParams devLp = new LinearLayout.LayoutParams(-1, -2);
        devLp.topMargin = dp(4);
        tplCard.addView(deviceInput, devLp);

        // ---- 高级覆盖（v3.43.3：指纹/版本号/时间戳/SPL 可手动填写，留空 = 自动；
        //      v3.43.4：每项写明自动读取的 ro 参数 + 内存上限选择） ----
        Button advToggle = pillButton("✏️ " + t("高级覆盖（选填）", "Advanced overrides"), 13f,
                0xff1f5f8f, dp(230), dp(34));
        advToggle.setOnClickListener(v -> {
            Haptics.perform(v);
            boolean show = advBox.getVisibility() != View.VISIBLE;
            advBox.setVisibility(show ? View.VISIBLE : View.GONE);
        });
        android.widget.LinearLayout.LayoutParams advToggleLp = new LinearLayout.LayoutParams(dp(230), dp(34));
        advToggleLp.topMargin = dp(10);
        tplCard.addView(advToggle, advToggleLp);
        advBox = new LinearLayout(this);
        advBox.setOrientation(LinearLayout.VERTICAL);
        advBox.setVisibility(View.GONE);
        tplCard.addView(advBox, new LinearLayout.LayoutParams(-1, -2));
        TextView advNote = new TextView(this);
        advNote.setText(t("· 手动值优先于自动采集；模板模式下不生效（模板元数据优先）",
                "· Manual values take priority; ignored in template mode"));
        advNote.setTextSize(11f);
        advNote.setTextColor(0xff6b84a3);
        advNote.setPadding(dp(2), dp(4), dp(2), 0);
        advBox.addView(advNote, new LinearLayout.LayoutParams(-1, -2));
        fpInput = advField(advBox, t("post-build 指纹", "post-build fingerprint"),
                t("留空 = 自动读取：ro.build.fingerprint → ro.vendor.build.fingerprint → …",
                        "blank = auto: ro.build.fingerprint → ro.vendor.build.fingerprint → …"),
                "oplus/ossi/ossi:17/CP2A.260605.016/...:user/release-keys");
        incInput = advField(advBox, t("post-build-incremental 版本号", "post-build-incremental"),
                t("留空 = 自动读取：ro.build.version.incremental",
                        "blank = auto: ro.build.version.incremental"),
                "1790446970813");
        tsInput = advField(advBox, t("post-timestamp 时间戳", "post-timestamp"),
                t("留空 = 自动读取：ro.build.date.utc（Unix 秒）",
                        "blank = auto: ro.build.date.utc (unix sec)"),
                "1790446970");
        splInput = advField(advBox, t("post-security-patch-level 安全补丁", "post-security-patch-level"),
                t("留空 = 自动读取：ro.build.version.security_patch",
                        "blank = auto: ro.build.version.security_patch"),
                "2026-09-01");

        // ---- 内存上限（v3.43.4：限制 delta_generator 打包进程的最大内存） ----
        TextView ramLabel = new TextView(this);
        ramLabel.setText("🧠 " + t("打包进程内存上限（过大杀后台 / 过小打包失败）",
                "Packer RAM limit (too big kills background / too small fails)"));
        ramLabel.setTextSize(12.5f);
        ramLabel.setTypeface(null, 1);
        ramLabel.setTextColor(0xff3d5a7a);
        LinearLayout.LayoutParams ramLabelLp = new LinearLayout.LayoutParams(-1, -2);
        ramLabelLp.topMargin = dp(8);
        advBox.addView(ramLabel, ramLabelLp);
        LinearLayout ramRow = new LinearLayout(this);
        ramRow.setOrientation(LinearLayout.HORIZONTAL);
        ramRow.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.LinearLayout.LayoutParams ramRowLp = new LinearLayout.LayoutParams(-1, dp(30));
        ramRowLp.topMargin = dp(4);
        addRamChip(ramRow, 0, t("不限", "none"));
        addRamChip(ramRow, 512, "512M");
        addRamChip(ramRow, 1024, "1G");
        addRamChip(ramRow, 2048, "2G");
        addRamChip(ramRow, 3072, "3G");
        advBox.addView(ramRow, ramRowLp);
        refreshRamChips();

        // ---- img 目录卡 ----
        LinearLayout dirCard = glassCard();
        LinearLayout.LayoutParams dcLp = new LinearLayout.LayoutParams(-1, -2);
        dcLp.topMargin = dp(10);
        content.addView(dirCard, dcLp);
        LinearLayout dirHead = new LinearLayout(this);
        dirHead.setOrientation(LinearLayout.HORIZONTAL);
        dirHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView dirLabel = new TextView(this);
        dirLabel.setText("🗂 " + t("img 集（分区镜像目录，分解 bin / super 的提取产物）",
                "img set (partition images dir)"));
        dirLabel.setTextSize(13f);
        dirLabel.setTextColor(0xff4a5f78);
        dirHead.addView(dirLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        imgCount = new TextView(this);
        imgCount.setTextSize(13f);
        imgCount.setTypeface(null, 1);
        imgCount.setTextColor(0xff1d7a4f);
        dirHead.addView(imgCount, new LinearLayout.LayoutParams(-2, -2));
        dirCard.addView(dirHead, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout dirRow = new LinearLayout(this);
        dirRow.setOrientation(LinearLayout.HORIZONTAL);
        dirRow.setGravity(Gravity.CENTER_VERTICAL);
        imgPickBtn = pillButton("📂 " + t("选择目录", "Pick dir"), 13f, 0xff1f5f8f, dp(96), dp(38));
        imgPickBtn.setOnClickListener(v -> { Haptics.perform(v); showImgDirPicker(); });
        dirRow.addView(imgPickBtn, new LinearLayout.LayoutParams(dp(96), dp(38)));
        imgDirText = new TextView(this);
        imgDirText.setTextSize(12.5f);
        imgDirText.setTextColor(0xff1f5f8f);
        // v3.43.4：路径多行完整显示
        imgDirText.setSingleLine(false);
        imgDirText.setMaxLines(3);
        imgDirText.setPadding(dp(10), 0, 0, 0);
        imgDirText.setText(t("未选择", "Not set"));
        dirRow.addView(imgDirText, new LinearLayout.LayoutParams(0, -2, 1f));
        android.widget.LinearLayout.LayoutParams drLp = new LinearLayout.LayoutParams(-1, -2);
        drLp.topMargin = dp(6);
        dirCard.addView(dirRow, drLp);
        // v3.43.4：分区完整性硬性提示（用户要求：不能少于官方 OTA 包的分区）
        TextView partWarn = new TextView(this);
        partWarn.setText("⚠ " + t("分区不得少于官方 OTA 包！请放入官方包提取出的全部 img"
                        + "（boot / system / vendor / product / system_ext / odm / vbmeta …）。"
                        + "打包时将与模板分区逐一比对，缺分区直接中止。",
                "Partition set must NOT be smaller than the stock OTA! Include ALL imgs extracted "
                        + "from the stock package (boot / system / vendor / product / system_ext / odm / "
                        + "vbmeta ...). Missing partitions abort the build."));
        partWarn.setTextSize(12f);
        partWarn.setTextColor(0xffB03A2E);
        partWarn.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams pwLp = new LinearLayout.LayoutParams(-1, -2);
        pwLp.topMargin = dp(8);
        dirCard.addView(partWarn, pwLp);

        // ---- 签名密钥卡（v3.43.4：可导入自定义密钥，格式写明） ----
        LinearLayout keyCard = glassCard();
        LinearLayout.LayoutParams kcLp = new LinearLayout.LayoutParams(-1, -2);
        kcLp.topMargin = dp(10);
        content.addView(keyCard, kcLp);
        TextView keyLabel = new TextView(this);
        keyLabel.setText("🔐 " + t("签名密钥（payload 签名 / whole-file 签名 / otacert）",
                "Signing key (payload / whole-file / otacert)"));
        keyLabel.setTextSize(13f);
        keyLabel.setTextColor(0xff4a5f78);
        keyCard.addView(keyLabel, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout keyRow = new LinearLayout(this);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        keyRow.setGravity(Gravity.CENTER_VERTICAL);
        Button keyImportBtn = pillButton("🔑 " + t("导入密钥", "Import"), 13f, 0xff1f5f8f, dp(110), dp(38));
        keyImportBtn.setOnClickListener(v -> { Haptics.perform(v); pickKeyFiles(); });
        // 长按清除 → 恢复内置 testkey
        keyImportBtn.setOnLongClickListener(v -> {
            Haptics.perform(v);
            keyPk8Path = null;
            keyCertPath = null;
            OtaPacker.clearCustomKey();
            renderKeyStatus();
            log("🔐 " + t("已恢复内置 testkey", "Restored built-in testkey"));
            toast(t("已恢复内置 testkey", "Restored built-in testkey"));
            return true;
        });
        keyRow.addView(keyImportBtn, new LinearLayout.LayoutParams(dp(110), dp(38)));
        keyStatusText = new TextView(this);
        keyStatusText.setTextSize(12.5f);
        keyStatusText.setTextColor(0xff1d7a4f);
        keyStatusText.setSingleLine(false);
        keyStatusText.setMaxLines(2);
        keyStatusText.setPadding(dp(10), 0, 0, 0);
        keyRow.addView(keyStatusText, new LinearLayout.LayoutParams(0, -2, 1f));
        keyCard.addView(keyRow, new LinearLayout.LayoutParams(-1, -2));
        TextView keyHelp = new TextView(this);
        keyHelp.setText(t("格式（与电脑端 signapk 相同，缺一不可）：\n"
                        + "① 私钥：PKCS#8 —— testkey.pk8（DER）或 \"-----BEGIN PRIVATE KEY-----\" PEM\n"
                        + "② 证书：X.509 —— testkey.x509.pem（\"-----BEGIN CERTIFICATE-----\"）或 DER\n"
                        + "要求 RSA ≥ 2048 位，两文件必须配对（同一密钥生成）。\n"
                        + "注意：自定义密钥签出的包，只有 recovery 装了对应公钥（otacert）才能刷；"
                        + "TWRP 默认信任 testkey。长按「导入密钥」恢复 testkey。",
                "Format (same as desktop signapk, both required):\n"
                        + "1) Private key: PKCS#8 — testkey.pk8 (DER) or \"BEGIN PRIVATE KEY\" PEM\n"
                        + "2) Certificate: X.509 — testkey.x509.pem (\"BEGIN CERTIFICATE\") or DER\n"
                        + "RSA >= 2048 bits; the pair must match.\n"
                        + "Note: a custom-signed zip flashes only if recovery trusts its public key "
                        + "(otacert); TWRP trusts testkey by default. Long-press Import to restore testkey."));
        keyHelp.setTextSize(11.5f);
        keyHelp.setTextColor(0xff4a6484);
        keyHelp.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams khLp = new LinearLayout.LayoutParams(-1, -2);
        khLp.topMargin = dp(6);
        keyCard.addView(keyHelp, khLp);
        renderKeyStatus();

        // ---- 状态 + 进度 ----
        status = new TextView(this);
        status.setTextSize(13.5f);
        status.setTextColor(0xff5a6b82);
        status.setPadding(dp(4), dp(12), dp(4), dp(2));
        content.addView(status, new LinearLayout.LayoutParams(-1, -2));

        progressTrack = new FrameLayout(this);
        progressTrack.setBackgroundResource(R.drawable.dna_progress_track);
        progressTrack.setVisibility(View.GONE);
        progressFill = new View(this);
        progressFill.setBackgroundResource(R.drawable.dna_progress_fill);
        progressTrack.addView(progressFill, new FrameLayout.LayoutParams(dp(84), android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(progressTrack, new LinearLayout.LayoutParams(-1, dp(12)));

        // ---- 合成按钮 ----
        runBtn = gradientButton("📦  " + t("合成完整卡刷包", "Build flashable OTA"), new int[]{0xFF11998E, 0xFF0E7A5F}, dp(18));
        runBtn.setOnClickListener(v -> {
            Haptics.perform(v);
            if (running.get()) { cancelFlag.set(true); log(t("正在取消 ...", "Cancelling...")); return; }
            build();
        });
        LinearLayout.LayoutParams rbLp = new LinearLayout.LayoutParams(-1, dp(54));
        rbLp.topMargin = dp(10);
        content.addView(runBtn, rbLp);

        // ---- 控制台 ----
        LinearLayout.LayoutParams consLp = new LinearLayout.LayoutParams(-1, -2);
        consLp.topMargin = dp(14);
        content.addView(buildConsole(), consLp);

        log(t("提示：元数据来源三选一 —— system.img（读目标机 build.prop，给其他手机打包）/ "
                        + "本机（getprop，给本机打包）/ 模板（继承原厂包 metadata 与脚本）。"
                        + "产物均为 payload 签名 + whole-file 签名的完整 A/B 卡刷包",
                "Note: pick a metadata source — system.img (target build.prop, for other devices) / "
                        + "local (getprop, for this device) / template (inherit stock metadata & scripts). "
                        + "Output is always a fully-signed A/B OTA zip"));
        refreshSrcUi();
    }

    // ================= 数据渲染 =================

    private void renderProject() {
        projectName.setText(project != null ? project : t("未选择工程", "No project"));
        projectOut.setText(project != null
                ? "➜ " + DnaTools.WORK_ROOT + "/" + project
                : t("点此选择要输出的工程", "Tap to pick an output project"));
    }

    private void log(String line) {
        final String stamp = new java.text.SimpleDateFormat("HH:mm:ss", Locale.US)
                .format(new java.util.Date());
        main.post(() -> {
            consoleText.append(stamp + "  " + line + "\n");
            consoleScroll.post(() -> consoleScroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void setStatus(String text, int color) {
        main.post(() -> { status.setText(text); status.setTextColor(color); });
    }

    private String fmtSizeShort(long bytes) {
        if (bytes < 1024) return bytes + "B";
        double v = bytes / 1024.0;
        String[] u = {"K", "M", "G", "T"};
        int i = 0;
        while (v >= 1024 && i < 3) { v /= 1024; i++; }
        return String.format(Locale.US, "%.1f%s", v, u[i]);
    }

    private String deltaGenPath() {
        return new File(getApplicationInfo().nativeLibraryDir, "libdelta_generator.so").getAbsolutePath();
    }

    // ================= 选择器 =================

    private void pickTemplate() {
        FileBrowserDialog.show(this, t("选择模板 OTA.zip", "Pick template OTA.zip"),
                new String[]{".zip", ".ozip", ".zip2"}, DnaTools.WORK_ROOT,
                path -> {
                    tplPath = path;
                    tplText.setText(new File(path).getName());
                    if (!"tpl".equals(metaSource)) {
                        metaSource = "tpl";
                        refreshSrcUi();
                        log("🧬 " + t("已切换为模板模式", "Switched to template mode"));
                    }
                    log("🧬 " + t("模板", "Template") + ": " + path);
                });
    }

    // ================= 自定义签名密钥（v3.43.4） =================

    /** 密钥状态行渲染（testkey / 自定义） */
    private void renderKeyStatus() {
        if (keyStatusText == null) return;
        if (OtaPacker.customKeyActive()) {
            String cn = "";
            try {
                cn = OtaPacker.testCert().getSubjectX500Principal().getName();
                for (String p : cn.split(",")) {
                    p = p.trim();
                    if (p.startsWith("CN=")) { cn = p.substring(3); break; }
                }
            } catch (Exception ignored) {}
            keyStatusText.setText("✓ " + t("自定义密钥", "custom key") + ": " + cn);
            keyStatusText.setTextColor(0xff1d7a4f);
        } else {
            keyStatusText.setText(t("内置 AOSP testkey（TWRP 默认信任）",
                    "Built-in AOSP testkey (TWRP default trust)"));
            keyStatusText.setTextColor(0xff4a6484);
        }
    }

    /** 两步导入：先选私钥（.pk8 / PEM），再选证书（.x509.pem / DER）→ 即时解析校验 */
    private void pickKeyFiles() {
        FileBrowserDialog.show(this, t("① 选择私钥（PKCS#8：.pk8 或 PEM）",
                        "1) Pick private key (PKCS#8: .pk8 or PEM)"),
                new String[]{".pk8", ".pem", ".key"}, DnaTools.WORK_ROOT,
                path -> {
                    keyPk8Path = path;
                    log("🔐 " + t("私钥", "private key") + ": " + path);
                    FileBrowserDialog.show(this, t("② 选择配对证书（X.509：.x509.pem 或 DER）",
                                    "2) Pick matching certificate (X.509: .x509.pem or DER)"),
                            new String[]{".pem", ".cer", ".crt", ".der"}, DnaTools.WORK_ROOT,
                            certPath -> {
                                keyCertPath = certPath;
                                log("🔐 " + t("证书", "certificate") + ": " + certPath);
                                applyCustomKey();
                            });
                });
    }

    /** 读取两个密钥文件 → 解析 + 配对自检 → 生效 */
    private void applyCustomKey() {
        io.execute(() -> {
            try {
                byte[] pk8 = readFileMaybeRoot(keyPk8Path);
                byte[] cert = readFileMaybeRoot(keyCertPath);
                if (pk8 == null || pk8.length == 0)
                    throw new PackException(t("私钥读取失败", "failed to read private key"));
                if (cert == null || cert.length == 0)
                    throw new PackException(t("证书读取失败", "failed to read certificate"));
                OtaPacker.useCustomKey(pk8, cert);
                OtaPacker.verifyKeyPair();
                main.post(() -> {
                    renderKeyStatus();
                    toast(t("自定义密钥已生效（已通过配对校验）", "Custom key active (pair verified)"));
                });
                log("✓ " + t("自定义签名密钥已生效（配对校验通过）",
                        "custom signing key active (pair verified)"));
            } catch (Exception e) {
                keyPk8Path = null;
                keyCertPath = null;
                OtaPacker.clearCustomKey();
                final String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                log("✗ " + t("密钥导入失败", "key import failed") + ": " + msg);
                main.post(() -> {
                    renderKeyStatus();
                    toast(t("密钥导入失败", "key import failed") + ": " + msg);
                });
            }
        });
    }

    /** 读文件（app 直读失败时 root cat + base64 兜底） */
    private byte[] readFileMaybeRoot(String path) {
        try (FileInputStream in = new FileInputStream(path)) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toByteArray();
        } catch (Exception ignored) {}
        try {
            com.topjohnwu.superuser.Shell.Result r = com.topjohnwu.superuser.Shell.cmd(
                    "cat " + DnaTools.quote(path) + " | base64").exec();
            if (r.isSuccess()) {
                StringBuilder sb = new StringBuilder();
                for (String line : r.getOut()) sb.append(line.trim());
                return java.util.Base64.getMimeDecoder().decode(sb.toString());
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 从模板 OTA.zip 流式读 payload 头 + manifest → 分区名清单（不解出整个 payload） */
    private List<String> templatePartitions(String zipPath) throws Exception {
        try (java.util.zip.ZipFile zt = new java.util.zip.ZipFile(zipPath)) {
            java.util.zip.ZipEntry pe = zt.getEntry("payload.bin");
            if (pe == null)
                throw new PackException(t("模板里没有 payload.bin（不是 A/B OTA 卡刷包）",
                        "template has no payload.bin (not an A/B OTA zip)"));
            try (java.io.InputStream in = zt.getInputStream(pe)) {
                byte[] hdr = new byte[24];
                readStream(in, hdr);
                if (hdr[0] != 'C' || hdr[1] != 'r' || hdr[2] != 'A' || hdr[3] != 'U')
                    throw new PackException(t("模板 payload.bin 头部异常", "bad payload header in template"));
                long manifestSize = ((hdr[12] & 0xffL) << 56) | ((hdr[13] & 0xffL) << 48)
                        | ((hdr[14] & 0xffL) << 40) | ((hdr[15] & 0xffL) << 32)
                        | ((hdr[16] & 0xffL) << 24) | ((hdr[17] & 0xffL) << 16)
                        | ((hdr[18] & 0xffL) << 8) | (hdr[19] & 0xffL);
                if (manifestSize <= 0 || manifestSize > 64L * 1024 * 1024)
                    throw new PackException(t("模板 manifest 大小异常", "bad manifest size in template"));
                byte[] manifest = new byte[(int) manifestSize];
                readStream(in, manifest);
                return OtaPacker.partitionsFromManifest(manifest);
            }
        }
    }

    private static void readStream(java.io.InputStream in, byte[] buf) throws Exception {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new java.io.EOFException("unexpected EOF");
            off += n;
        }
    }

    private void showImgDirPicker() {
        if (isFinishing() || isDestroyed()) return;
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.setCancelable(true);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(12));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xF2e9f0f7);
        bg.setCornerRadius(dp(24));
        bg.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
        panel.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("🗂 " + t("选择 img 目录", "Pick img dir"));
        title.setTextSize(15f);
        title.setTypeface(null, 1);
        title.setTextColor(0xff17334f);
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));

        final TextView cwd = new TextView(this);
        cwd.setTextSize(11.5f);
        cwd.setTextColor(0xff1f5f8f);
        cwd.setSingleLine(true);
        cwd.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        cwd.setPadding(0, dp(4), 0, dp(6));
        panel.addView(cwd, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        final java.util.concurrent.atomic.AtomicReference<String> cur =
                new java.util.concurrent.atomic.AtomicReference<>(
                        imgDir != null ? imgDir : DnaTools.WORK_ROOT);

        final Runnable[] load = new Runnable[1];
        load[0] = () -> {
            list.removeAllViews();
            final String dir = cur.get();
            cwd.setText(dir);
            java.util.List<String> dirs = new ArrayList<>();
            try {
                com.topjohnwu.superuser.Shell.Result r = com.topjohnwu.superuser.Shell.cmd(
                        "for d in " + DnaTools.quote(dir) + "/*/; do [ -d \"$d\" ] && echo \"${d%/}\"; done").exec();
                dirs.addAll(r.getOut());
            } catch (Exception ignored) {}
            java.util.Collections.sort(dirs);
            if (!"/storage/emulated/0".equals(dir) && dir.lastIndexOf('/') > 0) {
                final String parent = dir.substring(0, dir.lastIndexOf('/'));
                Button up = pillButton("⬆ " + t("上级", "Up"), 12f, 0xff5a6b82, dp(72), dp(38));
                up.setOnClickListener(v -> { cur.set(parent); load[0].run(); });
                LinearLayout.LayoutParams uLp = new LinearLayout.LayoutParams(dp(72), dp(38));
                uLp.bottomMargin = dp(4);
                list.addView(up, uLp);
            }
            for (final String d : dirs) {
                Button b = pillButton("📂 " + d.substring(d.lastIndexOf('/') + 1), 12.5f, 0xff1f5f8f, -2, dp(40));
                b.setOnClickListener(v -> { cur.set(d); load[0].run(); });
                LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-1, dp(40));
                bLp.topMargin = dp(3);
                list.addView(b, bLp);
            }
            if (dirs.isEmpty()) {
                TextView empty = new TextView(this);
                empty.setText(t("（无子目录，可直接选定此目录）", "(no subdirs, you can pick this dir)"));
                empty.setTextSize(12f);
                empty.setTextColor(0xff5a6b82);
                empty.setPadding(dp(4), dp(8), 0, 0);
                list.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            }
        };
        load[0].run();

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        Button cancel = pillButton(t("取消", "Cancel"), 13f, 0xff5a6b82, dp(72), dp(42));
        cancel.setOnClickListener(v -> { Haptics.perform(v); dialog.dismiss(); });
        btnRow.addView(cancel, new LinearLayout.LayoutParams(dp(72), dp(42)));
        android.widget.Space sp = new android.widget.Space(this);
        btnRow.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        Button ok = gradientButton("✓ " + t("选定此目录", "Pick this dir"), new int[]{0xFF2f9c8f, 0xFF1d6b46}, dp(10));
        ok.setOnClickListener(v -> {
            Haptics.perform(v);
            imgDir = cur.get();
            imgDirText.setText(imgDir);
            dialog.dismiss();
            log("🗂 " + t("img 目录", "img dir") + ": " + imgDir);
            countImages();
        });
        btnRow.addView(ok, new LinearLayout.LayoutParams(0, dp(42), 1.6f));
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(-1, dp(42));
        brLp.topMargin = dp(10);
        panel.addView(btnRow, brLp);

        dialog.setContentView(panel, new LinearLayout.LayoutParams(-1, dp(440)));
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        try {
            dialog.show();
            dialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.88f), dp(440));
        } catch (Exception ignored) {
        }
    }

    /** 统计 img 目录里的分区镜像数（root find，含大小合计） */
    private void countImages() {
        final String dir = imgDir;
        if (dir == null) return;
        io.execute(() -> {
            try {
                com.topjohnwu.superuser.Shell.Result r = com.topjohnwu.superuser.Shell.cmd(
                        "cd " + DnaTools.quote(dir) + " && ls -1 *.img 2>/dev/null | wc -l; " +
                        "du -c *.img 2>/dev/null | tail -1").exec();
                List<String> out = r.getOut();
                int n = 0;
                long total = 0;
                if (out != null && !out.isEmpty()) {
                    try { n = Integer.parseInt(out.get(0).trim()); } catch (Exception ignored) {}
                    if (out.size() > 1) {
                        try { total = Long.parseLong(out.get(1).trim().split("\\s+")[0]) * 1024; }
                        catch (Exception ignored) {}
                    }
                }
                final int fn = n;
                final long ft = total;
                main.post(() -> imgCount.setText(fn > 0
                        ? fn + t(" 个 · ", " imgs · ") + fmtSizeShort(ft)
                        : t("无 img", "no img")));
                if (fn == 0) main.post(() -> log("⚠ " + t("该目录下没有 .img 文件", "No .img files in this dir")));
            } catch (Exception ignored) {}
        });
    }

    private void showProjectPicker() {
        if (isFinishing() || isDestroyed()) return;
        List<String> projects = DnaTools.listProjects();
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.setCancelable(true);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(12));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xF2e9f0f7);
        bg.setCornerRadius(dp(24));
        bg.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
        panel.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("📂 " + t("选择输出工程", "Pick output project"));
        title.setTextSize(15f);
        title.setTypeface(null, 1);
        title.setTextColor(0xff17334f);
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        if (projects.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(t("暂无工程，请先在 DNA 工具箱创建", "No projects yet"));
            empty.setTextSize(12.5f);
            empty.setTextColor(0xff5a6b82);
            empty.setPadding(dp(4), dp(10), 0, 0);
            list.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        }
        for (final String p : projects) {
            Button b = pillButton((p.equals(project) ? "● " : "○ ") + p, 13f,
                    p.equals(project) ? 0xff1f7d72 : 0xff35507a, -2, dp(42));
            b.setOnClickListener(v -> {
                Haptics.perform(v);
                project = p;
                DnaTools.setCurrentProject(this, p);
                renderProject();
                dialog.dismiss();
            });
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-1, dp(42));
            bLp.topMargin = dp(4);
            list.addView(b, bLp);
        }

        dialog.setContentView(panel, new LinearLayout.LayoutParams(-1, dp(400)));
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        try {
            dialog.show();
            dialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.86f), dp(400));
        } catch (Exception ignored) {
        }
    }

    // ================= 打包主流程 =================

    private static class PackException extends Exception {
        PackException(String m) { super(m); }
    }

    private void build() {
        if (project == null) {
            toast(t("请先选择输出工程", "Select an output project first"));
            showProjectPicker();
            return;
        }
        if (imgDir == null || imgDir.isEmpty()) {
            toast(t("请先选择 img 目录", "Pick the img dir first"));
            showImgDirPicker();
            return;
        }
        if ("tpl".equals(metaSource) && (tplPath == null || tplPath.isEmpty())) {
            toast(t("模板模式：请先选择模板 OTA.zip（或切换其他元数据来源）",
                    "Template mode: pick a template OTA.zip (or switch source)"));
            return;
        }
        if (!new File(deltaGenPath()).exists()) {
            toast(t("本设备架构不支持（需 arm64）", "Unsupported arch (arm64 required)"));
            return;
        }
        running.set(true);
        cancelFlag.set(false);
        runBtn.setText("■  " + t("取消", "Cancel"));
        progressTrack.setVisibility(View.VISIBLE);
        setStatus("📦 " + t("打包中 ...", "Building..."), 0xff5a6b82);
        notify(t("打包中 ...", "Building..."), true, true, 0, 0);
        // EditText 只能在 UI 线程读，先捕获再交后台
        final String devOverride = deviceInput.getText().toString().trim();
        final String fpOverride = fpInput.getText().toString().trim();
        final String incOverride = incInput.getText().toString().trim();
        final String tsOverride = tsInput.getText().toString().trim();
        final String splOverride = splInput.getText().toString().trim();
        final String zipNameRaw = zipNameInput.getText().toString().trim();

        io.execute(() -> {
            try {
                doBuild(devOverride, fpOverride, incOverride, tsOverride, splOverride, zipNameRaw);
                setStatus("✓ " + t("卡刷包打包完成", "OTA zip built"), 0xff1d7a4f);
                notifyDone(true, t("卡刷包打包完成", "OTA zip built"));
                toast(t("卡刷包打包完成", "OTA zip built"));
            } catch (Exception e) {
                final String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                log("✗ " + t("打包失败", "Build failed") + ": " + msg);
                setStatus("✗ " + t("打包失败", "Build failed"), 0xffa33b3b);
                notifyDone(false, t("打包失败", "Build failed"));
            } finally {
                main.post(() -> {
                    running.set(false);
                    runBtn.setText("📦  " + t("合成完整卡刷包", "Build flashable OTA"));
                    progressTrack.setVisibility(View.GONE);
                });
            }
        });
    }

    private void doBuild(String devOverride, String fpOverride, String incOverride,
                         String tsOverride, String splOverride, String zipNameRaw) throws Exception {
        final String lib = deltaGenPath();
        final String projDir = DnaTools.WORK_ROOT + "/" + project;
        final String work = projDir + "/.otapack";
        final String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new java.util.Date());
        // v3.43.4：自定义输出 zip 命名（非法字符替换，缺 .zip 后缀补齐）
        String fname = zipNameRaw == null || zipNameRaw.isEmpty()
                ? "dna_ota_" + stamp : zipNameRaw;
        fname = fname.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        if (!fname.toLowerCase(Locale.US).endsWith(".zip")) fname = fname + ".zip";
        if (fname.length() > 120) fname = fname.substring(0, 116) + ".zip";
        final File outFile = new File(projDir, fname);
        final String realImgDir = DnaTools.INSTANCE.realPath(imgDir);
        final boolean fromScratch = !"tpl".equals(metaSource);
        final String realTpl = fromScratch ? null : DnaTools.INSTANCE.realPath(tplPath);
        final String realWork = DnaTools.INSTANCE.realPath(work);

        // ---- 0. 工作目录 ----
        log("⚙ " + t("准备工作目录", "Preparing work dir") + ": " + work);
        DnaTools.Result r = DnaTools.run(this,
                "rm -rf " + DnaTools.quote(realWork) + " && mkdir -p " + DnaTools.quote(realWork)
                        + " && chmod 777 " + DnaTools.quote(realWork),
                l -> kotlin.Unit.INSTANCE, () -> cancelFlag.get(), 30000);
        if (!r.getSuccess()) throw new PackException(t("工作目录创建失败", "work dir failed"));

        // ---- 1. 列 img 集 ----
        setStatus("📋 " + t("扫描 img 集 ...", "Scanning images..."), 0xff5a6b82);
        log("📋 " + t("扫描 img 目录", "Scanning img dir") + ": " + imgDir);
        r = DnaTools.run(this,
                "cd " + DnaTools.quote(realImgDir) + " && ls -1 *.img 2>/dev/null | LC_ALL=C sort",
                l -> kotlin.Unit.INSTANCE, () -> cancelFlag.get(), 30000);
        if (!r.getSuccess()) throw new PackException(t("扫描 img 目录失败", "scan failed"));
        List<String> imgs = new ArrayList<>();
        for (String line : r.getOutput().split("\n")) {
            String n = line.trim();
            if (n.endsWith(".img")) imgs.add(n.substring(0, n.length() - 4));
        }
        if (imgs.isEmpty()) throw new PackException(t("img 目录里没有 .img 文件", "no .img files"));
        StringBuilder names = new StringBuilder();
        StringBuilder paths = new StringBuilder();
        for (String n : imgs) {
            if (names.length() > 0) { names.append(':'); paths.append(':'); }
            names.append(n);
            paths.append(realImgDir).append('/').append(n).append(".img");
        }
        log("✓ " + imgs.size() + t(" 个分区：", " partitions: ") + String.join(" ", imgs));

        // ---- 1.5 分区完整性校验（v3.43.4：不得少于官方 OTA 包的分区） ----
        java.util.Set<String> have = new java.util.TreeSet<>(imgs);
        List<String> missing = new ArrayList<>();
        if (fromScratch) {
            // 无模板：基础必备（GKI 机用 init_boot 替代 boot）
            if (!have.contains("system")) missing.add("system");
            if (!have.contains("vendor")) missing.add("vendor");
            if (!have.contains("boot") && !have.contains("init_boot")) missing.add("boot / init_boot");
            if (!missing.isEmpty())
                throw new PackException(t("缺少基础分区（官方 OTA 必含）：", "missing essential partitions: ")
                        + String.join(", ", missing));
            String[] rec = {"product", "system_ext", "odm", "vbmeta", "dtbo", "init_boot",
                    "vendor_boot", "vbmeta_system", "odm_dlkm", "vendor_dlkm", "system_dlkm"};
            List<String> recMiss = new ArrayList<>();
            for (String p : rec) if (!have.contains(p)) recMiss.add(p);
            if (!recMiss.isEmpty())
                log("⚠ " + t("官方 OTA 一般还包含（建议补齐，缺了不拦但刷机风险自负）：",
                        "usually also in stock OTA (recommended, not blocking): ")
                        + String.join(", ", recMiss));
        } else {
            // 模板模式：与官方模板 payload 的分区逐一比对（硬性要求，缺 = 中止）
            log("📋 " + t("比对模板（官方 OTA）分区清单 ...", "Comparing template (stock OTA) partition list..."));
            List<String> tplParts = templatePartitions(tplPath);
            log("    " + t("模板分区", "template partitions") + " (" + tplParts.size() + "): "
                    + String.join(" ", tplParts));
            for (String p : tplParts) if (!have.contains(p)) missing.add(p);
            if (!missing.isEmpty())
                throw new PackException(t("分区少于官方 OTA 包！缺少：", "fewer partitions than stock OTA! Missing: ")
                        + String.join(", ", missing)
                        + " —— " + t("请补齐这些 img 后重试（缺分区刷机极可能变砖）",
                        "provide these imgs and retry (missing partitions risk a brick)"));
            log("✓ " + t("分区比对通过：img 集 ⊇ 官方模板分区", "partition check passed: img set ⊇ stock template"));
        }

        // ---- 2. delta_generator：生成未签名 payload（内存上限可选） ----
        setStatus("🧬 " + t("生成 payload.bin（压缩耗时与镜像大小相关）...", "Generating payload.bin..."), 0xff5a6b82);
        log("🧬 delta_generator " + t("步骤 1/5：img 集 → 未签名 payload", "step 1/5: imgs → unsigned payload"));
        if (ramLimitMb > 0)
            log("🧠 " + t("内存上限", "RAM limit") + ": " + ramLimitMb + " MB (ulimit -v)");
        notify(t("生成 payload.bin ...", "Generating payload.bin..."), true, true, 0, 0);
        String ramPre = ramLimitMb > 0 ? "ulimit -v " + ((long) ramLimitMb * 1024) + " 2>/dev/null; " : "";
        r = DnaTools.run(this,
                ramPre + DnaTools.quote(lib) + " -out_file=" + DnaTools.quote(realWork + "/unsigned-payload.bin")
                        + " -partition_names=" + DnaTools.quote(names.toString())
                        + " -new_partitions=" + DnaTools.quote(paths.toString()),
                l -> kotlin.Unit.INSTANCE, () -> cancelFlag.get(), 4 * 60 * 60_000L);
        if (!r.getSuccess()) throw new PackException(t("payload 生成失败", "payload gen failed"));
        log("✓ " + t("未签名 payload 完成", "unsigned payload done") + " (" + fileSize(work + "/unsigned-payload.bin") + ")");

        // ---- 3. delta_generator：导出哈希 ----
        log("🧬 delta_generator " + t("步骤 2/5：导出哈希", "step 2/5: export hashes"));
        r = DnaTools.run(this,
                DnaTools.quote(lib) + " --in_file=" + DnaTools.quote(realWork + "/unsigned-payload.bin")
                        + " -signature_size=256"
                        + " -out_metadata_hash_file=" + DnaTools.quote(realWork + "/sig_metadata.bin")
                        + " -out_hash_file=" + DnaTools.quote(realWork + "/sig_hash.bin"),
                l -> kotlin.Unit.INSTANCE, () -> cancelFlag.get(), 10 * 60_000L);
        if (!r.getSuccess()) throw new PackException(t("哈希导出失败", "hash export failed"));
        // app 放行读
        DnaTools.run(this, "chmod 666 " + DnaTools.quote(realWork + "/sig_hash.bin")
                        + " " + DnaTools.quote(realWork + "/sig_metadata.bin"),
                l -> kotlin.Unit.INSTANCE, () -> false, 15000);

        // ---- 4. Java 签名（testkey / 自定义密钥） ----
        log("🔐 " + t("步骤 3/5：RSA 签名（", "step 3/5: RSA sign (") + OtaPacker.activeKeyName() + ")");
        byte[] payloadHash = readBytes(new File(work, "sig_hash.bin"));
        byte[] metadataHash = readBytes(new File(work, "sig_metadata.bin"));
        if (payloadHash == null || payloadHash.length != 32
                || metadataHash == null || metadataHash.length != 32)
            throw new PackException(t("哈希文件异常（应为 32 字节）", "bad hash files (32B expected)"));
        writeBytes(new File(work, "signed_hash.bin"), OtaPacker.signHash(payloadHash));
        writeBytes(new File(work, "signed_metadata.bin"), OtaPacker.signHash(metadataHash));
        log("✓ " + t("两份签名完成（各 256B）", "both signatures done (256B each)"));

        // ---- 5. delta_generator：注入签名 ----
        log("🧬 delta_generator " + t("步骤 4/5：注入签名", "step 4/5: inject signatures"));
        r = DnaTools.run(this,
                DnaTools.quote(lib) + " --in_file=" + DnaTools.quote(realWork + "/unsigned-payload.bin")
                        + " --out_file=" + DnaTools.quote(realWork + "/payload.bin")
                        + " --signature_size=256"
                        + " --metadata_signature_file=" + DnaTools.quote(DnaTools.INSTANCE.realPath(work + "/signed_metadata.bin"))
                        + " --payload_signature_file=" + DnaTools.quote(DnaTools.INSTANCE.realPath(work + "/signed_hash.bin")),
                l -> kotlin.Unit.INSTANCE, () -> cancelFlag.get(), 10 * 60_000L);
        if (!r.getSuccess()) throw new PackException(t("签名注入失败", "signature injection failed"));

        // ---- 6. delta_generator：导出 properties ----
        log("🧬 delta_generator " + t("步骤 5/5：导出 payload_properties.txt", "step 5/5: export properties"));
        r = DnaTools.run(this,
                DnaTools.quote(lib) + " --in_file=" + DnaTools.quote(realWork + "/payload.bin")
                        + " --properties_file=" + DnaTools.quote(realWork + "/payload_properties.txt"),
                l -> kotlin.Unit.INSTANCE, () -> cancelFlag.get(), 10 * 60_000L);
        if (!r.getSuccess()) throw new PackException(t("properties 导出失败", "properties export failed"));
        DnaTools.run(this, "chmod 666 " + DnaTools.quote(realWork + "/payload.bin")
                        + " " + DnaTools.quote(realWork + "/payload_properties.txt"),
                l -> kotlin.Unit.INSTANCE, () -> false, 15000);
        byte[] props = readBytes(new File(work, "payload_properties.txt"));
        if (props == null || props.length == 0)
            throw new PackException(t("payload_properties.txt 为空", "empty payload_properties.txt"));
        log("✓ payload.bin " + t("已签名", "signed") + " (" + fileSize(work + "/payload.bin") + ")");

        // ---- 7. 组装 zip（模板重建 / 从零生成） ----
        setStatus("📦 " + t("组装卡刷包 zip ...", "Assembling OTA zip..."), 0xff5a6b82);
        notify(t("组装卡刷包 zip ...", "Assembling OTA zip..."), true, false, 0, 100);
        OtaPacker.ProgressFn copyProgress = (done, total) -> main.post(() -> {
            progressTrack.setVisibility(View.VISIBLE);
            int w = progressTrack.getWidth();
            if (w > 0 && total > 0) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) progressFill.getLayoutParams();
                lp.width = (int) (w * done / Math.max(total, 1));
                progressFill.setLayoutParams(lp);
            }
            notify(t("复制 payload", "Copying payload") + " " + fmtSizeShort(done) + "/" + fmtSizeShort(total),
                    true, false, (int) (total > 0 ? done * 100 / total : 0), 100);
        });
        long outSize;
        if (fromScratch) {
            log("📦 " + t("从零生成：自动生成 metadata / metadata.pb / otacert + payload",
                    "From scratch: generating metadata / metadata.pb / otacert + payload"));
            Map<String, String> meta;
            if ("local".equals(metaSource)) {
                log("📱 " + t("元数据来源：本机 getprop", "Metadata source: local getprop"));
                meta = collectDeviceMeta(devOverride);
            } else {
                log("📦 " + t("元数据来源：img 目录 system.img（目标机 build.prop）",
                        "Metadata source: system.img in img dir (target build.prop)"));
                meta = collectMetaFromSystemImg(work, realImgDir, devOverride);
            }
            // v3.43.3 高级覆盖：手动值优先于自动采集
            int applied = 0;
            if (!fpOverride.isEmpty()) { meta.put("post-build", fpOverride); applied++; }
            if (!incOverride.isEmpty()) { meta.put("post-build-incremental", incOverride); applied++; }
            if (!tsOverride.isEmpty()) { meta.put("post-timestamp", tsOverride); applied++; }
            if (!splOverride.isEmpty()) { meta.put("post-security-patch-level", splOverride); applied++; }
            if (applied > 0) log("✏️ " + t("已应用手动覆盖", "manual overrides applied") + ": " + applied);
            String pb = meta.get("post-build");
            if (pb != null && (pb.startsWith("qti/qssi/") || pb.contains("/qssi/")))
                log("⚠ " + t("post-build 为 QSSI 通用指纹（vendor.img 缺失时 system 只有通用值）—— "
                        + "建议补 vendor.img 或在「高级覆盖」手动填写设备指纹",
                        "post-build is the generic QSSI fingerprint (vendor.img missing) — "
                                + "provide vendor.img or fill the device fingerprint manually"));
            log("    pre-device=" + meta.get("pre-device")
                    + " · post-timestamp=" + meta.get("post-timestamp")
                    + " · spl=" + meta.get("post-security-patch-level"));
            log("    post-build=" + meta.get("post-build")
                    + " · incremental=" + meta.get("post-build-incremental"));
            outSize = OtaPacker.buildOtaZip(new File(work, "payload.bin"), props, meta,
                    outFile, l -> log("    " + l), copyProgress);
        } else {
            log("📦 " + t("以模板重建（替换 payload / metadata 补丁）", "Rebuilding from template"));
            DnaTools.run(this, "chmod 666 " + DnaTools.quote(realTpl),
                    l -> kotlin.Unit.INSTANCE, () -> false, 15000);
            outSize = OtaPacker.rebuildOtaZip(new File(tplPath), new File(work, "payload.bin"),
                    props, outFile, l -> log("    " + l), copyProgress);
        }

        // ---- 8. whole-file 签名 ----
        setStatus("🔐 " + t("whole-file 签名 ...", "Whole-file signing..."), 0xff5a6b82);
        log("🔐 " + t("whole-file 签名（signapk -w 格式，TWRP/recovery 验签）",
                "whole-file sign (signapk -w format)"));
        OtaPacker.signWholeFile(outFile, l -> log("    " + l));

        // ---- 9. 收尾 ----
        log("✓ " + t("卡刷包打包完成", "OTA zip built") + ": " + outFile.getAbsolutePath());
        log("📦 " + t("大小", "Size") + ": " + fmtSizeShort(outFile.length())
                + " · " + imgs.size() + t(" 个分区", " partitions"));
        // root 放行，便于用户在文件管理器直接可见可传
        DnaTools.run(this, "chmod 666 " + DnaTools.quote(DnaTools.INSTANCE.realPath(outFile.getAbsolutePath())),
                l -> kotlin.Unit.INSTANCE, () -> false, 15000);
    }

    /**
     * 模式三（v3.43.2）：从 img 目录的 system.img 读取目标机 build.prop。
     * 链路（root shell）：magic 探测（sparse/ext4/erofs）→ sparse 先经 simg2img 转 raw
     * → loop 只读挂载 → cat build.prop（system + vendor 双读，vendor 先 system 后，
     * 指纹类属性以 system 覆盖）→ umount → {@link OtaPacker#parseBuildProp} 解析。
     * 挂载失败（个别 ROM SELinux 限制 loop）时给出明确指引。
     */
    private Map<String, String> collectMetaFromSystemImg(String work, String realImgDir,
                                                          String devOverride) throws Exception {
        String sysImg = realImgDir + "/system.img";
        DnaTools.Result r = DnaTools.run(this, "[ -f " + DnaTools.quote(sysImg) + " ] && echo YES || echo NO",
                l -> kotlin.Unit.INSTANCE, () -> false, 15000);
        if (!r.getSuccess() || !r.getOutput().trim().endsWith("YES"))
            throw new PackException(t("system 模式需要 img 目录里包含 system.img（未找到）—— "
                    + "请切换「本机」来源或补齐 system.img",
                    "system.img not found in img dir — switch to 'local' source or provide it"));

        // magic 探测：offset 0 = sparse(3a ff 26 ed)；1024 = erofs(e2 e1 f5 e0)；1080 = ext4(53 ef)
        r = DnaTools.run(this,
                "dd if=" + DnaTools.quote(sysImg) + " bs=1 skip=0 count=4 2>/dev/null | od -An -tx1"
                        + " ; dd if=" + DnaTools.quote(sysImg) + " bs=1 skip=1024 count=4 2>/dev/null | od -An -tx1"
                        + " ; dd if=" + DnaTools.quote(sysImg) + " bs=1 skip=1080 count=2 2>/dev/null | od -An -tx1",
                l -> kotlin.Unit.INSTANCE, () -> false, 30000);
        if (!r.getSuccess()) throw new PackException(t("system.img 读取失败", "failed to read system.img"));
        String[] lines = r.getOutput().trim().split("\n");
        String m0 = lines.length > 0 ? lines[0].trim() : "";
        String m1024 = lines.length > 1 ? lines[1].trim() : "";
        String m1080 = lines.length > 2 ? lines[2].trim() : "";
        log("    magic[0]=" + m0 + " magic[1024]=" + m1024 + " magic[1080]=" + m1080);

        String toMount = sysImg;
        String fsType;
        if (m0.startsWith("3a ff 26 ed")) {          // Android sparse → 先转 raw
            log("    · " + t("检测到 sparse 镜像，simg2img 转换中 ...", "sparse image, converting..."));
            String raw = DnaTools.INSTANCE.realPath(work) + "/system-raw.img";
            DnaTools.Result c = DnaTools.run(this,
                    "simg2img " + DnaTools.quote(sysImg) + " " + DnaTools.quote(raw),
                    l -> kotlin.Unit.INSTANCE, () -> cancelFlag.get(), 10 * 60_000L);
            if (!c.getSuccess()) throw new PackException(t("simg2img 转换失败", "simg2img failed"));
            toMount = raw;
            // 转换后重探 fs 类型
            r = DnaTools.run(this,
                    "dd if=" + DnaTools.quote(raw) + " bs=1 skip=1024 count=4 2>/dev/null | od -An -tx1"
                            + " ; dd if=" + DnaTools.quote(raw) + " bs=1 skip=1080 count=2 2>/dev/null | od -An -tx1",
                    l -> kotlin.Unit.INSTANCE, () -> false, 30000);
            String[] l2 = r.getOutput().trim().split("\n");
            m1024 = l2.length > 0 ? l2[0].trim() : "";
            m1080 = l2.length > 1 ? l2[1].trim() : "";
        }
        if (m1024.startsWith("e2 e1 f5 e0")) fsType = "erofs";
        else if (m1080.startsWith("53 ef")) fsType = "ext4";
        else throw new PackException(t("system.img 文件系统无法识别（仅支持 ext4 / erofs / sparse）",
                "unrecognized system.img fs (ext4 / erofs / sparse only)"));
        log("    · " + t("文件系统", "fs") + ": " + fsType);

        // loop 只读挂载 → 读 build.prop → 卸载（###SYSTEM### 标记分隔，ro. 有值即成功）
        String mnt = DnaTools.INSTANCE.realPath(work) + "/mnt";
        r = DnaTools.run(this,
                "mkdir -p " + DnaTools.quote(mnt)
                        + " && mount -t " + fsType + " -o loop,ro " + DnaTools.quote(toMount)
                        + " " + DnaTools.quote(mnt)
                        + " && echo '###SYSTEM###' && cat " + DnaTools.quote(mnt) + "/build.prop"
                        + " ; umount " + DnaTools.quote(mnt) + " 2>/dev/null; true",
                l -> kotlin.Unit.INSTANCE, () -> cancelFlag.get(), 60000);
        String out = r.getOutput();
        String sysProp = out.contains("###SYSTEM###")
                ? out.substring(out.indexOf("###SYSTEM###") + 12) : "";
        if (!r.getSuccess() || !sysProp.contains("ro.product.device")
                && !sysProp.contains("ro.product.system.device")
                && !sysProp.contains("ro.product.vendor.device"))
            throw new PackException(t("system.img 挂载失败或缺少 build.prop（此 ROM 可能限制 loop 挂载）—— "
                    + "请改用「本机」来源（给本机打包）或手动填写设备代号",
                    "system.img mount failed or build.prop missing (loop restricted by this ROM) — "
                            + "use 'local' source or fill the codename manually"));

        // vendor.img 若存在：同样挂载读取，拼在 system 之前（后解析的 system 覆盖同 key，保证指纹取 system 值）
        String vendorProp = "";
        r = DnaTools.run(this, "[ -f " + DnaTools.quote(realImgDir + "/vendor.img") + " ] && echo YES || echo NO",
                l -> kotlin.Unit.INSTANCE, () -> false, 15000);
        if (r.getSuccess() && r.getOutput().trim().endsWith("YES")) {
            r = DnaTools.run(this,
                    "mkdir -p " + DnaTools.quote(mnt) + " && (mount -t erofs -o loop,ro "
                            + DnaTools.quote(realImgDir + "/vendor.img") + " " + DnaTools.quote(mnt)
                            + " 2>/dev/null || mount -t ext4 -o loop,ro "
                            + DnaTools.quote(realImgDir + "/vendor.img") + " " + DnaTools.quote(mnt) + ")"
                            + " && cat " + DnaTools.quote(mnt) + "/build.prop"
                            + " ; umount " + DnaTools.quote(mnt) + " 2>/dev/null; true",
                    l -> kotlin.Unit.INSTANCE, () -> cancelFlag.get(), 60000);
            if (r.getSuccess()) vendorProp = r.getOutput();
        }

        Map<String, String> meta = OtaPacker.parseBuildProp(vendorProp + "\n" + sysProp);
        if (devOverride != null && !devOverride.isEmpty())
            meta.put("pre-device", devOverride);      // 手动代号覆盖
        if (!meta.containsKey("pre-device") || meta.get("pre-device").isEmpty())
            throw new PackException(t("build.prop 中未找到 ro.product.device —— 请手动填写设备代号",
                    "ro.product.device missing in build.prop — fill the codename manually"));
        return meta;
    }

    /** 从零生成模式的设备元数据：手动代号优先，其余 getprop 本机采集 */
    private Map<String, String> collectDeviceMeta(String devOverride) throws Exception {
        Map<String, String> m = new LinkedHashMap<>();
        String dev = devOverride != null ? devOverride.trim() : "";
        if (dev.isEmpty()) dev = getprop("ro.product.device");
        if (dev.isEmpty())
            throw new PackException(t("无法获取设备代号（getprop ro.product.device 为空，请手动填写）",
                    "cannot detect device codename, fill it manually"));
        m.put("pre-device", dev);
        putIfFound(m, "post-build", "ro.build.fingerprint");
        putIfFound(m, "post-build-incremental", "ro.build.version.incremental");
        putIfFound(m, "post-timestamp", "ro.build.date.utc");
        putIfFound(m, "post-security-patch-level", "ro.build.version.security_patch");
        return m;
    }

    private void putIfFound(Map<String, String> m, String key, String prop) {
        String v = getprop(prop);
        if (!v.isEmpty()) m.put(key, v);
    }

    private String getprop(String key) {
        try {
            com.topjohnwu.superuser.Shell.Result r = com.topjohnwu.superuser.Shell.cmd(
                    "getprop " + key).exec();
            if (!r.getOut().isEmpty()) return r.getOut().get(0).trim();
        } catch (Exception ignored) {}
        return "";
    }

    private String fileSize(String path) {
        try {
            com.topjohnwu.superuser.Shell.Result r = com.topjohnwu.superuser.Shell.cmd(
                    "stat -c '%s' " + DnaTools.quote(path) + " 2>/dev/null").exec();
            if (!r.getOut().isEmpty()) return fmtSizeShort(Long.parseLong(r.getOut().get(0).trim()));
        } catch (Exception ignored) {}
        return "?";
    }

    private static byte[] readBytes(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeBytes(File f, byte[] data) throws Exception {
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(data);
        }
    }
}
