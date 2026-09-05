package com.probiotics.xiaoni;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.system.ErrnoException;
import android.system.Os;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.*;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

public final class LinuxTerminalActivity extends Activity {
    private static final int PICK_ROOTFS = 42;
    private LinearLayout list;
    private TextView status;
    private volatile java.lang.Process shellProcess;
    private volatile TerminalSession terminalSession;
    private static volatile LinuxTerminalActivity activeInstance;
    private volatile String activeImageId;
    private volatile boolean backgroundRequested;
    private TerminalView mTerminalView;
    private android.os.PowerManager.WakeLock terminalWakeLock;
    private boolean terminalVisible;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService sizeWorker = Executors.newFixedThreadPool(2);
    private final java.util.concurrent.ConcurrentHashMap<String, Long> sizeCache = new java.util.concurrent.ConcurrentHashMap<>();

    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + .5f); }
    private TextView label(String value, int size, int color) { TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setGravity(Gravity.CENTER_VERTICAL); return v; }

    @Override public void onCreate(Bundle state) { super.onCreate(state); activeInstance = this; getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS); getWindow().setStatusBarColor(Color.BLACK); getWindow().setNavigationBarColor(Color.BLACK); getWindow().getDecorView().setSystemUiVisibility(0); getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE); Intent intent = getIntent(); String openId = intent.getStringExtra("open_image"); if (openId != null) { terminalVisible = true; applyTerminalBars(); status = new TextView(this); LinuxImages.Image selected = null; for (LinuxImages.Image image : LinuxImages.ALL) if (image.id().equals(openId)) selected = image; if (LinuxImages.LOCAL.id().equals(openId)) selected = LinuxImages.LOCAL; if (selected != null) { startShell(selected); return; } } showCatalog(); if (intent.getBooleanExtra("local_install", false) && intent.getData() != null) importRootfs(intent.getData()); }

    private void applyTerminalBars() { if (!terminalVisible) { applyCatalogBars(); return; } getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS); if (Build.VERSION.SDK_INT >= 30) { getWindow().setDecorFitsSystemWindows(true); getWindow().getInsetsController().setSystemBarsAppearance(0, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS); } else { getWindow().getDecorView().setSystemUiVisibility(0); } getWindow().setStatusBarColor(0xff101418); getWindow().setNavigationBarColor(0xff101418); }
    private void applyCatalogBars() { getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS); if (Build.VERSION.SDK_INT >= 30) { getWindow().setDecorFitsSystemWindows(true); getWindow().getInsetsController().setSystemBarsAppearance(android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS); } getWindow().setStatusBarColor(0xfff6f7fb); getWindow().setNavigationBarColor(0xfff6f7fb); getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR); }

    private void showCatalog() {
        terminalVisible = false; applyCatalogBars(); LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(14), dp(14), dp(14), dp(20)); content.setBackgroundColor(0xfff6f7fb); content.setOnApplyWindowInsetsListener((v, i) -> { v.setPadding(dp(14), dp(14) + i.getSystemWindowInsetTop(), dp(14), dp(20) + i.getSystemWindowInsetBottom()); return i; });
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); header.setBackgroundResource(R.drawable.rounded_panel); Button back = new Button(this); back.setText("‹"); back.setTextSize(26); back.setAllCaps(false); back.setMinWidth(0); back.setMinHeight(0); back.setOnClickListener(v -> finish()); header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(56))); TextView title = label("Linux 终端", 23, 0xff141d37); title.setTypeface(null, 1); header.addView(title, new LinearLayout.LayoutParams(0, dp(56), 1)); content.addView(header);
         status = label("在线云端镜像\n选择系统下载，安装完成后可从更多功能直接进入", 14, 0xff2d3952); status.setTypeface(null, 1); status.setPadding(dp(8), dp(14), 0, dp(8)); content.addView(status);
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); content.addView(list); ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(content); setContentView(scroll); refreshList();
    }

    private void refreshList() { list.removeAllViews(); for (LinuxImages.Image image : LinuxImages.ALL) addImageRow(image); }
    private void addImageRow(LinuxImages.Image image) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL); row.setPadding(dp(14), dp(10), dp(12), dp(8)); row.setBackgroundResource(R.drawable.rounded_panel);
        LinearLayout heading = new LinearLayout(this); heading.setGravity(Gravity.CENTER_VERTICAL); TextView text = label(image.name + "\n" + image.description, 14, 0xff232c41); text.setTypeface(null, 1); heading.addView(text, new LinearLayout.LayoutParams(0, dp(58), 1));
         boolean installed = LinuxImages.hasUsableShell(LinuxImages.environment(this, image)); Button action = new Button(this); action.setAllCaps(false); action.setMinWidth(0); action.setMinHeight(0); action.setTextSize(12); action.setText(installed ? "进入终端" : "下载"); action.setTextColor(Color.WHITE); action.setBackgroundResource(installed ? R.drawable.button_green : R.drawable.button_blue); action.setOnClickListener(v -> { if (installed) startShell(image); else download(image, row); }); heading.addView(action, new LinearLayout.LayoutParams(dp(94), dp(46))); row.addView(heading);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(-1, dp(24));
        row.addView(label("ARM64 · 官方云端 · " + (image.name.startsWith("Alpine") ? "轻量、启动快" : image.name.startsWith("Ubuntu") ? "开发工具丰富" : "稳定、适合服务"), 11, image.name.startsWith("Alpine") ? 0xff17845b : image.name.startsWith("Ubuntu") ? 0xff9a5b13 : 0xff5e4c99), metaLp);
        TextView sizeText = label("下载大小：获取中...", 11, 0xff52617b); row.addView(sizeText, new LinearLayout.LayoutParams(-1, dp(22)));
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(100); progress.setProgressDrawable(getDrawable(R.drawable.progress_bar)); progress.setVisibility(View.GONE); row.addView(progress, new LinearLayout.LayoutParams(-1, dp(10))); TextView progressText = label("", 11, 0xff52617b); progressText.setVisibility(View.GONE); row.addView(progressText, new LinearLayout.LayoutParams(-1, dp(24)));
        Long cached = sizeCache.get(image.url); if (cached != null) { sizeText.setText(cached > 0 ? "下载大小：" + formatBytes(cached) : "下载大小：连接后获取"); } else { sizeWorker.execute(() -> { long size = contentLength(image.url); runOnUiThread(() -> sizeText.setText(size > 0 ? "下载大小：" + formatBytes(size) : "下载大小：连接后获取")); }); }
        if (installed) { Button remove = new Button(this); remove.setText("移除环境，释放空间"); remove.setTextSize(12); remove.setAllCaps(false); remove.setTextColor(0xffc62828); remove.setBackgroundResource(R.drawable.remove_pill); remove.setOnClickListener(v -> confirmRemove(image)); LinearLayout.LayoutParams removeLp = new LinearLayout.LayoutParams(-1, dp(40)); removeLp.setMargins(0, dp(4), 0, 0); row.addView(remove, removeLp); }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, dp(8), 0, 0); list.addView(row, lp);
    }

    private void confirmRemove(LinuxImages.Image image) { new AlertDialog.Builder(this).setTitle("移除终端环境").setMessage("将删除 " + image.name + " 的系统文件和下载压缩包，释放应用空间。").setNegativeButton("取消", null).setPositiveButton("移除", (d, w) -> worker.execute(() -> { stopShell(); File environment = LinuxImages.environment(this, image); File archive = LinuxImages.archive(this, image); boolean removed = removeAsRoot(environment, archive); runOnUiThread(() -> { refreshList(); Toast.makeText(this, removed ? "环境已移除，空间已释放" : "移除未完成，请先退出正在运行的终端后重试", Toast.LENGTH_SHORT).show(); }); })).show(); }

    private boolean removeAsRoot(File root, File archive) { if (root == null && archive == null) return true; String rootPath = root == null ? "" : root.getAbsolutePath(); String archivePath = archive == null ? "" : archive.getAbsolutePath(); String rootQuoted = "'" + rootPath.replace("'", "'\\''") + "'"; String archiveQuoted = "'" + archivePath.replace("'", "'\\''") + "'"; String command = "for p in " + rootQuoted + "/dev/pts " + rootQuoted + "/dev " + rootQuoted + "/proc " + rootQuoted + "/sys " + rootQuoted + "/tmp " + rootQuoted + "/etc/resolv.conf; do /system/bin/toybox umount -l \"$p\" 2>/dev/null; done; /system/bin/toybox rm -rf " + rootQuoted + " " + archiveQuoted + "; if [ ! -e " + rootQuoted + " ] && [ ! -e " + archiveQuoted + " ]; then exit 0; fi; exit 1"; try { java.lang.Process process = new ProcessBuilder("/system/bin/su", "-c", command).redirectErrorStream(true).start(); return process.waitFor() == 0; } catch (Exception ignored) { return false; } }
    private boolean deleteTree(File file) { if (file == null || !file.exists()) return true; boolean removed = true; if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) { File[] children = file.listFiles(); if (children != null) for (File child : children) removed = deleteTree(child) && removed; } try { Files.deleteIfExists(file.toPath()); } catch (IOException e) { removed = false; } return removed && !file.exists(); }

     private void snapshotPackages(File rootfs) {
         File marker = new File(rootfs, ".linux-dsu-base-packages");
         if (marker.isFile()) return;
         String path = rootfs.getAbsolutePath().replace("'", "'\\''");
         String command = "ROOT='" + path + "'; if [ -x \"$ROOT/usr/bin/dpkg-query\" ]; then /system/bin/toybox chroot \"$ROOT\" /usr/bin/dpkg-query -W -f='${binary:Package} ${Status}\\n' 2>/dev/null | while read -r package want error status; do [ \"$want $error $status\" = \"install ok installed\" ] && printf '%s\\n' \"$package\"; done; elif [ -f \"$ROOT/etc/apk/world\" ]; then /system/bin/toybox cat \"$ROOT/etc/apk/world\" 2>/dev/null; fi";
         try {
             java.lang.Process process = new ProcessBuilder("/system/bin/su", "-c", command).redirectErrorStream(true).start();
             StringBuilder output = new StringBuilder();
             try (InputStream input = process.getInputStream()) { byte[] buffer = new byte[4096]; int count; while ((count = input.read(buffer)) != -1) output.append(new String(buffer, 0, count, StandardCharsets.UTF_8)); }
             if (process.waitFor() == 0 && output.length() > 0) try (Writer writer = new OutputStreamWriter(new FileOutputStream(marker), StandardCharsets.UTF_8)) { writer.write(output.toString()); }
         } catch (Exception ignored) { }
     }

     private void pickRootfs() { Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("*/*"); intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/gzip", "application/x-gzip", "application/x-xz", "application/octet-stream"}); startActivityForResult(intent, PICK_ROOTFS); }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) { super.onActivityResult(requestCode, resultCode, data); if (requestCode == PICK_ROOTFS && resultCode == RESULT_OK && data != null && data.getData() != null) importRootfs(data.getData()); }
      private void importRootfs(Uri uri) { status.setText("正在导入本地 rootfs..."); worker.execute(() -> { File temporaryArchive = new File(LinuxImages.root(this), "local-rootfs.part"); try { String name = String.valueOf(uri).toLowerCase(Locale.US); String suffix = name.contains("xz") ? ".tar.xz" : ".tar.gz"; File archive = new File(LinuxImages.root(this), "local-rootfs" + suffix); LinuxImages.root(this).mkdirs(); try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(temporaryArchive)) { if (in == null) throw new IOException("无法读取文件"); byte[] buffer = new byte[65536]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n); } if (!temporaryArchive.renameTo(archive)) throw new IOException("无法保存本地压缩包"); File destination = LinuxImages.environment(this, LinuxImages.LOCAL); extractToTemporary(archive, destination); snapshotPackages(destination); runOnUiThread(() -> { if (status != null) { status.setText("本地 rootfs 已安装"); refreshList(); } }); } catch (Exception e) { deleteTree(temporaryArchive); runOnUiThread(() -> { if (status != null) status.setText("本地安装失败: " + e.getMessage()); }); } }); }

      private void download(LinuxImages.Image image, LinearLayout row) { ProgressBar progress = (ProgressBar) row.getChildAt(3); TextView progressText = (TextView) row.getChildAt(4); Button action = (Button) ((LinearLayout) row.getChildAt(0)).getChildAt(1); progress.setVisibility(View.VISIBLE); progressText.setVisibility(View.VISIBLE); progressText.setText("正在连接服务器..."); action.setEnabled(false); action.setText("下载中"); worker.execute(() -> { File environment = LinuxImages.environment(this, image); File archive = LinuxImages.archive(this, image); File partial = new File(archive.getParentFile(), archive.getName() + ".part"); java.net.HttpURLConnection c = null; try { LinuxImages.root(this).mkdirs(); c = (java.net.HttpURLConnection) new java.net.URL(image.url).openConnection(); c.setConnectTimeout(15000); c.setReadTimeout(30000); c.connect(); int response = c.getResponseCode(); if (response / 100 != 2) throw new IOException("HTTP " + response); long total = c.getContentLengthLong(); try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(partial)) { byte[] b = new byte[262144]; long done = 0; int n; while ((n = in.read(b)) != -1) { out.write(b, 0, n); done += n; final long downloaded = done; int value = total > 0 ? (int) (done * 100 / total) : 0; runOnUiThread(() -> { if (total > 0) progress.setProgress(value); progressText.setText(total > 0 ? "已下载 " + formatBytes(downloaded) + " / " + formatBytes(total) + "（" + value + "%）" : "已下载 " + formatBytes(downloaded)); }); } } if (!partial.renameTo(archive)) throw new IOException("无法保存下载包"); runOnUiThread(() -> progressText.setText("下载完成，正在解压...")); extractToTemporary(archive, environment); snapshotPackages(environment); runOnUiThread(this::refreshList); } catch (Exception e) { deleteTree(partial); runOnUiThread(() -> { progressText.setText("下载失败: " + e.getMessage()); action.setEnabled(true); action.setText("重试"); }); } finally { if (c != null) c.disconnect(); } }); }

    private long contentLength(String url) { Long cached = sizeCache.get(url); if (cached != null) return cached; try { java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(url).openConnection(); connection.setRequestMethod("HEAD"); connection.setConnectTimeout(5000); connection.setReadTimeout(8000); connection.setInstanceFollowRedirects(true); connection.connect(); long length = connection.getContentLengthLong(); connection.disconnect(); if (length > 0) sizeCache.put(url, length); return length; } catch (Exception ignored) { return -1; } }
    private String formatBytes(long bytes) { if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0); if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0); return String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0); }
    private LinearLayout terminalKeys() { LinearLayout keys = new LinearLayout(this); keys.setOrientation(LinearLayout.VERTICAL); keys.setPadding(dp(4), dp(4), dp(4), dp(2)); keys.setBackgroundColor(0xff101418); String[][] rows = {{"Esc", "Tab", "PgUp", "Home", "↑", "End", "Ctrl"}, {"Alt", "PgDn", "←", "↓", "→", "Enter"}}; for (String[] row : rows) { LinearLayout line = new LinearLayout(this); line.setGravity(Gravity.CENTER_VERTICAL); for (String key : row) { Button button = new Button(this); button.setText(key); button.setTextSize(11); button.setAllCaps(false); button.setTextColor(0xffe6edf3); button.setMinWidth(0); button.setMinHeight(0); button.setPadding(0, 0, 0, 0); button.setBackgroundResource(R.drawable.terminal_key_bg); button.setOnClickListener(v -> sendTerminalKey(key)); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1); lp.setMargins(dp(2), 0, dp(2), dp(4)); line.addView(button, lp); } keys.addView(line, new LinearLayout.LayoutParams(-1, dp(42))); } return keys; }
    private void sendTerminalKey(String key) { String value; if (key.equals("Esc")) value = "\u001b"; else if (key.equals("Tab")) value = "\t"; else if (key.equals("Enter")) value = "\r"; else if (key.equals("↑")) value = "\u001b[A"; else if (key.equals("↓")) value = "\u001b[B"; else if (key.equals("←")) value = "\u001b[D"; else if (key.equals("→")) value = "\u001b[C"; else if (key.equals("Home")) value = "\u001b[H"; else if (key.equals("End")) value = "\u001b[F"; else if (key.equals("PgUp")) value = "\u001b[5~"; else if (key.equals("PgDn")) value = "\u001b[6~"; else if (key.equals("Ctrl")) value = "\u0003"; else if (key.equals("Alt")) value = "\u001b"; else return; sendPty(value); }

    private void extract(File archive, File destination) throws IOException { destination.mkdirs(); InputStream raw = new FileInputStream(archive); if (archive.getName().endsWith(".gz")) raw = new GZIPInputStream(raw); else if (archive.getName().endsWith(".xz")) raw = new XZCompressorInputStream(raw); try (TarArchiveInputStream tar = new TarArchiveInputStream(raw)) { TarArchiveEntry entry; byte[] buffer = new byte[65536]; Path root = destination.toPath().toAbsolutePath().normalize(); String topDirectory = null; boolean firstEntry = true; java.util.List<Path[]> pendingHardLinks = new java.util.ArrayList<>(); java.util.List<Object[]> pendingSymbolicLinks = new java.util.ArrayList<>(); while ((entry = tar.getNextTarEntry()) != null) { String name = entry.getName().replace('\\', '/'); while (name.startsWith("/")) name = name.substring(1); if (firstEntry && entry.isDirectory() && name.indexOf('/') < 0 && !name.isEmpty()) topDirectory = name; firstEntry = false; if (topDirectory != null && name.startsWith(topDirectory + "/")) name = name.substring(topDirectory.length() + 1); if (name.isEmpty()) continue; Path target = root.resolve(Paths.get(name).normalize()).normalize(); if (!target.startsWith(root)) throw new IOException("压缩包包含越界路径: " + name); if (entry.isDirectory()) { Files.createDirectories(target); continue; } ensureParentDirectories(root, target.getParent()); if (entry.isSymbolicLink() || entry.isLink()) { String link = entry.getLinkName().replace('\\', '/'); if (entry.isLink() && topDirectory != null && link.startsWith(topDirectory + "/")) link = link.substring(topDirectory.length() + 1); Path linkTarget = entry.isLink() ? root.resolve(Paths.get(link.startsWith("/") ? link.substring(1) : link).normalize()).normalize() : link.startsWith("/") ? root.resolve(Paths.get(link.substring(1)).normalize()).normalize() : target.getParent().resolve(link).normalize(); if (!linkTarget.startsWith(root)) throw new IOException("压缩包包含越界链接: " + name); Files.deleteIfExists(target); if (entry.isLink()) pendingHardLinks.add(new Path[]{target, linkTarget}); else pendingSymbolicLinks.add(new Object[]{target, link.startsWith("/") ? target.getParent().relativize(linkTarget).toString() : link}); continue; } if (entry.isCharacterDevice() || entry.isBlockDevice() || entry.isFIFO()) continue; Files.deleteIfExists(target); try (OutputStream out = new FileOutputStream(target.toFile())) { int n; while ((n = tar.read(buffer)) != -1) out.write(buffer, 0, n); } target.toFile().setExecutable((entry.getMode() & 0100) != 0, false); } for (Path[] link : pendingHardLinks) { if (!Files.isRegularFile(link[1])) throw new IOException("压缩包硬链接目标缺失: " + root.relativize(link[1])); Files.copy(link[1], link[0], StandardCopyOption.REPLACE_EXISTING); link[0].toFile().setExecutable(Files.isExecutable(link[1]), false); } for (Object[] link : pendingSymbolicLinks) { Path target = (Path) link[0]; try { Os.symlink((String) link[1], target.toString()); } catch (ErrnoException e) { throw new IOException("无法创建符号链接: " + target + " -> " + link[1], e); } } } }
    private void ensureParentDirectories(Path root, Path directory) throws IOException { if (directory == null || directory.equals(root)) return; Path current = root; for (Path part : root.relativize(directory)) { current = current.resolve(part); if (Files.isSymbolicLink(current)) throw new IOException("父路径包含符号链接"); Files.createDirectories(current); } }

    private void startShell(LinuxImages.Image image) { terminalVisible = true; activeImageId = image.id(); status.setText("正在准备 ROOT Linux 终端..."); worker.execute(() -> { try { File environment = LinuxImages.environment(this, image); File archive = LinuxImages.archive(this, image); if (!environment.isDirectory() && archive.isFile()) { runOnUiThread(() -> { if (status != null) status.setText("正在从下载包恢复 " + image.name + "..."); }); extractToTemporary(archive, environment); } runRootShellStrict(image); } catch (Exception e) { runOnUiThread(() -> { if (status != null && !isFinishing()) status.setText("启动失败: " + e.getMessage()); }); } }); }

    private void runRootShellStrict(LinuxImages.Image image) throws IOException {
        File rootfs = LinuxImages.environment(this, image);
        normalizeRootfs(rootfs);
        File script = new File(getCacheDir(), "linux-start.sh");
        String root = rootfs.getAbsolutePath().replace("'", "'\\''");
        StringBuilder scriptText = new StringBuilder();
        scriptText.append("#!/system/bin/sh\n");
        scriptText.append("export PATH=/system/bin:/system/xbin:/sbin:/vendor/bin\n");
        scriptText.append("set +x\n");
        scriptText.append("ROOTFS='").append(root).append("'\n");
        scriptText.append("MOUNT_LIST=\"$ROOTFS/.xsh_mounts\"\n");
        scriptText.append("cleanup() {\n");
         scriptText.append("  if [ -f \"$MOUNT_LIST\" ]; then\n");
         scriptText.append("    while read -r mnt; do\n");
         scriptText.append("      [ -n \"$mnt\" ] && /system/bin/toybox umount -l \"$mnt\" 2>/dev/null || true\n");
        scriptText.append("    done < \"$MOUNT_LIST\"\n");
        scriptText.append("    /system/bin/toybox rm -f \"$MOUNT_LIST\"\n");
        scriptText.append("  fi\n");
         scriptText.append("  /system/bin/toybox umount -l \"$ROOTFS/etc/resolv.conf\" 2>/dev/null || true\n");
         scriptText.append("  /system/bin/toybox umount -l \"$ROOTFS/tmp\" 2>/dev/null || true\n");
         scriptText.append("  /system/bin/toybox umount -l \"$ROOTFS/sys\" 2>/dev/null || true\n");
         scriptText.append("  /system/bin/toybox umount -l \"$ROOTFS/proc\" 2>/dev/null || true\n");
         scriptText.append("  /system/bin/toybox umount -l \"$ROOTFS/dev/pts\" 2>/dev/null || true\n");
         scriptText.append("  /system/bin/toybox umount -l \"$ROOTFS/dev\" 2>/dev/null || true\n");
        scriptText.append("}\n");
         scriptText.append("trap cleanup EXIT INT TERM HUP\n");
         scriptText.append("if [ \"$(/system/bin/id -u 2>/dev/null)\" != \"0\" ]; then echo \"Linux-Dsu: 当前 shell 没有 root 权限，无法挂载和 chroot\" >&2; exit 126; fi\n");
          scriptText.append("/system/bin/toybox chmod 755 \"$ROOTFS/bin/bash\" \"$ROOTFS/usr/bin/bash\" \"$ROOTFS/bin/sh\" \"$ROOTFS/usr/bin/sh\" \"$ROOTFS/bin/dash\" \"$ROOTFS/usr/bin/dash\" 2>/dev/null || true\n");
           scriptText.append("CHROOT_SHELL=/bin/sh\n");
         scriptText.append("for old in \"$ROOTFS/etc/resolv.conf\" \"$ROOTFS/tmp\" \"$ROOTFS/sys\" \"$ROOTFS/proc\" \"$ROOTFS/dev/pts\" \"$ROOTFS/dev\"; do /system/bin/toybox umount -l \"$old\" 2>/dev/null || true; done\n");
         scriptText.append(": > \"$MOUNT_LIST\" || exit 1\n");
        scriptText.append("/system/bin/toybox mkdir -p \"$ROOTFS/dev/pts\" \"$ROOTFS/proc\" \"$ROOTFS/sys\" \"$ROOTFS/tmp\" \"$ROOTFS/root\" \"$ROOTFS/etc\"\n");
         scriptText.append("/system/bin/toybox mount --bind /dev \"$ROOTFS/dev\" || { echo \"挂载 /dev 失败\" >&2; exit 125; }\n");
         scriptText.append("echo \"$ROOTFS/dev\" >> \"$MOUNT_LIST\"\n");
         scriptText.append("/system/bin/toybox mount --bind /dev/pts \"$ROOTFS/dev/pts\" || { echo \"挂载 /dev/pts 失败\" >&2; exit 125; }\n");
         scriptText.append("echo \"$ROOTFS/dev/pts\" >> \"$MOUNT_LIST\"\n");
         scriptText.append("/system/bin/toybox mount -t proc proc \"$ROOTFS/proc\" || { echo \"挂载 /proc 失败\" >&2; exit 125; }\n");
         scriptText.append("echo \"$ROOTFS/proc\" >> \"$MOUNT_LIST\"\n");
         scriptText.append("/system/bin/toybox mount -t sysfs sysfs \"$ROOTFS/sys\" || { /system/bin/toybox mount | /system/bin/toybox grep -Fq \" $ROOTFS/sys \" || { echo \"挂载 /sys 失败\" >&2; exit 125; }; }\n");
         scriptText.append("echo \"$ROOTFS/sys\" >> \"$MOUNT_LIST\"\n");
         scriptText.append("/system/bin/toybox mount -t tmpfs tmpfs \"$ROOTFS/tmp\" || { echo \"挂载 /tmp 失败\" >&2; exit 125; }\n");
        scriptText.append("echo \"$ROOTFS/tmp\" >> \"$MOUNT_LIST\"\n");
         scriptText.append("/system/bin/toybox rm -f \"$ROOTFS/etc/resolv.conf\"\n/system/bin/toybox touch \"$ROOTFS/etc/resolv.conf\"\n");
         scriptText.append("/system/bin/toybox mount --bind /etc/resolv.conf \"$ROOTFS/etc/resolv.conf\" 2>/dev/null || true\n");
         scriptText.append("if ! /system/bin/toybox test -s \"$ROOTFS/etc/resolv.conf\"; then /system/bin/toybox printf '%s\\n' 'nameserver 114.114.114.114' 'nameserver 223.5.5.5' > \"$ROOTFS/etc/resolv.conf\"; fi\n");
          scriptText.append("# Prepare package-manager state before entering the chroot.\n");
          scriptText.append("/system/bin/toybox mkdir -p \"$ROOTFS/var/cache/apk\" \"$ROOTFS/var/cache/dnf\" \"$ROOTFS/var/cache/pacman/pkg\" \"$ROOTFS/var/lib/linux-dsu\" 2>/dev/null || true\n");
          scriptText.append("/system/bin/toybox chmod 755 \"$ROOTFS/var\" \"$ROOTFS/var/cache\" \"$ROOTFS/var/cache/apk\" \"$ROOTFS/var/cache/dnf\" \"$ROOTFS/var/cache/pacman\" \"$ROOTFS/var/cache/pacman/pkg\" 2>/dev/null || true\n");
          scriptText.append("if [ -x \"$ROOTFS/usr/bin/apt-get\" ]; then /system/bin/toybox mkdir -p \"$ROOTFS/var/lib/apt/lists/partial\" \"$ROOTFS/var/cache/apt/archives/partial\" \"$ROOTFS/etc/apt/apt.conf.d\"; /system/bin/toybox chmod 755 \"$ROOTFS/var/lib\" \"$ROOTFS/var/lib/apt\" \"$ROOTFS/var/lib/apt/lists\" \"$ROOTFS/var/lib/apt/lists/partial\" \"$ROOTFS/var/cache/apt\" \"$ROOTFS/var/cache/apt/archives\" \"$ROOTFS/var/cache/apt/archives/partial\"; /system/bin/toybox printf '%s\\n' 'APT::Sandbox::User \"root\";' > \"$ROOTFS/etc/apt/apt.conf.d/99linux-dsu-sandbox\"; fi\n");
         scriptText.append("/system/bin/toybox mkdir -p \"$ROOTFS/var/lib/linux-dsu\"\n/system/bin/toybox touch \"$ROOTFS/var/lib/linux-dsu/user-packages\"\n");
            scriptText.append("/system/bin/toybox printf '%s\\n' '[ -r /etc/os-release ] && . /etc/os-release' 'cd /' 'export PS1=\"root@${NAME:-Linux}:${PWD:-/}# \"' 'export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin' 'record_user_packages() {' '  for package in \"$@\"; do' '    case \"$package\" in -*) continue;; esac' '    package=${package%%=*}' '    if [ -n \"$package\" ]; then' '      command grep -Fqx \"$package\" /var/lib/linux-dsu/user-packages 2>/dev/null || printf \"%s\\n\" \"$package\" >> /var/lib/linux-dsu/user-packages' '    fi' '  done' '}' 'wrap_install() {' '  command_name=$1; shift' '  packages=; capture=0' '  for argument in \"$@\"; do' '    if [ \"$capture\" = 1 ]; then packages=\"$packages $argument\"; elif [ \"$argument\" = install ] || [ \"$argument\" = add ]; then capture=1; fi' '  done' '  if [ \"$command_name\" = apt ] && [ ! -x /usr/bin/apt ] && [ ! -x /usr/bin/apt-get ]; then printf \"Alpine Linux uses apk; run: apk add ...\\n\" >&2; return 127; fi' '  if [ \"$command_name\" = apt ] && [ \"$capture\" = 1 ] && [ ! -f /var/lib/linux-dsu/.apt-updated ]; then command apt-get -o APT::Sandbox::User=root update && : > /var/lib/linux-dsu/.apt-updated || return $?; fi' '  if [ \"$command_name\" = apt ]; then command apt -o APT::Sandbox::User=root \"$@\"; else command \"$command_name\" \"$@\"; fi; status=$?' '  [ $status -eq 0 ] && [ -n \"$packages\" ] && record_user_packages $packages' '  return $status' '}' 'apt() { wrap_install apt \"$@\"; }' 'alias apt-get=\"apt-get -o APT::Sandbox::User=root\"' 'apk() { wrap_install apk \"$@\"; }' 'dnf() { wrap_install dnf \"$@\"; }' 'yum() { wrap_install yum \"$@\"; }' 'pkg() { wrap_install pkg \"$@\"; }' > \"$ROOTFS/root/.bashrc\"\n");
         scriptText.append("/system/bin/toybox printf '%s\\n' '[ -f /root/.bashrc ] && . /root/.bashrc' > \"$ROOTFS/root/.profile\"\n");
        scriptText.append("cd \"$ROOTFS\" || exit 1\n");
         scriptText.append("CHROOT_LOADER=\nfor loader in /lib/ld-linux-aarch64.so.1 /lib64/ld-linux-aarch64.so.1 /lib/ld-linux-arm64.so.1; do if [ -e \"$ROOTFS$loader\" ]; then CHROOT_LOADER=\"$loader\"; break; fi; done\n");
         scriptText.append("/system/bin/toybox chroot \"$ROOTFS\" \"$CHROOT_SHELL\" -c 'exit 0' >/dev/null 2>\"$ROOTFS/.linux-dsu-chroot-error\"\nstatus=$?\nif [ \"$status\" -ne 0 ]; then echo \"Linux-Dsu: chroot shell=$CHROOT_SHELL status=$status loader=${CHROOT_LOADER:-missing}\" >&2; /system/bin/toybox ls -l \"$ROOTFS$CHROOT_SHELL\" \"$ROOTFS/bin/sh\" \"$ROOTFS/lib/ld-linux-aarch64.so.1\" \"$ROOTFS/lib64/ld-linux-aarch64.so.1\" 2>&1 >&2; /system/bin/toybox cat \"$ROOTFS/.linux-dsu-chroot-error\" >&2 2>/dev/null || true; exit $status; fi\n");
         scriptText.append("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin HOME=/root TERM=xterm-256color /system/bin/toybox chroot \"$ROOTFS\" \"$CHROOT_SHELL\" -l\n");
        scriptText.append("status=$?\n[ \"$status\" -ne 0 ] && echo \"Linux-Dsu: chroot 退出，状态码 $status\" >&2\nexit $status\n");
        try (Writer out = new OutputStreamWriter(new FileOutputStream(script), StandardCharsets.UTF_8)) { out.write(scriptText.toString()); }
        script.setExecutable(true, false);
        script.setReadable(true, false);
         runOnUiThread(() -> showPtyTerminal(image, script));
       }

    private void showPtyTerminal(LinuxImages.Image image, File script) {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
         getWindow().setStatusBarColor(0xff101418);
         getWindow().setNavigationBarColor(0xff101418);
         if (Build.VERSION.SDK_INT >= 30) getWindow().getInsetsController().setSystemBarsAppearance(0, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        getWindow().getDecorView().setSystemUiVisibility(0);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
         if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);

         LinearLayout root = new LinearLayout(this);
         root.setOrientation(LinearLayout.VERTICAL);
         root.setBackgroundColor(0xff0b0f13);
         root.setPadding(0, dp(8), 0, 0);
         root.setOnApplyWindowInsetsListener((v, insets) -> {
             int top = insets.getSystemWindowInsetTop();
             int bottomInset = insets.getSystemWindowInsetBottom();
             if (Build.VERSION.SDK_INT >= 30) {
                 android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                 android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                 top = bars.top;
                 bottomInset = Math.max(bars.bottom, ime.bottom);
             }
             v.setPadding(0, dp(8) + top, 0, bottomInset);
             return insets;
         });
        final LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xff101418);
        bar.setPadding(dp(4), dp(4), dp(6), dp(4));
        Button back = new Button(this);
        back.setText("‹");
        back.setTextSize(22);
        back.setAllCaps(false);
        back.setMinWidth(0);
        back.setMinHeight(0);
        back.setTextColor(0xffe6edf3);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(42), dp(44)));
        TextView title = label(image.name, 14, 0xffe6edf3);
        title.setTypeface(null, 1);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1));
        TextView state = label("启动中", 11, 0xff9adcff);
        state.setGravity(Gravity.CENTER);
        bar.addView(state, new LinearLayout.LayoutParams(dp(72), dp(44)));
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(52)));

        TerminalView terminal = new TerminalView(this, null);
        mTerminalView = terminal;
        terminal.setTextSize(dp(12));
        terminal.setBackgroundColor(Color.BLACK);
         terminal.setFocusable(true);
         terminal.setFocusableInTouchMode(true);
         terminal.setTerminalViewClient(new PtyViewClient());
         terminal.setOnTouchListener((v, event) -> {
             if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                 terminal.requestFocusFromTouch();
                 terminal.postDelayed(() -> showTerminalKeyboard(terminal), 80);
             }
             return false;
         });
        root.addView(terminal, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(0xff101418);
        root.addView(bottom, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);

         final TerminalSession session = new TerminalSession(
                 "/system/bin/sh",
                 "/",
                 new String[]{"sh", "-c", "exec /system/bin/su 0 /system/bin/sh " + shellQuote(script.getAbsolutePath())},
                 null,
                 4000,
                 new PtySessionClient(state));
         terminalSession = session;
         terminal.attachSession(session);
         bottom.addView(buildTerminalControls(state, session, image.name), new LinearLayout.LayoutParams(-1, -2));
         root.requestApplyInsets();
         terminal.requestFocus();
         terminal.postDelayed(() -> state.setText(session.isRunning() ? "已连接" : "启动失败"), 500);
     }

      private String shellQuote(String value) {
         return "'" + value.replace("'", "'\\''") + "'";
     }

     private void showTerminalKeyboard(TerminalView terminal) {
         if (terminal == null || !terminal.isShown()) return;
         terminal.requestFocusFromTouch();
         InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
         if (imm != null) {
             imm.restartInput(terminal);
             imm.showSoftInput(terminal, InputMethodManager.SHOW_IMPLICIT);
         }
         terminal.requestLayout();
     }

     private LinearLayout buildTerminalControls(TextView state, TerminalSession session, String sessionName) {
         LinearLayout controls = new LinearLayout(this);
         controls.setOrientation(LinearLayout.VERTICAL);
         controls.setPadding(dp(6), dp(6), dp(6), dp(6));
         controls.setBackgroundColor(0xff101418);
         LinearLayout keys = new LinearLayout(this);
         keys.setOrientation(LinearLayout.VERTICAL);
         keys.setPadding(0, 0, 0, dp(2));
        String[][] rows = { { "Esc", "Tab", "PgUp", "Home", "↑", "End", "Ctrl" }, { "Alt", "PgDn", "←", "↓", "→", "Enter" } };
        for (String[] row : rows) {
            LinearLayout line = new LinearLayout(this);
            line.setGravity(Gravity.CENTER_VERTICAL);
            for (String key : row) {
                Button b = new Button(this);
                b.setText(key);
                 b.setTextSize(11);
                b.setAllCaps(false);
                b.setMinWidth(0);
                b.setMinHeight(0);
                b.setPadding(0, 0, 0, 0);
                 b.setTextColor(0xffe6edf3);
                 b.setBackgroundResource(R.drawable.terminal_key_bg);
                 b.setClickable(true);
                 b.setFocusable(false);
                final String k = key;
                b.setOnClickListener(v -> {
                    if (session == null || !session.isRunning()) {
                        Toast.makeText(LinuxTerminalActivity.this, "终端未连接", Toast.LENGTH_SHORT).show();
                    } else {
                        sendTerminalKey(session, k);
                    }
                });
                 LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1);
                 lp.setMargins(dp(2), dp(1), dp(2), dp(3));
                line.addView(b, lp);
            }
              keys.addView(line, new LinearLayout.LayoutParams(-1, dp(43)));
        }

        controls.addView(keys, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(dp(3), dp(1), dp(3), dp(3));
        Button end = new Button(this);
        end.setText("结束");
        end.setTextSize(12);
        end.setAllCaps(false);
        end.setMinWidth(0);
        end.setMinHeight(0);
        end.setTextColor(Color.WHITE);
        end.setBackgroundResource(R.drawable.button_red);
        end.setOnClickListener(v -> terminalAction("end", session, state));
        actions.addView(end, new LinearLayout.LayoutParams(0, dp(42), 1));
        Button bg = new Button(this);
        bg.setText("后台运行");
        bg.setTextSize(12);
        bg.setAllCaps(false);
        bg.setMinWidth(0);
        bg.setMinHeight(0);
        bg.setTextColor(Color.WHITE);
        bg.setBackgroundResource(R.drawable.button_teal);
        bg.setOnClickListener(v -> terminalAction("background", session, state));
        actions.addView(bg, new LinearLayout.LayoutParams(0, dp(42), 1));
        Button more = new Button(this);
        more.setText("⋮");
        more.setTextSize(18);
        more.setAllCaps(false);
        more.setMinWidth(0);
        more.setMinHeight(0);
        more.setTextColor(0xffe6edf3);
        more.setBackgroundResource(R.drawable.terminal_key_bg);
        LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(0, dp(42), 1);
        moreLp.setMargins(dp(2), 0, 0, 0);
        actions.addView(more, moreLp);
        LinearLayout.LayoutParams actionRowLp = new LinearLayout.LayoutParams(-1, -2);
        actionRowLp.setMargins(0, dp(1), 0, 0);
        controls.addView(actions, actionRowLp);

        more.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(this, more);
            android.view.Menu m = menu.getMenu();
            m.add("唤醒锁");
            m.add("结束");
            m.add("重置");
            m.add("粘贴");
            m.add("后台运行");
            menu.setOnMenuItemClickListener(item -> {
                terminalAction(item.getTitle().toString(), session, state);
                return true;
            });
            menu.show();
        });
        return controls;
    }

     private void terminalAction(String action, TerminalSession session, TextView state) {
        if ("end".equals(action) || "结束".equals(action)) {
            state.setText("已退出");
            if (session != null && session.isRunning()) session.write("exit\r");
            stopShell();
            setTerminalWakeLock(false);
            finish();
         } else if ("background".equals(action) || "后台运行".equals(action)) {
             backgroundRequested = true;
             if (state != null) state.setText("后台运行");
             setTerminalWakeLock(true);
             try { moveTaskToBack(true); } catch (RuntimeException ignored) { }
        } else if ("reset".equals(action) || "重置".equals(action)) {
            if (session != null) { session.write("\u0003"); session.write("clear\r"); }
        } else if ("paste".equals(action) || "粘贴".equals(action)) {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence text = cm.getPrimaryClip().getItemAt(0).getText();
                if (text != null && session != null) session.write(normalizeTerminalPaste(text.toString()));
     }

        } else if ("wake".equals(action) || "唤醒锁".equals(action)) {
            boolean on = terminalWakeLock == null || !terminalWakeLock.isHeld();
            setTerminalWakeLock(on);
            Toast.makeText(this, on ? "唤醒锁已开启，CPU 保持运行" : "唤醒锁已关闭", Toast.LENGTH_SHORT).show();
        }
    }

    private void normalizeRootfs(File rootfs) throws IOException {
        if (new File(rootfs, "bin").isDirectory() || new File(rootfs, "usr").isDirectory()) return;
        File[] children = rootfs.listFiles();
        if (children == null || children.length != 1 || !children[0].isDirectory()) return;
        File nested = children[0];
        if (!new File(nested, "bin").isDirectory() && !new File(nested, "usr").isDirectory()) return;
        File[] entries = nested.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            Path target = new File(rootfs, entry.getName()).toPath();
            if (Files.exists(target)) throw new IOException("rootfs 目录结构冲突: " + entry.getName());
            Files.move(entry.toPath(), target);
        }
        Files.deleteIfExists(nested.toPath());
    }

    private void setTerminalWakeLock(boolean on) {
        if (on) {
            if (terminalWakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null) terminalWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.probiotics.xiaoni:terminal");
            }
            if (terminalWakeLock != null && !terminalWakeLock.isHeld()) terminalWakeLock.acquire(60L * 60L * 1000L);
        } else if (terminalWakeLock != null && terminalWakeLock.isHeld()) {
            terminalWakeLock.release();
        }
    }

    private void sendTerminalKey(TerminalSession session, String key) {
        String value;
        if (key.equals("Esc")) value = "\u001b";
        else if (key.equals("Tab")) value = "\t";
        else if (key.equals("Enter")) value = "\r";
        else if (key.equals("↑")) value = "\u001b[A";
        else if (key.equals("↓")) value = "\u001b[B";
        else if (key.equals("←")) value = "\u001b[D";
        else if (key.equals("→")) value = "\u001b[C";
        else if (key.equals("Home")) value = "\u001b[H";
        else if (key.equals("End")) value = "\u001b[F";
        else if (key.equals("PgUp")) value = "\u001b[5~";
        else if (key.equals("PgDn")) value = "\u001b[6~";
        else if (key.equals("Ctrl")) value = "\u0003";
        else if (key.equals("Alt")) value = "\u001b";
        else return;
        if (session != null && session.isRunning()) session.write(value);
    }

    private static final class RootProbe {
        final String exec;
        final boolean interactive;
        RootProbe(String exec, boolean interactive) { this.exec = exec; this.interactive = interactive; }
    }

    private String runCapture(java.util.List<String> command, String input, long timeoutMs) {
        StringBuilder out = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            java.lang.Process p = pb.start();
            OutputStream os = p.getOutputStream();
            if (input != null) {
                os.write(input.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            os.close();
            Thread reader = new Thread(() -> {
                try (InputStream in = p.getInputStream()) {
                    byte[] buffer = new byte[1024];
                    int n;
                    while ((n = in.read(buffer)) != -1) out.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                } catch (Exception ignored) {}
            });
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) p.destroy();
            reader.join(300);
        } catch (Exception ignored) {}
        return out.toString();
    }

    private String firstLine(String s) { if (s == null) return null; int i = s.indexOf('\n'); return i < 0 ? s : s.substring(0, i); }

    private RootProbe probeRoot(StringBuilder report) {
        java.util.List<String> paths = new java.util.ArrayList<>();
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(":")) {
                if (dir.isEmpty()) continue;
                File f = new File(dir, "su");
                if (f.isFile() && !paths.contains(f.getAbsolutePath())) paths.add(f.getAbsolutePath());
            }
        }
        String[] candidates = { "/data/adb/ksu/bin/su", "/data/adb/ksu/bin/sugote", "/data/adb/magisk/su", "/data/adb/magisk/magisk", "/su/bin/su", "/sbin/su", "/system/xbin/su", "/vendor/bin/su", "/debug_ramdisk/su", "/system/bin/su" };
        for (String p : candidates) if (!paths.contains(p)) paths.add(p);
        for (String p : paths) {
            File file = new File(p);
            if (!file.isFile() && !Files.isSymbolicLink(file.toPath())) { report.append("X ").append(p).append("  不存在\n"); continue; }
            String out = runCapture(java.util.Arrays.asList(p), "id\nexit 0\n", 2500);
            if (out.contains("uid=0")) { report.append("OK ").append(p).append("  可用(交互 stdin)\n"); return new RootProbe(p, true); }
            report.append("X ").append(p).append("  ").append(firstLine(out) != null ? firstLine(out).trim() : "").append("\n");
        }
        return null;
    }

     private void sendPty(String value) {
         TerminalSession session = terminalSession;
         if (session != null && session.isRunning()) session.write(value);
     }

     private String normalizeTerminalPaste(String text) {
         return text.replace("\r\n", "\n").replace('\r', '\n').replace('\n', '\r');
     }

    private final class PtySessionClient implements TerminalSessionClient {
        private final TextView state;
        PtySessionClient(TextView state) { this.state = state; }
          public void onTextChanged(TerminalSession s) {
              TerminalView view = mTerminalView;
              if (view != null && !isFinishing()) runOnUiThread(() -> { if (mTerminalView != null) mTerminalView.invalidate(); });
         }
        public void onTitleChanged(TerminalSession s) { }
         public void onSessionFinished(TerminalSession s) { runOnUiThread(() -> { if (state != null && !isFinishing()) state.setText("已退出"); }); }
        public void onCopyTextToClipboard(TerminalSession s, String text) {
            if (text == null || text.isEmpty()) return;
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text));
            runOnUiThread(() -> Toast.makeText(LinuxTerminalActivity.this, "已复制到剪贴板", Toast.LENGTH_SHORT).show());
        }
        public void onPasteTextFromClipboard(TerminalSession s) {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip() != null && clipboard.getPrimaryClip().getItemCount() > 0) {
                CharSequence text = clipboard.getPrimaryClip().getItemAt(0).coerceToText(LinuxTerminalActivity.this);
                if (text != null && s != null && s.isRunning()) s.write(normalizeTerminalPaste(text.toString()));
            }
        }
        public void onBell(TerminalSession s) { }
         public void onColorsChanged(TerminalSession s) { }
        public void onTerminalCursorStateChange(boolean state) { }
        public Integer getTerminalCursorStyle() { return 0; }
        public void logError(String tag, String message) { }
        public void logWarn(String tag, String message) { }
        public void logInfo(String tag, String message) { }
        public void logDebug(String tag, String message) { }
        public void logVerbose(String tag, String message) { }
        public void logStackTraceWithMessage(String tag, String message, Exception e) { }
        public void logStackTrace(String tag, Exception e) { }
    }

     private final class PtyViewClient implements TerminalViewClient {
         public void onScreenUpdated() {
             TerminalView view = mTerminalView;
             if (view != null) view.invalidate();
         }
         public float onScale(float scale) { return Math.max(0.7f, Math.min(1.5f, scale)); }
         public void onSingleTapUp(android.view.MotionEvent e) {
             TerminalView tv = mTerminalView;
             if (tv != null) tv.postDelayed(() -> showTerminalKeyboard(tv), 80);
         }
        public boolean shouldBackButtonBeMappedToEscape() { return false; }
        public boolean shouldEnforceCharBasedInput() { return true; }
        public boolean shouldUseCtrlSpaceWorkaround() { return false; }
        public boolean isTerminalViewSelected() { return true; }
        public void copyModeChanged(boolean copyMode) { }
        public boolean onKeyDown(int keyCode, android.view.KeyEvent e, TerminalSession s) {
            if (keyCode == android.view.KeyEvent.KEYCODE_ENTER && e.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                if (s != null && s.isRunning()) s.write("\r");
                return true;
            }
            return false;
        }
        public boolean onKeyUp(int keyCode, android.view.KeyEvent e) { return false; }
        public boolean onLongPress(android.view.MotionEvent e) { return false; }
        public boolean readControlKey() { return false; }
        public boolean readAltKey() { return false; }
        public boolean readShiftKey() { return false; }
        public boolean readFnKey() { return false; }
        public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession s) {
            if (ctrlDown || !Character.isValidCodePoint(codePoint) || s == null || !s.isRunning()) return false;
            s.write(new String(Character.toChars(codePoint)));
            return true;
        }
        public void onEmulatorSet() { }
        public void logError(String tag, String message) { }
        public void logWarn(String tag, String message) { }
        public void logInfo(String tag, String message) { }
        public void logDebug(String tag, String message) { }
        public void logVerbose(String tag, String message) { }
        public void logStackTraceWithMessage(String tag, String message, Exception e) { }
        public void logStackTrace(String tag, Exception e) { }
    }
    private void runRootShell(LinuxImages.Image image) throws IOException { File rootfs = LinuxImages.environment(this, image); File script = new File(getCacheDir(), "linux-start.sh"); String path = rootfs.getAbsolutePath(); String text = "#!/system/bin/sh\n" + "export PATH=/system/bin:/system/xbin:/sbin:/vendor/bin\n" + "ROOTFS='" + path + "'\n" + "MOUNT_LIST=\"$ROOTFS/.xsh_mounts\"\n" + "cleanup(){ if [ -f \"$MOUNT_LIST\" ]; then while read -r mnt; do [ -n \"$mnt\" ] && /system/bin/toybox umount \"$mnt\" 2>/dev/null; done < \"$MOUNT_LIST\"; /system/bin/toybox rm -f \"$MOUNT_LIST\"; fi; /system/bin/toybox umount \"$ROOTFS/dev/pts\" 2>/dev/null; /system/bin/toybox umount \"$ROOTFS/dev\" 2>/dev/null; /system/bin/toybox umount \"$ROOTFS/proc\" 2>/dev/null; /system/bin/toybox umount \"$ROOTFS/sys\" 2>/dev/null; /system/bin/toybox umount \"$ROOTFS/tmp\" 2>/dev/null; }\n" + "trap cleanup EXIT INT TERM HUP\n" + "if [ ! -e \"$ROOTFS/bin/bash\" ] && [ ! -e \"$ROOTFS/usr/bin/bash\" ]; then echo 'Linux-Dsu: rootfs 中没有 /bin/bash，无法启动终端' >&2; exit 127; fi\n" + "/system/bin/toybox chmod 755 \"$ROOTFS/bin/bash\" \"$ROOTFS/usr/bin/bash\" 2>/dev/null || true\n" + ": > \"$MOUNT_LIST\" || exit 1\n" + "/system/bin/toybox mkdir -p \"$ROOTFS/dev/pts\" \"$ROOTFS/proc\" \"$ROOTFS/sys\" \"$ROOTFS/tmp\" \"$ROOTFS/root\"\n" + "/system/bin/toybox mount --bind /dev \"$ROOTFS/dev\" || { echo '挂载 /dev 失败' >&2; exit 125; }\necho \"$ROOTFS/dev\" >> \"$MOUNT_LIST\"\n" + "/system/bin/toybox mount --bind /dev/pts \"$ROOTFS/dev/pts\" || exit 125\necho \"$ROOTFS/dev/pts\" >> \"$MOUNT_LIST\"\n" + "/system/bin/toybox mount -t proc proc \"$ROOTFS/proc\" || exit 125\necho \"$ROOTFS/proc\" >> \"$MOUNT_LIST\"\n" + "/system/bin/toybox mount -t sysfs sysfs \"$ROOTFS/sys\" || exit 125\necho \"$ROOTFS/sys\" >> \"$MOUNT_LIST\"\n" + "/system/bin/toybox mount -t tmpfs tmpfs \"$ROOTFS/tmp\" || exit 125\necho \"$ROOTFS/tmp\" >> \"$MOUNT_LIST\"\n" + "/system/bin/toybox mkdir -p \"$ROOTFS/etc\"\n" + "/system/bin/toybox printf '%s\\n' 'nameserver 114.114.114.114' > \"$ROOTFS/etc/resolv.conf\"\n" + "cd \"$ROOTFS\" || exit 1\n" + "echo 'Linux-Dsu: rootfs mounted, starting shell'\n" + "/system/bin/toybox chroot \"$ROOTFS\" /usr/bin/env PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin HOME=/root TERM=linux /bin/bash --login\n"; try (Writer out = new OutputStreamWriter(new FileOutputStream(script), StandardCharsets.UTF_8)) { out.write(text); } script.setExecutable(true, false); script.setReadable(true, false); runOnUiThread(() -> showTerminal(image, script)); }
    private void showTerminal(LinuxImages.Image image, File script) { getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS); if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(true); getWindow().setStatusBarColor(0xff182027); getWindow().setNavigationBarColor(0xff090b0e); getWindow().getDecorView().setSystemUiVisibility(0); LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(8), dp(8), dp(8), dp(8)); root.setBackgroundColor(0xff090b0e); root.setOnApplyWindowInsetsListener((v, i) -> { v.setPadding(dp(8), dp(8) + i.getSystemWindowInsetTop(), dp(8), dp(8) + i.getSystemWindowInsetBottom()); return i; }); LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(8), 0, dp(4), 0); bar.setBackgroundResource(R.drawable.terminal_toolbar_bg); Button back = new Button(this); back.setText("‹"); back.setTextSize(24); back.setAllCaps(false); back.setMinWidth(0); back.setMinHeight(0); back.setTextColor(Color.WHITE); back.setBackgroundColor(Color.TRANSPARENT); back.setOnClickListener(v -> finish()); bar.addView(back, new LinearLayout.LayoutParams(dp(42), dp(48))); TextView title = label(image.name, 15, Color.WHITE); title.setTypeface(null, 1); bar.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1)); TextView state = label("启动中", 11, 0xffffd166); state.setGravity(Gravity.CENTER); bar.addView(state, new LinearLayout.LayoutParams(dp(78), dp(48))); root.addView(bar, new LinearLayout.LayoutParams(-1, dp(52))); TextView output = new TextView(this); output.setTextColor(0xffd9f7d9); output.setTextSize(13); output.setTypeface(android.graphics.Typeface.MONOSPACE); output.setText("正在启动 " + image.name + "...\n"); ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(output); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); root.addView(terminalKeys(), new LinearLayout.LayoutParams(-1, dp(86))); LinearLayout inputBar = new LinearLayout(this); inputBar.setGravity(Gravity.CENTER_VERTICAL); inputBar.setPadding(dp(6), dp(4), dp(6), dp(4)); inputBar.setBackgroundResource(R.drawable.terminal_input_bg); EditText command = new EditText(this); command.setSingleLine(true); command.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND); command.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS); command.setHint("输入命令"); command.setTextColor(Color.WHITE); command.setHintTextColor(0xffaaaaaa); command.setBackgroundColor(Color.TRANSPARENT); command.setPadding(dp(8), 0, dp(8), 0); inputBar.addView(command, new LinearLayout.LayoutParams(0, dp(48), 1)); Button send = new Button(this); send.setText("发送"); send.setTextSize(12); send.setAllCaps(false); send.setTextColor(Color.WHITE); send.setBackgroundResource(R.drawable.button_teal); inputBar.addView(send, new LinearLayout.LayoutParams(dp(68), dp(42))); root.addView(inputBar, new LinearLayout.LayoutParams(-1, dp(60))); output.setOnClickListener(v -> { output.requestFocus(); ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(command, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT); command.requestFocus(); }); setContentView(root); applyTerminalBars(); getWindow().setStatusBarColor(0xff182027); getWindow().setNavigationBarColor(0xff090b0e); command.requestFocus(); getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE); worker.execute(() -> { try { java.lang.Process process = new ProcessBuilder("/system/bin/su", "-c", "/system/bin/sh " + script.getAbsolutePath()).redirectErrorStream(true).start(); shellProcess = process; runOnUiThread(() -> state.setText("已连接")); Runnable submit = () -> { try { String line = command.getText().toString().replaceAll("[\\p{Cntrl}&&[^\\t]]", "").trim(); if (line.isEmpty()) return; output.append("root@Linux:/# " + line + "\n"); scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN)); process.getOutputStream().write((line + "\n").getBytes(StandardCharsets.UTF_8)); process.getOutputStream().flush(); command.setText(""); } catch (IOException ignored) {} }; runOnUiThread(() -> { send.setOnClickListener(v -> submit.run()); command.setOnEditorActionListener((v, id, event) -> { submit.run(); return true; }); command.setOnKeyListener((v, keyCode, event) -> { if (keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.getAction() == android.view.KeyEvent.ACTION_DOWN) { submit.run(); return true; } return false; }); }); BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)); String line; while ((line = reader.readLine()) != null) { String text = line; runOnUiThread(() -> { output.append(text + "\n"); scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN)); }); } int exitCode = process.waitFor(); runOnUiThread(() -> state.setText("已退出 " + exitCode)); } catch (Exception e) { runOnUiThread(() -> { state.setText("启动失败"); output.append("启动失败: " + e.getMessage() + "\n"); }); } }); }
     static void stopRunningEnvironment(String imageId) { LinuxTerminalActivity instance = activeInstance; if (instance != null && imageId != null && imageId.equals(instance.activeImageId)) { instance.backgroundRequested = false; instance.stopShell(); instance.setTerminalWakeLock(false); } }
     private void extractToTemporary(File archive, File destination) throws IOException { File parent = destination.getParentFile(); if (parent == null) throw new IOException("rootfs 目录无效"); File temporary = new File(parent, destination.getName() + ".part"); deleteTree(temporary); try { extract(archive, temporary); if (!LinuxImages.hasUsableShell(temporary)) throw new IOException("rootfs 中没有可用的 shell"); deleteTree(destination); if (!temporary.renameTo(destination)) throw new IOException("无法替换 rootfs 目录"); } catch (Exception e) { deleteTree(temporary); throw e; } }
     private void stopShell() { TerminalSession session = terminalSession; terminalSession = null; if (session != null) session.finishIfRunning(); java.lang.Process process = shellProcess; shellProcess = null; if (process != null) { try { process.getOutputStream().close(); } catch (IOException ignored) {} process.destroy(); } }
     @Override protected void onDestroy() { if (!backgroundRequested) { setTerminalWakeLock(false); stopShell(); } if (activeInstance == this) activeInstance = null; worker.shutdownNow(); sizeWorker.shutdownNow(); super.onDestroy(); }
}
