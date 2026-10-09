package com.probiotics.xiaoni;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/** 当前月份工时日历缩略组件。 */
public class SalaryWidget extends AppWidgetProvider {
    private static final String ACTION_DAY = "com.probiotics.xiaoni.RECORD_DAY";
    private static final String ACTION_NIGHT = "com.probiotics.xiaoni.RECORD_NIGHT";
    private static final String ACTION_OVERTIME = "com.probiotics.xiaoni.RECORD_OVERTIME";

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) updateWidget(context, manager, id);
    }

    private void updateWidget(Context context, AppWidgetManager manager, int id) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_salary);
        SharedPreferences prefs = context.getSharedPreferences("salary", Context.MODE_PRIVATE);
        Calendar month = Calendar.getInstance();
        views.setTextViewText(R.id.widget_title, "工时记录表");
        views.setTextViewText(R.id.widget_month,
                new SimpleDateFormat("yyyy年MM月", Locale.CHINA).format(month.getTime()));
        month.set(Calendar.DAY_OF_MONTH, 1);
        int first = month.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY;
        int days = month.getActualMaximum(Calendar.DAY_OF_MONTH);
        for (int i = 0; i < 42; i++) {
            int viewId = getDayViewId(i);
            int day = i - first + 1;
            if (day < 1 || day > days) {
                views.setTextViewText(viewId, "");
                continue;
            }
            Calendar date = (Calendar) month.clone();
            date.set(Calendar.DAY_OF_MONTH, day);
            String key = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(date.getTime());
            String holidayMark = OfficialHolidaySchedule.mark(date);
            String record = prefs.getString(key, null);
            String prefix = String.valueOf(day) + (holidayMark.isEmpty() ? "" : " " + holidayMark);
            if (record == null) {
                views.setTextViewText(viewId, prefix);
            } else {
                String[] parts = record.split(",");
                float normal = parse(parts, 0), night = parse(parts, 1), overtime = parse(parts, 2);
                String hours = formatHours(normal + night + overtime);
                views.setTextViewText(viewId, prefix + "\n" + hours);
            }
            views.setOnClickPendingIntent(viewId, getDatePendingIntent(context, key, id, i));
        }
        Intent openIntent = new Intent(context, SalaryActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPending = PendingIntent.getActivity(context, id * 1000,
                openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_month, openPending);
        try { manager.updateAppWidget(id, views); } catch (RuntimeException ignored) { }
    }

    private int getDayViewId(int index) {
        switch (index) {
            case 0: return R.id.widget_day_00; case 1: return R.id.widget_day_01; case 2: return R.id.widget_day_02;
            case 3: return R.id.widget_day_03; case 4: return R.id.widget_day_04; case 5: return R.id.widget_day_05;
            case 6: return R.id.widget_day_06; case 7: return R.id.widget_day_07; case 8: return R.id.widget_day_08;
            case 9: return R.id.widget_day_09; case 10: return R.id.widget_day_10; case 11: return R.id.widget_day_11;
            case 12: return R.id.widget_day_12; case 13: return R.id.widget_day_13; case 14: return R.id.widget_day_14;
            case 15: return R.id.widget_day_15; case 16: return R.id.widget_day_16; case 17: return R.id.widget_day_17;
            case 18: return R.id.widget_day_18; case 19: return R.id.widget_day_19; case 20: return R.id.widget_day_20;
            case 21: return R.id.widget_day_21; case 22: return R.id.widget_day_22; case 23: return R.id.widget_day_23;
            case 24: return R.id.widget_day_24; case 25: return R.id.widget_day_25; case 26: return R.id.widget_day_26;
            case 27: return R.id.widget_day_27; case 28: return R.id.widget_day_28; case 29: return R.id.widget_day_29;
            case 30: return R.id.widget_day_30; case 31: return R.id.widget_day_31; case 32: return R.id.widget_day_32;
            case 33: return R.id.widget_day_33; case 34: return R.id.widget_day_34; case 35: return R.id.widget_day_35;
            case 36: return R.id.widget_day_36; case 37: return R.id.widget_day_37; case 38: return R.id.widget_day_38;
            case 39: return R.id.widget_day_39; case 40: return R.id.widget_day_40; default: return R.id.widget_day_41;
        }
    }
    private float parse(String[] parts, int index) {
        try { return parts.length > index ? Float.parseFloat(parts[index]) : 0f; } catch (Exception ignored) { return 0f; }
    }
    private String formatHours(float value) { return value == 0 ? "" : String.format(Locale.CHINA, "%.1fh", value); }
    private PendingIntent getDatePendingIntent(Context context, String date, int widgetId, int index) {
        Intent intent = new Intent(context, SalaryActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("widget_date", date);
        int requestCode = (widgetId * 1000) + index + 1;
        return PendingIntent.getActivity(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        // 兼容旧广播：刷新全部组件，记录由工资页面负责保存。
        String action = intent.getAction();
        if (ACTION_DAY.equals(action) || ACTION_NIGHT.equals(action) || ACTION_OVERTIME.equals(action)) {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] ids = manager.getAppWidgetIds(new android.content.ComponentName(context, SalaryWidget.class));
            onUpdate(context, manager, ids);
        }
    }
}
