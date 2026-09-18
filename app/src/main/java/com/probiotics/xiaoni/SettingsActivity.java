package com.probiotics.xiaoni;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
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

import java.util.Locale;

public final class SettingsActivity extends Activity {
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        Haptics.onTouch(getWindow().getDecorView(), event);
        return super.dispatchTouchEvent(event);
    }
    private static final String LANGUAGE_KEY = "language_mode";
    private static final String CURRENT_VERSION = BuildConfig.VERSION_NAME;
    private TextView updateStatus;
    private TextView releaseNotes;
    private TextView downloadHint;
    private Button downloadButton;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0x00000000);
        getWindow().setNavigationBarColor(0x220b131f);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
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
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                view.setPadding(dp(20), dp(18) + bars.top, dp(20), dp(20) + bars.bottom);
            } else {
                int top = insets.getSystemWindowInsetTop();
                int bottom = insets.getSystemWindowInsetBottom();
                view.setPadding(dp(20), dp(18) + top, dp(20), dp(20) + bottom);
            }
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
        back.setOnClickListener(v -> {
            finish();
            overridePendingTransition(R.anim.explode_in, R.anim.explode_out);
        });
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

        // 关于入口 - 放在语言设置后面
        TextView aboutTitle = label(english ? "About" : "关于", 16, Color.rgb(20, 29, 55));
        aboutTitle.setTypeface(null, 1);
        aboutTitle.setTextColor(Color.rgb(78, 87, 151));
        aboutTitle.setBackgroundResource(R.drawable.settings_language_title);
        LinearLayout.LayoutParams aboutTitleLp = new LinearLayout.LayoutParams(-1, dp(34));
        aboutTitleLp.setMargins(dp(4), dp(20), dp(4), dp(8));
        root.addView(aboutTitle, aboutTitleLp);
        
        Button aboutButton = new Button(this);
        aboutButton.setText(english ? "View About" : "查看关于");
        aboutButton.setAllCaps(false);
        aboutButton.setTextColor(0xffffffff); // 白色文字
        aboutButton.setTextSize(15);
        aboutButton.setTypeface(null, 1); // 加粗
        aboutButton.setBackgroundResource(R.drawable.button_green); // 使用绿色按钮背景
        aboutButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, AboutActivity.class);
            startActivity(intent);
            overridePendingTransition(R.anim.explode_in, R.anim.explode_out);
        });
        LinearLayout.LayoutParams aboutButtonLp = new LinearLayout.LayoutParams(-1, dp(52));
        aboutButtonLp.setMargins(0, 0, 0, dp(8));
        root.addView(aboutButton, aboutButtonLp);
        
        // 关于按钮说明文字
        TextView aboutHint = label(english 
                ? "View app information, feature description, version number, and acknowledgements" 
                : "查看应用信息、功能说明、版本号和致谢内容", 13, Color.rgb(110, 118, 135));
        aboutHint.setPadding(dp(14), dp(8), dp(14), dp(8));
        aboutHint.setBackgroundResource(R.drawable.rounded_panel);
        LinearLayout.LayoutParams aboutHintLp = new LinearLayout.LayoutParams(-1, -2);
        aboutHintLp.setMargins(0, 0, 0, dp(4));
        root.addView(aboutHint, aboutHintLp);

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
         TextView version = label(english ? "Dsu Manager " + CURRENT_VERSION : "Dsu 管理器 " + CURRENT_VERSION, 13, Color.rgb(110, 118, 135));
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
     
     @Override public void onBackPressed() {
         finish();
         overridePendingTransition(R.anim.explode_in, R.anim.explode_out);
     }

    private void checkForUpdates(boolean english) {
        updateStatus.setText(english ? "Checking for updates..." : "正在检查更新...");
        ViewParent initialNotesParent = releaseNotes.getParent();
        if (initialNotesParent instanceof android.view.View) ((android.view.View) initialNotesParent).setVisibility(android.view.View.GONE);
        downloadButton.setVisibility(android.view.View.GONE);
        downloadHint.setVisibility(android.view.View.GONE);
        // v3.9.1：检查走 UpdateCenter（直连 + 加速镜像逐个回退，国内不开 VPN 也能查到）
        new Thread(() -> {
            try {
                UpdateCenter.ReleaseInfo info = UpdateCenter.fetchLatest();
                final boolean newer = UpdateCenter.isVersionNewer(info.version, CURRENT_VERSION);
                final String resultVersion = info.version;
                final String resultNotes = info.notes.isEmpty()
                        ? (english ? "No release notes." : "暂无更新说明。") : info.notes;
                final UpdateCenter.ReleaseInfo result = info;
                new Handler(Looper.getMainLooper()).post(() -> {
                    updateStatus.setText(newer
                            ? (english ? "New version available: " + resultVersion : "发现新版本: " + resultVersion)
                            : (english ? "You are using the latest version: " + CURRENT_VERSION : "当前已是最新版本: " + CURRENT_VERSION));
                    releaseNotes.setText((english ? "Release notes:\n" : "更新内容:\n") + resultNotes);
                    ViewParent notesParent = releaseNotes.getParent();
                    if (notesParent instanceof android.view.View) ((android.view.View) notesParent).setVisibility(android.view.View.VISIBLE);
                    if (newer) {
                        downloadButton.setVisibility(android.view.View.VISIBLE);
                        downloadHint.setVisibility(android.view.View.VISIBLE);
                        downloadButton.setOnClickListener(v -> UpdateCenter.downloadWithDialog(this, result, english,
                                new UpdateCenter.Listener() {
                                    @Override public void onStatus(String message) {
                                        updateStatus.setText(message);
                                    }
                                    @Override public void onFinished() {
                                        updateStatus.setText(english
                                                ? "Download complete. Confirm installation in the system installer."
                                                : "下载完成，请在系统安装界面确认安装。");
                                        downloadButton.setEnabled(true);
                                        downloadButton.setText(english ? "Install downloaded APK" : "安装已下载 APK");
                                    }
                                    @Override public void onFailed(String error) {
                                        updateStatus.setText(error);
                                        downloadButton.setEnabled(true);
                                        downloadButton.setText(english ? "Download latest APK" : "下载最新 APK");
                                    }
                                }));
                    }
                });
            } catch (Exception error) {
                new Handler(Looper.getMainLooper()).post(() -> updateStatus.setText((english ? "Update check failed: " : "检查更新失败: ") + error.getMessage()));
            }
        }).start();
    }

    // v3.9.1：版本比较 / 说明清理 / 下载安装已统一收敛到 UpdateCenter
    //（多镜像回退 + 进度弹窗 + ZIP 校验 + 安装意图），设置页只保留状态展示。
}
