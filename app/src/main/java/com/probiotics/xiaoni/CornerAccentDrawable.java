package com.probiotics.xiaoni;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * DNA liquid-glass page backdrop: a quiet blue-gray base with independent,
 * edge-anchored color washes. The accents fade before reaching the content
 * center so they read as corner color fields rather than large oval blobs.
 */
public final class CornerAccentDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final int baseTop;
    private final int baseBottom;
    private final int[] colors;
    private final float[][] centers;
    private final int[] radii;
    private int drawableAlpha = 255;
    private ColorFilter colorFilter;

    public CornerAccentDrawable(int baseTop, int baseBottom, int[] colors,
                                float[][] centers, int[] radii) {
        this.baseTop = baseTop;
        this.baseBottom = baseBottom;
        this.colors = colors == null ? new int[0] : colors.clone();
        this.centers = centers == null ? new float[0][0] : centers.clone();
        this.radii = radii == null ? new int[0] : radii.clone();
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;

        paint.setAlpha(drawableAlpha);
        paint.setColorFilter(colorFilter);
        paint.setShader(new LinearGradient(
                bounds.left, bounds.top, bounds.left, bounds.bottom,
                baseTop, baseBottom, Shader.TileMode.CLAMP));
        canvas.drawRect(bounds, paint);

        int count = Math.min(colors.length, Math.min(centers.length, radii.length));
        float[] stops = new float[]{0f, 0.36f, 0.76f, 1f};
        for (int i = 0; i < count; i++) {
            int radius = Math.max(1, radii[i]);
            float centerX = bounds.left + bounds.width() * centers[i][0];
            float centerY = bounds.top + bounds.height() * centers[i][1];
            int rgb = colors[i] & 0x00ffffff;
            int[] wash = new int[]{
                    0xB0000000 | rgb,
                    0x76000000 | rgb,
                    0x26000000 | rgb,
                    0x00000000
            };
            paint.setShader(new RadialGradient(centerX, centerY, radius, wash, stops, Shader.TileMode.CLAMP));
            canvas.drawRect(bounds, paint);
        }
        paint.setShader(null);
    }

    @Override
    public void setAlpha(int alpha) {
        drawableAlpha = Math.max(0, Math.min(255, alpha));
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        colorFilter = filter;
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
