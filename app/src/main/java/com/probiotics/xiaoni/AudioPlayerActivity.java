package com.probiotics.xiaoni;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Dsu in-app audio playback with optional synchronized LRC lyrics. */
public final class AudioPlayerActivity extends BaseActivity {
    public static final String EXTRA_AUDIO_URI = "audio_uri";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_ARTIST = "artist";
    public static final String EXTRA_LYRICS_TEXT = "lyrics_text";
    public static final String EXTRA_LYRICS_URI = "lyrics_uri";
    public static final String EXTRA_REFERER = "referer";

    private static final int BG = 0xffeaf2f8;
    private static final int INK = 0xff19334a;
    private static final int MUTED = 0xff668094;
    private static final int ACCENT = 0xff2875c7;
    private static final int[] LYRIC_COLORS = {0xff9aabb8, 0xff6f8596, 0xff19334a, 0xff6f8596, 0xff9aabb8};
    private static final Pattern LRC_TIME = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<LyricLine> lyricLines = new ArrayList<>();
    private final List<TextView> lyricViews = new ArrayList<>();
    private ExoPlayer player;
    private SeekBar seekBar;
    private TextView playButton;
    private TextView timeView;
    private TextView durationView;
    private TextView statusView;
    private LinearLayout lyricsColumn;
    private ScrollView lyricsScroll;
    private int activeLyricIndex = -2;
    private boolean dragging;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updatePlaybackUi();
            handler.postDelayed(this, 350);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String audioUri = getIntent().getStringExtra(EXTRA_AUDIO_URI);
        if (audioUri == null || audioUri.trim().isEmpty()) {
            finish();
            return;
        }
        buildUi();
        loadLyrics();
        preparePlayer(audioUri);
        handler.post(ticker);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(12), dp(20), dp(20));
        root.setBackgroundColor(BG);

        TextView back = new TextView(this);
        back.setText("‹  返回");
        back.setTextColor(INK);
        back.setTextSize(17);
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setPadding(dp(4), dp(8), dp(12), dp(8));
        back.setOnClickListener(v -> finish());
        root.addView(back, new LinearLayout.LayoutParams(-1, dp(48)));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(20), dp(22), dp(20), dp(18));
        card.setBackground(round(0xfff7fbff, 0xffd4e3ef, 22));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.topMargin = dp(8);
        root.addView(card, cardLp);

        TextView note = new TextView(this);
        note.setText("♫");
        note.setTextColor(Color.WHITE);
        note.setTextSize(46);
        note.setGravity(Gravity.CENTER);
        note.setBackground(round(0xff61a9cc, 0, 28));
        card.addView(note, new LinearLayout.LayoutParams(dp(112), dp(112)));

        TextView title = new TextView(this);
        String titleText = getIntent().getStringExtra(EXTRA_TITLE);
        title.setText(titleText == null || titleText.trim().isEmpty() ? "音乐播放" : titleText);
        title.setTextColor(INK);
        title.setTextSize(21);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.topMargin = dp(18);
        card.addView(title, titleLp);

        TextView artist = new TextView(this);
        String artistText = getIntent().getStringExtra(EXTRA_ARTIST);
        artist.setText(artistText == null || artistText.trim().isEmpty() ? "Dsu 音频播放器" : artistText);
        artist.setTextColor(MUTED);
        artist.setTextSize(14);
        artist.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams artistLp = new LinearLayout.LayoutParams(-1, -2);
        artistLp.topMargin = dp(6);
        card.addView(artist, artistLp);

        statusView = new TextView(this);
        statusView.setText("正在加载音频…");
        statusView.setTextColor(MUTED);
        statusView.setTextSize(12);
        statusView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.topMargin = dp(6);
        card.addView(statusView, statusLp);

        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeView = new TextView(this);
        durationView = new TextView(this);
        timeView.setText("00:00");
        durationView.setText("00:00");
        timeView.setTextColor(MUTED);
        durationView.setTextColor(MUTED);
        timeView.setTextSize(12);
        durationView.setTextSize(12);
        timeRow.addView(timeView, new LinearLayout.LayoutParams(0, -2, 1));
        durationView.setGravity(Gravity.END);
        timeRow.addView(durationView, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams timeRowLp = new LinearLayout.LayoutParams(-1, -2);
        timeRowLp.topMargin = dp(16);
        card.addView(timeRow, timeRowLp);

        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        card.addView(seekBar, new LinearLayout.LayoutParams(-1, dp(42)));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { }
            @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                if (player != null && player.getDuration() > 0) {
                    player.seekTo((long) (player.getDuration() * (bar.getProgress() / 1000d)));
                }
            }
        });

        playButton = new TextView(this);
        playButton.setText("Ⅱ");
        playButton.setTextColor(Color.WHITE);
        playButton.setTextSize(23);
        playButton.setGravity(Gravity.CENTER);
        playButton.setBackground(round(0xff4c9fbd, 0, 26));
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(dp(66), dp(52));
        playLp.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(playButton, playLp);
        playButton.setOnClickListener(v -> {
            if (player == null) return;
            if (player.isPlaying()) player.pause(); else player.play();
            updatePlaybackUi();
        });

        TextView lyricsTitle = new TextView(this);
        lyricsTitle.setText("同步歌词");
        lyricsTitle.setTextColor(INK);
        lyricsTitle.setTextSize(17);
        lyricsTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams lyricsTitleLp = new LinearLayout.LayoutParams(-1, -2);
        lyricsTitleLp.topMargin = dp(20);
        root.addView(lyricsTitle, lyricsTitleLp);

        lyricsScroll = new ScrollView(this);
        lyricsScroll.setFillViewport(true);
        lyricsScroll.setBackground(round(0xfff7fbff, 0xffd4e3ef, 20));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, 0, 1);
        scrollLp.topMargin = dp(10);
        root.addView(lyricsScroll, scrollLp);
        lyricsColumn = new LinearLayout(this);
        lyricsColumn.setOrientation(LinearLayout.VERTICAL);
        lyricsColumn.setGravity(Gravity.CENTER_HORIZONTAL);
        lyricsColumn.setPadding(dp(14), dp(18), dp(14), dp(18));
        lyricsScroll.addView(lyricsColumn, new ScrollView.LayoutParams(-1, -2));
        setContentView(root);
    }

    private void preparePlayer(String audioUri) {
        try {
            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
                    .setConnectTimeoutMs(15_000)
                    .setReadTimeoutMs(30_000);
            String referer = getIntent().getStringExtra(EXTRA_REFERER);
            if (referer != null && referer.startsWith("http")) {
                java.util.Map<String, String> headers = new java.util.HashMap<>();
                headers.put("Referer", referer);
                http.setDefaultRequestProperties(headers);
            }
            DefaultDataSource.Factory source = new DefaultDataSource.Factory(this, http);
            player = new ExoPlayer.Builder(this)
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(source))
                    .build();
            player.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY) statusView.setText("正在播放");
                    else if (state == Player.STATE_BUFFERING) statusView.setText("正在缓冲…");
                    else if (state == Player.STATE_ENDED) statusView.setText("播放完成");
                    updatePlaybackUi();
                }
                @Override public void onPlayerError(PlaybackException error) {
                    statusView.setText("播放失败：" + (error.getMessage() == null ? "音频文件无法读取" : error.getMessage()));
                }
            });
            player.setMediaItem(MediaItem.fromUri(Uri.parse(audioUri)));
            player.prepare();
            player.play();
        } catch (Throwable error) {
            statusView.setText("播放器初始化失败：" + (error.getMessage() == null ? "未知错误" : error.getMessage()));
        }
    }

    private void loadLyrics() {
        String inline = getIntent().getStringExtra(EXTRA_LYRICS_TEXT);
        if (inline != null && !inline.trim().isEmpty()) {
            displayLyrics(inline);
            return;
        }
        String lyricsUri = getIntent().getStringExtra(EXTRA_LYRICS_URI);
        if (lyricsUri == null || lyricsUri.trim().isEmpty()) {
            displayLyrics("");
            return;
        }
        new Thread(() -> {
            String text = "";
            try (InputStream input = getContentResolver().openInputStream(Uri.parse(lyricsUri));
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (input != null) {
                    byte[] buffer = new byte[4096];
                    int count;
                    int total = 0;
                    while ((count = input.read(buffer)) != -1 && total < 512 * 1024) {
                        output.write(buffer, 0, count);
                        total += count;
                    }
                    text = new String(output.toByteArray(), StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) { }
            String result = text;
            handler.post(() -> displayLyrics(result));
        }, "dsu-lyrics-read").start();
    }

    private void displayLyrics(String raw) {
        lyricLines.clear();
        lyricViews.clear();
        lyricsColumn.removeAllViews();
        activeLyricIndex = -2;
        if (raw == null || raw.trim().isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("这首歌曲暂时没有可用歌词");
            empty.setTextColor(MUTED);
            empty.setTextSize(15);
            empty.setGravity(Gravity.CENTER);
            lyricsColumn.addView(empty, new LinearLayout.LayoutParams(-1, dp(96)));
            return;
        }
        String[] rows = raw.replace("\r", "").split("\n");
        for (String row : rows) {
            Matcher matcher = LRC_TIME.matcher(row);
            boolean found = false;
            String lyricText = "";
            List<Long> times = new ArrayList<>();
            int textStart = 0;
            while (matcher.find()) {
                found = true;
                int minutes = parseInt(matcher.group(1));
                int seconds = parseInt(matcher.group(2));
                String fraction = matcher.group(3);
                int millis = 0;
                if (fraction != null) {
                    if (fraction.length() == 1) millis = parseInt(fraction) * 100;
                    else if (fraction.length() == 2) millis = parseInt(fraction) * 10;
                    else millis = parseInt(fraction.substring(0, 3));
                }
                times.add((minutes * 60L + seconds) * 1000L + millis);
                textStart = matcher.end();
            }
            if (found) {
                lyricText = row.substring(textStart).trim();
                if (!lyricText.isEmpty()) for (Long time : times) lyricLines.add(new LyricLine(time, lyricText));
            } else if (!row.trim().isEmpty() && !row.trim().startsWith("[")) {
                lyricLines.add(new LyricLine(-1L, row.trim()));
            }
        }
        Collections.sort(lyricLines, Comparator.comparingLong(line -> line.timeMs < 0 ? Long.MAX_VALUE : line.timeMs));
        if (lyricLines.isEmpty()) {
            TextView plain = new TextView(this);
            plain.setText(raw.trim());
            plain.setTextColor(INK);
            plain.setTextSize(15);
            plain.setGravity(Gravity.CENTER);
            lyricsColumn.addView(plain, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (LyricLine line : lyricLines) {
            TextView row = new TextView(this);
            row.setText(line.text);
            row.setTextColor(MUTED);
            row.setTextSize(15);
            row.setGravity(Gravity.CENTER);
            row.setPadding(dp(4), dp(8), dp(4), dp(8));
            lyricsColumn.addView(row, new LinearLayout.LayoutParams(-1, -2));
            lyricViews.add(row);
        }
        statusView.setText("歌词已加载 · " + lyricLines.size() + " 行");
        updateLyrics();
    }

    private void updatePlaybackUi() {
        if (player == null) return;
        long position = Math.max(0L, player.getCurrentPosition());
        long duration = Math.max(0L, player.getDuration());
        if (!dragging && duration > 0L) seekBar.setProgress((int) Math.min(1000L, position * 1000L / duration));
        timeView.setText(formatTime(position));
        durationView.setText(formatTime(duration));
        playButton.setText(player.isPlaying() ? "Ⅱ" : "▶");
        updateLyrics();
    }

    private void updateLyrics() {
        if (player == null || lyricLines.isEmpty() || lyricViews.isEmpty()) return;
        long position = Math.max(0L, player.getCurrentPosition());
        int index = -1;
        for (int i = 0; i < lyricLines.size(); i++) {
            long time = lyricLines.get(i).timeMs;
            if (time < 0L) continue;
            if (time <= position) index = i;
            else break;
        }
        if (index < 0 && lyricLines.get(0).timeMs < 0L) index = 0;
        for (int i = 0; i < lyricViews.size(); i++) {
            boolean active = i == index;
            TextView row = lyricViews.get(i);
            row.setTextColor(active ? ACCENT : LYRIC_COLORS[Math.min(4, Math.abs(i - index) + 1)]);
            row.setTextSize(active ? 18 : 15);
            row.setTypeface(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL);
        }
        if (index >= 0 && index != activeLyricIndex) {
            activeLyricIndex = index;
            TextView row = lyricViews.get(index);
            lyricsScroll.post(() -> lyricsScroll.smoothScrollTo(0,
                    Math.max(0, row.getTop() - lyricsScroll.getHeight() / 2 + row.getHeight() / 2)));
        }
    }

    private int parseInt(String value) {
        try { return Integer.parseInt(value); } catch (Exception ignored) { return 0; }
    }

    private String formatTime(long millis) {
        long seconds = millis / 1000L;
        return String.format(Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L);
    }

    private GradientDrawable round(int color, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (stroke != 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override protected void onStop() {
        if (player != null) player.pause();
        super.onStop();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(ticker);
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    private static final class LyricLine {
        final long timeMs;
        final String text;
        LyricLine(long timeMs, String text) { this.timeMs = timeMs; this.text = text; }
    }
}
