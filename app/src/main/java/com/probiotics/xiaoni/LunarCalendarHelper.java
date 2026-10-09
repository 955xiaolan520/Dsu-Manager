package com.probiotics.xiaoni;

import android.icu.util.ChineseCalendar;
import java.util.Calendar;

/**
 * 本地农历显示工具，不依赖系统日历应用；Android 7.0+ 自带 ICU 农历实现。
 */
public final class LunarCalendarHelper {
    private static final String[] MONTHS = {"正月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "冬月", "腊月"};
    private static final String[] DAYS = {"初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十", "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十", "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十"};

    private LunarCalendarHelper() {}

    public static String day(Calendar solar) {
        ChineseCalendar lunar = new ChineseCalendar(android.icu.util.TimeZone.getDefault());
        lunar.setTimeInMillis(solar.getTimeInMillis());
        int month = lunar.get(Calendar.MONTH);
        int day = lunar.get(Calendar.DAY_OF_MONTH);
        if (month < 0 || month >= MONTHS.length || day < 1 || day > DAYS.length) return "";
        return MONTHS[month] + DAYS[day - 1];
    }

    public static String festival(Calendar solar) {
        int month = solar.get(Calendar.MONTH) + 1;
        int day = solar.get(Calendar.DAY_OF_MONTH);
        if (month == 1 && day == 1) return "元旦";
        if (month == 5 && day == 1) return "劳动节";
        if (month == 6 && day == 1) return "儿童节";
        if (month == 10 && day == 1) return "国庆节";
        if (month == 10 && day == 2) return "国庆节";
        if (month == 10 && day == 3) return "国庆节";
        if (month == 4 && day >= 4 && day <= 6) return "清明";

        ChineseCalendar lunar = new ChineseCalendar(android.icu.util.TimeZone.getDefault());
        lunar.setTimeInMillis(solar.getTimeInMillis());
        int lm = lunar.get(Calendar.MONTH) + 1;
        int ld = lunar.get(Calendar.DAY_OF_MONTH);
        if (lm == 1 && ld == 1) return "春节";
        if (lm == 1 && ld == 15) return "元宵";
        if (lm == 5 && ld == 5) return "端午";
        if (lm == 7 && ld == 7) return "七夕";
        if (lm == 8 && ld == 15) return "中秋";
        if (lm == 9 && ld == 9) return "重阳";
        if (lm == 12 && ld == 8) return "腊八";
        if (lm == 12 && (ld == 29 || ld == 30)) return "除夕";
        return "";
    }

    public static boolean isHoliday(Calendar solar) {
        return !festival(solar).isEmpty();
    }
}
