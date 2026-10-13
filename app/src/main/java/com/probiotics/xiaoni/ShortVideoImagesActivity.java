package com.probiotics.xiaoni;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** In-app full-screen viewer for multi-image short-video posts. */
public final class ShortVideoImagesActivity extends BaseActivity {
    public static final String EXTRA_IMAGES = "image_urls";
    public static final String EXTRA_INDEX = "image_index";
    public static final String EXTRA_TITLE = "image_title";
    public static final String EXTRA_REFERER = "image_referer";
    public static final String EXTRA_LOCAL_FILE = "local_image_file";

    private final ExecutorService imageWorker = Executors.newSingleThreadExecutor();
    private final Handler controlsHandler = new Handler(Looper.getMainLooper());
    private ArrayList<String> imageUrls = new ArrayList<>();
    private String title = "";
    private String referer = "";
    private String localFilePath = "";
    private int index;
    private ImageView imageView;
    private TextView statusView;
    private TextView counterView;
    private TextView previousButton;
    private TextView nextButton;
    private View topControls;
    private View bottomControls;
    private boolean controlsVisible = true;
    private final Runnable hideControls = () -> setControlsVisible(false);
    private boolean english;
    private int requestGeneration;

    private String t(String zh, String en) { return english ? en : zh; }
    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int languageMode = getSharedPreferences("settings", MODE_PRIVATE).getInt("language_mode", 0);
        english = languageMode == 2 || (languageMode == 0 && java.util.Locale.getDefault().getLanguage().equals("en"));
        ArrayList<String> incoming = getIntent().getStringArrayListExtra(EXTRA_IMAGES);
        if (incoming != null) imageUrls = incoming;
        title = getIntent().getStringExtra(EXTRA_TITLE);
        referer = getIntent().getStringExtra(EXTRA_REFERER);
        localFilePath = getIntent().getStringExtra(EXTRA_LOCAL_FILE);
        if (title == null) title = t("图片作品", "Image post");
        if (referer == null) referer = "";
        index = Math.max(0, Math.min(getIntent().getIntExtra(EXTRA_INDEX, 0), Math.max(0, imageUrls.size() - 1)));
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        setContentView(buildUi());
        enterImmersiveMode();
        if (localFilePath != null && !localFilePath.isBlank()) {
            counterView.setVisibility(View.GONE);
            bottomControls.setVisibility(View.GONE);
            showLocalImage(localFilePath);
        } else {
            showImage(index);
        }
    }

    private GradientDrawable rounded(int color, int stroke, int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radius));
        if (stroke != 0) bg.setStroke(Math.max(1, dp(1)), stroke);
        return bg;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private TextView pill(String value, int width) {
        TextView view = text(value, 12, Color.WHITE, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(10), 0, dp(10), 0);
        view.setBackground(rounded(0xb5202938, 0x66ffffff, 20));
        view.setLayoutParams(new LinearLayout.LayoutParams(width, dp(44)));
        return view;
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(imageView, new FrameLayout.LayoutParams(-1, -1));

        GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                toggleControls();
                return true;
            }
            @Override public boolean onFling(MotionEvent first, MotionEvent last, float velocityX, float velocityY) {
                float dx = last.getX() - first.getX();
                float dy = last.getY() - first.getY();
                if (Math.abs(dx) > dp(55) && Math.abs(dx) > Math.abs(dy)) {
                    if (dx < 0) nextImage(); else previousImage();
                    return true;
                }
                return false;
            }
        });
        imageView.setOnTouchListener((v, event) -> gestures.onTouchEvent(event));

        statusView = text(t("正在加载图片…", "Loading image…"), 13, Color.WHITE, true);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(15), dp(11), dp(15), dp(11));
        statusView.setBackground(rounded(0xb5202938, 0x66ffffff, 17));
        root.addView(statusView, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(6), dp(8), dp(6));
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        topLp.leftMargin = dp(10);
        topLp.rightMargin = dp(10);
        topControls = top;
        root.addView(top, topLp);
        TextView back = pill("‹", dp(48));
        top.addView(back);
        back.setOnClickListener(v -> finish());
        TextView heading = text(title, 12, Color.WHITE, true);
        heading.setSingleLine(true);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(12), 0, dp(12), 0);
        heading.setBackground(rounded(0xb5202938, 0x66ffffff, 20));
        LinearLayout.LayoutParams headingLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        headingLp.setMargins(dp(8), 0, dp(8), 0);
        top.addView(heading, headingLp);
        counterView = pill("1 / 1", dp(66));
        top.addView(counterView);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(10), dp(8), dp(10), dp(8));
        bottom.setBackground(rounded(0xb5202938, 0x66ffffff, 22));
        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(-1, dp(54), Gravity.BOTTOM);
        bottomLp.setMargins(dp(16), 0, dp(16), dp(18));
        bottomControls = bottom;
        root.addView(bottom, bottomLp);
        previousButton = pill(t("‹  上一张", "‹  Previous"), -1);
        nextButton = pill(t("下一张  ›", "Next  ›"), -1);
        bottom.addView(previousButton, new LinearLayout.LayoutParams(0, dp(42), 1f));
        TextView center = text("", 11, Color.WHITE, true);
        center.setGravity(Gravity.CENTER);
        bottom.addView(center, new LinearLayout.LayoutParams(dp(20), -1));
        bottom.addView(nextButton, new LinearLayout.LayoutParams(0, dp(42), 1f));
        previousButton.setOnClickListener(v -> previousImage());
        nextButton.setOnClickListener(v -> nextImage());

        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars());
            topLp.topMargin = bars.top + dp(8);
            top.setLayoutParams(topLp);
            bottomLp.bottomMargin = bars.bottom + dp(12);
            bottom.setLayoutParams(bottomLp);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        return root;
    }

    private void toggleControls() {
        setControlsVisible(!controlsVisible);
    }

    private void setControlsVisible(boolean visible) {
        controlsVisible = visible;
        int state = visible ? View.VISIBLE : View.GONE;
        if (topControls != null) topControls.setVisibility(state);
        if (bottomControls != null) bottomControls.setVisibility(state);
        controlsHandler.removeCallbacks(hideControls);
        if (visible) controlsHandler.postDelayed(hideControls, 3500);
    }

    private void showImage(int selected) {
        if (imageUrls.isEmpty()) {
            statusView.setText(t("解析结果没有图片链接", "No image URLs in parser result"));
            return;
        }
        index = Math.max(0, Math.min(selected, imageUrls.size() - 1));
        setControlsVisible(true);
        final int generation = ++requestGeneration;
        String selectedUrl = imageUrls.get(index);
        counterView.setText((index + 1) + " / " + imageUrls.size());
        statusView.setVisibility(View.VISIBLE);
        statusView.setText(t("正在加载图片 " + (index + 1) + "…", "Loading image " + (index + 1) + "…"));
        imageWorker.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(selectedUrl).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(25000);
                connection.setRequestProperty("User-Agent", "DsuManager/" + BuildConfig.VERSION_NAME);
                if (!referer.isBlank()) connection.setRequestProperty("Referer", referer);
                connection.connect();
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[16 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1 && output.size() < 40 * 1024 * 1024) output.write(buffer, 0, read);
                    byte[] bytes = output.toByteArray();
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                    int sample = 1;
                    while (Math.max(bounds.outWidth, bounds.outHeight) / sample > 3072) sample *= 2;
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = sample;
                    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed() || generation != requestGeneration) {
                            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                        } else if (bitmap == null) {
                            statusView.setText(t("图片格式无法解码", "Image format could not be decoded"));
                        } else {
                            imageView.setImageBitmap(bitmap);
                            statusView.setVisibility(View.GONE);
                            setControlsVisible(true);
                        }
                    });
                }
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!isFinishing() && generation == requestGeneration)
                        statusView.setText(t("图片加载失败：链接可能过期或源站限制访问。", "Image load failed; the link may have expired or the host may restrict access."));
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void previousImage() { if (!imageUrls.isEmpty()) showImage((index - 1 + imageUrls.size()) % imageUrls.size()); }
    private void nextImage() { if (!imageUrls.isEmpty()) showImage((index + 1) % imageUrls.size()); }

    private void showLocalImage(String path) {
        statusView.setVisibility(View.VISIBLE);
        statusView.setText(t("正在打开图片…", "Opening image…"));
        imageWorker.execute(() -> {
            Bitmap bitmap = null;
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, bounds);
                int largest = Math.max(1, Math.max(bounds.outWidth, bounds.outHeight));
                int sample = 1;
                while (largest / sample > 4096) sample *= 2;
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sample;
                bitmap = BitmapFactory.decodeFile(path, options);
            } catch (Throwable ignored) { }
            final Bitmap result = bitmap;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    if (result != null && !result.isRecycled()) result.recycle();
                } else if (result == null) {
                    statusView.setText(t("图片无法读取", "Unable to read image"));
                } else {
                    imageView.setImageBitmap(result);
                    statusView.setVisibility(View.GONE);
                    setControlsVisible(true);
                }
            });
        });
    }

    private void enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.statusBars() | WindowInsetsCompat.Type.navigationBars());
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersiveMode();
    }

    @Override protected void onResume() { super.onResume(); enterImmersiveMode(); }
    @Override protected void onDestroy() { controlsHandler.removeCallbacksAndMessages(null); imageWorker.shutdownNow(); super.onDestroy(); }
}
