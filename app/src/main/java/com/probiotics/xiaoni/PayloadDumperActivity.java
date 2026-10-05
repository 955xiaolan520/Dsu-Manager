package com.probiotics.xiaoni;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.*;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLong;

/** Payload Dumper 功能：在线提取和本地提取 */
public final class PayloadDumperActivity extends BaseActivity {
    private static final String[] TAB_LABELS = {"在线提取", "本地提取"};
    private static final int PICK_FILE = 1001;
    
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicLong tokenGenerator = new AtomicLong(1);
    private final Map<Integer, Long> extractTokens = new HashMap<>();
    private final Map<Long, Boolean> cancelledTokens = new HashMap<>(); // 取消标志
    
    private Button[] typeTabs;
    private EditText onlineUrlInput;
    private Button onlineExtractButton;
    private Button localPickButton;
    private LinearLayout[] panels;
    private TextView status;
    private LinearLayout onlinePartitionsList;
    private LinearLayout localPartitionsList;
    private TextView localFileNameDisplay;
    private LiquidGlassIndicator tabIndicator;
    private TextView onlineLogDisplay;
    private TextView localLogDisplay;
    private ScrollView onlineLogScroll;
    private ScrollView localLogScroll;
    private LiquidGlassPanel onlineLogPanelRef;
    private LiquidGlassPanel localLogPanelRef;
    
    // Tab 动画相关（完全照搬 vivo）
    private LinearLayout tabRow;
    private LinearLayout tabItems;
    private ValueAnimator tabPulse;
    private ValueAnimator breathingAnimator;
    private int tabIndicatorLeft = -1;
    private boolean tabDragging = false;
    private int tabDragTarget = -1;
    private float tabDragStartX = 0f;
    private Runnable tabLongPress;
    
    private PayloadExtractor extractor;
    private String currentInput;
    private volatile String originalLocalPath;   // v3.40.14：用户选择的原始路径（root dumper 直读，不受 FUSE 权限影响）
    private Uri selectedFileUri;
    private String selectedFileName = "";
    private int currentTab = 0;

    // v3.40.13：全选提取（在线/本地共用 root payload_dumper 逐分区链路）
    private final java.util.List<PayloadExtractor.PartitionInfo> onlinePartitions = new java.util.ArrayList<>();
    private final java.util.List<PayloadExtractor.PartitionInfo> localPartitions = new java.util.ArrayList<>();
    private final java.util.concurrent.atomic.AtomicBoolean extractAllRunning = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile boolean extractAllCancel = false;
    private Button localExtractAllButton;
    private Button onlineExtractAllButton;

    /** v3.40.13：GitHub 加速镜像（在线全选下载整包用，镜像优先 + 直连垫底，同 UpdateCenter） */
    private static final String[] GH_MIRRORS = {
            "https://gh-proxy.com/", "https://ghfast.top/", "https://ghproxy.net/", ""};
    /** 慢速看门狗：持续低于 128KB/s 达 6 秒且后面还有源 → 弃源切换 */
    private static final long SLOW_SPEED = 128 * 1024;
    private static final long SLOW_WINDOW_MS = 6000;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 获取状态栏高度
        int statusBarHeight = 0;
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            statusBarHeight = getResources().getDimensionPixelSize(resourceId);
        }
        
        FrameLayout root = new FrameLayout(this);
        root.setBackground(createXiaomiGradientBackground());
        root.setPadding(0, statusBarHeight + dp(12), 0, 0); // 状态栏高度 + 12dp 间距
        
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0x00000000);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), dp(16));
        
        // 顶部标题：返回按钮 + 标题（与其他查询页一致）
        LiquidGlassPanel titleBar = new LiquidGlassPanel(this, 24f);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.setPadding(dp(8), 0, dp(8), 0);
        Button back = new Button(this);
        back.setText("<");
        back.setTextSize(20);
        back.setMinWidth(0);
        back.setMinHeight(0);
        back.setAllCaps(false);
        back.setTypeface(null, 1);
        back.setTextColor(0xff0f1e36);
        back.setBackgroundResource(R.drawable.liquid_glass_panel);
        back.setStateListAnimator(null);
        back.setOnClickListener(v -> {
            Haptics.perform(v);
            finish();
            overridePendingTransition(R.anim.slide_up_in, R.anim.slide_up_out);
        });
        titleBar.addView(back, new LinearLayout.LayoutParams(dp(42), dp(48)));
        TextView title = new TextView(this);
        title.setText("Payload Dumper");
        title.setTextSize(22);
        title.setTextColor(0xff0f1e36);
        title.setTypeface(null, 1);
        title.setGravity(Gravity.CENTER);
        titleBar.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, dp(56));
        titleLp.bottomMargin = dp(8);
        content.addView(titleBar, titleLp);
        
        // Tab 切换（完全照搬 vivo）
        FrameLayout tabContainerWrapper = new FrameLayout(this);
        
        LinearLayout tabContainer = glass();
        tabContainer.setOrientation(LinearLayout.VERTICAL);
        tabContainer.setPadding(dp(12), dp(8), dp(12), dp(8));
        
        // 创建 Tab 按钮容器
        tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setId(View.generateViewId());
        
        tabItems = new LinearLayout(this);
        tabItems.setOrientation(LinearLayout.HORIZONTAL);
        
        typeTabs = new Button[TAB_LABELS.length];
        for (int i = 0; i < TAB_LABELS.length; i++) {
            final int index = i;
            typeTabs[i] = new Button(this);
            typeTabs[i].setText(TAB_LABELS[i]);
            typeTabs[i].setTextSize(14);
            // vivo 样式：alpha + typeface + 颜色
            typeTabs[i].setAlpha(i == 0 ? 1f : 0.55f);
            typeTabs[i].setTypeface(null, i == 0 ? 1 : 0); // bold / normal
            typeTabs[i].setTextColor(i == 0 ? 0xff17334f : 0xff5a6b82);
            typeTabs[i].setBackgroundColor(0);
            typeTabs[i].setAllCaps(false);
            typeTabs[i].setMinHeight(0);
            typeTabs[i].setMinWidth(0);
            typeTabs[i].setPadding(0, 0, 0, 0);
            // vivo 使用 onTouchListener 处理手势
            typeTabs[i].setOnTouchListener((v, event) -> handleTabGesture(v, event, index));
            
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1);
            lp.setMargins(dp(2), 0, dp(2), 0);
            tabItems.addView(typeTabs[i], lp);
        }
        
        tabRow.addView(tabItems, new LinearLayout.LayoutParams(-1, -2));
        tabContainer.addView(tabRow);
        tabContainerWrapper.addView(tabContainer, new FrameLayout.LayoutParams(-1, -2));
        
        // 液态指示器
        tabIndicator = new LiquidGlassIndicator(this);
        FrameLayout.LayoutParams indicatorLp = new FrameLayout.LayoutParams(dp(78), dp(46));
        indicatorLp.topMargin = dp(9);
        indicatorLp.gravity = Gravity.TOP | Gravity.LEFT;
        tabContainerWrapper.addView(tabIndicator, indicatorLp);
        
        content.addView(tabContainerWrapper, new LinearLayout.LayoutParams(-1, -2));
        
        // 状态提示
        status = new TextView(this);
        status.setText("请选择提取方式");
        status.setTextSize(13);
        status.setTextColor(0xff5a6a7f);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(16), 0, dp(12));
        content.addView(status, new LinearLayout.LayoutParams(-1, -2));
        
        // 两个面板
        panels = new LinearLayout[2];
        
        // 在线提取面板
        panels[0] = createOnlinePanel();
        LinearLayout.LayoutParams onlineLp = new LinearLayout.LayoutParams(-1, -2);
        onlineLp.setMargins(0, 0, 0, dp(12));
        content.addView(panels[0], onlineLp);
        
        // 本地提取面板
        panels[1] = createLocalPanel();
        panels[1].setVisibility(View.GONE);
        LinearLayout.LayoutParams localLp = new LinearLayout.LayoutParams(-1, -2);
        localLp.setMargins(0, 0, 0, dp(12));
        content.addView(panels[1], localLp);
        
        // 在线提取日志框（独立液态玻璃面板）- 放在分区列表前面
        onlineLogPanelRef = createOnlineLogPanel();
        LinearLayout.LayoutParams onlineLogLp = new LinearLayout.LayoutParams(-1, -2);
        onlineLogLp.setMargins(0, dp(12), 0, dp(12));
        content.addView(onlineLogPanelRef, onlineLogLp);
        
        // 在线分区列表
        onlinePartitionsList = new LinearLayout(this);
        onlinePartitionsList.setOrientation(LinearLayout.VERTICAL);
        content.addView(onlinePartitionsList, new LinearLayout.LayoutParams(-1, -2));
        
        // 本地提取日志框（独立液态玻璃面板）- 放在分区列表前面
        localLogPanelRef = createLocalLogPanel();
        LinearLayout.LayoutParams localLogLp = new LinearLayout.LayoutParams(-1, -2);
        localLogLp.setMargins(0, dp(12), 0, dp(12));
        content.addView(localLogPanelRef, localLogLp);
        
        // 本地分区列表
        localPartitionsList = new LinearLayout(this);
        localPartitionsList.setOrientation(LinearLayout.VERTICAL);
        localPartitionsList.setVisibility(View.GONE);
        content.addView(localPartitionsList, new LinearLayout.LayoutParams(-1, -2));
        
        scroll.addView(content);
        root.addView(scroll);
        setContentView(root);
        
        // 初始化液态指示器位置（照搬 vivo）
        tabIndicator.post(() -> {
            moveTabIndicator(0, false);
        });
    }
    
    @Override public void onBackPressed() {
        finish();
        overridePendingTransition(R.anim.slide_up_in, R.anim.slide_up_out);
    }
    
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        Haptics.onTouch(getWindow().getDecorView(), event);
        return super.dispatchTouchEvent(event);
    }
    
    // ========== Tab 手势和动画（完全照搬 vivo）==========
    
    private boolean handleTabGesture(View view, MotionEvent event, int pressedTab) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                tabDragging = false;
                tabDragTarget = pressedTab;
                tabDragStartX = event.getRawX();
                if (tabLongPress != null) mainHandler.removeCallbacks(tabLongPress);
                tabLongPress = () -> {
                    tabDragging = true;
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                    if (tabIndicator != null) tabIndicator.setLiquidPressed(true);
                    previewTabTarget(tabDragStartX);
                };
                mainHandler.postDelayed(tabLongPress, android.view.ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!tabDragging && Math.abs(event.getRawX() - tabDragStartX) >= dp(12)) {
                    tabDragging = true;
                    if (tabLongPress != null) mainHandler.removeCallbacks(tabLongPress);
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                    if (tabIndicator != null) tabIndicator.setLiquidPressed(true);
                }
                if (tabDragging) previewTabTarget(event.getRawX());
                return true;
            case MotionEvent.ACTION_UP:
                if (tabLongPress != null) mainHandler.removeCallbacks(tabLongPress);
                if (tabDragging) {
                    if (tabIndicator != null) tabIndicator.setLiquidPressed(false);
                    switchTab(tabDragTarget < 0 ? pressedTab : tabDragTarget);
                } else {
                    Haptics.perform(view);
                    switchTab(pressedTab);
                }
                tabDragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (tabLongPress != null) mainHandler.removeCallbacks(tabLongPress);
                if (tabIndicator != null) tabIndicator.setLiquidPressed(false);
                tabDragging = false;
                return true;
            default:
                return true;
        }
    }
    
    private void previewTabTarget(float rawX) {
        if (tabRow == null || tabRow.getChildCount() == 0) return;
        int[] location = new int[2];
        tabRow.getLocationOnScreen(location);
        float relativeX = rawX - location[0];
        int targetIndex = -1;
        for (int i = 0; i < tabRow.getChildCount(); i++) {
            View child = tabRow.getChildAt(i);
            if (relativeX >= child.getLeft() && relativeX < child.getRight()) {
                targetIndex = i;
                break;
            }
        }
        if (targetIndex >= 0 && targetIndex != tabDragTarget) {
            tabDragTarget = targetIndex;
            moveTabIndicator(targetIndex, false);
            for (int i = 0; i < typeTabs.length; i++) {
                typeTabs[i].setTextColor(i == targetIndex ? 0xff17334f : 0xff5a6b82);
            }
        }
    }
    
    private void switchTab(int index) {
        currentTab = index;
        for (int i = 0; i < typeTabs.length; i++) {
            boolean selected = i == index;
            // vivo 样式：alpha + typeface(bold) + 文字颜色
            typeTabs[i].setAlpha(selected ? 1f : 0.55f);
            typeTabs[i].setTypeface(null, selected ? 1 : 0);
            typeTabs[i].setTextColor(selected ? 0xff17334f : 0xff5a6b82);
            panels[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            
            // 每个按钮独立的文字缩放动画
            animateButtonScale(typeTabs[i], selected);
        }
        
        moveTabIndicator(index, true);
        
        // 切换分区列表显示
        onlinePartitionsList.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        localPartitionsList.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        // 日志框保持当前状态，不在切换Tab时改变
        if (onlineLogPanelRef != null && index != 0) {
            onlineLogPanelRef.setVisibility(View.GONE);
        }
        if (localLogPanelRef != null && index != 1) {
            localLogPanelRef.setVisibility(View.GONE);
        }
        
        status.setText(index == 0 ? "请输入 URL 开始在线提取" : "请选择文件开始本地提取");
    }
    
    // 每个按钮独立的文字缩放动画
    private void animateButtonScale(Button button, boolean selected) {
        float fromScale = button.getScaleX();
        float toScale = selected ? 1.08f : 1.0f;
        ValueAnimator scaleAnim = ValueAnimator.ofFloat(fromScale, toScale);
        scaleAnim.setDuration(280);
        scaleAnim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        scaleAnim.addUpdateListener(a -> {
            float scale = (Float) a.getAnimatedValue();
            button.setScaleX(scale);
            button.setScaleY(scale);
        });
        scaleAnim.start();
    }
    
    private void moveTabIndicator(int index, boolean animated) {
        if (tabIndicator == null || tabItems == null || tabItems.getChildCount() == 0) return;
        
        // 切换前先停止呼吸动画
        stopIndicatorBreathing();
        
        View target = tabItems.getChildAt(index);
        int width = dp(78);
        int targetLeft = tabItems.getLeft() + target.getLeft() + (target.getWidth() - width) / 2;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tabIndicator.getLayoutParams();
        lp.width = width;
        lp.height = dp(46);
        lp.leftMargin = tabIndicatorLeft < 0 ? targetLeft : tabIndicatorLeft;
        lp.topMargin = dp(9);
        tabIndicator.setLayoutParams(lp);
        if (!animated || tabIndicatorLeft < 0) { 
            tabIndicatorLeft = targetLeft; 
            lp.leftMargin = targetLeft; 
            tabIndicator.setLayoutParams(lp);
            startIndicatorBreathing(); // 启动呼吸动画
            return; 
        }
        int previous = tabIndicatorLeft;
        tabIndicatorLeft = targetLeft;
        ValueAnimator flow = ValueAnimator.ofFloat(0f, 1f);
        flow.setDuration(620);
        flow.setInterpolator(new android.view.animation.PathInterpolator(.18f, .78f, .22f, 1f));
        flow.addUpdateListener(a -> {
            float p = (Float) a.getAnimatedValue();
            float stretch = p < .24f ? p / .24f : p < .48f ? 1f : p < .80f ? 1f - (p - .48f) / .32f : 0f;
            float settle = p < .80f ? 0f : (p - .80f) / .20f;
            float travel = p < .43f ? 0f : (p - .43f) / .57f;
            tabIndicator.setTranslationX((targetLeft - previous) * travel);
            tabIndicator.setScaleX(1f + 1.18f * stretch + .07f * settle);
            tabIndicator.setScaleY(1f - .07f * stretch + .025f * settle);
        });
        flow.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                FrameLayout.LayoutParams settled = (FrameLayout.LayoutParams) tabIndicator.getLayoutParams();
                settled.leftMargin = targetLeft;
                tabIndicator.setTranslationX(0f);
                tabIndicator.setScaleX(1f);
                tabIndicator.setScaleY(1f);
                tabIndicator.setLayoutParams(settled);
                startIndicatorBreathing(); // 动画结束后启动呼吸动画
            }
        });
        pulseTabNavigation(); // 触发 Tab 文字跟随收缩动画
        flow.start();
    }
    
    private void pulseTabNavigation() {
        if (tabItems == null) return;
        if (tabPulse != null) tabPulse.cancel();
        tabItems.setPivotX(tabItems.getWidth() / 2f);
        tabItems.setPivotY(tabItems.getHeight() / 2f);
        tabPulse = ValueAnimator.ofFloat(0f, 1f);
        tabPulse.setDuration(840);
        tabPulse.setInterpolator(new android.view.animation.PathInterpolator(.22f, .76f, .26f, 1f));
        tabPulse.addUpdateListener(animation -> {
            float progress = (Float) animation.getAnimatedValue();
            float swell = progress < .28f ? progress / .28f : progress < .55f ? 1f : 1f - (progress - .55f) / .45f;
            tabItems.setScaleX(1f + .032f * swell);
            tabItems.setScaleY(1f + .064f * swell);
        });
        tabPulse.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (animation != tabPulse) return;
                tabItems.setScaleX(1f);
                tabItems.setScaleY(1f);
                tabPulse = null;
            }
        });
        tabPulse.start();
    }
    
    private void startIndicatorBreathing() {
        if (breathingAnimator != null) breathingAnimator.cancel();
        if (tabIndicator == null) return;
        
        breathingAnimator = ValueAnimator.ofFloat(0.85f, 1.0f);
        breathingAnimator.setDuration(1200);
        breathingAnimator.setRepeatMode(ValueAnimator.REVERSE);
        breathingAnimator.setRepeatCount(ValueAnimator.INFINITE);
        breathingAnimator.addUpdateListener(a -> {
            if (tabIndicator != null) {
                tabIndicator.setAlpha((Float) a.getAnimatedValue());
            }
        });
        breathingAnimator.start();
    }
    
    private void stopIndicatorBreathing() {
        if (breathingAnimator != null) {
            breathingAnimator.cancel();
            breathingAnimator = null;
        }
        if (tabIndicator != null) tabIndicator.setAlpha(1.0f);
    }
    
    // ========== Tab 方法结束 ==========
    
    private LinearLayout createOnlinePanel() {
        LiquidGlassPanel panel = new LiquidGlassPanel(this, 20f);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(18), dp(20), dp(18));
        
        TextView title = new TextView(this);
        title.setText("在线提取");
        title.setTextSize(18);
        title.setTextColor(0xff0f1e36);
        title.setTypeface(null, 1);
        panel.addView(title, new LinearLayout.LayoutParams(-1, dp(44)));
        
        TextView hint = new TextView(this);
        hint.setText("输入 OTA ZIP 包的 HTTP/HTTPS URL");
        hint.setTextSize(13);
        hint.setTextColor(0xff394b5e);
        panel.addView(hint);
        
        onlineUrlInput = new EditText(this);
        onlineUrlInput.setHint("https://example.com/ota.zip");
        onlineUrlInput.setTextSize(14);
        onlineUrlInput.setTextColor(0xff17334f);
        onlineUrlInput.setHintTextColor(0xff8a9aad);
        onlineUrlInput.setBackgroundResource(R.drawable.dark_liquid_glass);
        onlineUrlInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        onlineUrlInput.setMinHeight(dp(120));
        onlineUrlInput.setMaxLines(6);
        onlineUrlInput.setGravity(Gravity.TOP | Gravity.LEFT);
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(-1, -2);
        urlLp.setMargins(0, dp(12), 0, dp(16));
        panel.addView(onlineUrlInput, urlLp);
        
        onlineExtractButton = new Button(this);
        onlineExtractButton.setText("开始在线提取");
        onlineExtractButton.setTextSize(15);
        onlineExtractButton.setTextColor(Color.WHITE);
        onlineExtractButton.setBackgroundResource(R.drawable.button_blue);
        onlineExtractButton.setAllCaps(false);
        onlineExtractButton.setOnClickListener(v -> {
            Haptics.perform(v);
            startOnlineExtract();
        });
        panel.addView(onlineExtractButton, new LinearLayout.LayoutParams(-1, dp(54)));

        // v3.40.13：在线全选提取（整包仅下载一次，再逐分区提取 —— 单分区流式提取每分区都要重新下载）
        onlineExtractAllButton = new Button(this);
        onlineExtractAllButton.setText("⚡ 全选提取（下载整包一次提取全部）");
        onlineExtractAllButton.setTextSize(15);
        onlineExtractAllButton.setTextColor(Color.WHITE);
        onlineExtractAllButton.setBackgroundResource(R.drawable.button_green);
        onlineExtractAllButton.setAllCaps(false);
        onlineExtractAllButton.setOnClickListener(v -> {
            Haptics.perform(v);
            extractAllOnline();
        });
        LinearLayout.LayoutParams onlineAllLp = new LinearLayout.LayoutParams(-1, dp(54));
        onlineAllLp.topMargin = dp(10);
        panel.addView(onlineExtractAllButton, onlineAllLp);

        return panel;
    }
    
    private LiquidGlassPanel createOnlineLogPanel() {
        // 独立的日志框液态玻璃面板
        LiquidGlassPanel logPanel = new LiquidGlassPanel(this, 16f);
        logPanel.setOrientation(LinearLayout.VERTICAL);
        logPanel.setPadding(dp(14), dp(12), dp(14), dp(12));
        logPanel.setVisibility(View.GONE);
        
        // 标题和复制按钮
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        
        TextView logTitle = new TextView(this);
        logTitle.setText("操作日志");
        logTitle.setTextSize(12);
        logTitle.setTextColor(0xff17334f);
        logTitle.setTypeface(null, 1);
        headerRow.addView(logTitle, new LinearLayout.LayoutParams(0, -2, 1));
        
        Button copyAllButton = new Button(this);
        copyAllButton.setText("复制全部");
        copyAllButton.setTextSize(11);
        copyAllButton.setTextColor(0xff0891b2);
        copyAllButton.setBackgroundColor(0);
        copyAllButton.setAllCaps(false);
        copyAllButton.setPadding(dp(8), 0, 0, 0);
        copyAllButton.setMinHeight(0);
        copyAllButton.setMinWidth(0);
        copyAllButton.setOnClickListener(v -> {
            Haptics.perform(v);
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("日志", onlineLogDisplay.getText());
            clipboard.setPrimaryClip(clip);
            toast("全部日志已复制到剪贴板");
        });
        headerRow.addView(copyAllButton, new LinearLayout.LayoutParams(-2, -2));
        
        logPanel.addView(headerRow, new LinearLayout.LayoutParams(-1, -2));
        
        // 使用 ScrollView + TextView 方案
        // v3.41.11：BoundedScrollView（OTG 触摸模型，整条祖先链独占）替换裸 ScrollView
        onlineLogScroll = new DnaActivity.BoundedScrollView(this, 0);
        onlineLogScroll.setVerticalScrollBarEnabled(true);
        onlineLogScroll.setScrollbarFadingEnabled(false);
        onlineLogScroll.setFillViewport(false);
        
        onlineLogDisplay = new TextView(this);
        onlineLogDisplay.setText("");
        onlineLogDisplay.setTextSize(10);
        onlineLogDisplay.setTextColor(0xffdc2626); // 红色
        onlineLogDisplay.setTypeface(android.graphics.Typeface.MONOSPACE);
        onlineLogDisplay.setBackgroundColor(0);
        onlineLogDisplay.setPadding(dp(8), dp(8), dp(8), dp(8));
        
        onlineLogScroll.addView(onlineLogDisplay, new ScrollView.LayoutParams(-1, -2));
        
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(-1, dp(180));
        logLp.setMargins(0, dp(8), 0, 0);
        logPanel.addView(onlineLogScroll, logLp);
        
        return logPanel;
    }
    
    private LinearLayout createLocalPanel() {
        LiquidGlassPanel panel = new LiquidGlassPanel(this, 20f);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(18), dp(20), dp(18));
        
        TextView title = new TextView(this);
        title.setText("本地提取");
        title.setTextSize(18);
        title.setTextColor(0xff0f1e36);
        title.setTypeface(null, 1);
        panel.addView(title, new LinearLayout.LayoutParams(-1, dp(44)));
        
        TextView hint = new TextView(this);
        hint.setText("选择本地 payload.bin 或 OTA ZIP 文件");
        hint.setTextSize(13);
        hint.setTextColor(0xff394b5e);
        panel.addView(hint);
        
        // 显示选择的文件名和路径（可滚动）
        // v3.41.11：BoundedScrollView（OTG 触摸模型）——dispatchTouchEvent 保证
        // setTextIsSelectable(true) 的 TextView 消费触摸时拦截权仍生效
        ScrollView fileInfoScroll = new DnaActivity.BoundedScrollView(this, 0);
        fileInfoScroll.setBackgroundResource(R.drawable.dark_liquid_glass);
        
        localFileNameDisplay = new TextView(this);
        localFileNameDisplay.setText("未选择文件");
        localFileNameDisplay.setTextSize(12);
        localFileNameDisplay.setTextColor(0xff5a6a7f);
        localFileNameDisplay.setPadding(dp(14), dp(12), dp(14), dp(12));
        localFileNameDisplay.setTextIsSelectable(true);
        fileInfoScroll.addView(localFileNameDisplay);
        
        LinearLayout.LayoutParams fileInfoLp = new LinearLayout.LayoutParams(-1, dp(100));
        fileInfoLp.setMargins(0, dp(12), 0, dp(16));
        panel.addView(fileInfoScroll, fileInfoLp);
        
        localPickButton = new Button(this);
        localPickButton.setText("选择文件");
        localPickButton.setTextSize(15);
        localPickButton.setTextColor(Color.WHITE);
        localPickButton.setBackgroundResource(R.drawable.button_teal);
        localPickButton.setAllCaps(false);
        localPickButton.setOnClickListener(v -> {
            Haptics.perform(v);
            pickFile();
        });
        panel.addView(localPickButton, new LinearLayout.LayoutParams(-1, dp(54)));

        // v3.40.13：本地全选提取（root payload_dumper 逐分区，无权限问题）
        localExtractAllButton = new Button(this);
        localExtractAllButton.setText("⚡ 全选提取（已列出分区全部提取）");
        localExtractAllButton.setTextSize(15);
        localExtractAllButton.setTextColor(Color.WHITE);
        localExtractAllButton.setBackgroundResource(R.drawable.button_green);
        localExtractAllButton.setAllCaps(false);
        localExtractAllButton.setOnClickListener(v -> {
            Haptics.perform(v);
            extractAllLocal();
        });
        LinearLayout.LayoutParams localAllLp = new LinearLayout.LayoutParams(-1, dp(54));
        localAllLp.topMargin = dp(10);
        panel.addView(localExtractAllButton, localAllLp);

        return panel;
    }
    
    private LiquidGlassPanel createLocalLogPanel() {
        // 独立的日志框液态玻璃面板
        LiquidGlassPanel logPanel = new LiquidGlassPanel(this, 16f);
        logPanel.setOrientation(LinearLayout.VERTICAL);
        logPanel.setPadding(dp(14), dp(12), dp(14), dp(12));
        logPanel.setVisibility(View.GONE);
        
        // 标题和复制按钮
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        
        TextView logTitle = new TextView(this);
        logTitle.setText("操作日志");
        logTitle.setTextSize(12);
        logTitle.setTextColor(0xff17334f);
        logTitle.setTypeface(null, 1);
        headerRow.addView(logTitle, new LinearLayout.LayoutParams(0, -2, 1));
        
        Button copyAllButton = new Button(this);
        copyAllButton.setText("复制全部");
        copyAllButton.setTextSize(11);
        copyAllButton.setTextColor(0xff0891b2);
        copyAllButton.setBackgroundColor(0);
        copyAllButton.setAllCaps(false);
        copyAllButton.setPadding(dp(8), 0, 0, 0);
        copyAllButton.setMinHeight(0);
        copyAllButton.setMinWidth(0);
        copyAllButton.setOnClickListener(v -> {
            Haptics.perform(v);
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("日志", localLogDisplay.getText());
            clipboard.setPrimaryClip(clip);
            toast("全部日志已复制到剪贴板");
        });
        headerRow.addView(copyAllButton, new LinearLayout.LayoutParams(-2, -2));
        
        logPanel.addView(headerRow, new LinearLayout.LayoutParams(-1, -2));
        
        // 使用 ScrollView + TextView 方案
        // v3.41.11：BoundedScrollView（OTG 触摸模型，整条祖先链独占）替换裸 ScrollView
        localLogScroll = new DnaActivity.BoundedScrollView(this, 0);
        localLogScroll.setVerticalScrollBarEnabled(true);
        localLogScroll.setScrollbarFadingEnabled(false);
        localLogScroll.setFillViewport(false);
        
        localLogDisplay = new TextView(this);
        localLogDisplay.setText("");
        localLogDisplay.setTextSize(10);
        localLogDisplay.setTextColor(0xffdc2626); // 红色
        localLogDisplay.setTypeface(android.graphics.Typeface.MONOSPACE);
        localLogDisplay.setBackgroundColor(0);
        localLogDisplay.setPadding(dp(8), dp(8), dp(8), dp(8));
        
        localLogScroll.addView(localLogDisplay, new ScrollView.LayoutParams(-1, -2));
        
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(-1, dp(180));
        logLp.setMargins(0, dp(8), 0, 0);
        logPanel.addView(localLogScroll, logLp);
        
        return logPanel;
    }

    private void startOnlineExtract() {
        String url = onlineUrlInput.getText().toString().trim();
        if (url.isEmpty()) {
            toast("请输入 URL");
            return;
        }
        
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast("URL 必须以 http:// 或 https:// 开头");
            return;
        }
        
        status.setText("正在解析...");
        onlinePartitionsList.removeAllViews();
        onlineExtractButton.setEnabled(false);
        onlineLogDisplay.setText("");
        
        currentInput = url;
        selectedFileName = getFileNameFromUrl(url);
        
        logOnline("开始解析 URL: " + url);
        logOnline("文件名: " + selectedFileName);
        
        parsePayloadOnline();
    }
    
    private void logOnline(String msg) {
        mainHandler.post(() -> {
            String current = onlineLogDisplay.getText().toString();
            String timestamp = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date());
            onlineLogDisplay.setText(current + "\n[" + timestamp + "] " + msg);
            if (onlineLogPanelRef != null) {
                onlineLogPanelRef.setVisibility(View.VISIBLE);
            }
            // 自动滚动到底部
            onlineLogScroll.post(() -> onlineLogScroll.fullScroll(View.FOCUS_DOWN));
        });
    }
    
    private void logLocal(String msg) {
        mainHandler.post(() -> {
            String current = localLogDisplay.getText().toString();
            String timestamp = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date());
            localLogDisplay.setText(current + "\n[" + timestamp + "] " + msg);
            if (localLogPanelRef != null) {
                localLogPanelRef.setVisibility(View.VISIBLE);
            }
            // 自动滚动到底部
            localLogScroll.post(() -> localLogScroll.fullScroll(View.FOCUS_DOWN));
        });
    }
    
    private String getFileNameFromUrl(String url) {
        try {
            String fileName = url.substring(url.lastIndexOf('/') + 1);
            if (fileName.contains("?")) {
                fileName = fileName.substring(0, fileName.indexOf('?'));
            }
            if (fileName.endsWith(".zip")) {
                return fileName.substring(0, fileName.length() - 4);
            }
            return fileName;
        } catch (Exception e) {
            return "online_ota";
        }
    }
    
    private void pickFile() {
        // v3.30.15：内置文件浏览器替换系统 SAF（真实路径直接用，无需 getRealPath 中转）
        FileBrowserDialog.show(this, "选择 payload.bin / OTA zip",
                new String[]{".bin", ".zip", ".zip2"}, "/storage/emulated/0",
                path -> handleLocalFilePath(path));
    }

    /** v3.30.15：内置文件浏览器选中 —— 真实绝对路径（root 可读；PayloadExtractor 直接打开） */
    private void handleLocalFilePath(String path) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        selectedFileName = fileName;
        localFileNameDisplay.setText("已选择: " + fileName + "\n\n文件路径: " + path);
        localFileNameDisplay.setTextColor(0xff17334f);
        localLogDisplay.setText("");
        logLocal("已选择文件: " + fileName);
        status.setText("正在处理文件...");
        executor.execute(() -> {
            try {
                logLocal("正在处理文件...");
                closeOpenedFd();
                currentInput = path;
                logLocal("文件路径: " + path);
                mainHandler.post(() -> {
                    status.setText("正在解析...");
                    localPartitionsList.removeAllViews();
                });
                Thread.sleep(500);
                logLocal("正在打开 Payload...");
                if (extractor != null) {
                    extractor.close();
                }
                // v3.40.17 解析四级链（root 属主文件彻底修复）：
                // ① root 放行底层真实路径（FUSE 视图 chmod/chown 对 root 属主文件无效，
                //    必须操作 /data/media/0/...；放行后 app/JNI 可直读原路径）
                // ② Java 直读 manifest（app 属主/已放行文件毫秒级；zip 非 CrAU 头自动跳过）
                // ③ payload_dumper --list（root，bin/zip 直读原路径，root 属主也能读）
                // ④ JNI 兜底：resolveJniReadable（放行成功直读原路径；彻底失败才复制缓存，
                //    副本进程级 memo 共享——后续提取不再二次复制）
                originalLocalPath = path;
                DnaTools.rootRelaxForApp(path, msg -> { logLocal(msg); return kotlin.Unit.INSTANCE; });
                List<PayloadExtractor.PartitionInfo> partitions = PayloadExtractor.fastListPartitions(path);
                if (partitions == null || partitions.isEmpty()) {
                    logLocal("… 改用 payload_dumper 解析 ...");
                    DnaTools.Result r = DnaTools.run(PayloadDumperActivity.this,
                            DnaTools.quote(dumperPath()) + " --list " + DnaTools.quote(path),
                            line -> kotlin.Unit.INSTANCE,
                            () -> false, 120000);
                    if (r.getSuccess()) partitions = parseDumperList(r.getOutput());
                }
                if (partitions == null || partitions.isEmpty()) {
                    logLocal("… 改用 JNI 解析 ...");
                    String jniInput = DnaTools.resolveJniReadable(PayloadDumperActivity.this, path,
                            msg -> { logLocal(msg); return kotlin.Unit.INSTANCE; });
                    boolean ok = false;
                    if (jniInput != null) {
                        currentInput = jniInput;
                        logLocal("文件路径: " + currentInput);
                        extractor = new PayloadExtractor();
                        ok = extractor.open(jniInput);
                    }
                    if (!ok) {
                        logLocal("错误: 无法打开文件");
                        mainHandler.post(() -> {
                            status.setText("打开失败");
                            toast("无法打开文件");
                        });
                        return;
                    }
                    partitions = extractor.listPartitions(true);
                } else {
                    // 快速路径成功：提取走 JNI（extractPartition 自带 input 参数），无需句柄
                    extractor = new PayloadExtractor();
                }
                logLocal("成功，找到 " + partitions.size() + " 个分区");
                final List<PayloadExtractor.PartitionInfo> fParts = partitions;
                mainHandler.post(() -> {
                    displayPartitionsLocal(fParts);
                    status.setText("已解析 " + fParts.size() + " 个分区");
                });
            } catch (Exception e) {
                logLocal("异常: " + e.getClass().getSimpleName());
                logLocal("消息: " + e.getMessage());
                android.util.Log.e("PayloadDumper", "处理文件失败", e);
                mainHandler.post(() -> {
                    status.setText("处理失败");
                    toast("处理文件失败: " + e.getMessage());
                });
            }
        });
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                // 获取文件名
                String fileName = getFileNameFromUri(uri);
                selectedFileName = fileName;
                
                // 获取真实路径用于显示
                String displayPath = getReadablePathFromUri(uri);
                
                // 更新显示：已选择 + 文件路径
                localFileNameDisplay.setText("已选择: " + fileName + "\n\n文件路径: " + displayPath);
                localFileNameDisplay.setTextColor(0xff17334f);
                
                // 清空日志但不隐藏日志框
                localLogDisplay.setText("");
                
                logLocal("已选择文件: " + fileName);
                
                // 在后台线程处理文件
                status.setText("正在处理文件...");
                executor.execute(() -> {
                    try {
                        logLocal("正在处理文件...");
                        
                        // 关闭之前的 fd（如果有）
                        closeOpenedFd();
                        
                        String path = getRealPath(uri);
                        if (path == null) {
                            logLocal("错误: 无法获取文件路径");
                            mainHandler.post(() -> toast("无法获取文件路径"));
                            return;
                        }
                        
                        currentInput = path;
                        // v3.40.17：SAF 链路同样记录原始路径（提取走 JNI memo，防浏览器/SAF
                        // 混用时 originalLocalPath 残留上一个文件）
                        originalLocalPath = path;
                        logLocal("文件路径: " + path);
                        mainHandler.post(() -> {
                            status.setText("正在解析...");
                            localPartitionsList.removeAllViews();
                        });

                        Thread.sleep(500);

                        logLocal("正在打开 Payload...");
                        if (extractor != null) {
                            extractor.close();
                        }

                        // v3.40.17：root 属主文件先放行底层真实路径（幂等，已可读则零开销）
                        DnaTools.rootRelaxForApp(path, msg -> { logLocal(msg); return kotlin.Unit.INSTANCE; });
                        extractor = new PayloadExtractor();
                        boolean success = extractor.open(path);
                        
                        if (!success) {
                            logLocal("错误: 无法打开文件");
                            mainHandler.post(() -> {
                                status.setText("打开失败");
                                toast("无法打开文件");
                            });
                            return;
                        }
                        
                        logLocal("成功打开，正在列出分区...");
                        List<PayloadExtractor.PartitionInfo> partitions = extractor.listPartitions(true);
                        logLocal("找到 " + partitions.size() + " 个分区");
                        
                        mainHandler.post(() -> {
                            displayPartitionsLocal(partitions);
                            status.setText("已解析 " + partitions.size() + " 个分区");
                        });
                        
                    } catch (Exception e) {
                        logLocal("异常: " + e.getClass().getSimpleName());
                        logLocal("消息: " + e.getMessage());
                        android.util.Log.e("PayloadDumper", "处理文件失败", e);
                        mainHandler.post(() -> {
                            status.setText("处理失败: " + e.getMessage());
                            toast("处理失败: " + e.getMessage());
                        });
                    }
                });
            }
        }
    }
    
    private String getFileNameFromUri(Uri uri) {
        // 优先使用 ContentResolver 查询真实文件名
        if ("content".equals(uri.getScheme())) {
            try {
                android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (nameIndex >= 0) {
                        String displayName = cursor.getString(nameIndex);
                        cursor.close();
                        if (displayName != null && !displayName.isEmpty()) {
                            // 去除扩展名
                            if (displayName.endsWith(".zip")) {
                                return displayName.substring(0, displayName.length() - 4);
                            }
                            return displayName;
                        }
                    }
                    cursor.close();
                }
            } catch (Exception e) {
                android.util.Log.e("PayloadDumper", "获取文件名失败", e);
            }
        }
        
        // 回退到从路径提取
        String path = uri.getPath();
        if (path != null) {
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            if (fileName.endsWith(".zip")) {
                return fileName.substring(0, fileName.length() - 4);
            }
            return fileName;
        }
        return "local_file";
    }
    
    private String getReadablePathFromUri(Uri uri) {
        if (uri == null) return "未知路径";
        
        // 处理 ExternalStorageProvider (content://com.android.externalstorage.documents/...)
        if ("com.android.externalstorage.documents".equals(uri.getAuthority())) {
            String docId = android.provider.DocumentsContract.getDocumentId(uri);
            String[] split = docId.split(":");
            if (split.length >= 2) {
                String type = split[0];
                String path = split[1];
                if ("primary".equalsIgnoreCase(type)) {
                    return "/storage/emulated/0/" + path;
                } else {
                    return "/storage/" + type + "/" + path;
                }
            }
        }
        
        // 处理 MediaStore (content://media/...)
        if ("com.android.providers.media.documents".equals(uri.getAuthority())) {
            try {
                String[] projection = { android.provider.MediaStore.MediaColumns.DATA };
                android.database.Cursor cursor = getContentResolver().query(uri, projection, null, null, null);
                if (cursor != null) {
                    if (cursor.moveToFirst()) {
                        int columnIndex = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA);
                        if (columnIndex >= 0) {
                            String path = cursor.getString(columnIndex);
                            cursor.close();
                            if (path != null) return path;
                        }
                    }
                    cursor.close();
                }
            } catch (Exception e) {
                // 继续尝试其他方法
            }
        }
        
        // 尝试直接查询 _data 列
        try {
            String[] projection = { android.provider.MediaStore.MediaColumns.DATA };
            android.database.Cursor cursor = getContentResolver().query(uri, projection, null, null, null);
            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    int columnIndex = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA);
                    if (columnIndex >= 0) {
                        String path = cursor.getString(columnIndex);
                        cursor.close();
                        if (path != null) return path;
                    }
                }
                cursor.close();
            }
        } catch (Exception e) {
            // 继续
        }
        
        // 回退到显示 URI
        return uri.toString();
    }
    
    private void parsePayloadOnline() {
        executor.execute(() -> {
            try {
                logOnline("正在打开 Payload...");
                
                if (extractor != null) {
                    extractor.close();
                }
                
                extractor = new PayloadExtractor();
                boolean success = extractor.open(currentInput);
                
                if (!success) {
                    logOnline("错误: 无法打开 URL");
                    mainHandler.post(() -> {
                        status.setText("打开失败");
                        toast("无法打开 URL");
                        onlineExtractButton.setEnabled(true);
                    });
                    return;
                }
                
                logOnline("成功打开，正在列出分区...");
                List<PayloadExtractor.PartitionInfo> partitions = extractor.listPartitions(true);
                logOnline("找到 " + partitions.size() + " 个分区");
                
                mainHandler.post(() -> {
                    onlineExtractButton.setEnabled(true);
                    displayPartitionsOnline(partitions);
                    status.setText("已解析 " + partitions.size() + " 个分区");
                });
                
            } catch (Exception e) {
                logOnline("异常: " + e.getClass().getSimpleName());
                logOnline("消息: " + e.getMessage());
                android.util.Log.e("PayloadDumper", "在线解析失败", e);
                mainHandler.post(() -> {
                    status.setText("解析失败: " + e.getMessage());
                    toast("解析失败: " + e.getMessage());
                    onlineExtractButton.setEnabled(true);
                });
            }
        });
    }
    
    private void displayPartitionsOnline(List<PayloadExtractor.PartitionInfo> partitions) {
        onlinePartitionsList.removeAllViews();
        onlinePartitions.clear();
        onlinePartitions.addAll(partitions);
        
        for (int i = 0; i < partitions.size(); i++) {
            PayloadExtractor.PartitionInfo info = partitions.get(i);
            final int partitionId = i;
            
            PartitionItemView item = new PartitionItemView(this);
            item.setPartitionInfo(partitionId, info.getName(), info.getSize(), info.getHash());
            item.setOnExtractListener(new PartitionItemView.OnExtractListener() {
                @Override
                public void onStartExtract(int id, String name) {
                    startExtractPartition(id, name, info.getSize(), false);
                }
                
                @Override
                public void onCancelExtract(int id) {
                    Long token = extractTokens.get(id);
                    if (token != null) {
                        cancelledTokens.put(token, true);
                        logOnline("用户取消提取: " + onlinePartitionsList.getChildAt(id));
                    }
                }
            });
            
            onlinePartitionsList.addView(item, new LinearLayout.LayoutParams(-1, -2));
        }
    }
    
    private void displayPartitionsLocal(List<PayloadExtractor.PartitionInfo> partitions) {
        localPartitionsList.removeAllViews();
        localPartitions.clear();
        localPartitions.addAll(partitions);
        
        for (int i = 0; i < partitions.size(); i++) {
            PayloadExtractor.PartitionInfo info = partitions.get(i);
            final int partitionId = i;
            
            PartitionItemView item = new PartitionItemView(this);
            item.setPartitionInfo(partitionId, info.getName(), info.getSize(), info.getHash());
            item.setOnExtractListener(new PartitionItemView.OnExtractListener() {
                @Override
                public void onStartExtract(int id, String name) {
                    startExtractPartition(id, name, info.getSize(), true);
                }
                
                @Override
                public void onCancelExtract(int id) {
                    Long token = extractTokens.get(id);
                    if (token != null) {
                        cancelledTokens.put(token, true);
                        logLocal("用户取消提取");
                    }
                }
            });
            
            localPartitionsList.addView(item, new LinearLayout.LayoutParams(-1, -2));
        }
    }
    
    private void startExtractPartition(int partitionId, String partitionName, long partitionSizeBytes, boolean isLocal) {
        // 使用之前保存的文件名
        String outputDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            .getAbsolutePath() + "/DsuManager/" + selectedFileName;
        
        File dir = new File(outputDir);
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            android.util.Log.d("PayloadDumper", "创建目录: " + outputDir + ", 结果: " + created);
            if (isLocal) {
                logLocal("创建输出目录: " + (created ? "成功" : "失败"));
            } else {
                logOnline("创建输出目录: " + (created ? "成功" : "失败"));
            }
        }
        
        final String finalOutputPath = outputDir + "/" + partitionName + ".img";
        final long finalPartitionSize = partitionSizeBytes;
        
        long token = tokenGenerator.getAndIncrement();
        extractTokens.put(partitionId, token);
        
        // 获取对应的分区列表视图
        LinearLayout targetList = isLocal ? localPartitionsList : onlinePartitionsList;
        PartitionItemView itemView = (PartitionItemView) targetList.getChildAt(partitionId);
        
        // 显示保存路径
        if (itemView != null) {
            itemView.setOutputPath(finalOutputPath);
        }
        
        // 日志输出
        if (isLocal) {
            logLocal("开始提取: " + partitionName);
            logLocal("输出路径: " + finalOutputPath);
        } else {
            logOnline("开始提取: " + partitionName);
            logOnline("输出路径: " + finalOutputPath);
        }
        
        // 启动提取线程
        executor.execute(() -> {
            try {
                android.util.Log.d("PayloadDumper", "========== 开始提取 ==========");
                android.util.Log.d("PayloadDumper", "分区: " + partitionName);
                android.util.Log.d("PayloadDumper", "输入: " + currentInput);
                android.util.Log.d("PayloadDumper", "输出目录: " + outputDir);

                // v3.42.18：JNI 优先（单分区内部块级 8 线程），失败/URL 回退 root CLI；
                // 进度/结果日志由 extractOnePartition 内部处理
                String rawInput = isLocal && originalLocalPath != null ? originalLocalPath : currentInput;
                boolean ok = extractOnePartition(rawInput, outputDir, partitionName,
                        finalPartitionSize, itemView, isLocal,
                        () -> Boolean.TRUE.equals(cancelledTokens.get(token)), false);
                if (ok) {
                    final String fp = finalOutputPath;
                    mainHandler.post(() ->
                            toast("提取完成: " + partitionName + "\n保存到: " + fp));
                }
            } catch (Exception e) {
                android.util.Log.e("PayloadDumper", "========== 提取失败 ==========");
                android.util.Log.e("PayloadDumper", "分区: " + partitionName, e);

                String errorMsg = e.getMessage();
                if (isLocal) {
                    logLocal("提取失败: " + partitionName + " - " + errorMsg);
                } else {
                    logOnline("提取失败: " + partitionName + " - " + errorMsg);
                }
                final String finalDisplayMsg = errorMsg == null ? "unknown" : errorMsg;
                mainHandler.post(() -> {
                    if (itemView != null) {
                        itemView.setError("提取失败");
                    }
                    toast("提取失败: " + partitionName + "\n" + finalDisplayMsg);
                });
            }
        });
        
        // v3.40.19：旧 JNI 进度监听线程已移除 —— CLI 提取的进度/取消/结果
        // 全部由 extractOnePartition 内部的 stat 轮询统一驱动（本地/在线一致）
    }

    private String getInputFileName() {
        if (currentInput == null) return "payload";
        
        // 从 URL 或文件路径提取文件名（不带扩展名）
        String name;
        if (currentInput.startsWith("http")) {
            // 在线 URL
            String[] parts = currentInput.split("/");
            name = parts[parts.length - 1];
        } else {
            // 本地文件
            name = new File(currentInput).getName();
        }
        
        // 移除扩展名
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex > 0) {
            name = name.substring(0, dotIndex);
        }
        
        return name;
    }
    
    private android.os.ParcelFileDescriptor openedFd = null;
    
    private String getRealPath(Uri uri) {
        if ("content".equals(uri.getScheme())) {
            // 方案1: 优先尝试获取真实路径
            try {
                android.database.Cursor cursor = getContentResolver().query(uri, 
                    new String[]{android.provider.MediaStore.MediaColumns.DATA}, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int columnIndex = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA);
                    if (columnIndex >= 0) {
                        String path = cursor.getString(columnIndex);
                        cursor.close();
                        if (path != null && new File(path).exists()) {
                            android.util.Log.d("PayloadDumper", "使用真实路径: " + path);
                            logLocal("直接访问真实路径");
                            return path;
                        }
                    }
                    cursor.close();
                }
            } catch (Exception e) {
                android.util.Log.e("PayloadDumper", "获取真实路径失败", e);
            }
            
            // 方案2: 使用 /proc/self/fd/ 访问（不复制文件）
            logLocal("使用文件描述符访问（零复制）");
            try {
                // 关闭之前的 fd
                if (openedFd != null) {
                    openedFd.close();
                    openedFd = null;
                }
                
                android.os.ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r");
                if (pfd == null) {
                    throw new Exception("无法打开文件描述符");
                }
                
                openedFd = pfd;  // 保存 fd，不关闭
                int fd = pfd.getFd();
                String fdPath = "/proc/self/fd/" + fd;
                
                android.util.Log.d("PayloadDumper", "使用 fd 路径: " + fdPath);
                logLocal("成功: fd=" + fd);
                return fdPath;
            } catch (Exception e) {
                android.util.Log.e("PayloadDumper", "fd 访问失败", e);
                logLocal("失败: " + e.getMessage());
                mainHandler.post(() -> toast("无法访问文件: " + e.getMessage()));
                return null;
            }
        } else if ("file".equals(uri.getScheme())) {
            return uri.getPath();
        }
        return uri.toString();
    }
    
    // ================= v3.40.13：全选提取（在线/本地共用） =================

    /** nativeLibraryDir 下的 Rust 版 payload_dumper（root 链路，bin/zip 直读，无 FUSE 权限问题） */
    private String dumperPath() {
        return new File(getApplicationInfo().nativeLibraryDir, "libpayload_dumper.so").getAbsolutePath();
    }

    /** 解析 payload_dumper --list 表格输出：「boot<空白>1.00 MB」每行一个分区（同 DnaBinActivity） */
    private static List<PayloadExtractor.PartitionInfo> parseDumperList(String output) {
        List<PayloadExtractor.PartitionInfo> out = new java.util.ArrayList<>();
        if (output == null) return out;
        for (String raw : output.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("Partition Name") || line.startsWith("---")) continue;
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
            String u = m.group(2).toUpperCase(java.util.Locale.ROOT);
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

    private void extractAllLocal() {
        if (extractAllRunning.get()) { toast("全选提取进行中"); return; }
        if (localPartitions.isEmpty() || (currentInput == null && originalLocalPath == null)) {
            toast("请先选择文件并解析出分区列表");
            return;
        }
        // v3.40.14：输入优先原始路径（root dumper 直读，哪怕解析走了缓存兜底）
        String input = originalLocalPath != null ? originalLocalPath : currentInput;
        runExtractAll(input, true, new java.util.ArrayList<>(localPartitions));
    }

    private void extractAllOnline() {
        if (extractAllRunning.get()) { toast("全选提取进行中"); return; }
        String url = onlineUrlInput.getText().toString().trim();
        if (url.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            toast("请输入 OTA ZIP 的 URL");
            return;
        }
        selectedFileName = getFileNameFromUrl(url);
        final String fUrl = url;
        if (!extractAllRunning.compareAndSet(false, true)) return;
        extractAllCancel = false;
        setExtractAllUi(true, false);
        logOnline("⚡ 全选提取: 整包只下载一次，再逐分区提取");
        logOnline("URL: " + fUrl);
        executor.execute(() -> {
            File zip = null;
            try {
                status.setText("正在下载整包...");
                zip = downloadOnlineZip(fUrl);
                if (zip == null || extractAllCancel) {
                    throw new IllegalStateException("整包下载失败");
                }
                // 下载完成 → 本地解析（缓存文件 app 自有，JNI 直读）
                status.setText("正在解析...");
                logOnline("下载完成，正在解析分区...");
                if (extractor != null) { try { extractor.close(); } catch (Exception ignored) {} }
                extractor = new PayloadExtractor();
                if (!extractor.open(zip.getAbsolutePath())) {
                    throw new IllegalStateException("无法打开下载的 OTA 包（可能非 OTA ZIP）");
                }
                List<PayloadExtractor.PartitionInfo> parts = extractor.listPartitions(true);
                if (parts.isEmpty()) throw new IllegalStateException("未找到分区");
                logOnline("找到 " + parts.size() + " 个分区，开始逐分区提取");
                mainHandler.post(() -> displayPartitionsOnline(parts));
                Thread.sleep(300);   // 等列表渲染，分区行视图就位
                runExtractAllCore(zip.getAbsolutePath(), false, parts);
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? "unknown" : e.getMessage();
                logOnline("✗ 全选提取失败: " + msg);
                mainHandler.post(() -> { status.setText("全选提取失败"); toast("全选提取失败: " + msg); });
            } finally {
                if (zip != null) {
                    //noinspection ResultOfMethodCallIgnored
                    zip.delete();   // 多 GB 缓存包，用完即清
                    logOnline("已清理缓存的整包文件");
                }
                extractAllRunning.set(false);
                mainHandler.post(() -> setExtractAllUi(false, false));
            }
        });
    }

    /** 共用入口（本地/在线全选）：校验 + 包一层运行状态 */
    private void runExtractAll(String input, boolean isLocal, List<PayloadExtractor.PartitionInfo> parts) {
        if (!extractAllRunning.compareAndSet(false, true)) return;
        extractAllCancel = false;
        setExtractAllUi(true, isLocal);
        log(isLocal, "⚡ 全选提取: " + parts.size() + " 个分区（JNI 多线程引擎，CLI 兜底）");
        executor.execute(() -> {
            try {
                // v3.42.18：JNI 优先（ensureJniReadable 放行直读零复制），CLI 批量兜底
                runExtractAllCore(input, isLocal, parts);
            } finally {
                extractAllRunning.set(false);
                mainHandler.post(() -> setExtractAllUi(false, isLocal));
            }
        });
    }

    /** 运行期间按钮 ↔ 取消语义切换 */
    private void setExtractAllUi(boolean running, boolean local) {
        Button btn = local ? localExtractAllButton : onlineExtractAllButton;
        Button other = local ? onlineExtractAllButton : localExtractAllButton;
        if (btn != null) {
            btn.setText(running ? "■ 取消全选提取" : (local
                    ? "⚡ 全选提取（已列出分区全部提取）"
                    : "⚡ 全选提取（下载整包一次提取全部）"));
        }
        if (other != null) other.setEnabled(!running);
    }

    /**
     * v3.42.18：全选提取 —— JNI 优先（单分区内部块级 8 线程，对齐 DnaBin v3.42.17
     * 实测 OPPO 67 分区 76s / vivo 61 分区 16s），JNI 整体不可用或全军覆没才回退
     * CLI 批量（v3.42.15 的逗号拼接批量，保留作兜底）。
     * 在线全选：整包下载到 cache 后走本地路径，同样适用 JNI。
     */
    private void runExtractAllCore(String input, boolean isLocal, List<PayloadExtractor.PartitionInfo> parts) {
        String outputDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                .getAbsolutePath() + "/DsuManager/" + selectedFileName;
        File dir = new File(outputDir);
        if (!dir.exists() && !dir.mkdirs()) {
            log(isLocal, "✗ 无法创建输出目录: " + outputDir);
            mainHandler.post(() -> { status.setText("全选提取失败"); toast("无法创建输出目录"); });
            return;
        }
        log(isLocal, "输出目录: " + outputDir);
        LinearLayout targetList = isLocal ? localPartitionsList : onlinePartitionsList;
        final long startMs = System.currentTimeMillis();
        final int totalParts = parts.size();
        final String[] names = new String[totalParts];
        final long[] expected = new long[totalParts];
        final PartitionItemView[] views = new PartitionItemView[totalParts];
        final File[] outFiles = new File[totalParts];
        StringBuilder rmAll = new StringBuilder();
        for (int i = 0; i < totalParts; i++) {
            PayloadExtractor.PartitionInfo info = parts.get(i);
            names[i] = info.getName();
            expected[i] = info.getSize();
            views[i] = (PartitionItemView) targetList.getChildAt(i);
            outFiles[i] = new File(outputDir, names[i] + ".img");
            rmAll.append("rm -f ").append(DnaTools.quote(outFiles[i].getAbsolutePath())).append("; ");
            final PartitionItemView iv = views[i];
            final File of = outFiles[i];
            mainHandler.post(() -> {
                if (iv != null) { iv.setOutputPath(of.getAbsolutePath()); iv.setProgress(0, 0, 1); }
            });
        }
        com.topjohnwu.superuser.Shell.cmd(rmAll + "true").exec();
        // ---- JNI 主路径 ----
        final boolean isUrl = input.startsWith("http://") || input.startsWith("https://");
        String jniInput = null;
        if (!isUrl) {
            jniInput = DnaTools.ensureJniReadable(PayloadDumperActivity.this, input,
                    msg -> { log(isLocal, msg); return kotlin.Unit.INSTANCE; });
        }
        if (jniInput != null) {
            int r = runExtractAllJni(input, jniInput, isLocal, outputDir, totalParts,
                    names, expected, views, outFiles, startMs);
            if (r >= 0) return;   // JNI 路径已完成（含失败分区 CLI 重试与结尾统计）
            log(isLocal, "… JNI 全部失败，回退 CLI 批量重试 ...");
        }
        // ---- CLI 批量兜底 ----
        runExtractAllCliBatch(input, isLocal, outputDir, totalParts, names, expected, views, outFiles, startMs);
    }

    /**
     * v3.42.18：全选 JNI 逐分区提取（单分区内部 8 线程）+ 失败分区 CLI 单分区重试。
     * 取消即时响应：等待循环检测 extractAllCancel 立即退出并 root rm 半成品
     * （JNI 无中断接口，后台 native 跑完写已 unlink 的 fd，不占可见空间）。
     * @return 成功分区数；-1 = JNI 全军覆没（调用方应回退 CLI 批量）
     */
    private int runExtractAllJni(String rawInput, String jniInput, boolean isLocal, String outputDir,
                                 int totalParts, String[] names, long[] expected,
                                 PartitionItemView[] views, File[] outFiles, long startMs) {
        log(isLocal, "⚡ JNI 多线程引擎 · 单分区 8 线程并行解压");
        mainHandler.post(() -> status.setText("JNI 提取中 · 0/" + totalParts));
        final PayloadExtractor px = new PayloadExtractor();
        int ok = 0;
        final java.util.List<Integer> failedIdx = new java.util.ArrayList<>();
        for (int i = 0; i < totalParts; i++) {
            if (extractAllCancel) break;
            final String name = names[i];
            final int no = i + 1;
            final long token = tokenGenerator.getAndIncrement();
            log(isLocal, "⏳ [" + no + "/" + totalParts + "] " + name
                    + (expected[i] > 0 ? " (" + fmtMB(expected[i]) + ")" : ""));
            final File outFile = outFiles[i];
            final StringBuilder jniErr = new StringBuilder();
            final java.util.concurrent.atomic.AtomicBoolean jniDone =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            Thread jr = new Thread(() -> {
                try {
                    px.extractPartition(jniInput, outputDir, name, 8, false, token);
                    jniDone.set(true);
                } catch (Throwable e) {
                    String m = e.getMessage();
                    synchronized (jniErr) { jniErr.append(m == null || m.isEmpty() ? e.toString() : m); }
                }
            }, "pd-jni-all-" + name);
            jr.setDaemon(true);
            jr.start();
            long lastB = 0, lastMs = System.currentTimeMillis();
            while (jr.isAlive()) {
                try { Thread.sleep(600); } catch (InterruptedException e) { break; }
                if (extractAllCancel) {   // 取消即时退出 + 清半成品
                    com.topjohnwu.superuser.Shell.cmd(
                            "rm -f " + DnaTools.quote(outFile.getAbsolutePath()) + "; true").exec();
                    break;
                }
                long bytes = outFile.length();
                long now = System.currentTimeMillis();
                float spd = (bytes - lastB) / 1048576f / Math.max(0.001f, (now - lastMs) / 1000f);
                int pct = expected[i] > 0 ? (int) Math.min(100, bytes * 100 / expected[i]) : 0;
                final int p2 = pct; final float sp = spd;
                final PartitionItemView iv = views[i];
                mainHandler.post(() -> { if (iv != null) iv.setProgress(p2, sp, 1); });
                if (bytes > lastB) lastB = bytes;
                lastMs = now;
            }
            if (extractAllCancel) {
                log(isLocal, "■ 已取消（" + name + "）");
                break;
            }
            try { jr.join(2000); } catch (InterruptedException ignored) { }
            long sz = outFile.isFile() ? outFile.length() : 0;
            if (jniDone.get() && sz > 0) {
                ok++;
                final long size = sz;
                log(isLocal, "✓ " + name + ".img (" + fmtMB(size) + ") 提取完成");
                final PartitionItemView iv = views[i];
                mainHandler.post(() -> { if (iv != null) iv.setProgress(100, 0, 1); });
            } else {
                failedIdx.add(i);
            }
            final int done = i + 1;
            mainHandler.post(() -> status.setText("JNI 提取中 · " + done + "/" + totalParts));
        }
        if (extractAllCancel) {
            finishExtractAll(ok, totalParts, isLocal, outputDir, startMs);
            return ok;
        }
        // JNI 全军覆没 → 交回调用方走 CLI 批量
        if (ok == 0 && failedIdx.size() == totalParts) return -1;
        // 失败分区 → CLI 单分区重试（forceCli，拿精确错误）
        for (int fi = 0; fi < failedIdx.size(); fi++) {
            if (extractAllCancel) break;
            int i = failedIdx.get(fi);
            log(isLocal, "⏳ 重试 [" + (i + 1) + "/" + totalParts + "] " + names[i]
                    + (expected[i] > 0 ? " (" + fmtMB(expected[i]) + ")" : ""));
            if (extractOnePartition(rawInput, outputDir, names[i], expected[i], views[i], isLocal,
                    () -> extractAllCancel, true)) {
                ok++;
            } else if (extractAllCancel) {
                break;
            }
        }
        finishExtractAll(ok, totalParts, isLocal, outputDir, startMs);
        return ok;
    }

    /** v3.42.15 的 CLI 批量兜底（逗号拼接一次调用，分区级 8 线程并行） */
    private void runExtractAllCliBatch(String input, boolean isLocal, String outputDir,
                                       int totalParts, String[] names, long[] expected,
                                       PartitionItemView[] views, File[] outFiles, long startMs) {
        log(isLocal, "⏳ " + totalParts + " 个分区并行提取（CLI · 8 线程）...");
        mainHandler.post(() -> status.setText("并行提取中 · 0/" + totalParts));
        final java.util.concurrent.atomic.AtomicBoolean cliDone =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final boolean isUrl = input.startsWith("http://") || input.startsWith("https://");
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < totalParts; i++) {
            if (joined.length() > 0) joined.append(",");
            joined.append(names[i]);
        }
        Thread runner = new Thread(() -> {
            DnaTools.Result r = DnaTools.payloadExtractCli(PayloadDumperActivity.this,
                    input, outputDir, joined.toString(),
                    line -> {   // indicatif 进度条行（█░▓）不进日志
                        String s = line == null ? "" : line;
                        if (s.indexOf('█') >= 0 || s.indexOf('░') >= 0 || s.indexOf('▓') >= 0)
                            return kotlin.Unit.INSTANCE;
                        String tr = s.trim();
                        if (!tr.isEmpty()) log(isLocal, "  " + tr);
                        return kotlin.Unit.INSTANCE;
                    },
                    () -> extractAllCancel,
                    isUrl ? 60 * 60_000L : 30 * 60_000L);
            cliDone.set(r.getSuccess());
        }, "pd-cli-batch");
        runner.setDaemon(true);
        runner.start();
        long lastPollMs = System.currentTimeMillis();
        while (runner.isAlive()) {
            try { Thread.sleep(800); } catch (InterruptedException e) { break; }
            long now = System.currentTimeMillis();
            int done = 0;
            for (int i = 0; i < totalParts; i++) {
                long bytes = outFiles[i].length();
                if (bytes > 0 && (expected[i] <= 0 || bytes >= expected[i])) done++;
                final int pct = expected[i] > 0 ? (int) Math.min(100, bytes * 100 / expected[i]) : 0;
                final PartitionItemView iv = views[i];
                final float sp = 0f;
                if (iv != null && pct > 0 && pct < 100)
                    mainHandler.post(() -> iv.setProgress(pct, sp, 1));
            }
            final int d = done;
            if (now - lastPollMs >= 1000) {
                lastPollMs = now;
                mainHandler.post(() -> status.setText("并行提取中 · " + d + "/" + totalParts));
            }
        }
        try { runner.join(2000); } catch (InterruptedException ignored) { }
        // 统计落盘成功；失败行单独重跑（复用单分区链路拿精确错误 + 行内进度）
        int ok = 0;
        java.util.List<Integer> failedIdx = new java.util.ArrayList<>();
        for (int i = 0; i < totalParts; i++) {
            if (outFiles[i].isFile() && outFiles[i].length() > 0) {
                ok++;
                final String nm = names[i];
                final long sz = outFiles[i].length();
                log(isLocal, "✓ " + nm + ".img (" + fmtMB(sz) + ") 提取完成");
                final PartitionItemView iv = views[i];
                mainHandler.post(() -> { if (iv != null) iv.setProgress(100, 0, 1); });
            } else {
                failedIdx.add(i);
            }
        }
        for (int fi = 0; fi < failedIdx.size(); fi++) {
            if (extractAllCancel) break;
            int i = failedIdx.get(fi);
            final String name = names[i];
            log(isLocal, "⏳ 重试 [" + (i + 1) + "/" + totalParts + "] " + name
                    + (expected[i] > 0 ? " (" + fmtMB(expected[i]) + ")" : ""));
            if (extractOnePartition(input, outputDir, name, expected[i], views[i], isLocal,
                    () -> extractAllCancel, false)) {
                ok++;
            } else if (extractAllCancel) {
                break;
            }
        }
        finishExtractAll(ok, totalParts, isLocal, outputDir, startMs);
    }

    /** 全选结尾统计（JNI / CLI 批量共用） */
    private void finishExtractAll(int ok, int totalParts, boolean isLocal, String outputDir, long startMs) {
        final int okF = ok;
        final long secs = (System.currentTimeMillis() - startMs) / 1000;
        if (!extractAllCancel) {
            log(isLocal, (okF == totalParts ? "✓ 全选提取完成: " : "⚠ 全选提取部分失败: ")
                    + okF + "/" + totalParts + " · 耗时 " + secs + "s");
            log(isLocal, "文件位于: " + outputDir);
            mainHandler.post(() -> {
                status.setText(okF == totalParts ? "全选提取完成 · " + okF : "部分完成 · " + okF + "/" + totalParts);
                toast(okF == totalParts ? "全选提取完成" : "完成 " + okF + "/" + totalParts);
            });
        }
    }

    /**
     * v3.42.18：单分区提取统一入口（单分区按钮 / 全选失败重试 共用）——
     * JNI 优先（libpayload_extract_jni.so 单分区内部块级 8 线程，对齐 DnaBin v3.42.17
     * 实测 OPPO 67 分区 76s），失败/URL 输入回退 root CLI（libpayload_extract.so，
     * URL 模式内部 Range 流式多连接只拉所需数据段）。
     * stat 轮询落盘字节驱动分区行进度条与速度，本地 5 分钟 / 在线 20 分钟无进展熔断。
     * 取消：等待循环即时退出 + root rm 半成品（JNI 无中断接口，后台 native 跑完
     * 写已 unlink 的 fd 自动释放，不占可见空间、不阻塞后续任务）。
     * @param forceCli true=跳过 JNI 直接 CLI（JNI 已失败过的分区重试）
     * @return true=成功；false=失败或已取消（日志与分区行状态已在内部更新）
     */
    private boolean extractOnePartition(String input, String outputDir, String name,
                                        long expected, PartitionItemView itemView, boolean isLocal,
                                        java.util.function.BooleanSupplier cancelled,
                                        boolean forceCli) {
        final File outFile = new File(outputDir, name + ".img");
        // 清残留（防旧文件让进度虚高 / 误判完成）；root rm：旧版本 root 链路写的
        // root 属主残留文件 app 经 FUSE 可能删不掉
        com.topjohnwu.superuser.Shell.cmd(
                "rm -f " + DnaTools.quote(outFile.getAbsolutePath()) + "; true").exec();
        final boolean isUrl = input.startsWith("http://") || input.startsWith("https://");
        // ---- JNI 主路径（本地输入且非强制 CLI）----
        if (!isUrl && !forceCli) {
            String jniInput = DnaTools.ensureJniReadable(PayloadDumperActivity.this, input,
                    msg -> { log(isLocal, msg); return kotlin.Unit.INSTANCE; });
            if (jniInput != null) {
                final StringBuilder jniErr = new StringBuilder();
                final java.util.concurrent.atomic.AtomicBoolean jniDone =
                        new java.util.concurrent.atomic.AtomicBoolean(false);
                final long token = tokenGenerator.getAndIncrement();
                Thread jr = new Thread(() -> {
                    try {
                        new PayloadExtractor().extractPartition(jniInput, outputDir, name, 8, false, token);
                        jniDone.set(true);
                    } catch (Throwable e) {
                        String m = e.getMessage();
                        synchronized (jniErr) { jniErr.append(m == null || m.isEmpty() ? e.toString() : m); }
                    }
                }, "pd-jni-" + name);
                jr.setDaemon(true);
                jr.start();
                long lastB = 0, lastMs = System.currentTimeMillis();
                while (jr.isAlive()) {
                    try { Thread.sleep(600); } catch (InterruptedException e) { break; }
                    if (cancelled.getAsBoolean()) {   // 取消即时退出 + 清半成品
                        com.topjohnwu.superuser.Shell.cmd(
                                "rm -f " + DnaTools.quote(outFile.getAbsolutePath()) + "; true").exec();
                        break;
                    }
                    long bytes = outFile.length();
                    long now = System.currentTimeMillis();
                    float spd = (bytes - lastB) / 1048576f / Math.max(0.001f, (now - lastMs) / 1000f);
                    int pct = expected > 0 ? (int) Math.min(100, bytes * 100 / expected) : 0;
                    final int p2 = pct; final float sp = spd;
                    mainHandler.post(() -> { if (itemView != null) itemView.setProgress(p2, sp, 1); });
                    if (bytes > lastB) lastB = bytes;
                    lastMs = now;
                }
                if (cancelled.getAsBoolean()) {
                    log(isLocal, "■ " + name + " 已取消");
                    mainHandler.post(() -> { if (itemView != null) itemView.setError("已取消"); });
                    return false;
                }
                try { jr.join(2000); } catch (InterruptedException ignored) { }
                if (jniDone.get() && outFile.isFile() && outFile.length() > 0) {
                    log(isLocal, "✓ " + name + ".img (" + fmtMB(outFile.length()) + ") 提取完成");
                    mainHandler.post(() -> { if (itemView != null) itemView.setComplete(); });
                    return true;
                }
                String je; synchronized (jniErr) { je = jniErr.toString().trim(); }
                log(isLocal, "… JNI " + name + " 失败" + (je.isEmpty() ? "" : ": " + je)
                        + "，回退 CLI 重试");
                // 落到下方 CLI 兜底
            }
        }
        // ---- CLI 兜底 / URL 直读 ----
        final long stuckLimit = isUrl ? 20 * 60_000L : 300_000L;   // 在线先下载后落盘，放宽熔断
        final StringBuilder errOut = new StringBuilder();
        final java.util.concurrent.atomic.AtomicBoolean nativeDone =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread runner = new Thread(() -> {
            DnaTools.Result r = DnaTools.payloadExtractCli(PayloadDumperActivity.this,
                    input, outputDir, name, null,
                    cancelled::getAsBoolean,
                    isUrl ? 60 * 60_000L : 20 * 60_000L);
            if (r.getSuccess()) {
                nativeDone.set(true);
            } else {
                // v3.40.21：briefOf 抓 error: 行（真实原因），不再被 clap 尾巴
                // "For more information, try '--help'." 遮蔽
                synchronized (errOut) { errOut.append(DnaTools.briefOf(r)); }
            }
        }, "pd-cli-" + name);
        runner.setDaemon(true);
        runner.start();
        // 进度轮询：stat 已落盘字节（root CLI 写入，app stat 可见）
        long lastBytes = 0, lastPollMs = System.currentTimeMillis(), stuckMs = System.currentTimeMillis();
        while (runner.isAlive()) {
            try { Thread.sleep(600); } catch (InterruptedException e) { break; }
            if (cancelled.getAsBoolean()) {   // v3.42.18：取消即时退出 + 清半成品
                com.topjohnwu.superuser.Shell.cmd(
                        "rm -f " + DnaTools.quote(outFile.getAbsolutePath()) + "; true").exec();
                break;
            }
            long bytes = outFile.length();
            long now = System.currentTimeMillis();
            float speedMBs = (bytes - lastBytes) / 1048576f / Math.max(0.001f, (now - lastPollMs) / 1000f);
            int pct = expected > 0 ? (int) Math.min(100, bytes * 100 / expected) : 0;
            final int p2 = pct;
            final float sp = speedMBs;
            mainHandler.post(() -> { if (itemView != null) itemView.setProgress(p2, sp, 1); });
            if (bytes > lastBytes) { lastBytes = bytes; stuckMs = now; }
            else if (now - stuckMs > stuckLimit) {
                log(isLocal, "⏱ " + name + " 提取超时（" + (stuckLimit / 60000) + " 分钟无进展）");
                break;
            }
            lastPollMs = now;
        }
        try { runner.join(2000); } catch (InterruptedException ignored) { }
        if (cancelled.getAsBoolean()) {
            log(isLocal, "■ " + name + " 已取消");
            mainHandler.post(() -> { if (itemView != null) itemView.setError("已取消"); });
            return false;
        }
        long size = outFile.length();
        if (nativeDone.get() && size > 0) {
            log(isLocal, "✓ " + name + ".img (" + fmtMB(size) + ") 提取完成");
            mainHandler.post(() -> { if (itemView != null) itemView.setComplete(); });
            return true;
        }
        String err;
        synchronized (errOut) { err = errOut.toString().trim(); }
        String brief = err.isEmpty() ? "文件未生成" : err;
        log(isLocal, "✗ " + name + " 提取失败: " + brief);
        if (brief.contains("delta") || brief.toLowerCase(java.util.Locale.ROOT).contains("incremental")
                || brief.contains("differential") || brief.contains("source")) {
            log(isLocal, "  提示: 这是增量 OTA 包，请使用 DNA 工具箱 → 分解增量包");
        }
        mainHandler.post(() -> { if (itemView != null) itemView.setError("提取失败"); });
        return false;
    }

    private String fmtMB(long bytes) {
        if (bytes >= 1L << 30) return String.format(java.util.Locale.US, "%.1fG", bytes / 1073741824f);
        if (bytes >= 1L << 20) return String.format(java.util.Locale.US, "%.1fM", bytes / 1048576f);
        return (bytes / 1024) + "K";
    }

    private void log(boolean isLocal, String msg) {
        if (isLocal) logLocal(msg); else logOnline(msg);
    }

    /**
     * 在线全选的整包下载：GitHub 系链接镜像优先 + 直连垫底 + 慢速看门狗（同 UpdateCenter v3.40.11）。
     * 修复在线单分区提取的固有缺陷 —— 每个分区都要重新流式下载，N 个分区 = N 次下载。
     */
    private File downloadOnlineZip(String url) {
        boolean gh = isGithubHost(url);
        File out = new File(getCacheDir(), "payload_online_full.zip");
        //noinspection ResultOfMethodCallIgnored
        out.delete();
        for (int si = 0; si < (gh ? GH_MIRRORS.length : 1); si++) {
            if (extractAllCancel) return null;
            String src = (gh ? GH_MIRRORS[si] : "") + url;
            boolean lastSource = gh && si == GH_MIRRORS.length - 1;
            java.net.HttpURLConnection conn = null;
            try {
                logOnline((gh ? "下载源 " + (si + 1) + "/" + GH_MIRRORS.length + ": " : "下载源: ")
                        + (src.equals(url) ? "直连" : GH_MIRRORS[si]));
                conn = (java.net.HttpURLConnection) new java.net.URL(src).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "Dsu-Manager-Android/" + BuildConfig.VERSION_NAME);
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) throw new java.io.IOException("HTTP " + code);
                long total = conn.getContentLengthLong();
                boolean slow = false;
                try (java.io.InputStream in = conn.getInputStream();
                     java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) {
                    byte[] buf = new byte[16384];
                    int read;
                    long done = 0, winBytes = 0, winStart = System.currentTimeMillis();
                    long lastLog = 0, slowSince = System.currentTimeMillis();
                    while ((read = in.read(buf)) != -1) {
                        if (extractAllCancel) throw new java.io.IOException("cancelled");
                        fo.write(buf, 0, read);
                        done += read;
                        winBytes += read;
                        long now = System.currentTimeMillis();
                        if (now - lastLog >= 1000) {
                            long dt = Math.max(1, now - winStart);
                            long speed = winBytes * 1000 / dt;   // 窗口内真实速度
                            winBytes = 0;
                            winStart = now;
                            lastLog = now;
                            // 慢速看门狗：达标重置；持续过慢且后面还有源 → 弃源切换
                            if (speed >= SLOW_SPEED) slowSince = now;
                            else if (!lastSource && now - slowSince >= SLOW_WINDOW_MS) { slow = true; break; }
                            int pct = total > 0 ? (int) (done * 100 / total) : -1;
                            logOnline("↓ " + (pct >= 0 ? pct + "% · " : "") + fmtMB(done)
                                    + (total > 0 ? " / " + fmtMB(total) : "")
                                    + " · " + fmtMB(speed) + "/s");
                        }
                    }
                }
                if (slow) {
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                    logOnline("源过慢，自动切换下一个下载源...");
                    continue;
                }
                // ZIP 头校验（镜像可能返回 HTML 错误页）
                try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(out, "r")) {
                    byte[] head = new byte[2];
                    raf.readFully(head);
                    if (head[0] != 'P' || head[1] != 'K') throw new java.io.IOException("下载内容不是 ZIP（镜像返回无效内容）");
                }
                return out;
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
                logOnline("下载失败: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return null;
    }

    private boolean isGithubHost(String url) {
        String[] hosts = {
                "https://github.com/", "https://raw.githubusercontent.com/",
                "https://objects.githubusercontent.com/", "https://release-assets.githubusercontent.com/",
                "https://gist.githubusercontent.com/", "https://codeload.github.com/",
                "https://media.githubusercontent.com/", "https://cloud.githubusercontent.com/"};
        for (String h : hosts) if (url != null && url.startsWith(h)) return true;
        return false;
    }

    private void closeOpenedFd() {
        if (openedFd != null) {
            try {
                openedFd.close();
            } catch (Exception e) {
                android.util.Log.e("PayloadDumper", "关闭 fd 失败", e);
            }
            openedFd = null;
        }
    }
    
    private LinearLayout glass() {
        LinearLayout panel = new LinearLayout(this);
        panel.setBackgroundResource(R.drawable.liquid_glass_panel);
        return panel;
    }
    
    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
    
    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + 0.5f);
    }
    
    private android.graphics.drawable.Drawable createXiaomiGradientBackground() {
        android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] {
            createRadialGradient(0xffb8d4e8, 0x00000000, 0.5f, 0.0f),
            createRadialGradient(0xff98c4d9, 0x00000000, 0.25f, 0.25f),
            createRadialGradient(0xffa8ccd9, 0x00000000, 0.75f, 0.25f),
            createLinearGradient(0xffc8dce8, 0xff6a8fa8, true),
            createRadialGradient(0xff5a7d94, 0x00000000, 0.5f, 0.8f)
        });
        return layers;
    }
    
    private android.graphics.drawable.GradientDrawable createLinearGradient(int startColor, int endColor, boolean vertical) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable(
            vertical ? android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM 
                     : android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            new int[] { startColor, endColor }
        );
        return gd;
    }
    
    private android.graphics.drawable.GradientDrawable createRadialGradient(int centerColor, int edgeColor, float cx, float cy) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setGradientType(android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT);
        gd.setColors(new int[] { centerColor, edgeColor });
        gd.setGradientRadius(800);
        gd.setGradientCenter(cx, cy);
        return gd;
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeOpenedFd();
        executor.shutdown();
        if (extractor != null) {
            extractor.close();
        }
        // v3.41.14：返回上一页即自动清理拷贝的缓存（JNI 解析兜底副本 + 在线整包下载残留），
        // 释放了空间则提示一下（在线 zip 是页面内逐分区提取的输入源，页面销毁后会话已结束）
        // v3.41.21：移入后台线程 —— root 属主副本需 su rm 兜底删除，大目录可能耗时数秒
        new Thread(() -> {
            long freed = DnaTools.INSTANCE.releaseJniCache(this);
            File onlineZip = new File(getCacheDir(), "payload_online_full.zip");
            if (onlineZip.isFile()) {
                freed += onlineZip.length();
                //noinspection ResultOfMethodCallIgnored
                onlineZip.delete();
            }
            final long total = freed;
            if (total > 0) {
                runOnUiThread(() -> Toast.makeText(getApplicationContext(),
                        "♻ 已自动清理缓存副本 " + fmtMB(total), Toast.LENGTH_SHORT).show());
            }
        }, "dump-cache-clean").start();
    }
}
