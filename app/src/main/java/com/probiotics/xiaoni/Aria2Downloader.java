package com.probiotics.xiaoni;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * aria2c 高速多线程下载器 —— 本应用唯一的下载后端。
 *
 * - 引擎：内置 libaria2c.so（Aria2c.candidates 探测：nativeLibraryDir / APK 解包 / root PATH）
 * - 多线程：默认 16 线程 + 8MB 分片（--max-connection-per-server / --split / --min-split-size）
 * - 断点续传：暂停即结束进程，.aria2 控制文件保留，--continue=true 后续恢复
 * - 进度/速度/剩余时间：解析 aria2c summary 行
 *   例：[#c961f1 1.2GiB/4.8GiB(25%) CN:16 DL:12MiB ETA:5m]
 */
public final class Aria2Downloader {

    /** 兼容旧下载器的回调接口（onSpeed 为新增，用于通知栏速度/剩余时间展示） */
    public interface Listener {
        void onState(String text);
        void onProgress(long done, long total);
        default void onSpeed(long bytesPerSecond, long etaSeconds) { }
        void onError(String message);
        void onComplete(File output);
    }

    private static final int DEFAULT_THREADS = 16;
    private static final long DEFAULT_CHUNK_MB = 8;
    private static final String USER_AGENT =
            "Dalvik/2.1.0 (Linux; U; Android 16; PMA120 Build/BP2A.250605.015)";
    // 进度长时间无变化视为线路假死，及时报错让上层换节点重试
    private static final long STALL_TIMEOUT_MS = 90_000L;

    private static final Pattern PROGRESS_PATTERN = Pattern.compile(
            "([0-9.]+)(B|KiB|MiB|GiB|TiB)\\s*/\\s*([0-9.]+)(B|KiB|MiB|GiB|TiB)\\(([0-9]{1,3})%\\)");
    private static final Pattern SPEED_PATTERN = Pattern.compile(
            "DL:\\s*([0-9.]+)\\s*(B|KiB|MiB|GiB|TiB)");
    private static final Pattern ETA_PATTERN = Pattern.compile("ETA:([0-9hms]+)");
    private static final Pattern PERCENT_ONLY = Pattern.compile("\\(([0-9]{1,3})%\\)");

    private final Context context;
    private final String url;
    private final File output;
    private final Listener listener;
    private final int threads;
    private final long chunkMB;

    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private volatile Process process;

    public Aria2Downloader(Context context, String url, File output, Listener listener) {
        this(context, url, output, listener, DEFAULT_THREADS, DEFAULT_CHUNK_MB);
    }

    public Aria2Downloader(Context context, String url, File output, Listener listener,
                           int threads, long chunkMB) {
        this.context = context.getApplicationContext();
        this.url = url;
        this.output = output;
        this.listener = listener;
        this.threads = Math.max(1, threads);
        this.chunkMB = Math.max(1, chunkMB);
    }

    public void pause() {
        paused.set(true);
        destroyProcess(false);
    }

    public void cancel() {
        cancelled.set(true);
        paused.set(false);
        destroyProcess(false);
    }

    public boolean isFinished() { return finished.get(); }

    public void run() {
        finished.set(false);
        try {
            // v3.8.6：状态文案不再拼接文件名 —— 下载管理/查询页卡片会原样显示状态行，
            // 拼上文件名会挤在一行被截断（如「PMA120_xxx.zip 准备下...」），
            // 文件名已由标题行「正在下载: xxx」展示
            listener.onState("正在准备下载");
            File dir = output.getParentFile();
            if (dir != null && !dir.isDirectory()) runCatching(() -> dir.mkdirs());

            List<String> binaries = resolveBinaries();
            if (binaries.isEmpty()) {
                listener.onError("未找到可用的 aria2c 下载引擎（内置组件缺失）");
                return;
            }

            boolean useRoot = false;
            if (dir != null && !isAppWritable(dir)) {
                useRoot = RootShell.INSTANCE.available();
                if (!useRoot) {
                    listener.onError("无权写入下载目录，请授予“所有文件访问”权限后重试");
                    return;
                }
            }

            while (!cancelled.get()) {
                if (paused.get()) {
                    listener.onState("已暂停，点击继续可断点续传");
                    return;
                }
                ExitResult result = runOnce(binaries.get(0), useRoot);
                if (result.completed) {
                    listener.onProgress(output.length(), output.length());
                    listener.onComplete(output);
                    return;
                }
                if (cancelled.get()) {
                    deleteCancelledDownload(output);
                    return;
                }
                if (paused.get()) {
                    listener.onState("已暂停，点击继续可断点续传");
                    return;
                }
                if (result.cancelled) return;
                // 直跑失败且尚未尝试过 root：换 root 身份重试一次（公共目录权限不足场景）
                if (!useRoot && result.maybePermission && RootShell.INSTANCE.available()) {
                    useRoot = true;
                    listener.onState("切换 root 身份重试下载");
                    continue;
                }
                listener.onError(result.message);
                return;
            }
            if (cancelled.get()) deleteCancelledDownload(output);
        } catch (Exception error) {
            if (cancelled.get()) {
                deleteCancelledDownload(output);
            } else if (!paused.get()) {
                listener.onError(shortMessage(error));
            }
        } finally {
            finished.set(true);
        }
    }

    private static final class ExitResult {
        boolean completed;
        boolean cancelled;
        boolean maybePermission;
        String message = "下载失败";
    }

    private List<String> resolveBinaries() {
        List<String> list = new ArrayList<>();
        runCatchingAdd(list, Aria2c.INSTANCE.candidates(context));
        if (list.isEmpty()) {
            // 兜底：直接探测 nativeLibraryDir（Aria2c.candidates 内部已覆盖，此处防御性保留）
            try {
                File so = new File(context.getApplicationInfo().nativeLibraryDir, "libaria2c.so");
                if (so.isFile()) list.add(so.getAbsolutePath());
            } catch (Throwable ignored) { }
        }
        return list;
    }

    private ExitResult runOnce(String binary, boolean useRoot) {
        ExitResult result = new ExitResult();
        String cleanUrl = url.replaceAll("\\s+", "");
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            result.message = "下载地址无效：" + cleanUrl;
            return result;
        }
        File dir = output.getParentFile();
        if (dir == null) {
            result.message = "无效的保存路径";
            return result;
        }

        List<String> args = buildArgs(cleanUrl, dir);
        Process proc;
        try {
            if (useRoot) {
                proc = new ProcessBuilder("su", "-c", shellCommand(binary, args))
                        .redirectErrorStream(true).start();
            } else {
                List<String> command = new ArrayList<>();
                command.add(binary);
                command.addAll(args);
                proc = new ProcessBuilder(command)
                        .redirectErrorStream(true).start();
            }
        } catch (Exception error) {
            result.message = "启动 aria2c 失败: " + shortMessage(error);
            return result;
        }
        process = proc;

        final AtomicLong lastBytes = new AtomicLong(-1);
        final AtomicLong lastTotal = new AtomicLong(-1);
        final AtomicLong lastProgressAt = new AtomicLong(System.currentTimeMillis());
        final AtomicReference<String> lastError = new AtomicReference<>(null);

        Thread reader = new Thread(() -> {
            try {
                BufferedReader in = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                String line;
                while ((line = in.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    if (line.contains("[ERROR]") || line.contains("errorCode=")) {
                        lastError.set(line);
                    }
                    parseSummary(line, lastBytes, lastTotal, lastProgressAt);
                }
            } catch (Throwable ignored) { }
        }, "aria2-reader");
        reader.setDaemon(true);
        reader.start();

        try {
            while (true) {
                if (proc.waitFor(1, TimeUnit.SECONDS)) break;
                if (cancelled.get() || paused.get()) {
                    destroyProcess(true);
                    reader.join(1500);
                    result.cancelled = cancelled.get();
                    if (paused.get() && !cancelled.get()) return result;
                    if (cancelled.get()) return result;
                    break;
                }
                if (System.currentTimeMillis() - lastProgressAt.get() > STALL_TIMEOUT_MS) {
                    destroyProcess(true);
                    reader.join(1500);
                    result.message = "网络无进展（" + (STALL_TIMEOUT_MS / 1000)
                            + " 秒无数据），请更换下载节点后重试";
                    return result;
                }
            }
            reader.join(2000);
            int code;
            try { code = proc.exitValue(); } catch (IllegalThreadStateException e) { code = -1; }

            if (code == 0 && output.isFile() && output.length() > 0) {
                runCatchingFileDelete(controlFile(output));
                result.completed = true;
                return result;
            }
            if (paused.get() || cancelled.get()) {
                result.cancelled = cancelled.get();
                return result;
            }
            String detail = lastError.get();
            result.maybePermission = detail != null && (detail.contains("Permission denied")
                    || detail.contains("Cannot open file")
                    || detail.contains("No such file or directory"));
            result.message = humanizeAria2Error(detail, code, output);
            return result;
        } catch (InterruptedException e) {
            destroyProcess(true);
            result.cancelled = cancelled.get();
            return result;
        }
    }

    /** 解析 summary 行：已下载/总大小/速度/ETA，实时回调 */
    private void parseSummary(String line, AtomicLong lastBytes, AtomicLong lastTotal,
                              AtomicLong lastProgressAt) {
        long done = -1;
        long total = -1;
        Matcher m = PROGRESS_PATTERN.matcher(line);
        if (m.find()) {
            done = parseBytes(m.group(1), m.group(2));
            total = parseBytes(m.group(3), m.group(4));
        }
        if (done < 0) {
            // 无 Content-Length 的回退：只有百分比
            Matcher p = PERCENT_ONLY.matcher(line);
            if (p.find()) done = -2;
        }
        if (done >= 0 && done != lastBytes.get()) {
            lastBytes.set(done);
            lastProgressAt.set(System.currentTimeMillis());
            if (total < 0) total = lastTotal.get();
            if (total >= 0) lastTotal.set(total);
            if (done > 0 || total > 0) listener.onProgress(done, total);
        }
        long speed = -1;
        long eta = -1;
        Matcher sm = SPEED_PATTERN.matcher(line);
        if (sm.find()) speed = parseBytes(sm.group(1), sm.group(2));
        Matcher em = ETA_PATTERN.matcher(line);
        if (em.find()) eta = parseEta(em.group(1));
        if (speed >= 0 || eta >= 0) listener.onSpeed(Math.max(0, speed), eta);
    }

    private List<String> buildArgs(String cleanUrl, File dir) {
        List<String> args = new ArrayList<>();
        args.add("--no-conf=true");
        args.add("--allow-overwrite=true");
        args.add("--auto-file-renaming=false");
        args.add("--continue=true");
        args.add("--max-connection-per-server=" + threads);
        args.add("--split=" + threads);
        args.add("--min-split-size=" + chunkMB + "M");
        args.add("--file-allocation=none");
        args.add("--summary-interval=1");
        args.add("--connect-timeout=30");
        args.add("--timeout=60");
        args.add("--max-tries=10");
        args.add("--retry-wait=3");
        args.add("--disable-ipv6=true");
        args.add("--check-certificate=false");
        args.add("--user-agent=" + USER_AGENT);
        // 节点 Referer 规则：cdnorg/阿里云需 miui referer，vivo 需自家 referer
        String referer = refererFor(cleanUrl);
        if (referer != null) args.add("--referer=" + referer);
        args.add("--dir=" + dir.getAbsolutePath());
        args.add("--out=" + output.getName());
        args.add(cleanUrl);
        return args;
    }

    private static String refererFor(String url) {
        if (url.contains("cdnorg.d.miui.com") || url.contains("aliyuncs.com")) {
            return "https://www.miui.com/";
        }
        if (url.contains("vivo.com.cn")) {
            return "https://www.vivo.com.cn/";
        }
        return null;
    }

    private static String humanizeAria2Error(String detail, int code, File output) {
        if (detail != null) {
            String d = detail
                    .replaceFirst("^\\[?\\d{4}-\\d{2}-\\d{2} [\\d:.]+\\]?\\s*", "")
                    .replaceFirst("\\[ERROR\\] \\[[^\\]]+\\]\\s*", "")
                    .trim();
            if (d.contains("errorCode=3") || d.contains("Resource not found")) {
                return "资源不存在（404），下载链接可能已过期，请重新查询";
            }
            if (d.contains("errorCode=22") || d.contains("HTTP response header was bad")) {
                return "服务器响应异常，请更换下载节点后重试";
            }
            if (d.contains("Permission denied")) {
                return "无法写入存储，请授予“所有文件访问”权限后重试";
            }
            if (d.length() > 0 && d.length() < 200) return d;
        }
        return "aria2c 退出（code=" + code + "）：" + (output.isFile()
                ? "已下载 " + formatBytes(output.length()) : "未产生下载数据");
    }

    private void destroyProcess(boolean force) {
        Process proc = process;
        if (proc != null) {
            try {
                // SIGTERM 让 aria2c 优雅退出并保存 .aria2 控制文件（断点续传）
                proc.destroy();
                if (!proc.waitFor(3, TimeUnit.SECONDS) && force) {
                    proc.destroyForcibly();
                }
            } catch (Throwable ignored) { }
        }
    }

    // ---------- 静态工具（供 Activity 恢复/清理进度） ----------

    /** aria2c 控制文件（断点续传信息） */
    public static File controlFile(File output) {
        return new File(output.getParentFile(), output.getName() + ".aria2");
    }

    /** 清理断点续传文件（aria2c .aria2 + 旧下载器 .download/.ctrl） */
    public static void deleteCheckpointFiles(File output) {
        File parent = output.getParentFile() != null ? output.getParentFile() : new File(".");
        deleteFile(new File(parent, output.getName() + ".aria2"));
        deleteFile(new File(parent, output.getName() + ".download"));
        deleteFile(new File(parent, output.getName() + ".ctrl"));
        deleteFile(new File(parent, output.getName() + ".ctrl.tmp"));
    }

    /** 取消下载：删除断点文件与未完成的目标文件 */
    public static void deleteCancelledDownload(File output) {
        deleteCheckpointFiles(output);
        deleteFile(output);
    }

    /** 读取已保存进度：aria2c 直接写入目标文件，文件长度即已下载量 */
    public static long[] readSavedProgress(File output) {
        if (output.isFile()) return new long[]{output.length(), -1};
        File control = controlFile(output);
        if (control.isFile()) return new long[]{0, -1};
        return new long[]{-1, -1};
    }

    private static void deleteFile(File file) {
        if (file.exists() && !file.delete()) file.deleteOnExit();
    }

    private static void runCatchingAdd(List<String> target, List<String> source) {
        try { if (source != null) target.addAll(source); } catch (Throwable ignored) { }
    }

    private static boolean isAppWritable(File dir) {
        if (dir.isDirectory() && dir.canWrite()) return true;
        if (!dir.exists()) runCatching(() -> dir.mkdirs());
        return dir.isDirectory() && dir.canWrite();
    }

    private static void runCatching(Runnable r) {
        try { r.run(); } catch (Throwable ignored) { }
    }

    private static void runCatchingFileDelete(File f) {
        try { if (f.isFile()) f.delete(); } catch (Throwable ignored) { }
    }

    private static String shellCommand(String binary, List<String> args) {
        StringBuilder sb = new StringBuilder();
        sb.append('"').append(binary).append('"');
        for (String arg : args) {
            sb.append(" '").append(arg.replace("'", "'\\''")).append("'");
        }
        return sb.toString();
    }

    private static long parseBytes(String value, String unit) {
        try {
            double v = Double.parseDouble(value);
            switch (unit) {
                case "KiB": return (long) (v * 1024);
                case "MiB": return (long) (v * 1048576);
                case "GiB": return (long) (v * 1073741824);
                case "TiB": return (long) (v * 1099511627776L);
                default: return (long) v;
            }
        } catch (Exception e) {
            return -1;
        }
    }

    /** 解析 aria2c ETA（如 "20s"、"5m"、"1h40m"）为秒 */
    private static long parseEta(String eta) {
        long seconds = 0;
        Matcher m = Pattern.compile("(\\d+)(h|m|s)").matcher(eta);
        boolean found = false;
        while (m.find()) {
            found = true;
            long v = Long.parseLong(m.group(1));
            switch (m.group(2)) {
                case "h": seconds += v * 3600; break;
                case "m": seconds += v * 60; break;
                default: seconds += v; break;
            }
        }
        return found ? seconds : -1;
    }

    private static String shortMessage(Exception error) {
        Throwable root = error;
        while (root.getCause() != null) root = root.getCause();
        String message = root.getMessage();
        if (message == null || message.trim().isEmpty()) return "网络或文件读写失败";
        return message.replaceAll("\\s+", " ").trim();
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.2f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.2f MB", bytes / 1048576.0);
        return String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0);
    }

    /** 秒数格式化为剩余时间（如 "3分20秒"、"1小时12分"） */
    public static String formatEta(long seconds) {
        if (seconds < 0) return "计算中";
        if (seconds < 60) return seconds + "秒";
        if (seconds < 3600) return (seconds / 60) + "分" + (seconds % 60) + "秒";
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        return h + "小时" + (m > 0 ? m + "分" : "");
    }
}
