package com.probiotics.xiaoni;

import android.content.Intent;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;


import com.topjohnwu.superuser.ipc.RootService;

/** Root-side bridge for the hidden dynamic_system Binder service. */
public final class PrivilegedRootService extends RootService {
    private static final String TAG = "TianmingDsu";
    private final IPrivilegedService.Stub bridge = new IPrivilegedService.Stub() {
        @Override public int getUid() { return Process.myUid(); }
        @Override public boolean isInUse() { return bool(7); }
        @Override public boolean isInstalled() { return bool(8); }
        @Override public boolean isEnabled() { return bool(9); }
        @Override public boolean setEnable(boolean enable, boolean oneShot) {
            return transactBoolean(11, p -> { p.writeInt(enable ? 1 : 0); p.writeInt(oneShot ? 1 : 0); });
        }
        @Override public boolean boot() {
            try {
                java.lang.Process reboot = Runtime.getRuntime().exec(new String[]{
                        "/system/bin/setprop", "sys.powerctl", "reboot,dsu"
                });
                return reboot.waitFor() == 0;
            } catch (Exception e) {
                Log.e(TAG, "Unable to reboot to DSU", e);
                return false;
            }
        }
        @Override public boolean remove() { return bool(10); }
        @Override public boolean abort() { return bool(6); }
        @Override public boolean startInstallation(String slot) { return transactBoolean(1, p -> p.writeString(slot)); }
        @Override public int createPartition(String name, long size, boolean readOnly) {
            return transactInt(2, p -> { p.writeString(name); p.writeLong(size); p.writeInt(readOnly ? 1 : 0); });
        }
        @Override public boolean setAshmem(ParcelFileDescriptor fd, long size) {
            return transactBoolean(12, p -> { p.writeTypedObject(fd, 0); p.writeLong(size); });
        }
        @Override public boolean submitFromAshmem(long bytes) { return transactBoolean(13, p -> p.writeLong(bytes)); }
        @Override public boolean closePartition() { return bool(3); }
        @Override public boolean finishInstallation() { return bool(4); }
        @Override public String listDsuImages() {
            StringBuilder result = new StringBuilder();
            File root = installedImageDirectory();
            collectInstalledImages(root, result);
            if (result.length() == 0) return "EMPTY|暂无已安装镜像|" + root.getAbsolutePath();
            return result.toString();
        }
        private void collectInstalledImages(File directory, StringBuilder result) {
            if (directory == null || !directory.isDirectory()) return;
            File[] files = directory.listFiles();
            if (files == null) return;
            for (File file : files) {
                if (!file.isDirectory() && isImageFile(file)) {
                    if (result.length() > 0) result.append('\n');
                    long size = fileSize(file);
                    result.append(file.getName()).append('|').append(size).append('|').append(file.getAbsolutePath()).append('|').append(sizeLabel(size));
                }
            }
        }
        private boolean isImageFile(File file) {
            String name = file.getName().toLowerCase(java.util.Locale.US);
            return !name.startsWith(".") && (name.contains(".img") || name.endsWith(".raw"));
        }
        private File installedImageDirectory() {
            return new File("/data/gsi/dsu/dsu");
        }
        private long fileSize(File file) {
            long size = file.length();
            if (size > 0) return size;
            try {
                java.lang.Process process = Runtime.getRuntime().exec(new String[]{"/system/bin/stat", "-c", "%s", file.getAbsolutePath()});
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
                String value = reader.readLine();
                int code = process.waitFor();
                if (code == 0 && value != null) return Long.parseLong(value.trim());
            } catch (Exception ignored) { }
            try {
                java.lang.Process process = Runtime.getRuntime().exec(new String[]{"/system/bin/ls", "-l", file.getAbsolutePath()});
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
                String line = reader.readLine();
                int code = process.waitFor();
                if (code == 0 && line != null) {
                    String[] fields = line.trim().split("\\s+");
                    if (fields.length >= 5) return Long.parseLong(fields[4]);
                }
            } catch (Exception ignored) { }
            return -1;
        }
        private String sizeLabel(long bytes) {
            if (bytes < 0) return "读取失败（权限或文件系统不支持）";
            if (bytes == 0) return "大小未知（可能为稀疏文件或块设备）";
            if (bytes >= 1073741824L) return String.format(java.util.Locale.US, "%.2f GB", bytes / 1073741824d);
            return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576d);
        }
        @Override public boolean replaceDsuImage(String imagePath, String sourcePath) {
            if (isInUse()) return false;
            File target = secureDsuImage(imagePath);
            File source = new File(sourcePath);
            if (target == null || !source.isFile()) return false;
            File temporary = new File(target.getParentFile(), "." + target.getName() + ".new");
            try (FileInputStream input = new FileInputStream(source);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[1024 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.getFD().sync();
                if (!target.delete()) return false;
                return temporary.renameTo(target);
            } catch (IOException error) {
                Log.e(TAG, "Unable to replace DSU image", error);
                if (temporary.exists()) temporary.delete();
                return false;
            }
        }
        private File secureDsuImage(String path) {
            try {
                File target = new File(path).getCanonicalFile();
                File canonicalRoot = installedImageDirectory().getCanonicalFile();
                String rootPath = canonicalRoot.getPath() + File.separator;
                if (target.getPath().startsWith(rootPath) && target.isFile()) return target;
            } catch (IOException ignored) { }
            return null;
        }
    };

    @Override public IBinder onBind(Intent intent) { return bridge; }

    private IBinder dynamicSystem() {
        try {
            Class<?> manager = Class.forName("android.os.ServiceManager");
            return (IBinder) manager.getMethod("getService", String.class)
                    .invoke(null, "dynamic_system");
        } catch (Exception e) {
            Log.e(TAG, "Unable to resolve dynamic_system", e);
            return null;
        }
    }
    private interface Writer { void write(Parcel parcel); }

    private boolean bool(int code) { return transactBoolean(code, null); }

    private boolean transactBoolean(int code, Writer writer) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("android.os.image.IDynamicSystemService");
            if (writer != null) writer.write(data);
            IBinder service = dynamicSystem();
            if (service == null || !service.transact(code, data, reply, 0)) return false;
            reply.readException();
            return reply.readInt() != 0;
        } catch (Exception e) {
            Log.e(TAG, "dynamic_system transaction failed: " + code, e);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private int transactInt(int code, Writer writer) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("android.os.image.IDynamicSystemService");
            writer.write(data);
            IBinder service = dynamicSystem();
            if (service == null || !service.transact(code, data, reply, 0)) return -1;
            reply.readException();
            return reply.readInt();
        } catch (Exception e) {
            Log.e(TAG, "dynamic_system transaction failed: " + code, e);
            return -1;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
