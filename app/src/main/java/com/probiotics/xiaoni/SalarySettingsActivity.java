package com.probiotics.xiaoni;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.*;
import android.view.View;

/**
 * v3.52.1: 薪资设置独立页面
 */
public class SalarySettingsActivity extends BaseActivity {
    private SharedPreferences prefs;
    private RadioGroup modeGroup;
    private LinearLayout formCard;
    
    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + 0.5f); }
    private String t(String zh, String en) {
        return getResources().getConfiguration().locale.getLanguage().startsWith("zh") ? zh : en;
    }
    
    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        Haptics.onTouch(getWindow().getDecorView(), e);
        return super.dispatchTouchEvent(e);
    }
    
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("salary", MODE_PRIVATE);
        
        getWindow().setStatusBarColor(0x00000000);
        getWindow().setNavigationBarColor(0x220b131f);
        
        int sb = 0;
        int rid = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (rid > 0) sb = getResources().getDimensionPixelSize(rid);
        
        FrameLayout root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xffd7e3f0, 0xff8fa8c4});
        root.setBackground(bg);
        root.setPadding(0, sb + dp(8), 0, 0);
        
        ScrollView page = new ScrollView(this);
        page.setFillViewport(false);
        page.setVerticalScrollBarEnabled(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(6), dp(16), dp(24));
        page.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        
        buildTitleBar(content);
        buildForm(content);
        buildSaveButton(content);
    }
    
    private void buildTitleBar(LinearLayout parent) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        
        Button back = new Button(this, null, 0);
        back.setText("‹");
        back.setTextSize(26);
        back.setAllCaps(false);
        back.setTextColor(0xff0f1e36);
        back.setMinWidth(0);
        back.setMinHeight(0);
        back.setGravity(Gravity.CENTER);
        back.setPadding(0, 0, 0, 0);
        GradientDrawable bkBg = new GradientDrawable();
        bkBg.setColor(0x59FFFFFF);
        bkBg.setCornerRadius(dp(23));
        bkBg.setStroke(Math.max(1, dp(1)), 0x80FFFFFF);
        back.setBackground(bkBg);
        back.setStateListAnimator(null);
        back.setOnClickListener(v -> { 
            Haptics.perform(v); 
            finish(); 
            overridePendingTransition(R.anim.slide_up_out, R.anim.fade_out); 
        });
        bar.addView(back, new LinearLayout.LayoutParams(dp(46), dp(46)));
        
        TextView title = new TextView(this);
        title.setText(t("薪资设置", "Salary Settings"));
        title.setTextSize(19);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xff0f1e36);
        title.setPadding(dp(12), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, -2);
        barLp.bottomMargin = dp(12);
        parent.addView(bar, barLp);
    }
    
    private void buildForm(LinearLayout parent) {
        formCard = new LinearLayout(this);
        formCard.setOrientation(LinearLayout.VERTICAL);
        formCard.setPadding(dp(16), dp(14), dp(16), dp(16));
        GradientDrawable cb = new GradientDrawable();
        cb.setColor(0x4DFFFFFF);
        cb.setCornerRadius(dp(18));
        cb.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
        formCard.setBackground(cb);
        
        addSectionTitle(formCard, "薪资模式");
        
        modeGroup = new RadioGroup(this);
        modeGroup.setPadding(dp(8), dp(8), dp(8), dp(8));
        String[] modes = {"月薪制(固定工资)", "日薪制(底薪+加班)", "小时工", "计件工资"};
        int currentMode = prefs.getInt("mode", 1);
        for (int i = 0; i < modes.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(modes[i]);
            rb.setTextSize(14);
            rb.setTextColor(0xff17334f);
            rb.setId(i);
            rb.setChecked(i == currentMode);
            LinearLayout.LayoutParams rbLp = new LinearLayout.LayoutParams(-1, -2);
            rbLp.topMargin = dp(6);
            modeGroup.addView(rb, rbLp);
        }
        formCard.addView(modeGroup);
        
        addSectionTitle(formCard, "收入项");
        addStyledInput(formCard, "base", "基本工资/底薪/时薪");
        addStyledInput(formCard, "perf", "绩效奖金");
        addStyledInput(formCard, "attend", "全勤奖");
        addStyledInput(formCard, "position", "岗位补贴");
        addStyledInput(formCard, "seniority", "工龄工资");
        addStyledInput(formCard, "meal", "餐补");
        addStyledInput(formCard, "transport", "交通补贴");
        addStyledInput(formCard, "night", "夜班补贴(元/小时)");
        addStyledInput(formCard, "heat", "高温补贴");
        addStyledInput(formCard, "otherIncome", "其他收入");
        
        addSectionTitle(formCard, "扣款项");
        addStyledInput(formCard, "social", "社保个人部分");
        addStyledInput(formCard, "fund", "公积金个人部分");
        addStyledInput(formCard, "absent", "缺勤扣款(元/天)");
        addStyledInput(formCard, "late", "迟到扣款(元/次)");
        addStyledInput(formCard, "otherDeduct", "其他扣款");
        
        addSectionTitle(formCard, "工作参数");
        addStyledInput(formCard, "dailyhours", "每日标准工作时长(小时)");
        
        addSectionTitle(formCard, "计件工资参数");
        addStyledInput(formCard, "pieceRate", "计件单价(元/件)");
        addStyledInput(formCard, "pieceQuota", "每日计件定额(件)");
        addStyledInput(formCard, "pieceBonus", "超额奖励(元/件,超出定额部分)");
        
        parent.addView(formCard, new LinearLayout.LayoutParams(-1, -2));
    }
    
    private void buildSaveButton(LinearLayout parent) {
        Button saveBtn = new Button(this, null, 0);
        saveBtn.setText("保存");
        saveBtn.setTextSize(16);
        saveBtn.setTextColor(0xFFFFFFFF);
        saveBtn.setTypeface(null, Typeface.BOLD);
        saveBtn.setAllCaps(false);
        saveBtn.setGravity(Gravity.CENTER);
        saveBtn.setMinHeight(dp(52));
        saveBtn.setPadding(0, 0, 0, 0);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setOrientation(GradientDrawable.Orientation.TL_BR);
        btnBg.setColors(new int[]{0xFF35A8C4, 0xFF0E7D95});
        btnBg.setCornerRadius(dp(16));
        saveBtn.setBackground(btnBg);
        saveBtn.setStateListAnimator(null);
        saveBtn.setOnClickListener(v -> {
            Haptics.perform(v);
            saveConfig();
            finish();
            overridePendingTransition(R.anim.slide_up_out, R.anim.fade_out);
        });
        
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-1, dp(52));
        btnLp.topMargin = dp(16);
        parent.addView(saveBtn, btnLp);
    }
    
    private void addSectionTitle(LinearLayout parent, String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(15);
        tv.setTextColor(0xff0f5c4f);
        tv.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(16);
        lp.bottomMargin = dp(8);
        parent.addView(tv, lp);
    }
    
    private void addStyledInput(LinearLayout parent, String key, String label) {
        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(13);
        labelView.setTextColor(0xff17334f);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(-1, -2);
        labelLp.topMargin = dp(10);
        parent.addView(labelView, labelLp);
        
        EditText input = new EditText(this);
        input.setTag(key);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        float savedValue = prefs.getFloat(key, 0);
        if (savedValue > 0) {
            input.setText(String.valueOf(savedValue));
        } else {
            input.setText("");
        }
        input.setHint("请输入");
        input.setTextSize(14);
        input.setTextColor(0xff0f1e36);
        input.setHintTextColor(0xff999999);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(0x4DFFFFFF);
        inputBg.setCornerRadius(dp(12));
        inputBg.setStroke(Math.max(1, dp(1)), 0x6635A8C4);
        input.setBackground(inputBg);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(-1, -2);
        inputLp.topMargin = dp(6);
        parent.addView(input, inputLp);
    }
    
    private void saveConfig() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt("mode", modeGroup.getCheckedRadioButtonId());
        
        for (int i = 0; i < formCard.getChildCount(); i++) {
            View v = formCard.getChildAt(i);
            if (v instanceof EditText) {
                EditText input = (EditText) v;
                String key = (String) input.getTag();
                if (key != null) {
                    try {
                        String text = input.getText().toString().trim();
                        float value = text.isEmpty() ? 0 : Float.parseFloat(text);
                        editor.putFloat(key, value);
                    } catch (Exception e) {
                        editor.putFloat(key, 0);
                    }
                }
            }
        }
        
        editor.apply();
        Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show();
    }
}
