package com.probiotics.xiaoni;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;

/** Hosts Dsu's own root-aware glass file browser for selecting an aria2c output directory. */
public final class DsuDirectoryPickerActivity extends Activity {
    public static final String EXTRA_START_DIRECTORY = "com.probiotics.xiaoni.extra.START_DIRECTORY";
    public static final String EXTRA_BROWSE_ONLY = "com.probiotics.xiaoni.extra.BROWSE_ONLY";
    public static final String EXTRA_DIRECTORY = "com.probiotics.xiaoni.extra.DIRECTORY";
    public static final String EXTRA_PICK_AUTH_FILE = "com.probiotics.xiaoni.extra.PICK_AUTH_FILE";
    public static final String EXTRA_AUTH_FILE_PATH = "com.probiotics.xiaoni.extra.AUTH_FILE_PATH";
    private boolean finished;

    /** Show Dsu's native directory picker as a modal Dialog on the caller's current screen. */
    public static void showDirectoryDialog(Activity host, String startDirectory,
                                           java.util.function.Consumer<String> callback) {
        java.io.File initial = new java.io.File(
                startDirectory == null || startDirectory.trim().isEmpty()
                        ? "/storage/emulated/0/Download/DsuManager/Yunpan" : startDirectory.trim());
        if (!initial.exists()) {
            try { initial.mkdirs(); } catch (Exception ignored) { }
        }
        if (initial.isFile()) initial = initial.getParentFile();
        if (initial == null) initial = new java.io.File("/storage/emulated/0/Download");
        FileBrowserDialog.showDirectoryPicker(
                host,
                "选择网盘下载文件夹",
                initial.getAbsolutePath(),
                path -> callback.accept(path),
                () -> callback.accept(null)
        );
    }

    /** Show Dsu's native auth-file picker as a modal Dialog and return a private cache copy. */
    public static void showAuthBackupDialog(Activity host,
                                            java.util.function.Consumer<String> callback) {
        FileBrowserDialog.showFilePicker(
                host,
                "选择网盘认证备份",
                null,
                "/storage/emulated/0/Download",
                sourcePath -> callback.accept(copyAuthFileToPrivateCache(host, sourcePath)),
                () -> callback.accept(null)
        );
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        View backdrop = new FrameLayout(this);
        backdrop.setBackgroundResource(R.drawable.liquid_backdrop);
        setContentView(backdrop, new FrameLayout.LayoutParams(-1, -1));

        boolean browseOnly = getIntent().getBooleanExtra(EXTRA_BROWSE_ONLY, false);
        boolean pickAuthFile = getIntent().getBooleanExtra(EXTRA_PICK_AUTH_FILE, false);
        String start = getIntent().getStringExtra(EXTRA_START_DIRECTORY);
        if (pickAuthFile && (start == null || start.trim().isEmpty())) {
            start = "/storage/emulated/0/Download";
        }
        if (start == null || start.trim().isEmpty()) {
            start = getSharedPreferences("yunx_settings", MODE_PRIVATE)
                    .getString("dsu_download_dir_path", "");
        }
        if (start == null || start.trim().isEmpty()) {
            start = "/storage/emulated/0/Download/DsuManager/Yunpan";
        }
        java.io.File initial = new java.io.File(start);
        if (browseOnly && initial.isFile()) initial = initial.getParentFile();
        if (initial == null) initial = new java.io.File("/storage/emulated/0/Download");
        if (!browseOnly) {
            try { initial.mkdirs(); } catch (Exception ignored) { }
        }
        start = initial.getAbsolutePath();
        if (pickAuthFile) {
            FileBrowserDialog.showFilePicker(
                    this,
                    "选择网盘认证备份",
                    null,
                    start,
                    this::copyAuthFileToPrivateCache,
                    () -> finishWithAuthPath(null)
            );
            return;
        }
        if (browseOnly) {
            FileBrowserDialog.showLocationBrowser(this, "打开下载位置", start, this::finish);
            return;
        }
        FileBrowserDialog.showDirectoryPicker(
                this,
                "选择网盘下载文件夹",
                start,
                path -> finishWith(path),
                () -> finishWith(null)
        );
    }

    private void finishWith(String path) {
        if (finished) return;
        finished = true;
        if (path == null || path.trim().isEmpty()) {
            setResult(Activity.RESULT_CANCELED);
        } else {
            setResult(Activity.RESULT_OK, new Intent().putExtra(EXTRA_DIRECTORY, path));
        }
        finish();
        overridePendingTransition(0, 0);
    }

    private void copyAuthFileToPrivateCache(String sourcePath) {
        if (finished) return;
        finishWithAuthPath(copyAuthFileToPrivateCache(this, sourcePath));
    }

    private static String copyAuthFileToPrivateCache(Activity host, String sourcePath) {
        if (host == null || sourcePath == null || sourcePath.trim().isEmpty()) return null;
        java.io.File cached = new java.io.File(host.getCacheDir(),
                "yunx-auth-import-" + System.currentTimeMillis() + ".json");
        String cachePath = DnaTools.quote(cached.getAbsolutePath());
        String command = "cp -f " + DnaTools.quote(sourcePath) + " " + cachePath
                + " && chown " + android.os.Process.myUid()
                + " " + cachePath + " && chmod 600 " + cachePath;
        com.topjohnwu.superuser.Shell.Result result = null;
        try {
            result = com.topjohnwu.superuser.Shell.cmd(command).exec();
        } catch (Exception ignored) { }
        if (result == null || !result.isSuccess() || !cached.isFile()) {
            cached.delete();
            android.widget.Toast.makeText(host, "无法读取所选认证备份", android.widget.Toast.LENGTH_LONG).show();
            return null;
        }
        return cached.getAbsolutePath();
    }

    private void finishWithAuthPath(String path) {
        if (finished) return;
        finished = true;
        if (path == null || path.trim().isEmpty()) {
            setResult(Activity.RESULT_CANCELED);
        } else {
            setResult(Activity.RESULT_OK, new Intent().putExtra(EXTRA_AUTH_FILE_PATH, path));
        }
        finish();
        overridePendingTransition(0, 0);
    }

    @Override public void onBackPressed() {
        super.onBackPressed();
        if (!finished) finishWith(null);
    }
}
