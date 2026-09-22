package com.probiotics.xiaoni;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.widget.Toast;

import java.io.File;

/**
 * v3.9.13 应用自更新安装结果接收器（PackageInstaller 会话提交后的系统回调）：
 *  - STATUS_PENDING_USER_ACTION（Android 8~11 / 未满足静默条件）→ 自动拉起系统安装确认页，
 *    点一下「安装」即完成，全程无需 root；
 *  - STATUS_SUCCESS → 新版本已装好（老进程随后被系统替换，正常现象）；
 *  - STATUS_FAILURE → Toast 提示失败原因，可去 GitHub Releases 手动下载。
 *
 * v3.9.14：安装成功后自动清理安装包（EXTRA_APK_PATH 携带路径；自更新与
 * 下载管理页手动安装 APK 两条路径共用），ROM 等其他格式文件不受影响。
 */
public final class UpdateInstallReceiver extends BroadcastReceiver {

    public static final String ACTION_INSTALL_RESULT = "com.probiotics.xiaoni.UPDATE_INSTALL_RESULT";
    /** 安装包绝对路径：安装成功后自动删除（UpdateCenter.installApk 提交时携带） */
    public static final String EXTRA_APK_PATH = "apk_path";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_INSTALL_RESULT.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // 系统仍需用户确认 → 打开确认页（无需 root，只是少一步自动）
            Intent confirm;
            if (Build.VERSION.SDK_INT >= 33) {
                confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
            } else {
                //noinspection deprecation
                confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            }
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { context.startActivity(confirm); } catch (Exception ignored) { }
            }
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) {
            // v3.9.14：安装成功 → 自动清理安装包（自更新的缓存 APK / 下载管理页下载的 APK）
            String apkPath = intent.getStringExtra(EXTRA_APK_PATH);
            if (apkPath != null && !apkPath.isEmpty()) {
                try {
                    File apk = new File(apkPath);
                    //noinspection ResultOfMethodCallIgnored
                    apk.delete();
                } catch (Exception ignored) { }
            }
            Toast.makeText(context, "✓ 安装完成，已自动清理安装包", Toast.LENGTH_LONG).show();
            return;
        }
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        Toast.makeText(context, "自动安装失败: " + (message == null || message.isEmpty()
                ? "未知错误" : message), Toast.LENGTH_LONG).show();
    }
}
