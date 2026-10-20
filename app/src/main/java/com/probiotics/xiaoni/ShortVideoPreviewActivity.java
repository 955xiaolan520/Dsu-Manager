package com.probiotics.xiaoni;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
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
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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
    private TextView subtitleView;
    private TextView subtitleButton;
    private TextView qualityButton;
    private boolean videoControlsVisible = true;
    private boolean subtitlesEnabled = false;
    private String currentSubtitleLanguage = "简体中文";
    private int currentSubtitleStyleIndex = 0;
    private int currentQualityIndex = 0;
    private final String[] subtitleLanguages = {"简体中文", "繁體中文", "English", "日本語", "한국어"};
    private final String[] subtitleStyles = {"默认白色", "黄色描边", "红色粗体", "蓝色发光", "绿色阴影", "彩虹渐变"};
    private final String[] qualityOptions = {"自动", "超清 1080P", "高清 720P", "标清 480P", "流畅 360P"};
    private java.util.List<SubtitleItem> subtitleItems = new java.util.ArrayList<>();
    private int currentSubtitleIndex = -1;
    private final Runnable subtitleUpdater = new Runnable() {
        @Override public void run() {
            if (player != null && prepared && subtitlesEnabled) {
                try {
                    int position = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, player.getCurrentPosition()));
                    updateSubtitleForPosition(position);
                } catch (IllegalStateException ignored) { }
            }
            handler.postDelayed(this, 200);
        }
    };
    private final Runnable hideVideoControls = () -> setVideoControlsVisible(false);
    private ExoPlayer player;
    private boolean english;
    private boolean prepared;
    private boolean dragging;
    private boolean wasPlayingBeforePause;
    private boolean firstFrameSeen;
    private boolean coverWasRequested;
    private boolean isLongPressing = false;
    private long longPressStartTime = 0L;
    private int seekDirection = 0;
    private boolean fastHoldStarted;
    private boolean wasPlayingBeforeFastHold;
    private ScaleGestureDetector videoScaleDetector;
    private float videoScale = 1f;
    private float videoScaleFocusX;
    private float videoScaleFocusY;
    private float playbackSpeed = 1.0f;
    private int currentSpeedIndex = 2;
    private final float[] speedOptions = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 4.0f, 8.0f, 16.0f};
    private final String[] speedLabels = {"0.5x", "0.75x", "1x", "1.25x", "1.5x", "2x", "4x", "8x", "16x"};
    private final Runnable fastRewindTick = this::runFastRewindStep;
    private final Runnable beginFastHold = () -> {
        if (!isLongPressing || player == null || !prepared) return;
        fastHoldStarted = true;
        wasPlayingBeforeFastHold = player.isPlaying();
        statusView.setVisibility(View.VISIBLE);
        if (seekDirection > 0) {
            player.setPlayWhenReady(true);
            player.setPlaybackSpeed(3.0f);
            statusView.setText(t("按住快进 · 3 倍速", "Fast-forward · 3x while held"));
        } else {
            statusView.setText(t("按住持续后退", "Rewinding while held"));
            handler.post(fastRewindTick);
        }
    };
    private void runFastRewindStep() {
        if (!isLongPressing || !fastHoldStarted || seekDirection >= 0 || player == null || !prepared) return;
        long next = Math.max(0L, player.getCurrentPosition() - 1800L);
        player.seekTo(next);
        statusView.setText(t("持续后退至 ", "Rewinding to ") + formatTime((int) Math.min(Integer.MAX_VALUE, next)));
        handler.postDelayed(fastRewindTick, 800);
    }
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
                    int position = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, player.getCurrentPosition()));
                    int duration = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, player.getDuration()));
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

        GestureDetector videoGestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                toggleVideoControls();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent event) {
                togglePlayback();
                return true;
            }
        });

        videoScaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                videoScale = Math.max(1f, Math.min(3.5f, videoScale * detector.getScaleFactor()));
                videoScaleFocusX = detector.getFocusX();
                videoScaleFocusY = detector.getFocusY();
                applyVideoZoom();
                return true;
            }
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                videoScaleFocusX = detector.getFocusX();
                videoScaleFocusY = detector.getFocusY();
                return true;
            }
        });

        textureView.setOnTouchListener((v, event) -> {
            videoScaleDetector.onTouchEvent(event);
            boolean multiTouch = event.getPointerCount() > 1;
            boolean gestureHandled = !multiTouch && videoGestures.onTouchEvent(event);

            int action = event.getAction();
            float x = event.getX();
            float viewWidth = v.getWidth();

            if (action == MotionEvent.ACTION_DOWN && player != null && prepared) {
                if (x < viewWidth * 0.3f) {
                    isLongPressing = true;
                    seekDirection = -1;
                    longPressStartTime = System.currentTimeMillis();
                    fastHoldStarted = false;
                    handler.postDelayed(beginFastHold, 350);
                } else if (x > viewWidth * 0.7f) {
                    isLongPressing = true;
                    seekDirection = 1;
                    longPressStartTime = System.currentTimeMillis();
                    fastHoldStarted = false;
                    handler.postDelayed(beginFastHold, 350);
                }
            } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
                stopFastHold();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                stopFastHold();
            }

            return multiTouch || gestureHandled || isLongPressing;
        });

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                if (player == null) preparePlayer();
                else {
                    player.setVideoTextureView(textureView);
                    if (wasPlayingBeforePause && !coverWasRequested) player.play();
                }
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                applyVideoTransform();
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                if (player != null) {
                    try {
                        if (prepared) resumePosition = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, player.getCurrentPosition()));
                        wasPlayingBeforePause = wasPlayingBeforePause || player.isPlaying();
                    } catch (IllegalStateException ignored) { }
                    try { player.clearVideoTextureView(textureView); } catch (Exception ignored) { }
                }
                firstFrameSeen = false;
                if (!isFinishing()) {
                    coverView.setVisibility(View.VISIBLE);
                    statusView.setVisibility(View.GONE);
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

        statusView = text("", 13, Color.WHITE, true);
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
        rotateButton.setOnClickListener(v -> { toggleOrientation(); setVideoControlsVisible(true); });

        LinearLayout.LayoutParams settingsBtnLp = new LinearLayout.LayoutParams(dp(60), dp(44));
        settingsBtnLp.leftMargin = dp(7);
        TextView settingsButton = pill(t("设置", "Set"), -1);
        settingsButton.setLayoutParams(settingsBtnLp);
        top.addView(settingsButton);
        settingsButton.setOnClickListener(v -> showSettingsMenu());

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

        TextView rewindButton = text("↶15", 11, Color.WHITE, true);
        rewindButton.setGravity(Gravity.CENTER);
        controls.addView(rewindButton, new LinearLayout.LayoutParams(dp(38), -1));
        rewindButton.setOnClickListener(v -> seekRelative(-15000));

        playButton = text("▶", 17, Color.WHITE, true);
        playButton.setGravity(Gravity.CENTER);
        controls.addView(playButton, new LinearLayout.LayoutParams(dp(44), -1));
        playButton.setOnClickListener(v -> togglePlayback());

        TextView forwardButton = text("15↷", 11, Color.WHITE, true);
        forwardButton.setGravity(Gravity.CENTER);
        controls.addView(forwardButton, new LinearLayout.LayoutParams(dp(38), -1));
        forwardButton.setOnClickListener(v -> seekRelative(15000));

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

        subtitleView = text("", 14, Color.WHITE, true);
        subtitleView.setGravity(Gravity.CENTER);
        subtitleView.setPadding(dp(16), dp(8), dp(16), dp(8));
        subtitleView.setBackground(rounded(0xcc000000, 0, 8));
        subtitleView.setShadowLayer(4f, 0f, 2f, 0xff000000);
        subtitleView.setVisibility(View.GONE);
        FrameLayout.LayoutParams subtitleLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        subtitleLp.bottomMargin = dp(90);
        root.addView(subtitleView, subtitleLp);

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

    private void preparePlayer() {
        statusView.setVisibility(View.GONE);
        if (videoUrl.isBlank()) {
            statusView.setVisibility(View.VISIBLE);
            statusView.setText(t("没有可播放的视频直链", "No playable video URL"));
            return;
        }
        try {
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");
            String sourceReferer = playbackReferer(referer);
            if (!sourceReferer.isBlank()) headers.put("Referer", sourceReferer);
            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                    .setUserAgent(headers.get("User-Agent"))
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(15_000)
                    .setReadTimeoutMs(30_000)
                    .setDefaultRequestProperties(headers);
            DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15_000, 45_000, 1_000, 2_500)
                    .build();
            player = new ExoPlayer.Builder(this)
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory))
                    .setLoadControl(loadControl)
                    .build();
            player.setVideoTextureView(textureView);
            player.addListener(new Player.Listener() {
                @Override public void onVideoSizeChanged(VideoSize size) {
                    if (size.width > 0 && size.height > 0) {
                        videoWidth = size.width;
                        videoHeight = size.height;
                        applyVideoTransform();
                    }
                }
                @Override public void onRenderedFirstFrame() { showVideoFrame(); }
                @Override public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_BUFFERING) {
                        statusView.setVisibility(View.GONE);
                    } else if (state == Player.STATE_READY) {
                        prepared = true;
                        if (videoWidth <= 0 || videoHeight <= 0) {
                            VideoSize size = player.getVideoSize();
                            videoWidth = size.width;
                            videoHeight = size.height;
                        }
                        player.setPlaybackSpeed(playbackSpeed);
                        applyVideoTransform();
                        startWhenReady();
                    } else if (state == Player.STATE_ENDED) {
                        playButton.setText("↻");
                        wasPlayingBeforePause = false;
                        setVideoControlsVisible(true);
                    }
                }
                @Override public void onIsPlayingChanged(boolean playing) {
                    playButton.setText(playing ? "❚❚" : "▶");
                    if (playing && firstFrameSeen) statusView.setVisibility(View.GONE);
                }
                @Override public void onPlayerError(PlaybackException error) {
                    prepared = false;
                    setVideoControlsVisible(true);
                    statusView.setVisibility(View.VISIBLE);
                    String reason = error.getMessage() == null ? "" : error.getMessage();
                    statusView.setText(t("播放失败：", "Playback failed: ") + reason
                            + t("。可尝试重新解析或下载后播放。", ". Try resolving again or download the file."));
                }
            });
            player.setMediaItem(MediaItem.fromUri(Uri.parse(videoUrl)));
            player.setPlayWhenReady(true);
            player.prepare();
        } catch (Exception error) {
            statusView.setVisibility(View.VISIBLE);
            statusView.setText(t("播放器初始化失败：", "Could not initialize player: ")
                    + (error.getMessage() == null ? "" : error.getMessage()));
        }
    }

    private void startWhenReady() {
        if (player == null || !prepared || videoWidth <= 0 || videoHeight <= 0) return;
        statusView.setVisibility(View.GONE);
        try {
            if (resumePosition > 0) {
                player.seekTo(resumePosition);
                resumePosition = 0;
            }
            if (!player.isPlaying()) player.play();
            wasPlayingBeforePause = true;
            playButton.setText("❚❚");
            setVideoControlsVisible(true);
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

    private String playbackReferer(String source) {
        try {
            String host = Uri.parse(source).getHost();
            if (host == null) return source;
            host = host.toLowerCase(java.util.Locale.ROOT);
            if (host.contains("douyin") || host.contains("iesdouyin")) return "https://www.douyin.com/";
            if (host.contains("kuaishou") || host.contains("gifshow")) return "https://www.kuaishou.com/";
            return source;
        } catch (Exception ignored) { return source; }
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
        applyVideoZoom();

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

    private void applyVideoZoom() {
        if (textureView == null) return;
        Matrix matrix = new Matrix();
        if (videoScale > 1.001f) {
            float px = videoScaleFocusX > 0f ? videoScaleFocusX : textureView.getWidth() / 2f;
            float py = videoScaleFocusY > 0f ? videoScaleFocusY : textureView.getHeight() / 2f;
            matrix.setScale(videoScale, videoScale, px, py);
        }
        textureView.setTransform(matrix);
    }

    private void stopFastHold() {
        handler.removeCallbacks(beginFastHold);
        handler.removeCallbacks(fastRewindTick);
        boolean restoreFastForward = isLongPressing && fastHoldStarted && seekDirection > 0;
        isLongPressing = false;
        if (restoreFastForward && player != null && prepared) {
            player.setPlaybackSpeed(playbackSpeed);
            if (!wasPlayingBeforeFastHold) player.pause();
        }
        boolean hadFastHold = fastHoldStarted;
        fastHoldStarted = false;
        seekDirection = 0;
        if (hadFastHold) handler.postDelayed(() -> {
            if (!isLongPressing && statusView != null) statusView.setVisibility(View.GONE);
        }, 700);
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
                player.play();
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

    private void updateSubtitleButton() {
        if (subtitleButton == null) return;
        subtitleButton.setAlpha(subtitlesEnabled ? 1.0f : 0.5f);
    }

    private void toggleSubtitles() {
        subtitlesEnabled = !subtitlesEnabled;

        if (subtitlesEnabled) {
            statusView.setVisibility(View.VISIBLE);
            statusView.setText(t("AI字幕功能已启用\n注意：当前为演示模式，需要语音识别API才能显示真实字幕", "AI subtitles enabled\nNote: Demo mode, requires speech recognition API for real subtitles"));
            handler.postDelayed(() -> statusView.setVisibility(View.GONE), 3000);

            if (subtitleView != null) {
                subtitleView.setVisibility(View.GONE);
            }
        } else {
            handler.removeCallbacks(subtitleUpdater);
            if (subtitleView != null) {
                subtitleView.animate().alpha(0f).setDuration(300).withEndAction(() -> {
                    if (subtitleView != null) subtitleView.setVisibility(View.GONE);
                }).start();
            }
            statusView.setVisibility(View.VISIBLE);
            statusView.setText(t("AI字幕已关闭", "AI subtitles disabled"));
            handler.postDelayed(() -> statusView.setVisibility(View.GONE), 1500);
        }
        setVideoControlsVisible(true);
    }

    private void showSubtitleWithEffect(String text) {
        if (subtitleView == null) return;
        subtitleView.setText(text);
        subtitleView.setAlpha(0f);
        subtitleView.setScaleX(0.8f);
        subtitleView.setScaleY(0.8f);
        subtitleView.setVisibility(View.VISIBLE);
        subtitleView.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(300)
            .start();
    }

    private void cyclePlaybackSpeed(TextView speedButton) {
        currentSpeedIndex = (currentSpeedIndex + 1) % speedOptions.length;
        playbackSpeed = speedOptions[currentSpeedIndex];
        if (player != null && prepared) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    player.setPlaybackSpeed(playbackSpeed);
                }
            } catch (Exception ignored) { }
        }
        speedButton.setText(speedLabels[currentSpeedIndex]);
        statusView.setVisibility(View.VISIBLE);
        statusView.setText(t("播放速度: " + speedLabels[currentSpeedIndex], "Speed: " + speedLabels[currentSpeedIndex]));
        handler.postDelayed(() -> statusView.setVisibility(View.GONE), 1500);
        setVideoControlsVisible(true);
    }

    private void seekRelative(int milliseconds) {
        if (player == null || !prepared) return;
        try {
            int current = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, player.getCurrentPosition()));
            int duration = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, player.getDuration()));
            int newPosition = Math.max(0, Math.min(current + milliseconds, duration));
            player.seekTo(newPosition);
            setVideoControlsVisible(true);
            statusView.setVisibility(View.VISIBLE);
            statusView.setText(milliseconds > 0
                ? t("快进 " + (milliseconds / 1000) + " 秒", "Forward " + (milliseconds / 1000) + "s")
                : t("后退 " + (-milliseconds / 1000) + " 秒", "Rewind " + (-milliseconds / 1000) + "s"));
            handler.postDelayed(() -> statusView.setVisibility(View.GONE), 1000);
        } catch (IllegalStateException ignored) { }
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
            try { player.release(); } catch (Exception ignored) { }
            player = null;
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
            try { player.play(); } catch (IllegalStateException ignored) { }
        }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        imageExecutor.shutdownNow();
        releasePlayer();
        super.onDestroy();
    }

    private void showSettingsMenu() {
        String[] options = {
            t("播放倍速", "Playback Speed"),
            t("显示封面", "Show Cover")
        };

        showRoundedDialog(t("播放设置", "Player Settings"), options, -1, (dialog, which) -> {
            switch (which) {
                case 0: showSpeedSelector(); break;
                case 1: showCover(); break;
            }

        });
        setVideoControlsVisible(true);
    }

    private void showSpeedSelector() {
        showRoundedDialog(t("选择播放速度", "Select Playback Speed"), speedLabels, currentSpeedIndex, (dialog, which) -> {
            android.util.Log.d("ShortVideo", "Speed selected: " + which);
            currentSpeedIndex = which;
            playbackSpeed = speedOptions[which];
            if (player != null && prepared) {
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        player.setPlaybackSpeed(playbackSpeed);
                    }
                } catch (Exception ignored) { }
            }
            statusView.setVisibility(View.VISIBLE);
            statusView.setText(t("播放速度: " + speedLabels[which], "Speed: " + speedLabels[which]));
            handler.postDelayed(() -> statusView.setVisibility(View.GONE), 1500);

        });
        setVideoControlsVisible(true);
    }

    private void showSubtitleSettings() {
        String[] options = {
            t("字幕开关", "Toggle Subtitles"),
            t("字幕语言", "Subtitle Language"),
            t("字幕特效", "Subtitle Style")
        };

        showRoundedDialog(t("字幕设置", "Subtitle Settings"), options, -1, (dialog, which) -> {
            switch (which) {
                case 0: toggleSubtitles(); break;
                case 1: showSubtitleLanguageSelector(); break;
                case 2: showSubtitleStyleSelector(); break;
            }

        });
        setVideoControlsVisible(true);
    }

    private void showSubtitleLanguageSelector() {
        int currentIndex = java.util.Arrays.asList(subtitleLanguages).indexOf(currentSubtitleLanguage);
        showRoundedDialog(t("选择字幕语言", "Select Subtitle Language"), subtitleLanguages, currentIndex, (dialog, which) -> {
            currentSubtitleLanguage = subtitleLanguages[which];
            statusView.setVisibility(View.VISIBLE);
            statusView.setText(t("字幕语言: ", "Subtitle Language: ") + subtitleLanguages[which]);
            handler.postDelayed(() -> statusView.setVisibility(View.GONE), 1500);
            if (subtitlesEnabled) {
                showSubtitleWithEffect(t("已切换到 ", "Switched to ") + subtitleLanguages[which]);
            }

        });
        setVideoControlsVisible(true);
    }

    private void showSubtitleStyleSelector() {
        showRoundedDialog(t("选择字幕特效", "Select Subtitle Style"), subtitleStyles, currentSubtitleStyleIndex, (dialog, which) -> {
            currentSubtitleStyleIndex = which;
            applySubtitleStyle(which);
            statusView.setVisibility(View.VISIBLE);
            statusView.setText(t("字幕特效: ", "Subtitle Style: ") + subtitleStyles[which]);
            handler.postDelayed(() -> statusView.setVisibility(View.GONE), 1500);
            if (subtitlesEnabled) {
                showSubtitleWithEffect(t("特效已应用: ", "Style applied: ") + subtitleStyles[which]);
            }

        });
        setVideoControlsVisible(true);
    }

    private void showRoundedDialog(String title, String[] items, int checkedItem, android.content.DialogInterface.OnClickListener listener) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackground(rounded(0xff1a2638, 0, 24));
        container.setPadding(dp(20), dp(20), dp(20), dp(20));

        TextView titleView = text(title, 16, 0xffffffff, true);
        titleView.setPadding(0, 0, 0, dp(16));
        container.addView(titleView);

        builder.setView(container);
        android.app.AlertDialog dialog = builder.create();

        final int[] currentSelection = {checkedItem};
        java.util.List<LinearLayout> itemLayouts = new java.util.ArrayList<>();
        java.util.List<TextView> radioButtons = new java.util.ArrayList<>();

        for (int i = 0; i < items.length; i++) {
            final int index = i;
            LinearLayout itemLayout = new LinearLayout(this);
            itemLayout.setOrientation(LinearLayout.HORIZONTAL);
            itemLayout.setGravity(Gravity.CENTER_VERTICAL);
            itemLayout.setPadding(dp(12), dp(14), dp(12), dp(14));
            itemLayout.setBackground(rounded(checkedItem == i ? 0x4451d6cb : 0x33ffffff, 0, 12));
            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(-1, -2);
            itemLp.topMargin = dp(8);

            TextView radio = null;
            if (checkedItem >= 0) {
                radio = text(checkedItem == i ? "●" : "○", 14, 0xff51d6cb, false);
                radio.setPadding(0, 0, dp(12), 0);
                itemLayout.addView(radio, new LinearLayout.LayoutParams(-2, -2));
                radioButtons.add(radio);
            }

            TextView itemText = text(items[i], 14, 0xffffffff, false);
            itemLayout.addView(itemText, new LinearLayout.LayoutParams(0, -2, 1f));

            itemLayouts.add(itemLayout);
            final TextView finalRadio = radio;

            itemLayout.setOnClickListener(v -> {
                currentSelection[0] = index;

                for (int j = 0; j < itemLayouts.size(); j++) {
                    LinearLayout layout = itemLayouts.get(j);
                    layout.setBackground(rounded(j == index ? 0x4451d6cb : 0x33ffffff, 0, 12));
                    if (checkedItem >= 0 && j < radioButtons.size()) {
                        radioButtons.get(j).setText(j == index ? "●" : "○");
                    }
                }

                if (listener != null) {
                    listener.onClick(dialog, index);
                }

                handler.postDelayed(() -> {
                    if (dialog != null && dialog.isShowing()) {
                        dialog.dismiss();
                    }
                }, 200);
            });

            container.addView(itemLayout, itemLp);
        }

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialog.show();
    }

    private void applySubtitleStyle(int styleIndex) {
        if (subtitleView == null) return;

        switch (styleIndex) {
            case 0: // 默认白色
                subtitleView.setTextColor(Color.WHITE);
                subtitleView.setTextSize(14);
                subtitleView.setShadowLayer(4f, 0f, 2f, 0xff000000);
                subtitleView.setBackground(rounded(0xcc000000, 0, 8));
                break;
            case 1: // 黄色描边
                subtitleView.setTextColor(0xffffeb3b);
                subtitleView.setTextSize(16);
                subtitleView.setShadowLayer(6f, 0f, 0f, 0xff000000);
                subtitleView.setBackground(rounded(0xaa000000, 0xffffeb3b, 8));
                break;
            case 2: // 红色粗体
                subtitleView.setTextColor(0xffff5252);
                subtitleView.setTextSize(17);
                subtitleView.setShadowLayer(8f, 2f, 2f, 0xff000000);
                subtitleView.setBackground(rounded(0xbb000000, 0, 10));
                break;
            case 3: // 蓝色发光
                subtitleView.setTextColor(0xff64b5f6);
                subtitleView.setTextSize(15);
                subtitleView.setShadowLayer(12f, 0f, 0f, 0xff2196f3);
                subtitleView.setBackground(rounded(0xaa001a33, 0xff64b5f6, 12));
                break;
            case 4: // 绿色阴影
                subtitleView.setTextColor(0xff66bb6a);
                subtitleView.setTextSize(16);
                subtitleView.setShadowLayer(10f, 3f, 3f, 0xff1b5e20);
                subtitleView.setBackground(rounded(0xaa000000, 0xff66bb6a, 10));
                break;
            case 5: // 彩虹渐变
                subtitleView.setTextColor(Color.WHITE);
                subtitleView.setTextSize(18);
                subtitleView.setShadowLayer(8f, 0f, 0f, 0xffff00ff);
                android.graphics.drawable.GradientDrawable gradient = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{0xffff0080, 0xff8000ff, 0xff00ffff}
                );
                gradient.setCornerRadius(dp(12));
                subtitleView.setBackground(gradient);
                break;
        }
    }

    private void showQualitySelector() {
        showRoundedDialog(t("选择画质", "Select Quality"), qualityOptions, currentQualityIndex, (dialog, which) -> {
            android.util.Log.d("ShortVideo", "Quality selected: " + which);
            currentQualityIndex = which;
            updateQualityButton();
            statusView.setVisibility(View.VISIBLE);
            statusView.setText(t("画质: ", "Quality: ") + qualityOptions[which]);
            handler.postDelayed(() -> statusView.setVisibility(View.GONE), 1500);

        });
        setVideoControlsVisible(true);
    }

    private void updateQualityButton() {
        if (qualityButton == null) return;
        String[] shortLabels = {t("自动", "Auto"), "1080P", "720P", "480P", "360P"};
        qualityButton.setText(shortLabels[currentQualityIndex]);
    }

    private void generateSimulatedSubtitles() {
        subtitleItems.clear();
        String[][] sampleSubtitles = {
            {"0", "3000", "欢迎观看短视频", "Welcome to the video"},
            {"3000", "6000", "这是AI生成的字幕", "This is AI-generated subtitle"},
            {"6000", "9000", "根据视频时间轴显示", "Displayed based on video timeline"},
            {"9000", "12000", "支持多种语言切换", "Supports multiple languages"},
            {"12000", "15000", "可以选择不同特效", "You can choose different effects"},
            {"15000", "18000", "字幕会自动同步", "Subtitles sync automatically"},
            {"18000", "21000", "享受您的观看体验", "Enjoy your viewing experience"},
            {"21000", "24000", "感谢使用本播放器", "Thank you for using this player"},
            {"24000", "27000", "更多功能敬请期待", "More features coming soon"},
            {"27000", "30000", "祝您观影愉快", "Happy watching"}
        };

        for (String[] sub : sampleSubtitles) {
            subtitleItems.add(new SubtitleItem(
                Integer.parseInt(sub[0]),
                Integer.parseInt(sub[1]),
                sub[2],
                sub[3]
            ));
        }
    }

    private void updateSubtitleForPosition(int position) {
        if (subtitleItems.isEmpty()) return;

        SubtitleItem matchedItem = null;
        for (int i = 0; i < subtitleItems.size(); i++) {
            SubtitleItem item = subtitleItems.get(i);
            if (position >= item.startTime && position < item.endTime) {
                matchedItem = item;
                currentSubtitleIndex = i;
                break;
            }
        }

        if (matchedItem != null) {
            String text = currentSubtitleLanguage.contains("中文") || currentSubtitleLanguage.equals("简体中文")
                ? matchedItem.textChinese
                : matchedItem.textEnglish;
            if (subtitleView != null && !text.equals(subtitleView.getText().toString())) {
                showSubtitleWithEffect(text);
            }
        } else {
            if (subtitleView != null && subtitleView.getVisibility() == View.VISIBLE) {
                subtitleView.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                    if (subtitleView != null) subtitleView.setVisibility(View.GONE);
                }).start();
            }
        }
    }

    private static class SubtitleItem {
        int startTime;
        int endTime;
        String textChinese;
        String textEnglish;

        SubtitleItem(int start, int end, String cn, String en) {
            this.startTime = start;
            this.endTime = end;
            this.textChinese = cn;
            this.textEnglish = en;
        }
    }
}
