package com.probiotics.xiaoni;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 纯 Java 多线程 HTTP Range 下载器，不依赖 root 或外部二进制。
 * 支持暂停/继续（断点续传）与取消。分片游标持久化到 .ctrl 文件，可跨运行续传。
 */
public final class JavaDownloader {

    private static final String USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android 16; PMA120 Build/BP2A.250605.015)";

    public interface Listener {
        void onState(String text);
        void onProgress(long done, long total);
        void onError(String message);
        void onComplete(File output);
    }

    // A larger segment reduces TLS/request churn while workers keep the link full.
    // 参考aria2高性能配置
    private static final int XIAOMI_CONNECTIONS = 16;  // aria2默认16线程
    private static final int OPLUS_CONNECTIONS = 4;
    private static final int OPLUS_DOWNGRADE_CONNECTIONS = 16;
    private static final long XIAOMI_SEGMENT_BYTES = 1L * 1024 * 1024;  // 1MB min-split-size
    private static final long OPLUS_SEGMENT_BYTES = 16L * 1024 * 1024;
    private static final long OPLUS_DOWNGRADE_SEGMENT_BYTES = 8L * 1024 * 1024;
    private static final int MAX_SEGMENTS = 2048;
    private static final int BUFFER_BYTES = 1024 * 1024;  // 1MB buffer - aria2 -k1M
    private static final int CONNECT_TIMEOUT = 30000;  // 30秒连接超时
    private static final int READ_TIMEOUT = 60000;  // 60秒读取超时

    private final String address;
    private final File output;
    private final File partial;
    private final File control;
    private final boolean downgrade;
    private final int connectionsLimit;
    private final long segmentBytes;
    private final Listener listener;

    private volatile boolean paused;
    private volatile boolean cancelled;
    private volatile boolean rangeSupported;
    private final AtomicLong done = new AtomicLong();
    private volatile long total = -1;
    private volatile HttpURLConnection activeConnection;
    private final Set<HttpURLConnection> connections = Collections.synchronizedSet(new HashSet<>());
    private volatile boolean finished;

    private long[] segStart;
    private long[] segEnd;
    private AtomicLongArray segCursor;
    private int segCount;
    private int segPointer;
    private volatile Exception segFatal;
    private Thread reporter;

    public JavaDownloader(String address, File output, Listener listener) {
        this(address, output, false, false, listener);
    }

    public JavaDownloader(String address, File output, boolean downgrade, Listener listener) {
        this(address, output, downgrade, false, listener);
    }

    public JavaDownloader(String address, File output, boolean downgrade, boolean oplus, Listener listener) {
        this(address, output, downgrade, oplus, -1, listener);
    }

    public JavaDownloader(String address, File output, boolean downgrade, boolean oplus, int customThreads, Listener listener) {
        this.address = address;
        this.output = output;
        this.downgrade = downgrade;
        
        // 如果提供了自定义线程数，使用自定义值；否则使用默认配置
        if (customThreads > 0) {
            this.connectionsLimit = customThreads;
        } else {
            this.connectionsLimit = oplus
                    ? (downgrade ? OPLUS_DOWNGRADE_CONNECTIONS : OPLUS_CONNECTIONS)
                    : XIAOMI_CONNECTIONS;
        }
        
        this.segmentBytes = oplus
                ? (downgrade ? OPLUS_DOWNGRADE_SEGMENT_BYTES : OPLUS_SEGMENT_BYTES)
                : XIAOMI_SEGMENT_BYTES;
        this.listener = listener;
        File parent = output.getParentFile() != null ? output.getParentFile() : new File(".");
        this.partial = new File(parent, output.getName() + ".download");
        this.control = new File(parent, output.getName() + ".ctrl");
    }

    public void pause() {
        paused = true;
        HttpURLConnection connection = activeConnection;
        if (connection != null) {
            try { connection.disconnect(); } catch (Exception ignored) { }
        }
        disconnectAllConnections();
    }

    public void cancel() {
        cancelled = true;
        paused = false;
        HttpURLConnection connection = activeConnection;
        if (connection != null) {
            try { connection.disconnect(); } catch (Exception ignored) { }
        }
        disconnectAllConnections();
    }

    public boolean isFinished() { return finished; }

    /** Returns saved partial bytes and total bytes, or negative values when unavailable. */
    public static long[] readSavedProgress(File output) {
        File parent = output.getParentFile() != null ? output.getParentFile() : new File(".");
        File control = new File(parent, output.getName() + ".ctrl");
        if (!control.isFile()) return new long[]{-1, -1};
        try (FileInputStream input = new FileInputStream(control);
             java.io.BufferedReader reader = new java.io.BufferedReader(
                     new java.io.InputStreamReader(input, "UTF-8"))) {
            String header = reader.readLine();
            if (header == null) return new long[]{-1, -1};
            int space = header.indexOf(' ');
            if (space <= 0) return new long[]{-1, -1};
            int count = Integer.parseInt(header.substring(0, space));
            long total = Long.parseLong(header.substring(space + 1).trim());
            long done = 0;
            if (count == 0) {
                String value = reader.readLine();
                done = value == null ? 0 : Long.parseLong(value.trim());
            } else {
                for (int i = 0; i < count; i++) {
                    String value = reader.readLine();
                    if (value == null) return new long[]{-1, -1};
                    long cursor = Long.parseLong(value.trim());
                    long start = total * i / count;
                    done += Math.max(0, cursor - start);
                }
            }
            return new long[]{Math.max(0, done), total};
        } catch (Exception ignored) {
            return new long[]{-1, -1};
        }
    }

    public void run() {
        try {
            probe();
            if (total < 0) throw new IOException("无法获取文件大小，请更换下载节点后重试");
            reporter = new Thread(this::reportLoop, "dsu-progress");
            reporter.setDaemon(true);
            reporter.start();
            if (rangeSupported) {
                listener.onState(output.getName() + " 下载中");
                initSegments();
                runSegmented();
            } else {
                listener.onState(output.getName() + " 下载中（单线程）");
                singleStream();
            }
            if (cancelled) {
                deletePartialFiles();
                return;
            }
            if (paused) {
                saveControl();
                listener.onState("已暂停，点击继续可断点续传");
                return;
            }
            long completedBytes = done.get();
            if (completedBytes < total) {
                throw new IOException("下载不完整: " + formatBytes(completedBytes) + " / " + formatBytes(total));
            }
            stopReporter();
            finishFile();
            listener.onComplete(output);
        } catch (Exception error) {
            if (cancelled) {
                deletePartialFiles();
            } else if (paused) {
                saveControl();
                listener.onState("已暂停，点击继续可断点续传");
            } else {
                saveControl();
                listener.onError(shortMessage(error));
            }
        } finally {
            finished = true;
            if (reporter != null) {
                try { reporter.join(500); } catch (InterruptedException ignored) { }
            }
            HttpURLConnection connection = activeConnection;
            if (connection != null) {
                try { connection.disconnect(); } catch (Exception ignored) { }
            }
            disconnectAllConnections();
            listener.onProgress(done.get(), total);
        }
    }

    private void initSegments() {
        int count = (int) Math.min(MAX_SEGMENTS, Math.max(1, (total + segmentBytes - 1) / segmentBytes));
        segCount = count;
        segStart = new long[count];
        segEnd = new long[count];
        segCursor = new AtomicLongArray(count);
        long resumed = 0;
        for (int i = 0; i < count; i++) {
            segStart[i] = total * i / count;
            segEnd[i] = (i == count - 1) ? total : total * (i + 1) / count;
            segCursor.set(i, segStart[i]);
        }
        long[] saved = readControl();
        if (saved != null && saved.length == count + 1 && saved[0] == total) {
            for (int i = 0; i < count; i++) {
                long cursor = saved[i + 1];
                if (cursor < segStart[i]) cursor = segStart[i];
                if (cursor > segEnd[i]) cursor = segEnd[i];
                segCursor.set(i, cursor);
                resumed += cursor - segStart[i];
            }
            done.set(resumed);
        } else {
            done.set(0);
        }
        if (done.get() > 0) listener.onProgress(done.get(), total);
    }

    private long[] readControl() {
        if (!control.isFile()) return null;
        FileInputStream input = null;
        try {
            input = new FileInputStream(control);
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(input, "UTF-8"));
            String header = reader.readLine();
            if (header == null) return null;
            int count;
            long fileSize;
            try {
                int space = header.indexOf(' ');
                if (space <= 0) return null;
                count = Integer.parseInt(header.substring(0, space));
                fileSize = Long.parseLong(header.substring(space + 1).trim());
            } catch (NumberFormatException bad) {
                return null;
            }
            long[] values = new long[count + 1];
            values[0] = fileSize;
            int index = 1;
            String line;
            while (index <= count && (line = reader.readLine()) != null) {
                try { values[index] = Long.parseLong(line.trim()); } catch (NumberFormatException bad) { return null; }
                index++;
            }
            if (index <= count) return null;
            return values;
        } catch (Exception error) {
            return null;
        } finally {
            if (input != null) { try { input.close(); } catch (IOException ignored) { } }
        }
    }

    private void runSegmented() throws IOException {
        preparePartialFile();
        int workers = Math.min(connectionsLimit, segCount);
        segPointer = 0;
        Thread[] threads = new Thread[workers];
        for (int w = 0; w < workers; w++) {
            threads[w] = new Thread(() -> {
                RandomAccessFile file = null;
                FileChannel channel = null;
                try {
                    file = new RandomAccessFile(partial, "rw");
                    channel = file.getChannel();
                    while (!cancelled && !paused) {
                        int index;
                        synchronized (this) {
                            index = nextIncompleteSegment();
                        }
                        if (index < 0) break;
                        try {
                            downloadSegment(channel, index);
                        } catch (Exception error) {
                            segFatal = error;
                            return;
                        }
                    }
                } catch (IOException io) {
                    segFatal = io;
                } finally {
                    if (channel != null) { try { channel.close(); } catch (IOException ignored) { } }
                    if (file != null) { try { file.close(); } catch (IOException ignored) { } }
                }
            }, "dsu-download-" + w);
            threads[w].start();
        }
        for (Thread thread : threads) {
            try { thread.join(); } catch (InterruptedException ignored) { }
        }
        Exception fatal = segFatal;
        if (fatal != null && !cancelled && !paused) {
            if (fatal instanceof IOException) throw (IOException) fatal;
            throw new IOException(fatal.getMessage(), fatal);
        }
    }

    private int nextIncompleteSegment() {
        while (segPointer < segCount) {
            int index = segPointer++;
            if (segCursor.get(index) < segEnd[index]) return index;
        }
        return -1;
    }

    private void downloadSegment(FileChannel channel, int index) throws IOException {
        long end = segEnd[index];
        int retries = 0;
        while (!cancelled && !paused) {
            long cursor;
            cursor = segCursor.get(index);
            if (cursor >= end) return;
            HttpURLConnection connection = null;
            try {
                    connection = openConnection(address, cursor, end - 1, true);
                activeConnection = connection;
                connections.add(connection);
                int code = connection.getResponseCode();
                if (code == HttpURLConnection.HTTP_PARTIAL) {
                    retries = 0;
                    try (InputStream input = connection.getInputStream()) {
                        byte[] bytes = new byte[BUFFER_BYTES];
                        int read;
                        while (!cancelled && !paused && (read = input.read(bytes)) != -1) {
                            ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, read);
                            long writePosition = cursor;
                            while (buffer.hasRemaining()) {
                                int written = channel.write(buffer, writePosition);
                                if (written <= 0) throw new IOException("存储写入停滞");
                                writePosition += written;
                            }
                            cursor += read;
                            segCursor.set(index, cursor);
                            done.addAndGet(read);
                        }
                        if (paused || cancelled) return;
                    }
                } else if (code == HttpURLConnection.HTTP_OK) {
                    throw new IOException("服务器不支持断点续传，请更换下载节点后重试");
                } else {
                    throw new IOException("服务器响应异常 HTTP " + code);
                }
            } catch (IOException error) {
                if (paused || cancelled) return;
                retries++;
                if (retries > 4) throw error;
                try { Thread.sleep(1500L * retries); } catch (InterruptedException interrupted) { return; }
            } finally {
                activeConnection = null;
                connections.remove(connection);
                if (connection != null) { try { connection.disconnect(); } catch (Exception ignored) { } }
            }
        }
    }

    private void preparePartialFile() throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(partial, "rw")) {
            if (raf.length() < total) raf.setLength(total);
        }
    }

    private void singleStream() throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(partial, "rw")) {
            long cursor = 0;
            done.set(0);
            int retries = 0;
            while (!cancelled && !paused) {
                HttpURLConnection connection = null;
                try {
                    connection = openConnection(address, cursor, -1, rangeSupported && cursor > 0);
                    activeConnection = connection;
                    connections.add(connection);
                    int code = connection.getResponseCode();
                    if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                        throw new IOException("服务器响应异常 HTTP " + code);
                    }
                    if (code == HttpURLConnection.HTTP_OK && cursor > 0) {
                        raf.setLength(0);
                        cursor = 0;
                        done.set(0);
                    }
                    retries = 0;
                    try (InputStream input = connection.getInputStream()) {
                        byte[] buffer = new byte[BUFFER_BYTES];
                        raf.seek(cursor);
                        int read;
                        while (!cancelled && !paused && (read = input.read(buffer)) != -1) {
                            raf.write(buffer, 0, read);
                            cursor += read;
                            done.set(cursor);
                        }
                    }
                    if (paused || cancelled) return;
                    if (done.get() < total) continue;
                    break;
                } catch (IOException error) {
                    if (paused || cancelled) return;
                    retries++;
                    if (retries > 4) throw error;
                    try { Thread.sleep(1500L * retries); } catch (InterruptedException interrupted) { return; }
                } finally {
                    activeConnection = null;
                    connections.remove(connection);
                    if (connection != null) { try { connection.disconnect(); } catch (Exception ignored) { } }
                }
            }
        }
    }

    private void reportLoop() {
        long lastReported = -1;
        long lastSaved = -1;
        long lastSaveTime = 0;
        while (!finished && !cancelled) {
            long current = done.get();
            if (current != lastReported) {
                lastReported = current;
                if (!paused) listener.onProgress(current, total);
            }
            long now = System.currentTimeMillis();
            if (current != lastSaved && now - lastSaveTime >= 2000) {
                saveControl();
                lastSaved = current;
                lastSaveTime = now;
            }
            try { Thread.sleep(250); } catch (InterruptedException ignored) { return; }
        }
    }

    private void probe() throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = openConnection(address, 0, 0, false);
            activeConnection = connection;
            connections.add(connection);
            int code;
            try {
                code = connection.getResponseCode();
            } catch (IOException error) {
                throw new IOException("探测下载地址超时或网络不可达: " + shortMessage(error), error);
            }
            if (code == HttpURLConnection.HTTP_OK) {
                String length = connection.getHeaderField("Content-Length");
                if (length != null && !length.trim().isEmpty()) {
                    long size = Long.parseLong(length.trim());
                    if (size > 0) {
                        total = size;
                        connection.disconnect();
                        connections.remove(connection);
                        connection = openConnection(address, 0, 0, true);
                        activeConnection = connection;
                        connections.add(connection);
                        int rangeCode = connection.getResponseCode();
                        rangeSupported = rangeCode == HttpURLConnection.HTTP_PARTIAL;
                        return;
                    }
                }
                throw new IOException("HTTP 200 缺少有效 Content-Length");
            }
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                rangeSupported = true;
                String range = connection.getHeaderField("Content-Range");
                if (range != null) {
                    int slash = range.lastIndexOf('/');
                    if (slash >= 0 && slash + 1 < range.length()) {
                        String value = range.substring(slash + 1).trim();
                        if (!value.isEmpty() && !"*".equals(value)) {
                            long size = Long.parseLong(value);
                            if (size > 0) { total = size; return; }
                        }
                    }
                }
                throw new IOException("HTTP 206 缺少有效 Content-Range");
            }
            if (code == HttpURLConnection.HTTP_FORBIDDEN) {
                connection.disconnect();
                connections.remove(connection);
                connection = openConnection(address, 0, 0, false);
                activeConnection = connection;
                connections.add(connection);
                code = connection.getResponseCode();
                if (code == HttpURLConnection.HTTP_FORBIDDEN) {
                    String host = new URL(address).getHost();
                    throw new IOException("服务器拒绝访问 HTTP 403 (" + host + ")。可能原因：下载链接已过期，请重新查询后再试");
                }
            }
            String location = connection.getHeaderField("Location");
            String detail = location == null || location.trim().isEmpty()
                    ? "" : ", Location=" + location;
            String host = new URL(address).getHost();
            throw new IOException("服务器响应异常 HTTP " + code + " (" + host + ")" + detail);
        } finally {
            activeConnection = null;
            connections.remove(connection);
            if (connection != null) { try { connection.disconnect(); } catch (Exception ignored) { } }
        }
    }

    private HttpURLConnection openConnection(String url, long from, long to, boolean useRange) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "*/*");
        connection.setRequestProperty("Accept-Encoding", "identity");  // 禁用压缩，提升速度
        connection.setRequestProperty("Connection", "keep-alive");  // 保持连接
        
        // 小米CDN需要Referer，否则403 (deny by referer access rule)
        if (url.contains("miui.com") || url.contains("aliyuncs.com")) {
            connection.setRequestProperty("Referer", "https://www.miui.com/");
        }
        
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(true);
        if (useRange) connection.setRequestProperty("Range", "bytes=" + from + "-" + (to < 0 ? "" : to));
        return connection;
    }

    private void disconnectAllConnections() {
        synchronized (connections) {
            for (HttpURLConnection connection : connections) {
                try { connection.disconnect(); } catch (Exception ignored) { }
            }
            connections.clear();
        }
    }

    private void saveControl() {
        if (segCursor == null) {
            saveHeader();
            return;
        }
        synchronized (this) {
            StringBuilder builder = new StringBuilder();
            builder.append(segCount).append(' ').append(total).append('\n');
            for (int i = 0; i < segCount; i++) builder.append(segCursor.get(i)).append('\n');
            writeControl(builder.toString());
        }
    }

    private void saveHeader() {
        StringBuilder builder = new StringBuilder();
        builder.append(0).append(' ').append(total).append('\n');
        builder.append(done.get()).append('\n');
        writeControl(builder.toString());
    }

    private void writeControl(String content) {
        FileOutputStream out = null;
        File temporary = new File(control.getParentFile(), control.getName() + ".tmp");
        try {
            out = new FileOutputStream(temporary);
            out.write(content.getBytes("UTF-8"));
            out.flush();
            out.getFD().sync();
            out.close();
            out = null;
            Files.move(temporary.toPath(), control.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        } finally {
            if (out != null) { try { out.close(); } catch (IOException ignored) { } }
        }
    }

    private void finishFile() throws IOException {
        if (!partial.isFile() || partial.length() < total) {
            throw new IOException("文件校验失败，请重试");
        }
        if (output.exists() && output.isFile() && !output.delete()) {
            throw new IOException("无法覆盖已存在的文件");
        }
        if (!partial.renameTo(output)) {
            try (InputStream input = new FileInputStream(partial); FileOutputStream out = new FileOutputStream(output)) {
                byte[] buffer = new byte[256 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) out.write(buffer, 0, read);
                out.flush();
                out.getFD().sync();
            }
            if (!partial.delete()) partial.deleteOnExit();
        }
        deleteCheckpointFiles(output);
    }

    private void deletePartialFiles() {
        deletePartialFiles(output);
    }

    private void stopReporter() {
        finished = true;
        if (reporter != null) {
            reporter.interrupt();
            try { reporter.join(1000); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static void deletePartialFiles(File output) {
        deleteCheckpointFiles(output);
    }

    public static void deleteCancelledDownload(File output) {
        deleteCheckpointFiles(output);
        deleteFile(output);
    }

    public static void deleteCheckpointFiles(File output) {
        File parent = output.getParentFile() != null ? output.getParentFile() : new File(".");
        File partial = new File(parent, output.getName() + ".download");
        File control = new File(parent, output.getName() + ".ctrl");
        File temporary = new File(parent, control.getName() + ".tmp");
        deleteFile(partial);
        deleteFile(control);
        deleteFile(temporary);
    }

    private static void deleteFile(File file) {
        if (file.exists() && !file.delete()) file.deleteOnExit();
    }

    private String shortMessage(Exception error) {
        Throwable root = error;
        while (root.getCause() != null) root = root.getCause();
        String message = root.getMessage();
        if (message == null || message.trim().isEmpty()) return "网络或文件读写失败，请检查下载节点或存储空间";
        if (message.contains("Permission denied") || message.contains("error=13")) return "无法写入存储，请授予“所有文件访问”权限后重试";
        if (message.contains("HTTP")) return message.trim();
        return message.replaceAll("\\s+", " ").trim();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }
}
