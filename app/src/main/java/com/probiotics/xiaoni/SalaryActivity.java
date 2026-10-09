package com.probiotics.xiaoni;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * v3.52.3: 修复所有输入框预设值问题+添加上班时间选择器
 */
public class SalaryActivity extends BaseActivity {
    private SharedPreferences prefs;
    private Calendar calendar;
    private TextView monthTitle;
    private GridLayout calendarGrid;
    private TextView grossSalaryView;
    private TextView netSalaryView;
    private LinearLayout detailPanel;
    
    private int salaryMode = 1;
    private float baseSalary = 0;
    private float performance = 0;
    private float attendance = 0;
    private float positionAllowance = 0;
    private float seniorityPay = 0;
    private float mealAllowance = 0;
    private float transportAllowance = 0;
    private float nightPayRate = 0;
    private float heatAllowance = 0;
    private float otherIncome = 0;
    private float socialSecurity = 0;
    private float housingFund = 0;
    private float absentDeduction = 0;
    private float lateDeduction = 0;
    private float otherDeduction = 0;
    private float dailyHours = 8;
    private float pieceRate = 0;
    
    private static final Set<String> HOLIDAYS_2026 = new HashSet<>(Arrays.asList(
        "2026-01-01", "2026-01-02", "2026-01-03",
        "2026-02-17", "2026-02-18", "2026-02-19", "2026-02-20", "2026-02-21", "2026-02-22", "2026-02-23",
        "2026-04-05", "2026-04-06", "2026-04-07",
        "2026-05-01", "2026-05-02", "2026-05-03",
        "2026-06-25", "2026-06-26", "2026-06-27",
        "2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04", "2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08", "2026-10-09"
    ));
    
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
        calendar = Calendar.getInstance();
        loadConfig();
        
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
        buildMonthSelector(content);
        buildCalendar(content);
        buildSalaryCard(content);
        buildDetailCard(content);
        
        checkCalendarPermission();
        refreshCalendar();
    }
    
    private void checkCalendarPermission() {
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, 1001);
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1001 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            refreshCalendar();
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        loadConfig();
        refreshCalendar();
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
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right); 
        });
        bar.addView(back, new LinearLayout.LayoutParams(dp(46), dp(46)));
        
        TextView title = new TextView(this);
        title.setText(t("工资工时记账", "Salary Tracker"));
        title.setTextSize(19);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xff0f1e36);
        title.setPadding(dp(12), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        
        Button settings = new Button(this, null, 0);
        settings.setText("⚙");
        settings.setTextSize(18);
        settings.setAllCaps(false);
        settings.setTextColor(0xff0f1e36);
        settings.setMinWidth(0);
        settings.setMinHeight(0);
        settings.setGravity(Gravity.CENTER);
        settings.setPadding(0, 0, 0, 0);
        GradientDrawable setBg = new GradientDrawable();
        setBg.setColor(0x59FFFFFF);
        setBg.setCornerRadius(dp(23));
        setBg.setStroke(Math.max(1, dp(1)), 0x80FFFFFF);
        settings.setBackground(setBg);
        settings.setStateListAnimator(null);
        settings.setOnClickListener(v -> { 
            Haptics.perform(v); 
            startActivity(new Intent(this, SalarySettingsActivity.class));
            overridePendingTransition(R.anim.slide_up_in, R.anim.fade_in);
        });
        bar.addView(settings, new LinearLayout.LayoutParams(dp(46), dp(46)));
        
        Button stats = new Button(this, null, 0);
        stats.setText("📊");
        stats.setTextSize(16);
        stats.setAllCaps(false);
        stats.setMinWidth(0);
        stats.setMinHeight(0);
        stats.setGravity(Gravity.CENTER);
        stats.setPadding(0, 0, 0, 0);
        GradientDrawable statsBg = new GradientDrawable();
        statsBg.setColor(0x59FFFFFF);
        statsBg.setCornerRadius(dp(23));
        statsBg.setStroke(Math.max(1, dp(1)), 0x80FFFFFF);
        stats.setBackground(statsBg);
        stats.setStateListAnimator(null);
        stats.setOnClickListener(v -> { 
            Haptics.perform(v); 
            startActivity(new Intent(this, SalaryStatsActivity.class));
            overridePendingTransition(R.anim.slide_up_in, R.anim.fade_in);
        });
        LinearLayout.LayoutParams statsParams = new LinearLayout.LayoutParams(dp(46), dp(46));
        statsParams.leftMargin = dp(8);
        bar.addView(stats, statsParams);
        
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, -2);
        barLp.bottomMargin = dp(12);
        parent.addView(bar, barLp);
    }
    
    private void buildMonthSelector(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable cb = new GradientDrawable();
        cb.setColor(0x4DFFFFFF);
        cb.setCornerRadius(dp(18));
        cb.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
        card.setBackground(cb);
        
        Button prevBtn = new Button(this, null, 0);
        prevBtn.setText("‹");
        prevBtn.setTextSize(22);
        prevBtn.setAllCaps(false);
        prevBtn.setTextColor(0xff0f1e36);
        prevBtn.setMinWidth(0);
        prevBtn.setMinHeight(0);
        prevBtn.setGravity(Gravity.CENTER);
        prevBtn.setPadding(0, 0, 0, 0);
        GradientDrawable prevBg = new GradientDrawable();
        prevBg.setColor(0x33FFFFFF);
        prevBg.setCornerRadius(dp(18));
        prevBtn.setBackground(prevBg);
        prevBtn.setStateListAnimator(null);
        prevBtn.setOnClickListener(v -> {
            Haptics.perform(v);
            calendar.add(Calendar.MONTH, -1);
            refreshCalendar();
        });
        card.addView(prevBtn, new LinearLayout.LayoutParams(dp(36), dp(36)));
        
        monthTitle = new TextView(this);
        monthTitle.setTextSize(16);
        monthTitle.setTextColor(0xff0f1e36);
        monthTitle.setTypeface(null, Typeface.BOLD);
        monthTitle.setGravity(Gravity.CENTER);
        card.addView(monthTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        
        Button nextBtn = new Button(this, null, 0);
        nextBtn.setText("›");
        nextBtn.setTextSize(22);
        nextBtn.setAllCaps(false);
        nextBtn.setTextColor(0xff0f1e36);
        nextBtn.setMinWidth(0);
        nextBtn.setMinHeight(0);
        nextBtn.setGravity(Gravity.CENTER);
        nextBtn.setPadding(0, 0, 0, 0);
        GradientDrawable nextBg = new GradientDrawable();
        nextBg.setColor(0x33FFFFFF);
        nextBg.setCornerRadius(dp(18));
        nextBtn.setBackground(nextBg);
        nextBtn.setStateListAnimator(null);
        nextBtn.setOnClickListener(v -> {
            Haptics.perform(v);
            calendar.add(Calendar.MONTH, 1);
            refreshCalendar();
        });
        card.addView(nextBtn, new LinearLayout.LayoutParams(dp(36), dp(36)));
        
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        parent.addView(card, lp);
    }
    
    private void buildCalendar(LinearLayout parent) {
        LinearLayout calCard = new LinearLayout(this);
        calCard.setOrientation(LinearLayout.VERTICAL);
        calCard.setPadding(dp(10), dp(12), dp(10), dp(12));
        GradientDrawable cb = new GradientDrawable();
        cb.setColor(0x4DFFFFFF);
        cb.setCornerRadius(dp(18));
        cb.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
        calCard.setBackground(cb);
        
        GridLayout weekHeader = new GridLayout(this);
        weekHeader.setColumnCount(7);
        String[] weeks = {"日", "一", "二", "三", "四", "五", "六"};
        for (String w : weeks) {
            TextView tv = new TextView(this);
            tv.setText(w);
            tv.setTextSize(11);
            tv.setTextColor(0xff5a6b82);
            tv.setTypeface(null, Typeface.BOLD);
            tv.setGravity(Gravity.CENTER);
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = dp(28);
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            weekHeader.addView(tv, params);
        }
        calCard.addView(weekHeader);
        
        calendarGrid = new GridLayout(this);
        calendarGrid.setColumnCount(7);
        calCard.addView(calendarGrid);
        
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        parent.addView(calCard, lp);
    }
    
    private void buildSalaryCard(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable cb = new GradientDrawable();
        cb.setOrientation(GradientDrawable.Orientation.TL_BR);
        cb.setColors(new int[]{0xFF35A8C4, 0xFF0E7D95});
        cb.setCornerRadius(dp(20));
        cb.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
        card.setBackground(cb);
        
        TextView label = new TextView(this);
        label.setText(t("本月工资", "Monthly Salary"));
        label.setTextSize(12);
        label.setTextColor(0xccffffff);
        label.setGravity(Gravity.CENTER);
        card.addView(label);
        
        grossSalaryView = new TextView(this);
        grossSalaryView.setTextSize(14);
        grossSalaryView.setTextColor(0xffffffff);
        grossSalaryView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
        glp.topMargin = dp(4);
        card.addView(grossSalaryView, glp);
        
        netSalaryView = new TextView(this);
        netSalaryView.setTextSize(26);
        netSalaryView.setTextColor(0xffffffff);
        netSalaryView.setTypeface(null, Typeface.BOLD);
        netSalaryView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = dp(6);
        card.addView(netSalaryView, nlp);
        
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        parent.addView(card, lp);
    }
    
    private void buildDetailCard(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable cb = new GradientDrawable();
        cb.setColor(0x4DFFFFFF);
        cb.setCornerRadius(dp(18));
        cb.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
        card.setBackground(cb);
        
        TextView title = new TextView(this);
        title.setText(t("工资明细", "Details"));
        title.setTextSize(14);
        title.setTextColor(0xff0f1e36);
        title.setTypeface(null, Typeface.BOLD);
        card.addView(title);
        
        detailPanel = new LinearLayout(this);
        detailPanel.setOrientation(LinearLayout.VERTICAL);
        detailPanel.setPadding(0, dp(8), 0, 0);
        card.addView(detailPanel);
        
        parent.addView(card, new LinearLayout.LayoutParams(-1, -2));
    }
    
    private void refreshCalendar() {
        calendarGrid.removeAllViews();
        Calendar cal = (Calendar) calendar.clone();
        cal.set(Calendar.DAY_OF_MONTH, 1);
        int month = cal.get(Calendar.MONTH);
        int firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1;
        
        monthTitle.setText(new SimpleDateFormat("yyyy年MM月", Locale.CHINA).format(cal.getTime()));
        
        for (int i = 0; i < firstDayOfWeek; i++) {
            calendarGrid.addView(new View(this), dayParams());
        }
        
        while (cal.get(Calendar.MONTH) == month) {
            int day = cal.get(Calendar.DAY_OF_MONTH);
            String dateKey = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(cal.getTime());
            boolean isWeekend = cal.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || 
                               cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY;
            boolean isHoliday = HOLIDAYS_2026.contains(dateKey);
            
            String lunarInfo = getLunarAndFestival(cal);
            
            LinearLayout dayCell = buildDayCell(day, dateKey, isWeekend, isHoliday, lunarInfo);
            calendarGrid.addView(dayCell, dayParams());
            
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        
        calculateMonthlySalary();
    }
    
    private String getLunarAndFestival(Calendar cal) {
        int month = cal.get(Calendar.MONTH) + 1;
        int day = cal.get(Calendar.DAY_OF_MONTH);
        String dateKey = month + "-" + day;
        
        // 优先读取系统日历
        String systemEvent = getSystemCalendarEvent(cal);
        if (systemEvent != null && !systemEvent.isEmpty()) {
            return systemEvent;
        }
        
        // 回退到预设节日
        Map<String, String> festivals = new HashMap<>();
        festivals.put("1-1", "元旦");
        festivals.put("2-14", "情人节");
        festivals.put("3-8", "妇女节");
        festivals.put("5-1", "劳动节");
        festivals.put("5-4", "青年节");
        festivals.put("6-1", "儿童节");
        festivals.put("9-10", "教师节");
        festivals.put("10-1", "国庆节");
        festivals.put("12-25", "圣诞节");
        
        if (festivals.containsKey(dateKey)) {
            return festivals.get(dateKey);
        }
        
        return "";
    }
    
    private String getSystemCalendarEvent(Calendar cal) {
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        
        try {
            ContentResolver cr = getContentResolver();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
            String dateStr = sdf.format(cal.getTime());
            
            long startTime = cal.getTimeInMillis();
            long endTime = startTime + 24 * 60 * 60 * 1000;
            
            String[] projection = new String[]{
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND
            };
            
            Uri.Builder builder = CalendarContract.Instances.CONTENT_URI.buildUpon();
            android.content.ContentUris.appendId(builder, startTime);
            android.content.ContentUris.appendId(builder, endTime);
            
            Cursor cursor = cr.query(builder.build(), projection, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                String title = cursor.getString(0);
                cursor.close();
                return title;
            }
            if (cursor != null) cursor.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        return null;
    }
    
    private GridLayout.LayoutParams dayParams() {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = dp(76);
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        return params;
    }
    
    private LinearLayout buildDayCell(int day, String dateKey, boolean isWeekend, boolean isHoliday, String lunarInfo) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(dp(3), dp(5), dp(3), dp(5));
        
        GradientDrawable cellBg = new GradientDrawable();
        if (isHoliday) {
            cellBg.setColor(0xFFFFE5CC);
            cellBg.setStroke(Math.max(1, dp(1)), 0xFFFFB366);
        } else if (isWeekend) {
            cellBg.setColor(0x26FFFFFF);
        } else {
            cellBg.setColor(0x1AFFFFFF);
        }
        cellBg.setCornerRadius(dp(10));
        cell.setBackground(cellBg);
        
        TextView dayText = new TextView(this);
        dayText.setText(String.valueOf(day));
        dayText.setTextSize(13);
        dayText.setTextColor(isHoliday ? 0xFFCC6600 : (isWeekend ? 0xff5a6b82 : 0xff17334f));
        dayText.setTypeface(null, Typeface.BOLD);
        cell.addView(dayText);
        
        if (isHoliday) {
            TextView holidayMark = new TextView(this);
            holidayMark.setText("休");
            holidayMark.setTextSize(7);
            holidayMark.setTextColor(0xFFFFFFFF);
            holidayMark.setTypeface(null, Typeface.BOLD);
            holidayMark.setPadding(dp(3), dp(1), dp(3), dp(1));
            GradientDrawable markBg = new GradientDrawable();
            markBg.setColor(0xFFFF6600);
            markBg.setCornerRadius(dp(4));
            holidayMark.setBackground(markBg);
            cell.addView(holidayMark);
        } else if (!lunarInfo.isEmpty()) {
            TextView festivalText = new TextView(this);
            festivalText.setText(lunarInfo);
            festivalText.setTextSize(7);
            festivalText.setTextColor(0xFFFF6600);
            festivalText.setTypeface(null, Typeface.BOLD);
            cell.addView(festivalText);
        }
        
        String record = prefs.getString(dateKey, null);
        if (record != null) {
            String[] parts = record.split(",");
            float normalHours = Float.parseFloat(parts[0]);
            float nightHours = parts.length > 1 ? Float.parseFloat(parts[1]) : 0;
            float overtimeHours = parts.length > 2 ? Float.parseFloat(parts[2]) : 0;
            int late = parts.length > 3 ? parseIntSafe(parts[3]) : 0;
            
            if (normalHours > 0 || nightHours > 0 || overtimeHours > 0) {
                TextView hourText = new TextView(this);
                String hourStr = "";
                if (normalHours > 0) hourStr += String.format("%.1f", normalHours);
                if (nightHours > 0) hourStr += (hourStr.isEmpty() ? "" : "+") + String.format("%.1f夜", nightHours);
                if (overtimeHours > 0) hourStr += (hourStr.isEmpty() ? "" : "+") + String.format("%.1f加", overtimeHours);
                hourText.setText(hourStr);
                hourText.setTextSize(7);
                hourText.setTextColor(0xFF0E7D95);
                hourText.setTypeface(null, Typeface.BOLD);
                cell.addView(hourText);
            }
            
            if (late > 0) {
                TextView lateText = new TextView(this);
                lateText.setText("迟" + late);
                lateText.setTextSize(6);
                lateText.setTextColor(0xFFCC6600);
                cell.addView(lateText);
            }
        }
        
        cell.setOnClickListener(v -> { Haptics.perform(v); showDayEditor(dateKey, isWeekend, isHoliday); });
        return cell;
    }
    
    private int parseIntSafe(String str) {
        try {
            if (str == null || str.trim().isEmpty()) return 0;
            float f = Float.parseFloat(str.trim());
            return (int) f;
        } catch (Exception e) {
            return 0;
        }
    }
    
    private void showDayEditor(String dateKey, boolean isWeekend, boolean isHoliday) {
        final android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x80000000);
        root.setOnClickListener(v -> dialog.dismiss());
        
        ScrollView scrollCard = new ScrollView(this);
        scrollCard.setVerticalScrollBarEnabled(false);
        
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(18), dp(20), dp(20));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setOrientation(GradientDrawable.Orientation.TL_BR);
        cardBg.setColors(new int[]{0xFFEEF5F9, 0xFFD7E3F0});
        cardBg.setCornerRadius(dp(20));
        cardBg.setStroke(Math.max(1, dp(2)), 0x4035A8C4);
        card.setBackground(cardBg);
        card.setOnClickListener(v -> {});
        
        TextView title = new TextView(this);
        title.setText(dateKey + (isHoliday ? " 🎉 法定节假日" : (isWeekend ? " 🏖 周末" : "")));
        title.setTextSize(16);
        title.setTextColor(0xff0f1e36);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        card.addView(title);
        
        View divider = new View(this);
        divider.setBackgroundColor(0x3335A8C4);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(-1, dp(2));
        divLp.topMargin = dp(12);
        divLp.bottomMargin = dp(16);
        card.addView(divider, divLp);
        
        String record = prefs.getString(dateKey, null);
        String[] parts = record != null ? record.split(",") : new String[]{"", "", "", ""};
        
        // 白班上班时间选择
        addTimeSelector(card, "⏰ 白班上班时间:", "dayStartTime", "08:00");
        
        // 夜班上班时间选择
        addTimeSelector(card, "🌙 夜班上班时间:", "nightStartTime", "22:00");
        
        EditText normalInput = addStyledInputEmpty(card, "⏰ 白班工时(小时):", parts[0]);
        EditText nightInput = addStyledInputEmpty(card, "🌙 夜班工时(小时):", parts.length > 1 ? parts[1] : "");
        EditText overtimeInput = addStyledInputEmpty(card, "⚡ 加班工时(小时):", parts.length > 2 ? parts[2] : "");
        EditText lateInput = addStyledInputEmpty(card, "⏱ 迟到次数:", parts.length > 3 ? parts[3] : "");
        
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(-1, -2);
        btnRowLp.topMargin = dp(20);
        card.addView(btnRow, btnRowLp);
        
        Button cancelBtn = createStyledButton("取消", 0xFF999999, 0x33999999);
        cancelBtn.setOnClickListener(v -> {
            Haptics.perform(v);
            dialog.dismiss();
        });
        btnRow.addView(cancelBtn, new LinearLayout.LayoutParams(0, dp(46), 1f));
        
        View btnSpace = new View(this);
        btnRow.addView(btnSpace, new LinearLayout.LayoutParams(dp(12), dp(1)));
        
        Button saveBtn = createStyledButton("保存", 0xFF35A8C4, 0x3335A8C4);
        saveBtn.setOnClickListener(v -> {
            Haptics.perform(v);
            String normal = normalInput.getText().toString().trim();
            String night = nightInput.getText().toString().trim();
            String overtime = overtimeInput.getText().toString().trim();
            String late = lateInput.getText().toString().trim();
            
            float normalVal = parseFloatStr(normal);
            float nightVal = parseFloatStr(night);
            float overtimeVal = parseFloatStr(overtime);
            int lateVal = parseIntSafe(late);
            
            float totalHours = normalVal + nightVal + overtimeVal;
            if (totalHours > 0 || lateVal > 0) {
                String saveStr = normalVal + "," + nightVal + "," + overtimeVal + "," + lateVal;
                prefs.edit().putString(dateKey, saveStr).apply();
                refreshCalendar();
                dialog.dismiss();
            } else {
                Toast.makeText(this, "请至少填写一项工时或迟到次数", Toast.LENGTH_SHORT).show();
            }
        });
        btnRow.addView(saveBtn, new LinearLayout.LayoutParams(0, dp(46), 1f));
        
        scrollCard.addView(card);
        
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(-1, -2);
        cardLp.leftMargin = dp(24);
        cardLp.rightMargin = dp(24);
        cardLp.gravity = Gravity.CENTER;
        root.addView(scrollCard, cardLp);
        
        dialog.setContentView(root);
        dialog.show();
    }
    
    private EditText addStyledInputEmpty(LinearLayout parent, String label, String value) {
        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(13);
        labelView.setTextColor(0xff17334f);
        labelView.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(-1, -2);
        labelLp.topMargin = dp(8);
        parent.addView(labelView, labelLp);
        
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(value != null && !value.trim().isEmpty() ? value : "");
        input.setHint("请输入");
        input.setTextSize(15);
        input.setTextColor(0xff0f1e36);
        input.setHintTextColor(0xff999999);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(0x4DFFFFFF);
        inputBg.setCornerRadius(dp(12));
        inputBg.setStroke(Math.max(1, dp(1)), 0x6635A8C4);
        input.setBackground(inputBg);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(-1, -2);
        inputLp.topMargin = dp(6);
        parent.addView(input, inputLp);
        
        return input;
    }
    
    private void addTimeSelector(LinearLayout parent, String label, String prefKey, String defaultTime) {
        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(13);
        labelView.setTextColor(0xff17334f);
        labelView.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(-1, -2);
        labelLp.topMargin = dp(8);
        parent.addView(labelView, labelLp);
        
        String savedTime = prefs.getString(prefKey, defaultTime);
        TextView timeDisplay = new TextView(this);
        timeDisplay.setText(savedTime);
        timeDisplay.setTextSize(15);
        timeDisplay.setTextColor(0xff0f1e36);
        timeDisplay.setPadding(dp(14), dp(12), dp(14), dp(12));
        timeDisplay.setGravity(Gravity.CENTER);
        GradientDrawable timeBg = new GradientDrawable();
        timeBg.setColor(0x4DFFFFFF);
        timeBg.setCornerRadius(dp(12));
        timeBg.setStroke(Math.max(1, dp(1)), 0x6635A8C4);
        timeDisplay.setBackground(timeBg);
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(-1, -2);
        timeLp.topMargin = dp(6);
        parent.addView(timeDisplay, timeLp);
        
        timeDisplay.setOnClickListener(v -> {
            Haptics.perform(v);
            String[] timeParts = savedTime.split(":");
            int hour = Integer.parseInt(timeParts[0]);
            int minute = Integer.parseInt(timeParts[1]);
            
            TimePickerDialog picker = new TimePickerDialog(this, (view, h, m) -> {
                String newTime = String.format("%02d:%02d", h, m);
                timeDisplay.setText(newTime);
                prefs.edit().putString(prefKey, newTime).apply();
            }, hour, minute, true);
            picker.show();
        });
    }
    
    private EditText addStyledInput(LinearLayout parent, String label, float value) {
        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(13);
        labelView.setTextColor(0xff17334f);
        labelView.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(-1, -2);
        labelLp.topMargin = dp(8);
        parent.addView(labelView, labelLp);
        
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText("");
        input.setHint(value == 0 ? "0" : String.valueOf(value));
        input.setTextSize(15);
        input.setTextColor(0xff0f1e36);
        input.setHintTextColor(0xff999999);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(0x4DFFFFFF);
        inputBg.setCornerRadius(dp(12));
        inputBg.setStroke(Math.max(1, dp(1)), 0x6635A8C4);
        input.setBackground(inputBg);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(-1, -2);
        inputLp.topMargin = dp(6);
        parent.addView(input, inputLp);
        
        return input;
    }
    
    private Button createStyledButton(String text, int textColor, int bgColor) {
        Button btn = new Button(this, null, 0);
        btn.setText(text);
        btn.setTextSize(15);
        btn.setTextColor(textColor);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setAllCaps(false);
        btn.setGravity(Gravity.CENTER);
        btn.setMinWidth(0);
        btn.setMinHeight(0);
        btn.setPadding(0, 0, 0, 0);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(bgColor);
        btnBg.setCornerRadius(dp(12));
        btnBg.setStroke(Math.max(1, dp(1)), textColor);
        btn.setBackground(btnBg);
        btn.setStateListAnimator(null);
        return btn;
    }
    
    private int getWorkDaysInMonth() {
        Calendar cal = (Calendar) calendar.clone();
        cal.set(Calendar.DAY_OF_MONTH, 1);
        int month = cal.get(Calendar.MONTH);
        int workDays = 0;
        
        while (cal.get(Calendar.MONTH) == month) {
            int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK);
            String dateKey = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(cal.getTime());
            if (dayOfWeek != Calendar.SATURDAY && dayOfWeek != Calendar.SUNDAY && !HOLIDAYS_2026.contains(dateKey)) {
                workDays++;
            }
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        
        return workDays;
    }
    
    private void calculateMonthlySalary() {
        Calendar cal = (Calendar) calendar.clone();
        cal.set(Calendar.DAY_OF_MONTH, 1);
        int month = cal.get(Calendar.MONTH);
        
        float totalNormalHours = 0;
        float totalNightHours = 0;
        float totalOvertimeHours = 0;
        int workDays = 0;
        int lateTimes = 0;
        int absentDays = 0;
        
        int expectedWorkDays = getWorkDaysInMonth();
        
        while (cal.get(Calendar.MONTH) == month) {
            String dateKey = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(cal.getTime());
            String record = prefs.getString(dateKey, null);
            
            if (record != null) {
                String[] parts = record.split(",");
                float normal = Float.parseFloat(parts[0]);
                float night = parts.length > 1 ? Float.parseFloat(parts[1]) : 0;
                float overtime = parts.length > 2 ? Float.parseFloat(parts[2]) : 0;
                int late = parts.length > 3 ? parseIntSafe(parts[3]) : 0;
                
                totalNormalHours += normal;
                totalNightHours += night;
                totalOvertimeHours += overtime;
                lateTimes += late;
                
                int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK);
                boolean isHoliday = HOLIDAYS_2026.contains(dateKey);
                
                if (dayOfWeek != Calendar.SATURDAY && dayOfWeek != Calendar.SUNDAY && !isHoliday) {
                    workDays++;
                }
            } else {
                int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK);
                if (dayOfWeek != Calendar.SATURDAY && dayOfWeek != Calendar.SUNDAY && !HOLIDAYS_2026.contains(dateKey)) {
                    absentDays++;
                }
            }
            
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        
        detailPanel.removeAllViews();
        float grossSalary = 0;
        float totalDeduction = 0;
        
        addSectionTitle("收入项");
        
        if (salaryMode == 0) {
            grossSalary = baseSalary;
            if (baseSalary > 0) addDetailRow("基本工资", baseSalary);
        } else if (salaryMode == 1) {
            if (baseSalary > 0) {
                float basePay = workDays * baseSalary;
                grossSalary = basePay;
                addDetailRow("底薪(" + workDays + "天×" + baseSalary + ")", basePay);
                
                float hourlyRate = baseSalary / dailyHours;
                
                if (totalOvertimeHours > 0) {
                    float overtimePay = totalOvertimeHours * hourlyRate * 1.5f;
                    grossSalary += overtimePay;
                    addDetailRow("加班工资(" + String.format("%.1f", totalOvertimeHours) + "h×1.5)", overtimePay);
                }
            }
        } else if (salaryMode == 2) {
            if (baseSalary > 0) {
                float totalHours = totalNormalHours + totalOvertimeHours;
                float basePay = totalHours * baseSalary;
                grossSalary = basePay;
                addDetailRow("时薪×工时(" + String.format("%.1f", totalHours) + "h)", basePay);
            }
        } else if (salaryMode == 3) {
            // 计件工资
            if (pieceRate > 0) {
                String key = new SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(calendar.getTime());
                int pieces = prefs.getInt(key + "_pieces", 0);
                if (pieces > 0) {
                    float piecePay = pieces * pieceRate;
                    grossSalary = piecePay;
                    addDetailRow("计件收入(" + pieces + "件×" + pieceRate + ")", piecePay);
                }
            }
        } else {
            // 旧的计件逻辑作为后备
            if (pieceRate > 0) {
                String key = new SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(calendar.getTime());
                int pieces = prefs.getInt(key + "_pieces", 0);
                if (pieces > 0) {
                    float piecePay = pieces * pieceRate;
                    grossSalary = piecePay;
                    addDetailRow("计件收入(" + pieces + "件×" + pieceRate + ")", piecePay);
                }
            }
        }
        
        if (performance > 0) { grossSalary += performance; addDetailRow("绩效奖金", performance); }
        if (attendance > 0 && absentDays == 0 && lateTimes == 0) { grossSalary += attendance; addDetailRow("全勤奖", attendance); }
        if (positionAllowance > 0) { grossSalary += positionAllowance; addDetailRow("岗位补贴", positionAllowance); }
        if (seniorityPay > 0) { grossSalary += seniorityPay; addDetailRow("工龄工资", seniorityPay); }
        if (mealAllowance > 0) { grossSalary += mealAllowance; addDetailRow("餐补", mealAllowance); }
        if (transportAllowance > 0) { grossSalary += transportAllowance; addDetailRow("交通补贴", transportAllowance); }
        if (totalNightHours > 0 && nightPayRate > 0) {
            float nightPay = totalNightHours * nightPayRate;
            grossSalary += nightPay;
            addDetailRow("夜班补贴(" + String.format("%.1f", totalNightHours) + "h)", nightPay);
        }
        if (heatAllowance > 0) { grossSalary += heatAllowance; addDetailRow("高温补贴", heatAllowance); }
        if (otherIncome > 0) { grossSalary += otherIncome; addDetailRow("其他收入", otherIncome); }
        
        addDetailRow("应发工资", grossSalary, true);
        
        addSectionTitle("扣款项");
        
        if (socialSecurity > 0) { totalDeduction += socialSecurity; addDetailRow("社保个人部分", -socialSecurity); }
        if (housingFund > 0) { totalDeduction += housingFund; addDetailRow("公积金个人部分", -housingFund); }
        if (absentDays > 0 && absentDeduction > 0) {
            float absent = absentDays * absentDeduction;
            totalDeduction += absent;
            addDetailRow("缺勤扣款(" + absentDays + "天)", -absent);
        }
        if (lateTimes > 0 && lateDeduction > 0) {
            float late = lateTimes * lateDeduction;
            totalDeduction += late;
            addDetailRow("迟到扣款(" + lateTimes + "次)", -late);
        }
        
        float taxableIncome = grossSalary - socialSecurity - housingFund - 5000;
        float tax = 0;
        if (taxableIncome > 0) {
            if (taxableIncome <= 3000) {
                tax = taxableIncome * 0.03f;
            } else if (taxableIncome <= 12000) {
                tax = 3000 * 0.03f + (taxableIncome - 3000) * 0.1f;
            } else {
                tax = 3000 * 0.03f + 9000 * 0.1f + (taxableIncome - 12000) * 0.2f;
            }
            totalDeduction += tax;
            addDetailRow("个人所得税", -tax);
        }
        
        if (otherDeduction > 0) { totalDeduction += otherDeduction; addDetailRow("其他扣款", -otherDeduction); }
        
        if (totalDeduction > 0) {
            addDetailRow("扣款合计", -totalDeduction, true);
        }
        
        float netSalary = grossSalary - totalDeduction;
        
        grossSalaryView.setText(t("应发工资：", "Gross: ") + String.format("¥%.2f", grossSalary));
        netSalaryView.setText(String.format("¥%.2f", netSalary));
        
        TextView hint = new TextView(this);
        hint.setText("本月工作日: " + expectedWorkDays + "天 | 已出勤: " + workDays + "天");
        hint.setTextSize(11);
        hint.setTextColor(0xff5a6b82);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(12), 0, 0);
        detailPanel.addView(hint);
    }
    
    private void addSectionTitle(String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(13);
        tv.setTextColor(0xff0f5c4f);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, dp(12), 0, dp(6));
        detailPanel.addView(tv);
    }
    
    private void addDetailRow(String label, float value) {
        addDetailRow(label, value, false);
    }
    
    private void addDetailRow(String label, float value, boolean isTotal) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(5), 0, dp(5));
        
        TextView labelText = new TextView(this);
        labelText.setText(label);
        labelText.setTextSize(isTotal ? 14 : 12);
        labelText.setTextColor(isTotal ? 0xff0f1e36 : 0xff5a6b82);
        if (isTotal) labelText.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2);
        labelParams.weight = 1;
        row.addView(labelText, labelParams);
        
        TextView valueText = new TextView(this);
        valueText.setText(String.format("¥%.2f", value));
        valueText.setTextSize(isTotal ? 14 : 12);
        valueText.setTextColor(isTotal ? 0xff0f1e36 : (value < 0 ? 0xFFCC6600 : 0xff17334f));
        if (isTotal) valueText.setTypeface(null, Typeface.BOLD);
        row.addView(valueText);
        
        detailPanel.addView(row);
        
        if (isTotal) {
            View divider = new View(this);
            divider.setBackgroundColor(0x3317334f);
            LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(-1, dp(1));
            divParams.topMargin = dp(6);
            divParams.bottomMargin = dp(6);
            detailPanel.addView(divider, divParams);
        }
    }
    
    private void loadConfig() {
        salaryMode = prefs.getInt("mode", 1);
        baseSalary = prefs.getFloat("base", 0);
        performance = prefs.getFloat("perf", 0);
        attendance = prefs.getFloat("attend", 0);
        positionAllowance = prefs.getFloat("position", 0);
        seniorityPay = prefs.getFloat("seniority", 0);
        mealAllowance = prefs.getFloat("meal", 0);
        transportAllowance = prefs.getFloat("transport", 0);
        nightPayRate = prefs.getFloat("night", 0);
        heatAllowance = prefs.getFloat("heat", 0);
        otherIncome = prefs.getFloat("otherIncome", 0);
        socialSecurity = prefs.getFloat("social", 0);
        housingFund = prefs.getFloat("fund", 0);
        absentDeduction = prefs.getFloat("absent", 0);
        lateDeduction = prefs.getFloat("late", 0);
        otherDeduction = prefs.getFloat("otherDeduct", 0);
        dailyHours = prefs.getFloat("dailyhours", 8);
        pieceRate = prefs.getFloat("pieceRate", 0);
    }
    
    private float parseFloatStr(String str) {
        try {
            if (str == null || str.trim().isEmpty()) return 0;
            return Float.parseFloat(str.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
