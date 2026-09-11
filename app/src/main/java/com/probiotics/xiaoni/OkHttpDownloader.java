package com.probiotics.xiaoni;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class OkHttpDownloader {
    
    private static final int DEFAULT_THREADS = 16;
    private static final long DEFAULT_CHUNK_SIZE = 8 * 1024 * 1024; // 8MB
    private static final int BUFFER_SIZE = 64 * 1024; // 64KB
    
    private final int threads;
    private final long chunkSize;
    private final String url;
    private final File output;
    private final JavaDownloader.Listener listener;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicLong downloaded = new AtomicLong(0);
    private long totalSize = 0;
    private ExecutorService executor;
    private OkHttpClient client;
    private RandomAccessFile sharedFile;
    
    public OkHttpDownloader(String url, File output, JavaDownloader.Listener listener) {
        this(url, output, listener, DEFAULT_THREADS, DEFAULT_CHUNK_SIZE);
    }
    
    public OkHttpDownloader(String url, File output, JavaDownloader.Listener listener, int threads, long chunkSizeMB) {
        this.url = url;
        this.output = output;
        this.listener = listener;
        this.threads = threads;
        this.chunkSize = chunkSizeMB * 1024 * 1024; // 转换为字节
        android.util.Log.d("OkHttpDownloader", "配置: threads=" + threads + ", chunkSizeMB=" + chunkSizeMB + ", chunkSize=" + this.chunkSize);
    }
    
    public void pause() {
        paused.set(true);
    }
    
    public void resume() {
        paused.set(false);
    }
    
    public void cancel() {
        cancelled.set(true);
        if (executor != null) executor.shutdownNow();
    }
    
    public void run() {
        try {
            listener.onState("OkHttp下载器启动中...");
            
            okhttp3.ConnectionPool connectionPool = new okhttp3.ConnectionPool(threads * 2, 5, TimeUnit.MINUTES);
            
            client = new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .writeTimeout(120, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .connectionPool(connectionPool)
                    .build();
            
            // 获取文件大小
            Request.Builder headBuilder = new Request.Builder()
                    .url(url)
                    .head()
                    .addHeader("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 16; PMA120 Build/BP2A.250605.015)")
                    .addHeader("Accept", "*/*")
                    .addHeader("Accept-Encoding", "identity");
            
            // 只有cdnorg和阿里云需要Referer，bigota/hugeota不需要
            if (url.contains("cdnorg.d.miui.com") || url.contains("aliyuncs.com")) {
                headBuilder.addHeader("Referer", "https://www.miui.com/");
            }
            // vivo需要Referer
            if (url.contains("vivo.com.cn")) {
                headBuilder.addHeader("Referer", "https://www.vivo.com.cn/");
            }
            
            Request headRequest = headBuilder.build();
            
            listener.onState("正在获取文件信息...");
            
            try (Response headResponse = client.newCall(headRequest).execute()) {
                if (!headResponse.isSuccessful()) {
                    listener.onError("服务器返回错误: " + headResponse.code());
                    return;
                }
                
                String lengthStr = headResponse.header("Content-Length");
                if (lengthStr != null) {
                    totalSize = Long.parseLong(lengthStr);
                }
                
                String acceptRanges = headResponse.header("Accept-Ranges");
                boolean supportsRange = "bytes".equalsIgnoreCase(acceptRanges);
                
                if (!supportsRange || totalSize <= 0) {
                    listener.onState("服务器不支持断点续传，使用单线程下载");
                    downloadSingleThread();
                    return;
                }
            }
            
            String configMsg = String.format("文件大小: %s，配置: %d线程 × %dMB分片", 
                formatBytes(totalSize), threads, chunkSize / (1024 * 1024));
            listener.onState(configMsg);
            android.util.Log.d("OkHttpDownloader", configMsg);
            listener.onProgress(0, totalSize);
            
            long startTime = System.currentTimeMillis();
            
            // 准备文件
            sharedFile = new RandomAccessFile(output, "rw");
            sharedFile.setLength(totalSize);
            
            // 计算分片：使用配置的分片大小，让所有线程同时工作
            int totalChunks = (int)((totalSize + chunkSize - 1) / chunkSize);
            
            listener.onState(String.format("分片下载: %d 个分片，%d 线程并发", totalChunks, threads));
            
            executor = Executors.newFixedThreadPool(threads);
            
            for (long start = 0; start < totalSize && !cancelled.get(); start += chunkSize) {
                long end = Math.min(start + chunkSize - 1, totalSize - 1);
                final long finalStart = start;
                final long finalEnd = end;
                
                executor.submit(() -> downloadChunk(finalStart, finalEnd));
            }
            
            executor.shutdown();
            
            // 等待完成并更新进度
            long lastUpdate = System.currentTimeMillis();
            long lastBytes = 0;
            while (!executor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                if (cancelled.get()) {
                    executor.shutdownNow();
                    try { sharedFile.close(); } catch (Exception ignored) {}
                    listener.onError("下载已取消");
                    return;
                }
                long now = System.currentTimeMillis();
                long currentBytes = downloaded.get();
                if (now - lastUpdate >= 2000) {
                    long speed = (currentBytes - lastBytes) / ((now - lastUpdate) / 1000);
                    listener.onState("速度: " + formatBytes(speed) + "/s, 已下载: " + formatBytes(currentBytes));
                    lastUpdate = now;
                    lastBytes = currentBytes;
                }
                listener.onProgress(currentBytes, totalSize);
            }
            
            try { sharedFile.close(); } catch (Exception ignored) {}
            
            if (downloaded.get() >= totalSize) {
                listener.onComplete(output);
            } else {
                listener.onError("下载未完成");
            }
            
        } catch (Exception e) {
            listener.onError("OkHttp错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void downloadChunk(long start, long end) {
        try {
            Request.Builder requestBuilder = new Request.Builder()
                    .url(url)
                    .addHeader("Range", "bytes=" + start + "-" + end)
                    .addHeader("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 16; PMA120 Build/BP2A.250605.015)")
                    .addHeader("Accept", "*/*")
                    .addHeader("Accept-Encoding", "identity");
            
            // 只有cdnorg和阿里云需要Referer，bigota/hugeota不需要
            if (url.contains("cdnorg.d.miui.com") || url.contains("aliyuncs.com")) {
                requestBuilder.addHeader("Referer", "https://www.miui.com/");
            }
            // vivo需要Referer
            if (url.contains("vivo.com.cn")) {
                requestBuilder.addHeader("Referer", "https://www.vivo.com.cn/");
            }
            
            Request request = requestBuilder.build();
            
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() && response.code() != 206) {
                    return;
                }
                
                ResponseBody body = response.body();
                if (body == null) return;
                
                try (InputStream in = body.byteStream()) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int read;
                    long position = start;
                    while ((read = in.read(buffer)) != -1 && !cancelled.get()) {
                        synchronized (sharedFile) {
                            sharedFile.seek(position);
                            sharedFile.write(buffer, 0, read);
                        }
                        position += read;
                        downloaded.addAndGet(read);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private void downloadSingleThread() throws IOException {
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 16; PMA120 Build/BP2A.250605.015)")
                .addHeader("Accept", "*/*")
                .addHeader("Accept-Encoding", "identity");
        
        // 只有cdnorg和阿里云需要Referer，bigota/hugeota不需要
        if (url.contains("cdnorg.d.miui.com") || url.contains("aliyuncs.com")) {
            requestBuilder.addHeader("Referer", "https://www.miui.com/");
        }
        // vivo需要Referer
        if (url.contains("vivo.com.cn")) {
            requestBuilder.addHeader("Referer", "https://www.vivo.com.cn/");
        }
        
        Request request = requestBuilder.build();
        
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                listener.onError("服务器返回错误: " + response.code());
                return;
            }
            
            ResponseBody body = response.body();
            if (body == null) {
                listener.onError("响应体为空");
                return;
            }
            
            try (InputStream in = body.byteStream();
                 RandomAccessFile raf = new RandomAccessFile(output, "rw")) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1 && !cancelled.get()) {
                    raf.write(buffer, 0, read);
                    downloaded.addAndGet(read);
                    if (totalSize > 0) {
                        listener.onProgress(downloaded.get(), totalSize);
                    }
                }
            }
            
            if (downloaded.get() > 0) {
                listener.onComplete(output);
            }
        }
    }
    
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
