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
import android.graphics.drawable.Drawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
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
    private GestureDetector calendarGesture;
    private boolean calendarSwiped;
    private final Map<String, List<String>> calendarTitlesCache = new HashMap<>();
    private TextView grossSalaryView;
    private TextView netSalaryView;
    private LinearLayout detailPanel;
    private GridLayout hoursSummaryView;
    
    private int salaryMode = 0;
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
    private float pieceQuota = 0;
    private float pieceBonus = 0;
    private float weekdayOvertimeMultiplier = 1.5f;
    private float weekendOvertimeMultiplier = 2f;
    private float holidayOvertimeMultiplier = 3f;
    private int workScheduleDays = 5;
    
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
        buildMonthSelector(content);
        buildCalendar(content);
        buildSalaryCard(content);
        buildDetailCard(content);
        
        checkCalendarPermission();
        refreshCalendar();
        String widgetDate = getIntent().getStringExtra("widget_date");
        if (widgetDate != null) {
            root.post(() -> {
                Calendar selected = parseDate(widgetDate);
                if (selected != null) showDayEditor(widgetDate,
                        selected.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || selected.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY,
                        isLegalHoliday(selected));
            });
        }
    }
    
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String widgetDate = intent.getStringExtra("widget_date");
        if (widgetDate != null) {
            getWindow().getDecorView().post(() -> {
                Calendar selected = parseDate(widgetDate);
                if (selected != null) showDayEditor(widgetDate,
                        selected.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || selected.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY,
                        isLegalHoliday(selected));
            });
        }
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
            calendarTitlesCache.clear();
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
            overridePendingTransition(R.anim.fade_in, R.anim.slide_out_right);
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
            switchMonth(-1);
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
            switchMonth(1);
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
        calendarGesture = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.max(Math.abs(dx), Math.abs(dy)) < dp(70)) return false;
                // 只允许左右切换月份；上下滑动不切换，也不触发日期点击。
                if (Math.abs(dy) >= Math.abs(dx)) {
                    calendarSwiped = true;
                    return true;
                }
                int direction = dx < 0 ? 1 : -1;
                calendarSwiped = true;
                switchMonth(direction);
                Haptics.perform(calendarGrid);
                return true;
            }
        });
        calendarGrid.setClickable(true);
        calendarGrid.setOnTouchListener((v, event) -> calendarGesture.onTouchEvent(event));
        calCard.addView(calendarGrid);
        
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        parent.addView(calCard, lp);
        LinearLayout summaryCard = new LinearLayout(this);
        summaryCard.setOrientation(LinearLayout.VERTICAL);
        summaryCard.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable summaryBg = new GradientDrawable();
        summaryBg.setColor(0x42FFFFFF);
        summaryBg.setCornerRadius(dp(16));
        summaryBg.setStroke(Math.max(1, dp(1)), 0x55FFFFFF);
        summaryCard.setBackground(summaryBg);
        TextView summaryTitle = new TextView(this);
        summaryTitle.setText("本月工时统计");
        summaryTitle.setTextSize(13);
        summaryTitle.setTextColor(0xff17334f);
        summaryTitle.setTypeface(null, Typeface.BOLD);
        summaryCard.addView(summaryTitle, new LinearLayout.LayoutParams(-1, -2));
        hoursSummaryView = new GridLayout(this);
        hoursSummaryView.setColumnCount(2);
        hoursSummaryView.setRowCount(4);
        hoursSummaryView.setUseDefaultMargins(false);
        LinearLayout.LayoutParams tableLp = new LinearLayout.LayoutParams(-1, -2);
        tableLp.topMargin = dp(7);
        summaryCard.addView(hoursSummaryView, tableLp);
        LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(-1, -2);
        summaryLp.bottomMargin = dp(10);
        parent.addView(summaryCard, summaryLp);
    }
    
    private void addSummaryCell(GridLayout grid, String label, String value, int valueColor) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(dp(9), dp(7), dp(9), dp(7));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x35FFFFFF);
        bg.setCornerRadius(dp(10));
        bg.setStroke(Math.max(1, dp(1)), 0x35FFFFFF);
        cell.setBackground(bg);
        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(10);
        labelView.setTextColor(0xff526A82);
        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextSize(15);
        valueView.setTypeface(null, Typeface.BOLD);
        valueView.setTextColor(valueColor);
        cell.addView(labelView);
        cell.addView(valueView);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        grid.addView(cell, lp);
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
    
    private void switchMonth(int direction) {
        calendar.add(Calendar.MONTH, direction);
        if (calendarGrid == null) { refreshCalendar(); return; }
        calendarGrid.post(() -> {
            try {
                refreshCalendar();
                calendarGrid.setAlpha(0.25f);
                calendarGrid.setTranslationY(direction > 0 ? dp(26) : -dp(26));
                calendarGrid.animate().alpha(1f).translationY(0f)
                        .setDuration(260L)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
            } catch (RuntimeException ignored) { }
        });
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
            String calendarEvent = getSystemCalendarEvent(cal);
            String holidayMark = getHolidayMark(cal);
            boolean isHoliday = "休".equals(holidayMark);
            String lunarInfo = getLunarAndFestival(cal);
            FrameLayout dayCell = buildDayCell(day, dateKey, isWeekend, isHoliday, lunarInfo, calendarEvent, holidayMark);
            calendarGrid.addView(dayCell, dayParams());
            
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        
        calculateMonthlySalary();
    }
    
    private String getLunarAndFestival(Calendar cal) {
        // 先显示本地农历节日，再显示农历日期；系统日历事件作为额外提示。
        String festival = LunarCalendarHelper.festival(cal);
        String lunarDay = LunarCalendarHelper.day(cal);
        String systemEvent = getSystemCalendarEvent(cal);
        String result = lunarDay + (festival.isEmpty() ? "" : " · " + festival);
        if (systemEvent != null && !systemEvent.trim().isEmpty()) result += " · " + systemEvent;
        return result;
    }
    
    private List<String> querySystemCalendarTitles(Calendar cal) {
        String cacheKey = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(cal.getTime());
        List<String> cached = calendarTitlesCache.get(cacheKey);
        if (cached != null) return cached;
        ArrayList<String> titles = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            calendarTitlesCache.put(cacheKey, titles);
            return titles;
        }
        Calendar dayStart = (Calendar) cal.clone();
        dayStart.set(Calendar.HOUR_OF_DAY, 0); dayStart.set(Calendar.MINUTE, 0);
        dayStart.set(Calendar.SECOND, 0); dayStart.set(Calendar.MILLISECOND, 0);
        long localStart = dayStart.getTimeInMillis();
        long localEnd = localStart + 24L * 60L * 60L * 1000L;
        SimpleDateFormat localDate = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
        localDate.setTimeZone(dayStart.getTimeZone());
        SimpleDateFormat utcDate = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        utcDate.setTimeZone(TimeZone.getTimeZone("UTC"));
        String targetLocalDate = localDate.format(dayStart.getTime());
        Cursor cursor = null;
        try {
            // 使用 Instances 最小兼容列，避免部分系统 Provider 不支持 DESCRIPTION/CALENDAR_DISPLAY_NAME 导致整次查询失败。
            Uri.Builder range = CalendarContract.Instances.CONTENT_URI.buildUpon();
            android.content.ContentUris.appendId(range, localStart - 2L * 24L * 60L * 60L * 1000L);
            android.content.ContentUris.appendId(range, localEnd + 2L * 24L * 60L * 60L * 1000L);
            String[] projection = {
                    CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.CALENDAR_ID
            };
            cursor = getContentResolver().query(range.build(), projection, null, null,
                    CalendarContract.Instances.BEGIN + " ASC");
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    long begin = cursor.isNull(2) ? Long.MIN_VALUE : cursor.getLong(2);
                    long end = cursor.isNull(3) ? begin + 24L * 60L * 60L * 1000L : cursor.getLong(3);
                    boolean allDay = !cursor.isNull(4) && cursor.getInt(4) != 0;
                    boolean overlaps;
                    if (allDay) {
                        String beginUtc = utcDate.format(new Date(begin));
                        String endUtc = utcDate.format(new Date(Math.max(begin, end - 1L)));
                        String beginLocal = localDate.format(new Date(begin));
                        String endLocal = localDate.format(new Date(Math.max(begin, end - 1L)));
                        overlaps = (targetLocalDate.compareTo(beginUtc) >= 0 && targetLocalDate.compareTo(endUtc) <= 0)
                                || (targetLocalDate.compareTo(beginLocal) >= 0 && targetLocalDate.compareTo(endLocal) <= 0);
                    } else {
                        overlaps = begin < localEnd && end > localStart;
                    }
                    if (!overlaps) continue;
                    StringBuilder text = new StringBuilder();
                    String title = cursor.getString(1);
                    if (title != null && !title.trim().isEmpty()) text.append(title.trim());
                    long eventId = cursor.isNull(0) ? -1L : cursor.getLong(0);
                    if (eventId >= 0) {
                        Cursor event = null;
                        try {
                            event = getContentResolver().query(CalendarContract.Events.CONTENT_URI,
                                    new String[]{CalendarContract.Events.DESCRIPTION},
                                    CalendarContract.Events._ID + "=?",
                                    new String[]{String.valueOf(eventId)}, null);
                            if (event != null && event.moveToFirst()) {
                                String description = event.getString(0);
                                if (description != null && !description.trim().isEmpty()) {
                                    if (text.length() > 0) text.append(" ");
                                    text.append(description.trim());
                                }
                            }
                        } finally { if (event != null) event.close(); }
                    }
                    // 日历名称单独读取，兼容系统节假日专用日历把“休/班”放在名称中。
                    long calendarId = cursor.isNull(5) ? -1L : cursor.getLong(5);
                    if (calendarId >= 0) {
                        Cursor calendarCursor = null;
                        try {
                            calendarCursor = getContentResolver().query(CalendarContract.Calendars.CONTENT_URI,
                                    new String[]{CalendarContract.Calendars.CALENDAR_DISPLAY_NAME},
                                    CalendarContract.Calendars._ID + "=?",
                                    new String[]{String.valueOf(calendarId)}, null);
                            if (calendarCursor != null && calendarCursor.moveToFirst()) {
                                String name = calendarCursor.getString(0);
                                if (name != null && !name.trim().isEmpty()) {
                                    if (text.length() > 0) text.append(" ");
                                    text.append(name.trim());
                                }
                            }
                        } finally { if (calendarCursor != null) calendarCursor.close(); }
                    }
                    if (text.length() > 0) titles.add(text.toString());
                }
            }
            if (titles.isEmpty()) {
                Cursor events = null;
                try {
                    String[] ep = {CalendarContract.Events.TITLE, CalendarContract.Events.DESCRIPTION,
                            CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND,
                            CalendarContract.Events.ALL_DAY};
                    String sel = CalendarContract.Events.DTSTART + " < ? AND (" +
                            CalendarContract.Events.DTEND + " IS NULL OR " +
                            CalendarContract.Events.DTEND + " > ?)";
                    events = getContentResolver().query(CalendarContract.Events.CONTENT_URI, ep, sel,
                            new String[]{String.valueOf(localEnd), String.valueOf(localStart)},
                            CalendarContract.Events.DTSTART + " ASC");
                    if (events != null) while (events.moveToNext()) {
                        long begin = events.isNull(2) ? Long.MIN_VALUE : events.getLong(2);
                        long end = events.isNull(3) ? begin + 86400000L : events.getLong(3);
                        boolean allDay = !events.isNull(4) && events.getInt(4) != 0;
                        boolean overlaps = allDay
                                ? targetLocalDate.compareTo(utcDate.format(new Date(begin))) >= 0
                                && targetLocalDate.compareTo(utcDate.format(new Date(Math.max(begin, end - 1L)))) <= 0
                                : begin < localEnd && end > localStart;
                        if (!overlaps) continue;
                        String title = events.getString(0), desc = events.getString(1);
                        String text = ((title == null ? "" : title) + " " + (desc == null ? "" : desc)).trim();
                        if (!text.isEmpty()) titles.add(text);
                    }
                } finally { if (events != null) events.close(); }
            }
        } catch (SecurityException ignored) {
            titles.clear();
        } catch (Exception ignored) {
            titles.clear();
        } finally { if (cursor != null) cursor.close(); }
        calendarTitlesCache.put(cacheKey, titles);
        return titles;
    }

    private String getSystemCalendarEvent(Calendar cal) {
        List<String> titles = querySystemCalendarTitles(cal);
        return titles.isEmpty() ? null : titles.get(0);
    }

    private String getHolidayMark(Calendar cal) {
        String mark = "";
        for (String title : querySystemCalendarTitles(cal)) {
            String t = title.trim();
            // 优先识别调休上班，避免同一天同时存在节日和补班事件时显示错误。
            if (t.matches(".*(^|[^休])班([^班]|$).*" ) || t.contains("上班") || t.contains("补班")
                    || t.contains("调班") || t.contains("调休上班") || t.contains("工作日")
                    || t.contains("上班日")) return "班";
            if (t.matches(".*(^|[^休])休([^休]|$).*" ) || t.contains("放假") || t.contains("休假")
                    || t.contains("休息") || t.contains("休班") || t.contains("法定") || t.contains("节假")
                    || t.contains("假日") || t.contains("Holiday")
                    || t.contains("国庆") || t.contains("春节") || t.contains("清明")
                    || t.contains("劳动节") || t.contains("端午") || t.contains("中秋")
                    || t.contains("元旦")) mark = "休";
        }
        if (!mark.isEmpty()) return mark;
        // 厂商日历的蓝色“休”/橙色“班”常来自私有节假日库，不一定出现在 CalendarContract。
        // 公开 Provider 没有显式休班时，使用国务院办公厅 2026 年安排作为可靠回退。
        return getOfficialHolidayMark(cal);
    }
    private String getOfficialHolidayMark(Calendar cal) {
        return OfficialHolidaySchedule.mark(cal);
    }
    private boolean isLegalHoliday(Calendar cal) {
        return "休".equals(getHolidayMark(cal));
    }
    private boolean isAdjustedWorkday(Calendar cal) {
        return "班".equals(getHolidayMark(cal));
    }

    private GridLayout.LayoutParams dayParams() {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = dp(76);
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        return params;
    }
    
    private FrameLayout buildDayCell(int day, String dateKey, boolean isWeekend, boolean isHoliday, String lunarInfo, String calendarEvent, String holidayMark) {
        FrameLayout cell = new FrameLayout(this);
        GradientDrawable cellBg = new GradientDrawable();
        if (isHoliday) {
            cellBg.setColor(0x18D32F2F);
        } else if (isWeekend) {
            cellBg.setColor(0x1AFFFFFF);
        } else {
            cellBg.setColor(0x12FFFFFF);
        }
        cellBg.setCornerRadius(dp(10));
        cell.setBackground(cellBg);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(dp(3), dp(5), dp(3), dp(5));
        cell.addView(content, new FrameLayout.LayoutParams(-1, -1));

        TextView dayText = new TextView(this);
        dayText.setText(String.valueOf(day));
        dayText.setTextSize(14);
        dayText.setTextColor(isHoliday ? 0xFFB71C1C : (isWeekend ? 0xff40566F : 0xff243B55));
        dayText.setTypeface(null, Typeface.BOLD);
        content.addView(dayText);
        if (!lunarInfo.isEmpty()) {
            TextView lunarText = new TextView(this);
            lunarText.setText(lunarInfo);
            lunarText.setTextSize(8);
            lunarText.setTextColor(0xFF526A82);
            lunarText.setTypeface(null, Typeface.BOLD);
            content.addView(lunarText);
        }
        String record = prefs.getString(dateKey, null);
        if (record != null) {
            String[] parts = record.split(",");
            float normalHours = parseFloatStr(parts[0]);
            float nightHours = parts.length > 1 ? parseFloatStr(parts[1]) : 0;
            float overtimeHours = parts.length > 2 ? parseFloatStr(parts[2]) : 0;
            int late = parts.length > 3 ? parseIntSafe(parts[3]) : 0;
            if (normalHours > 0 || nightHours > 0 || overtimeHours > 0) {
                TextView hourText = new TextView(this);
                String hourStr = "";
                if (normalHours > 0) hourStr += String.format(Locale.CHINA, "%.1f", normalHours);
                if (nightHours > 0) hourStr += (hourStr.isEmpty() ? "" : "+") + String.format(Locale.CHINA, "%.1f夜", nightHours);
                if (overtimeHours > 0) hourStr += (hourStr.isEmpty() ? "" : "+") + String.format(Locale.CHINA, "%.1f加", overtimeHours);
                hourText.setText(hourStr);
                hourText.setTextSize(8);
                hourText.setTextColor(overtimeHours > 0 ? 0xFFC2410C : 0xFF00695C);
                hourText.setTypeface(null, Typeface.BOLD);
                content.addView(hourText);
            }
            if (late > 0) {
                TextView lateText = new TextView(this);
                lateText.setText("迟" + late);
                lateText.setTextSize(7);
                lateText.setTextColor(0xFF7C3AED);
                content.addView(lateText);
            }
        }
        Calendar cellCalendar = Calendar.getInstance();
        try { cellCalendar.setTime(new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(dateKey)); } catch (Exception ignored) { }
        boolean workdayAdjustment = "班".equals(holidayMark);
        if (isHoliday || workdayAdjustment) {
            TextView badge = new TextView(this);
            badge.setText(isHoliday ? "休" : "班");
            badge.setTextSize(10);
            badge.setTypeface(null, Typeface.BOLD);
            badge.setTextColor(isHoliday ? 0xFFE53935 : 0xFFFFA000);
            badge.setGravity(Gravity.CENTER);
            badge.setPadding(0, 0, 0, 0);
            FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(dp(20), dp(18), Gravity.TOP | Gravity.END);
            badgeLp.setMargins(0, dp(2), dp(2), 0);
            cell.addView(badge, badgeLp);
        }
        cell.setOnClickListener(v -> { Haptics.perform(v); showDayEditor(dateKey, isWeekend, isHoliday); });
        cell.setOnTouchListener((v, eventTouch) -> {
            if (eventTouch.getActionMasked() == MotionEvent.ACTION_DOWN) {
                calendarSwiped = false;
                if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
            }
            boolean handled = calendarGesture != null && calendarGesture.onTouchEvent(eventTouch);
            if (eventTouch.getActionMasked() == MotionEvent.ACTION_UP) {
                if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(false);
                if (!calendarSwiped) v.performClick();
            }
            return handled || eventTouch.getActionMasked() != MotionEvent.ACTION_CANCEL;
        });
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
    
    private void refreshSalaryWidgets() {
        try {
            android.appwidget.AppWidgetManager manager = android.appwidget.AppWidgetManager.getInstance(this);
            android.content.ComponentName component = new android.content.ComponentName(this, SalaryWidget.class);
            int[] ids = manager.getAppWidgetIds(component);
            if (ids != null && ids.length > 0) new SalaryWidget().onUpdate(this, manager, ids);
        } catch (RuntimeException ignored) { }
    }

    private void showDayEditor(String dateKey, boolean isWeekend, boolean isHoliday) {
        final android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x80000000);
        root.setOnClickListener(v -> dialog.dismiss());
        
        ScrollView scrollCard = new ScrollView(this);
        scrollCard.setVerticalScrollBarEnabled(true);
        scrollCard.setFillViewport(true);
        
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
        
        // 只保留用户直接填写的白班、夜班和加班工时。
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
                refreshSalaryWidgets();
                dialog.dismiss();
            } else {
                Toast.makeText(this, "请至少填写一项工时或迟到次数", Toast.LENGTH_SHORT).show();
            }
        });
        btnRow.addView(saveBtn, new LinearLayout.LayoutParams(0, dp(46), 1f));
        
        scrollCard.addView(card);
        
        int maxDialogHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.82f);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(-1, -2);
        cardLp.leftMargin = dp(24);
        cardLp.rightMargin = dp(24);
        cardLp.topMargin = dp(24);
        cardLp.bottomMargin = dp(24);
        cardLp.gravity = Gravity.CENTER;
        root.addView(scrollCard, cardLp);
        
        dialog.setContentView(root);
        dialog.show();
        scrollCard.post(() -> {
            if (scrollCard.getHeight() > maxDialogHeight) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) scrollCard.getLayoutParams();
                lp.height = maxDialogHeight;
                scrollCard.setLayoutParams(lp);
            }
        });
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
    
    private TextView addTimeSelector(LinearLayout parent, String label, String prefKey, String defaultTime) {
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
            String[] timeParts = timeDisplay.getText().toString().split(":");
            int hour = 0;
            int minute = 0;
            try {
                hour = Integer.parseInt(timeParts[0]);
                minute = Integer.parseInt(timeParts[1]);
            } catch (Exception ignored) { }
            
            TimePickerDialog picker = new TimePickerDialog(this, (view, h, m) -> {
                String newTime = String.format("%02d:%02d", h, m);
                timeDisplay.setText(newTime);
                prefs.edit().putString(prefKey, newTime).apply();
            }, hour, minute, true);
            picker.show();
        });
        return timeDisplay;
    }

    private float durationHours(String start, String end, boolean crossesMidnight) {
        try {
            String[] a = start.split(":");
            String[] b = end.split(":");
            int from = Integer.parseInt(a[0]) * 60 + Integer.parseInt(a[1]);
            int to = Integer.parseInt(b[0]) * 60 + Integer.parseInt(b[1]);
            if (crossesMidnight || to <= from) to += 24 * 60;
            return Math.max(0f, (to - from) / 60f);
        } catch (Exception ignored) {
            return 0f;
        }
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
    
    private boolean isScheduledWorkday(Calendar cal) {
        String mark = getHolidayMark(cal);
        if ("休".equals(mark)) return false;
        if ("班".equals(mark)) return true;
        int day = cal.get(Calendar.DAY_OF_WEEK);
        if (workScheduleDays == 6) return day != Calendar.SUNDAY;
        return day != Calendar.SATURDAY && day != Calendar.SUNDAY;
    }
    private int getWorkDaysInMonth() {
        Calendar cal = (Calendar) calendar.clone();
        cal.set(Calendar.DAY_OF_MONTH, 1);
        int month = cal.get(Calendar.MONTH);
        int workDays = 0;
        while (cal.get(Calendar.MONTH) == month) {
            if (isScheduledWorkday(cal)) workDays++;
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
        float recordedTotalHours = 0;
        float totalOvertimeHours = 0;
        float baseHoursUsed = 0;
        int scheduledWorkDays = Math.max(1, getWorkDaysInMonth());
        float standardHours = scheduledWorkDays * Math.max(1f, dailyHours);
        float weekdayOvertimeHours = 0;
        float weekendOvertimeHours = 0;
        float holidayOvertimeHours = 0;
        float weekdayOvertimePay = 0;
        float weekendOvertimePay = 0;
        float holidayOvertimePay = 0;
        float totalOvertimePay = 0;
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
                recordedTotalHours += normal + night + overtime;
                
                totalNormalHours += normal;
                totalNightHours += night;
                float regularHours = normal + night;
                boolean scheduledDay = isScheduledWorkday(cal);
                float basePart = scheduledDay
                        ? Math.min(regularHours, Math.max(0f, standardHours - baseHoursUsed))
                        : 0f;
                float excessRegular = Math.max(0f, regularHours - basePart);
                baseHoursUsed += basePart;
                float countedOvertime = overtime + excessRegular;
                totalOvertimeHours += countedOvertime;
                boolean holidayDay = isLegalHoliday(cal);
                boolean weekendDay = cal.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY
                        || cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY;
                if (holidayDay) holidayOvertimeHours += countedOvertime;
                else if (weekendDay) weekendOvertimeHours += countedOvertime;
                else weekdayOvertimeHours += countedOvertime;
                if (salaryMode == 1) {
                    float overtimeHourly = baseSalary / Math.max(1f, dailyHours) / scheduledWorkDays;
                    weekdayOvertimePay = weekdayOvertimeHours * overtimeHourly * weekdayOvertimeMultiplier;
                    weekendOvertimePay = weekendOvertimeHours * overtimeHourly * weekendOvertimeMultiplier;
                    holidayOvertimePay = holidayOvertimeHours * overtimeHourly * holidayOvertimeMultiplier;
                    totalOvertimePay = weekdayOvertimePay + weekendOvertimePay + holidayOvertimePay;
                } else {
                    totalOvertimePay = 0f;
                }
                lateTimes += late;
                
                int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK);
                boolean isHoliday = isLegalHoliday(cal);
                if (isScheduledWorkday(cal)) {
                    workDays++;
                }
            } else {
                int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK);
                if (isScheduledWorkday(cal)) {
                    absentDays++;
                }
            }
            
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        
        if (hoursSummaryView != null) {
            hoursSummaryView.removeAllViews();
            addSummaryCell(hoursSummaryView, "总工时", String.format(Locale.CHINA, "%.1fh", recordedTotalHours), 0xff00695C);
            addSummaryCell(hoursSummaryView, "已计薪", String.format(Locale.CHINA, "%.1fh", baseHoursUsed), 0xff1565C0);
            addSummaryCell(hoursSummaryView, "满额工时", String.format(Locale.CHINA, "%.1fh", standardHours), 0xff455A64);
            addSummaryCell(hoursSummaryView, "应出勤", scheduledWorkDays + "天", 0xff455A64);
            addSummaryCell(hoursSummaryView, "平日加班", String.format(Locale.CHINA, "%.1fh", weekdayOvertimeHours), 0xffC2410C);
            addSummaryCell(hoursSummaryView, "周六日加班", String.format(Locale.CHINA, "%.1fh", weekendOvertimeHours), 0xffD97706);
            addSummaryCell(hoursSummaryView, "法定节假日加班", String.format(Locale.CHINA, "%.1fh", holidayOvertimeHours), 0xffB91C1C);
            addSummaryCell(hoursSummaryView, "加班合计", String.format(Locale.CHINA, "%.1fh", totalOvertimeHours), 0xff7C3AED);
        }
        detailPanel.removeAllViews();
        float grossSalary = 0;
        float totalDeduction = 0;
        
        addSectionTitle("收入项");
        
        if (salaryMode == 0) {
            if (baseSalary > 0) {
                grossSalary = baseSalary;
                addDetailRow("固定月薪", baseSalary);
            }
        } else if (salaryMode == 1) {
            if (baseSalary > 0) {
                float normalizedBase = baseSalary / Math.max(1f, dailyHours)
                        / scheduledWorkDays * baseHoursUsed;
                grossSalary = normalizedBase + totalOvertimePay;
                addDetailRow("底薪(" + baseSalary + "÷" + dailyHours + "÷" + scheduledWorkDays
                        + "×已计薪工时" + String.format(Locale.CHINA, "%.1f", baseHoursUsed)
                        + "/" + String.format(Locale.CHINA, "%.1f", standardHours) + ")", normalizedBase);
                if (weekdayOvertimeHours > 0) {
                    addDetailRow("平日加班(" + baseSalary + "÷" + dailyHours + "÷" + scheduledWorkDays
                            + "×" + weekdayOvertimeMultiplier + "×" + String.format(Locale.CHINA, "%.1f", weekdayOvertimeHours) + "h)", weekdayOvertimePay);
                }
                if (weekendOvertimeHours > 0) {
                    addDetailRow("周六日加班(" + baseSalary + "÷" + dailyHours + "÷" + scheduledWorkDays
                            + "×" + weekendOvertimeMultiplier + "×" + String.format(Locale.CHINA, "%.1f", weekendOvertimeHours) + "h)", weekendOvertimePay);
                }
                if (holidayOvertimeHours > 0) {
                    addDetailRow("法定节假日加班(" + baseSalary + "÷" + dailyHours + "÷" + scheduledWorkDays
                            + "×" + holidayOvertimeMultiplier + "×" + String.format(Locale.CHINA, "%.1f", holidayOvertimeHours) + "h)", holidayOvertimePay);
                }
            }
        } else if (salaryMode == 2) {
            if (baseSalary > 0) {
                float totalHours = totalNormalHours + totalNightHours + totalOvertimeHours;
                float basePay = totalHours * baseSalary;
                grossSalary = basePay;
                addDetailRow("小时工(" + String.format(Locale.CHINA, "%.1f", totalHours) + "h×工价)", basePay);
            }
        } else if (salaryMode == 3) {
            // 计件工资
            if (pieceRate > 0) {
                String key = new SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(calendar.getTime());
                int pieces = prefs.getInt(key + "_pieces", 0);
                if (pieces > 0) {
                    float piecePay = pieces * pieceRate;
                    float excessBonus = pieceQuota > 0 ? Math.max(0, pieces - pieceQuota * workDays) * pieceBonus : 0;
                    grossSalary = piecePay + excessBonus;
                    addDetailRow("计件收入(" + pieces + "件×" + pieceRate + ")", piecePay);
                    if (excessBonus > 0) addDetailRow("超额奖励", excessBonus);
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
        
        String currentMonthKey = new SimpleDateFormat("yyyy-MM", Locale.CHINA).format(calendar.getTime());
        int currentMonth = calendar.get(Calendar.MONTH) + 1;
        float cumulativeIncome = grossSalary;
        float cumulativeSocial = socialSecurity;
        float cumulativeFund = housingFund;
        float previousTaxPaid = 0f;
        Calendar prior = (Calendar) calendar.clone();
        prior.set(Calendar.MONTH, Calendar.JANUARY);
        for (int m = 1; m < currentMonth; m++) {
            String key = new SimpleDateFormat("yyyy-MM", Locale.CHINA).format(prior.getTime());
            cumulativeIncome += prefs.getFloat("income_" + key, 0f);
            cumulativeSocial += prefs.getFloat("social_" + key, 0f);
            cumulativeFund += prefs.getFloat("fund_" + key, 0f);
            previousTaxPaid += prefs.getFloat("tax_" + key, 0f);
            prior.add(Calendar.MONTH, 1);
        }
        float monthlyExtraDeduction = prefs.getFloat("taxAdditionalDeduction", 0f);
        float monthlyOtherTaxDeduction = prefs.getFloat("taxOtherDeduction", 0f);
        float cumulativeTaxable = Math.max(0f, cumulativeIncome - cumulativeSocial - cumulativeFund
                - (monthlyExtraDeduction + monthlyOtherTaxDeduction) * currentMonth - 5000f * currentMonth);
        float cumulativeTax = calculateAnnualComprehensiveTax(cumulativeTaxable);
        float tax = Math.max(0f, cumulativeTax - previousTaxPaid);
        if (tax > 0) {
            totalDeduction += tax;
            addDetailRow("个人所得税（累计预扣）", -tax);
        }
        if (otherDeduction > 0) { totalDeduction += otherDeduction; addDetailRow("其他扣款", -otherDeduction); }
        
        if (totalDeduction > 0) {
            addDetailRow("扣款合计", -totalDeduction, true);
        }
        
        float netSalary = grossSalary - totalDeduction;
        
        grossSalaryView.setText(t("应发工资：", "Gross: ") + String.format("¥%.2f", grossSalary));
        netSalaryView.setText(String.format("¥%.2f", netSalary));
        String monthKey = new SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(calendar.getTime());
        prefs.edit().putFloat("income_" + monthKey, grossSalary)
                .putFloat("tax_" + monthKey, tax)
                .putFloat("social_" + monthKey, socialSecurity)
                .putFloat("fund_" + monthKey, housingFund)
                .putFloat("net_" + monthKey, netSalary)
                .putFloat("overtime_" + monthKey, totalOvertimeHours).apply();
        
        TextView hint = new TextView(this);
        hint.setText("本月工作日: " + expectedWorkDays + "天 | 已出勤: " + workDays + "天");
        hint.setTextSize(11);
        hint.setTextColor(0xff5a6b82);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(12), 0, 0);
        detailPanel.addView(hint);
    }
    
    private Calendar parseDate(String key) {
        try {
            Calendar c = Calendar.getInstance();
            c.setTime(new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(key));
            return c;
        } catch (Exception ignored) { return null; }
    }
    private float calculateAnnualComprehensiveTax(float income) {
        if (income <= 36000f) return income * 0.03f;
        if (income <= 144000f) return income * 0.10f - 2520f;
        if (income <= 300000f) return income * 0.20f - 16920f;
        if (income <= 420000f) return income * 0.25f - 31920f;
        if (income <= 660000f) return income * 0.30f - 52920f;
        if (income <= 960000f) return income * 0.35f - 85920f;
        return income * 0.45f - 181920f;
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
        // 工作制度统一为八小时；旧版本保存的 dailyhours 不再参与计算。
        dailyHours = 8f;
        pieceRate = prefs.getFloat("pieceRate", 0);
        pieceQuota = prefs.getFloat("pieceQuota", 0);
        pieceBonus = prefs.getFloat("pieceBonus", 0);
        weekdayOvertimeMultiplier = prefs.getFloat("weekdayOvertimeMultiplier", 1.5f);
        weekendOvertimeMultiplier = prefs.getFloat("weekendOvertimeMultiplier", 2f);
        holidayOvertimeMultiplier = prefs.getFloat("holidayOvertimeMultiplier", 3f);
        workScheduleDays = prefs.getInt("workSchedule", 5);
        if (workScheduleDays != 6) workScheduleDays = 5;
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
