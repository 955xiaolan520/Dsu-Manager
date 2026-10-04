package com.probiotics.xiaoni;

import android.app.Dialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.TranslateAnimation;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DNA 工作台（v3.28.2 交互重构）：
 * - 选择 / 切换工程后自动在页面内列出工程文件（玻璃行 + 圆点 + 大小，点选即选，不再弹窗）
 * - bin / super 选文件后自动列出分区（玻璃行多选，支持全选 / 清空）
 * - 执行时展示圆角渐变滑动进度条，状态行实时显示
 * - 单选项全部升级为液态玻璃胶囊分段控件，SeekBar / 输入框圆角玻璃化
 * 全程液态玻璃 UI + 全局振动反馈 + 通知栏状态同步。
 */
public final class DnaActivity extends BaseActivity {

    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_FILTER = "filter";
    public static final String MODE_EXTRACT = "extract";
    public static final String MODE_BIN = "bin";
    public static final String MODE_SUPER_UNPACK = "superU";
    public static final String MODE_REPACK = "repack";
    public static final String MODE_SUPER_PACK = "superP";
    public static final String MODE_CONVERT = "convert";
    public static final String MODE_SPARSE = "sparse";
    public static final String MODE_ZST = "zst";
    public static final String MODE_CHUNK = "chunk";

    private static final int PICK_FILE = 2001;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);

    private String mode;
    private String filter = "";

    // 当前工程（null = 未选择）
    private String project;
    // 选中的工程内文件（多选集合 / 单选即一个）
    private final Set<String> picked = new LinkedHashSet<>();
    // 来自 /data/PDNA/工程名 分解输出目录的名字（合成 repack 数据源）
    private final Set<String> droNames = new LinkedHashSet<>();
    // 手动输入的外部路径（空格分隔）
    private String manualPaths = "";

    private TextView projectView;
    private TextView projectMeta;
    private TextView fileCountView;
    private TextView status;
    private TextView logDisplay;
    private ScrollView logScroll;
    private LinearLayout fileList;          // 工程文件行容器
    private BoundedScrollView fileListScroll;
    private LinearLayout partitionsList;    // 分区行容器
    private LinearLayout partitionsCard;
    private TextView partitionsHint;
    private FrameLayout progressTrack;
    private View progressFill;
    private Button runButton;
    private EditText manualInput;

    // 行视图记录（工程文件）
    private final List<Row> fileRows = new ArrayList<>();
    // 行视图记录（分区）
    private final List<Row> partRows = new ArrayList<>();
    private final Set<String> checkedPartitions = new LinkedHashSet<>();
    private final List<String> partitionNames = new ArrayList<>();

    /** 可点击玻璃行（圆点 + 名称 + 附加信息） */
    private static final class Row {
        final String name;
        final LinearLayout view;
        final View dot;
        final TextView label;
        final TextView tail;
        Row(String name, LinearLayout view, View dot, TextView label, TextView tail) {
            this.name = name; this.view = view; this.dot = dot; this.label = label; this.tail = tail;
        }
    }

    /** 限高 ScrollView（文件 / 分区列表内嵌滚动，不撑爆页面） */
    public static class BoundedScrollView extends ScrollView {
        private final int maxHeight;
        public BoundedScrollView(android.content.Context c, int maxHeightPx) {
            super(c);
            maxHeight = maxHeightPx;
            setVerticalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            setOnTouchListener((v, event) -> {
                int action = event.getActionMasked();
                v.getParent().requestDisallowInterceptTouchEvent(
                        action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL);
                return false;
            });
        }
        @Override
        protected void onMeasure(int wms, int hms) {
            super.onMeasure(wms, hms);
            if (getMeasuredHeight() > maxHeight) {
                setMeasuredDimension(getMeasuredWidth(), maxHeight);
            }
        }
    }

    /** 液态玻璃胶囊分段选择器（替代 RadioGroup）
     *  v3.28.4：每组一个主题色（标签彩点 + 选中胶囊渐变），解决配色单调问题 */
    private final class SegmentGroup {
        private final List<TextView> pills = new ArrayList<>();
        private int selected;
        private final int accent;
        SegmentGroup(LinearLayout parent, String label, String[] options, int init, int accentColor) {
            accent = accentColor;
            LinearLayout labelRow = new LinearLayout(DnaActivity.this);
            labelRow.setOrientation(LinearLayout.HORIZONTAL);
            labelRow.setGravity(Gravity.CENTER_VERTICAL);
            labelRow.setPadding(dp(2), dp(8), 0, 0);
            View dot = new View(DnaActivity.this);
            GradientDrawable dd = new GradientDrawable();
            dd.setShape(GradientDrawable.OVAL);
            dd.setColor(accent);
            dot.setBackground(dd);
            labelRow.addView(dot, new LinearLayout.LayoutParams(dp(7), dp(7)));
            TextView text = new TextView(DnaActivity.this);
            text.setText(label);
            text.setTextSize(12.5f);
            text.setTypeface(null, 1);
            text.setTextColor(0xff5a6b82);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
            tl.leftMargin = dp(6);
            labelRow.addView(text, tl);
            parent.addView(labelRow, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout strip = new LinearLayout(DnaActivity.this);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            strip.setPadding(0, dp(6), 0, dp(2));
            for (int i = 0; i < options.length; i++) {
                final int idx = i;
                TextView pill = new TextView(DnaActivity.this);
                pill.setText(options[i]);
                pill.setTextSize(13f);
                pill.setTypeface(null, 1);
                pill.setGravity(Gravity.CENTER);
                pill.setMinHeight(dp(36));
                pill.setPadding(dp(4), 0, dp(4), 0);
                pill.setOnClickListener(v -> {
                    Haptics.perform(v);
                    select(idx);
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(36), 1f);
                if (i > 0) lp.leftMargin = dp(8);
                strip.addView(pill, lp);
                pills.add(pill);
            }
            parent.addView(strip, new LinearLayout.LayoutParams(-1, -2));
            select(init);
        }
        void select(int i) {
            selected = i;
            for (int k = 0; k < pills.size(); k++) {
                TextView pill = pills.get(k);
                boolean on = k == i;
                if (on) {
                    GradientDrawable gd = new GradientDrawable();
                    gd.setOrientation(GradientDrawable.Orientation.TL_BR);
                    gd.setColors(new int[]{blendWhite(accent, 0.42f), accent});
                    gd.setCornerRadius(dp(16));
                    gd.setStroke(Math.max(1, dp(1)), 0x59FFFFFF);
                    pill.setBackground(gd);
                    pill.setTextColor(0xFFFFFFFF);
                } else {
                    pill.setBackgroundResource(R.drawable.dna_pill_glass);
                    pill.setTextColor(0xff2c405e);
                }
                pill.animate().scaleX(on ? 1.03f : 1f).scaleY(on ? 1.03f : 1f).setDuration(120).start();
            }
        }
        int selected() { return selected; }
    }

    /** 颜色向白色混合（选中胶囊高光渐变起点） */
    private static int blendWhite(int color, float ratio) {
        int a = (int) (((color >>> 24) & 0xff) * (1 - ratio) + 0xff * ratio);
        int r = (int) (((color >>> 16) & 0xff) * (1 - ratio) + 0xff * ratio);
        int g = (int) (((color >>> 8) & 0xff) * (1 - ratio) + 0xff * ratio);
        int b = (int) ((color & 0xff) * (1 - ratio) + 0xff * ratio);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        mode = getIntent().getStringExtra(EXTRA_MODE);
        if (mode == null) mode = MODE_EXTRACT;
        filter = getIntent().getStringExtra(EXTRA_FILTER);
        if (filter == null) filter = "";
        project = DnaTools.currentProject(this);
        getWindow().setStatusBarColor(0x00000000);
        getWindow().setNavigationBarColor(0x220b131f);
        ensureNoteChannel();
        buildUi();
        refreshProjectFiles();
    }

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + 0.5f);
    }

    private LinearLayout glass() {
        LinearLayout panel = new LinearLayout(this);
        panel.setBackgroundResource(R.drawable.liquid_glass_panel);
        return panel;
    }

    private String projectPath() {
        return project != null ? DnaTools.WORK_ROOT + "/" + project : DnaTools.WORK_ROOT;
    }

    // ============ UI 构建 ============

    private void buildUi() {
        int statusBarHeight = 0;
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) statusBarHeight = getResources().getDimensionPixelSize(resourceId);

        FrameLayout root = new FrameLayout(this);
        root.setBackground(createGradientBackground());
        root.setPadding(0, statusBarHeight + dp(10), 0, 0);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0x00000000);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), dp(16));

        // ---- 标题栏（圆返回键 + 标题 + 工程胶囊）----
        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        Button back = new Button(this);
        back.setText("‹");
        back.setTextSize(26);
        back.setMinWidth(0);
        back.setMinHeight(0);
        back.setAllCaps(false);
        back.setTypeface(null, 1);
        back.setTextColor(0xff0f1e36);
        // v3.28.7：显式居中 + 零内边距（修复设备主题默认内边距挤压文字）
        back.setGravity(Gravity.CENTER);
        back.setPadding(0, 0, 0, 0);
        GradientDrawable backBg = new GradientDrawable();
        backBg.setShape(GradientDrawable.OVAL);
        backBg.setColor(0x66ffffff);
        backBg.setStroke(Math.max(1, dp(1)), 0x99ffffff);
        back.setBackground(backBg);
        back.setStateListAnimator(null);
        back.setOnClickListener(v -> {
            Haptics.perform(v);
            finish();
            overridePendingTransition(R.anim.slide_up_in, R.anim.slide_up_out);
        });
        titleBar.addView(back, new LinearLayout.LayoutParams(dp(46), dp(46)));
        TextView title = new TextView(this);
        title.setText(modeTitle());
        title.setTextSize(19);
        title.setTextColor(0xff0f1e36);
        title.setTypeface(null, 1);
        title.setPadding(dp(12), 0, 0, 0);
        titleBar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView modeChip = new TextView(this);
        modeChip.setText("PDNA");
        modeChip.setTextSize(11f);
        modeChip.setTypeface(null, 1);
        modeChip.setTextColor(0xff1f7d72);
        modeChip.setGravity(Gravity.CENTER);
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setCornerRadius(dp(13));
        chipBg.setColor(0x59eafff5);
        chipBg.setStroke(Math.max(1, dp(1)), 0x662f9c8f);
        modeChip.setBackground(chipBg);
        titleBar.addView(modeChip, new LinearLayout.LayoutParams(dp(58), dp(26)));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.bottomMargin = dp(10);
        content.addView(titleBar, titleLp);

        // ---- 工程选择横幅（最顶部 · 整卡点击切换工程，对齐原版"工程菜单"）----
        LinearLayout projectCard = glass();
        projectCard.setOrientation(LinearLayout.VERTICAL);
        projectCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable pickBorder = new GradientDrawable();
        pickBorder.setColor(0x33FFFFFF);
        pickBorder.setCornerRadius(dp(18));
        pickBorder.setStroke(Math.max(1, dp(1)), 0x6635A8C4);
        projectCard.setBackground(pickBorder);
        LinearLayout projRow = new LinearLayout(this);
        projRow.setOrientation(LinearLayout.HORIZONTAL);
        projRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout projectText = new LinearLayout(this);
        projectText.setOrientation(LinearLayout.VERTICAL);
        TextView projectLabel = new TextView(this);
        projectLabel.setText(t("当前工程（点击选择）", "Current project (tap to switch)"));
        projectLabel.setTextSize(11.5f);
        projectLabel.setTextColor(0xff5a6b82);
        projectText.addView(projectLabel, new LinearLayout.LayoutParams(-1, -2));
        projectView = new TextView(this);
        projectView.setTextSize(18f);
        projectView.setTypeface(null, 1);
        projectView.setSingleLine(true);
        projectView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        projectText.addView(projectView, new LinearLayout.LayoutParams(-1, -2));
        projRow.addView(projectText, new LinearLayout.LayoutParams(0, -2, 1f));
        Button switchProject = new Button(this);
        switchProject.setText("⌄");
        switchProject.setAllCaps(false);
        switchProject.setTextSize(18);
        switchProject.setTypeface(null, 1);
        switchProject.setTextColor(0xff172b4d);
        GradientDrawable switchBg = new GradientDrawable();
        switchBg.setShape(GradientDrawable.OVAL);
        switchBg.setColor(0x59FFFFFF);
        switchBg.setStroke(Math.max(1, dp(1)), 0x80FFFFFF);
        switchProject.setBackground(switchBg);
        switchProject.setStateListAnimator(null);
        switchProject.setMinWidth(0);
        switchProject.setMinHeight(0);
        // v3.28.7：显式居中 + 零内边距
        switchProject.setGravity(Gravity.CENTER);
        switchProject.setPadding(0, 0, 0, 0);
        switchProject.setOnClickListener(v -> {
            Haptics.perform(v);
            showProjectPicker();
        });
        projRow.addView(switchProject, new LinearLayout.LayoutParams(dp(42), dp(42)));
        projectCard.addView(projRow, new LinearLayout.LayoutParams(-1, -2));
        projectMeta = new TextView(this);
        projectMeta.setTextSize(10.5f);
        projectMeta.setTextColor(0xff5a6b82);
        projectMeta.setPadding(0, dp(5), 0, 0);
        projectCard.addView(projectMeta, new LinearLayout.LayoutParams(-1, -2));
        projectCard.setOnClickListener(v -> {
            Haptics.perform(v);
            showProjectPicker();
        });
        LinearLayout.LayoutParams projLp = new LinearLayout.LayoutParams(-1, -2);
        projLp.bottomMargin = dp(10);
        content.addView(projectCard, projLp);
        refreshProjectView();

        // ---- 说明条 ----
        TextView desc = new TextView(this);
        desc.setText(modeDesc());
        desc.setTextSize(12.5f);
        desc.setTextColor(0xff2c405e);
        desc.setPadding(dp(14), dp(9), dp(14), dp(9));
        desc.setBackgroundResource(R.drawable.dna_row_glass);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(-1, -2);
        descLp.bottomMargin = dp(10);
        content.addView(desc, descLp);

        // ---- 文件卡：标题行 + 内嵌文件列表 + 手动路径 ----
        LinearLayout pickCard = glass();
        pickCard.setOrientation(LinearLayout.VERTICAL);
        pickCard.setPadding(dp(12), dp(10), dp(12), dp(12));
        LinearLayout pickHead = new LinearLayout(this);
        pickHead.setOrientation(LinearLayout.HORIZONTAL);
        pickHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView pickTitle = new TextView(this);
        pickTitle.setText(t("工程文件", "Project files"));
        pickTitle.setTextSize(15);
        pickTitle.setTypeface(null, 1);
        pickTitle.setTextColor(0xff17334f);
        pickHead.addView(pickTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        fileCountView = new TextView(this);
        fileCountView.setTextSize(11.5f);
        fileCountView.setTypeface(null, 1);
        fileCountView.setTextColor(0xff2f9c8f);
        fileCountView.setPadding(0, 0, dp(10), 0);
        pickHead.addView(fileCountView, new LinearLayout.LayoutParams(-2, -2));
        // v3.28.7：多选模式加「全选 / 清空」（分解 / 合成 / 转换等可批量处理全部文件）
        if (multiPick()) {
            Button fileAllBtn = new Button(this);
            fileAllBtn.setText(t("全选", "All"));
            fileAllBtn.setAllCaps(false);
            fileAllBtn.setTextSize(12);
            fileAllBtn.setMinWidth(0);
            fileAllBtn.setMinHeight(0);
            fileAllBtn.setGravity(Gravity.CENTER);
            fileAllBtn.setPadding(0, 0, 0, 0);
            fileAllBtn.setTextColor(0xff172b4d);
            fileAllBtn.setBackgroundResource(R.drawable.dna_pill_glass);
            fileAllBtn.setStateListAnimator(null);
            fileAllBtn.setOnClickListener(v -> {
                Haptics.perform(v);
                picked.clear();
                for (Row r : fileRows) picked.add(r.name);
                refreshFileRows();
            });
            pickHead.addView(fileAllBtn, new LinearLayout.LayoutParams(dp(52), dp(32)));
            Button fileNoneBtn = new Button(this);
            fileNoneBtn.setText(t("清空", "None"));
            fileNoneBtn.setAllCaps(false);
            fileNoneBtn.setTextSize(12);
            fileNoneBtn.setMinWidth(0);
            fileNoneBtn.setMinHeight(0);
            fileNoneBtn.setGravity(Gravity.CENTER);
            fileNoneBtn.setPadding(0, 0, 0, 0);
            fileNoneBtn.setTextColor(0xffa33b3b);
            fileNoneBtn.setBackgroundResource(R.drawable.dna_pill_glass);
            fileNoneBtn.setStateListAnimator(null);
            LinearLayout.LayoutParams fileNoneLp = new LinearLayout.LayoutParams(dp(52), dp(32));
            fileNoneLp.leftMargin = dp(6);
            fileNoneBtn.setOnClickListener(v -> {
                Haptics.perform(v);
                picked.clear();
                refreshFileRows();
            });
            pickHead.addView(fileNoneBtn, fileNoneLp);
        }
        Button refresh = new Button(this);
        refresh.setText("⟳");
        refresh.setTextSize(15);
        refresh.setAllCaps(false);
        refresh.setMinWidth(0);
        refresh.setMinHeight(0);
        refresh.setGravity(Gravity.CENTER);
        refresh.setPadding(0, 0, 0, 0);
        refresh.setTextColor(0xff172b4d);
        refresh.setBackgroundResource(R.drawable.dna_pill_glass);
        refresh.setStateListAnimator(null);
        refresh.setOnClickListener(v -> {
            Haptics.perform(v);
            refreshProjectFiles();
        });
        pickHead.addView(refresh, new LinearLayout.LayoutParams(dp(38), dp(34)));
        Button pickSaf = new Button(this);
        pickSaf.setText("＋");
        pickSaf.setTextSize(15);
        pickSaf.setAllCaps(false);
        pickSaf.setMinWidth(0);
        pickSaf.setMinHeight(0);
        pickSaf.setGravity(Gravity.CENTER);
        pickSaf.setPadding(0, 0, 0, 0);
        pickSaf.setTextColor(0xff172b4d);
        LinearLayout.LayoutParams safLp = new LinearLayout.LayoutParams(dp(38), dp(34));
        safLp.leftMargin = dp(8);
        pickSaf.setBackgroundResource(R.drawable.dna_pill_glass);
        pickSaf.setStateListAnimator(null);
        pickSaf.setOnClickListener(v -> {
            Haptics.perform(v);
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, PICK_FILE);
        });
        pickHead.addView(pickSaf, safLp);
        pickCard.addView(pickHead, new LinearLayout.LayoutParams(-1, -2));

        fileListScroll = new BoundedScrollView(this, dp(320));
        fileList = new LinearLayout(this);
        fileList.setOrientation(LinearLayout.VERTICAL);
        fileListScroll.addView(fileList, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams flLp = new LinearLayout.LayoutParams(-1, -2);
        flLp.topMargin = dp(8);
        pickCard.addView(fileListScroll, flLp);

        manualInput = new EditText(this);
        manualInput.setTextSize(12.5f);
        manualInput.setTextColor(0xff17334f);
        manualInput.setHintTextColor(0xff8fa1b8);
        manualInput.setHint(t("或输入绝对路径（多个用空格分隔）", "Or absolute paths, space separated"));
        manualInput.setBackground(null);
        // v3.28.6：垂直居中 + 上下对称内边距（修复文字贴上沿不居中）
        manualInput.setGravity(Gravity.CENTER_VERTICAL);
        manualInput.setPadding(dp(4), dp(6), dp(4), dp(6));
        pickCard.addView(manualInput, new LinearLayout.LayoutParams(-1, dp(42)));
        manualInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                manualPaths = manualInput.getText().toString().trim();
                refreshFileRows();
            }
        });
        LinearLayout.LayoutParams pickLp = new LinearLayout.LayoutParams(-1, -2);
        pickLp.bottomMargin = dp(10);
        content.addView(pickCard, pickLp);

        // ---- 分区卡（bin / super 模式）----
        if (mode == MODE_BIN || mode == MODE_SUPER_UNPACK) {
            partitionsCard = glass();
            partitionsCard.setOrientation(LinearLayout.VERTICAL);
            partitionsCard.setPadding(dp(12), dp(10), dp(12), dp(12));
            LinearLayout partHead = new LinearLayout(this);
            partHead.setOrientation(LinearLayout.HORIZONTAL);
            partHead.setGravity(Gravity.CENTER_VERTICAL);
            TextView partTitle = new TextView(this);
            partTitle.setText(mode == MODE_BIN ? "payload 分区" : "super 分区");
            partTitle.setTextSize(15);
            partTitle.setTypeface(null, 1);
            partTitle.setTextColor(0xff17334f);
            partHead.addView(partTitle, new LinearLayout.LayoutParams(0, -2, 1f));
            partitionsHint = new TextView(this);
            partitionsHint.setTextSize(11.5f);
            partitionsHint.setTextColor(0xff5a6b82);
            partitionsHint.setPadding(0, 0, dp(10), 0);
            partHead.addView(partitionsHint, new LinearLayout.LayoutParams(-2, -2));
            Button allBtn = new Button(this);
            allBtn.setText(t("全选", "All"));
            allBtn.setAllCaps(false);
            allBtn.setTextSize(12);
            allBtn.setMinWidth(0);
            allBtn.setMinHeight(0);
            allBtn.setGravity(Gravity.CENTER);
            allBtn.setPadding(0, 0, 0, 0);
            allBtn.setTextColor(0xff172b4d);
            allBtn.setBackgroundResource(R.drawable.dna_pill_glass);
            allBtn.setStateListAnimator(null);
            allBtn.setOnClickListener(v -> {
                Haptics.perform(v);
                checkedPartitions.clear();
                checkedPartitions.addAll(partitionNames);
                refreshPartRows();
            });
            partHead.addView(allBtn, new LinearLayout.LayoutParams(dp(56), dp(32)));
            Button noneBtn = new Button(this);
            noneBtn.setText(t("清空", "None"));
            noneBtn.setAllCaps(false);
            noneBtn.setTextSize(12);
            noneBtn.setMinWidth(0);
            noneBtn.setMinHeight(0);
            noneBtn.setGravity(Gravity.CENTER);
            noneBtn.setPadding(0, 0, 0, 0);
            noneBtn.setTextColor(0xffa33b3b);
            LinearLayout.LayoutParams noneLp = new LinearLayout.LayoutParams(dp(56), dp(32));
            noneLp.leftMargin = dp(6);
            noneBtn.setBackgroundResource(R.drawable.dna_pill_glass);
            noneBtn.setStateListAnimator(null);
            noneBtn.setOnClickListener(v -> {
                Haptics.perform(v);
                checkedPartitions.clear();
                refreshPartRows();
            });
            partHead.addView(noneBtn, noneLp);
            partitionsCard.addView(partHead, new LinearLayout.LayoutParams(-1, -2));
            BoundedScrollView partScroll = new BoundedScrollView(this, dp(320));
            partitionsList = new LinearLayout(this);
            partitionsList.setOrientation(LinearLayout.VERTICAL);
            partScroll.addView(partitionsList, new ScrollView.LayoutParams(-1, -2));
            LinearLayout.LayoutParams psLp = new LinearLayout.LayoutParams(-1, -2);
            psLp.topMargin = dp(8);
            partitionsCard.addView(partScroll, psLp);
            LinearLayout.LayoutParams partLp = new LinearLayout.LayoutParams(-1, -2);
            partLp.bottomMargin = dp(10);
            content.addView(partitionsCard, partLp);
            showPartitionsHint(t("选择文件后自动列出分区（不勾选 = 全部）", "Auto-listed after picking (none = all)"));
        }

        // ---- 模式选项 ----
        LinearLayout optionsHost = new LinearLayout(this);
        optionsHost.setOrientation(LinearLayout.VERTICAL);
        content.addView(optionsHost, new LinearLayout.LayoutParams(-1, -2));
        buildOptions(optionsHost);

        // ---- 状态行 + 圆角进度条 ----
        status = new TextView(this);
        status.setTextSize(12.5f);
        status.setTextColor(0xff5a6b82);
        // v3.28.6：上下对称内边距（视觉居中）
        status.setPadding(dp(4), dp(8), dp(4), dp(4));
        content.addView(status, new LinearLayout.LayoutParams(-1, -2));

        progressTrack = new FrameLayout(this);
        progressTrack.setBackgroundResource(R.drawable.dna_progress_track);
        progressTrack.setVisibility(View.GONE);
        progressFill = new View(this);
        progressFill.setBackgroundResource(R.drawable.dna_progress_fill);
        progressTrack.addView(progressFill, new FrameLayout.LayoutParams(dp(84), android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams ptLp = new LinearLayout.LayoutParams(-1, dp(12));
        ptLp.topMargin = dp(8);
        content.addView(progressTrack, ptLp);

        // ---- 执行按钮 ----
        runButton = new Button(this);
        runButton.setAllCaps(false);
        runButton.setText("▶  " + actionLabel());
        runButton.setTextSize(16);
        runButton.setTypeface(null, 1);
        runButton.setTextColor(Color.WHITE);
        // v3.28.7：显式居中 + 零内边距
        runButton.setGravity(Gravity.CENTER);
        runButton.setPadding(0, 0, 0, 0);
        runButton.setBackgroundResource(R.drawable.button_green);
        runButton.setStateListAnimator(null);
        runButton.setOnClickListener(v -> {
            Haptics.perform(v);
            if (running.get()) {
                cancelFlag.set(true);
                logLine(t("正在取消 ...", "Cancelling..."));
                return;
            }
            execute();
        });
        LinearLayout.LayoutParams runLp = new LinearLayout.LayoutParams(-1, dp(54));
        runLp.topMargin = dp(10);
        runLp.bottomMargin = dp(10);
        content.addView(runButton, runLp);

        // ---- 终端日志面板（v3.28.4：头部加复制 / 清空按钮，支持上下滑动）----
        LinearLayout logHead = new LinearLayout(this);
        logHead.setOrientation(LinearLayout.HORIZONTAL);
        logHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView logTitle = new TextView(this);
        logTitle.setText(t("执行日志", "Console"));
        logTitle.setTextSize(13);
        logTitle.setTypeface(null, 1);
        logTitle.setTextColor(0xff17334f);
        logHead.addView(logTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        Button clearLog = new Button(this);
        clearLog.setText(t("清空", "Clear"));
        clearLog.setAllCaps(false);
        clearLog.setTextSize(11.5f);
        clearLog.setTextColor(0xffa33b3b);
        clearLog.setMinWidth(0);
        clearLog.setMinHeight(0);
        // v3.28.7：显式居中
        clearLog.setGravity(Gravity.CENTER);
        clearLog.setPadding(dp(10), 0, dp(10), 0);
        clearLog.setBackgroundResource(R.drawable.dna_pill_glass);
        clearLog.setStateListAnimator(null);
        clearLog.setOnClickListener(v -> {
            Haptics.perform(v);
            logDisplay.setText("");
            toast(t("日志已清空", "Log cleared"));
        });
        logHead.addView(clearLog, new LinearLayout.LayoutParams(-2, dp(30)));
        Button copyLog = new Button(this);
        copyLog.setText("⧉ " + t("复制日志", "Copy"));
        copyLog.setAllCaps(false);
        copyLog.setTextSize(11.5f);
        copyLog.setTypeface(null, 1);
        copyLog.setTextColor(0xff1f7d72);
        copyLog.setMinWidth(0);
        copyLog.setMinHeight(0);
        // v3.28.7：显式居中
        copyLog.setGravity(Gravity.CENTER);
        copyLog.setPadding(dp(10), 0, dp(10), 0);
        LinearLayout.LayoutParams clLp = new LinearLayout.LayoutParams(-2, dp(30));
        clLp.leftMargin = dp(8);
        copyLog.setBackgroundResource(R.drawable.dna_pill_glass);
        copyLog.setStateListAnimator(null);
        copyLog.setOnClickListener(v -> {
            Haptics.perform(v);
            CharSequence text = logDisplay.getText();
            if (text.length() == 0) {
                toast(t("暂无日志", "Nothing to copy"));
                return;
            }
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("DNA log", text));
            toast(t("已复制全部日志", "Log copied"));
        });
        logHead.addView(copyLog, clLp);
        content.addView(logHead, new LinearLayout.LayoutParams(-1, -2));
        GradientDrawable logBg = new GradientDrawable();
        logBg.setColor(0xCC0b1622);
        logBg.setCornerRadius(dp(18));
        logBg.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
        logDisplay = new TextView(this);
        logDisplay.setTypeface(android.graphics.Typeface.MONOSPACE);
        logDisplay.setTextSize(11.5f);
        logDisplay.setTextColor(0xFF9fd8b4);
        logDisplay.setPadding(dp(12), dp(10), dp(12), dp(10));
        logDisplay.setLineSpacing(dp(2), 1f);
        logScroll = new ScrollView(this);
        logScroll.setBackground(logBg);
        logScroll.setFillViewport(false);
        logScroll.setVerticalScrollBarEnabled(true);
        logScroll.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            view.getParent().requestDisallowInterceptTouchEvent(
                    action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL);
            return false;
        });
        logScroll.addView(logDisplay, new ScrollView.LayoutParams(-1, -2));
        // v3.28.6：日志框与上方按钮行保持距离感（12→16dp）
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(-1, dp(260));
        logLp.topMargin = dp(16);
        content.addView(logScroll, logLp);

        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    private String t(String zh, String en) {
        return getResources().getConfiguration().locale.getLanguage().startsWith("zh") ? zh : en;
    }

    private String modeTitle() {
        switch (mode) {
            case MODE_BIN: return "DNA · " + t("分解 bin", "Extract bin");
            case MODE_SUPER_UNPACK: return "DNA · " + t("分解 super", "Extract super");
            case MODE_REPACK: return "DNA · " + t("合成镜像", "Repack");
            case MODE_SUPER_PACK: return "DNA · " + t("合成 super", "Build super");
            case MODE_CONVERT: return "DNA · " + t("img-dat-br 转换", "img-dat-br");
            case MODE_SPARSE: return "DNA · " + t("img-simg 互转", "img-simg");
            case MODE_ZST: return "DNA · " + t("zst-img 互转", "zst-img");
            case MODE_CHUNK: return "DNA · " + t("合并 Sparse 分段", "Merge sparse chunks");
            default: return "DNA · " + t("分解镜像", "Extract");
        }
    }

    private String modeDesc() {
        switch (mode) {
            case MODE_BIN: return t("payload.bin → 分区镜像。选择文件后自动列出分区，勾选需要的分区提取到当前工程（payload_extract）。",
                    "payload.bin → partition images (payload_extract).");
            case MODE_SUPER_UNPACK: return t("super.img → 分区镜像（lpunpack）。自动识别动态分区，可勾选分区、支持提取后自动分解。",
                    "super.img → partition images (lpunpack).");
            case MODE_REPACK: return t("分解目录 → img / dat / br 镜像（repack）。自动列出当前工程 /data/PDNA/工程名 下的分解输出目录（原版 findfile.sh dir），支持 ext4 / erofs / f2fs，可多目录同时打包。",
                    "Extracted dirs → img / dat / br (repack). ext4 / erofs / f2fs.");
            case MODE_SUPER_PACK: return t("分区镜像 → super.img（lpmake）。支持 A-only / AB / Virtual-AB，raw / sparse 输出。",
                    "Partition images → super.img (lpmake).");
            case MODE_CONVERT: return t("img → dat / br（dna convert）。多选批量转换，自动压缩等级。",
                    "img → dat / br (dna convert).");
            case MODE_SPARSE: return t("img ↔ sparse 互转。gettype 自动判断方向：ext / erofs → img2simg，sparse → simg2img，输出到工程 out/",
                    "raw ↔ sparse (auto direction), output to project out/.");
            case MODE_ZST: return t("zst ↔ img 互转（zstd 多线程高速压缩 / 解压），输出到工程 out/。",
                    "zst ↔ img (zstd multithread), output to project out/.");
            case MODE_CHUNK: return t("分段 Sparse 镜像合并：xxx.img.1 + xxx.img.2 … → out/xxx.img（simg2img）。",
                    "Merge split sparse images: prefix.N → out/prefix (simg2img).");
            default: return t("img / br / dat → 可编辑目录（dna extract）。自动识别 erofs / ext4 / f2fs / sparse 格式，支持多选批量分解。",
                    "img / br / dat → directory (dna extract).");
        }
    }

    private boolean multiPick() {
        return mode != MODE_BIN && mode != MODE_SUPER_UNPACK;
    }

    private String filterType() {
        switch (mode) {
            case MODE_BIN: return "bin";
            case MODE_REPACK: return "dro_dir";
            case MODE_SPARSE:
            case MODE_CONVERT:
            case MODE_SUPER_PACK: return "img";
            case MODE_ZST: return "zst";
            case MODE_CHUNK: return "split_sparse";
            case MODE_SUPER_UNPACK: return "img";
            default: return filter.isEmpty() ? "img" : filter;
        }
    }

    // ============ 工程文件列表（自动加载） ============

    private void refreshProjectView() {
        if (projectView == null) return;
        if (project != null) {
            projectView.setText(project);
            projectView.setTextColor(0xff17334f);
        } else {
            projectView.setText(t("未选择工程", "No project"));
            projectView.setTextColor(0xffa33b3b);
        }
    }

    /** 选择 / 切换工程后自动读取工程内匹配文件，内嵌展示（v3.28.2 核心改动）
     *  v3.28.3：合成 repack 列出 /data/PDNA/工程名 分解输出目录（原版 findfile.sh dir → DNA_DRO） */
    private void refreshProjectFiles() {
        refreshProjectView();
        fileRows.clear();
        fileList.removeAllViews();
        if (project == null) {
            fileCountView.setText("");
            projectMeta.setText(t("点击卡片选择或新建工程", "Tap card to pick / create a project"));
            TextView empty = emptyRow(t("请先选择工程", "Select a project first"));
            fileList.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        String type = filterType();
        boolean fromDro = "dro_dir".equals(type);
        List<String> files;
        if (fromDro) {
            files = DnaTools.listDroDirs(project);
            droNames.clear();
            droNames.addAll(files);
            if (files.isEmpty()) {
                // 分解输出目录为空 → 回退工程目录子目录
                files = DnaTools.listProjectFiles(project, "dir");
                droNames.clear();
            }
        } else {
            files = DnaTools.listProjectFiles(project, type);
            droNames.clear();
        }
        projectMeta.setText(t("工程 ", "Project ") + projectPath()
                + "\n" + t("分解输出 ", "Output ") + DnaTools.TMP_ROOT + "/" + project
                + (files.isEmpty() ? "" : "  ·  " + files.size() + t(" 项", " items")));
        if (files.isEmpty()) {
            fileCountView.setText("");
            TextView empty = emptyRow(fromDro
                    ? t("暂无分解输出目录（先分解 img / bin / super）", "No extracted dirs yet. Extract first")
                    : t("工程内暂无匹配文件", "No matching files in this project"));
            fileList.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        // 保留仍存在的已选项
        picked.retainAll(files);
        for (String name : files) {
            Row row = buildRow(name, rowExtra(name, type), false);
            fileList.addView(row.view, rowParams());
            fileRows.add(row);
        }
        refreshFileRows();
        // 原版 findfile.sh img 分支：列表显示 dna gettype 类型（unknow 的文件需先去格式转换）。
        // dna 冷启动慢，后台批量取类型后回填，不阻塞 UI
        if ("img".equals(type) && !files.isEmpty()) {
            final List<String> names = new ArrayList<>(files);
            final String proj = project;
            new Thread(() -> {
                java.util.Map<String, String> ts = DnaTools.getFileTypes(proj, names);
                if (ts.isEmpty()) return;
                mainHandler.post(() -> {
                    for (Row r : fileRows) {
                        String ft = ts.get(r.name);
                        if (ft == null) continue;
                        String cur = r.tail.getText().toString();
                        r.tail.setText((cur.isEmpty() ? "" : cur + " · ") + ft);
                    }
                });
            }).start();
        }
    }

    private String rowExtra(String name, String type) {
        if ("dro_dir".equals(type)) {
            return droNames.contains(name) ? t("分解输出", "extracted") : t("工程目录", "in project");
        }
        File f = new File(projectPath(), name);
        if ("dir".equals(type)) {
            File[] children = f.listFiles();
            int n = children == null ? 0 : children.length;
            return t("目录 · ", "dir · ") + n + t(" 项", " items");
        }
        if ("split_sparse".equals(type)) return t("分段", "chunks");
        long len = f.length();
        if (len <= 0) return "";
        return formatSize(len);
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double k = bytes / 1024.0;
        if (k < 1024) return String.format(Locale.US, "%.0f KB", k);
        double m = k / 1024.0;
        if (m < 1024) return String.format(Locale.US, "%.1f MB", m);
        return String.format(Locale.US, "%.2f GB", m / 1024.0);
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(6);
        return lp;
    }

    private TextView emptyRow(String text) {
        TextView empty = new TextView(this);
        empty.setText(text);
        empty.setTextSize(12.5f);
        empty.setTextColor(0xff5a6b82);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(10), dp(18), dp(10), dp(18));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x26ffffff);
        bg.setCornerRadius(dp(14));
        bg.setStroke(Math.max(1, dp(1)), 0x40ffffff);
        empty.setBackground(bg);
        return empty;
    }

    /** 玻璃行：圆点 + 名称 + 附加信息（大小 / 目录项数） */
    private Row buildRow(String name, String extra, boolean partition) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        View dot = new View(this);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(20), dp(20));
        row.addView(dot, dotLp);
        TextView label = new TextView(this);
        label.setText(name);
        label.setTextSize(13.5f);
        label.setTextColor(0xff17334f);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0, -2, 1f);
        labelLp.leftMargin = dp(10);
        labelLp.rightMargin = dp(8);
        row.addView(label, labelLp);
        TextView tail = new TextView(this);
        tail.setText(extra);
        tail.setTextSize(11f);
        tail.setTextColor(0xff5a6b82);
        row.addView(tail, new LinearLayout.LayoutParams(-2, -2));
        Row holder = new Row(name, row, dot, label, tail);
        row.setOnClickListener(v -> {
            Haptics.perform(v);
            if (partition) {
                if (checkedPartitions.contains(name)) checkedPartitions.remove(name);
                else checkedPartitions.add(name);
                refreshPartRows();
            } else if (multiPick()) {
                if (picked.contains(name)) picked.remove(name);
                else picked.add(name);
                refreshFileRows();
            } else {
                picked.clear();
                picked.add(name);
                refreshFileRows();
                afterPick(name);
            }
        });
        return holder;
    }

    /** 刷新工程文件行选中态与计数 */
    private void refreshFileRows() {
        for (Row r : fileRows) {
            boolean on = picked.contains(r.name);
            applyRowState(r, on);
        }
        if (fileCountView != null) {
            fileCountView.setText(fileRows.isEmpty() ? "" : t("已选 ", "Picked ") + picked.size() + " / " + fileRows.size());
        }
    }

    private void applyRowState(Row r, boolean on) {
        r.view.setBackgroundResource(on ? R.drawable.dna_row_checked : R.drawable.dna_row_glass);
        r.dot.setBackgroundResource(on ? R.drawable.dna_dot_on : R.drawable.dna_dot_off);
        r.label.setTextColor(on ? 0xff14524b : 0xff17334f);
        r.label.setTypeface(null, on ? 1 : 0);
    }

    private void showPartitionsHint(String text) {
        if (partitionsHint != null) partitionsHint.setText(text);
    }

    private void refreshPartRows() {
        for (Row r : partRows) applyRowState(r, checkedPartitions.contains(r.name));
        if (partitionsHint != null && !partitionNames.isEmpty()) {
            partitionsHint.setText(checkedPartitions.isEmpty()
                    ? t("全部", "all")
                    : checkedPartitions.size() + "/" + partitionNames.size());
        }
    }

    /** 选择文件后的联动：bin / super 模式自动列出分区 */
    private void afterPick(String firstName) {
        if (mode == MODE_BIN) listBinPartitions();
        else if (mode == MODE_SUPER_UNPACK) listSuperPartitions();
    }

    // ============ 分区列举 ============

    private void listBinPartitions() {
        String bin = picked.isEmpty() ? null : picked.iterator().next();
        if (bin == null || running.get()) return;
        running.set(true);
        status.setText(t("正在读取 payload 分区 ...", "Reading payload partitions..."));
        logLine("$ payload_extract -i " + bin + " -p");
        executor.execute(() -> {
            DnaTools.Result result = DnaTools.run(this,
                    "payload_extract -i " + DnaTools.quote(projectPath() + "/" + bin) + " -p",
                    line -> { logLine(line); return kotlin.Unit.INSTANCE; }, () -> false, 120000);
            mainHandler.post(() -> {
                running.set(false);
                loadPartitions(result, "payload");
            });
        });
    }

    private void listSuperPartitions() {
        String img = picked.isEmpty() ? null : picked.iterator().next();
        if (img == null || running.get()) return;
        running.set(true);
        status.setText(t("正在读取 super 分区 ...", "Reading super partitions..."));
        logLine("$ dna lpunpack --list " + img);
        executor.execute(() -> {
            DnaTools.Result result = DnaTools.run(this,
                    "dna lpunpack --list " + DnaTools.quote(projectPath() + "/" + img),
                    line -> { logLine(line); return kotlin.Unit.INSTANCE; }, () -> false, 60000);
            mainHandler.post(() -> {
                running.set(false);
                loadPartitions(result, "super");
            });
        });
    }

    private void loadPartitions(DnaTools.Result result, String what) {
        partRows.clear();
        partitionNames.clear();
        checkedPartitions.clear();
        partitionsList.removeAllViews();
        if (result.getSuccess()) {
            for (String line : result.getOutput().split("\n")) {
                String name = line.trim();
                if (name.isEmpty() || name.startsWith(">") || name.startsWith("=")) continue;
                partitionNames.add(name);
                Row row = buildRow(name, "", true);
                partitionsList.addView(row.view, rowParams());
                partRows.add(row);
            }
            status.setText(what + t(" 共 ", " has ") + partitionNames.size() + t(" 个分区", " partitions"));
            status.setTextColor(0xff17334f);
            logLine(what + " " + partitionNames.size() + t(" 个分区", " partitions"));
            refreshPartRows();
        } else {
            status.setText(t("分区列表读取失败", "Failed to list partitions"));
            status.setTextColor(0xffa33b3b);
            logLine(t("读取失败", "List failed") + ": " + result.getMessage());
            TextView empty = emptyRow(t("分区读取失败，请检查文件", "Failed to list partitions"));
            partitionsList.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    // ============ 选项 ============

    private CheckBox optionSwitch(LinearLayout parent, String label, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setTextSize(13.5f);
        box.setTextColor(0xff17334f);
        box.setChecked(checked);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(12), 0, dp(12), 0);
        box.setMinHeight(dp(42));
        box.setBackgroundResource(R.drawable.dna_option_row);
        box.setButtonTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{0xff2f9c8f, 0xff8fa1b8}));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(46));
        lp.topMargin = dp(6);
        parent.addView(box, lp);
        return box;
    }

    private LinearLayout optionsCard(LinearLayout host, String title) {
        LinearLayout card = glass();
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(12));
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextSize(15);
        heading.setTypeface(null, 1);
        heading.setTextColor(0xff17334f);
        // v3.28.6：固定高度行内垂直居中（修复标题偏上与框不居中）
        heading.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(heading, new LinearLayout.LayoutParams(-1, dp(30)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        host.addView(card, lp);
        return card;
    }

    private EditText glassInput(LinearLayout parent, String value) {
        EditText input = new EditText(this);
        input.setText(value);
        input.setTextSize(14);
        input.setTextColor(0xff17334f);
        input.setBackgroundResource(R.drawable.dna_input_glass);
        input.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(46));
        lp.topMargin = dp(4);
        parent.addView(input, lp);
        return input;
    }

    private SeekBar seekRow(LinearLayout parent, String label, int min, int max, int value) {
        TextView text = new TextView(this);
        text.setText(label + "  ·  " + value);
        text.setTextSize(12.5f);
        text.setTextColor(0xff5a6b82);
        text.setPadding(dp(2), dp(8), 0, 0);
        parent.addView(text, new LinearLayout.LayoutParams(-1, -2));
        SeekBar seek = new SeekBar(this);
        seek.setProgressDrawable(getResources().getDrawable(R.drawable.dna_seekbar, getTheme()));
        seek.setThumb(getResources().getDrawable(R.drawable.dna_seek_thumb, getTheme()));
        seek.setSplitTrack(false);
        seek.setPadding(dp(8), 0, dp(8), 0);
        seek.setMax(max - min);
        seek.setProgress(value - min);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                text.setText(label + "  ·  " + (progress + min));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        parent.addView(seek, new LinearLayout.LayoutParams(-1, dp(30)));
        return seek;
    }

    // 选项控件引用
    private static final int C_BLUE = 0xFF35A8C4;   // 晴空蓝
    private static final int C_TEAL = 0xFF11998E;   // 青碧
    private static final int C_VIOLET = 0xFF8E6FC7; // 紫罗兰
    private static final int C_ORANGE = 0xFFE07B39; // 暖橙
    private static final int C_ROSE = 0xFFD95970;   // 玫红
    private static final int C_OCEAN = 0xFF2E86AB;  // 海蓝
    private CheckBox extractDeleteSource;
    private CheckBox superAutoExtract, superDeleteSource;
    private SegmentGroup repackFsType, repackReadonly, repackAutoSize, repackFormat, repackRepackType, repackErofsMode;
    private SeekBar repackBrLevel, repackErofsLevel;
    private CheckBox repackCompress, repackDelavb;
    private SegmentGroup superPackType, superPackFormat;
    private EditText superPackSize, superPackGroup;
    private SegmentGroup convertType;
    private SeekBar convertBrLevel;
    private CheckBox convertDeleteSource;
    private CheckBox sparseDeleteSource, zstDeleteSource;
    private SeekBar zstLevel;

    private void buildOptions(LinearLayout host) {
        switch (mode) {
            case MODE_EXTRACT:
            case MODE_BIN: {
                LinearLayout card = optionsCard(host, t("分解选项", "Options"));
                extractDeleteSource = optionSwitch(card, t("删除源文件", "Delete source"), false);
                break;
            }
            case MODE_SUPER_UNPACK: {
                LinearLayout card = optionsCard(host, t("分解选项", "Options"));
                superAutoExtract = optionSwitch(card, t("自动分解提取的 IMG", "Auto-extract images"), false);
                superDeleteSource = optionSwitch(card, t("删除源文件", "Delete source"), false);
                break;
            }
            case MODE_REPACK: {
                LinearLayout card = optionsCard(host, t("打包方式", "Pack method"));
                repackFsType = new SegmentGroup(card, t("文件系统", "Filesystem"), new String[]{"ext4", "erofs", "f2fs"}, 0, C_BLUE);
                repackRepackType = new SegmentGroup(card, t("打包类型", "Type"), new String[]{"IMG", "DAT", "BR"}, 0, C_VIOLET);
                repackFormat = new SegmentGroup(card, t("打包格式", "Format"), new String[]{t("卡刷 raw", "raw"), t("线刷 sparse", "sparse")}, 0, C_ORANGE);
                // 原版 dna.xml 默认：读写 rw（选项第一位）；v3.28.10 前误默认 ro
                repackReadonly = new SegmentGroup(card, t("分区读写（仅 ext4）", "rw/ro (ext4)"), new String[]{t("读写 rw", "rw"), t("只读 ro", "ro")}, 0, C_TEAL);
                repackAutoSize = new SegmentGroup(card, t("打包大小（仅 ext4）", "Size (ext4)"), new String[]{t("自动计算", "auto"), t("原 img 大小", "original")}, 0, C_ROSE);
                repackErofsMode = new SegmentGroup(card, t("erofs 压缩方式", "erofs mode"), new String[]{"lz4hc", "lz4", "lzma"}, 0, C_OCEAN);
                repackBrLevel = seekRow(card, t("br 压缩等级", "br level"), 1, 7, 3);
                repackErofsLevel = seekRow(card, t("erofs/f2fs 压缩等级", "erofs/f2fs level"), 0, 9, 8);
                LinearLayout card2 = optionsCard(host, t("高级选项", "Advanced"));
                repackCompress = optionSwitch(card2, t("压缩 ext4 镜像空间", "Shrink ext4"), false);
                repackDelavb = optionSwitch(card2, t("去除 AVB / data 加密", "Remove AVB (fstab)"), false);
                break;
            }
            case MODE_SUPER_PACK: {
                LinearLayout card = optionsCard(host, "SUPER " + t("参数", "params"));
                superPackType = new SegmentGroup(card, t("打包类型", "Type"), new String[]{"A-only", "AB", "VAB"}, 2, C_BLUE);
                superPackFormat = new SegmentGroup(card, t("打包格式", "Format"), new String[]{"raw", "sparse"}, 0, C_TEAL);
                TextView sizeLabel = new TextView(this);
                sizeLabel.setText("super.img " + t("总大小（GB）", "size (GB)"));
                sizeLabel.setTextSize(12.5f);
                sizeLabel.setTextColor(0xff5a6b82);
                sizeLabel.setPadding(dp(2), dp(10), 0, 0);
                card.addView(sizeLabel, new LinearLayout.LayoutParams(-1, -2));
                superPackSize = glassInput(card, "8.5");
                TextView groupLabel = new TextView(this);
                groupLabel.setText(t("动态分区组名", "Group name"));
                groupLabel.setTextSize(12.5f);
                groupLabel.setTextColor(0xff5a6b82);
                groupLabel.setPadding(dp(2), dp(10), 0, 0);
                card.addView(groupLabel, new LinearLayout.LayoutParams(-1, -2));
                superPackGroup = glassInput(card, "qti_dynamic_partitions");
                break;
            }
            case MODE_CONVERT: {
                LinearLayout card = optionsCard(host, t("转换选项", "Options"));
                convertType = new SegmentGroup(card, t("转换类型", "Target"), new String[]{"DAT", "BR"}, 0, C_ORANGE);
                convertBrLevel = seekRow(card, t("br 压缩等级", "br level"), 1, 7, 3);
                LinearLayout card2 = optionsCard(host, t("选项", "Options"));
                convertDeleteSource = optionSwitch(card2, t("删除源文件", "Delete source"), false);
                break;
            }
            case MODE_SPARSE: {
                LinearLayout card = optionsCard(host, t("转换选项", "Options"));
                sparseDeleteSource = optionSwitch(card, t("删除源文件", "Delete source"), false);
                break;
            }
            case MODE_ZST: {
                LinearLayout card = optionsCard(host, t("转换选项", "Options"));
                // 原版 dna.xml：ZSTD压缩等级 seekbar 0-19 默认 3（zstd_img.sh：zstd -$level -T4 -f）
                zstLevel = seekRow(card, t("ZSTD 压缩等级", "zstd level"), 0, 19, 3);
                zstDeleteSource = optionSwitch(card, t("删除源文件", "Delete source"), false);
                break;
            }
        }
    }

    // ============ 执行 ============

    private void showProgress(boolean show) {
        if (progressTrack == null) return;
        if (show) {
            progressTrack.setVisibility(View.VISIBLE);
            TranslateAnimation anim = new TranslateAnimation(
                    Animation.RELATIVE_TO_PARENT, -0.4f,
                    Animation.RELATIVE_TO_PARENT, 1.0f,
                    Animation.RELATIVE_TO_SELF, 0f,
                    Animation.RELATIVE_TO_SELF, 0f);
            anim.setDuration(1250);
            anim.setRepeatCount(Animation.INFINITE);
            anim.setRepeatMode(Animation.RESTART);
            anim.setInterpolator(new LinearInterpolator());
            progressFill.startAnimation(anim);
        } else {
            progressFill.clearAnimation();
            progressTrack.setVisibility(View.GONE);
        }
    }

    private void execute() {
        if (project == null) {
            toast(t("请先选择工程", "Select a project first"));
            showProjectPicker();
            return;
        }
        String command = buildCommand();
        if (command == null) return;
        running.set(true);
        cancelFlag.set(false);
        runButton.setText("■  " + t("执行中（点击取消）", "Running (tap to cancel)"));
        runButton.setBackgroundResource(R.drawable.button_red);
        status.setText(t("正在执行 ...", "Running..."));
        status.setTextColor(0xff5a6b82);
        showProgress(true);
        logLine("$ " + command);
        notify(t("正在执行", "Running") + " · " + project, true, true);
        executor.execute(() -> {
            DnaTools.Result result = DnaTools.run(this, command,
                    line -> { logLine(line); return kotlin.Unit.INSTANCE; },
                    () -> cancelFlag.get());
            mainHandler.post(() -> {
                running.set(false);
                runButton.setText("▶  " + actionLabel());
                runButton.setBackgroundResource(R.drawable.button_green);
                showProgress(false);
                if (result.getSuccess()) {
                    logLine("✓ " + result.getMessage());
                    status.setText("✓ " + t("执行完成", "Done"));
                    status.setTextColor(0xff1d7a4f);
                    toast(t("执行完成", "Done"));
                } else {
                    logLine("✗ " + result.getMessage());
                    status.setText("✗ " + t("执行失败", "Failed") + ": " + result.getMessage());
                    status.setTextColor(0xffa33b3b);
                }
                notifyDone(result.getSuccess(), result.getMessage() + " · " + project);
                refreshProjectFiles();
            });
        });
    }

    /** 工程内选中项 → 绝对路径列表（外加手动输入路径）
     *  合成 repack：分解输出目录优先（/data/PDNA/工程名/x），回退工程目录 */
    private List<String> targetPaths() {
        List<String> paths = new ArrayList<>();
        for (String name : picked) {
            if (droNames.contains(name)) paths.add(DnaTools.TMP_ROOT + "/" + project + "/" + name);
            else paths.add(projectPath() + "/" + name);
        }
        if (manualInput != null) {
            manualPaths = manualInput.getText().toString().trim();
        }
        for (String p : manualPaths.split("\\s+")) {
            if (!p.isEmpty()) paths.add(p);
        }
        return paths;
    }

    /**
     * 原版 dna.xml 参数风格：工程内文件传基名（dna 从 $DNA_PRO 解析），
     * 分解输出目录（$DNA_DRO）下的文件/目录同样只传基名（dna 内部自拼 $DNA_DRO/基名；
     * v3.28.10 修复：传绝对路径导致 dna repack 目录解析错乱 → 256块/16inodes 空镜像 → e2fsdroid 权限表对不上 → 打包失败）。
     * 工程外的手动输入路径原样传。
     */
    private String dnaArg(String path) {
        String pro = projectPath() + "/";
        if (path.startsWith(pro)) return new File(path).getName();
        if (project != null && path.startsWith(DnaTools.TMP_ROOT + "/" + project + "/")) return new File(path).getName();
        return path;
    }

    /** 执行按钮文案：分解类 → 开始分解，repack → 开始打包，super 合成 → 开始打包 SUPER，转换类 → 开始转换 */
    private String actionLabel() {
        switch (mode) {
            case MODE_EXTRACT:
            case MODE_BIN:
            case MODE_SUPER_UNPACK:
                return t("开始分解", "Extract");
            case MODE_REPACK:
                return t("开始打包", "Pack");
            case MODE_SUPER_PACK:
                return t("开始打包 SUPER", "Pack SUPER");
            default:
                return t("开始转换", "Convert");
        }
    }

    private String buildCommand() {
        List<String> targets = targetPaths();
        StringBuilder cmd = new StringBuilder();
        switch (mode) {
            case MODE_EXTRACT: {
                if (targets.isEmpty()) { toast(t("请选择文件", "Pick files first")); return null; }
                // 原版 dna.xml：dna extract $IMG --delete $silence（工程内文件名 + 删除开关带值）
                cmd.append("dna extract");
                for (String p : targets) cmd.append(" ").append(DnaTools.quote(dnaArg(p)));
                cmd.append(" --delete ").append(extractDeleteSource != null && extractDeleteSource.isChecked() ? "1" : "0");
                return cmd.toString();
            }
            case MODE_BIN: {
                if (targets.isEmpty()) { toast(t("请选择 payload.bin", "Pick payload.bin first")); return null; }
                String bin = targets.get(0);
                StringBuilder parts = new StringBuilder();
                for (String name : partitionNames) {
                    if (checkedPartitions.contains(name)) {
                        if (parts.length() > 0) parts.append(",");
                        parts.append(name);
                    }
                }
                cmd.append("payload_extract -i ").append(DnaTools.quote(bin));
                if (parts.length() > 0) cmd.append(" --extract=").append(DnaTools.quote(parts.toString()));
                cmd.append(" -o ").append(DnaTools.quote(projectPath() + "/")).append(" -s");
                if (extractDeleteSource != null && extractDeleteSource.isChecked())
                    cmd.append(" && rm -f ").append(DnaTools.quote(bin));
                return cmd.toString();
            }
            case MODE_SUPER_UNPACK: {
                if (targets.isEmpty()) { toast(t("请选择 super.img", "Pick super.img first")); return null; }
                // 原版 dna.xml：dna lpunpack --partition "$img" --delete $silence --auto $auto $DNA_PRO/super.img
                cmd.append("dna lpunpack");
                StringBuilder parts = new StringBuilder();
                for (String name : partitionNames) {
                    if (checkedPartitions.contains(name)) {
                        if (parts.length() > 0) parts.append(",");
                        parts.append(name);
                    }
                }
                if (parts.length() > 0) cmd.append(" --partition ").append(DnaTools.quote(parts.toString()));
                cmd.append(" --delete ").append(superDeleteSource != null && superDeleteSource.isChecked() ? "1" : "0");
                cmd.append(" --auto ").append(superAutoExtract != null && superAutoExtract.isChecked() ? "1" : "0");
                cmd.append(" ").append(DnaTools.quote(targets.get(0)));
                return cmd.toString();
            }
            case MODE_REPACK: {
                if (targets.isEmpty()) { toast(t("请选择目录", "Pick directories first")); return null; }
                // 原版 dna.xml：dna repack $IMG_DIR --read $Read --auto $Pack --format $img_type
                //   --type $repack_from --br $brze --erofs $erofsze --mode $type --compress $test
                //   --delavb $test1 --convert $tool（目录名为 $DNA_DRO 下基名，开关全部带值）
                cmd.append("dna repack");
                for (String p : targets) cmd.append(" ").append(DnaTools.quote(dnaArg(p)));
                cmd.append(" --read ").append(segValue(repackReadonly, "1", "0"));
                cmd.append(" --auto ").append(segValue(repackAutoSize, "1", "0"));
                cmd.append(" --format ").append(segValue(repackFormat, "0", "1"));
                cmd.append(" --type ").append(segValue(repackRepackType, "0", "1", "2"));
                cmd.append(" --br ").append(seekValue(repackBrLevel, 1));
                cmd.append(" --erofs ").append(seekValue(repackErofsLevel, 0));
                cmd.append(" --mode ").append(segValue(repackErofsMode, "lz4hc", "lz4", "lzma"));
                cmd.append(" --compress ").append(repackCompress != null && repackCompress.isChecked() ? "1" : "0");
                cmd.append(" --delavb ").append(repackDelavb != null && repackDelavb.isChecked() ? "1" : "0");
                cmd.append(" --convert ").append(segValue(repackFsType, "0", "1", "2"));
                return cmd.toString();
            }
            case MODE_SUPER_PACK: {
                if (targets.isEmpty()) { toast(t("请选择镜像", "Pick images first")); return null; }
                String size = superPackSize.getText().toString().trim();
                if (size.isEmpty()) size = "8.5";
                // 原版 dna.xml：dna lpmake --type $type --format $from --super_size $size --super_group $super_group $IMG_NAME
                cmd.append("dna lpmake");
                cmd.append(" --type ").append(segValue(superPackType, "A", "AB", "VAB"));
                cmd.append(" --format ").append(segValue(superPackFormat, "0", "1"));
                cmd.append(" --super_size ").append(size);
                cmd.append(" --super_group ").append(DnaTools.quote(superPackGroup.getText().toString().trim()));
                for (String p : targets) cmd.append(" ").append(DnaTools.quote(dnaArg(p)));
                return cmd.toString();
            }
            case MODE_CONVERT: {
                if (targets.isEmpty()) { toast(t("请选择镜像", "Pick images first")); return null; }
                // 原版 dna.xml：dna convert $IMG --delete $silence --type $from --br $brze
                cmd.append("dna convert");
                for (String p : targets) cmd.append(" ").append(DnaTools.quote(dnaArg(p)));
                cmd.append(" --delete ").append(convertDeleteSource != null && convertDeleteSource.isChecked() ? "1" : "0");
                cmd.append(" --type ").append(segValue(convertType, "0", "1"));
                cmd.append(" --br ").append(seekValue(convertBrLevel, 1));
                return cmd.toString();
            }
            case MODE_SPARSE: {
                if (targets.isEmpty()) { toast(t("请选择镜像", "Pick images first")); return null; }
                String del = sparseDeleteSource != null && sparseDeleteSource.isChecked() ? "yes" : "no";
                cmd.append("mkdir -p ").append(DnaTools.quote(projectPath() + "/out")).append("; for __f in");
                for (String p : targets) cmd.append(" ").append(DnaTools.quote(p));
                cmd.append("; do __n=$(basename \"$__f\"); __t=$(dna gettype \"$__f\"); ")
                        .append("if [ \"$__t\" = \"ext\" ] || [ \"$__t\" = \"erofs\" ]; then img2simg \"$__f\" ")
                        .append(DnaTools.quote(projectPath() + "/out")).append("/\"$__n\" && echo \"> img2simg: $__n\"; ")
                        .append("elif [ \"$__t\" = \"sparse\" ]; then simg2img \"$__f\" ")
                        .append(DnaTools.quote(projectPath() + "/out")).append("/\"$__n\" && echo \"> simg2img: $__n\"; ")
                        .append("else echo \"> 不支持转换: $__n ($__t)\"; fi; ")
                        .append("if [ \"").append(del).append("\" = \"yes\" ]; then rm -f \"$__f\"; fi; done");
                return cmd.toString();
            }
            case MODE_ZST: {
                if (targets.isEmpty()) { toast(t("请选择文件", "Pick files first")); return null; }
                // 原版 zstd_img.sh：解压 zstd -d -k -f，压缩 zstd -$level -T4 -f，输出 $DNA_PRO/out
                String del = zstDeleteSource != null && zstDeleteSource.isChecked() ? "yes" : "no";
                String level = String.valueOf(zstLevel != null ? seekValue(zstLevel, 0) : 3);
                cmd.append("mkdir -p ").append(DnaTools.quote(projectPath() + "/out")).append("; for __f in");
                for (String p : targets) cmd.append(" ").append(DnaTools.quote(p));
                cmd.append("; do __n=$(basename \"$__f\"); case \"$__n\" in ")
                        .append("*.zst|*.zstd) __o=${__n%.*}; zstd -d -f -T4 \"$__f\" -o ")
                        .append(DnaTools.quote(projectPath() + "/out")).append("/\"$__o\" && echo \"> 解压: $__n\";; ")
                        .append("*) zstd -").append(level).append(" -T4 -f \"$__f\" -o ")
                        .append(DnaTools.quote(projectPath() + "/out")).append("/\"$__n.zst\" && echo \"> 压缩: $__n.zst\";; esac; ")
                        .append("if [ \"").append(del).append("\" = \"yes\" ]; then rm -f \"$__f\"; fi; done");
                return cmd.toString();
            }
            case MODE_CHUNK: {
                if (targets.isEmpty()) { toast(t("请选择分段镜像", "Pick split images first")); return null; }
                cmd.append("mkdir -p ").append(DnaTools.quote(projectPath() + "/out")).append("; cd ")
                        .append(DnaTools.quote(projectPath())).append("; __rc=0; for __p in");
                for (String p : targets) cmd.append(" ").append(DnaTools.quote(new File(p).getName()));
                cmd.append("; do __files=$(ls | grep -E \"^${__p}\\.[0-9]+$\" | sort -V | tr '\\n' ' '); ")
                        .append("if [ -z \"$__files\" ]; then echo \"> 未找到分段: $__p\"; __rc=1; continue; fi; ")
                        .append("echo \"> 合并: $__p (${__files})\"; simg2img ${__files} out/$__p || __rc=1; done; cd /; [ \"$__rc\" = \"0\" ]");
                return cmd.toString();
            }
        }
        return null;
    }

    // ============ 工程选择对话框（玻璃风） ============

    private void showProjectPicker() {
        List<String> projects = DnaTools.listProjects();
        Dialog dialog = new Dialog(this);
        dialog.setCancelable(true);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable bg = new GradientDrawable();
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
        ScrollView listScroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        if (projects.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(t("暂无工程，请先在 DNA 页新建工程", "No projects yet. Create one on DNA page"));
            empty.setTextSize(13);
            empty.setTextColor(0xff5a6b82);
            empty.setPadding(0, dp(8), 0, dp(8));
            list.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        }
        for (String name : projects) {
            boolean current = name.equals(project);
            TextView row = new TextView(this);
            row.setText((current ? "● " : "○ ") + name);
            row.setTextSize(14);
            row.setTextColor(current ? 0xff14524b : 0xff17334f);
            // v3.28.7：长工程名单行省略（修复弹窗文字溢出）
            row.setSingleLine(true);
            row.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(13), dp(12), dp(13));
            GradientDrawable rowBg = new GradientDrawable();
            rowBg.setColor(current ? 0x332f9c8f : 0x22FFFFFF);
            rowBg.setCornerRadius(dp(14));
            rowBg.setStroke(Math.max(1, dp(1)), current ? 0x662f9c8f : 0x33FFFFFF);
            row.setBackground(rowBg);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
            rowLp.bottomMargin = dp(6);
            list.addView(row, rowLp);
            row.setOnClickListener(v -> {
                Haptics.perform(v);
                project = name;
                DnaTools.setCurrentProject(this, name);
                picked.clear();
                partitionNames.clear();
                checkedPartitions.clear();
                if (partitionsList != null) {
                    partRows.clear();
                    partitionsList.removeAllViews();
                    showPartitionsHint(t("选择文件后自动列出分区（不勾选 = 全部）", "Auto-listed after picking (none = all)"));
                }
                refreshProjectFiles();
                dialog.dismiss();
                logLine(t("已切换工程", "Project switched") + ": " + name);
            });
        }
        panel.addView(listScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        Button close = new Button(this);
        close.setText(t("关闭", "Close"));
        close.setAllCaps(false);
        close.setTextColor(0xff172b4d);
        // v3.28.7：显式居中 + 零内边距
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, 0, 0, 0);
        close.setBackgroundResource(R.drawable.liquid_glass_panel);
        close.setStateListAnimator(null);
        close.setOnClickListener(v -> dialog.dismiss());
        panel.addView(close, new LinearLayout.LayoutParams(-1, dp(44)));
        dialog.setContentView(panel, new LinearLayout.LayoutParams(-1, dp(460)));
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        dialog.show();
        // v3.28.4：弹窗加宽到 94% 屏宽（修复"弹窗太窄"）
        dialog.getWindow().setLayout(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.94f), dp(460));
    }

    // ============ 通知栏同步 ============

    private static final String NOTE_CHANNEL = "dna_tools_progress";
    private static final int NOTE_ID = 3401;
    private long lastNoteAt;

    private void ensureNoteChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    NOTE_CHANNEL, "DNA 工具箱进度", android.app.NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("显示 DNA 分解 / 打包任务实时状态");
            channel.setShowBadge(false);
            getSystemService(android.app.NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private void notify(String text, boolean ongoing, boolean indeterminate) {
        try {
            android.app.Notification.Builder builder = new android.app.Notification.Builder(this, NOTE_CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(modeTitle())
                    .setContentText(text)
                    .setOngoing(ongoing)
                    .setOnlyAlertOnce(true)
                    .setProgress(100, 0, indeterminate);
            Intent intent = new Intent(this, DnaActivity.class);
            intent.putExtra(EXTRA_MODE, mode);
            intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            builder.setContentIntent(android.app.PendingIntent.getActivity(
                    this, NOTE_ID, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE));
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTE_ID, builder.build());
        } catch (Exception ignored) {
        }
    }

    private void notifyProgress(String line) {
        long now = System.currentTimeMillis();
        if (now - lastNoteAt < 800) return;
        lastNoteAt = now;
        String text = line.length() > 90 ? line.substring(0, 90) + "…" : line;
        notify(text, true, true);
    }

    private void notifyDone(boolean success, String message) {
        notify((success ? "✓ " : "✗ ") + message, false, false);
    }

    private void cancelNote() {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTE_ID);
        } catch (Exception ignored) {
        }
    }

    // ============ 日志与工具 ============

    private void logLine(String line) {
        mainHandler.post(() -> {
            if (logDisplay == null) return;
            logDisplay.append(line + "\n");
            logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
        if (running.get()) notifyProgress(line);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private String segValue(SegmentGroup group, String... values) {
        if (group == null) return values[0];
        int i = group.selected();
        return i >= 0 && i < values.length ? values[i] : values[0];
    }

    private int seekValue(SeekBar seek, int min) {
        return seek.getProgress() + min;
    }

    private android.graphics.drawable.Drawable createGradientBackground() {
        return new GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xffccdbe8, 0xff7e9cb4});
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FILE || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        String path = resolvePath(uri);
        if (path != null && path.startsWith("/")) {
            manualPaths = (manualPaths.isEmpty() ? "" : manualPaths + " ") + path;
            if (manualInput != null) manualInput.setText(manualPaths);
            if (mode == MODE_BIN || mode == MODE_SUPER_UNPACK) {
                String name = new File(path).getName();
                picked.clear();
                picked.add(name);
                refreshFileRows();
                afterPick(name);
            }
        } else {
            toast(t("无法解析该文件路径，请手动输入", "Cannot resolve path, enter manually"));
        }
    }

    private String resolvePath(Uri uri) {
        if ("file".equals(uri.getScheme())) return uri.getPath();
        if (!"content".equals(uri.getScheme())) return null;
        if ("com.android.externalstorage.documents".equals(uri.getAuthority())) {
            String docId = android.provider.DocumentsContract.getDocumentId(uri);
            String[] split = docId.split(":");
            if (split.length >= 2 && "primary".equals(split[0])) {
                return Environment.getExternalStorageDirectory() + "/" + split[1];
            }
        }
        try {
            android.database.Cursor cursor = getContentResolver().query(uri,
                    new String[]{android.provider.MediaStore.MediaColumns.DATA}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA);
                if (index >= 0) {
                    String path = cursor.getString(index);
                    cursor.close();
                    if (path != null && new File(path).exists()) return path;
                }
                cursor.close();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        Haptics.onTouch(getWindow().getDecorView(), event);
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        cancelNote();
    }
}
