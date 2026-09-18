package com.probiotics.xiaoni;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import java.io.File;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * ROM 下载前台服务 —— 唯一下载后端为 aria2c 多线程引擎（Aria2Downloader）。
 * 通知栏实时展示：文件名、进度条、百分比、速度、剩余时间；支持 暂停/继续/取消 三个动作按钮。
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
    /** 下载历史（下载管理页数据源）：name / path / size / time / status（done|cancelled|failed） */
    public static final String HISTORY_PREFS = "download_history";
    // v3.8.6：全新通知渠道。旧渠道「rom_download」在 3.8.0~3.8.5 的幽灵常驻通知时代
    // 极可能已被用户长按屏蔽或被系统静音 —— 渠道一旦被屏蔽，同一渠道 ID 的所有下载
    // 通知永远不再显示（这正是「通知栏一直没有下载任务通知」的根源，3.8.5 治了幽灵
    // 通知却治不了已受伤的渠道）。换全新 ID 获得干净渠道，旧渠道同步删除。
    private static final String CHANNEL = "rom_download_v2";
    private static final String LEGACY_CHANNEL = "rom_download";
    private static final int NOTIFICATION_ID = 42;
    private final Object lock = new Object();
    private volatile boolean paused;
    private volatile boolean cancelled;
    private volatile Aria2Downloader downloader;   // aria2c 下载器（唯一后端）
    private Thread worker;
    private PowerManager.WakeLock wakeLock;

    // 当前任务标识（用于判断 ACTION_START 是恢复旧任务还是切换新任务）
    private volatile String activeAddress = "";
    private volatile String activeOutputPath = "";
    // 任务切换：新任务先取消当前任务，旧任务退出后无缝接续新任务
    private volatile String switchAddress;
    private volatile String switchOutputPath;
    private volatile int switchThreads = 16;
    private volatile long switchChunkMB = 8;

    // 通知栏节流：进度/速度每秒最多刷新一次
    private volatile long lastNotifyAt;
    private volatile int lastPercent = -1;
    private volatile long lastSpeedBps = -1;
    private volatile long lastEtaSeconds = -1;
    private volatile long lastDone = -1;
    private volatile long lastTotal = -1;
    private volatile String fileName = "ROM";

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        // v3.8.5 修复通知栏消失：不再无条件 startForeground。
        // 3.8.0+ 打开下载管理页会以 ACTION_QUERY 拉起服务，旧逻辑立即挂出「等待下载」
        // 常驻前台通知且永不停止 —— 幽灵通知被系统/用户静音整个渠道后，
        // 真正的下载进度通知也一并被压制（表现为「加了下载管理后通知栏就没了」）。
        // 现在仅 ACTION_START / ACTION_RESUME 有真实任务时才进入前台，
        // 空闲查询/指令处理完立即 stopSelf，通知渠道保持干净。
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DsuManager:RomDownload");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
    }

    /** 是否有进行中（含暂停等待）的下载任务 */
    private boolean hasActiveTask() {
        synchronized (lock) {
            return worker != null && worker.isAlive() && !cancelled;
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_RESUME : intent.getAction();
        if (ACTION_START.equals(action)) {
            String address = intent.getStringExtra(EXTRA_ADDRESS);
            String output = intent.getStringExtra(EXTRA_OUTPUT);
            // v3.8.6：进前台前先把通知数据源复位成新任务 —— 文件名 / 进度 / 速度 / 暂停位
            // 全部清零，首条通知直接显示真实文件名，不再闪现默认占位「正在下载: ROM」
            // 或上一任务残留的进度/速度
            if (output != null) {
                fileName = new File(output).getName();
                lastDone = -1;
                lastTotal = -1;
                lastSpeedBps = -1;
                lastEtaSeconds = -1;
                lastPercent = -1;
                lastNotifyAt = 0;
                paused = false;
            }
            // 新任务必须重新进入前台（服务进程可能跨任务存活），满足 startForegroundService
            // 5 秒契约
            ensureForeground();
            warnIfNotificationsDisabled();
            if (address != null && output != null) {
                boolean downgrade = intent.getBooleanExtra(EXTRA_DOWNGRADE, false);
                boolean oplus = intent.getBooleanExtra(EXTRA_OPLUS, false);
                int threads = intent.getIntExtra("download_threads", 16);
                long chunkMB = intent.getLongExtra("download_chunk_mb", 8);
                getSharedPreferences("rom_download", MODE_PRIVATE).edit()
                        .putString("address", address)
                        .putString("output", output)
                        .putString("package", intent.getStringExtra(EXTRA_PACKAGE) == null
                                ? "下载" : intent.getStringExtra(EXTRA_PACKAGE))
                        .putBoolean("downgrade", downgrade)
                        .putBoolean("oplus", oplus)
                        .putBoolean("paused", false)
                        .putInt("download_threads", threads)
                        .putLong("download_chunk_mb", chunkMB)
                        // v3.8.5：新任务开始时清除上一个任务残留的 done/total/message，
                        // 避免暂停瞬间恢复时显示旧任务的进度（如刚启动就显示 93%）
                        .remove("done").remove("total").remove("message")
                        .apply();
                File outputFile = new File(output);
                boolean sameTask = address.equals(activeAddress) && output.equals(activeOutputPath);
                boolean busy;
                synchronized (lock) { busy = worker != null && worker.isAlive() && !cancelled; }
                if (busy && !sameTask) {
                    // 任务切换：新任务与当前任务不同 → 先取消当前，旧任务退出后自动接续新任务
                    switchAddress = address;
                    switchOutputPath = output;
                    switchThreads = threads;
                    switchChunkMB = chunkMB;
                    cancelled = true;
                    paused = false;
                    Aria2Downloader active = downloader;
                    if (active != null) active.cancel();
                    synchronized (lock) { lock.notifyAll(); }
                    Thread currentWorker = worker;
                    if (currentWorker != null) currentWorker.interrupt();
                } else {
                    startWorker(address, outputFile, threads, chunkMB);
                }
            }
        } else if (ACTION_PAUSE.equals(action)) {
            // v3.8.6：无进行中任务时退出 —— 必须先补 startForeground 满足契约再停（见 quitIdle）
            if (!hasActiveTask()) { quitIdle(startId); return START_NOT_STICKY; }
            ensureForeground();   // 指令可能经 startForegroundService 下发，须满足 5 秒前台契约
            paused = true;
            Aria2Downloader active = downloader;
            if (active != null) active.pause();
            broadcast("已暂停，点击继续可断点续传", -1, -1);
            lastNotifyAt = System.currentTimeMillis();
            getSystemService(NotificationManager.class)
                    .notify(NOTIFICATION_ID, buildProgressNotification(true, null));
        } else if (ACTION_RESUME.equals(action)) {
            // v3.8.6：已取消 → 按契约退出（先 startForeground 再停止，防 5 秒超时崩溃）
            if (cancelled) { quitIdle(startId); return START_NOT_STICKY; }
            paused = false;
            synchronized (lock) { lock.notifyAll(); }
            if (startSavedWorker()) {
                // 有任务可恢复：进入前台，通知栏恢复进度显示
                ensureForeground();
            } else {
                // v3.8.6：无可恢复任务（已被取消/完成）→ 按契约安静退出
                quitIdle(startId);
            }
        } else if (ACTION_CANCEL.equals(action)) {
            cancelled = true;
            paused = false;
            switchAddress = null;   // 用户主动取消：不再接续任何排队任务
            switchOutputPath = null;
            getSharedPreferences("rom_download", MODE_PRIVATE).edit().clear().apply();
            Aria2Downloader active = downloader;
            if (active != null) active.cancel();
            synchronized (lock) { lock.notifyAll(); }
            Thread currentWorker = worker;
            boolean workerAlive = currentWorker != null && currentWorker.isAlive();
            if (currentWorker != null) currentWorker.interrupt();
            // 广播取消状态 → APP 内下载框同步关闭（通知栏/下载管理/ROM 查询页任一侧取消都走这里）
            sendBroadcast(new Intent(ACTION_UPDATE).setPackage(getPackageName())
                    .putExtra(EXTRA_STATE, "下载已取消")
                    .putExtra(EXTRA_DONE, -1L)
                    .putExtra(EXTRA_TOTAL, -1L)
                    .putExtra(EXTRA_SPEED, -1L)
                    .putExtra(EXTRA_ETA, -1L));
            if (workerAlive) {
                // 真实任务取消：立即退出前台进度状态，随后补一条可点击的最终通知
                ensureForeground();   // 满足可能的 startForegroundService 5 秒契约
                if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
                else stopForeground(true);
                postFinalNotification("下载已取消");
                // 工作线程收尾后由其 stopSelf
            } else {
                // v3.8.6：本就没有进行中的任务 → 按契约安静退出（先 startForeground 再停止）
                quitIdle(startId);
            }
            return START_NOT_STICKY;
        } else if (ACTION_QUERY.equals(action)) {
            if (hasActiveTask()) {
                // 有进行中任务：回报最新持久化状态（下载管理页/ROM 查询页进入时刷新用）
                ensureForeground();   // 满足可能的 startForegroundService 5 秒契约
                broadcastSavedState();
            } else {
                // v3.8.6：空闲查询不驻留，但必须先 startForeground 满足契约再停止。
                // v3.8.5 在此直接 stopSelf：vivo 查询页 onResume 经 startForegroundService
                // 发查询指令，服务 5 秒内未 startForeground →
                // ForegroundServiceDidNotStartInTimeException（本次 vivo 闪退根因）
                quitIdle(startId);
            }
        }
        return START_STICKY;
    }

    /** 按持久化状态恢复任务；返回是否有任务在跑（供 RESUME 分支决定是否进入前台） */
    private boolean startSavedWorker() {
        android.content.SharedPreferences prefs = getSharedPreferences("rom_download", MODE_PRIVATE);
        String address = prefs.getString("address", "");
        String output = prefs.getString("output", "");
        int threads = prefs.getInt("download_threads", 16);
        long chunkMB = prefs.getLong("download_chunk_mb", 8);
        if (address.isEmpty() || output.isEmpty()) return false;
        paused = false;
        startWorker(address, new File(output), threads, chunkMB);
        return true;
    }

    private void startWorker(String address, File output, int threads, long chunkMB) {
        synchronized (lock) {
            if (worker != null && worker.isAlive()) {
                paused = false;
                lock.notifyAll();
                return;
            }
            cancelled = false;
            paused = false;
            fileName = output.getName();
            worker = new Thread(() -> runDownload(address, output, threads, chunkMB), "dsu-download-service");
            worker.start();
        }
    }

    private void runDownload(String address, File output, int threads, long chunkMB) {
        if (address == null || !address.matches("https?://.+/.+(?:[?#].*)?")) {
            broadcast("下载地址无效，缺少完整 ROM 文件名", output.length(), -1);
            finishWithNotification("下载地址无效");
            return;
        }
        activeAddress = address;
        activeOutputPath = output.getAbsolutePath();
        fileName = output.getName();
        lastPercent = -1;
        lastSpeedBps = -1;
        lastEtaSeconds = -1;
        lastDone = -1;
        lastTotal = -1;
        lastNotifyAt = 0;   // 新任务立即刷新一次通知栏
        broadcast("正在下载: " + output.getName(), output.length(), -1);
        getSystemService(NotificationManager.class)
                .notify(NOTIFICATION_ID, buildProgressNotification(false, null));
        while (!cancelled) {
            synchronized (lock) {
                while (paused && !cancelled) {
                    try { lock.wait(); } catch (InterruptedException ignored) { }
                }
            }
            if (cancelled) break;
            final boolean[] completed = {false};
            final boolean[] failed = {false};
            final String[] failMessage = {""};
            Aria2Downloader active = new Aria2Downloader(this, address, output,
                    new Aria2Downloader.Listener() {
                @Override public void onState(String text) { broadcast(text, -1, -1); }
                @Override public void onProgress(long done, long total) {
                    broadcast(null, done, total);
                    notifyProgress(done, total);
                }
                @Override public void onSpeed(long bytesPerSecond, long etaSeconds) {
                    lastSpeedBps = bytesPerSecond;
                    if (etaSeconds >= 0) lastEtaSeconds = etaSeconds;
                    notifySpeedTick();   // 速度/剩余时间变化同样同步到通知栏
                }
                @Override public void onError(String message) {
                    failed[0] = true;
                    paused = true;
                    failMessage[0] = message;
                    broadcast("下载失败: " + message, -1, -1);
                }
                @Override public void onComplete(File completedFile) {
                    downloader = null;
                    completed[0] = true;
                    Aria2Downloader.deleteCheckpointFiles(completedFile);
                    // 下载历史：完成记录（供下载管理页「打开文件位置」使用）
                    recordHistory(completedFile.getName(), completedFile.getAbsolutePath(),
                            completedFile.length(), "done");
                    broadcast("下载完成", completedFile.length(), completedFile.length());
                    getSharedPreferences("rom_download", MODE_PRIVATE).edit()
                            .remove("address").remove("output").remove("package")
                            .remove("paused").remove("message").remove("done").remove("total")
                            .apply();
                }
            }, threads, chunkMB);
            downloader = active;
            active.run();
            downloader = null;
            if (paused && !cancelled) {
                if (!failed[0]) broadcast("已暂停，点击继续可断点续传", -1, -1);
                synchronized (lock) {
                    while (paused && !cancelled) {
                        try { lock.wait(); } catch (InterruptedException ignored) { }
                    }
                }
                if (cancelled) break;
                continue;
            }
            if (cancelled) break;
            if (completed[0]) {
                finishWithNotification("下载完成 · " + Aria2Downloader.formatBytes(output.length()));
                return;
            }
            if (failed[0]) {
                // v3.8.2 按需求调整：失败任务同样不写入下载历史，仅下载成功才记录
                finishWithNotification("下载失败: " + failMessage[0]);
                return;
            }
            break;
        }
        if (cancelled) {
            Aria2Downloader.deleteCancelledDownload(output);
            String nextAddress = switchAddress;
            String nextOutputPath = switchOutputPath;
            if (nextAddress != null && nextOutputPath != null) {
                // 任务切换：清理旧任务缓存后无缝启动新任务（不弹“已取消”通知）
                int nextThreads = switchThreads;
                long nextChunk = switchChunkMB;
                switchAddress = null;
                switchOutputPath = null;
                File nextOutput = new File(nextOutputPath);
                cancelled = false;
                paused = false;
                lastSpeedBps = -1;
                lastEtaSeconds = -1;
                synchronized (lock) {
                    fileName = nextOutput.getName();
                    worker = new Thread(() -> runDownload(nextAddress, nextOutput, nextThreads, nextChunk),
                            "dsu-download-service");
                    worker.start();
                }
                return;
            }
            activeAddress = "";
            activeOutputPath = "";
            finishWithNotification("下载已取消");
            return;
        }
        // v3.8.6：兜底退出路径同样先退出前台再停止，避免遗留前台通知
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }

    private void broadcast(String state, long done, long total) {
        android.content.SharedPreferences.Editor editor =
                getSharedPreferences("rom_download", MODE_PRIVATE).edit();
        if (state != null) editor.putString("message", state);
        if (done >= 0) editor.putLong("done", done);
        if (total >= 0) editor.putLong("total", total);
        editor.putBoolean("paused", paused).apply();
        Intent update = new Intent(ACTION_UPDATE).setPackage(getPackageName());
        if (state != null) update.putExtra(EXTRA_STATE, state);
        update.putExtra(EXTRA_DONE, done);
        update.putExtra(EXTRA_TOTAL, total);
        update.putExtra(EXTRA_SPEED, lastSpeedBps);
        update.putExtra(EXTRA_ETA, lastEtaSeconds);
        sendBroadcast(update);
    }

    /** 下载历史持久化（最多保留 50 条，新的在前）。下载管理页数据源。 */
    public static void recordHistory(android.content.Context context,
            String name, String path, long size, String status) {
        try {
            android.content.SharedPreferences prefs =
                    context.getSharedPreferences(HISTORY_PREFS, MODE_PRIVATE);
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

    private void broadcastSavedState() {
        android.content.SharedPreferences prefs = getSharedPreferences("rom_download", MODE_PRIVATE);
        String output = prefs.getString("output", "");
        if (output.isEmpty()) return;
        // v3.8.5：不用文件长度兜底 —— aria2c 多线程分段并行写入时文件长度是最远写入偏移
        // 而非真实已下载量（小米 16 线程下载暂停后误显 93% 的根源），进度以 prefs 为准
        long done = prefs.getLong("done", 0);
        long total = prefs.getLong("total", -1);
        String state = prefs.getString("message", "正在下载");
        Intent update = new Intent(ACTION_UPDATE).setPackage(getPackageName())
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_DONE, done)
                .putExtra(EXTRA_TOTAL, total);
        sendBroadcast(update);
    }

    // ---------- 通知栏（状态栏下载状态，图九样式：系统原生下载进度模板） ----------

    /** v3.8.5：确保服务处于前台并显示下载通知（可安全重复调用，按当前暂停态构建标题） */
    private void ensureForeground() {
        try {
            startForeground(NOTIFICATION_ID, buildProgressNotification(paused, null));
        } catch (Exception ignored) { }
    }

    /**
     * v3.8.6 修复崩溃：本服务可能经 startForegroundService() 拉起（如 vivo 查询页 onResume
     * 发送的状态查询），Android 8+ 要求此类拉起后 5 秒内必须调用 startForeground()，
     * 否则系统抛 ForegroundServiceDidNotStartInTimeException（本次 vivo 查询页闪退根因）。
     * 无任务退出前先补一次 startForeground 满足契约，随即移除通知并停止服务。
     */
    private void quitIdle(int startId) {
        try {
            startForeground(NOTIFICATION_ID, buildProgressNotification(false, "空闲"));
        } catch (Exception ignored) { }
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf(startId);
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

    /** 进度回调：与 APP 内下载框同一数据源，同步刷新通知栏 */
    private void notifyProgress(long done, long total) {
        if (done >= 0) lastDone = done;
        if (total >= 0) lastTotal = total;
        int percent = lastTotal > 0 && lastDone >= 0 ? (int) (lastDone * 100 / lastTotal) : -1;
        long now = System.currentTimeMillis();
        if (percent == lastPercent && now - lastNotifyAt < 1000) return;
        if (now - lastNotifyAt < 900) return;
        lastNotifyAt = now;
        lastPercent = percent;
        getSystemService(NotificationManager.class)
                .notify(NOTIFICATION_ID, buildProgressNotification(false, null));
    }

    /** 速度/剩余时间回调：进度条数字暂未变化时也保持通知栏速度实时同步 */
    private void notifySpeedTick() {
        long now = System.currentTimeMillis();
        if (now - lastNotifyAt < 900) return;
        lastNotifyAt = now;
        getSystemService(NotificationManager.class)
                .notify(NOTIFICATION_ID, buildProgressNotification(false, null));
    }

    /**
     * 图九样式：系统原生下载进度通知（跨 ROM 渲染一致，自定义 RemoteViews 在部分系统上不刷新）。
     * 标题「正在下载: 文件名」，正文「9% · 921.6MB / 9.00GB · 58.7MB/s · 剩余 2分30秒」，
     * 进度条 + 暂停/取消按钮 —— 与 APP 内下载框同一数据源、同步刷新。
     */
    private Notification buildProgressNotification(boolean paused, String titleOverride) {
        Intent open = new Intent(this, DownloadManagerActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, NOTIFICATION_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        int percent = lastTotal > 0 && lastDone >= 0 ? (int) (lastDone * 100 / lastTotal) : -1;
        String title = titleOverride != null ? titleOverride
                : (paused ? "已暂停: " : "正在下载: ") + fileName;
        StringBuilder body = new StringBuilder();
        if (percent >= 0) body.append(percent).append("%");
        if (lastDone >= 0 && lastTotal > 0) {
            if (body.length() > 0) body.append(" · ");
            body.append(Aria2Downloader.formatBytes(lastDone))
                    .append(" / ").append(Aria2Downloader.formatBytes(lastTotal));
        }
        if (lastSpeedBps > 0) {
            body.append(" · ").append(Aria2Downloader.formatBytes(lastSpeedBps)).append("/s");
            if (!paused && lastEtaSeconds > 0) {
                body.append(" · 剩余 ").append(Aria2Downloader.formatEta(lastEtaSeconds));
            }
        }
        if (body.length() == 0) body.append(paused ? "点击继续可断点续传" : "正在连接下载节点...");
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle(title)
                .setContentText(body.toString())
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pending)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(100, Math.max(0, percent), percent < 0)
                .addAction(new Notification.Action.Builder(null,
                        paused ? "继续" : "暂停",
                        servicePending(paused ? ACTION_RESUME : ACTION_PAUSE, 2)).build())
                .addAction(new Notification.Action.Builder(null, "取消",
                        servicePending(ACTION_CANCEL, 3)).build())
                .build();
    }

    private PendingIntent servicePending(String action, int requestCode) {
        Intent intent = new Intent(this, DownloadService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 结束前台服务并发布一条可点击的最终通知（完成/失败/取消） */
    private void finishWithNotification(String text) {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        postFinalNotification(text);
        stopSelf();
    }

    /** 发布最终状态通知（下载已取消/完成/失败），不停止服务本身 */
    private void postFinalNotification(String text) {
        Intent open = new Intent(this, DownloadManagerActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, NOTIFICATION_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification done = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Dsu 管理器下载")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID + 1, done);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            // v3.8.6：删除可能已被屏蔽的旧渠道，创建全新干净渠道
            try { nm.deleteNotificationChannel(LEGACY_CHANNEL); } catch (Exception ignored) { }
            NotificationChannel channel = new NotificationChannel(CHANNEL, "ROM 下载进度",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("ROM 下载进度、速度与剩余时间");
            nm.createNotificationChannel(channel);
        }
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
