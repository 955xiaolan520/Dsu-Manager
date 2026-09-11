# Dsu Manager

Dsu Manager is an Android utility for managing Dynamic System Updates (DSU) and GSI images on rooted devices. It provides ROOT and GSI status checks, ZIP-based GSI installation, userdata sizing, DSU boot and removal actions, installed image management, single-image replacement, custom background artwork, multilingual settings, and a GitHub Releases update entry.

## Version 3.6.0

### 🚀 下载速度优化
- **小米ROM**: 16线程+8MB分片，实测速度达到 **52 MB/s**
- **vivo ROM**: 4线程+16MB分片（固定最优配置）
- **OPPO ROM**: 16线程+8MB分片，实测速度 **54-60 MB/s**

### 🐛 核心Bug修复
- **Referer逻辑修复**: 只对cdnorg.d.miui.com和aliyuncs.com添加Referer
  - 修复bigota和hugeota节点因错误添加Referer导致的下载失败问题
- **状态栏污染修复**: ROM查询界面滚动时内容不再进入状态栏区域

### 🔧 技术改进
- 使用OkHttpDownloader替代aria2c，解决SELinux权限问题
- 共享RandomAccessFile + synchronized写入，避免多线程I/O竞争
- 支持断点续传
- ConnectionPool优化（连接池大小=线程数×2）
- vivo文件名多字段提取（pkName/fileName/filename/downloadUrl）

### 📦 Download
- [Release APK (7.7 MB)](https://github.com/955xiaolan520/Dsu-Manager/releases/download/v3.6.0/Dsu-Manager-3.6.0.apk) - MD5: b415ea88f518436470ed071ebff736e3
- [Debug APK (9.6 MB)](https://github.com/955xiaolan520/Dsu-Manager/releases/download/v3.6.0/Dsu-Manager-3.6.0-debug.apk) - MD5: b216c05f67463b37fb1e932f80630c49

## Build

The project uses Gradle and Android SDK 37.

```bash
# Build the release APK
./gradlew assembleRelease
```

The release signing keystore is intentionally excluded from the repository. Configure a local signing key before producing a distributable release APK.

## Requirements

- Android Studio or a compatible Android SDK and Gradle installation
- Android 10 or newer on the target device
- ROOT access for DSU operations
- A device with Dynamic System support

## License and acknowledgements

This project is an independent utility built around Android's public Dynamic System APIs and documented DSU workflows. The application does not include the source code or binary implementation of third-party applications.
