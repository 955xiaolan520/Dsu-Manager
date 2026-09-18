package com.probiotics.xiaoni;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * ROM 下载前台服务（v3.8.8 多任务版）—— 唯一下载后端为 aria2c 多线程引擎（Aria2Downloader）。
 *
 * v3.8.8 多任务能力：
 *  - 最多 3 个任务同时下载（并行），第 4 个起自动排队「待下载」，前面的任务结束后自动接续；
 *  - 每个任务独立通知（槽位 42/43/44）、独立断点、独立进度与暂停/继续/取消；
 *  - 每个任务携带来源页面标记（EXTRA_PAGE）：小米 / vivo / OPPO 查询页只显示自己页
 *    发起的下载框，其他页任务以折叠条提示「还有 N 个任务在下载」；
 *  - 任务状态统一持久化到「download_tasks」（JSON），页面恢复/对账的唯一数据源。
 *
 * 通知栏：每任务实时展示文件名、进度条、百分比、速度、剩余时间，支持 暂停/继续/取消。
 */
public final class DownloadService extends Service {
    public static final String ACTION_START = "com.probiotics.xiaoni.download.START";
    public static final String ACTION_PAUSE = "com.probiotics.xiaoni.download.PAUSE";
    public static final String ACTION_RESUME = "com.probiotics.xiaoni.download.RESUME";
    public static final String ACTION_CANCEL = "com.probiotics.xiaoni.download.CANCEL";
    public static final String ACTION_QUERY = "com.probiotics.xiaoni.download.QUERY";
    public static final String ACTION_UPDATE = "com.probiotics.xiaoni.download.UPDATE";
    public static final String EXTRA_ADDRESS = "address";
    public static final String EXTRA_OUTPUT = "output";
    public static final String EXTRA_PACKAGE = "package";
    public static final String EXTRA_DOWNGRADE = "downgrade";
    public static final String EXTRA_OPLUS = "oplus";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_DONE = "done";
    public static final String EXTRA_TOTAL = "total";
    public static final String EXTRA_SPEED = "speed";
    public static final String EXTRA_ETA = "eta";
    /** v3.8.8：任务来源页面标记（Activity simpleName）。广播据此过滤，页面只显示自己的任务。 */
    public static final String EXTRA_PAGE = "page";
    /** v3.8.8：任务唯一标识 = 输出文件绝对路径。暂停/继续/取消指令据此定位任务。 */
    public static final String EXTRA_TASK_ID = "task_id";
    /** v3.8.8：多任务持久化（JSON 数组，服务与页面共享的唯一任务数据源）。 */
    public static final String TASKS_PREFS = "download_tasks";
    /** 下载历史（下载管理页数据源）：name / path / size / time / status（done|cancelled|failed） */
    public static final String HISTORY_PREFS = "download_history";

    // v3.8.6：全新通知渠道（旧渠道「rom_download」在幽灵通知时代可能已被屏蔽，弃用并删除）
    private static final String CHANNEL = "rom_download_v2";
    private static final String LEGACY_CHANNEL = "rom_download";
    /** v3.8.8：最大并行下载数；第 4 个起排队「待下载」 */
    private static final int MAX_ACTIVE = 3;
    /** 活跃任务通知槽位（一个任务一个独立通知） */
    private static final int[] NOTE_IDS = {42, 43, 44};
    /** 结束通知（完成/失败/取消）ID 基数：50/51/52 */
    private static final int FINAL_BASE = 50;

    /** 单个下载任务：独立的状态、断点、通知槽位与工作线程 */
    private static final class Task {
        final String id;          // 输出文件绝对路径（唯一标识）
        final String address;
        final File output;
        final String page;        // 来源页面（RomActivity / VivoActivity / OPlusOtaActivity…）
        final String pkg;         // 包类型文案（卡刷包 / vivo OTA / OPlus OTA…）
        final int threads;
        final long chunkMB;
        final Object pauseLock = new Object();
        volatile boolean paused;
        volatile boolean cancelled;
        volatile boolean pending;   // true = 排队「待下载」
        volatile Aria2Downloader downloader;
        Thread worker;
        int slot = -1;             // 通知槽位 0..2，-1 = 未激活
        // 实时状态（广播与通知共用）
        volatile String message = "正在准备下载";
        volatile long done = -1, total = -1, speed = -1, eta = -1;
        // 通知节流
        long lastNotifyAt;
        int lastPercent = -1;

        Task(String id, String address, File output, String page, String pkg, int threads, long chunkMB) {
            this.id = id;
            this.address = address;
            this.output = output;
            this.page = page;
            this.pkg = pkg;
            this.threads = threads;
            this.chunkMB = chunkMB;
        }
    }

    private final List<Task> tasks = new ArrayList<>();   // 全部任务（活跃 + 待下载）
    private final Object listLock = new Object();
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DsuManager:RomDownload");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
    }

    // ---------- 任务查找 / 计数 ----------

    private Task findTask(String id) {
        if (id == null || id.isEmpty()) return null;
        synchronized (listLock) {
            for (Task task : tasks) if (task.id.equals(id)) return task;
        }
        return null;
    }

    private int countActive() {
        synchronized (listLock) {
            int n = 0;
            for (Task task : tasks) if (!task.pending) n++;
            return n;
        }
    }

    private Task firstActiveTask() {
        synchronized (listLock) {
            for (Task task : tasks) if (!task.pending) return task;
        }
        return null;
    }

    private Task firstPendingTask() {
        synchronized (listLock) {
            for (Task task : tasks) if (task.pending) return task;
        }
        return null;
    }

    /** 目标任务：优先按 EXTRA_TASK_ID 定位；未指定时取第一个活跃任务（兼容旧调用方） */
    private Task targetTask(Intent intent) {
        Task byId = findTask(intent.getStringExtra(EXTRA_TASK_ID));
        if (byId != null) return byId;
        return firstActiveTask();
    }

    // ---------- 指令分发 ----------

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_RESUME : intent.getAction();
        if (ACTION_START.equals(action)) {
            String address = intent.getStringExtra(EXTRA_ADDRESS);
            String output = intent.getStringExtra(EXTRA_OUTPUT);
            if (address == null || output == null) { quitIdle(startId); return START_NOT_STICKY; }
            warnIfNotificationsDisabled();
            Task task = findTask(output);
            if (task != null) {
                // 同一文件重复开始：活跃中 → 忽略；已暂停 → 恢复；排队中 → 回报排队状态
                if (task.pending) {
                    broadcastTask(task, task.message, -1, -1);
                } else if (task.paused) {
                    resumeTask(task);
                } else {
                    broadcastTask(task, task.message, -1, -1);
                }
                if (countActive() > 0) ensureForeground();
                return START_STICKY;
            }
            task = new Task(output, address, new File(output),
                    intent.getStringExtra(EXTRA_PAGE) == null ? "" : intent.getStringExtra(EXTRA_PAGE),
                    intent.getStringExtra(EXTRA_PACKAGE) == null ? "下载" : intent.getStringExtra(EXTRA_PACKAGE),
                    intent.getIntExtra("download_threads", 16),
                    intent.getLongExtra("download_chunk_mb", 8));
            synchronized (listLock) { tasks.add(task); }
            startOrQueue(task);
            ensureForeground();   // 有活跃任务必须满足 startForegroundService 5 秒契约
        } else if (ACTION_PAUSE.equals(action)) {
            Task task = targetTask(intent);
            if (task == null || task.pending) {
                if (countActive() == 0) { quitIdle(startId); return START_NOT_STICKY; }
                ensureForeground();
                return START_STICKY;
            }
            task.paused = true;
            Aria2Downloader active = task.downloader;
            if (active != null) active.pause();
            task.lastNotifyAt = System.currentTimeMillis();
            broadcastTask(task, "已暂停，点击继续可断点续传", -1, -1);
            notifyTaskNow(task);
            ensureForeground();
        } else if (ACTION_RESUME.equals(action)) {
            String taskId = intent.getStringExtra(EXTRA_TASK_ID);
            String address = intent.getStringExtra(EXTRA_ADDRESS);
            String output = intent.getStringExtra(EXTRA_OUTPUT);
            Task task = findTask(taskId);
            if (task == null && output != null && address != null && findTask(output) == null) {
                // 页面恢复路径：服务侧无此任务（服务重启/进程被杀）→ 按 extras 重建任务
                task = new Task(output, address, new File(output),
                        intent.getStringExtra(EXTRA_PAGE) == null ? "" : intent.getStringExtra(EXTRA_PAGE),
                        intent.getStringExtra(EXTRA_PACKAGE) == null ? "下载" : intent.getStringExtra(EXTRA_PACKAGE),
                        intent.getIntExtra("download_threads", 16),
                        intent.getLongExtra("download_chunk_mb", 8));
                synchronized (listLock) { tasks.add(task); }
                startOrQueue(task);
                ensureForeground();
                return START_STICKY;
            }
            if (task == null) {
                // 服务重启（START_STICKY 空 intent）或无目标任务 → 从持久化恢复全部任务
                restoreSavedTasks();
                if (countActive() == 0) { quitIdle(startId); return START_NOT_STICKY; }
                ensureForeground();
                return START_STICKY;
            }
            if (task.pending) {
                // 排队任务无空闲槽位 → 仍回报排队状态（避免误恢复）
                broadcastTask(task, task.message, -1, -1);
                ensureForeground();
                return START_STICKY;
            }
            resumeTask(task);
            ensureForeground();
        } else if (ACTION_CANCEL.equals(action)) {
            String taskId = intent.getStringExtra(EXTRA_TASK_ID);
            if (taskId == null || taskId.isEmpty()) {
                // v3.8.8 兼容：未指定任务 → 取消全部任务（旧调用方语义）
                List<Task> all;
                synchronized (listLock) { all = new ArrayList<>(tasks); }
                for (Task task : all) cancelTask(task);
                return START_NOT_STICKY;
            }
            Task task = findTask(taskId);
            if (task != null) cancelTask(task);
            else quitIdle(startId);
            return START_NOT_STICKY;
        } else if (ACTION_QUERY.equals(action)) {
            if (countActive() > 0 || firstPendingTask() != null) {
                ensureForeground();   // 满足可能的 startForegroundService 5 秒契约
                broadcastAllTasks();
            } else {
                // v3.8.6 契约修复保留：空闲查询必须先 startForeground 再 stopSelf，
                // 否则 ForegroundServiceDidNotStartInTimeException（vivo 页闪退根因）
                quitIdle(startId);
            }
        }
        return START_STICKY;
    }

    /** 新任务：有空闲槽位立即开始；已满 3 个并行 → 排队「待下载」 */
    private void startOrQueue(Task task) {
        if (countActive() < MAX_ACTIVE) {
            activate(task);
        } else {
            task.pending = true;
            task.message = "排队等待中 · 前面还有 " + countActive() + " 个任务（最多同时下载 " + MAX_ACTIVE + " 个）";
            persistTasks();
            broadcastTask(task, task.message, -1, -1);
        }
    }

    /** 激活任务：分配通知槽位并启动工作线程 */
    private void activate(Task task) {
        synchronized (listLock) {
            if (task.worker != null && task.worker.isAlive()) return;
            int slot = -1;
            boolean[] used = new boolean[MAX_ACTIVE];
            for (Task t : tasks) if (!t.pending && t.slot >= 0) used[t.slot] = true;
            for (int i = 0; i < MAX_ACTIVE; i++) if (!used[i]) { slot = i; break; }
            if (slot < 0) {   // 无槽位（理论不可达）→ 排队
                task.pending = true;
                task.message = "排队等待中 · 前面还有 " + MAX_ACTIVE + " 个任务（最多同时下载 " + MAX_ACTIVE + " 个）";
                persistTasks();
                broadcastTask(task, task.message, -1, -1);
                return;
            }
            task.slot = slot;
            task.pending = false;
            task.paused = false;
            task.cancelled = false;
            task.lastPercent = -1;
            task.lastNotifyAt = 0;
            task.worker = new Thread(() -> runTask(task), "dsu-download-" + slot);
            task.worker.start();
        }
    }

    /** 恢复已暂停的活跃任务 */
    private void resumeTask(Task task) {
        task.paused = false;
        synchronized (task.pauseLock) { task.pauseLock.notifyAll(); }
        // v3.9.2 修复：此前广播「正在继续下载」，而下载管理页/ROM查询页状态机只认
        // 「正在下载」开头才清除暂停态 → 恢复后页面卡片永远卡在「已暂停」，
        // 只有通知栏（直接读内存字段）正常。改为与 worker 首启一致的标准文案。
        broadcastTask(task, "正在下载: " + task.output.getName(), task.output.length(), -1);
        notifyTaskNow(task);
    }

    /** 取消任务（活跃 → 工作线程收尾；排队 → 立即移除） */
    private void cancelTask(Task task) {
        task.cancelled = true;
        task.paused = false;
        Aria2Downloader active = task.downloader;
        if (active != null) active.cancel();
        synchronized (task.pauseLock) { task.pauseLock.notifyAll(); }
        Thread worker = task.worker;
        if (worker != null) worker.interrupt();
        if (task.pending) {
            synchronized (listLock) { tasks.remove(task); }
            persistTasks();
            broadcastTask(task, "下载已取消", -1, -1);
            if (countActive() == 0 && firstPendingTask() == null) {
                stopForegroundCompat();
                stopSelf();
            }
            return;
        }
        // 活跃任务：广播立即收框，文件清理与接续由工作线程完成
        broadcastTask(task, "下载已取消", -1, -1);
    }

    // ---------- 任务工作线程（每任务一个，逻辑同 v3.8.6 单任务引擎） ----------

    private void runTask(Task task) {
        if (task.address == null || !task.address.matches("https?://.+/.+(?:[?#].*)?")) {
            broadcastTask(task, "下载地址无效，缺少完整 ROM 文件名", task.output.length(), -1);
            endTask(task, "下载地址无效", false);
            return;
        }
        task.done = -1;
        task.total = -1;
        task.speed = -1;
        task.eta = -1;
        task.lastPercent = -1;
        task.lastNotifyAt = 0;
        broadcastTask(task, "正在下载: " + task.output.getName(), task.output.length(), -1);
        notifyTaskNow(task);
        while (!task.cancelled) {
            synchronized (task.pauseLock) {
                while (task.paused && !task.cancelled) {
                    try { task.pauseLock.wait(); } catch (InterruptedException ignored) { }
                }
            }
            if (task.cancelled) break;
            final boolean[] completed = {false};
            final boolean[] failed = {false};
            final String[] failMessage = {""};
            Aria2Downloader engine = new Aria2Downloader(this, task.address, task.output,
                    new Aria2Downloader.Listener() {
                @Override public void onState(String text) { broadcastTask(task, text, -1, -1); }
                @Override public void onProgress(long done, long total) {
                    broadcastTask(task, null, done, total);
                    notifyTaskThrottled(task);
                }
                @Override public void onSpeed(long bytesPerSecond, long etaSeconds) {
                    task.speed = bytesPerSecond;
                    if (etaSeconds >= 0) task.eta = etaSeconds;
                    notifyTaskThrottled(task);
                }
                @Override public void onError(String message) {
                    failed[0] = true;
                    task.paused = true;
                    failMessage[0] = message;
                    broadcastTask(task, "下载失败: " + message, -1, -1);
                }
                @Override public void onComplete(File completedFile) {
                    task.downloader = null;
                    completed[0] = true;
                    Aria2Downloader.deleteCheckpointFiles(completedFile);
                    // v3.8.2 需求保留：仅下载成功才写入历史
                    recordHistory(completedFile.getName(), completedFile.getAbsolutePath(),
                            completedFile.length(), "done");
                    broadcastTask(task, "下载完成", completedFile.length(), completedFile.length());
                }
            }, task.threads, task.chunkMB);
            task.downloader = engine;
            engine.run();
            task.downloader = null;
            if (task.paused && !task.cancelled) {
                if (!failed[0] && !task.message.startsWith("已暂停")) {
                    broadcastTask(task, "已暂停，点击继续可断点续传", -1, -1);
                }
                synchronized (task.pauseLock) {
                    while (task.paused && !task.cancelled) {
                        try { task.pauseLock.wait(); } catch (InterruptedException ignored) { }
                    }
                }
                if (task.cancelled) break;
                continue;
            }
            if (task.cancelled) break;
            if (completed[0]) {
                endTask(task, "下载完成 · " + Aria2Downloader.formatBytes(task.output.length()), true);
                return;
            }
            if (failed[0]) {
                endTask(task, "下载失败: " + failMessage[0], false);
                return;
            }
            break;
        }
        if (task.cancelled) {
            Aria2Downloader.deleteCancelledDownload(task.output);
            endTask(task, "下载已取消", false);
        }
    }

    /**
     * 任务结束统一收尾：移除任务、取消槽位通知、发最终通知、
     * 接续排队任务、必要时重新绑定前台通知或停止服务。
     */
    private void endTask(Task task, String finalText, boolean completed) {
        int slot = task.slot;
        synchronized (listLock) { tasks.remove(task); }
        persistTasks();
        if (slot >= 0) {
            try { getSystemService(NotificationManager.class).cancel(NOTE_IDS[slot]); } catch (Exception ignored) { }
            postFinalNotification(FINAL_BASE + slot, finalText);
        }
        Task next = firstPendingTask();
        if (next != null) activate(next);
        if (countActive() > 0) {
            ensureForeground();   // 前台通知重新绑定到剩余的第一个活跃任务
        } else if (firstPendingTask() == null) {
            stopForegroundCompat();
            stopSelf();
        } else {
            // 活跃为 0 但仍有排队（理论不可达：activate 已接续）→ 兜底激活
            Task pending = firstPendingTask();
            if (pending != null) { activate(pending); ensureForeground(); }
        }
    }

    // ---------- 广播与持久化（多任务数据源） ----------

    /** 任务状态广播：携带任务 ID 与来源页面标记，页面据此过滤只显示自己的任务 */
    private void broadcastTask(Task task, String state, long done, long total) {
        if (state != null) task.message = state;
        if (done >= 0) task.done = done;
        if (total >= 0) task.total = total;
        persistTasks();
        Intent update = new Intent(ACTION_UPDATE).setPackage(getPackageName())
                .putExtra(EXTRA_TASK_ID, task.id)
                .putExtra(EXTRA_PAGE, task.page);
        if (state != null) update.putExtra(EXTRA_STATE, state);
        update.putExtra(EXTRA_DONE, done)
                .putExtra(EXTRA_TOTAL, total)
                .putExtra(EXTRA_SPEED, task.speed)
                .putExtra(EXTRA_ETA, task.eta);
        sendBroadcast(update);
    }

    /** 回报全部任务状态（QUERY / 服务恢复后全量刷新页面） */
    private void broadcastAllTasks() {
        List<Task> snapshot;
        synchronized (listLock) { snapshot = new ArrayList<>(tasks); }
        for (Task task : snapshot) {
            String state = task.pending
                    ? "排队等待中 · 前面还有 " + countActive() + " 个任务（最多同时下载 " + MAX_ACTIVE + " 个）"
                    : task.message;
            Intent update = new Intent(ACTION_UPDATE).setPackage(getPackageName())
                    .putExtra(EXTRA_TASK_ID, task.id)
                    .putExtra(EXTRA_PAGE, task.page)
                    .putExtra(EXTRA_STATE, state)
                    .putExtra(EXTRA_DONE, task.done)
                    .putExtra(EXTRA_TOTAL, task.total)
                    .putExtra(EXTRA_SPEED, task.speed)
                    .putExtra(EXTRA_ETA, task.eta);
            sendBroadcast(update);
        }
    }

    /** 任务列表持久化（页面恢复/对账的唯一数据源，见 DownloadTaskStore） */
    private void persistTasks() {
        try {
            JSONArray array = new JSONArray();
            List<Task> snapshot;
            synchronized (listLock) { snapshot = new ArrayList<>(tasks); }
            for (Task task : snapshot) {
                array.put(new JSONObject()
                        .put("id", task.id)
                        .put("address", task.address)
                        .put("output", task.output.getAbsolutePath())
                        .put("page", task.page)
                        .put("pkg", task.pkg)
                        .put("threads", task.threads)
                        .put("chunk", task.chunkMB)
                        .put("state", task.pending ? "pending" : task.paused ? "paused" : "downloading")
                        .put("message", task.message)
                        .put("done", task.done)
                        .put("total", task.total)
                        .put("speed", task.speed)
                        .put("eta", task.eta));
            }
            getSharedPreferences(TASKS_PREFS, MODE_PRIVATE).edit()
                    .putString("items", array.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** 服务重启后按持久化恢复全部任务（前 3 个激活，其余排队） */
    private void restoreSavedTasks() {
        synchronized (listLock) {
            if (!tasks.isEmpty()) { broadcastAllTasks(); return; }
            try {
                JSONArray array = new JSONArray(getSharedPreferences(TASKS_PREFS, MODE_PRIVATE)
                        .getString("items", "[]"));
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    String output = item.optString("output", "");
                    String address = item.optString("address", "");
                    if (output.isEmpty() || address.isEmpty()) continue;
                    Task task = new Task(output, address, new File(output),
                            item.optString("page", ""),
                            item.optString("pkg", "下载"),
                            item.optInt("threads", 16),
                            item.optLong("chunk", 8));
                    task.pending = "pending".equals(item.optString("state", "downloading"));
                    task.paused = "paused".equals(item.optString("state", "downloading"));
                    task.message = item.optString("message", "正在准备下载");
                    task.done = item.optLong("done", -1);
                    task.total = item.optLong("total", -1);
                    task.speed = item.optLong("speed", -1);
                    task.eta = item.optLong("eta", -1);
                    tasks.add(task);
                }
            } catch (Exception ignored) { }
        }
        // 按原顺序激活前 MAX_ACTIVE 个（含重启前正在下载/暂停的），其余排队
        List<Task> snapshot;
        synchronized (listLock) { snapshot = new ArrayList<>(tasks); }
        int activated = 0;
        for (Task task : snapshot) {
            if (activated >= MAX_ACTIVE) break;
            if (task.pending) continue;
            task.pending = false;
            activate(task);
            activated++;
        }
        persistTasks();
        broadcastAllTasks();
    }

    // ---------- 通知栏（每任务独立通知，系统原生下载进度模板） ----------

    /** 确保服务前台：绑定到第一个活跃任务的通知（可安全重复调用） */
    private void ensureForeground() {
        Task first = firstActiveTask();
        if (first == null) return;
        try {
            startForeground(NOTE_IDS[Math.max(0, first.slot)], buildTaskNotification(first));
        } catch (Exception ignored) { }
    }

    /**
     * v3.8.6 契约修复保留：本服务可能经 startForegroundService() 拉起，
     * Android 8+ 要求 5 秒内调用 startForeground()，否则
     * ForegroundServiceDidNotStartInTimeException。空闲退出前先补一次再停止。
     */
    private void quitIdle(int startId) {
        try {
            Notification idle = new Notification.Builder(this, CHANNEL)
                    .setContentTitle("Dsu 管理器")
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .build();
            startForeground(NOTE_IDS[0], idle);
        } catch (Exception ignored) { }
        stopForegroundCompat();
        stopSelf(startId);
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
    }

    /** v3.8.6：下载启动时检测通知是否被关闭（应用总开关 / 渠道被屏蔽），提示用户手动开启 */
    private void warnIfNotificationsDisabled() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            boolean blocked = !nm.areNotificationsEnabled();
            if (!blocked && Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = nm.getNotificationChannel(CHANNEL);
                blocked = channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE;
            }
            if (blocked) {
                android.widget.Toast.makeText(getApplicationContext(),
                        "系统通知已被关闭：状态栏将看不到下载进度，请在系统设置中开启 Dsu 管理器的通知",
                        android.widget.Toast.LENGTH_LONG).show();
            }
        } catch (Exception ignored) { }
    }

    /** 进度回调：与 APP 内下载框同一数据源，同步刷新该任务的通知（1 秒节流） */
    private void notifyTaskThrottled(Task task) {
        long now = System.currentTimeMillis();
        if (now - task.lastNotifyAt < 900) return;
        task.lastNotifyAt = now;
        notifyTaskNow(task);
    }

    private void notifyTaskNow(Task task) {
        if (task.slot < 0 || task.pending) return;
        try {
            getSystemService(NotificationManager.class)
                    .notify(NOTE_IDS[task.slot], buildTaskNotification(task));
        } catch (Exception ignored) { }
    }

    /**
     * 系统原生下载进度通知（跨 ROM 渲染一致）。标题「正在下载: 文件名」，
     * 正文「9% · 921.6MB / 9.00GB · 58.7MB/s · 剩余 2分30秒」，进度条 + 暂停/取消按钮。
     */
    private Notification buildTaskNotification(Task task) {
        Intent open = new Intent(this, DownloadManagerActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, NOTE_IDS[Math.max(0, task.slot)], open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long done = task.done, total = task.total;
        int percent = total > 0 && done >= 0 ? (int) (done * 100 / total) : -1;
        String title = task.paused ? "已暂停: " : "正在下载: " + task.output.getName();
        if (task.paused) title = "已暂停: " + task.output.getName();
        StringBuilder body = new StringBuilder();
        if (percent >= 0) body.append(percent).append("%");
        if (done >= 0 && total > 0) {
            if (body.length() > 0) body.append(" · ");
            body.append(Aria2Downloader.formatBytes(done))
                    .append(" / ").append(Aria2Downloader.formatBytes(total));
        }
        if (task.speed > 0) {
            body.append(" · ").append(Aria2Downloader.formatBytes(task.speed)).append("/s");
            if (!task.paused && task.eta > 0) {
                body.append(" · 剩余 ").append(Aria2Downloader.formatEta(task.eta));
            }
        }
        if (body.length() == 0) body.append(task.paused ? "点击继续可断点续传" : "正在连接下载节点...");
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle(title)
                .setContentText(body.toString())
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pending)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(100, Math.max(0, percent), percent < 0)
                .addAction(new Notification.Action.Builder(null,
                        task.paused ? "继续" : "暂停",
                        servicePending(task.paused ? ACTION_RESUME : ACTION_PAUSE,
                                task, (Math.max(0, task.slot)) * 10 + 1)).build())
                .addAction(new Notification.Action.Builder(null, "取消",
                        servicePending(ACTION_CANCEL, task, (Math.max(0, task.slot)) * 10 + 2)).build())
                .build();
    }

    private PendingIntent servicePending(String action, Task task, int requestCode) {
        Intent intent = new Intent(this, DownloadService.class).setAction(action)
                .putExtra(EXTRA_TASK_ID, task.id);
        return PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 发布任务最终状态通知（下载已取消/完成/失败） */
    private void postFinalNotification(int id, String text) {
        Intent open = new Intent(this, DownloadManagerActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, id, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification done = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Dsu 管理器下载")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build();
        try { getSystemService(NotificationManager.class).notify(id, done); } catch (Exception ignored) { }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            try { nm.deleteNotificationChannel(LEGACY_CHANNEL); } catch (Exception ignored) { }
            NotificationChannel channel = new NotificationChannel(CHANNEL, "ROM 下载进度",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("ROM 下载进度、速度与剩余时间（多任务并行，最多 3 个）");
            nm.createNotificationChannel(channel);
        }
    }

    // ---------- 下载历史（v3.8.2：仅成功记录） ----------

    public static void recordHistory(android.content.Context context,
            String name, String path, long size, String status) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(HISTORY_PREFS, MODE_PRIVATE);
            JSONArray array = new JSONArray(prefs.getString("items", "[]"));
            JSONObject item = new JSONObject();
            item.put("name", name == null ? "" : name)
                    .put("path", path == null ? "" : path)
                    .put("size", size)
                    .put("time", System.currentTimeMillis())
                    .put("status", status);
            array.put(item);
            while (array.length() > 50) array.remove(0);
            prefs.edit().putString("items", array.toString()).apply();
        } catch (Exception ignored) { }
    }

    private void recordHistory(String name, String path, long size, String status) {
        recordHistory(this, name, path, size, status);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        // 前台下载在任务被划掉后继续进行
        super.onTaskRemoved(rootIntent);
    }
}
