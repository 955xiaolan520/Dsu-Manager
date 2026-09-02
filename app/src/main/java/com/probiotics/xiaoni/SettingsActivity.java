package com.probiotics.xiaoni;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewParent;
import android.widget.ScrollView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import java.util.Locale;

public final class SettingsActivity extends Activity {
    private static final String LANGUAGE_KEY = "language_mode";
    private static final String CURRENT_VERSION = "3.3.1";
    private static final String LATEST_RELEASE_API = "https://api.github.com/repos/955xiaolan520/Dsu-Manager/releases/latest";
    private TextView updateStatus;
    private TextView releaseNotes;
    private TextView downloadHint;
    private Button downloadButton;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }

    private TextView label(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private void buildUi() {
        int language = getSharedPreferences("settings", MODE_PRIVATE).getInt(LANGUAGE_KEY, 0);
        boolean english = language == 2 || (language == 0 && Locale.getDefault().getLanguage().equals("en"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(20));
        root.setBackgroundColor(Color.rgb(246, 247, 251));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            view.setPadding(dp(20), dp(18) + top, dp(20), dp(20) + bottom);
            return insets;
        });

        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.setPadding(dp(8), dp(5), dp(8), dp(5));
        titleBar.setBackgroundResource(R.drawable.rounded_panel);
        Button back = new Button(this);
        back.setText("<");
        back.setTextSize(20);
        back.setMinWidth(0);
        back.setMinHeight(0);
        back.setTextColor(Color.rgb(40, 50, 70));
        back.setBackgroundResource(R.drawable.rounded_panel);
        back.setOnClickListener(v -> finish());
        titleBar.addView(back, new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView title = label(english ? "Settings" : "设置", 24, Color.rgb(20, 29, 55));
        title.setTypeface(null, 1);
        titleBar.addView(title, new LinearLayout.LayoutParams(0, dp(46), 1));
        root.addView(titleBar);

        TextView languageTitle = label(english ? "Language" : "多语言", 16, Color.rgb(20, 29, 55));
        languageTitle.setTypeface(null, 1);
        languageTitle.setTextColor(Color.rgb(42, 91, 170));
        LinearLayout.LayoutParams languageTitleLp = new LinearLayout.LayoutParams(-1, dp(34));
        languageTitleLp.setMargins(dp(4), dp(18), dp(4), dp(4));
        languageTitle.setBackgroundResource(R.drawable.settings_language_title);
        root.addView(languageTitle, languageTitleLp);
        RadioGroup languages = new RadioGroup(this);
        String[] choices = english ? new String[]{"Use system language", "中文", "English"} : new String[]{"系统语言", "中文", "English"};
        RadioButton[] radios = new RadioButton[3];
        for (int i = 0; i < choices.length; i++) {
            radios[i] = new RadioButton(this);
            radios[i].setId(100 + i);
            radios[i].setText(choices[i]);
            radios[i].setTextSize(14);
            languages.addView(radios[i], new RadioGroup.LayoutParams(-1, dp(46)));
        }
        radios[language].setChecked(true);
        languages.setOnCheckedChangeListener((group, checkedId) -> {
            for (int i = 0; i < radios.length; i++) if (radios[i].getId() == checkedId) {
                getSharedPreferences("settings", MODE_PRIVATE).edit().putInt(LANGUAGE_KEY, i).apply();
                recreate();
                break;
            }
        });
        languages.setPadding(dp(8), dp(4), dp(8), dp(4));
        languages.setBackgroundResource(R.drawable.rounded_panel);
        root.addView(languages);

        TextView updateTitle = label(english ? "Updates" : "更新", 16, Color.rgb(20, 29, 55));
        updateTitle.setTypeface(null, 1);
        updateTitle.setTextColor(Color.rgb(35, 126, 91));
        updateTitle.setBackgroundResource(R.drawable.settings_update_title);
        LinearLayout.LayoutParams updateTitleLp = new LinearLayout.LayoutParams(-1, dp(34));
        updateTitleLp.setMargins(dp(4), dp(24), dp(4), dp(8));
        root.addView(updateTitle, updateTitleLp);
         updateStatus = label(english ? "Tap check for the latest release." : "点击检查最新版本。", 13, Color.rgb(80, 88, 105));
         updateStatus.setPadding(dp(14), dp(10), dp(14), dp(10));
         updateStatus.setBackgroundResource(R.drawable.rounded_panel);
         LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, dp(48));
         statusLp.setMargins(0, 0, 0, dp(10));
         root.addView(updateStatus, statusLp);
           releaseNotes = label("", 13, Color.rgb(80, 88, 105));
           releaseNotes.setGravity(Gravity.TOP | Gravity.START);
           releaseNotes.setPadding(dp(14), dp(10), dp(14), dp(10));
           ScrollView notesScroll = new ScrollView(this);
           notesScroll.setFillViewport(false);
           notesScroll.setVerticalScrollBarEnabled(true);
           notesScroll.setOnTouchListener((view, event) -> {
               if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) {
                   view.getParent().requestDisallowInterceptTouchEvent(true);
               } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                   view.getParent().requestDisallowInterceptTouchEvent(false);
               }
               return false;
           });
          notesScroll.setBackgroundResource(R.drawable.rounded_panel);
          notesScroll.addView(releaseNotes);
          notesScroll.setVisibility(android.view.View.GONE);
          LinearLayout.LayoutParams notesLp = new LinearLayout.LayoutParams(-1, dp(170));
          notesLp.setMargins(0, 0, 0, dp(10));
          root.addView(notesScroll, notesLp);
         Button update = new Button(this);
         update.setText(english ? "Check for updates" : "检查更新");
         update.setAllCaps(false);
         update.setBackgroundResource(R.drawable.rounded_panel);
          update.setOnClickListener(v -> checkForUpdates(english));
         LinearLayout.LayoutParams updateLp = new LinearLayout.LayoutParams(-1, dp(52));
         updateLp.setMargins(0, 0, 0, dp(10));
         root.addView(update, updateLp);
         downloadButton = new Button(this);
         downloadButton.setText(english ? "Download latest APK" : "下载最新 APK");
         downloadButton.setAllCaps(false);
         downloadButton.setBackgroundResource(R.drawable.button_green);
         downloadButton.setTextColor(Color.WHITE);
         downloadButton.setVisibility(android.view.View.GONE);
         LinearLayout.LayoutParams downloadLp = new LinearLayout.LayoutParams(-1, dp(52));
         downloadLp.setMargins(0, 0, 0, dp(8));
         root.addView(downloadButton, downloadLp);
         downloadHint = label(english
                 ? "Download address: GitHub. Use a proxy or VPN when GitHub cannot be opened."
                 : "下载地址是 GitHub，网页打不开时可以使用魔法下载最新版。", 12, Color.rgb(80, 88, 105));
         downloadHint.setPadding(dp(14), dp(8), dp(14), dp(8));
         downloadHint.setBackgroundResource(R.drawable.rounded_panel);
         downloadHint.setVisibility(android.view.View.GONE);
         LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, dp(50));
         hintLp.setMargins(0, 0, 0, dp(4));
         root.addView(downloadHint, hintLp);
        TextView thanksTitle = label(english ? "Acknowledgements" : "感谢", 16, Color.rgb(20, 29, 55));
        thanksTitle.setTypeface(null, 1);
        thanksTitle.setTextColor(Color.rgb(181, 103, 39));
        thanksTitle.setBackgroundResource(R.drawable.settings_thanks_title);
        LinearLayout.LayoutParams thanksTitleLp = new LinearLayout.LayoutParams(-1, dp(34));
        thanksTitleLp.setMargins(dp(4), dp(24), dp(4), dp(8));
        root.addView(thanksTitle, thanksTitleLp);
        TextView thanks = label(english
                ? "Thanks to Coolapk user and GitHub user yangFenTuoZi for developing the Dsu img lossless replacement feature.\nIf any content infringes your rights, please contact the author and it will be removed promptly."
                : "感谢酷安用户及 GitHub 用户 yangFenTuoZi 开发 Dsu 功能修改 img 无损替换功能。\n如有侵权，请联系作者，我们会及时删除相关内容。", 14, Color.rgb(80, 88, 105));
        thanks.setPadding(dp(14), dp(10), dp(14), dp(10));
        thanks.setBackgroundResource(R.drawable.rounded_panel);
        root.addView(thanks, new LinearLayout.LayoutParams(-1, dp(86)));
         TextView version = label(english ? "Dsu Manager 3.3.1" : "Dsu 管理器 3.3.1", 13, Color.rgb(110, 118, 135));
        version.setPadding(dp(14), 0, dp(14), 0);
        version.setBackgroundResource(R.drawable.rounded_panel);
        LinearLayout.LayoutParams versionLp = new LinearLayout.LayoutParams(-1, dp(42));
        versionLp.setMargins(0, dp(10), 0, 0);
        root.addView(version, versionLp);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
         setContentView(scroll);
     }

    private void checkForUpdates(boolean english) {
        updateStatus.setText(english ? "Checking for updates..." : "正在检查更新...");
        ViewParent initialNotesParent = releaseNotes.getParent();
        if (initialNotesParent instanceof android.view.View) ((android.view.View) initialNotesParent).setVisibility(android.view.View.GONE);
        downloadButton.setVisibility(android.view.View.GONE);
        downloadHint.setVisibility(android.view.View.GONE);
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(LATEST_RELEASE_API).openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                }
                JSONObject release = new JSONObject(body.toString());
                String tag = release.optString("tag_name", "");
                String latestVersion = tag.startsWith("v") ? tag.substring(1) : tag;
                String notes = release.optString("body", "").trim();
                String downloadUrl = release.optString("html_url", "https://github.com/955xiaolan520/Dsu-Manager/releases");
                JSONArray assets = release.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.optJSONObject(i);
                        if (asset != null && asset.optString("name", "").endsWith(".apk") && !asset.optString("name", "").contains("debug")) {
                            downloadUrl = asset.optString("browser_download_url", downloadUrl);
                            break;
                        }
                    }
                }
                final String resultVersion = latestVersion;
                final String resultNotes = notes.isEmpty() ? (english ? "No release notes." : "暂无更新说明。") : notes;
                final String resultUrl = downloadUrl;
                new Handler(Looper.getMainLooper()).post(() -> {
                    boolean newer = isVersionNewer(resultVersion, CURRENT_VERSION);
                    updateStatus.setText(newer
                            ? (english ? "New version available: " + resultVersion : "发现新版本: " + resultVersion)
                            : (english ? "You are using the latest version: " + CURRENT_VERSION : "当前已是最新版本: " + CURRENT_VERSION));
                    releaseNotes.setText((english ? "Release notes:\n" : "更新内容:\n") + resultNotes);
                    ViewParent notesParent = releaseNotes.getParent();
                    if (notesParent instanceof android.view.View) ((android.view.View) notesParent).setVisibility(android.view.View.VISIBLE);
                    if (newer) {
                        downloadButton.setVisibility(android.view.View.VISIBLE);
                        downloadHint.setVisibility(android.view.View.VISIBLE);
                        downloadButton.setOnClickListener(v -> downloadAndInstall(resultUrl, english));
                    }
                });
            } catch (Exception error) {
                new Handler(Looper.getMainLooper()).post(() -> updateStatus.setText((english ? "Update check failed: " : "检查更新失败: ") + error.getMessage()));
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    private boolean isVersionNewer(String candidate, String current) {
        try {
            String[] candidateParts = candidate.split("\\.");
            String[] currentParts = current.split("\\.");
            int count = Math.max(candidateParts.length, currentParts.length);
            for (int i = 0; i < count; i++) {
                int candidatePart = i < candidateParts.length ? Integer.parseInt(candidateParts[i]) : 0;
                int currentPart = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
                if (candidatePart != currentPart) return candidatePart > currentPart;
            }
        } catch (NumberFormatException ignored) { }
        return false;
    }

    private void downloadAndInstall(String downloadUrl, boolean english) {
        downloadButton.setEnabled(false);
        downloadButton.setText(english ? "Downloading..." : "正在下载...");
        updateStatus.setText(english ? "Downloading update APK..." : "正在下载更新 APK...");
        new Thread(() -> {
            HttpURLConnection connection = null;
            File apk = new File(getCacheDir(), "dsu-manager-update.apk");
            try {
                connection = (HttpURLConnection) new URL(downloadUrl).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(true);
                if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) {
                    throw new IllegalStateException("HTTP " + connection.getResponseCode());
                }
                int contentLength = connection.getContentLength();
                try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(apk)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    long total = 0;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                        total += read;
                        if (contentLength > 0) {
                            int progress = (int) Math.min(100, total * 100 / contentLength);
                            new Handler(Looper.getMainLooper()).post(() -> downloadButton.setText((english ? "Downloading " : "下载中 ") + progress + "%"));
                        }
                    }
                }
                Uri apkUri = UpdateFileProvider.getUriForFile(this, apk);
                new Handler(Looper.getMainLooper()).post(() -> {
                    updateStatus.setText(english ? "Download complete. Confirm installation in the system installer." : "下载完成，请在系统安装界面确认安装。");
                    downloadButton.setEnabled(true);
                    downloadButton.setText(english ? "Install downloaded APK" : "安装已下载 APK");
                    downloadButton.setOnClickListener(v -> installApk(apkUri));
                    installApk(apkUri);
                });
            } catch (Exception error) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    downloadButton.setEnabled(true);
                    downloadButton.setText(english ? "Download latest APK" : "下载最新 APK");
                    updateStatus.setText((english ? "Download failed: " : "下载失败: ") + error.getMessage());
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    private void installApk(Uri apkUri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            updateStatus.setText("请在系统设置中允许本应用安装未知应用。\nPlease allow this app to install unknown apps in system settings.");
            Intent settings = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            startActivity(settings);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        intent.setData(apkUri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
    }
}
