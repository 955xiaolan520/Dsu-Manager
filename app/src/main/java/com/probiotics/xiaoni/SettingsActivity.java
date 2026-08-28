package com.probiotics.xiaoni;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ScrollView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.util.Locale;

public final class SettingsActivity extends Activity {
    private static final String LANGUAGE_KEY = "language_mode";

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
        Button back = new Button(this);
        back.setText("<");
        back.setTextSize(20);
        back.setMinWidth(0);
        back.setMinHeight(0);
        back.setOnClickListener(v -> finish());
        titleBar.addView(back, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView title = label(english ? "Settings" : "设置", 24, Color.rgb(20, 29, 55));
        title.setTypeface(null, 1);
        titleBar.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(titleBar);

        TextView languageTitle = label(english ? "Language" : "多语言", 16, Color.rgb(20, 29, 55));
        languageTitle.setTypeface(null, 1);
        root.addView(languageTitle, new LinearLayout.LayoutParams(-1, dp(48)));
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
        root.addView(languages);

        TextView updateTitle = label(english ? "Updates" : "更新", 16, Color.rgb(20, 29, 55));
        updateTitle.setTypeface(null, 1);
        LinearLayout.LayoutParams updateTitleLp = new LinearLayout.LayoutParams(-1, dp(48));
        updateTitleLp.setMargins(0, dp(20), 0, 0);
        root.addView(updateTitle, updateTitleLp);
        Button update = new Button(this);
        update.setText(english ? "Check for updates" : "检查更新");
        update.setAllCaps(false);
        update.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/955xiaolan520/Dsu-Manager/releases"))));
        root.addView(update, new LinearLayout.LayoutParams(-1, dp(52)));
        root.addView(label(english ? "Dsu Manager 3.1.1" : "Dsu 管理器 3.1.1", 13, Color.rgb(110, 118, 135)), new LinearLayout.LayoutParams(-1, dp(42)));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
    }
}
