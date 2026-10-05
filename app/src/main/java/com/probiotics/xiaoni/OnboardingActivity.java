package com.probiotics.xiaoni;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.Color;
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

/**
 * 首次进入 APP 的引导页（共 7 页，原汁原味的液态玻璃 + 动态极光背景）：
 *  1. 用户协议与隐私政策 + 人机验证（图二）
 *  2. ROM 查询与高速下载（图三）
 *  3. ROM 镜像提取（图四）
 *  4. DNA 工具箱（v3.41.20 新增）
 *  5. OTG 助手（v3.41.20 新增）
 *  6. DSU 与更多能力（图五）
 *  7. 一切已就绪 → 开始使用（图六）
 * 协议通过后弹出「环境与权限」卡片弹窗（通知 / 音频 / 照片视频 / 验证 Root）。
 */
public final class OnboardingActivity extends BaseActivity {

    private static final String[] CLAUSES_ZH = {
            "本应用为 DSU 动态系统更新 / ROM 下载与提取 / DNA 镜像打包 / OTG 刷机助手工具，仅供个人学习与研究使用，请勿用于商业用途。",
            "ROM 与设备清单数据来自公开社区数据源（HyperOS.fans / HyperData 等），下载链接为各厂商官方更新节点直链，本应用不存储任何 ROM 文件。",
            "本应用不收集、不上传任何个人信息；设备清单获取与版本查询直接连接公开数据源，其余操作均在本地完成。",
            "下载的 ROM 包保存在公共下载目录 Download/DsuManager，aria2c 多线程下载支持断点续传。",
            "DNA 工具箱的 17 个命令行工具（约 15M）在首次使用时从 GitHub Release 云端下载（含 gh-proxy 等公共加速镜像线路自动回退），下载、校验与部署均在本地完成。",
            "OTG 刷机助手对本机分区与外接 U 盘设备的读写操作风险较高，请确认镜像来源可靠；刷写前请自行做好数据备份。",
            "ROM 刷写、DSU 安装、镜像提取、OTG 刷机等操作存在一定风险，操作前请自行做好数据备份；因使用本应用造成的任何数据丢失或设备损坏，由使用者自行承担。",
            "继续使用即表示你已阅读并同意以上条款。",
    };
    private static final String[] CLAUSES_EN = {
            "This app is a DSU (Dynamic System Updates) / ROM download & extraction / DNA image packing / OTG flashing assistant tool, for personal study and research only.",
            "ROM and device catalog data come from public community sources (HyperOS.fans / HyperData etc.). Download links are direct links to vendor official update nodes. No ROM files are hosted by this app.",
            "This app collects and uploads no personal information. Device catalog fetching and version queries connect to public data sources directly; everything else runs locally.",
            "Downloaded ROM packages are saved to the public download folder Download/DsuManager. aria2c multi-threaded download supports resume.",
            "The 17 DNA command-line tools (~15M) are downloaded on first use from GitHub Release (with automatic fallback across public mirror routes such as gh-proxy). Download, verification and deployment all run locally.",
            "The OTG flashing assistant reads and writes local partitions and attached USB drives, which is high-risk. Verify image sources before flashing and back up your data.",
            "ROM flashing, DSU installation, image extraction and OTG flashing are risky. Back up your data before proceeding. Any data loss or device damage caused by using this app is at your own risk.",
            "By continuing you confirm that you have read and agreed to the terms above.",
    };

    // 功能页顺序（图三 → 图四 → DNA → OTG → 图五）：[0]=卡片标题，[1..n]="emoji|标题|描述"
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
            {"🧬 DNA 工具箱",
                    "🧬|DNA 打包全家桶|移植自酷安用户「相见即是缘」的 DNA 工具箱：分解 / 合成 SUPER、img-dat-br 互转、格式转换一站搞定",
                    "📦|分解 bin / 增量包|payload.bin / OTA 增量包直接解析，勾选分区逐个提取 .img 镜像",
                    "☁|工具链云端下载|17 个 CLI 工具（约 15M）首次使用时从 GitHub 下载，APK 减重 27M，下载后一键检测完整性",
                    "🔌|插件扩展|支持 DNA 插件脚本：去除 vbmeta 验证、一键宽容 SELinux 等进阶玩法",
                    "🧪|深度检测报告|ROOT 授权、工具包完整性、关键工具真实执行逐项体检，缺失影响一目了然"},
            {"🔌 OTG 助手",
                    "📱|本机分区浏览|ROOT 直读分区表，分区大小与类型一目了然，支持镜像导出与替换",
                    "⚡|OTG 刷机助手|U 盘直连刷入单分区镜像 / 完整刷机包，随时随地，无需电脑",
                    "🤖|ADB 推送通道|OTG 场景 ADB 直推安装，救砖备用通道",
                    "🛡|操作透明可控|关键操作二次确认，日志实时滚动，每一步都看得见"},
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
            {"🧬 DNA Toolbox",
                    "🧬|Full DNA Toolkit|Ported from Coolapk user XiangJianJiShiYuan's DNA: SUPER unpack / repack, img-dat-br conversion, format conversion in one place",
                    "📦|bin / Incremental Unpack|Parse payload.bin / OTA incremental packages directly, pick partitions and extract .img files",
                    "☁|Cloud Toolchain|17 CLI tools (~15M) downloaded from GitHub on first use — 27M lighter APK, with one-tap integrity check",
                    "🔌|Plugin Extensions|DNA plugin scripts: vbmeta verification removal, one-tap SELinux permissive and more",
                    "🧪|Deep Check Report|ROOT grant, toolchain integrity and real execution checks item by item, with a clear impact summary"},
            {"🔌 OTG Assistant",
                    "📱|Local Partition Browser|Read the partition table with ROOT; sizes and types at a glance, export or replace partition images",
                    "⚡|OTG Flashing Assistant|Flash single-partition images or full packages straight from a USB drive — no PC needed",
                    "🤖|ADB Push Channel|Direct ADB push install for OTG scenarios, a brick-rescue backup path",
                    "🛡|Transparent & Controlled|Double confirmation for critical actions, live scrolling logs — every step visible"},
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

    private static final int PAGE_COUNT = 7;   // v3.41.20：5 → 7（新增 DNA 工具箱、OTG 助手两页）

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
        // v3.41.20：开屏动态极光背景 —— 蓝紫 / 粉紫 / 青三色光斑缓慢漂移 + 颜色呼吸过渡
        // （18s 一个来回，对齐图三「开机引导」流光效果），垫在内容层之下透出玻璃卡片
        rootFrame.addView(new AuroraBackdrop(this), new FrameLayout.LayoutParams(-1, -1));

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
            // 最后一条（第 8 条）蓝色加粗高亮 —— 与下方验证框中的验证句呼应
            if (i == clauses.length - 1) {
                item.setTextColor(ACCENT);
                item.setTypeface(Typeface.DEFAULT_BOLD);
            }
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

        // 人机验证（v3.41.22：独立验证框 + 一键复制按钮，替代长按条款选择复制）
        TextView verifyPrefix = new TextView(this);
        verifyPrefix.setText(english ? "🤖 Human verification" : "🤖 人机验证");
        verifyPrefix.setTextSize(14);
        verifyPrefix.setTypeface(Typeface.DEFAULT_BOLD);
        verifyPrefix.setTextColor(INK);
        LinearLayout.LayoutParams vpLp = new LinearLayout.LayoutParams(-1, -2);
        vpLp.topMargin = dp(14);
        card.addView(verifyPrefix, vpLp);

        // 验证句独立框：句子文本（蓝） + 右侧「复制」按钮
        LinearLayout verifyBox = new LinearLayout(this);
        verifyBox.setOrientation(LinearLayout.HORIZONTAL);
        verifyBox.setGravity(Gravity.CENTER_VERTICAL);
        verifyBox.setPadding(dp(12), dp(10), dp(10), dp(10));
        GradientDrawable vbg = new GradientDrawable();
        vbg.setColor(0x142F6FD8);
        vbg.setCornerRadius(dp(14));
        vbg.setStroke(Math.max(1, dp(1)), 0x332F6FD8);
        verifyBox.setBackground(vbg);
        verifyLabel = new TextView(this);
        verifyLabel.setText(verifyClauseText());
        verifyLabel.setTextSize(13);
        verifyLabel.setTypeface(Typeface.DEFAULT_BOLD);
        verifyLabel.setTextColor(ACCENT);
        verifyLabel.setLineSpacing(dp(2), 1.05f);
        LinearLayout.LayoutParams vLabelLp = new LinearLayout.LayoutParams(0, -2, 1f);
        verifyBox.addView(verifyLabel, vLabelLp);
        Button copyBtn = new Button(this, null, 0);
        copyBtn.setText(english ? "复制" : "复制");
        copyBtn.setAllCaps(false);
        copyBtn.setTextSize(13);
        copyBtn.setTypeface(Typeface.DEFAULT_BOLD);
        copyBtn.setTextColor(Color.WHITE);
        copyBtn.setGravity(Gravity.CENTER);
        copyBtn.setPadding(dp(14), 0, dp(14), 0);
        copyBtn.setMinWidth(0);
        copyBtn.setMinHeight(0);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setOrientation(GradientDrawable.Orientation.TL_BR);
        cbg.setColors(new int[]{0xFF7FAAF5, 0xFF4C74DE});
        cbg.setCornerRadius(dp(999));
        copyBtn.setBackground(cbg);
        copyBtn.setStateListAnimator(null);
        copyBtn.setOnClickListener(v -> {
            Haptics.perform(v);
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("verification", verifyClauseText()));
            android.widget.Toast.makeText(this,
                    english ? "Copied — long-press the box below and paste" : "已复制，长按下方输入框粘贴即可",
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(-2, dp(34));
        copyLp.leftMargin = dp(10);
        verifyBox.addView(copyBtn, copyLp);
        LinearLayout.LayoutParams vBoxLp = new LinearLayout.LayoutParams(-1, -2);
        vBoxLp.topMargin = dp(8);
        card.addView(verifyBox, vBoxLp);

        // 操作说明
        TextView verifyHint = new TextView(this);
        verifyHint.setText(english
                ? "Tap the copy button above, then long-press the input box below and paste. This confirms you are a real person reading the terms."
                : "点上方「复制」按钮，再长按下方输入框粘贴，即完成验证。以此确认你已实际阅读条款，而非脚本自动跳过。");
        verifyHint.setTextSize(11.5f);
        verifyHint.setTextColor(INK_SOFT);
        verifyHint.setLineSpacing(dp(2), 1.05f);
        LinearLayout.LayoutParams vhLp = new LinearLayout.LayoutParams(-1, -2);
        vhLp.topMargin = dp(6);
        card.addView(verifyHint, vhLp);

        verifyInput = new EditText(this);
        verifyInput.setHint(english ? "Long-press here and paste" : "长按这里粘贴验证句");
        verifyInput.setTextSize(14);
        verifyInput.setTextColor(INK);
        verifyInput.setHintTextColor(0xff9aa5b3);
        // 文本输入（支持粘贴），关闭自动建议避免输入法改写粘贴内容
        verifyInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
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
        // 键盘弹出 → 滚动到人机验证区域，确保说明与输入框可见
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

    // ---------- 「环境与权限」授权弹窗（v3.41.20：图二风格居中玻璃卡片） ----------

    private android.app.Dialog permissionSheet;
    private LinearLayout sheetBody;
    private TextView allowNotification;
    private TextView allowAudio;
    private TextView allowMedia;
    private TextView rootRow;          // v3.41.20：「验证 Root」行胶囊
    private boolean rootCheckRunning;  // Root 检测进行中（防重复点击）
    private TextView allFilesRow;      // v3.41.21：「所有文件访问」行状态圆点
    private TextView installUnknownRow;   // v3.41.25：「安装未知应用」行状态圆点
    private Button confirmButton;      // v3.41.23：底部「一键授权」大按钮（授权中显示进度）
    /** v3.41.21：「继续」流程等待用户从系统设置开启所有文件访问（回来 onResume 自动前进） */
    private boolean pendingAllFiles;
    /** v3.41.25：「继续」流程等待用户从系统设置开启安装未知应用（回来 onResume 自动前进） */
    private boolean pendingInstallUnknown;
    /** 当前单行授权请求对应的权限（回调里判断永久拒绝 → 引导去设置） */
    private String[] pendingPerms;
    private TextView pendingRow;
    /** v3.8.8：底部「允许」一键授权流程进行中（系统授权弹窗回调后自动进入下一页） */
    private boolean pendingAllowAll;

    private void showPermissionSheet() {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setBackgroundColor(0x59000000);
        // v3.41.20：重做为「环境与权限」居中玻璃卡片弹窗（对齐图二：图标 + 说明 + 选项卡片 + 底部大按钮）
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(22), dp(20), dp(22), dp(14));
        GradientDrawable sheetBg = new GradientDrawable();
        sheetBg.setColor(0xF7F0F4FA);
        sheetBg.setCornerRadius(dp(28));
        sheetBg.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
        sheet.setBackground(sheetBg);
        sheet.setElevation(dp(18));
        FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        sheetLp.leftMargin = dp(20);
        sheetLp.rightMargin = dp(20);
        wrap.addView(sheet, sheetLp);
        sheetBody = sheet;

        // 顶部渐变圆底盾牌图标（居中）
        LinearLayout iconCircle = new LinearLayout(this);
        iconCircle.setGravity(Gravity.CENTER);
        GradientDrawable circleBg = new GradientDrawable();
        circleBg.setOrientation(GradientDrawable.Orientation.TL_BR);
        circleBg.setColors(new int[]{0xFF7FAAF5, 0xFFB98FE8, 0xFFF0A8C8});
        circleBg.setShape(GradientDrawable.OVAL);
        circleBg.setStroke(Math.max(1, dp(1)), 0x99FFFFFF);
        iconCircle.setBackground(circleBg);
        iconCircle.setElevation(dp(6));
        TextView shield = new TextView(this);
        shield.setText("🛡");
        shield.setTextSize(26);
        iconCircle.addView(shield, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams circleLp = new LinearLayout.LayoutParams(dp(62), dp(62));
        circleLp.gravity = Gravity.CENTER_HORIZONTAL;
        circleLp.topMargin = dp(4);
        sheet.addView(iconCircle, circleLp);

        // 标题 + 副标题（图二：居中说明文字）
        TextView heading = new TextView(this);
        heading.setText(english ? "Environment & Permissions" : "环境与权限");
        heading.setTextSize(18);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setTextColor(INK);
        heading.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams headingLp = new LinearLayout.LayoutParams(-1, -2);
        headingLp.topMargin = dp(10);
        sheet.addView(heading, headingLp);
        TextView subtitle = new TextView(this);
        subtitle.setText(english
                ? "With Root granted, tap \"Grant all\" and everything completes automatically. Without Root, \"All files access\" needs to be enabled manually in app details."
                : "已授权 Root 时，点击「一键授权」即可全部自动完成，不跳转设置；未授权 Root 时，「所有文件访问」需跳转应用详情手动开启，其余权限仍可自动授予。");
        subtitle.setTextSize(12);
        subtitle.setTextColor(INK_SOFT);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = dp(4);
        sheet.addView(subtitle, subLp);

        // 选项卡片行（整行可点击授权）
        allowNotification = envRow(sheet, "🔔",
                english ? "Notifications" : "通知权限", notificationDescription(),
                () -> grantRow(allowNotification, 1010,
                        new String[]{"android.permission.POST_NOTIFICATIONS"}, false));
        allowAudio = envRow(sheet, "🎵",
                english ? "Music & audio" : "音频文件",
                english ? "Read and edit music and audio files" : "读取和编辑音乐、音频文件",
                () -> grantRow(allowAudio, 1011,
                        new String[]{"android.permission.READ_MEDIA_AUDIO"}, false));
        allowMedia = envRow(sheet, "🖼️",
                english ? "Photos and videos" : "照片和视频",
                english ? "Read and edit photos and video files" : "读取和编辑照片、视频文件",
                () -> grantRow(allowMedia, 1012,
                        new String[]{"android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO"}, false));
        // v3.41.21：补「所有文件访问」—— ROM 选择 / 镜像保存到公共目录的核心权限
        // v3.41.22：ROOT 可用时直接 appops set 静默开启，不再跳系统设置页
        allFilesRow = envRow(sheet, "📂",
                english ? "All files access" : "所有文件访问",
                english ? "Required for picking ROM packages and saving images to public folders. Without Root this must be enabled manually in app details."
                        : "选择 ROM 包、保存镜像到公共目录需要此权限。未授权 Root 时需跳转应用详情手动开启",
                () -> grantRow(allFilesRow, 1014, new String[0], true));
        // v3.41.25：新增「安装未知应用」—— 应用内自动更新安装的核心权限
        // ROOT 时 appops 静默开启；无 ROOT 时跳应用详情手动开启（开启后应用内更新即静默安装）
        installUnknownRow = envRow(sheet, "📦",
                english ? "Install unknown apps" : "安装未知应用",
                english ? "Required for in-app self-update. Enabled automatically with Root; otherwise enable it in app details"
                        : "应用内检查更新并自动安装新版本需要此权限。已授权 Root 时自动开启；未授权时需跳转应用详情手动开启",
                () -> grantInstallUnknown());
        rootRow = envRow(sheet, "🧢",
                english ? "Verify Root" : "验证 Root",
                english ? "ROOT is required by DSU install, image extraction, DNA toolbox and OTG"
                        : "DSU 安装、镜像提取、DNA 工具箱、OTG 助手均需要 ROOT 授权",
                null);   // 点击行为单独接 runRootCheck
        rootRow.setOnClickListener(v -> {
            Haptics.perform(v);
            runRootCheck();
        });
        // v3.41.23：打开即对账全部行（已授权的立即亮绿）+ 静默自检 ROOT
        refreshAllPermissionRows();
        runRootCheck();

        // 底部「一键授权」大按钮（品牌渐变蓝，全宽 48dp）—— 点击后 ROOT 自动授予全部权限
        confirmButton = new Button(this, null, 0);
        confirmButton.setText(english ? "Grant all" : "一键授权");
        confirmButton.setAllCaps(false);
        confirmButton.setTextSize(15.5f);
        confirmButton.setTypeface(Typeface.DEFAULT_BOLD);
        confirmButton.setTextColor(Color.WHITE);
        confirmButton.setStateListAnimator(null);
        confirmButton.setGravity(Gravity.CENTER);
        confirmButton.setPadding(0, 0, 0, 0);
        GradientDrawable confirmBg = new GradientDrawable();
        confirmBg.setOrientation(GradientDrawable.Orientation.TL_BR);
        confirmBg.setColors(new int[]{0xFF7FAAF5, 0xFF4C74DE, 0xFF3E63C9});
        confirmBg.setCornerRadius(dp(22));
        confirmBg.setStroke(Math.max(1, dp(1)), 0x80FFFFFF);
        confirmButton.setBackground(confirmBg);
        confirmButton.setElevation(dp(6));
        confirmButton.setOnClickListener(v -> {
            Haptics.perform(v);
            requestAllThenDismiss();
        });
        LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(-1, dp(48));
        confirmLp.topMargin = dp(14);
        sheet.addView(confirmButton, confirmLp);

        // 次级入口：暂不授权直接进入（不阻塞引导）
        Button cancel = new Button(this, null, 0);
        cancel.setText(english ? "Skip for now" : "暂不授权，直接进入");
        cancel.setAllCaps(false);
        cancel.setTextSize(13);
        cancel.setTextColor(INK_SOFT);
        cancel.setStateListAnimator(null);
        cancel.setBackground(rounded(0x00000000, 0));
        cancel.setIncludeFontPadding(false);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, 0, 0, 0);
        cancel.setOnClickListener(v -> {
            Haptics.perform(v);
            dismissSheetThen();
        });
        sheet.addView(cancel, new LinearLayout.LayoutParams(-1, dp(40)));

        permissionSheet = new android.app.Dialog(this);
        permissionSheet.setContentView(wrap);
        permissionSheet.getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(0x00000000));
        permissionSheet.getWindow().setLayout(-1, -1);
        permissionSheet.setCancelable(false);
        permissionSheet.show();

        // 居中缩放淡入（弹性）
        wrap.setAlpha(0f);
        wrap.animate().alpha(1f).setDuration(200).start();
        sheet.setScaleX(0.92f);
        sheet.setScaleY(0.92f);
        sheet.animate().scaleX(1f).scaleY(1f)
                .setDuration(340)
                .setInterpolator(new DecelerateInterpolator(1.2f))
                .start();

        // v3.41.23：已合并入上方 refreshAllPermissionRows()（含所有文件访问与照片视频）
    }

    /** 「环境与权限」选项行：图标圆 + 标题/描述 + 右侧状态圆点（整行可点单行授权）；
     *  v3.41.23 图一风格：空心圆 = 待授权，绿色实心 ✓ = 已授权（去掉逐项「授权/去设置」按钮） */
    private TextView envRow(LinearLayout parent, String emoji, String title, CharSequence desc, Runnable grant) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable rowBg = new GradientDrawable();
        rowBg.setColor(0xCFFFFFFF);
        rowBg.setCornerRadius(dp(18));
        rowBg.setStroke(Math.max(1, dp(1)), 0x4DFFFFFF);
        row.setBackground(rowBg);
        row.setElevation(dp(2));

        LinearLayout circle = new LinearLayout(this);
        circle.setGravity(Gravity.CENTER);
        GradientDrawable cb = new GradientDrawable();
        cb.setOrientation(GradientDrawable.Orientation.TL_BR);
        cb.setColors(new int[]{0xFFBFD4F2, 0xFF9FBFE8});
        cb.setShape(GradientDrawable.OVAL);
        circle.setBackground(cb);
        TextView icon = new TextView(this);
        icon.setText(emoji);
        icon.setTextSize(17);
        circle.addView(icon, new LinearLayout.LayoutParams(-2, -2));
        row.addView(circle, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout tb = new LinearLayout(this);
        tb.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(14);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(INK);
        tb.addView(name, new LinearLayout.LayoutParams(-1, -2));
        TextView detail = new TextView(this);
        detail.setText(desc);
        detail.setTextSize(11);
        detail.setTextColor(INK_SOFT);
        detail.setLineSpacing(dp(1), 1.05f);
        if (desc instanceof android.text.Spanned) {
            detail.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
            detail.setHighlightColor(0x1a2f6fd8);
        }
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(-1, -2);
        detailLp.topMargin = dp(2);
        tb.addView(detail, detailLp);
        LinearLayout.LayoutParams tbLp = new LinearLayout.LayoutParams(0, -2, 1f);
        tbLp.leftMargin = dp(11);
        row.addView(tb, tbLp);

        // v3.41.23：状态圆点 —— 待授权：透明底灰描边空心圆；已授权：绿色实心 + 白 ✓
        TextView dot = new TextView(this);
        dot.setText("✓");
        dot.setTextSize(12);
        dot.setTypeface(Typeface.DEFAULT_BOLD);
        dot.setTextColor(Color.WHITE);
        dot.setGravity(Gravity.CENTER);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(0x00000000);
        dotBg.setStroke(Math.max(1, dp(2)), 0xFFB7C3D6);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(24), dp(24));
        dotLp.leftMargin = dp(8);
        row.addView(dot, dotLp);

        row.setOnClickListener(v -> {
            Haptics.perform(v);
            if (grant != null) grant.run();
        });

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = dp(9);
        parent.addView(row, rowLp);
        return dot;
    }

    /** 「验证 Root」行：后台静默检测 su 可用性，成功则圆点亮绿（不阻塞，可重复点） */
    private void runRootCheck() {
        if (rootRow == null || rootCheckRunning) return;
        rootCheckRunning = true;
        new Thread(() -> {
            boolean ok = false;
            try { ok = RootShell.INSTANCE.available(); } catch (Exception ignored) { }
            final boolean granted = ok;
            runOnUiThread(() -> {
                rootCheckRunning = false;
                if (granted) markAllowed(rootRow);
            });
        }, "onboard-root-check").start();
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

    /** 授权弹窗关闭 → 进入第 2 页（ROM 查询与高速下载）；v3.41.20 居中卡片：缩放淡出 */
    private void dismissSheetThen() {
        if (permissionSheet != null) {
            View sheet = sheetBody;
            if (sheet != null) {
                sheet.animate().scaleX(0.94f).scaleY(0.94f).alpha(0f)
                        .setDuration(220)
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

    /**
     * v3.41.22：单行授权统一入口 —— ROOT 可用时直接 pm grant / appops set 静默授权
     * （点一下立即变绿，无需系统弹窗、无需跳设置页，对齐图五「直接点击授权」体验）；
     * 无 ROOT 才回退系统 requestPermissions / 跳设置页。
     */
    private void grantRow(TextView row, int reqCode, String[] perms, boolean allFiles) {
        new Thread(() -> {
            boolean rooted = false;
            try { rooted = RootShell.INSTANCE.available(); } catch (Exception ignored) { }
            if (rooted) {
                String pkg = getPackageName();
                for (String p : perms) {
                    try { RootShell.INSTANCE.exec("pm grant " + pkg + " " + p, 10000L, null); } catch (Exception ignored) { }
                }
                if (allFiles) {
                    try {
                        RootShell.INSTANCE.exec("pm grant " + pkg + " android.permission.MANAGE_EXTERNAL_STORAGE", 10000L, null);
                    } catch (Exception ignored) { }
                    try {
                        RootShell.INSTANCE.exec("appops set " + pkg + " MANAGE_EXTERNAL_STORAGE allow", 10000L, null);
                    } catch (Exception ignored) { }
                }
                runOnUiThread(() -> {
                    if (allFiles) refreshAllFilesRow();
                    else if (perms.length > 0) reconcileRow(row, perms);
                });
            } else {
                runOnUiThread(() -> {
                    if (allFiles) requestAllFilesAccess();
                    else if (perms.length > 0)
                        requestPermissionSet(String.join(",", perms), reqCode, row);
                });
            }
        }, "grant-row").start();
    }

    /**
     * v3.41.25：「安装未知应用」行授权 —— ROOT 可用时 appops set 静默开启
     * （应用内更新即可真静默安装）；无 ROOT 回退跳系统应用详情页手动开启。
     */
    private void grantInstallUnknown() {
        if (getPackageManager().canRequestPackageInstalls()) {
            markAllowed(installUnknownRow);
            return;
        }
        new Thread(() -> {
            boolean rooted = false;
            try { rooted = RootShell.INSTANCE.available(); } catch (Exception ignored) { }
            if (rooted) {
                try {
                    RootShell.INSTANCE.exec("appops set " + getPackageName()
                            + " REQUEST_INSTALL_PACKAGES allow", 10000L, null);
                } catch (Exception ignored) { }
                runOnUiThread(() -> {
                    if (getPackageManager().canRequestPackageInstalls())
                        markAllowed(installUnknownRow);
                });
            } else {
                runOnUiThread(() -> requestInstallUnknownAccess());
            }
        }, "grant-install-unknown").start();
    }

    /** 跳系统「安装未知应用」设置页（本应用直达开关） */
    private void requestInstallUnknownAccess() {
        try {
            startActivity(new android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) { }
    }

    /**
     * v3.8.8：底部「允许」一键授权 —— 收集全部未授权权限（通知 / 音频 / 照片和视频），
     * 一次 requestPermissions 发起真实系统授权；回调到达后刷新各行状态并进入下一页。
     * v3.41.21 全自动流：运行时权限齐了以后，若 Android 11+「所有文件访问」未开启，
     * 自动跳系统设置页，用户开启后返回（onResume）自动关闭弹窗进入下一页。
     * v3.41.22：ROOT 可用时整组直接 pm grant + appops 一键全绿（不弹任何系统窗口）。
     */
    private void requestAllThenDismiss() {
        // v3.41.23：按钮进入「正在授权…」状态，防止重复点击
        if (confirmButton != null) {
            confirmButton.setEnabled(false);
            confirmButton.setText(english ? "Granting…" : "正在授权…");
        }
        new Thread(() -> {
            boolean rooted = false;
            try { rooted = RootShell.INSTANCE.available(); } catch (Exception ignored) { }
            if (rooted) {
                String pkg = getPackageName();
                String[] runtime = {
                        "android.permission.POST_NOTIFICATIONS",
                        "android.permission.READ_MEDIA_AUDIO",
                        "android.permission.READ_MEDIA_IMAGES",
                        "android.permission.READ_MEDIA_VIDEO",
                };
                for (String p : runtime) {
                    if (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) continue;
                    try { RootShell.INSTANCE.exec("pm grant " + pkg + " " + p, 10000L, null); } catch (Exception ignored) { }
                }
                if (needAllFiles()) {
                    try {
                        RootShell.INSTANCE.exec("pm grant " + pkg + " android.permission.MANAGE_EXTERNAL_STORAGE", 10000L, null);
                    } catch (Exception ignored) { }
                    try {
                        RootShell.INSTANCE.exec("appops set " + pkg + " MANAGE_EXTERNAL_STORAGE allow", 10000L, null);
                    } catch (Exception ignored) { }
                }
                // v3.41.25：安装未知应用也一并静默开启（应用内更新真静默安装的前提）
                try {
                    RootShell.INSTANCE.exec("appops set " + pkg + " REQUEST_INSTALL_PACKAGES allow", 10000L, null);
                } catch (Exception ignored) { }
                runOnUiThread(() -> {
                    refreshAllPermissionRows();
                    runRootCheck();
                    dismissSheetThen();
                });
                return;
            }
            // 无 ROOT：系统弹窗流（恢复按钮可点，由回调推进）
            runOnUiThread(() -> {
                if (confirmButton != null) {
                    confirmButton.setEnabled(true);
                    confirmButton.setText(english ? "Grant all" : "一键授权");
                }
                requestAllThenDismissViaSystem();
            });
        }, "grant-all").start();
    }

    /** 无 ROOT 时的「继续」：系统弹窗批量授权 + 所有文件访问跳设置（原 v3.41.21 流程） */
    private void requestAllThenDismissViaSystem() {
        java.util.List<String> need = new java.util.ArrayList<>();
        String[][] groups = {
                {"android.permission.POST_NOTIFICATIONS"},
                {"android.permission.READ_MEDIA_AUDIO"},
                {"android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO"}
        };
        for (String[] group : groups) {
            boolean granted = true;
            for (String p : group) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    break;
                }
            }
            if (!granted) need.addAll(java.util.Arrays.asList(group));
        }
        if (need.isEmpty()) {
            // 运行时权限已齐：检查「所有文件访问」，未开启则引导去系统设置（返回后自动前进）
            refreshAllPermissionRows();
            if (needAllFiles()) {
                pendingAllFiles = true;
                requestAllFilesAccess();
                return;
            }
            // v3.41.25：「安装未知应用」未开启 → 继续引导（返回后自动前进）
            if (!getPackageManager().canRequestPackageInstalls()) {
                pendingInstallUnknown = true;
                requestInstallUnknownAccess();
                return;
            }
            dismissSheetThen();
            return;
        }
        pendingAllowAll = true;
        requestPermissions(need.toArray(new String[0]), 1013);
    }

    /** Android 11+ 是否需要开启「所有文件访问」（旧版本由 WRITE_EXTERNAL_STORAGE 覆盖） */
    private boolean needAllFiles() {
        if (Build.VERSION.SDK_INT < 30) return false;
        return !android.os.Environment.isExternalStorageManager();
    }

    /** 跳系统「所有文件访问」设置页（优先直达本应用开关，失败回退到总开关列表） */
    private void requestAllFilesAccess() {
        if (!needAllFiles()) {
            refreshAllFilesRow();
            return;
        }
        try {
            startActivity(new android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception ignored) { }
        }
    }

    /** 「所有文件访问」胶囊状态：已开启 → 绿色 ✓；未开启保持「授权」可点击 */
    private void refreshAllFilesRow() {
        if (allFilesRow == null) return;
        if (!needAllFiles()) {
            markAllowed(allFilesRow);
        }
    }

    /** 弹窗打开期间从系统设置/授权弹窗返回 → 全部行状态对账刷新（v3.41.21：解决"允许了却不变绿"） */
    @Override protected void onResume() {
        super.onResume();
        if (permissionSheet != null && permissionSheet.isShowing()) {
            refreshAllPermissionRows();
            // 「继续」流程等待所有文件访问：开启成功 → 自动关闭弹窗进入下一页
            if (pendingAllFiles && !needAllFiles()) {
                pendingAllFiles = false;
                // v3.41.25：所有文件访问完成 → 继续引导「安装未知应用」（链式，不中断）
                if (!getPackageManager().canRequestPackageInstalls()) {
                    pendingInstallUnknown = true;
                    requestInstallUnknownAccess();
                    return;
                }
                dismissSheetThen();
            }
            // v3.41.25：「继续」流程等待安装未知应用：开启成功 → 自动关闭弹窗进入下一页
            if (pendingInstallUnknown && getPackageManager().canRequestPackageInstalls()) {
                pendingInstallUnknown = false;
                dismissSheetThen();
            }
        }
    }

    private void refreshAllPermissionRows() {
        refreshPermissionRow(allowNotification, "android.permission.POST_NOTIFICATIONS");
        refreshPermissionRow(allowAudio, "android.permission.READ_MEDIA_AUDIO");
        refreshPermissionRow(allowMedia, "android.permission.READ_MEDIA_IMAGES");
        refreshAllFilesRow();
        // v3.41.25：安装未知应用状态对账
        if (installUnknownRow != null && getPackageManager().canRequestPackageInstalls()) {
            markAllowed(installUnknownRow);
        }
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
        pendingPerms = list;
        requestPermissions(list, requestCode);
    }

    private void markAllowed(TextView row) {
        if (row == null) return;
        // v3.41.23：授权后圆点 → 绿色实心 + 白 ✓
        GradientDrawable okBg = new GradientDrawable();
        okBg.setShape(GradientDrawable.OVAL);
        okBg.setColor(0xFF34A853);
        okBg.setStroke(Math.max(1, dp(1)), 0xFF1D7A4F);
        row.setBackground(okBg);
    }

    /**
     * v3.41.23：删除「去设置」橙色按钮 —— ROOT 路径直接 pm grant / appops 授权，
     * 永久拒绝也能强制授予；无 ROOT 才走系统弹窗。对账失败一律保持空心圆（可再点）。
     */
    private void reconcileRow(TextView row, String[] perms) {
        boolean granted = true;
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) { granted = false; break; }
        }
        if (granted) markAllowed(row);
    }

    private void refreshPermissionRow(TextView row, String permission) {
        if (row != null && checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            markAllowed(row);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // v3.8.8：底部「允许」一键授权回调 → 刷新三行状态后进入下一页（拒绝也不阻塞引导）
        if (requestCode == 1013) {
            reconcileRow(allowNotification, new String[]{"android.permission.POST_NOTIFICATIONS"});
            reconcileRow(allowAudio, new String[]{"android.permission.READ_MEDIA_AUDIO"});
            reconcileRow(allowMedia, new String[]{"android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO"});
            if (pendingAllowAll) {
                pendingAllowAll = false;
                // 运行时权限走完：Android 11+ 所有文件访问未开启 → 跳设置，返回后自动前进
                if (needAllFiles()) {
                    pendingAllFiles = true;
                    android.widget.Toast.makeText(this,
                            english ? "One more step: enable \"All files access\" in Settings, then return"
                                    : "还差一步：在系统设置中开启「所有文件访问」后返回即可",
                            android.widget.Toast.LENGTH_LONG).show();
                    requestAllFilesAccess();
                } else if (!getPackageManager().canRequestPackageInstalls()) {
                    // v3.41.25：运行时权限走完 → 继续引导「安装未知应用」
                    pendingInstallUnknown = true;
                    android.widget.Toast.makeText(this,
                            english ? "One more step: enable \"Install unknown apps\", then return"
                                    : "还差一步：在系统设置中开启「安装未知应用」后返回即可",
                            android.widget.Toast.LENGTH_LONG).show();
                    requestInstallUnknownAccess();
                } else {
                    dismissSheetThen();
                }
            }
            pendingRow = null;
            pendingPerms = null;
            return;
        }
        if (pendingRow != null) {
            if (pendingPerms != null) reconcileRow(pendingRow, pendingPerms);
            else if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                markAllowed(pendingRow);
            }
        }
        pendingRow = null;
        pendingPerms = null;
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

    // v3.41.21：人机验证改为「长按复制第 8 条条款原文 → 粘贴」——
    // 输入内容与条款原文归一化后一致即通过（替代旧加减算式）
    private boolean answerCorrect() {
        if (verifyInput == null) return false;
        String input = normalizeClause(verifyInput.getText().toString());
        return !input.isEmpty() && input.equals(normalizeClause(verifyClauseText()));
    }

    /** 人机验证的粘贴目标：最后一条条款（第 8 条）原文 */
    private String verifyClauseText() {
        String[] clauses = english ? CLAUSES_EN : CLAUSES_ZH;
        return clauses[clauses.length - 1];
    }

    /** 粘贴内容归一化：trim + 连续空白合一 + 去尾部句号 + 忽略大小写（英文） */
    private String normalizeClause(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ")
                .replaceAll("[。．.]$", "")
                .toLowerCase(Locale.ROOT);
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

    // ---------- v3.41.20：开屏动态极光背景 ----------

    /**
     * 开屏「流光」背景（对齐图三开机引导的动态弥散渐变）：
     * 三个彩色光斑（蓝紫 / 粉紫 / 青）各自沿椭圆轨迹缓慢漂移，颜色随相位呼吸过渡，
     * 18 秒一个来回（REVERSE 往返，永不落幕）。半透明叠加在静态液态玻璃底色之上，
     * 玻璃卡片半透明处会随光斑流动微微变色 —— 「Dsu 管理器」标题下方流光溢彩。
     */
    private final class AuroraBackdrop extends View {
        private final android.animation.ValueAnimator phase;
        // 每个光斑：中心相对位置 [x, y] 与两端呼吸色（ARGB，含透明度）
        private final float[][] centers = {{0.22f, 0.16f}, {0.80f, 0.30f}, {0.50f, 0.88f}};
        private final int[][] palette = {
                {0x5A7FAAF5, 0x5AB98FE8},   // 蓝紫 → 亮紫
                {0x5AF0A8C8, 0x5AD48BA8},   // 粉紫 → 玫瑰
                {0x5A9FE0DC, 0x5AB8C4FF},   // 青 → 蓝白
        };
        private final android.graphics.Paint paint = new android.graphics.Paint();

        AuroraBackdrop(android.content.Context ctx) {
            super(ctx);
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
            phase = android.animation.ValueAnimator.ofFloat(0f, 1f);
            phase.setDuration(18000L);
            phase.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            phase.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            phase.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            phase.addUpdateListener(a -> invalidate());
            phase.start();
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            float p = (float) phase.getAnimatedValue();
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float span = Math.max(w, h);
            for (int i = 0; i < centers.length; i++) {
                float wobble = (float) Math.sin(p * Math.PI * 2f + i * 2.1f);
                float x = (centers[i][0] + 0.09f * wobble) * w;
                float y = (centers[i][1] + 0.05f * (float) Math.cos(p * Math.PI * 2f + i * 1.7f)) * h;
                float t = 0.5f + 0.5f * (float) Math.sin(p * Math.PI + i * 1.3f);
                int color = blend(palette[i][0], palette[i][1], t);
                float radius = span * (0.40f + 0.07f * wobble);
                paint.reset();
                paint.setAntiAlias(true);
                paint.setShader(new android.graphics.RadialGradient(
                        x, y, radius, color, 0, android.graphics.Shader.TileMode.CLAMP));
                canvas.drawCircle(x, y, radius, paint);
            }
        }

        /** 两 ARGB 颜色按 t∈[0,1] 线性插值 */
        private int blend(int a, int b, float t) {
            float clamped = Math.max(0f, Math.min(1f, t));
            int aa = (a >> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
            int ba = (b >> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
            return Color.argb(
                    Math.round(aa + (ba - aa) * clamped),
                    Math.round(ar + (br - ar) * clamped),
                    Math.round(ag + (bg - ag) * clamped),
                    Math.round(ab + (bb - ab) * clamped));
        }

        @Override protected void onDetachedFromWindow() {
            phase.cancel();
            super.onDetachedFromWindow();
        }
    }
}
