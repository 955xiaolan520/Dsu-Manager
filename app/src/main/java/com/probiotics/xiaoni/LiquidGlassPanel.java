package com.probiotics.xiaoni;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.widget.LinearLayout;

public final class LiquidGlassPanel extends LinearLayout {
    private final RectF box;
    private float cornerRadius;
    private final Paint glow;
    private final Paint rim;
    private final Paint surface;

    public LiquidGlassPanel(Context context) {
        this(context, 28.0f);
    }

    public LiquidGlassPanel(Context context, float cornerRadius) {
        super(context);
        this.surface = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.box = new RectF();
        this.cornerRadius = cornerRadius;
        setWillNotDraw(false);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(dp(1.0f));
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(dp(1.0f));
    }

    private float dp(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float dp = dp(1.0f);
        box.set(dp, dp, getWidth() - dp, getHeight() - dp);
        float minRadius = Math.min(dp(cornerRadius), box.height() / 2.0f);
        
        surface.setShader(new LinearGradient(
            0.0f, box.top, 
            getWidth(), box.bottom,
            new int[]{0xD6FFFFFF, 0x9AFFFFFF, 0xB8FFFFFF}, 
            null, 
            Shader.TileMode.MIRROR
        ));
        canvas.drawRoundRect(box, minRadius, minRadius, surface);
        surface.setShader(null);
        
        rim.setColor(0x7DFFFFFF);
        canvas.drawRoundRect(box, minRadius, minRadius, rim);
        
        glow.setColor(0x68FFFFFF);
        box.set(
            box.left + dp(2.0f), 
            box.top + dp(2.0f), 
            box.right - dp(2.0f), 
            box.top + dp(13.0f)
        );
        canvas.drawArc(box, 190.0f, 160.0f, false, glow);
    }
}
