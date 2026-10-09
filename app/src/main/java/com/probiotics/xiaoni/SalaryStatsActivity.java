package com.probiotics.xiaoni;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.*;
import com.github.mikephil.charting.formatter.ValueFormatter;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * v3.53.0: 统计图表与数据导出
 */
public class SalaryStatsActivity extends BaseActivity {
    private SharedPreferences prefs;
    private LineChart incomeChart;
    private BarChart overtimeChart;
    private TextView totalIncomeView;
    private TextView avgIncomeView;
    private TextView totalOvertimeView;
    
    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + 0.5f); }
    
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
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(6), dp(16), dp(24));
        page.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        
        addHeader(content);
        addSummaryCards(content);
        addIncomeChart(content);
        addOvertimeChart(content);
        addExportButton(content);
        
        loadStatistics();
    }
    
    private void addHeader(LinearLayout parent) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(12));
        
        ImageView back = new ImageView(this);
        back.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        back.setColorFilter(0xff0f1e36);
        back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(dp(32), dp(32));
        header.addView(back, backParams);
        
        TextView title = new TextView(this);
        title.setText("统计分析");
        title.setTextSize(18);
        title.setTextColor(0xff0f1e36);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(12), 0, 0, 0);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2);
        titleParams.weight = 1;
        header.addView(title, titleParams);
        
        parent.addView(header);
    }
    
    private void addSummaryCards(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.bottomMargin = dp(16);
        parent.addView(row, rowParams);
        
        totalIncomeView = addSummaryCard(row, "总收入", "¥0.00", 0xff4a90e2);
        avgIncomeView = addSummaryCard(row, "月均收入", "¥0.00", 0xff50c878);
        totalOvertimeView = addSummaryCard(row, "总加班", "0h", 0xffff6b6b);
    }
    
    private TextView addSummaryCard(LinearLayout parent, String label, String value, int color) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setGravity(Gravity.CENTER);
        
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xffffffff);
        cardBg.setCornerRadius(dp(12));
        card.setBackground(cardBg);
        
        TextView labelText = new TextView(this);
        labelText.setText(label);
        labelText.setTextSize(11);
        labelText.setTextColor(0xff5a6b82);
        card.addView(labelText);
        
        TextView valueText = new TextView(this);
        valueText.setText(value);
        valueText.setTextSize(16);
        valueText.setTextColor(color);
        valueText.setTypeface(null, Typeface.BOLD);
        valueText.setPadding(0, dp(4), 0, 0);
        card.addView(valueText);
        
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(0, -2);
        cardParams.weight = 1;
        cardParams.rightMargin = dp(8);
        parent.addView(card, cardParams);
        
        return valueText;
    }
    
    private void addIncomeChart(LinearLayout parent) {
        LinearLayout card = createChartCard("月度收入趋势");
        
        incomeChart = new LineChart(this);
        incomeChart.setNoDataText("暂无数据");
        incomeChart.getDescription().setEnabled(false);
        incomeChart.setTouchEnabled(true);
        incomeChart.setDragEnabled(true);
        incomeChart.setScaleEnabled(false);
        incomeChart.getLegend().setEnabled(false);
        
        card.addView(incomeChart, new LinearLayout.LayoutParams(-1, dp(200)));
        
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.bottomMargin = dp(16);
        parent.addView(card, cardParams);
    }
    
    private void addOvertimeChart(LinearLayout parent) {
        LinearLayout card = createChartCard("月度加班时长");
        
        overtimeChart = new BarChart(this);
        overtimeChart.setNoDataText("暂无数据");
        overtimeChart.getDescription().setEnabled(false);
        overtimeChart.setTouchEnabled(true);
        overtimeChart.setDragEnabled(true);
        overtimeChart.setScaleEnabled(false);
        overtimeChart.getLegend().setEnabled(false);
        
        card.addView(overtimeChart, new LinearLayout.LayoutParams(-1, dp(200)));
        
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.bottomMargin = dp(16);
        parent.addView(card, cardParams);
    }
    
    private LinearLayout createChartCard(String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xffffffff);
        cardBg.setCornerRadius(dp(12));
        card.setBackground(cardBg);
        
        TextView titleText = new TextView(this);
        titleText.setText(title);
        titleText.setTextSize(14);
        titleText.setTextColor(0xff0f1e36);
        titleText.setTypeface(null, Typeface.BOLD);
        titleText.setPadding(0, 0, 0, dp(12));
        card.addView(titleText);
        
        return card;
    }
    
    private void addExportButton(LinearLayout parent) {
        Button exportBtn = new Button(this);
        exportBtn.setText("导出数据 (CSV)");
        exportBtn.setTextColor(0xffffffff);
        exportBtn.setTextSize(14);
        exportBtn.setTypeface(null, Typeface.BOLD);
        
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(0xff4a90e2);
        btnBg.setCornerRadius(dp(999));
        exportBtn.setBackground(btnBg);
        
        exportBtn.setOnClickListener(v -> exportData());
        
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(-1, dp(48));
        parent.addView(exportBtn, btnParams);
    }
    
    private void loadStatistics() {
        Calendar cal = Calendar.getInstance();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM", Locale.getDefault());
        
        Map<String, Float> monthlyIncome = new HashMap<>();
        Map<String, Float> monthlyOvertime = new HashMap<>();
        float totalIncome = 0;
        float totalOvertime = 0;
        int monthCount = 0;
        
        for (int i = 0; i < 12; i++) {
            String month = fmt.format(cal.getTime());
            float income = prefs.getFloat("income_" + month, 0);
            float overtime = prefs.getFloat("overtime_" + month, 0);
            
            if (income > 0) {
                monthlyIncome.put(month, income);
                totalIncome += income;
                monthCount++;
            }
            if (overtime > 0) {
                monthlyOvertime.put(month, overtime);
                totalOvertime += overtime;
            }
            
            cal.add(Calendar.MONTH, -1);
        }
        
        totalIncomeView.setText(String.format("¥%.2f", totalIncome));
        avgIncomeView.setText(String.format("¥%.2f", monthCount > 0 ? totalIncome / monthCount : 0));
        totalOvertimeView.setText(String.format("%.1fh", totalOvertime));
        
        updateIncomeChart(monthlyIncome);
        updateOvertimeChart(monthlyOvertime);
    }
    
    private void updateIncomeChart(Map<String, Float> data) {
        if (data.isEmpty()) return;
        
        List<Entry> entries = new ArrayList<>();
        List<String> labels = new ArrayList<>(data.keySet());
        Collections.sort(labels);
        
        for (int i = 0; i < labels.size(); i++) {
            entries.add(new Entry(i, data.get(labels.get(i))));
        }
        
        LineDataSet dataSet = new LineDataSet(entries, "收入");
        dataSet.setColor(0xff4a90e2);
        dataSet.setCircleColor(0xff4a90e2);
        dataSet.setLineWidth(2f);
        dataSet.setCircleRadius(4f);
        dataSet.setDrawValues(false);
        dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        
        LineData lineData = new LineData(dataSet);
        incomeChart.setData(lineData);
        
        XAxis xAxis = incomeChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                int idx = (int) value;
                return idx >= 0 && idx < labels.size() ? labels.get(idx).substring(5) : "";
            }
        });
        
        incomeChart.invalidate();
    }
    
    private void updateOvertimeChart(Map<String, Float> data) {
        if (data.isEmpty()) return;
        
        List<BarEntry> entries = new ArrayList<>();
        List<String> labels = new ArrayList<>(data.keySet());
        Collections.sort(labels);
        
        for (int i = 0; i < labels.size(); i++) {
            entries.add(new BarEntry(i, data.get(labels.get(i))));
        }
        
        BarDataSet dataSet = new BarDataSet(entries, "加班");
        dataSet.setColor(0xffff6b6b);
        dataSet.setDrawValues(false);
        
        BarData barData = new BarData(dataSet);
        overtimeChart.setData(barData);
        
        XAxis xAxis = overtimeChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                int idx = (int) value;
                return idx >= 0 && idx < labels.size() ? labels.get(idx).substring(5) : "";
            }
        });
        
        overtimeChart.invalidate();
    }
    
    private void exportData() {
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "xiaoni");
            if (!dir.exists()) dir.mkdirs();
            
            String filename = "salary_export_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".csv";
            File file = new File(dir, filename);
            
            FileWriter writer = new FileWriter(file);
            writer.write("日期,白班工时,夜班工时,加班工时,迟到次数,实际收入\n");
            
            Calendar cal = Calendar.getInstance();
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            
            for (int i = 0; i < 90; i++) {
                String date = fmt.format(cal.getTime());
                float day = prefs.getFloat("day_" + date, 0);
                float night = prefs.getFloat("night_" + date, 0);
                float overtime = prefs.getFloat("overtime_" + date, 0);
                int late = prefs.getInt("late_" + date, 0);
                
                if (day > 0 || night > 0 || overtime > 0 || late > 0) {
                    writer.write(String.format("%s,%.1f,%.1f,%.1f,%d,0\n", date, day, night, overtime, late));
                }
                
                cal.add(Calendar.DAY_OF_MONTH, -1);
            }
            
            writer.close();
            
            Toast.makeText(this, "已导出: " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
            
        } catch (Exception e) {
            Toast.makeText(this, "导出失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
