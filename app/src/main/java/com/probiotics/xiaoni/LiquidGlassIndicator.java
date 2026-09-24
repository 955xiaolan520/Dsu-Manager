package com.probiotics.xiaoni;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

public final class LiquidGlassIndicator extends View {
    private final RectF bounds;
    private final Paint fill;
    private final Paint highlight;
    private final RectF inner;
    private final Paint innerShadow;
    private final RectF lowerEdge;
    private boolean pressed;
    private final Paint refraction;
    private final Paint rim;
    private final RectF topReflection;

    public LiquidGlassIndicator(Context context) {
        super(context);
        this.fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.highlight = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.refraction = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.innerShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.bounds = new RectF();
        this.topReflection = new RectF();
        this.inner = new RectF();
        this.lowerEdge = new RectF();
        
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        fill.setShadowLayer(dp(8.0f), 0.0f, dp(3.0f), 0x66000000);
        highlight.setStyle(Paint.Style.STROKE);
        highlight.setStrokeWidth(dp(1.0f));
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(dp(1.0f));
        innerShadow.setStyle(Paint.Style.STROKE);
        innerShadow.setStrokeWidth(dp(2.0f));
    }

    private float dp(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    public void setLiquidPressed(boolean pressed) {
        this.pressed = pressed;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float dp = dp(1.0f);
        bounds.set(dp, dp, getWidth() - dp, getHeight() - dp);
        float radius = bounds.height() / 2.0f;
        
        fill.setShadowLayer(
            pressed ? dp(9.0f) : dp(4.0f), 
            0.0f, 
            pressed ? dp(5.0f) : dp(2.0f), 
            0x48000000
        );
        fill.setShader(new LinearGradient(
            0.0f, bounds.top, 
            0.0f, bounds.bottom,
            new int[]{0x68FFFFFF, 0x32FFFFFF, 0x1DAFAFAF}, 
            null, 
            Shader.TileMode.CLAMP
        ));
        canvas.drawRoundRect(bounds, radius, radius, fill);
        fill.setShader(null);
        
        highlight.setColor(0xD0FFFFFF);
        highlight.setStrokeWidth(dp(pressed ? 2.0f : 1.0f));
        topReflection.set(
            bounds.left + dp(7.0f), 
            bounds.top + dp(3.0f), 
            bounds.right - dp(7.0f), 
            bounds.top + dp(14.0f)
        );
        canvas.drawArc(topReflection, 198.0f, 144.0f, false, highlight);
        
        rim.setColor(pressed ? 0xE6FFFFFF : 0xB8FFFFFF);
        canvas.drawRoundRect(bounds, radius, radius, rim);
        
        inner.set(
            bounds.left + dp(4.0f), 
            bounds.top + dp(4.0f), 
            bounds.right - dp(4.0f), 
            bounds.bottom - dp(4.0f)
        );
        highlight.setColor(0x35FFFFFF);
        highlight.setStrokeWidth(dp(1.0f));
        canvas.drawRoundRect(inner, radius - dp(3.0f), radius - dp(3.0f), highlight);
        
        refraction.setShader(new RadialGradient(
            bounds.centerX(), 
            bounds.top + dp(4.0f), 
            bounds.width() * 0.68f,
            new int[]{0x52FFFFFF, 0x1D8DD6EB, 0x008088BB}, 
            null, 
            Shader.TileMode.CLAMP
        ));
        canvas.drawRoundRect(inner, radius - dp(3.0f), radius - dp(3.0f), refraction);
        refraction.setShader(null);
        
        refraction.setStyle(Paint.Style.STROKE);
        refraction.setStrokeWidth(dp(1.0f));
        refraction.setColor(0x6A7EBFDE);
        canvas.drawArc(bounds, 8.0f, 164.0f, false, refraction);
        refraction.setColor(0x468EBADB);
        canvas.drawArc(bounds, 188.0f, 150.0f, false, refraction);
        
        innerShadow.setColor(0x3D3D3D00);
        lowerEdge.set(
            bounds.left + dp(3.0f), 
            bounds.top + dp(3.0f), 
            bounds.right - dp(3.0f), 
            bounds.bottom - dp(1.0f)
        );
        canvas.drawArc(lowerEdge, 12.0f, 156.0f, false, innerShadow);
    }
}
