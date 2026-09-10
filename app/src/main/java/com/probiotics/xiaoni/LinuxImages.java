package com.probiotics.xiaoni;

import android.content.Context;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

final class LinuxImages {
    static final Image[] ALL = {
        new Image("Alpine Linux 3.21.7", "极简系统 · 适合脚本、网络工具和低存储设备", "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.7-aarch64.tar.gz"),
        new Image("Alpine Linux 3.22.5", "极简系统 · 适合脚本、网络工具和低存储设备", "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/alpine-minirootfs-3.22.5-aarch64.tar.gz"),
        new Image("Alpine Linux 3.23.5", "极简系统 · 适合脚本、网络工具和低存储设备", "https://dl-cdn.alpinelinux.org/alpine/v3.23/releases/aarch64/alpine-minirootfs-3.23.5-aarch64.tar.gz"),
        new Image("Alpine Linux 3.24.1", "极简系统 · 适合脚本、网络工具和低存储设备", "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.1-aarch64.tar.gz"),
        new Image("Ubuntu 20.04.5 LTS", "Focal · 适合旧项目、基础开发和稳定命令行工具", "https://cdimage.ubuntu.com/ubuntu-base/releases/focal/release/ubuntu-base-20.04.5-base-arm64.tar.gz"),
        new Image("Ubuntu 22.04.5 LTS", "Jammy · 适合日常开发、Python、编译和服务器工具", "https://cdimage.ubuntu.com/ubuntu-base/releases/jammy/release/ubuntu-base-22.04.5-base-arm64.tar.gz"),
        new Image("Ubuntu 24.04.4 LTS", "Noble · 适合新软件、现代开发工具和长期使用", "https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/ubuntu-base-24.04.4-base-arm64.tar.gz"),
        new Image("Debian 12 (Bookworm)", "稳定版 · 适合服务器、软件包管理和长期运行", "https://images.linuxcontainers.org/images/debian/bookworm/arm64/default/20260904_05:24/rootfs.tar.xz"),
        new Image("Debian 13 (Trixie)", "新稳定版 · 适合测试新软件和 ARM64 开发环境", "https://images.linuxcontainers.org/images/debian/trixie/arm64/default/20260904_05:24/rootfs.tar.xz")
    };
    static final Image LOCAL = new Image("本地 rootfs", "用户选择的本地压缩包环境", "local-rootfs.tar.gz");

    static File root(Context context) { return new File(context.getFilesDir(), "rootfs"); }
    static File archive(Context context, Image image) {
        if (image == LOCAL) {
            File xz = new File(root(context), "local-rootfs.tar.xz");
            if (xz.isFile()) return xz;
            return new File(root(context), "local-rootfs.tar.gz");
        }
        String archiveId = image.name.startsWith("Debian ") ? image.id() + "-pd" : image.id();
        return new File(root(context), archiveId + (image.url.endsWith(".xz") ? ".tar.xz" : ".tar.gz"));
    }
    static File environment(Context context, Image image) { return new File(root(context), image.id()); }

    static boolean hasUsableShell(File environment) {
        if (environment == null || !environment.isDirectory()) return false;
        return new File(environment, "bin/sh").isFile()
                || new File(environment, "bin/bash").isFile()
                || new File(environment, "usr/bin/sh").isFile()
                || new File(environment, "usr/bin/bash").isFile();
    }

    static long storageBytes(File file) {
        if (file == null || !file.exists()) return 0;
        if (Files.isSymbolicLink(file.toPath())) return 0;
        if (file.isFile()) return file.length();
        long total = 0;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) total += storageBytes(child);
        return total;
    }

    static final class Image {
        final String name, description, url;
        Image(String name, String description, String url) { this.name = name; this.description = description; this.url = url; }
        String id() { return name.equals("本地 rootfs") ? "local" : name.toLowerCase().replaceAll("[^a-z0-9]+", "-"); }
    }
}
