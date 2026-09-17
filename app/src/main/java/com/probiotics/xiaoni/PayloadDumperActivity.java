package com.probiotics.xiaoni;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
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
public final class PayloadDumperActivity extends Activity {
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
    private Uri selectedFileUri;
    private String selectedFileName = "";
    private int currentTab = 0;
    
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
        
        // 顶部标题
        TextView title = new TextView(this);
        title.setText("Payload Dumper");
        title.setTextSize(22);
        title.setTextColor(0xff0f1e36);
        title.setTypeface(null, 1);
        title.setGravity(Gravity.CENTER);
        content.addView(title, new LinearLayout.LayoutParams(-1, dp(50)));
        
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
        onlineLogScroll = new ScrollView(this);
        onlineLogScroll.setVerticalScrollBarEnabled(true);
        onlineLogScroll.setScrollbarFadingEnabled(false);
        onlineLogScroll.setFillViewport(false);
        // 关键：阻止父容器拦截触摸事件，允许滚动
        onlineLogScroll.setOnTouchListener((view, event) -> {
            ViewParent parent = view.getParent();
            if (parent != null) {
                int action = event.getActionMasked();
                parent.requestDisallowInterceptTouchEvent(action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL);
            }
            return false;
        });
        
        onlineLogDisplay = new TextView(this);
        onlineLogDisplay.setText("");
        onlineLogDisplay.setTextSize(10);
        onlineLogDisplay.setTextColor(0xffdc2626); // 红色
        onlineLogDisplay.setTypeface(android.graphics.Typeface.MONOSPACE);
        onlineLogDisplay.setBackgroundColor(0);
        onlineLogDisplay.setPadding(dp(8), dp(8), dp(8), dp(8));
        // 不设置 setTextIsSelectable，否则会阻止滚动
        
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
        ScrollView fileInfoScroll = new ScrollView(this);
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
        localLogScroll = new ScrollView(this);
        localLogScroll.setVerticalScrollBarEnabled(true);
        localLogScroll.setScrollbarFadingEnabled(false);
        localLogScroll.setFillViewport(false);
        // 关键：阻止父容器拦截触摸事件，允许滚动
        localLogScroll.setOnTouchListener((view, event) -> {
            ViewParent parent = view.getParent();
            if (parent != null) {
                int action = event.getActionMasked();
                parent.requestDisallowInterceptTouchEvent(action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL);
            }
            return false;
        });
        
        localLogDisplay = new TextView(this);
        localLogDisplay.setText("");
        localLogDisplay.setTextSize(10);
        localLogDisplay.setTextColor(0xffdc2626); // 红色
        localLogDisplay.setTypeface(android.graphics.Typeface.MONOSPACE);
        localLogDisplay.setBackgroundColor(0);
        localLogDisplay.setPadding(dp(8), dp(8), dp(8), dp(8));
        // 不设置 setTextIsSelectable，否则会阻止滚动
        
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
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_FILE);
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
                android.util.Log.d("PayloadDumper", "Token: " + token);
                
                if (extractor == null) {
                    throw new Exception("Extractor is null");
                }
                
                extractor.extractPartition(currentInput, outputDir, partitionName, 4, false, token);
                android.util.Log.d("PayloadDumper", "提取调用完成: " + partitionName);
            } catch (Exception e) {
                android.util.Log.e("PayloadDumper", "========== 提取失败 ==========");
                android.util.Log.e("PayloadDumper", "分区: " + partitionName, e);
                android.util.Log.e("PayloadDumper", "错误消息: " + e.getMessage());
                android.util.Log.e("PayloadDumper", "错误类型: " + e.getClass().getName());
                
                String errorMsg = e.getMessage();
                String displayMsg = errorMsg;
                
                // 检测增量 OTA 错误
                if (errorMsg != null && errorMsg.contains("delta/incremental OTA")) {
                    displayMsg = "这是增量 OTA 包，需要源分区数据才能提取\n当前版本暂不支持增量 OTA";
                    if (isLocal) {
                        logLocal("错误: 增量 OTA 包不支持");
                        logLocal("提示: 请使用完整 OTA 包");
                    } else {
                        logOnline("错误: 增量 OTA 包不支持");
                        logOnline("提示: 请使用完整 OTA 包");
                    }
                } else {
                    if (isLocal) {
                        logLocal("提取失败: " + partitionName + " - " + errorMsg);
                    } else {
                        logOnline("提取失败: " + partitionName + " - " + errorMsg);
                    }
                }
                
                final String finalDisplayMsg = displayMsg;
                mainHandler.post(() -> {
                    if (itemView != null) {
                        itemView.setError("提取失败");
                    }
                    toast("提取失败: " + partitionName + "\n" + finalDisplayMsg);
                });
                return;
            }
        });
        
        // 启动进度监听线程
        executor.execute(() -> {
            try {
                Thread.sleep(1000); // 等待提取线程启动
                
                long startTime = System.currentTimeMillis();
                int lastPercent = -1;
                int lastPhase = -1;
                long lastTime = startTime;
                int stuckCount = 0;
                int nullCount = 0;
                
                // 速度平滑：移动平均
                java.util.LinkedList<Float> speedHistory = new java.util.LinkedList<>();
                final int SPEED_WINDOW = 5; // 取最近 5 次的平均值
                
                android.util.Log.d("PayloadDumper", "开始监听进度: " + partitionName + ", 文件大小: " + (finalPartitionSize / 1024 / 1024) + " MB");
                
                while (true) {
                    Thread.sleep(500);
                    
                    // 检查是否被取消
                    if (cancelledTokens.containsKey(token) && cancelledTokens.get(token)) {
                        android.util.Log.d("PayloadDumper", "提取已取消: " + partitionName);
                        if (isLocal) {
                            logLocal("已取消: " + partitionName);
                        } else {
                            logOnline("已取消: " + partitionName);
                        }
                        mainHandler.post(() -> {
                            if (itemView != null) {
                                itemView.setError("已取消");
                            }
                        });
                        cancelledTokens.remove(token);
                        break;
                    }
                    
                    android.util.Pair<Integer, Integer> progress = null;
                    try {
                        progress = extractor.getExtractProgress(token);
                    } catch (Exception e) {
                        android.util.Log.e("PayloadDumper", "获取进度失败: " + partitionName, e);
                        if (isLocal) {
                            logLocal("进度获取失败: " + e.getMessage());
                        } else {
                            logOnline("进度获取失败: " + e.getMessage());
                        }
                        break;
                    }
                    
                    if (progress == null) {
                        nullCount++;
                        android.util.Log.d("PayloadDumper", "进度为 null, 计数: " + nullCount);
                        
                        if (nullCount >= 3) {
                            // 检查文件是否存在
                            File outputFile = new File(finalOutputPath);
                            boolean exists = outputFile.exists();
                            long size = exists ? outputFile.length() : 0;
                            
                            android.util.Log.d("PayloadDumper", "提取完成检查: " + partitionName);
                            android.util.Log.d("PayloadDumper", "文件存在: " + exists);
                            android.util.Log.d("PayloadDumper", "文件大小: " + size);
                            
                            if (exists && size > 0) {
                                // 提取完成
                                if (isLocal) {
                                    logLocal("提取完成: " + partitionName + " (" + (size / 1024 / 1024) + " MB)");
                                } else {
                                    logOnline("提取完成: " + partitionName + " (" + (size / 1024 / 1024) + " MB)");
                                }
                                mainHandler.post(() -> {
                                    if (itemView != null) {
                                        itemView.setComplete();
                                    }
                                    toast("提取完成: " + partitionName + "\n保存到: " + finalOutputPath);
                                });
                            } else {
                                // 提取失败
                                if (isLocal) {
                                    logLocal("提取失败: " + partitionName + " - 文件未生成");
                                } else {
                                    logOnline("提取失败: " + partitionName + " - 文件未生成");
                                }
                                mainHandler.post(() -> {
                                    if (itemView != null) {
                                        itemView.setError("提取失败: 文件未生成");
                                    }
                                    toast("提取失败: " + partitionName + "\n文件未生成");
                                });
                            }
                            break;
                        }
                        continue;
                    }
                    
                    nullCount = 0;
                    
                    int phase = progress.first;
                    int permille = progress.second;
                    int percent = permille / 10;
                    
                    // 检测 phase 切换，重置进度追踪
                    if (phase != lastPhase && lastPhase >= 0) {
                        String phaseText = (phase == 0) ? "下载" : "写入";
                        android.util.Log.d("PayloadDumper", "阶段切换: " + lastPhase + " -> " + phase + " (" + phaseText + ")");
                        if (phase == 1) {
                            if (isLocal) {
                                logLocal("下载完成，开始写入: " + partitionName);
                            } else {
                                logOnline("下载完成，开始写入: " + partitionName);
                            }
                        }
                        // 重置进度追踪
                        lastPercent = -1;
                        lastTime = System.currentTimeMillis();
                        speedHistory.clear();
                    }
                    lastPhase = phase;
                    
                    android.util.Log.d("PayloadDumper", String.format("%s: %d%% (phase=%d, permille=%d)", partitionName, percent, phase, permille));
                    
                    // 检测卡死（针对当前阶段）
                    if (percent == lastPercent && lastPercent >= 0) {
                        stuckCount++;
                        if (stuckCount > 240) { // 120秒无变化
                            android.util.Log.e("PayloadDumper", "提取超时: " + partitionName + ", 卡在 " + percent + "%");
                            if (isLocal) {
                                logLocal("提取超时: " + partitionName + " (卡在 " + percent + "%)");
                            } else {
                                logOnline("提取超时: " + partitionName + " (卡在 " + percent + "%)");
                            }
                            mainHandler.post(() -> {
                                if (itemView != null) {
                                    itemView.setError("提取超时 (卡在 " + percent + "%)");
                                }
                            });
                            break;
                        }
                    } else {
                        stuckCount = 0;
                    }
                    
                    // 计算速度（基于文件实际大小，带平滑）
                    long currentTime = System.currentTimeMillis();
                    long timeDiff = currentTime - lastTime;
                    float instantSpeed = 0;
                    
                    if (timeDiff > 0 && percent > lastPercent && lastPercent >= 0 && finalPartitionSize > 0) {
                        float progressDiff = (percent - lastPercent) / 100.0f;
                        float timeSeconds = timeDiff / 1000.0f;
                        float sizeMB = finalPartitionSize / 1024.0f / 1024.0f;
                        instantSpeed = (progressDiff * sizeMB) / timeSeconds; // MB/s
                        
                        // 添加到历史记录
                        speedHistory.add(instantSpeed);
                        if (speedHistory.size() > SPEED_WINDOW) {
                            speedHistory.removeFirst();
                        }
                    }
                    
                    // 计算平均速度
                    float avgSpeed = 0;
                    if (!speedHistory.isEmpty()) {
                        float sum = 0;
                        for (float s : speedHistory) {
                            sum += s;
                        }
                        avgSpeed = sum / speedHistory.size();
                    }
                    
                    lastPercent = percent;
                    lastTime = currentTime;
                    
                    final float finalSpeed = avgSpeed;
                    final int finalPhase = phase;
                    final int finalPercent = percent;
                    mainHandler.post(() -> {
                        if (itemView != null) {
                            itemView.setProgress(finalPercent, finalSpeed, finalPhase);
                        }
                    });
                }
                
                android.util.Log.d("PayloadDumper", "进度监听结束: " + partitionName);
                
            } catch (Exception e) {
                android.util.Log.e("PayloadDumper", "进度监听异常: " + partitionName, e);
                if (isLocal) {
                    logLocal("进度监听异常: " + e.getMessage());
                } else {
                    logOnline("进度监听异常: " + e.getMessage());
                }
            }
        });
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
    }
}
