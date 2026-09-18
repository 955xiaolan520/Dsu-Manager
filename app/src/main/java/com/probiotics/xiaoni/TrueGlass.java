package com.probiotics.xiaoni;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;

/**
 * 原汁原味的液态玻璃（Liquid Glass）绘制组件。
 *
 * LiquidBackdrop：底层动态彩色弥散光斑背景 —— 玻璃的"折射感"来自背景色彩在玻璃后方的流动。
 * TrueGlassPanel：玻璃面板 —— 多层结构模拟真实玻璃：
 *   ① 柔和弥散投影（elevation 阴影 + 环境色）
 *   ② 斜向半透明渐变主体（玻璃厚度不均，左上最透亮）
 *   ③ 底部青蓝色色彩渗透（背景色"折射"进玻璃）
 *   ④ 渐变高光描边（顶部亮、侧腰收、底部回亮 —— 玻璃边缘的菲涅尔反射）
 *   ⑤ 顶部内侧反光弧 + 光泽带 + 左上角光源亮斑
 */
public final class TrueGlass {

    // ============================ 动态光斑背景 ============================

    public static final class LiquidBackdrop extends View {
        private static final long CYCLE_MS = 14000L;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ValueAnimator drift;
        private float phase;

        /** 光斑定义：cx, cy（比例）, 半径比例, 中心色, 边缘透明 */
        private static final float[][] BLOBS = {
                {0.22f, 0.16f, 0.62f}, // 天蓝
                {0.86f, 0.10f, 0.55f}, // 青绿
                {0.72f, 0.88f, 0.66f}, // 淡紫
                {0.12f, 0.74f, 0.58f}, // 纯白高光
                {0.50f, 0.46f, 0.50f}, // 亮青
                {0.55f, 1.12f, 0.70f}, // 底部深蓝压暗
        };
        private static final int[] COLORS = {
                0x9FA8D4F5, 0x7A79D9CE, 0x60B9A8EC, 0x80FFFFFF, 0x5296DCE8, 0x4551789E,
        };

        public LiquidBackdrop(Context context) {
            super(context);
            setLayerType(LAYER_TYPE_HARDWARE, null);
            drift = ValueAnimator.ofFloat(0f, 1f);
            drift.setDuration(CYCLE_MS);
            drift.setRepeatCount(ValueAnimator.INFINITE);
            drift.setRepeatMode(ValueAnimator.REVERSE);
            drift.setInterpolator(new LinearInterpolator());
            drift.addUpdateListener(a -> {
                phase = (float) a.getAnimatedValue();
                invalidate();
            });
        }

        public void startDrift() {
            if (!drift.isStarted()) drift.start();
        }

        public void stopDrift() {
            drift.cancel();
        }

        @Override protected void onDetachedFromWindow() {
            drift.cancel();
            super.onDetachedFromWindow();
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            // 1. 基底：上浅下深的蓝灰渐变
            paint.setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{0xFFE9F0F8, 0xFFD3DFEC, 0xFFB8C9DC, 0xFFA3B8CF},
                    new float[]{0f, 0.42f, 0.78f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);

            // 2. 弥散光斑（缓慢漂移 + 轻微呼吸缩放）
            for (int i = 0; i < BLOBS.length; i++) {
                float[] blob = BLOBS[i];
                float sway = (float) Math.sin(phase * Math.PI * 2 + i * 1.9);
                float breath = 0.92f + 0.08f * (float) Math.cos(phase * Math.PI * 2 + i * 0.7);
                float cx = (blob[0] + 0.05f * sway) * w;
                float cy = (blob[1] + 0.04f * (float) Math.cos(phase * Math.PI * 2 + i)) * h;
                float radius = blob[2] * Math.max(w, h) * breath;
                paint.setShader(new RadialGradient(cx, cy, radius,
                        COLORS[i], 0x00000000, Shader.TileMode.CLAMP));
                canvas.drawCircle(cx, cy, radius, paint);
            }
            paint.setShader(null);

            // 3. 顶部整片柔光，让玻璃有"天空反光"
            paint.setShader(new LinearGradient(0, 0, 0, h * 0.4f,
                    0x5AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h * 0.4f, paint);
            paint.setShader(null);
        }
    }

    // ============================ 玻璃面板 ============================

    /** 真正的液态玻璃面板：渐变主体 + 折射色渗透 + 菲涅尔渐变描边 + 多层高光 */
    public static final class TrueGlassPanel extends LinearLayout {
        private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint spot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint streak = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private float cornerDp = 26f;
        private boolean pressed;

        public TrueGlassPanel(Context context) {
            this(context, 26f);
        }

        public TrueGlassPanel(Context context, float radiusDp) {
            super(context);
            cornerDp = radiusDp;
            setWillNotDraw(false);
            rim.setStyle(Paint.Style.STROKE);
            inner.setStyle(Paint.Style.STROKE);
            sheen.setStyle(Paint.Style.STROKE);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    float r = dp(cornerDp);
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
                }
            });
            setElevation(dp(6));
            setOutlineSpotShadowColor(0x33395273);
            setOutlineAmbientShadowColor(0x24395273);
        }

        public void setPressedLook(boolean pressed) {
            if (this.pressed != pressed) {
                this.pressed = pressed;
                invalidate();
            }
        }

        private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            float r = Math.min(dp(cornerDp), Math.min(w, h) / 2f);
            float inset = dp(0.75f);
            box.set(inset, inset, w - inset, h - inset);

            float lift = pressed ? 0.06f : 0f;

            // ① 玻璃主体：135° 斜向渐变（左上最透亮 → 右腰收敛 → 底部回白），厚度不均感
            body.setShader(new LinearGradient(0, 0, w, h,
                    new int[]{
                            (int) (0xC4 + 24 * lift) << 24 | 0xFFFFFF,
                            0x84FFFFFF,
                            (int) (0xA0 + 20 * lift) << 24 | 0xFFFFFF,
                            0x94FFFFFF},
                    new float[]{0f, 0.38f, 0.72f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(box, r, r, body);

            // ①.b 玻璃内折射色斑：背景的蓝 / 青 / 紫透过玻璃被看见（液态玻璃的灵魂）
            spot.setShader(new RadialGradient(w * 0.90f, h * 0.14f, Math.max(w, h) * 0.48f,
                    0x2C8FB8E8, 0x008FB8E8, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(box, r, r, spot);
            spot.setShader(new RadialGradient(w * 0.06f, h * 0.90f, Math.max(w, h) * 0.42f,
                    0x28B9A8EC, 0x00B9A8EC, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(box, r, r, spot);
            spot.setShader(null);

            // ② 色彩渗透：玻璃底部折射出的背景青蓝
            body.setShader(new LinearGradient(0, h * 0.55f, 0, h,
                    0x009FBFD8, 0x2E9FBFD8, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(box, r, r, body);
            body.setShader(null);

            // ②.b 斜向镜面反光带：一道高光从左上扫向右腰（玻璃表面对环境光的镜面反射）
            streak.setShader(new LinearGradient(0, 0, w, h,
                    new int[]{0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF, 0x00FFFFFF},
                    new float[]{0.14f, 0.32f, 0.50f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(box, r, r, streak);
            streak.setShader(null);

            // ③ 菲涅尔渐变描边：顶亮 → 侧收 → 底部回亮（真实玻璃边缘反射规律）
            rim.setStrokeWidth(dp(1.2f));
            rim.setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{0xE6FFFFFF, 0x59FFFFFF, 0x2EFFFFFF, 0x8CFFFFFF},
                    new float[]{0f, 0.30f, 0.62f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(box, r, r, rim);
            rim.setShader(null);

            // ④ 顶部内侧反光弧（上边缘玻璃厚度反光）
            sheen.setStrokeWidth(dp(2.2f));
            sheen.setStrokeCap(Paint.Cap.ROUND);
            sheen.setColor(0xB8FFFFFF);
            RectF arc = new RectF(box.left + dp(10), box.top + dp(1), box.right - dp(10), box.top + dp(20));
            canvas.drawArc(arc, 205, 130, false, sheen);

            // ⑤ 内侧光泽带：顶部 30% 高度的白色渐变
            inner.setStrokeWidth(dp(1));
            inner.setShader(new LinearGradient(0, box.top, 0, box.top + h * 0.3f,
                    0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(new RectF(box.left + dp(2), box.top + dp(2), box.right - dp(2), box.bottom - dp(2)), r - dp(2), r - dp(2), inner);
            inner.setShader(null);

            // ⑥ 左上角光源亮斑（径向高光，模拟窗光源反射）
            spot.setShader(new RadialGradient(box.left + w * 0.16f, box.top + h * 0.10f,
                    Math.min(w, h) * 0.55f,
                    0x54FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(box, r, r, spot);
            spot.setShader(null);
        }
    }

    // ============================ 玻璃按钮工厂 ============================

    /** 蓝色液态玻璃主按钮背景（代码绘制渐变，配合 TrueGlassButton 用） */
    public static android.graphics.drawable.Drawable primaryButtonBg(Context context, float radiusDp) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TL_BR);
        gd.setColors(new int[]{0xFF6C9BF2, 0xFF4472DE, 0xFF3F66CF});
        float d = radiusDp * context.getResources().getDisplayMetrics().density;
        gd.setCornerRadii(new float[]{d, d, d, d, d, d, d * 0.92f, d * 0.92f});
        gd.setStroke((int) (context.getResources().getDisplayMetrics().density * 1.2f), 0xCCFFFFFF);
        return gd;
    }

    /** 白色玻璃次按钮背景 */
    public static android.graphics.drawable.Drawable secondaryButtonBg(Context context, float radiusDp) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TL_BR);
        gd.setColors(new int[]{0xE6FFFFFF, 0xB4FFFFFF, 0xC8FFFFFF});
        float d = radiusDp * context.getResources().getDisplayMetrics().density;
        gd.setCornerRadius(d);
        gd.setStroke((int) (context.getResources().getDisplayMetrics().density * 1f), 0x8CFFFFFF);
        return gd;
    }

    private TrueGlass() {}
}
