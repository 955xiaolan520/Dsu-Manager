package com.probiotics.xiaoni;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Immersive in-app player for parsed short-video links. */
public final class ShortVideoPreviewActivity extends BaseActivity {
    public static final String EXTRA_VIDEO_URL = "video_url";
    public static final String EXTRA_COVER_URL = "cover_url";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_REFERER = "referer";

    private final ExecutorService imageExecutor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout playerRoot;
    private TextureView textureView;
    private ImageView coverView;
    private TextView statusView;
    private TextView rotateButton;
    private TextView playButton;
    private TextView positionView;
    private TextView durationView;
    private SeekBar seekBar;
    private View videoTopControls;
    private View videoBottomControls;
    private View videoSourceLabel;
    private boolean videoControlsVisible = true;
    private final Runnable hideVideoControls = () -> setVideoControlsVisible(false);
    private MediaPlayer player;
    private Surface playerSurface;
    private boolean english;
    private boolean prepared;
    private boolean dragging;
    private boolean wasPlayingBeforePause;
    private boolean firstFrameSeen;
    private boolean coverWasRequested;
    private int videoWidth;
    private int videoHeight;
    private int resumePosition;
    private String videoUrl;
    private String coverUrl;
    private String referer;
    private String title;

    private final Runnable progressUpdater = new Runnable() {
        @Override public void run() {
            if (player != null && prepared) {
                try {
                    int position = player.getCurrentPosition();
                    int duration = Math.max(0, player.getDuration());
                    if (!dragging) seekBar.setProgress(position);
                    seekBar.setMax(Math.max(1, duration));
                    positionView.setText(formatTime(position));
                    durationView.setText(formatTime(duration));
                    playButton.setText(player.isPlaying() ? "❚❚" : "▶");
                } catch (IllegalStateException ignored) { }
            }
            handler.postDelayed(this, 500);
        }
    };

    private String t(String zh, String en) { return english ? en : zh; }
    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private String safeExtra(String key) {
        String value = getIntent().getStringExtra(key);
        return value == null ? "" : value;
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int languageMode = getSharedPreferences("settings", MODE_PRIVATE).getInt("language_mode", 0);
        english = languageMode == 2 || (languageMode == 0 && java.util.Locale.getDefault().getLanguage().equals("en"));
        videoUrl = safeExtra(EXTRA_VIDEO_URL);
        coverUrl = safeExtra(EXTRA_COVER_URL);
        referer = safeExtra(EXTRA_REFERER);
        title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title == null || title.trim().isEmpty()) title = t("短视频预览", "Video preview");

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        setContentView(buildUi());
        enterImmersiveMode();
        loadCover();
        handler.post(progressUpdater);
    }

    private GradientDrawable rounded(int color, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (stroke != 0) drawable.setStroke(Math.max(1, dp(1)), stroke);
        return drawable;
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
        TextView view = text(value, 13, Color.WHITE, true);
        view.setGravity(Gravity.CENTER);
        view.setBackground(rounded(0xb51a2638, 0x75ffffff, 20));
        view.setLayoutParams(new LinearLayout.LayoutParams(width < 0 ? width : dp(width), dp(44)));
        return view;
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        playerRoot = root;
        root.setBackgroundColor(Color.BLACK);
        root.setOnClickListener(v -> toggleVideoControls());
        root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (videoWidth > 0 && videoHeight > 0) applyVideoTransform();
        });

        textureView = new TextureView(this);
        textureView.setOpaque(true);
        textureView.setOnClickListener(v -> toggleVideoControls());
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                preparePlayer(surface);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                applyVideoTransform();
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                if (player != null) {
                    try {
                        if (prepared) resumePosition = player.getCurrentPosition();
                        wasPlayingBeforePause = player.isPlaying();
                    } catch (IllegalStateException ignored) { }
                }
                releasePlayer();
                firstFrameSeen = false;
                if (!isFinishing()) {
                    coverView.setVisibility(View.VISIBLE);
                    statusView.setVisibility(View.VISIBLE);
                    statusView.setText(t("正在恢复视频画面…", "Restoring video picture…"));
                }
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                if (!firstFrameSeen) {
                    firstFrameSeen = true;
                    coverView.setVisibility(View.GONE);
                    statusView.setVisibility(View.GONE);
                    coverWasRequested = false;
                }
            }
        });
        root.addView(textureView, new FrameLayout.LayoutParams(-1, -1));

        coverView = new ImageView(this);
        coverView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        coverView.setBackgroundColor(Color.BLACK);
        coverView.setVisibility(View.VISIBLE);
        root.addView(coverView, new FrameLayout.LayoutParams(-1, -1));
        coverView.setOnClickListener(v -> {
            coverWasRequested = false;
            if (firstFrameSeen) coverView.setVisibility(View.GONE);
            toggleVideoControls();
        });

        statusView = text(t("正在连接视频…", "Connecting to video…"), 13, Color.WHITE, true);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(16), dp(11), dp(16), dp(11));
        statusView.setBackground(rounded(0xb51a2638, 0x66ffffff, 18));
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        root.addView(statusView, statusLp);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setPadding(dp(8), dp(6), dp(8), dp(6));
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        topLp.leftMargin = dp(10);
        topLp.rightMargin = dp(10);
        videoTopControls = top;
        root.addView(top, topLp);

        TextView back = pill("‹", 48);
        top.addView(back);
        back.setOnClickListener(v -> finish());

        TextView titleView = text(title, 12, Color.WHITE, true);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setGravity(Gravity.CENTER_VERTICAL);
        titleView.setPadding(dp(12), 0, dp(12), 0);
        titleView.setBackground(rounded(0xb51a2638, 0x75ffffff, 20));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        titleLp.setMargins(dp(7), 0, dp(7), 0);
        top.addView(titleView, titleLp);

        rotateButton = pill("横屏", 72);
        top.addView(rotateButton);
        LinearLayout.LayoutParams coverBtnLp = new LinearLayout.LayoutParams(dp(60), dp(44));
        coverBtnLp.leftMargin = dp(7);
        TextView coverButton = pill(t("封面", "Cover"), -1);
        coverButton.setLayoutParams(coverBtnLp);
        top.addView(coverButton);
        rotateButton.setOnClickListener(v -> { toggleOrientation(); setVideoControlsVisible(true); });
        coverButton.setOnClickListener(v -> showCover());
        updateRotateButton();

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setPadding(dp(10), dp(7), dp(10), dp(7));
        controls.setBackground(rounded(0xb51a2638, 0x66ffffff, 20));
        FrameLayout.LayoutParams controlsLp = new FrameLayout.LayoutParams(-1, dp(54), Gravity.BOTTOM);
        controlsLp.setMargins(dp(14), 0, dp(14), dp(20));
        videoBottomControls = controls;
        root.addView(controls, controlsLp);

        playButton = text("▶", 17, Color.WHITE, true);
        playButton.setGravity(Gravity.CENTER);
        controls.addView(playButton, new LinearLayout.LayoutParams(dp(44), -1));
        playButton.setOnClickListener(v -> togglePlayback());

        positionView = text("00:00", 10, Color.WHITE, false);
        positionView.setGravity(Gravity.CENTER);
        controls.addView(positionView, new LinearLayout.LayoutParams(dp(42), -1));

        seekBar = new SeekBar(this);
        seekBar.setMax(1);
        seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xff51d6cb));
        seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        controls.addView(seekBar, new LinearLayout.LayoutParams(0, dp(38), 1f));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { }
            @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                if (player != null && prepared) {
                    try { player.seekTo(bar.getProgress()); } catch (IllegalStateException ignored) { }
                }
            }
        });

        durationView = text("00:00", 10, Color.WHITE, false);
        durationView.setGravity(Gravity.CENTER);
        controls.addView(durationView, new LinearLayout.LayoutParams(dp(42), -1));

        TextView source = text(t("Dsu 应用内播放器", "Dsu in-app player"), 10, 0xffd6e2ed, false);
        source.setGravity(Gravity.CENTER);
        source.setBackground(rounded(0x7a101827, 0, 12));
        FrameLayout.LayoutParams sourceLp = new FrameLayout.LayoutParams(-2, dp(28), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sourceLp.bottomMargin = dp(82);
        videoSourceLabel = source;
        root.addView(source, sourceLp);

        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets safe = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars());
            topLp.topMargin = safe.top + dp(8);
            top.setLayoutParams(topLp);
            controlsLp.bottomMargin = Math.max(safe.bottom, dp(12)) + dp(12);
            controls.setLayoutParams(controlsLp);
            sourceLp.bottomMargin = controlsLp.bottomMargin + dp(62);
            source.setLayoutParams(sourceLp);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        return root;
    }

    private void toggleVideoControls() {
        setVideoControlsVisible(!videoControlsVisible);
    }

    private void setVideoControlsVisible(boolean visible) {
        videoControlsVisible = visible;
        int visibility = visible ? View.VISIBLE : View.GONE;
        if (videoTopControls != null) videoTopControls.setVisibility(visibility);
        if (videoBottomControls != null) videoBottomControls.setVisibility(visibility);
        if (videoSourceLabel != null) videoSourceLabel.setVisibility(visibility);
        handler.removeCallbacks(hideVideoControls);
        if (visible && player != null && prepared && player.isPlaying()) {
            handler.postDelayed(hideVideoControls, 3500);
        }
    }

    private void preparePlayer(SurfaceTexture surfaceTexture) {
        if (videoUrl.isBlank()) {
            statusView.setText(t("没有可播放的视频直链", "No playable video URL"));
            return;
        }
        releasePlayer();
        try {
            playerSurface = new Surface(surfaceTexture);
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build());
            player.setScreenOnWhilePlaying(true);
            player.setSurface(playerSurface);
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "DsuManager/" + BuildConfig.VERSION_NAME);
            if (!referer.isBlank()) headers.put("Referer", referer);
            player.setDataSource(this, Uri.parse(videoUrl), headers);
            player.setOnVideoSizeChangedListener((mp, width, height) -> {
                if (width > 0 && height > 0) {
                    videoWidth = width;
                    videoHeight = height;
                    applyVideoTransform();
                    startWhenReady();
                }
            });
            player.setOnPreparedListener(mp -> {
                prepared = true;
                if (videoWidth <= 0 || videoHeight <= 0) {
                    videoWidth = mp.getVideoWidth();
                    videoHeight = mp.getVideoHeight();
                }
                if (videoWidth > 0 && videoHeight > 0) {
                    applyVideoTransform();
                    startWhenReady();
                } else {
                    statusView.setVisibility(View.VISIBLE);
                    statusView.setText(t("接口暂未返回可显示的视频画面，正在检查视频轨道…",
                            "Waiting for a video track from the source…"));
                    handler.postDelayed(() -> {
                        if (prepared && (videoWidth <= 0 || videoHeight <= 0) && !firstFrameSeen) {
                            statusView.setText(t("未检测到视频画面轨道；该直链可能只有音频或编码不兼容。",
                                    "No video picture track detected; this link may be audio-only or use an unsupported codec."));
                        }
                    }, 3500);
                }
            });
            player.setOnInfoListener((mp, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) showVideoFrame();
                return false;
            });
            player.setOnCompletionListener(mp -> {
                playButton.setText("↻");
                wasPlayingBeforePause = false;
                setVideoControlsVisible(true);
            });
            player.setOnErrorListener((mp, what, extra) -> {
                prepared = false;
                setVideoControlsVisible(true);
                statusView.setVisibility(View.VISIBLE);
                statusView.setText(t("播放失败：源站限制、编码不兼容或链接已过期。可尝试下载后在 Dsu 管理器中播放。",
                        "Playback failed: source restriction, unsupported codec, or expired link. Try downloading it."));
                return true;
            });
            player.prepareAsync();
        } catch (Exception error) {
            statusView.setVisibility(View.VISIBLE);
            statusView.setText(t("播放器初始化失败：", "Could not initialize player: ")
                    + (error.getMessage() == null ? "" : error.getMessage()));
        }
    }

    private void startWhenReady() {
        if (player == null || !prepared || videoWidth <= 0 || videoHeight <= 0) return;
        try {
            if (resumePosition > 0) {
                player.seekTo(resumePosition);
                resumePosition = 0;
            }
            if (!player.isPlaying()) player.start();
            wasPlayingBeforePause = true;
            playButton.setText("❚❚");
            setVideoControlsVisible(true);
            statusView.setVisibility(View.VISIBLE);
            statusView.setText(t("视频已连接，正在显示首帧…", "Video connected; waiting for first frame…"));
        } catch (IllegalStateException error) {
            statusView.setVisibility(View.VISIBLE);
            statusView.setText(t("无法启动视频解码", "Could not start video decoder"));
        }
    }

    private void showVideoFrame() {
        firstFrameSeen = true;
        coverView.setVisibility(View.GONE);
        statusView.setVisibility(View.GONE);
        coverWasRequested = false;
    }

    /** Fit the TextureView itself to the source aspect ratio; never crop or stretch. */
    private void applyVideoTransform() {
        if (textureView == null || playerRoot == null || videoWidth <= 0 || videoHeight <= 0) return;
        int viewWidth = playerRoot.getWidth();
        int viewHeight = playerRoot.getHeight();
        if (viewWidth <= 0 || viewHeight <= 0) return;

        float videoAspect = videoWidth / (float) videoHeight;
        float availableAspect = viewWidth / (float) viewHeight;
        int fittedWidth;
        int fittedHeight;
        if (videoAspect >= availableAspect) {
            fittedWidth = viewWidth;
            fittedHeight = Math.max(1, Math.round(viewWidth / videoAspect));
        } else {
            fittedHeight = viewHeight;
            fittedWidth = Math.max(1, Math.round(viewHeight * videoAspect));
        }

        FrameLayout.LayoutParams videoParams = (FrameLayout.LayoutParams) textureView.getLayoutParams();
        if (videoParams.width != fittedWidth || videoParams.height != fittedHeight || videoParams.gravity != Gravity.CENTER) {
            videoParams.width = fittedWidth;
            videoParams.height = fittedHeight;
            videoParams.gravity = Gravity.CENTER;
            textureView.setLayoutParams(videoParams);
        }
        textureView.setTransform(new Matrix());

        if (coverView != null) {
            FrameLayout.LayoutParams coverParams = (FrameLayout.LayoutParams) coverView.getLayoutParams();
            if (coverParams.width != fittedWidth || coverParams.height != fittedHeight || coverParams.gravity != Gravity.CENTER) {
                coverParams.width = fittedWidth;
                coverParams.height = fittedHeight;
                coverParams.gravity = Gravity.CENTER;
                coverView.setLayoutParams(coverParams);
            }
        }
    }

    private void loadCover() {
        if (coverUrl.isBlank()) {
            coverView.setVisibility(View.GONE);
            return;
        }
        imageExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(coverUrl).openConnection();
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(20000);
                connection.setRequestProperty("User-Agent", "DsuManager/" + BuildConfig.VERSION_NAME);
                if (!referer.isBlank()) connection.setRequestProperty("Referer", referer);
                try (InputStream stream = connection.getInputStream()) {
                    Bitmap bitmap = BitmapFactory.decodeStream(stream);
                    if (bitmap != null) runOnUiThread(() -> {
                        if (!isFinishing() && !firstFrameSeen && !coverWasRequested) {
                            coverView.setImageBitmap(bitmap);
                            coverView.setVisibility(View.VISIBLE);
                        }
                    });
                }
            } catch (Exception ignored) {
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void showCover() {
        if (coverView.getDrawable() == null) {
            statusView.setVisibility(View.VISIBLE);
            statusView.setText(t("接口没有提供可用封面图片", "No cover image was returned"));
            return;
        }
        coverWasRequested = true;
        coverView.setVisibility(View.VISIBLE);
        if (player != null && player.isPlaying()) player.pause();
        playButton.setText("▶");
        setVideoControlsVisible(true);
        statusView.setVisibility(View.GONE);
    }

    private void togglePlayback() {
        if (player == null || !prepared) {
            statusView.setVisibility(View.VISIBLE);
            statusView.setText(t("视频正在加载，请稍候…", "Video is still loading…"));
            return;
        }
        try {
            if (player.isPlaying()) {
                player.pause();
                wasPlayingBeforePause = false;
                playButton.setText("▶");
            } else {
                player.start();
                wasPlayingBeforePause = true;
                playButton.setText("❚❚");
                if (firstFrameSeen) coverView.setVisibility(View.GONE);
            }
            setVideoControlsVisible(true);
        } catch (IllegalStateException ignored) { }
    }

    private void toggleOrientation() {
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        setRequestedOrientation(landscape
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    private void updateRotateButton() {
        if (rotateButton == null) return;
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        rotateButton.setText(landscape ? t("竖屏", "Portrait") : t("横屏", "Landscape"));
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

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateRotateButton();
        enterImmersiveMode();
        if (textureView != null) textureView.post(this::applyVideoTransform);
    }

    private String formatTime(int milliseconds) {
        int totalSeconds = Math.max(0, milliseconds / 1000);
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        return hours > 0 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(java.util.Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    private void releasePlayer() {
        prepared = false;
        if (player != null) {
            try { player.setOnPreparedListener(null); player.setOnVideoSizeChangedListener(null); player.setOnErrorListener(null); player.setOnInfoListener(null); player.stop(); }
            catch (Exception ignored) { }
            try { player.reset(); player.release(); } catch (Exception ignored) { }
            player = null;
        }
        if (playerSurface != null) {
            try { playerSurface.release(); } catch (Exception ignored) { }
            playerSurface = null;
        }
    }

    @Override protected void onPause() {
        if (player != null) {
            try {
                wasPlayingBeforePause = player.isPlaying();
                if (wasPlayingBeforePause) player.pause();
            } catch (IllegalStateException ignored) { }
        }
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        enterImmersiveMode();
        if (player != null && prepared && wasPlayingBeforePause && !coverWasRequested) {
            try { player.start(); } catch (IllegalStateException ignored) { }
        }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        imageExecutor.shutdownNow();
        releasePlayer();
        super.onDestroy();
    }
}
