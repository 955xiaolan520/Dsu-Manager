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
    private static final String CHANNEL = "rom_download";
    private final Object lock = new Object();
    private volatile boolean paused;
    private volatile boolean cancelled;
    private volatile JavaDownloader active;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(42, notification("等待下载"));
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DsuManager:RomDownload");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_RESUME : intent.getAction();
        if (ACTION_START.equals(action)) {
            String address = intent.getStringExtra(EXTRA_ADDRESS);
            String output = intent.getStringExtra(EXTRA_OUTPUT);
            if (address != null && output != null) {
                boolean downgrade = intent.getBooleanExtra(EXTRA_DOWNGRADE, false);
                boolean oplus = intent.getBooleanExtra(EXTRA_OPLUS, false);
                getSharedPreferences("rom_download", MODE_PRIVATE).edit()
                        .putString("address", address)
                        .putString("output", output)
                        .putString("package", intent.getStringExtra(EXTRA_PACKAGE) == null
                                ? "下载" : intent.getStringExtra(EXTRA_PACKAGE))
                        .putBoolean("downgrade", downgrade)
                        .putBoolean("oplus", oplus)
                        .putBoolean("paused", false)
                        .apply();
                 startWorker(address, new File(output), downgrade, oplus);
            }
        } else if (ACTION_PAUSE.equals(action)) {
            paused = true;
            JavaDownloader downloader = active;
            if (downloader != null) downloader.pause();
            broadcast("已暂停，点击继续可断点续传", -1, -1);
        } else if (ACTION_RESUME.equals(action)) {
            if (cancelled) return START_NOT_STICKY;
            paused = false;
            synchronized (lock) { lock.notifyAll(); }
            startSavedWorker();
        } else if (ACTION_CANCEL.equals(action)) {
            cancelled = true;
            paused = false;
            getSharedPreferences("rom_download", MODE_PRIVATE).edit().clear().apply();
            JavaDownloader downloader = active;
            if (downloader != null) downloader.cancel();
            synchronized (lock) { lock.notifyAll(); }
            Thread currentWorker = worker;
            if (currentWorker != null) currentWorker.interrupt();
            // Let runDownload finish cleanup before stopping the foreground service.
            return START_NOT_STICKY;
        } else if (ACTION_QUERY.equals(action)) {
            broadcastSavedState();
        }
        return START_STICKY;
    }

    private void startSavedWorker() {
        android.content.SharedPreferences prefs = getSharedPreferences("rom_download", MODE_PRIVATE);
        String address = prefs.getString("address", "");
        String output = prefs.getString("output", "");
        boolean downgrade = prefs.getBoolean("downgrade", false);
        boolean oplus = prefs.getBoolean("oplus", false);
        if (!address.isEmpty() && !output.isEmpty()) {
            paused = false;
            startWorker(address, new File(output), downgrade, oplus);
        }
    }

    private void startWorker(String address, File output, boolean downgrade, boolean oplus) {
        synchronized (lock) {
            if (worker != null && worker.isAlive()) {
                paused = false;
                lock.notifyAll();
                return;
            }
            cancelled = false;
            paused = false;
            worker = new Thread(() -> runDownload(address, output, downgrade, oplus), "dsu-download-service");
            worker.start();
        }
    }

    private void runDownload(String address, File output, boolean downgrade, boolean oplus) {
        if (address == null || !address.matches("https?://.+/.+(?:[?#].*)?")) {
            broadcast("下载地址无效，缺少完整 ROM 文件名", output.length(), -1);
            stopSelf();
            return;
        }
        broadcast("正在下载: " + output.getName(), output.length(), -1);
        while (!cancelled) {
            synchronized (lock) {
                while (paused && !cancelled) {
                    try { lock.wait(); } catch (InterruptedException ignored) { }
                }
            }
            if (cancelled) break;
            final boolean[] completed = {false};
            final boolean[] failed = {false};
            JavaDownloader downloader = new JavaDownloader(address, output, downgrade, oplus, new JavaDownloader.Listener() {
                @Override public void onState(String text) { broadcast(text, -1, -1); }
                @Override public void onProgress(long done, long total) { broadcast(null, done, total); }
                @Override public void onError(String message) {
                    failed[0] = true;
                    paused = true;
                    broadcast("下载失败: " + message, -1, -1);
                }
                @Override public void onComplete(File completedFile) {
                    DownloadService.this.active = null;
                    completed[0] = true;
                    JavaDownloader.deleteCheckpointFiles(completedFile);
                    broadcast("下载完成", completedFile.length(), completedFile.length());
                    getSharedPreferences("rom_download", MODE_PRIVATE).edit()
                            .remove("address").remove("output").remove("package")
                            .remove("paused").remove("message").remove("done").remove("total")
                            .apply();
                }
            });
            active = downloader;
            downloader.run();
            active = null;
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
            if (completed[0]) break;
            break;
        }
        if (cancelled) {
            JavaDownloader.deletePartialFiles(output);
            if (output.isFile() && !output.delete()) output.deleteOnExit();
        }
        stopSelf();
    }

    private void broadcast(String state, long done, long total) {
        android.content.SharedPreferences.Editor editor = getSharedPreferences("rom_download", MODE_PRIVATE).edit();
        if (state != null) editor.putString("message", state);
        if (done >= 0) editor.putLong("done", done);
        if (total >= 0) editor.putLong("total", total);
        editor.putBoolean("paused", paused).apply();
        Intent update = new Intent(ACTION_UPDATE).setPackage(getPackageName());
        if (state != null) update.putExtra(EXTRA_STATE, state);
        update.putExtra(EXTRA_DONE, done);
        update.putExtra(EXTRA_TOTAL, total);
        sendBroadcast(update);
        if (state != null) getSystemService(NotificationManager.class).notify(42, notification(state));
    }

    private void broadcastSavedState() {
        android.content.SharedPreferences prefs = getSharedPreferences("rom_download", MODE_PRIVATE);
        String output = prefs.getString("output", "");
        if (output.isEmpty()) return;
        long done = prefs.getLong("done", new File(output).isFile() ? new File(output).length() : 0);
        long total = prefs.getLong("total", -1);
        String state = prefs.getString("message", "正在下载");
        Intent update = new Intent(ACTION_UPDATE).setPackage(getPackageName())
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_DONE, done)
                .putExtra(EXTRA_TOTAL, total);
        sendBroadcast(update);
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, RomActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 42, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("Dsu 管理器下载")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "ROM 下载", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        // Keep the foreground download alive when the app task is dismissed.
        super.onTaskRemoved(rootIntent);
    }
}
