package com.probiotics.xiaoni;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** Draws the selected tab as a bright, elastic liquid-glass capsule. */
final class LiquidGlassIndicator extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlight = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint refraction = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final RectF topReflection = new RectF();
    private final RectF inner = new RectF();
    private final RectF lowerEdge = new RectF();
    private boolean pressed;

    LiquidGlassIndicator(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        fill.setShadowLayer(dp(8), 0, dp(3), 0x66000000);
        highlight.setStyle(Paint.Style.STROKE);
        highlight.setStrokeWidth(dp(1));
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(dp(1));
        innerShadow.setStyle(Paint.Style.STROKE);
        innerShadow.setStrokeWidth(dp(2));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    void setLiquidPressed(boolean pressed) {
        this.pressed = pressed;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float inset = dp(1);
        bounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
        float radius = bounds.height() / 2f;
        fill.setShadowLayer(pressed ? dp(9) : dp(4), 0, pressed ? dp(5) : dp(2), 0x4827465c);
        fill.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                new int[]{0x68f8fdff, 0x32d9ecf4, 0x1da4cad9}, null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(bounds, radius, radius, fill);
        fill.setShader(null);

        highlight.setColor(0xd0ffffff);
        highlight.setStrokeWidth(dp(pressed ? 2 : 1));
        topReflection.set(bounds.left + dp(7), bounds.top + dp(3), bounds.right - dp(7), bounds.top + dp(14));
        canvas.drawArc(topReflection, 198, 144, false, highlight);
        rim.setColor(pressed ? 0xe6ffffff : 0xb8f4fbff);
        canvas.drawRoundRect(bounds, radius, radius, rim);

        inner.set(bounds.left + dp(4), bounds.top + dp(4), bounds.right - dp(4), bounds.bottom - dp(4));
        highlight.setColor(0x35ffffff);
        highlight.setStrokeWidth(dp(1));
        canvas.drawRoundRect(inner, radius - dp(3), radius - dp(3), highlight);

        refraction.setShader(new RadialGradient(bounds.centerX(), bounds.top + dp(4), bounds.width() * .68f,
                new int[]{0x52ffffff, 0x1d8fd7eb, 0x0080b9db}, null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(inner, radius - dp(3), radius - dp(3), refraction);
        refraction.setShader(null);
        refraction.setStyle(Paint.Style.STROKE);
        refraction.setStrokeWidth(dp(1));
        refraction.setColor(0x6a70c9e2);
        canvas.drawArc(bounds, 8, 164, false, refraction);
        refraction.setColor(0x468b86da);
        canvas.drawArc(bounds, 188, 150, false, refraction);

        innerShadow.setColor(0x3d456b80);
        lowerEdge.set(bounds.left + dp(3), bounds.top + dp(3), bounds.right - dp(3), bounds.bottom - dp(1));
        canvas.drawArc(lowerEdge, 12, 156, false, innerShadow);
    }

}
