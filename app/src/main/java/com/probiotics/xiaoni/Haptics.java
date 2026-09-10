package com.probiotics.xiaoni;

import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

final class Haptics {
    private static long lastFeedbackAt;

    private Haptics() { }

    static void onTouch(View root, MotionEvent event) {
        if (root == null || event.getActionMasked() != MotionEvent.ACTION_UP) return;
        View target = findClickable(root, event.getX(), event.getY());
        if (target != null) perform(target);
    }

    static void perform(View view) {
        long now = android.os.SystemClock.uptimeMillis();
        if (view != null && now - lastFeedbackAt > 180) {
            lastFeedbackAt = now;
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
    }

    static void performImmediate(View view) {
        if (view != null) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private static View findClickable(View view, float x, float y) {
        if (!view.isShown() || x < 0 || y < 0 || x > view.getWidth() || y > view.getHeight()) return null;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View child = group.getChildAt(i);
                View target = findClickable(child, x - child.getLeft(), y - child.getTop());
                if (target != null) return target;
            }
        }
        return view.isClickable() ? view : null;
    }
}
