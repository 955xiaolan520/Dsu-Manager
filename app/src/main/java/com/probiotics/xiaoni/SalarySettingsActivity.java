package com.probiotics.xiaoni;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.*;
import android.view.View;

/**
 * v3.52.1: 薪资设置独立页面
 */
public class SalarySettingsActivity extends BaseActivity {
    private SharedPreferences prefs;
    private RadioGroup modeGroup;
    private LinearLayout formCard;
    private TextView modeFormulaView;
    private LinearLayout modeInputs;
    private LinearLayout modeDependentContainer;
    private RadioGroup scheduleGroup;
    
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
    public void onBackPressed() {
        finish();
        overridePendingTransition(R.anim.fade_in, R.anim.slide_up_out);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("salary", MODE_PRIVATE);
        
        getWindow().setStatusBarColor(0x00000000);
        getWindow().setNavigationBarColor(0x220b131f);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        
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
            overridePendingTransition(R.anim.fade_in, R.anim.slide_up_out);
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
        String[] modes = {"月薪制（固定工资）", "日薪制（底薪＋加班）", "小时工（工价×总工时）", "计件工资"};
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
        modeFormulaView = new TextView(this);
        modeFormulaView.setTextSize(12);
        modeFormulaView.setTextColor(0xff31516e);
        modeFormulaView.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable formulaBg = new GradientDrawable();
        formulaBg.setColor(0x33FFFFFF);
        formulaBg.setCornerRadius(dp(12));
        formulaBg.setStroke(Math.max(1, dp(1)), 0x5535A8C4);
        modeFormulaView.setBackground(formulaBg);
        formCard.addView(modeFormulaView, new LinearLayout.LayoutParams(-1, -2));
        modeDependentContainer = new LinearLayout(this);
        modeDependentContainer.setOrientation(LinearLayout.VERTICAL);
        formCard.addView(modeDependentContainer, new LinearLayout.LayoutParams(-1, -2));
        modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            updateModeFormula(checkedId);
            rebuildModeDependentSections(checkedId);
        });
        updateModeFormula(currentMode);
        rebuildModeDependentSections(currentMode);
        addSectionTitle(formCard, "个人所得税（可调整）");
        addStyledInput(formCard, "taxAdditionalDeduction", "每月专项附加扣除合计");
        addStyledInput(formCard, "taxOtherDeduction", "每月依法确定的其他扣除");
        addSectionTitle(formCard, "固定扣款");
        addStyledInput(formCard, "social", "社保个人部分");
        addStyledInput(formCard, "fund", "公积金个人部分");
        addStyledInput(formCard, "otherDeduct", "其他固定扣款");
        addStyledInput(formCard, "absent", "缺勤扣款(元/天)");
        addStyledInput(formCard, "late", "迟到扣款(元/次)");
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
            overridePendingTransition(R.anim.fade_in, R.anim.slide_up_out);
        });
        
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-1, dp(52));
        btnLp.topMargin = dp(16);
        parent.addView(saveBtn, btnLp);
    }
    
    private void rebuildModeDependentSections(int mode) {
        if (modeDependentContainer == null) return;
        modeDependentContainer.removeAllViews();
        scheduleGroup = null;
        modeInputs = new LinearLayout(this);
        modeInputs.setOrientation(LinearLayout.VERTICAL);
        modeDependentContainer.addView(modeInputs, new LinearLayout.LayoutParams(-1, -2));
        addSectionTitle(modeInputs, "当前模式参数");
        if (mode == 0) {
            addStyledInput(modeInputs, "base", "固定月薪(元)");
            addSectionTitle(modeInputs, "月薪附加收入");
            addStyledInput(modeInputs, "perf", "绩效奖金");
            addStyledInput(modeInputs, "attend", "全勤奖");
            addStyledInput(modeInputs, "position", "岗位补贴");
            addStyledInput(modeInputs, "seniority", "工龄工资");
            addStyledInput(modeInputs, "meal", "餐补");
            addStyledInput(modeInputs, "transport", "交通补贴");
            addStyledInput(modeInputs, "otherIncome", "其他收入");
        } else if (mode == 1) {
            addStyledInput(modeInputs, "base", "底薪(元)");
            addSectionTitle(modeInputs, "工作制度");
            scheduleGroup = new RadioGroup(this);
            scheduleGroup.setOrientation(RadioGroup.HORIZONTAL);
            int schedule = prefs.getInt("workSchedule", 5);
            RadioButton fiveDay = new RadioButton(this);
            fiveDay.setId(5); fiveDay.setText("五天八小时"); fiveDay.setTextColor(0xff17334f); fiveDay.setChecked(schedule != 6);
            RadioButton sixDay = new RadioButton(this);
            sixDay.setId(6); sixDay.setText("六天八小时"); sixDay.setTextColor(0xff17334f); sixDay.setChecked(schedule == 6);
            scheduleGroup.addView(fiveDay, new RadioGroup.LayoutParams(0, -2, 1f));
            scheduleGroup.addView(sixDay, new RadioGroup.LayoutParams(0, -2, 1f));
            modeInputs.addView(scheduleGroup);
            addSectionTitle(modeInputs, "日薪附加收入与加班");
            addStyledInput(modeInputs, "perf", "绩效奖金");
            addStyledInput(modeInputs, "attend", "全勤奖");
            addStyledInput(modeInputs, "position", "岗位补贴");
            addStyledInput(modeInputs, "seniority", "工龄工资");
            addStyledInput(modeInputs, "meal", "餐补");
            addStyledInput(modeInputs, "transport", "交通补贴");
            addStyledInput(modeInputs, "night", "夜班补贴(元/小时)");
            addStyledInput(modeInputs, "heat", "高温补贴");
            addStyledInput(modeInputs, "otherIncome", "其他收入");
            addStyledInput(modeInputs, "weekdayOvertimeMultiplier", "平日加班倍数（例如 1.5）");
            addStyledInput(modeInputs, "weekendOvertimeMultiplier", "周六日加班倍数（例如 2）");
            addStyledInput(modeInputs, "holidayOvertimeMultiplier", "法定节假日加班倍数（例如 3）");
        } else if (mode == 2) {
            addStyledInput(modeInputs, "base", "时薪(元/小时)");
            addSectionTitle(modeInputs, "小时工附加项目");
            addStyledInput(modeInputs, "perf", "绩效奖金");
            addStyledInput(modeInputs, "otherIncome", "其他收入");
            addStyledInput(modeInputs, "night", "夜班补贴(元/小时)");
        } else {
            addStyledInput(modeInputs, "pieceRate", "计件单价(元/件)");
            addStyledInput(modeInputs, "pieceQuota", "每日计件定额(件)");
            addStyledInput(modeInputs, "pieceBonus", "超额奖励(元/件)");
            addSectionTitle(modeInputs, "计件附加项目");
            addStyledInput(modeInputs, "perf", "绩效奖金");
            addStyledInput(modeInputs, "position", "岗位补贴");
            addStyledInput(modeInputs, "otherIncome", "其他收入");
        }
    }

    private void buildModeInputs(int mode) {
        if (modeInputs == null) return;
        modeInputs.removeAllViews();
        addSectionTitle(modeInputs, "当前模式参数");
        if (mode == 0) {
            addStyledInput(modeInputs, "base", "固定月薪(元)");
        } else if (mode == 1) {
            addStyledInput(modeInputs, "base", "底薪(元)");
        } else if (mode == 2) {
            addStyledInput(modeInputs, "base", "时薪(元/小时)");
        } else if (mode == 3) {
            addStyledInput(modeInputs, "pieceRate", "计件单价(元/件)");
            addStyledInput(modeInputs, "pieceQuota", "每日计件定额(件)");
            addStyledInput(modeInputs, "pieceBonus", "超额奖励(元/件)");
        }
    }

    private void updateModeFormula(int mode) {
        if (modeFormulaView == null) return;
        String formula;
        switch (mode) {
            case 0:
                formula = "月薪制：固定月薪 + 绩效/补贴/其他收入 − 各项扣款";
                break;
            case 1:
                formula = "日薪制：按日历实际应出勤天数×8小时计算满额工时；底薪÷8÷应出勤天数×已完成工时 + 三类加班工资 + 补贴 − 扣款";
                break;
            case 2:
                formula = "小时工：工价 ×（白班工时 + 夜班工时 + 加班工时）+ 补贴 − 扣款";
                break;
            case 3:
                formula = "计件工资：完成件数×单价 + max(0,完成件数−定额×出勤天数)×超额单价 + 补贴 − 扣款";
                break;
            default:
                formula = "请选择一种薪资模式查看计算方式";
        }
        modeFormulaView.setText(formula);
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
        input.setInputType("overtimeBaseDays".equals(key)
                ? InputType.TYPE_CLASS_NUMBER
                : (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL));
        // 初次进入所有金额均为空，由用户自行填写；已有保存值才回显。
        if (prefs.contains(key)) {
            try {
                String saved = prefs.getString(key, null);
                if (saved != null && !saved.trim().isEmpty()) input.setText(saved);
            } catch (ClassCastException ignored) {
                float savedValue = prefs.getFloat(key, 0f);
                if (savedValue != 0f) input.setText(String.valueOf(savedValue));
            }
        }
        input.setHint("overtimeBaseDays".equals(key) ? "请输入完整天数" : (key.contains("Multiplier") ? "请输入倍数" : "请输入金额"));
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
    
    private void saveInputs(ViewGroup group, SharedPreferences.Editor editor) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View view = group.getChildAt(i);
            if (view instanceof EditText) {
                EditText input = (EditText) view;
                String key = (String) input.getTag();
                if (key != null) {
                    try {
                        String value = input.getText().toString().trim();
                        if ("overtimeBaseDays".equals(key)) editor.putFloat(key, value.isEmpty() ? 22f : Math.round(Float.parseFloat(value)));
                        else editor.putFloat(key, value.isEmpty() ? 0f : Float.parseFloat(value));
                    } catch (Exception ignored) { editor.putFloat(key, 0f); }
                }
            } else if (view instanceof ViewGroup) saveInputs((ViewGroup) view, editor);
        }
    }

    private void saveConfig() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt("mode", modeGroup.getCheckedRadioButtonId());
        if (scheduleGroup != null) {
            editor.putInt("workSchedule", scheduleGroup.getCheckedRadioButtonId() == 6 ? 6 : 5);
        }
        
        saveInputs(formCard, editor);
        editor.apply();
        Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show();
    }
}
