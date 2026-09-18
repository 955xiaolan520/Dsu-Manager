package com.probiotics.xiaoni;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.probiotics.xiaoni.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * v3.9.1 应用内更新中心（设置页 + APP 启动自动检查共用）。
 *
 * 网络修复（此前开/不开 VPN 更新下载都失败）：
 *  - GitHub 直连在国内经常不通（连接重置），VPN 未接管应用流量时同样失败；
 *  - 检查与下载均改为「直连 → 多个加速镜像」逐个回退，任一可用即成功；
 *  - 下载完成后校验 ZIP 头（PK），防止镜像返回的 HTML 错误页被当成 APK 安装。
 *
 * 更新体验：
 *  - APP 启动自动静默检查（每进程一次），有新版本弹窗：更新内容 + 立即更新 / 暂不更新；
 *  - 下载过程独立进度弹窗：进度条 / 百分比 / 已下载大小 / 速度，可随时取消；
 *  - 弹窗尺寸与文字行距经过调校（宽 92% 屏宽、说明区限高滚动、按钮 48dp），无文字挤压。
 */
public final class UpdateCenter {

    /** GitHub API 直连 + 加速镜像（前缀式，逐个回退） */
    private static final String[] API_URLS = {
            "https://api.github.com/repos/955xiaolan520/Dsu-Manager/releases/latest",
            "https://gh-proxy.com/https://api.github.com/repos/955xiaolan520/Dsu-Manager/releases/latest",
            "https://ghfast.top/https://api.github.com/repos/955xiaolan520/Dsu-Manager/releases/latest",
            "https://ghproxy.net/https://api.github.com/repos/955xiaolan520/Dsu-Manager/releases/latest",
    };
    /** APK 下载地址前缀：空串 = 直连，其余为加速镜像 */
    private static final String[] DL_PREFIXES = {
            "",
            "https://gh-proxy.com/",
            "https://ghfast.top/",
            "https://ghproxy.net/",
    };

    private static final String REPO_PAGE =
            "https://github.com/955xiaolan520/Dsu-Manager/releases";

    /** 本次进程是否已做过启动自动检查（避免每次 onResume 重复弹窗） */
    private static volatile boolean sAutoChecked;

    private UpdateCenter() { }

    // ---------- 数据模型 ----------

    public static final class ReleaseInfo {
        public String version = "";
        public String notes = "";
        public String apkUrl = "";
        public String htmlUrl = REPO_PAGE;
        public long apkSize;
    }

    /** 状态回调（主线程；供设置页同步自己的状态文字，可空） */
    public interface Listener {
        void onStatus(String message);
        void onFinished();
        void onFailed(String error);
    }

    // ---------- 语言 ----------

    public static boolean isEnglish(Context context) {
        int mode = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getInt("language_mode", 0);
        return mode == 2 || (mode == 0 && Locale.getDefault().getLanguage().equals("en"));
    }

    // ---------- 版本比较 / 文案清理 ----------

    public static boolean isVersionNewer(String candidate, String current) {
        try {
            String[] candidateParts = candidate.split("\\.");
            String[] currentParts = current.split("\\.");
            int count = Math.max(candidateParts.length, currentParts.length);
            for (int i = 0; i < count; i++) {
                int candidatePart = i < candidateParts.length ? Integer.parseInt(candidateParts[i]) : 0;
                int currentPart = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
                if (candidatePart != currentPart) return candidatePart > currentPart;
            }
        } catch (NumberFormatException ignored) { }
        return false;
    }

    /**
     * 更新说明清理：截断安装/校验等尾部章节；去掉 Markdown 符号（### / **），
     * 列表项转圆点 —— 设置页与更新弹窗共用，避免「###」「**」挤进正文。
     */
    public static String cleanNotes(String notes) {
        String normalized = notes == null ? "" : notes
                .replace("\\r\\n", "\n").replace("\\n", "\n").trim();
        StringBuilder visible = new StringBuilder();
        for (String rawLine : normalized.split("\r?\n")) {
            String compact = rawLine.trim().toLowerCase(Locale.ROOT).replace(" ", "");
            if (compact.equals("##安装说明") || compact.equals("##installation")
                    || compact.equals("##校验") || compact.equals("##verification")
                    || compact.equals("##文件说明") || compact.equals("##filelist")
                    || compact.equals("##files")) break;
            String line = rawLine.trim()
                    .replaceAll("^#{1,6}\\s*", "")
                    .replace("**", "");
            if (line.startsWith("- ")) line = "• " + line.substring(2);
            else if (line.startsWith("-")) line = "• " + line.substring(1);
            if (line.isEmpty() && visible.length() == 0) continue;
            if (visible.length() > 0) visible.append('\n');
            visible.append(line);
        }
        return visible.toString().trim();
    }

    // ---------- 检查更新（多镜像回退） ----------

    /** 阻塞式获取最新 Release（调用方自备后台线程）；全部镜像失败时抛出最后一个异常 */
    public static ReleaseInfo fetchLatest() throws Exception {
        Exception last = null;
        for (String apiUrl : API_URLS) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(apiUrl).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(12000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "Dsu-Manager-Android/" + BuildConfig.VERSION_NAME);
                connection.setUseCaches(false);
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) throw new java.io.IOException("HTTP " + code);
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                }
                JSONObject release = new JSONObject(body.toString());
                ReleaseInfo info = new ReleaseInfo();
                String tag = release.optString("tag_name", "");
                info.version = tag.startsWith("v") ? tag.substring(1) : tag;
                info.notes = cleanNotes(release.optString("body", ""));
                info.htmlUrl = release.optString("html_url", REPO_PAGE);
                info.apkUrl = info.htmlUrl;
                JSONArray assets = release.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.optJSONObject(i);
                        if (asset != null && asset.optString("name", "").endsWith(".apk")
                                && !asset.optString("name", "").contains("debug")) {
                            info.apkUrl = asset.optString("browser_download_url", info.apkUrl);
                            info.apkSize = asset.optLong("size", 0);
                            break;
                        }
                    }
                }
                if (!info.version.isEmpty()) return info;
                throw new java.io.IOException("empty tag_name");
            } catch (Exception error) {
                last = error;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        throw last != null ? last : new java.io.IOException("no mirror reachable");
    }

    // ---------- APP 启动自动检查 ----------

    /** 启动 1.2s 后静默检查（每进程一次）；发现新版本弹窗：更新内容 + 立即更新 / 暂不更新 */
    public static void autoCheck(Activity activity) {
        if (sAutoChecked || activity == null || activity.isFinishing()) return;
        sAutoChecked = true;
        final Activity target = activity;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (target.isFinishing() || target.isDestroyed()) return;
            new Thread(() -> {
                try {
                    ReleaseInfo info = fetchLatest();
                    boolean newer = isVersionNewer(info.version, BuildConfig.VERSION_NAME);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (target.isFinishing() || target.isDestroyed()) return;
                        if (newer) showUpdateDialog(target, info, isEnglish(target));
                    });
                } catch (Exception ignored) {
                    // 静默失败：自动检查不打扰用户（设置页手动检查才显示错误）
                }
            }, "app-update-check").start();
        }, 1200);
    }

    // ---------- 新版本弹窗（更新内容 + 立即更新 / 暂不更新） ----------

    public static void showUpdateDialog(Activity activity, ReleaseInfo info, boolean english) {
        boolean hasApk = info.apkUrl != null && info.apkUrl.endsWith(".apk");

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(activity, 24), dp(activity, 22), dp(activity, 24), dp(activity, 18));
        GradientDrawable bodyBg = new GradientDrawable();
        bodyBg.setColor(0xFFFFFFFF);
        bodyBg.setCornerRadius(dp(activity, 26));
        body.setBackground(bodyBg);

        // 标题：发现新版本 + 版本号徽章（同一行，版本号过长时省略中部）
        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(activity, english ? "New version available" : "发现新版本", 17.5f, 0xde000000);
        title.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD);
        head.addView(title, new LinearLayout.LayoutParams(-2, -2));
        TextView versionChip = text(activity, "v" + info.version, 13f, 0xFF3D6BD6);
        versionChip.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD);
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setColor(0x1A3D6BD6);
        chipBg.setCornerRadius(dp(activity, 12));
        versionChip.setBackground(chipBg);
        versionChip.setPadding(dp(activity, 10), dp(activity, 3), dp(activity, 10), dp(activity, 3));
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(-2, -2);
        chipLp.leftMargin = dp(activity, 8);
        head.addView(versionChip, chipLp);
        body.addView(head, new LinearLayout.LayoutParams(-1, -2));

        // 更新内容：限高滚动（42% 屏高），行距 1.15，不再挤压
        ScrollView notesScroll = new ScrollView(activity);
        notesScroll.setVerticalScrollBarEnabled(true);
        TextView notes = text(activity, "", 13.5f, 0x99000000);
        notes.setGravity(Gravity.START | Gravity.TOP);
        notes.setLineSpacing(dp(activity, 3), 1.12f);
        notes.setText(info.notes == null || info.notes.isEmpty()
                ? (english ? "No release notes." : "暂无更新说明。")
                : info.notes);
        notes.setPadding(dp(activity, 2), 0, dp(activity, 2), 0);
        notesScroll.addView(notes, new ViewGroup.LayoutParams(-1, -2));
        LinearLayout.LayoutParams notesLp = new LinearLayout.LayoutParams(-1, -2);
        notesLp.topMargin = dp(activity, 14);
        body.addView(notesScroll, notesLp);
        // 说明过长时限高滚动（42% 屏高上限），短说明保持自适应，避免空白或溢出
        int noteLines = info.notes == null ? 0 : info.notes.split("\n").length;
        if (noteLines > 10) {
            notesScroll.getLayoutParams().height = Math.min(
                    (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.42), dp(activity, 320));
        }

        // 按钮：暂不更新（浅灰）+ 立即更新（蓝色渐变粗体），等宽 48dp
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button later = pillButton(activity, english ? "Not Now" : "暂不更新",
                0xFFF1F3F7, 0xFF46536B, false);
        later.setOnClickListener(v -> {
            Haptics.perform(v);
            dialogOf(v).dismiss();
        });
        actions.addView(later, new LinearLayout.LayoutParams(0, dp(activity, 48), 1));
        Button now = pillButton(activity,
                hasApk ? (english ? "Update Now" : "立即更新")
                        : (english ? "Open Release Page" : "打开发布页"),
                0xFF4472DE, 0xFFFFFFFF, true);
        LinearLayout.LayoutParams nowLp = new LinearLayout.LayoutParams(0, dp(activity, 48), 1.15f);
        nowLp.leftMargin = dp(activity, 10);
        actions.addView(now, nowLp);
        now.setOnClickListener(v -> {
            Haptics.perform(v);
            dialogOf(v).dismiss();
            if (hasApk) {
                downloadWithDialog(activity, info, english, null);
            } else {
                try {
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(info.htmlUrl)));
                } catch (Exception e) {
                    Toast.makeText(activity, english ? "No browser available" : "无可用浏览器", Toast.LENGTH_SHORT).show();
                }
            }
        });
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, dp(activity, 48));
        actionsLp.topMargin = dp(activity, 16);
        body.addView(actions, actionsLp);

        Dialog dialog = showDialog(activity, body, 0.92f, true);
        later.setTag(dialog);
        now.setTag(dialog);
    }

    // ---------- 下载进度弹窗（多镜像回退 + 进度条 + 取消） ----------

    public static void downloadWithDialog(Activity activity, ReleaseInfo info, boolean english,
                                          Listener listener) {
        String fileName = "Dsu-Manager-v" + info.version + ".apk";

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(activity, 24), dp(activity, 22), dp(activity, 24), dp(activity, 18));
        GradientDrawable bodyBg = new GradientDrawable();
        bodyBg.setColor(0xFFFFFFFF);
        bodyBg.setCornerRadius(dp(activity, 26));
        body.setBackground(bodyBg);

        TextView title = text(activity, english ? "Downloading update" : "正在下载更新", 17f, 0xde000000);
        title.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD);
        title.setMaxLines(1);
        body.addView(title, new LinearLayout.LayoutParams(-1, -2));

        // 文件名 + 大小（单行省略，绝不挤压）
        TextView subtitle = text(activity, fileName
                + (info.apkSize > 0 ? " · " + formatBytes(info.apkSize) : ""),
                12.5f, 0x8a000000);
        subtitle.setMaxLines(1);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = dp(activity, 6);
        body.addView(subtitle, subLp);

        // 进度条（圆角，与下载管理页同款）
        ProgressBar progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        progress.setProgressDrawable(activity.getDrawable(R.drawable.progress_bar));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, dp(activity, 14));
        barLp.topMargin = dp(activity, 16);
        body.addView(progress, barLp);

        // 百分比（左粗体）+ 速度（右），一行两段
        LinearLayout statRow = new LinearLayout(activity);
        statRow.setOrientation(LinearLayout.HORIZONTAL);
        statRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView percent = text(activity, "0%", 16f, 0xFF155e70);
        percent.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD);
        statRow.addView(percent, new LinearLayout.LayoutParams(0, dp(activity, 30), 1));
        TextView speedView = text(activity, english ? "connecting..." : "连接中...", 12.5f, 0x8a000000);
        speedView.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        statRow.addView(speedView, new LinearLayout.LayoutParams(0, dp(activity, 30), 1.4f));
        LinearLayout.LayoutParams statLp = new LinearLayout.LayoutParams(-1, dp(activity, 30));
        statLp.topMargin = dp(activity, 8);
        body.addView(statRow, statLp);

        // 已下载 / 总大小
        TextView bytes = text(activity, english ? "Preparing..." : "正在准备...", 12.5f, 0x8a000000);
        bytes.setMaxLines(1);
        bytes.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams bytesLp = new LinearLayout.LayoutParams(-1, dp(activity, 22));
        body.addView(bytes, bytesLp);

        // 取消按钮（下载完成后变为「关闭」）
        Button cancel = pillButton(activity, english ? "Cancel" : "取消下载",
                0xFFF1F3F7, 0xFF46536B, false);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(-1, dp(activity, 46));
        cancelLp.topMargin = dp(activity, 14);
        body.addView(cancel, cancelLp);

        Dialog dialog = showDialog(activity, body, 0.92f, true);
        Handler ui = new Handler(Looper.getMainLooper());
        AtomicBoolean cancelled = new AtomicBoolean(false);
        cancel.setOnClickListener(v -> {
            Haptics.perform(v);
            if (cancelled.get()) {
                dialog.dismiss();
                return;
            }
            cancelled.set(true);
            dialog.dismiss();
            if (listener != null) listener.onStatus(english ? "Download cancelled" : "已取消更新下载");
        });

        final File apk = new File(activity.getCacheDir(), "dsu-manager-update.apk");
        new Thread(() -> {
            Exception last = null;
            boolean done = false;
            for (String prefix : DL_PREFIXES) {
                if (cancelled.get() || done) break;
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(prefix + info.apkUrl).openConnection();
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(30000);
                    connection.setInstanceFollowRedirects(true);
                    connection.setRequestProperty("User-Agent", "Dsu-Manager-Android/" + BuildConfig.VERSION_NAME);
                    int code = connection.getResponseCode();
                    if (code < 200 || code >= 300) throw new java.io.IOException("HTTP " + code);
                    long contentLength = connection.getContentLengthLong();
                    try (InputStream input = connection.getInputStream();
                         FileOutputStream output = new FileOutputStream(apk)) {
                        byte[] buffer = new byte[16384];
                        int read;
                        long total = 0, windowBytes = 0, windowStart = System.currentTimeMillis(), lastUi = 0;
                        while ((read = input.read(buffer)) != -1) {
                            if (cancelled.get()) throw new java.io.IOException("cancelled");
                            output.write(buffer, 0, read);
                            total += read;
                            windowBytes += read;
                            long now = System.currentTimeMillis();
                            if (now - lastUi >= 300) {   // UI 节流 300ms
                                lastUi = now;
                                long speed = windowBytes * 1000 / Math.max(1, now - windowStart);
                                windowBytes = 0;
                                windowStart = now;
                                final long t = total, cl = contentLength, sp = speed;
                                ui.post(() -> {
                                    int p = cl > 0 ? (int) Math.min(100, t * 100 / cl) : 0;
                                    progress.setProgress(p);
                                    percent.setText(p + "%");
                                    speedView.setText(formatBytes(sp) + "/s");
                                    bytes.setText(cl > 0
                                            ? (english ? "Downloaded " : "已下载 ") + formatBytes(t)
                                            + " / " + formatBytes(cl)
                                            : (english ? "Downloaded " : "已下载 ") + formatBytes(t)
                                            + (english ? " · total size unknown" : " · 总大小获取中"));
                                });
                            }
                        }
                    }
                    // ZIP 头校验：镜像异常时可能返回 HTML 错误页
                    if (!isZipFile(apk)) {
                        //noinspection ResultOfMethodCallIgnored
                        apk.delete();
                        throw new java.io.IOException(english
                                ? "mirror returned invalid file" : "镜像返回了无效内容");
                    }
                    done = true;
                } catch (Exception error) {
                    last = error;
                    //noinspection ResultOfMethodCallIgnored
                    apk.delete();
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }
            final boolean ok = done;
            final Exception failure = last;
            ui.post(() -> {
                if (cancelled.get()) return;
                if (ok) {
                    progress.setProgress(100);
                    percent.setText("100%");
                    bytes.setText(english ? "Download complete" : "下载完成");
                    speedView.setText("");
                    title.setText(english ? "Download complete" : "下载完成");
                    cancel.setText(english ? "Close" : "关闭");
                    cancelled.set(true);   // 复位为「关闭」语义
                    if (listener != null) listener.onFinished();
                    installApk(activity, UpdateFileProvider.getUriForFile(activity, apk), english);
                } else {
                    dialog.dismiss();
                    String error = failure == null ? "unknown" : failure.getMessage();
                    if (listener != null) {
                        listener.onFailed((english ? "Download failed: " : "下载失败: ") + error
                                + (english ? " (all mirrors unreachable)" : "（直连与全部加速镜像均不可达）"));
                    } else {
                        Toast.makeText(activity,
                                (english ? "Download failed: " : "下载失败: ") + error, Toast.LENGTH_LONG).show();
                    }
                }
            });
        }, "app-update-download").start();
    }

    // ---------- 安装 ----------

    public static void installApk(Activity activity, Uri apkUri, boolean english) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(activity, english
                    ? "Allow this app to install unknown apps first"
                    : "请先在系统设置中允许本应用安装未知应用", Toast.LENGTH_LONG).show();
            try {
                activity.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName())));
            } catch (Exception ignored) { }
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.setData(apkUri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity, english ? "Installer unavailable" : "系统安装器不可用", Toast.LENGTH_LONG).show();
        }
    }

    // ---------- 弹窗与控件工具 ----------

    private static Dialog showDialog(Activity activity, LinearLayout body, float widthRatio, boolean dim) {
        Dialog dialog = new Dialog(activity);
        dialog.setContentView(body);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * widthRatio);
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.setCanceledOnTouchOutside(false);
        dialog.setCancelable(false);
        dialog.show();
        return dialog;
    }

    /** 从按钮视图反查宿主 Dialog（弹窗内按钮关闭自身用） */
    private static Dialog dialogOf(View view) {
        Object tag = view.getTag();
        return tag instanceof Dialog ? (Dialog) tag : null;
    }

    private static Button pillButton(Context context, String label, int bg, int fg, boolean bold) {
        Button button = new Button(context, null, 0);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(bold ? 15f : 14.5f);
        if (bold) button.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD);
        button.setTextColor(fg);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setIncludeFontPadding(false);
        button.setPadding(0, 0, 0, 0);
        button.setStateListAnimator(null);
        GradientDrawable background = new GradientDrawable();
        if (bold) {
            background.setOrientation(GradientDrawable.Orientation.TL_BR);
            background.setColors(new int[]{0xFF6C9BF2, 0xFF4472DE});
        } else {
            background.setColor(bg);
        }
        background.setCornerRadius(dp(context, 24));
        background.setStroke(Math.max(1, dp(context, 1)), bold ? 0x59FFFFFF : 0x14000000);
        button.setBackground(background);
        return button;
    }

    private static TextView text(Context context, String value, float size, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + .5f);
    }

    private static boolean isZipFile(File file) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            byte[] head = new byte[2];
            raf.readFully(head);
            return head[0] == 'P' && head[1] == 'K';
        } catch (Exception e) {
            return false;
        }
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.CHINA, "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.CHINA, "%.2f GB", bytes / 1073741824.0);
    }
}
