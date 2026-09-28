package com.probiotics.xiaoni;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import com.topjohnwu.superuser.Shell;

/**
 * ADB/Fastboot 管理器
 * 负责从 assets 提取二进制文件，通过 Root 权限执行命令
 */
public class AdbManager {
    private final Context context;
    private File adbBinary;
    private File fastbootBinary;
    private File binDir;

    public AdbManager(Context context) {
        this.context = context.getApplicationContext();
        binDir = new File(context.getFilesDir(), "bin");
        adbBinary = new File(binDir, "adb");
        fastbootBinary = new File(binDir, "fastboot");
    }

    /**
     * 从 assets 提取 ADB 和 Fastboot 二进制文件
     */
    public boolean extractBinaries() {
        try {
            if (!binDir.exists()) binDir.mkdirs();
            
            // 提取 adb
            if (!adbBinary.exists() || adbBinary.length() == 0) {
                extractAsset("bin/adb", adbBinary);
                Shell.cmd("chmod 755 " + adbBinary.getAbsolutePath()).exec();
            }
            
            // 提取 fastboot
            if (!fastbootBinary.exists() || fastbootBinary.length() == 0) {
                extractAsset("bin/fastboot", fastbootBinary);
                Shell.cmd("chmod 755 " + fastbootBinary.getAbsolutePath()).exec();
            }
            
            return adbBinary.exists() && fastbootBinary.exists();
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private void extractAsset(String assetPath, File dest) throws Exception {
        InputStream in = context.getAssets().open(assetPath);
        OutputStream out = new FileOutputStream(dest);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        out.flush();
        out.close();
        in.close();
    }

    /**
     * 启动 ADB server
     */
    public Shell.Result startAdbServer() {
        return Shell.cmd(adbBinary.getAbsolutePath() + " start-server").exec();
    }

    /**
     * 停止 ADB server
     */
    public Shell.Result stopAdbServer() {
        return Shell.cmd(adbBinary.getAbsolutePath() + " kill-server").exec();
    }

    /**
     * 获取已连接的设备列表
     */
    public List<String> getDevices() {
        Shell.Result result = Shell.cmd(adbBinary.getAbsolutePath() + " devices").exec();
        List<String> devices = new ArrayList<>();
        if (result.isSuccess()) {
            for (String line : result.getOut()) {
                if (line.contains("\t") && !line.startsWith("List of devices")) {
                    devices.add(line.split("\t")[0]);
                }
            }
        }
        return devices;
    }

    /**
     * 执行 ADB 命令
     */
    public Shell.Result execAdb(String... args) {
        StringBuilder cmd = new StringBuilder(adbBinary.getAbsolutePath());
        for (String arg : args) {
            cmd.append(" ").append(arg);
        }
        return Shell.cmd(cmd.toString()).exec();
    }

    /**
     * 执行 Fastboot 命令
     */
    public Shell.Result execFastboot(String... args) {
        StringBuilder cmd = new StringBuilder(fastbootBinary.getAbsolutePath());
        for (String arg : args) {
            cmd.append(" ").append(arg);
        }
        return Shell.cmd(cmd.toString()).exec();
    }

    /**
     * 列出设备上的文件
     */
    public List<String> listFiles(String deviceId, String path) {
        Shell.Result result = execAdb("-s", deviceId, "shell", "ls", "-la", path);
        List<String> files = new ArrayList<>();
        if (result.isSuccess()) {
            files.addAll(result.getOut());
        }
        return files;
    }

    /**
     * 列出已安装的应用
     */
    public List<String> listPackages(String deviceId) {
        Shell.Result result = execAdb("-s", deviceId, "shell", "pm", "list", "packages");
        List<String> packages = new ArrayList<>();
        if (result.isSuccess()) {
            for (String line : result.getOut()) {
                if (line.startsWith("package:")) {
                    packages.add(line.substring(8));
                }
            }
        }
        return packages;
    }

    /**
     * 安装 APK
     */
    public Shell.Result installApk(String deviceId, String apkPath) {
        return execAdb("-s", deviceId, "install", "-r", apkPath);
    }

    /**
     * 卸载应用
     */
    public Shell.Result uninstallPackage(String deviceId, String packageName) {
        return execAdb("-s", deviceId, "uninstall", packageName);
    }

    /**
     * 执行 Shell 命令
     */
    public Shell.Result execShell(String deviceId, String command) {
        return execAdb("-s", deviceId, "shell", command);
    }

    /**
     * 备份应用数据
     */
    public Shell.Result backupPackage(String deviceId, String packageName, String backupPath) {
        return execAdb("-s", deviceId, "backup", "-f", backupPath, packageName);
    }

    /**
     * 恢复应用数据
     */
    public Shell.Result restoreBackup(String deviceId, String backupPath) {
        return execAdb("-s", deviceId, "restore", backupPath);
    }

    /**
     * 无线调试配对
     */
    public Shell.Result pairWireless(String host, String port, String pairingCode) {
        return execAdb("pair", host + ":" + port, pairingCode);
    }

    /**
     * 连接无线调试设备
     */
    public Shell.Result connectWireless(String host, String port) {
        return execAdb("connect", host + ":" + port);
    }

    /**
     * 断开设备连接
     */
    public Shell.Result disconnect(String deviceId) {
        return execAdb("disconnect", deviceId);
    }

    public File getAdbBinary() {
        return adbBinary;
    }

    public File getFastbootBinary() {
        return fastbootBinary;
    }

    /**
     * 分区信息类
     */
    public static class PartitionInfo {
        public String name;
        public String blockDevice;
        public long sizeBytes;
        public String slot;

        public PartitionInfo(String name, String blockDevice, long sizeBytes) {
            this.name = name;
            this.blockDevice = blockDevice;
            this.sizeBytes = sizeBytes;
            
            // 判断槽位
            if (name.endsWith("_a")) {
                this.slot = "A";
            } else if (name.endsWith("_b")) {
                this.slot = "B";
            } else {
                this.slot = "其他";
            }
        }

        public String getSizeMB() {
            return String.format("%.2f", sizeBytes / 1024.0 / 1024.0);
        }
    }

    /**
     * 读取本机所有分区
     */
    public List<PartitionInfo> getLocalPartitions() {
        List<PartitionInfo> partitions = new ArrayList<>();
        
        // 方法1：从 /dev/block/by-name/ 读取（推荐）
        Shell.Result byNameResult = Shell.cmd("ls -l /dev/block/by-name/").exec();
        if (byNameResult.isSuccess()) {
            for (String line : byNameResult.getOut()) {
                if (line.contains("->")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 11) {
                        String name = parts[8];
                        String target = parts[10];
                        String blockDevice = "/dev/block/by-name/" + name;
                        
                        // 获取分区大小
                        Shell.Result sizeResult = Shell.cmd("blockdev --getsize64 " + blockDevice).exec();
                        long size = 0;
                        if (sizeResult.isSuccess() && !sizeResult.getOut().isEmpty()) {
                            try {
                                size = Long.parseLong(sizeResult.getOut().get(0).trim());
                            } catch (Exception ignored) {}
                        }
                        
                        partitions.add(new PartitionInfo(name, blockDevice, size));
                    }
                }
            }
        }
        
        // 方法2：如果方法1失败，尝试从 /proc/partitions 读取
        if (partitions.isEmpty()) {
            Shell.Result procResult = Shell.cmd("cat /proc/partitions").exec();
            if (procResult.isSuccess()) {
                for (String line : procResult.getOut()) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 4 && !parts[0].equals("major")) {
                        try {
                            long blocks = Long.parseLong(parts[2]);
                            String name = parts[3];
                            String blockDevice = "/dev/block/" + name;
                            partitions.add(new PartitionInfo(name, blockDevice, blocks * 1024));
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
        
        return partitions;
    }

    /**
     * 提取分区镜像到文件
     */
    public Shell.Result extractPartition(String blockDevice, String outputPath) {
        return Shell.cmd("dd if=" + blockDevice + " of=" + outputPath + " bs=4M").exec();
    }

    /**
     * 刷入镜像到分区
     */
    public Shell.Result flashPartition(String imagePath, String blockDevice) {
        return Shell.cmd("dd if=" + imagePath + " of=" + blockDevice + " bs=4M").exec();
    }

    /**
     * 获取 Fastboot 设备列表
     */
    public List<String> getFastbootDevices() {
        Shell.Result result = execFastboot("devices");
        List<String> devices = new ArrayList<>();
        if (result.isSuccess()) {
            for (String line : result.getOut()) {
                if (line.contains("\tfastboot")) {
                    devices.add(line.split("\t")[0]);
                }
            }
        }
        return devices;
    }
}
