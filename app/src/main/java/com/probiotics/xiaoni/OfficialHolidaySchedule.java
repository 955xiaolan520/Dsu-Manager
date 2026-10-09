package com.probiotics.xiaoni;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** 官方公布的中国法定节假日及调休上班日，作为厂商私有日历不可见时的本地回退。 */
public final class OfficialHolidaySchedule {
    private OfficialHolidaySchedule() {}

    public static String mark(Calendar calendar) {
        if (calendar == null || calendar.get(Calendar.YEAR) != 2026) return "";
        String key = String.format(Locale.US, "%02d-%02d",
                calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH));
        if (REST_2026.contains(key)) return "休";
        if (WORK_2026.contains(key)) return "班";
        return "";
    }

    private static final Set<String> REST_2026 = set(
            "01-01", "01-02", "01-03",
            "02-15", "02-16", "02-17", "02-18", "02-19", "02-20", "02-21", "02-22", "02-23",
            "04-04", "04-05", "04-06",
            "05-01", "05-02", "05-03", "05-04", "05-05",
            "06-19", "06-20", "06-21",
            "09-25", "09-26", "09-27",
            "10-01", "10-02", "10-03", "10-04", "10-05", "10-06", "10-07");

    private static final Set<String> WORK_2026 = set(
            "01-04", "02-14", "02-28", "05-09", "09-20", "10-10");

    private static Set<String> set(String... values) {
        Set<String> result = new HashSet<>();
        for (String value : values) result.add(value);
        return result;
    }
}
