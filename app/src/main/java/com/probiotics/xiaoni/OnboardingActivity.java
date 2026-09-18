package com.probiotics.xiaoni;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;
import java.util.Random;

/**
 * 首次进入 APP 的引导页（共 5 页，原汁原味的液态玻璃）：
 *  1. 用户协议与隐私政策 + 人机验证（图二）
 *  2. ROM 查询与高速下载（图三）
 *  3. ROM 镜像提取（图四）
 *  4. DSU 与更多能力（图五）
 *  5. 一切已就绪 → 开始使用（图六）
 * 完成后弹出图一 MIUI 白色底部授权弹窗（通知 / 音频文件 / 照片和视频）。
 */
public final class OnboardingActivity extends Activity {

    private static final String[] CLAUSES_ZH = {
            "本应用为 DSU 动态系统更新 / ROM 下载与提取工具，仅供个人学习与研究使用，请勿用于商业用途。",
            "ROM 与设备清单数据来自公开社区数据源（HyperOS.fans / HyperData 等），下载链接为各厂商官方更新节点直链，本应用不存储任何 ROM 文件。",
            "本应用不收集、不上传任何个人信息；设备清单获取与版本查询直接连接公开数据源，其余操作均在本地完成。",
            "下载的 ROM 包保存在公共下载目录 Download/DsuManager，aria2c 多线程下载支持断点续传。",
            "ROM 刷写、DSU 安装、镜像提取等操作存在一定风险，操作前请自行做好数据备份；因使用本应用造成的任何数据丢失或设备损坏，由使用者自行承担。",
            "继续使用即表示你已阅读并同意以上条款。",
    };
    private static final String[] CLAUSES_EN = {
            "This app is a DSU (Dynamic System Updates) / ROM download & extraction tool, for personal study and research only.",
            "ROM and device catalog data come from public community sources (HyperOS.fans / HyperData etc.). Download links are direct links to vendor official update nodes. No ROM files are hosted by this app.",
            "This app collects and uploads no personal information. Device catalog fetching and version queries connect to public data sources directly; everything else runs locally.",
            "Downloaded ROM packages are saved to the public download folder Download/DsuManager. aria2c multi-threaded download supports resume.",
            "ROM flashing, DSU installation and image extraction are risky. Back up your data before proceeding. Any data loss or device damage caused by using this app is at your own risk.",
            "By continuing you confirm that you have read and agreed to the terms above.",
    };

    // 功能页顺序（图三 → 图四 → 图五）：[0]=卡片标题，[1..n]="emoji|标题|描述"
    private static final String[][] PAGES_ZH = {
            {"🚀 ROM 查询与高速下载",
                    "📱|全系机型覆盖|Xiaomi / Redmi / POCO / OPPO / vivo 全系 240+ 机型，按系列智能分类，最新机型排在最前",
                    "🔍|智能检索|机型名、设备代号双向搜索（如 nuwa、nezha），宫格 / 列表两种视图随心切换",
                    "⚡|四节点并发加速|aria2c 16 线程 + 8MB 分片，CDN.ORG / BigOTA / HugeOTA / 阿里云四大节点同时下载，突破单节点限速",
                    "🔄|断点续传|暂停后继续不丢进度，支持后台下载，应用重启后自动恢复",
                    "🔔|通知栏掌控|进度、速度、剩余时间实时同步，暂停 / 继续 / 取消一键直达"},
            {"📦 ROM 镜像提取",
                    "🌐|在线直链提取|粘贴 ROM 下载链接即可远程解析 payload.bin，无需先下载完整 ROM 包，节省时间与流量",
                    "📁|本地包提取|选择手机存储中已有的 ROM 包（.zip），快速解析出 payload.bin，离线也能用",
                    "🧩|分区级镜像|system / vendor / boot / product 等分区独立提取，按需导出 .img 镜像",
                    "🎯|提取即用|产出镜像可直接用于 DSU 动态系统更新或 GSI 刷机研究",
                    "🛡|实时进度与校验|提取进度实时展示，大文件后台稳定运行，输出镜像完整可用"},
            {"🧩 DSU 与更多能力",
                    "🔄|DSU 动态系统更新|无需解锁、不动原系统，临时启动新系统镜像，重启即回到原系统",
                    "🐧|内置 Linux 终端|完整 rootfs 环境，手机上的随身开发终端",
                    "🎨|液态玻璃界面|全局毛玻璃质感与流畅转场动画，四个功能入口各具特色的进出动画",
                    "📊|下载全掌控|自定义文件名、下载节点选择、速度与剩余时间实时展示",
                    "🧭|一键极简操作|全程图形化界面，无需命令行，关键操作二次确认，安心使用"},
    };
    private static final String[][] PAGES_EN = {
            {"🚀 ROM Query & Fast Download",
                    "📱|Full Device Coverage|Xiaomi / Redmi / POCO / OPPO / vivo, 240+ devices, grouped by series, newest first",
                    "🔍|Smart Search|Two-way search by name or codename (nuwa, nezha), grid / list views",
                    "⚡|4-Node Concurrent Boost|aria2c 16 threads + 8MB pieces; CDN.ORG / BigOTA / HugeOTA / Aliyun nodes together break single-node limits",
                    "🔄|Resume Anytime|Pause and continue without losing progress; background download with auto-recovery after restart",
                    "🔔|Notification Control|Progress, speed and ETA in the status bar; pause / resume / cancel at hand"},
            {"📦 ROM Image Extraction",
                    "🌐|Online Direct-Link Extraction|Paste a ROM link to remotely parse payload.bin without downloading the full package — saves time and data",
                    "📁|Local Package Extraction|Pick a ROM .zip already on your storage and parse payload.bin quickly, works offline",
                    "🧩|Partition-Level Images|Extract system / vendor / boot / product partitions independently to .img files",
                    "🎯|Ready To Use|Output images work directly with DSU or GSI flashing research",
                    "🛡|Live Progress & Verification|Extraction progress in real time, stable background runs, verified output images"},
            {"🧩 DSU & More",
                    "🔄|DSU Dynamic System Updates|Boot a new system image temporarily without unlocking; a reboot returns to the original system",
                    "🐧|Built-in Linux Terminal|Complete rootfs environment, a pocket dev terminal on your phone",
                    "🎨|Liquid Glass UI|Global frosted-glass look with smooth transitions; four entrances with unique animations",
                    "📊|Full Download Control|Custom file names, node selection, live speed and remaining time",
                    "🧭|One-Tap Simplicity|Fully graphical interface, no command line, critical actions double-confirmed"},
    };

    // 配色（与 APP 全局液态玻璃一致）
    private static final int INK = 0xff172b4d;        // 主文字：深海军蓝
    private static final int INK_SOFT = 0xff5e6c83;   // 次级文字
    private static final int ACCENT = 0xff2f6fd8;     // 强调蓝

    private static final int PAGE_COUNT = 5;

    private int page;
    private boolean agreed;
    private boolean english;
    private FrameLayout cardHost;
    private LinearLayout indicator;
    private LinearLayout buttonsRow;
    private View[] dots = new View[PAGE_COUNT];
    private Button negativeButton;
    private Button positiveButton;
    private TextView headingTitle;
    private TextView headingSubtitle;
    private TextView verifyLabel;
    private EditText verifyInput;
    private ScrollView agreementScroll;
    private int verifyA, verifyB, verifyC;
    private GestureDetector gestureDetector;
    private View fadeContentRoot;   // 内容根视图（就绪页→首页淡入淡出用，背景渐变常驻防黑屏）

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 已完成引导 + 已同意协议 → 直接进入主界面（首页仍带淡入），不再重复展示引导页
        android.content.SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        if (prefs.getBoolean("onboarding_done", false) && prefs.getBoolean("agreement_accepted", false)) {
            Intent direct = new Intent(this, MainActivity.class);
            direct.putExtra("crossfade_entry", true);
            startActivity(direct);
            if (Build.VERSION.SDK_INT >= 34) {
                overrideActivityTransition(OVERRIDE_TRANSITION_OPEN,
                        R.anim.fade_in, R.anim.fade_out);
            } else {
                overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
            }
            finish();
            return;
        }
        int languageMode = prefs.getInt("language_mode", 0);
        english = languageMode == 2 || (languageMode == 0
                && !Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT).startsWith("zh"));
        agreed = prefs.getBoolean("agreement_accepted", false);
        generateVerification();
        getWindow().setStatusBarColor(0x00000000);
        getWindow().setNavigationBarColor(0x33000000);
        // 人机验证键盘弹出时，问题框 / 输入框随键盘同步顶起（adjustResize）
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        buildUi();
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dx) > 90 && Math.abs(dx) > Math.abs(vy) * 0.7f) {
                    int before = page;
                    if (dx < 0) tryNextPage();
                    else tryPrevPage();
                    // 翻页成功 → 全局振动反馈
                    if (page != before) Haptics.perform(getWindow().getDecorView());
                    return true;
                }
                return false;
            }
        });
        showPage(0, false);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        gestureDetector.onTouchEvent(event);
        Haptics.onTouch(getWindow().getDecorView(), event);
        return super.dispatchTouchEvent(event);
    }

    // ---------- UI 构建 ----------

    private void buildUi() {
        FrameLayout rootFrame = new FrameLayout(this);

        // 与 ROM 查询完全同款的背景：天蓝光晕 + 蓝绿纵向渐变（液态玻璃的底色）
        rootFrame.setBackground(romBackdrop());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        fadeContentRoot = root;
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars;
            android.graphics.Insets ime = android.graphics.Insets.NONE;
            if (Build.VERSION.SDK_INT >= 30) {
                bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                // 键盘高度计入底部内边距 → 问题框 / 输入框 / 按钮随键盘同步顶起
                ime = insets.getInsets(android.view.WindowInsets.Type.ime());
            } else {
                bars = android.graphics.Insets.of(0, insets.getSystemWindowInsetTop(), 0,
                        insets.getSystemWindowInsetBottom());
            }
            view.setPadding(0, bars.top, 0, Math.max(Math.max(bars.bottom, dp(8)), ime.bottom));
            return insets;
        });

        // 标题区：大标题 + 灰色副标题（图三：标题在卡片外上方，无 Logo）
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(-1, -2);
        headerLp.topMargin = dp(34);
        root.addView(header, headerLp);

        headingTitle = new TextView(this);
        headingTitle.setText("Dsu 管理器");
        headingTitle.setTextSize(26);
        headingTitle.setTypeface(Typeface.DEFAULT_BOLD);
        headingTitle.setTextColor(INK);
        headingTitle.setGravity(Gravity.CENTER);
        header.addView(headingTitle, new LinearLayout.LayoutParams(-1, -2));

        headingSubtitle = new TextView(this);
        headingSubtitle.setText("DSU 动态系统更新 · ROM 下载与提取工具");
        headingSubtitle.setTextSize(12.5f);
        headingSubtitle.setTextColor(INK_SOFT);
        headingSubtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = dp(4);
        header.addView(headingSubtitle, subLp);

        // 卡片容器
        cardHost = new FrameLayout(this);
        root.addView(cardHost, new LinearLayout.LayoutParams(-1, 0, 1f));

        // 页面指示器：当前页蓝色胶囊，其余半透明圆点（卡片区与按钮之间）
        indicator = new LinearLayout(this);
        indicator.setOrientation(LinearLayout.HORIZONTAL);
        indicator.setGravity(Gravity.CENTER);
        for (int i = 0; i < PAGE_COUNT; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
            dotLp.rightMargin = dp(7);
            dot.setBackground(rounded(0x3d172b4d, dp(4)));
            dots[i] = dot;
            indicator.addView(dot, dotLp);
        }
        LinearLayout.LayoutParams indicatorLp = new LinearLayout.LayoutParams(-1, dp(20));
        indicatorLp.topMargin = dp(12);
        root.addView(indicator, indicatorLp);

        // 底部按钮行（玻璃按钮）：左右留边，不再贴屏幕边缘
        buttonsRow = new LinearLayout(this);
        buttonsRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonsRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(-1, dp(54));
        buttonsLp.topMargin = dp(10);
        buttonsLp.bottomMargin = dp(6);
        buttonsLp.leftMargin = dp(18);
        buttonsLp.rightMargin = dp(18);
        root.addView(buttonsRow, buttonsLp);

        negativeButton = glassButton(false);
        negativeButton.setOnClickListener(v -> {
            Haptics.perform(v);
            pressFx(v);
            onNegativeButton();
        });
        positiveButton = glassButton(true);
        positiveButton.setOnClickListener(v -> {
            Haptics.perform(v);
            pressFx(v);
            onPositiveButton();
        });
        buttonsRow.addView(negativeButton, new LinearLayout.LayoutParams(0, dp(52), 1f));
        LinearLayout.LayoutParams posLp = new LinearLayout.LayoutParams(0, dp(52), 1.4f);
        posLp.leftMargin = dp(12);
        buttonsRow.addView(positiveButton, posLp);

        rootFrame.addView(root, new FrameLayout.LayoutParams(-1, -1));
        setContentView(rootFrame);
    }

    /** ROM 查询同款背景（createXiaomiGradientBackground）：多层径向光晕 + 蓝绿纵向渐变 */
    private android.graphics.drawable.LayerDrawable romBackdrop() {
        return new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                radial(0xffb8d4e8, 0.5f, 0.0f),   // 顶部中心天蓝光晕
                radial(0xff98c4d9, 0.25f, 0.25f), // 左上青蓝光晕
                radial(0xffa8ccd9, 0.75f, 0.25f), // 右上青蓝光晕
                linear(0xffc8dce8, 0xff6a8fa8),   // 主背景：上浅蓝下深蓝绿
                radial(0xff5a7d94, 0.5f, 0.8f),   // 底部中心深蓝绿光晕
        });
    }

    private android.graphics.drawable.GradientDrawable linear(int startColor, int endColor) {
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{startColor, endColor});
        return gd;
    }

    private android.graphics.drawable.GradientDrawable radial(int centerColor, float cx, float cy) {
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable();
        gd.setGradientType(android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT);
        gd.setColors(new int[]{centerColor, 0x00000000});
        gd.setGradientCenter(cx, cy);
        gd.setGradientRadius(800);   // 与 RomActivity 完全一致
        return gd;
    }

    // ---------- 页面内容 ----------

    private void showPage(int target, boolean forward) {
        page = target;
        // 第 5 页（图六）：隐藏顶部标题区，内容区自带居中主/副标题
        boolean ready = target == PAGE_COUNT - 1;
        headingTitle.setVisibility(ready ? View.GONE : View.VISIBLE);
        headingSubtitle.setVisibility(ready ? View.GONE : View.VISIBLE);
        View content = buildPageContent(target);
        if (forward || target > 0 && cardHost.getChildCount() > 0) {
            animateTransition(content, forward);
        } else {
            cardHost.removeAllViews();
            cardHost.addView(content, new FrameLayout.LayoutParams(-1, -1));
        }
        updateIndicator();
        updateButtons();
    }

    private View buildPageContent(int target) {
        // 第 5 页（一切已就绪）：无卡片极简居中布局（图六）
        if (target == PAGE_COUNT - 1) {
            LinearLayout center = new LinearLayout(this);
            center.setOrientation(LinearLayout.VERTICAL);
            center.setGravity(Gravity.CENTER);
            buildReadyPage(center);
            return center;
        }
        ScrollView scroll = new ScrollView(this);
        // 全部页面撑满：协议页内容长可滚，功能页（2/3/4）卡片拉长占满内容区（图三 ~76% 屏高）
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        if (target == 0) agreementScroll = scroll;   // 人机验证页：键盘弹出时滚到底部
        // 与 ROM 查询完全同款的液态玻璃卡片：LiquidGlassPanel + liquid_glass_panel 背景叠加
        LiquidGlassPanel card = new LiquidGlassPanel(this, 28f);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.liquid_glass_panel);
        int pad = dp(20);
        card.setPadding(pad, pad, pad, pad + dp(4));
        // 卡片左右留边（图三：卡片占屏宽 85-90%，不再贴屏幕边缘）
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(-1, -2);
        cardLp.leftMargin = dp(18);
        cardLp.rightMargin = dp(18);
        cardLp.topMargin = dp(10);
        cardLp.bottomMargin = dp(2);
        scroll.addView(card, cardLp);

        if (target == 0) {
            buildAgreementCard(card);
        } else {
            buildFeatureCard(card, target - 1);
        }
        return scroll;
    }

    /** 第 1 页（图二）：用户协议与隐私政策 + 人机验证 */
    private void buildAgreementCard(LinearLayout card) {
        TextView heading = cardTitle(english ? "User Agreement & Privacy Policy" : "用户协议与隐私政策");
        card.addView(heading, headLp());

        String[] clauses = english ? CLAUSES_EN : CLAUSES_ZH;
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < clauses.length; i++) {
            TextView item = new TextView(this);
            item.setText((i + 1) + ". " + clauses[i]);
            item.setTextSize(12.5f);
            item.setTextColor(0xff3c4757);
            item.setLineSpacing(dp(3), 1f);
            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(-1, -2);
            itemLp.topMargin = i == 0 ? dp(10) : dp(7);
            list.addView(item, itemLp);
        }
        card.addView(list, new LinearLayout.LayoutParams(-1, -2));

        // 分隔线
        View divider = new View(this);
        divider.setBackground(rounded(0x1f2f6fd8, 1));
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
        dividerLp.topMargin = dp(16);
        card.addView(divider, dividerLp);

        // 人机验证（图一：「人机验证：」与算式同为深灰，算式略粗）
        LinearLayout verifyRow = new LinearLayout(this);
        verifyRow.setOrientation(LinearLayout.HORIZONTAL);
        verifyRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView verifyPrefix = new TextView(this);
        verifyPrefix.setText(english ? "Human verification: " : "人机验证：");
        verifyPrefix.setTextSize(14);
        verifyPrefix.setTextColor(INK_SOFT);
        verifyRow.addView(verifyPrefix, new LinearLayout.LayoutParams(-2, -2));
        verifyLabel = new TextView(this);
        verifyLabel.setText(String.format(Locale.ROOT, "%d - %d - %d = ?", verifyA, verifyB, verifyC));
        verifyLabel.setTextSize(14.5f);
        verifyLabel.setTextColor(0xff3c4757);
        verifyLabel.setTypeface(Typeface.DEFAULT_BOLD);
        verifyRow.addView(verifyLabel, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams vrLp = new LinearLayout.LayoutParams(-1, -2);
        vrLp.topMargin = dp(16);
        card.addView(verifyRow, vrLp);

        verifyInput = new EditText(this);
        verifyInput.setHint(english ? "Enter the result" : "输入计算结果");
        verifyInput.setTextSize(15);
        verifyInput.setTextColor(INK);
        verifyInput.setHintTextColor(0xff9aa5b3);
        verifyInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        verifyInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        verifyInput.setBackground(inputBackground());
        verifyInput.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(-1, dp(46));
        inputLp.topMargin = dp(8);
        card.addView(verifyInput, inputLp);
        verifyInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                updateAgreeButton();
            }
        });
        verifyInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE && agreeButtonReady()) {
                onPositiveButton();
            }
            return false;
        });
        // 键盘弹出 → 滚动到人机验证区域，确保算式与输入框可见
        verifyInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && agreementScroll != null) {
                agreementScroll.postDelayed(() -> agreementScroll.fullScroll(View.FOCUS_DOWN), 150);
            }
        });
    }

    /** 第 2-4 页（图三/图四/图五）：功能介绍 */
    private void buildFeatureCard(LinearLayout card, int index) {
        String[][] pages = english ? PAGES_EN : PAGES_ZH;
        String[] data = pages[index];
        TextView heading = cardTitle(data[0]);
        card.addView(heading, headLp());
        for (int i = 1; i < data.length; i++) {
            String[] parts = data[i].split("\\|", 3);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);

            TextView icon = new TextView(this);
            icon.setText(parts[0]);
            icon.setTextSize(21);
            FrameLayout iconBox = new FrameLayout(this);
            iconBox.setBackground(iconTile(i - 1));
            iconBox.addView(icon, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(44), dp(44));

            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(this);
            name.setText(parts[1]);
            name.setTextSize(15);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setTextColor(INK);
            textCol.addView(name, new LinearLayout.LayoutParams(-1, -2));
            TextView desc = new TextView(this);
            desc.setText(parts[2]);
            desc.setTextSize(12);
            desc.setTextColor(INK_SOFT);
            desc.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(-1, -2);
            descLp.topMargin = dp(3);
            textCol.addView(desc, descLp);

            row.addView(iconBox, iconLp);
            LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(-1, -2);
            textLp.leftMargin = dp(13);
            row.addView(textCol, textLp);

            // 条目间距（图三拉长卡片：宽松呼吸感，撑满整卡）
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
            rowLp.topMargin = i == 1 ? dp(18) : dp(22);
            card.addView(row, rowLp);
        }
    }

    /** 第 5 页（图六）：一切已就绪 —— 图标头像 + 主标题 + 副标题 + 状态在上中部居中，「开始使用」在屏幕正中 */
    private void buildReadyPage(LinearLayout center) {
        center.setGravity(Gravity.CENTER_HORIZONTAL);

        // 上部弹性留白：内容组（头像+标题）落在屏幕上部 ~36% 处，对齐图二比例
        center.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 4f));

        // 图标头像（图二效果）：圆形白色描边徽章 + 应用图标，位于 Dsu 管理器标题之前
        FrameLayout avatar = new FrameLayout(this);
        avatar.setPadding(dp(3), dp(3), dp(3), dp(3));
        GradientDrawable avatarBg = new GradientDrawable();
        avatarBg.setShape(GradientDrawable.OVAL);
        avatarBg.setColors(new int[]{0xE6FFFFFF, 0xCCDCEAF8});
        avatarBg.setOrientation(GradientDrawable.Orientation.TL_BR);
        avatarBg.setStroke(Math.max(1, dp(2)), 0xFFFFFFFF);
        avatar.setBackground(avatarBg);
        avatar.setElevation(dp(12));
        android.widget.ImageView avatarIcon = new android.widget.ImageView(this);
        avatarIcon.setImageResource(R.drawable.app_icon);
        avatarIcon.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        avatarIcon.setClipToOutline(true);
        avatarIcon.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        avatar.addView(avatarIcon, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(dp(80), dp(80));
        avatarLp.gravity = Gravity.CENTER_HORIZONTAL;
        center.addView(avatar, avatarLp);

        // 主标题（与其他页标题区一致：Dsu 管理器 在上，副标题在下）
        TextView big = new TextView(this);
        big.setText("Dsu 管理器");
        big.setTextSize(27);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        big.setTextColor(INK);
        big.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bigLp = new LinearLayout.LayoutParams(-1, -2);
        bigLp.topMargin = dp(14);
        center.addView(big, bigLp);

        TextView sub = new TextView(this);
        sub.setText(english ? "DSU Dynamic System Updates · ROM Download & Extraction"
                : "DSU 动态系统更新 · ROM 下载与提取工具");
        sub.setTextSize(12.5f);
        sub.setTextColor(INK_SOFT);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = dp(6);
        center.addView(sub, subLp);

        TextView ready = new TextView(this);
        ready.setText(english ? "All set" : "一切已就绪");
        ready.setTextSize(15.5f);
        ready.setTextColor(INK_SOFT);
        ready.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams readyLp = new LinearLayout.LayoutParams(-1, -2);
        readyLp.topMargin = dp(12);
        center.addView(ready, readyLp);

        // 中部弹性留白：把「开始使用」顶到屏幕正中（图六）
        center.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));

        // 开始使用：蓝色渐变玻璃胶囊 + 白色描边，屏幕正中（图二：按钮中心 ≈ 屏幕 60% 处）
        Button start = new Button(this, null, 0);
        start.setText(english ? "Get Started" : "开始使用");
        start.setAllCaps(false);
        start.setTextSize(16);
        start.setTypeface(Typeface.DEFAULT_BOLD);
        start.setTextColor(0xffffffff);
        start.setStateListAnimator(null);
        // 文字严格居中：清除默认按钮样式的内边距 / 最小尺寸 / 字体基线留白（否则文字偏左偏上）
        start.setGravity(Gravity.CENTER);
        start.setPadding(0, 0, 0, 0);
        start.setMinWidth(0);
        start.setMinHeight(0);
        start.setIncludeFontPadding(false);
        GradientDrawable startBg = new GradientDrawable();
        startBg.setOrientation(GradientDrawable.Orientation.TL_BR);
        startBg.setColors(new int[]{0xFF6C9BF2, 0xFF4472DE, 0xFF3F66CF});
        startBg.setCornerRadius(dp(28));
        startBg.setStroke(Math.max(1, dp(2)), 0xFFFFFFFF);
        start.setBackground(startBg);
        start.setElevation(dp(11));
        start.setOnClickListener(v -> {
            Haptics.perform(v);
            pressFx(v);
            finishOnboarding();
        });
        int startWidth = (int) (getResources().getDisplayMetrics().widthPixels * 0.64f);
        center.addView(start, new LinearLayout.LayoutParams(startWidth, dp(56)));

        // 下部弹性留白：按钮中心位于屏幕 ~60%，其余留白沉底（图二）
        center.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 6.5f));

        // 入场动画：头像 + 文字整组淡入，按钮轻微上浮
        avatar.setAlpha(0f);
        big.setAlpha(0f);
        sub.setAlpha(0f);
        ready.setAlpha(0f);
        start.setAlpha(0f);
        avatar.setScaleX(0.6f);
        avatar.setScaleY(0.6f);
        avatar.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(420)
                .setInterpolator(new DecelerateInterpolator(1.4f))
                .start();
        big.animate().alpha(1f).setDuration(420).start();
        sub.animate().alpha(1f).setDuration(460).start();
        ready.animate().alpha(1f).setDuration(500).start();
        start.setTranslationY(dp(18));
        start.animate().alpha(1f).translationY(0f)
                .setDuration(460)
                .setInterpolator(new DecelerateInterpolator(1.4f))
                .start();
    }

    // ---------- 交互 ----------

    private void onNegativeButton() {
        if (page == 0) {
            new AlertDialog.Builder(this)
                    .setTitle(english ? "Agreement required" : "需要同意协议")
                    .setMessage(english
                            ? "You must accept the User Agreement & Privacy Policy to use this app. You can re-enter and agree later after understanding the risks."
                            : "需要同意《用户协议与隐私政策》才能使用本应用。你可以在了解风险后重新进入并同意。")
                    .setPositiveButton(english ? "Exit" : "退出应用",
                            (d, w) -> finishAffinity())
                    .setNegativeButton(english ? "Review" : "再看看", null)
                    .show();
        } else {
            // 「上一步」：回退一页（第 2 页可经按钮回到协议页，滑动仍然单向禁止）
            showPage(page - 1, false);
        }
    }

    private void onPositiveButton() {
        if (page == 0) {
            if (!agreeButtonReady()) {
                if (verifyInput != null) {
                    verifyInput.setError(english ? "Wrong result" : "计算结果不正确");
                }
                return;
            }
            agreed = true;
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putBoolean("agreement_accepted", true)
                    .putLong("agreement_time", System.currentTimeMillis())
                    .apply();
            // 授权弹窗在第 1 页（协议页）弹出：统一在此完成全部权限授权
            showPermissionSheet();
        } else if (page == PAGE_COUNT - 1) {
            finishOnboarding();
        } else {
            showPage(page + 1, true);
        }
    }

    private void tryNextPage() {
        if (page == 0) {
            // 协议页只能通过「同意并继续」前进
            return;
        }
        if (page < PAGE_COUNT - 1) showPage(page + 1, true);
    }

    private void tryPrevPage() {
        // 协议是单向的：第 2 页起不可回滑到第 1 页（第 3-5 页可自由左右滑动）
        if (page > 1) showPage(page - 1, false);
    }

    private void finishOnboarding() {
        getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putBoolean("onboarding_done", true)
                .putBoolean("agreement_accepted", true)
                .apply();
        // 授权已在第 1 页完成 → 就绪页直接淡入淡出进入 APP 首页
        crossfadeToHome(MainActivity.class);
    }

    // ---------- 图一：MIUI 白色底部授权弹窗 ----------

    private android.app.Dialog permissionSheet;
    private LinearLayout sheetBody;
    private TextView allowNotification;
    private TextView allowAudio;
    private TextView allowMedia;
    private TextView pendingRow;

    private void showPermissionSheet() {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setBackgroundColor(0x59000000);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(24), dp(22), dp(24), dp(10));
        // MIUI 底部弹窗：纯白 + 仅顶部大圆角
        GradientDrawable sheetBg = new GradientDrawable();
        sheetBg.setColor(0xFFFFFFFF);
        float r = dp(28);
        sheetBg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(sheetBg);
        sheet.setElevation(dp(20));
        wrap.addView(sheet, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        // 底部安全区适配
        wrap.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars;
            if (Build.VERSION.SDK_INT >= 30) {
                bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            } else {
                bars = android.graphics.Insets.of(0, 0, 0, insets.getSystemWindowInsetBottom());
            }
            sheet.setPadding(dp(24), dp(22), dp(24), Math.max(bars.bottom, dp(8)));
            return insets;
        });
        sheetBody = sheet;

        TextView heading = new TextView(this);
        heading.setText(english ? "\u201CDsu Manager\u201D needs the following permissions"
                : "\u201CDsu 管理器\u201D需要使用以下权限");
        heading.setTextSize(17);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setTextColor(0xde000000);
        heading.setGravity(Gravity.CENTER);
        sheet.addView(heading, new LinearLayout.LayoutParams(-1, -2));

        allowNotification = permissionRow(sheet,
                english ? "Notifications" : "通知",
                notificationDescription(),
                english ? "Allow" : "允许",
                () -> requestPermissionSet("android.permission.POST_NOTIFICATIONS", 1010, allowNotification));
        allowAudio = permissionRow(sheet,
                english ? "Music & audio" : "音频文件",
                english ? "Read and edit music and audio files" : "读取和编辑音乐、音频文件",
                english ? "Allow" : "允许",
                () -> requestPermissionSet("android.permission.READ_MEDIA_AUDIO", 1011, allowAudio));
        allowMedia = permissionRow(sheet,
                english ? "Photos and videos" : "照片和视频",
                english ? "Read and edit photos and video files" : "读取和编辑照片、视频文件",
                english ? "All photos and videos" : "全部照片和视频",
                () -> requestPermissionSet("android.permission.READ_MEDIA_IMAGES,android.permission.READ_MEDIA_VIDEO",
                        1012, allowMedia));

        // 底部操作（图二）：确定（蓝色粗体）在上、取消（蓝色）在下，垂直排列居中
        Button confirm = new Button(this, null, 0);
        confirm.setText(english ? "OK" : "确定");
        confirm.setAllCaps(false);
        confirm.setTextSize(16.5f);
        confirm.setTypeface(Typeface.DEFAULT_BOLD);
        confirm.setTextColor(ACCENT);
        confirm.setStateListAnimator(null);
        confirm.setBackground(rounded(0x00000000, 0));
        confirm.setIncludeFontPadding(false);
        confirm.setGravity(Gravity.CENTER);
        confirm.setPadding(0, 0, 0, 0);
        confirm.setOnClickListener(v -> {
            Haptics.perform(v);
            dismissSheetThen();
        });
        LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(-1, dp(46));
        confirmLp.topMargin = dp(10);
        sheet.addView(confirm, confirmLp);

        Button cancel = new Button(this, null, 0);
        cancel.setText(english ? "Cancel" : "取消");
        cancel.setAllCaps(false);
        cancel.setTextSize(15.5f);
        cancel.setTextColor(ACCENT);
        cancel.setStateListAnimator(null);
        cancel.setBackground(rounded(0x00000000, 0));
        cancel.setIncludeFontPadding(false);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, 0, 0, 0);
        cancel.setOnClickListener(v -> {
            Haptics.perform(v);
            dismissSheetThen();
        });
        sheet.addView(cancel, new LinearLayout.LayoutParams(-1, dp(44)));

        permissionSheet = new android.app.Dialog(this);
        permissionSheet.setContentView(wrap);
        permissionSheet.getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(0x00000000));
        permissionSheet.getWindow().setLayout(-1, -1);
        permissionSheet.setCancelable(false);
        permissionSheet.show();

        // MIUI 底部滑入动画
        wrap.setAlpha(0f);
        wrap.animate().alpha(1f).setDuration(200).start();
        sheet.post(() -> {
            sheet.setTranslationY(sheet.getHeight() + dp(16));
            sheet.animate().translationY(0f)
                    .setDuration(320)
                    .setInterpolator(new DecelerateInterpolator(1.15f))
                    .start();
        });

        refreshPermissionRow(allowNotification, "android.permission.POST_NOTIFICATIONS");
        refreshPermissionRow(allowAudio, "android.permission.READ_MEDIA_AUDIO");
    }

    /** 通知权限说明（图二参考，一字不差）：正文中「通知管理」为蓝色可点击，直达系统通知设置 */
    private CharSequence notificationDescription() {
        if (english) {
            return "Once allowed, the app can send notification alerts, including lock-screen notifications, "
                    + "sound & vibration, and home-screen badges. To change notification types, go to Notification settings.";
        }
        String full = "允许后，应用可以发送通知提醒，包括锁屏通知、声音振动、桌面角标等形式。"
                + "如需变更通知类型，可以在通知管理中修改。";
        android.text.SpannableString span = new android.text.SpannableString(full);
        int start = full.indexOf("通知管理");
        if (start >= 0) {
            span.setSpan(new android.text.style.ClickableSpan() {
                @Override public void onClick(View widget) {
                    try {
                        startActivity(new android.content.Intent(
                                android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName()));
                    } catch (Exception ignored) { }
                }
                @Override public void updateDrawState(android.text.TextPaint ds) {
                    ds.setColor(ACCENT);
                    ds.setUnderlineText(false);
                }
            }, start, start + "通知管理".length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return span;
    }

    /** MIUI 权限行（图二）：名称加粗在上，灰色描述在下，「允许」蓝色文字在描述下方左侧 */
    private TextView permissionRow(LinearLayout sheet, String title, CharSequence desc,
                                   String actionLabel, Runnable grant) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);

        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(16);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(0xde000000);
        block.addView(name, new LinearLayout.LayoutParams(-1, -2));

        TextView detail = new TextView(this);
        detail.setText(desc);
        detail.setTextSize(13);
        detail.setTextColor(0x8a000000);
        detail.setLineSpacing(dp(2), 1.05f);
        if (desc instanceof android.text.Spanned) {
            detail.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
            detail.setHighlightColor(0x1a2f6fd8);
        }
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(-1, -2);
        detailLp.topMargin = dp(6);
        block.addView(detail, detailLp);

        TextView allow = new TextView(this);
        allow.setText(actionLabel);
        allow.setTextSize(14);
        allow.setTextColor(ACCENT);
        allow.setPadding(0, 0, dp(14), dp(4));   // 扩大点击热区
        allow.setOnClickListener(v -> {
            Haptics.perform(v);
            grant.run();
        });
        LinearLayout.LayoutParams allowLp = new LinearLayout.LayoutParams(-2, -2);
        allowLp.topMargin = dp(8);
        block.addView(allow, allowLp);

        View divider = new View(this);
        divider.setBackground(rounded(0x0f000000, 1));
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
        dividerLp.topMargin = dp(14);
        block.addView(divider, dividerLp);

        LinearLayout.LayoutParams blockLp = new LinearLayout.LayoutParams(-1, -2);
        blockLp.topMargin = dp(16);
        sheet.addView(block, blockLp);
        return allow;
    }

    /** 授权弹窗关闭 → 进入第 2 页（ROM 查询与高速下载） */
    private void dismissSheetThen() {
        if (permissionSheet != null) {
            View sheet = sheetBody;
            if (sheet != null) {
                sheet.animate().translationY(sheet.getHeight() + dp(16))
                        .setDuration(240)
                        .setListener(new AnimatorListenerAdapter() {
                            @Override public void onAnimationEnd(Animator a) {
                                if (permissionSheet != null) permissionSheet.dismiss();
                                showPage(1, true);
                            }
                        }).start();
                return;
            }
            permissionSheet.dismiss();
        }
        showPage(1, true);
    }

    /** 就绪页 → APP 首页：淡出 + 淡入 交叉转场（三重保险，任何 ROM 都必然可见）
     *  ① 当前引导页整窗视图动画淡出（不依赖系统窗口转场，绝不可能被 ROM 吞掉）
     *  ② 系统窗口转场 fade_in / fade_out（overrideActivityTransition / overridePendingTransition）
     *  ③ MainActivity 收到 crossfade_entry 后整页手动淡入 */
    private void crossfadeToHome(Class<?> next) {
        // 淡出内容根视图（而非 decor）：渐变背景始终保留 → 任何 ROM 都不会露黑屏
        View target = fadeContentRoot != null ? fadeContentRoot : getWindow().getDecorView();
        target.animate().alpha(0f)
                .setDuration(340)
                .setInterpolator(new android.view.animation.AccelerateInterpolator(1.1f))
                .withEndAction(() -> {
                    Intent intent = new Intent(this, next);
                    intent.putExtra("crossfade_entry", true);
                    startActivity(intent);
                    if (Build.VERSION.SDK_INT >= 34) {
                        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN,
                                R.anim.fade_in, R.anim.fade_out);
                    } else {
                        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
                    }
                    finish();
                })
                .start();
    }

    private void requestPermissionSet(String permissions, int requestCode, TextView row) {
        String[] list = permissions.split(",");
        boolean allGranted = true;
        for (String p : list) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (allGranted) {
            markAllowed(row);
            return;
        }
        pendingRow = row;
        requestPermissions(list, requestCode);
    }

    private void markAllowed(TextView row) {
        if (row == null) return;
        row.setText(english ? "Allowed" : "已允许");
        row.setTextColor(0xff9aa5b3);
        row.setBackground(rounded(0x00000000, 0));
        row.setOnClickListener(null);
    }

    private void refreshPermissionRow(TextView row, String permission) {
        if (row != null && checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            markAllowed(row);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (pendingRow != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            markAllowed(pendingRow);
        }
        pendingRow = null;
    }

    @Override public void onBackPressed() {
        if (permissionSheet != null && permissionSheet.isShowing()) {
            return;   // 授权弹窗不可返回关闭
        }
        if (page > 1) {
            // 协议单向：最多回退到第 2 页，不可回到第 1 页协议页
            showPage(page - 1, false);
        } else {
            moveTaskToBack(true);
        }
    }

    // ---------- 状态刷新 ----------

    private boolean answerCorrect() {
        if (verifyInput == null) return false;
        try {
            return Integer.parseInt(verifyInput.getText().toString().trim()) == verifyA - verifyB - verifyC;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean agreeButtonReady() {
        return agreed || answerCorrect();
    }

    private void updateAgreeButton() {
        if (page != 0) return;
        boolean ready = agreeButtonReady();
        positiveButton.setEnabled(ready);
        positiveButton.setAlpha(ready ? 1f : 0.45f);
    }

    private void updateButtons() {
        LinearLayout.LayoutParams posLp = (LinearLayout.LayoutParams) positiveButton.getLayoutParams();
        if (page == 0) {
            buttonsRow.setVisibility(View.VISIBLE);
            negativeButton.setVisibility(View.VISIBLE);
            negativeButton.setText(english ? "Decline" : "不同意");
            positiveButton.setText(english ? "Agree & Continue" : "同意并继续");
            posLp.width = 0;
            posLp.weight = 1.4f;
            positiveButton.setLayoutParams(posLp);
            updateAgreeButton();
        } else if (page == PAGE_COUNT - 1) {
            // 就绪页（图六）：底部按钮行整体隐藏，「开始使用」在页面正中
            buttonsRow.setVisibility(View.GONE);
        } else {
            buttonsRow.setVisibility(View.VISIBLE);
            negativeButton.setVisibility(View.VISIBLE);
            negativeButton.setText(english ? "Previous" : "上一步");
            positiveButton.setText(english ? "Next" : "下一步");
            positiveButton.setEnabled(true);
            positiveButton.setAlpha(1f);
            posLp.width = 0;
            posLp.weight = 1.4f;
            posLp.leftMargin = dp(12);
            positiveButton.setLayoutParams(posLp);
        }
    }

    private void updateIndicator() {
        for (int i = 0; i < PAGE_COUNT; i++) {
            ViewGroup.LayoutParams lp = dots[i].getLayoutParams();
            boolean active = i == page;
            lp.width = active ? dp(24) : dp(8);
            lp.height = dp(8);
            dots[i].setLayoutParams(lp);
            dots[i].setBackground(rounded(active ? ACCENT : 0x2e172b4d, dp(4)));
        }
    }

    // ---------- 过渡动画 ----------

    private void animateTransition(final View content, boolean forward) {
        cardHost.removeAllViews();
        cardHost.addView(content, new FrameLayout.LayoutParams(-1, -1));
        int width = cardHost.getWidth() > 0 ? cardHost.getWidth() : dp(320);
        float start = forward ? width * 0.35f : -width * 0.35f;
        content.setTranslationX(start);
        content.setAlpha(0f);
        ObjectAnimator slideIn = ObjectAnimator.ofFloat(content, View.TRANSLATION_X, start, 0f);
        ObjectAnimator fadeIn = ObjectAnimator.ofFloat(content, View.ALPHA, 0f, 1f);
        slideIn.setDuration(260);
        fadeIn.setDuration(260);
        slideIn.start();
        fadeIn.start();
    }

    private void pressFx(View v) {
        v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).withEndAction(() ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()).start();
    }

    private void generateVerification() {
        Random random = new Random();
        do {
            verifyA = 40 + random.nextInt(59);   // 40-98
            verifyB = 1 + random.nextInt(19);    // 1-19
            verifyC = 1 + random.nextInt(19);    // 1-19
        } while (verifyA - verifyB - verifyC <= 0);
    }

    // ---------- 绘制工具 ----------

    private TextView cardTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(17.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(INK);
        return tv;
    }

    private LinearLayout.LayoutParams headLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(2);
        return lp;
    }

    /** 液态玻璃按钮：主按钮蓝色渐变玻璃，次按钮与 ROM 查询同款白色液态玻璃
     *  文字精确垂直居中：关闭 CJK 字体上下留白 + 显式重力居中 + 清零内边距 */
    private Button glassButton(boolean primary) {
        Button button = new Button(this, null, 0);
        button.setAllCaps(false);
        button.setTextSize(15.5f);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(primary ? 0xffffffff : 0xff2c3a4e);
        button.setStateListAnimator(null);
        button.setIncludeFontPadding(false);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(0, 0, 0, 0);
        if (primary) {
            button.setBackground(TrueGlass.primaryButtonBg(this, 26f));
        } else {
            button.setBackgroundResource(R.drawable.liquid_glass_panel);
        }
        button.setElevation(primary ? dp(9) : dp(6));
        return button;
    }

    private GradientDrawable inputBackground() {
        // 胶囊输入框（图三）：浅色玻璃 + 极淡描边
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(0xd9eef3f7);
        drawable.setCornerRadius(dp(23));
        drawable.setStroke(Math.max(1, dp(1)), 0x599aa5b3);
        return drawable;
    }

    /** 功能行图标底座：按行次轮换的彩色轻染玻璃小方块（图三：各条目图标色彩各异） */
    private GradientDrawable iconTile(int index) {
        int[] tints = {0x242f6fd8, 0x24129d8c, 0x24d98a2f, 0x247a5fd8, 0x2438a85c, 0x24d85f8a};
        int[] rims = {0x2e2f6fd8, 0x2e129d8c, 0x2ed98a2f, 0x2e7a5fd8, 0x2e38a85c, 0x2ed85f8a};
        int i = Math.max(0, index) % tints.length;
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(tints[i]);
        drawable.setCornerRadius(dp(14));
        drawable.setStroke(Math.max(1, dp(1)), rims[i]);
        return drawable;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radiusDp);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
