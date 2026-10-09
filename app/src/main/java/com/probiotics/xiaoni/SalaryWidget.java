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

/**
 * v3.53.0: 桌面小部件 - 快速记录工时
 */
public class SalaryWidget extends AppWidgetProvider {
    private static final String ACTION_DAY = "com.probiotics.xiaoni.RECORD_DAY";
    private static final String ACTION_NIGHT = "com.probiotics.xiaoni.RECORD_NIGHT";
    private static final String ACTION_OVERTIME = "com.probiotics.xiaoni.RECORD_OVERTIME";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) {
            updateWidget(context, manager, id);
        }
    }

    private void updateWidget(Context context, AppWidgetManager manager, int id) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_salary);
        
        SharedPreferences prefs = context.getSharedPreferences("salary", Context.MODE_PRIVATE);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Calendar.getInstance().getTime());
        
        float dayHours = prefs.getFloat("day_" + today, 0);
        float nightHours = prefs.getFloat("night_" + today, 0);
        float overtimeHours = prefs.getFloat("overtime_" + today, 0);
        
        views.setTextViewText(R.id.widget_date, new SimpleDateFormat("MM/dd", Locale.getDefault()).format(Calendar.getInstance().getTime()));
        views.setTextViewText(R.id.widget_day_hours, String.format("%.1f", dayHours));
        views.setTextViewText(R.id.widget_night_hours, String.format("%.1f", nightHours));
        views.setTextViewText(R.id.widget_overtime_hours, String.format("%.1f", overtimeHours));
        
        views.setOnClickPendingIntent(R.id.widget_btn_day, getPendingIntent(context, ACTION_DAY, id));
        views.setOnClickPendingIntent(R.id.widget_btn_night, getPendingIntent(context, ACTION_NIGHT, id));
        views.setOnClickPendingIntent(R.id.widget_btn_overtime, getPendingIntent(context, ACTION_OVERTIME, id));
        
        Intent openIntent = new Intent(context, SalaryActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(context, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_container, openPending);
        
        manager.updateAppWidget(id, views);
    }

    private PendingIntent getPendingIntent(Context context, String action, int widgetId) {
        Intent intent = new Intent(context, SalaryWidget.class);
        intent.setAction(action);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        return PendingIntent.getBroadcast(context, action.hashCode() + widgetId, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (action == null) return;
        
        if (ACTION_DAY.equals(action) || ACTION_NIGHT.equals(action) || ACTION_OVERTIME.equals(action)) {
            SharedPreferences prefs = context.getSharedPreferences("salary", Context.MODE_PRIVATE);
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Calendar.getInstance().getTime());
            
            String key = null;
            if (ACTION_DAY.equals(action)) key = "day_" + today;
            else if (ACTION_NIGHT.equals(action)) key = "night_" + today;
            else if (ACTION_OVERTIME.equals(action)) key = "overtime_" + today;
            
            if (key != null) {
                float current = prefs.getFloat(key, 0);
                prefs.edit().putFloat(key, current + 1).apply();
                
                int widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    updateWidget(context, AppWidgetManager.getInstance(context), widgetId);
                }
            }
        }
    }
}
