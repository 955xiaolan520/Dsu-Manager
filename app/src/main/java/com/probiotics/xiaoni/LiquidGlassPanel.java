package com.probiotics.xiaoni;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.widget.LinearLayout;

/** Shared static glass surface for the native View screens. */
final class LiquidGlassPanel extends LinearLayout {
    private final Paint surface = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();

    LiquidGlassPanel(Context context) {
        super(context);
        setWillNotDraw(false);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(dp(1));
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(dp(1));
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }

    @Override protected void onDraw(Canvas canvas) {
        float inset = dp(1);
        box.set(inset, inset, getWidth() - inset, getHeight() - inset);
        float radius = Math.min(dp(28), box.height() / 2);
        surface.setShader(new LinearGradient(0, box.top, getWidth(), box.bottom,
                new int[]{0xd6f3f7fb, 0x9bbcc7d2, 0xb8eef3f7}, null, Shader.TileMode.MIRROR));
        canvas.drawRoundRect(box, radius, radius, surface);
        surface.setShader(null);
        rim.setColor(0x7dffffff);
        canvas.drawRoundRect(box, radius, radius, rim);
        glow.setColor(0x68ffffff);
        box.set(box.left + dp(2), box.top + dp(2), box.right - dp(2), box.top + dp(13));
        canvas.drawArc(box, 190, 160, false, glow);
    }
}
