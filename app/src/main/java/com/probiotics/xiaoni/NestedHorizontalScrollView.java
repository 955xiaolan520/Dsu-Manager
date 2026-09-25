package com.probiotics.xiaoni;

import android.content.Context;
import android.view.MotionEvent;
import android.widget.HorizontalScrollView;

/**
 * 支持嵌套在 ScrollView 内的 HorizontalScrollView
 * 根据滑动方向自动判断：水平滑动自己处理，垂直滑动交给父 ScrollView
 */
public class NestedHorizontalScrollView extends HorizontalScrollView {
    private float xDistance, yDistance, lastX, lastY;

    public NestedHorizontalScrollView(Context context) {
        super(context);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                xDistance = yDistance = 0f;
                lastX = ev.getX();
                lastY = ev.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                final float curX = ev.getX();
                final float curY = ev.getY();
                xDistance += Math.abs(curX - lastX);
                yDistance += Math.abs(curY - lastY);
                lastX = curX;
                lastY = curY;
                // 如果水平滑动距离 > 垂直滑动距离，拦截事件，禁止父 ScrollView 处理
                if (xDistance > yDistance) {
                    return true;
                }
        }
        return super.onInterceptTouchEvent(ev);
    }
}
