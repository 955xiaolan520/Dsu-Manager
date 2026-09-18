package com.probiotics.xiaoni;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * v3.8.8 多任务持久化存储（页面侧只读）。
 *
 * DownloadService.persistTasks() 把全部任务写入「download_tasks」JSON 数组，
 * 页面恢复 / 回页对账时通过本类读取，不再依赖内存中的单例状态：
 *  - forPage(page)：当前页面发起的任务列表（下载框渲染数据源）
 *  - countOthers(page)：其他页面任务数（折叠条「还有 N 个任务在下载」）
 *  - all()：全部任务（下载管理页任务卡列表）
 */
public final class DownloadTaskStore {

    /** 单个任务快照（UI 只读展示） */
    public static final class Item {
        public final String id;        // 输出文件绝对路径（唯一标识）
        public final String address;
        public final File output;
        public final String page;      // 来源页面 simpleName
        public final String pkg;       // 包类型文案
        public final String state;     // downloading | paused | pending
        public final String message;   // 最新状态行
        public final long done, total, speed, eta;

        Item(JSONObject o) {
            id = o.optString("id", "");
            address = o.optString("address", "");
            output = new File(o.optString("output", ""));
            page = o.optString("page", "");
            pkg = o.optString("pkg", "");
            state = o.optString("state", "downloading");
            message = o.optString("message", "");
            done = o.optLong("done", -1);
            total = o.optLong("total", -1);
            speed = o.optLong("speed", -1);
            eta = o.optLong("eta", -1);
        }

        public boolean isPending() { return "pending".equals(state); }
        public boolean isPaused() { return "paused".equals(state); }
        public String fileName() { return output.getName(); }
    }

    private DownloadTaskStore() { }

    /** 全部任务（按发起顺序） */
    public static List<Item> all(Context context) {
        return read(context, null);
    }

    /** 指定页面发起的任务（页面只渲染自己的下载框） */
    public static List<Item> forPage(Context context, String page) {
        return read(context, page);
    }

    /** 其他页面任务数：>0 时页面底部显示折叠条「还有 N 个任务在下载」 */
    public static int countOthers(Context context, String page) {
        int n = 0;
        for (Item item : read(context, null)) {
            if (!item.page.equals(page)) n++;
        }
        return n;
    }

    private static List<Item> read(Context context, String pageFilter) {
        List<Item> items = new ArrayList<>();
        try {
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(DownloadService.TASKS_PREFS, Context.MODE_PRIVATE);
            JSONArray array = new JSONArray(prefs.getString("items", "[]"));
            for (int i = 0; i < array.length(); i++) {
                Item item = new Item(array.getJSONObject(i));
                if (pageFilter == null || item.page.equals(pageFilter)) items.add(item);
            }
        } catch (Exception ignored) { }
        return items;
    }
}
