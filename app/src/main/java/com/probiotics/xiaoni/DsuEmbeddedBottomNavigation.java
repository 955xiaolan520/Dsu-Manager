package com.probiotics.xiaoni;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Dsu 主界面 DNA/ROM 导航栏的 YunX 工作区适配版：仅更换标签和点击回调，保留同款
 * navigation_glass_bg、LiquidGlassIndicator、液态滑动、长按/横向拖动、文字呼吸缩放、
 * 全局振动反馈以及底栏脉冲动画。
 */
public final class DsuEmbeddedBottomNavigation extends FrameLayout {
    public interface OnTabSelectedListener {
        void onTabSelected(int index);
    }

    private static final String[] LABELS = {"解析", "网盘", "下载", "设置"};
    private final HandlerCompat handler;
    private final LinearLayout items;
    private final View indicator;
    private int selectedTab;
    private int indicatorLeft = -1;
    private int navigationTarget = -1;
    private float navigationStartX;
    private boolean navigationDragging;
    private View navigationGestureView;
    private Runnable navigationHold;
    private ValueAnimator liquidNavigationFlow;
    private ValueAnimator bottomNavigationPulse;
    private OnTabSelectedListener onTabSelectedListener;

    public DsuEmbeddedBottomNavigation(Context context) {
        super(context);
        handler = new HandlerCompat();
        setClipChildren(false);
        setClipToPadding(false);
        setPadding(dp(2), dp(2), dp(2), dp(2));
        setBackgroundResource(R.drawable.navigation_glass_bg);
        setElevation(0f);

        items = new LinearLayout(context);
        items.setGravity(Gravity.CENTER);
        items.setClipChildren(false);
        items.setClipToPadding(false);
        addView(items, new FrameLayout.LayoutParams(-1, -1));
        for (int i = 0; i < LABELS.length; i++) addNavigationItem(i);

        indicator = new LiquidGlassIndicator(context);
        indicator.setElevation(dp(4));
        indicator.setClickable(false);
        indicator.setFocusable(false);
        indicator.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(indicator, new FrameLayout.LayoutParams(dp(62), dp(48)));
        post(() -> {
            moveLiquidIndicator(selectedTab, false);
            updateNavigationLabels(selectedTab);
        });
    }

    public void setSelectedTab(int index) {
        setSelectedTab(index, true);
    }

    public void setSelectedTab(int index, boolean animated) {
        if (index < 0 || index >= LABELS.length) return;
        if (selectedTab == index && indicatorLeft >= 0) return;
        selectedTab = index;
        animateNavigation(index, animated);
    }

    public void setOnTabSelectedListener(OnTabSelectedListener listener) {
        onTabSelectedListener = listener;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void addNavigationItem(int tab) {
        TextView item = new TextView(getContext());
        item.setText(LABELS[tab]);
        item.setTextSize(12f);
        item.setGravity(Gravity.CENTER);
        item.setSingleLine(true);
        item.setIncludeFontPadding(false);
        item.setTextColor(0xfff8fbff);
        item.setShadowLayer(dp(2), 0, dp(1), 0x66233c50);
        item.setPadding(0, 0, 0, 0);
        item.setBackground(null);
        item.setTag(tab);
        item.setStateListAnimator(null);
        item.setOnTouchListener((view, event) -> handleNavigationGesture(view, event, tab));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(60), 1f);
        params.setMargins(dp(2), 0, dp(2), 0);
        items.addView(item, params);
    }

    private boolean handleNavigationGesture(View view, MotionEvent event, int pressedTab) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressLiquidIndicator(true);
                navigationDragging = false;
                navigationTarget = -1;
                navigationStartX = event.getRawX();
                navigationGestureView = view;
                navigationHold = () -> {
                    navigationDragging = true;
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                    requestDisallowInterceptTouchEvent(true);
                    expandDraggingLens();
                    previewNavigationTarget(navigationStartX);
                };
                handler.postDelayed(navigationHold, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!navigationDragging && Math.abs(event.getRawX() - navigationStartX) >= dp(12)) {
                    cancelNavigationHold();
                    navigationDragging = true;
                    requestDisallowInterceptTouchEvent(true);
                    expandDraggingLens();
                    previewNavigationTarget(event.getRawX());
                }
                if (navigationDragging) previewNavigationTarget(event.getRawX());
                return true;
            case MotionEvent.ACTION_UP:
                cancelNavigationHold();
                boolean dragged = navigationDragging;
                navigationDragging = false;
                if (dragged) finishDraggingLens();
                else Haptics.perform(view);
                pressLiquidIndicator(false);
                int target = dragged ? navigationTarget : pressedTab;
                if (target >= 0 && target < LABELS.length) {
                    if (onTabSelectedListener != null) onTabSelectedListener.onTabSelected(target);
                }
                navigationGestureView = null;
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelNavigationHold();
                pressLiquidIndicator(false);
                navigationDragging = false;
                navigationGestureView = null;
                return true;
            default:
                return true;
        }
    }

    private void cancelNavigationHold() {
        if (navigationHold != null) handler.removeCallbacks(navigationHold);
        navigationHold = null;
    }

    private void previewNavigationTarget(float rawX) {
        if (items.getChildCount() == 0) return;
        int[] location = new int[2];
        items.getLocationOnScreen(location);
        float itemWidth = items.getWidth() / (float) items.getChildCount();
        int tab = Math.max(0, Math.min(items.getChildCount() - 1,
                (int) ((rawX - location[0]) / itemWidth)));
        if (tab == navigationTarget) return;
        boolean movedBetweenTabs = navigationTarget >= 0;
        navigationTarget = tab;
        if (movedBetweenTabs && navigationGestureView != null) {
            navigationGestureView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        }
        moveDraggingLens(tab, movedBetweenTabs);
        updateNavigationLabels(tab);
    }

    private void moveDraggingLens(int tab, boolean animate) {
        if (indicator == null || items.getChildCount() == 0) return;
        int lensWidth = dp(78);
        int lensHeight = dp(64);
        View target = items.getChildAt(tab);
        int targetLeft = items.getLeft() + target.getLeft() + (target.getWidth() - lensWidth) / 2;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) indicator.getLayoutParams();
        int startLeft = indicatorLeft < 0 ? targetLeft
                : Math.round(params.leftMargin + indicator.getTranslationX());
        cancelFlow();
        params.width = lensWidth;
        params.height = lensHeight;
        params.leftMargin = startLeft;
        params.topMargin = 0;
        indicator.setPivotX(lensWidth / 2f);
        indicator.setPivotY(lensHeight / 2f);
        indicator.setScaleY(1f);
        indicator.setTranslationX(0f);
        indicator.setLayoutParams(params);
        ((LiquidGlassIndicator) indicator).setLiquidPressed(true);
        if (!animate || startLeft == targetLeft) {
            indicatorLeft = targetLeft;
            params.leftMargin = targetLeft;
            indicator.setScaleX(1.16f);
            indicator.setLayoutParams(params);
            return;
        }
        ValueAnimator slide = ValueAnimator.ofFloat(0f, 1f);
        liquidNavigationFlow = slide;
        slide.setDuration(540);
        slide.setInterpolator(new PathInterpolator(.16f, .88f, .22f, 1f));
        slide.addUpdateListener(animation -> {
            float progress = (Float) animation.getAnimatedValue();
            float swell = 4f * progress * (1f - progress);
            indicator.setTranslationX((targetLeft - startLeft) * progress);
            indicator.setScaleX(1.16f + .30f * swell);
            indicator.setScaleY(1f - .035f * swell);
        });
        slide.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (animation != liquidNavigationFlow) return;
                indicatorLeft = targetLeft;
                FrameLayout.LayoutParams settled = (FrameLayout.LayoutParams) indicator.getLayoutParams();
                settled.leftMargin = targetLeft;
                indicator.setTranslationX(0f);
                indicator.setScaleX(1.16f);
                indicator.setScaleY(1f);
                indicator.setLayoutParams(settled);
                liquidNavigationFlow = null;
            }
        });
        pulseBottomNavigation();
        slide.start();
    }

    private void expandDraggingLens() {
        indicator.animate().cancel();
        indicator.animate().scaleX(1.16f).scaleY(1f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private void finishDraggingLens() {
        int settleLeft = indicatorLeft;
        if (navigationTarget >= 0) {
            View target = items.getChildAt(navigationTarget);
            int lensWidth = dp(78);
            settleLeft = items.getLeft() + target.getLeft() + (target.getWidth() - lensWidth) / 2;
        }
        cancelFlow();
        int baseWidth = dp(62);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) indicator.getLayoutParams();
        params.width = baseWidth;
        params.height = dp(48);
        params.leftMargin = settleLeft + (dp(78) - baseWidth) / 2;
        params.topMargin = dp(8);
        indicator.setScaleX(1.16f);
        indicator.setScaleY(1f);
        indicator.setTranslationX(0f);
        indicator.setLayoutParams(params);
        indicatorLeft = settleLeft;
        ((LiquidGlassIndicator) indicator).setLiquidPressed(false);
        indicator.animate().scaleX(1f).scaleY(1f).setDuration(240)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private void animateNavigation(int tab, boolean animated) {
        moveLiquidIndicator(tab, animated);
        updateNavigationLabels(tab);
    }

    private void updateNavigationLabels(int selected) {
        for (int i = 0; i < items.getChildCount(); i++) {
            TextView label = (TextView) items.getChildAt(i);
            boolean active = i == selected;
            label.setTextColor(active ? 0xff17334f : 0xfff8fbff);
            label.setShadowLayer(dp(2), 0, dp(1), active ? 0x66ffffff : 0x66233c50);
            animateNavigationButtonScale(label, active);
        }
    }

    private void animateNavigationButtonScale(TextView button, boolean selected) {
        float fromScale = button.getScaleX();
        float toScale = selected ? 1.08f : 1f;
        ValueAnimator scale = ValueAnimator.ofFloat(fromScale, toScale);
        scale.setDuration(280);
        scale.setInterpolator(new DecelerateInterpolator());
        scale.addUpdateListener(animator -> {
            float value = (Float) animator.getAnimatedValue();
            button.setScaleX(value);
            button.setScaleY(value);
        });
        scale.start();
    }

    private void moveLiquidIndicator(int selected, boolean animated) {
        if (indicator == null || items.getChildCount() == 0) return;
        View target = items.getChildAt(selected);
        int indicatorHeight = dp(48);
        int indicatorWidth = dp(62);
        int targetLeft = items.getLeft() + target.getLeft() + (target.getWidth() - indicatorWidth) / 2;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) indicator.getLayoutParams();
        params.width = indicatorWidth;
        params.height = indicatorHeight;
        params.topMargin = dp(6);
        if (indicatorLeft < 0 || !animated) {
            indicatorLeft = targetLeft;
            params.leftMargin = targetLeft;
            indicator.setTranslationX(0f);
            indicator.setLayoutParams(params);
            return;
        }
        cancelFlow();
        int previousLeft = indicatorLeft;
        params.leftMargin = previousLeft;
        indicator.setTranslationX(0f);
        indicator.setLayoutParams(params);
        indicatorLeft = targetLeft;
        if (previousLeft == targetLeft) {
            indicator.setScaleX(1f);
            indicator.setScaleY(1f);
            ((LiquidGlassIndicator) indicator).setLiquidPressed(false);
            return;
        }
        indicator.setPivotX(indicatorWidth / 2f);
        indicator.setPivotY(indicatorHeight / 2f);
        ValueAnimator flow = ValueAnimator.ofFloat(0f, 1f);
        liquidNavigationFlow = flow;
        flow.setDuration(620);
        flow.setInterpolator(new PathInterpolator(.18f, .78f, .22f, 1f));
        flow.addUpdateListener(animation -> {
            float progress = (Float) animation.getAnimatedValue();
            float move = targetLeft - previousLeft;
            float stretch = progress < .24f ? progress / .24f
                    : progress < .48f ? 1f
                    : progress < .80f ? 1f - (progress - .48f) / .32f : 0f;
            float settle = progress < .80f ? 0f : (progress - .80f) / .20f;
            float travel = progress < .43f ? 0f : (progress - .43f) / .57f;
            indicator.setTranslationX(move * travel);
            indicator.setScaleX(1f + 1.18f * stretch + .07f * settle);
            indicator.setScaleY(1f - .07f * stretch + .025f * settle);
        });
        flow.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (animation != liquidNavigationFlow) return;
                FrameLayout.LayoutParams settled = (FrameLayout.LayoutParams) indicator.getLayoutParams();
                settled.leftMargin = targetLeft;
                indicator.setTranslationX(0f);
                indicator.setScaleX(1f);
                indicator.setScaleY(1f);
                indicator.setPivotX(indicatorWidth / 2f);
                indicator.setLayoutParams(settled);
                ((LiquidGlassIndicator) indicator).setLiquidPressed(false);
                liquidNavigationFlow = null;
            }
        });
        pulseBottomNavigation();
        flow.start();
    }

    private void pressLiquidIndicator(boolean pressed) {
        indicator.animate().cancel();
        indicator.animate().scaleX(pressed ? 1.16f : 1f).scaleY(pressed ? 1.16f : 1f)
                .setDuration(pressed ? 110 : 260)
                .setInterpolator(pressed ? new DecelerateInterpolator() : new OvershootInterpolator(1.4f))
                .start();
        ((LiquidGlassIndicator) indicator).setLiquidPressed(pressed);
    }

    private void pulseBottomNavigation() {
        if (bottomNavigationPulse != null) {
            ValueAnimator previous = bottomNavigationPulse;
            bottomNavigationPulse = null;
            previous.cancel();
        }
        setPivotX(getWidth() / 2f);
        setPivotY(getHeight() / 2f);
        ValueAnimator pulse = ValueAnimator.ofFloat(0f, 1f);
        bottomNavigationPulse = pulse;
        pulse.setDuration(840);
        pulse.setInterpolator(new PathInterpolator(.22f, .76f, .26f, 1f));
        pulse.addUpdateListener(animation -> {
            float progress = (Float) animation.getAnimatedValue();
            float swell = progress < .28f ? progress / .28f
                    : progress < .55f ? 1f : 1f - (progress - .55f) / .45f;
            setScaleX(1f + .032f * swell);
            setScaleY(1f + .064f * swell);
        });
        pulse.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (animation != bottomNavigationPulse) return;
                setScaleX(1f);
                setScaleY(1f);
                bottomNavigationPulse = null;
            }
        });
        pulse.start();
    }

    private void cancelFlow() {
        if (liquidNavigationFlow != null) {
            ValueAnimator previous = liquidNavigationFlow;
            liquidNavigationFlow = null;
            previous.cancel();
        }
    }

    @Override protected void onDetachedFromWindow() {
        cancelNavigationHold();
        cancelFlow();
        if (bottomNavigationPulse != null) {
            bottomNavigationPulse.cancel();
            bottomNavigationPulse = null;
        }
        super.onDetachedFromWindow();
    }

    /** Handler wrapper avoids keeping a Context reference in pending gesture callbacks. */
    private final class HandlerCompat {
        private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        void postDelayed(Runnable runnable, long delay) { main.postDelayed(runnable, delay); }
        void removeCallbacks(Runnable runnable) { main.removeCallbacks(runnable); }
    }
}
