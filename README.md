# Dsu Manager

Dsu Manager is an Android utility for managing Dynamic System Updates (DSU) and GSI images on rooted devices. It provides ROOT and GSI status checks, ZIP-based GSI installation, userdata sizing, DSU boot and removal actions, installed image management, single-image replacement, custom background artwork, multilingual settings, and a GitHub Releases update entry.

## Historical release notes: 3.6.0

Current source configuration: **3.55.50** (`versionCode` **35550**).

### 3.55.50 integration update
- Refined the embedded YunX origin/about page with rounded, clipped DNA glass panels and distributed corner color accents.
- Opened the built-in Dsu folder and auth-backup browsers as modal dialogs over Settings instead of navigating to a secondary Activity.
- Kept Dsu and YunX download task/history stores independent while sharing the download UI and interaction design.

### 🚀 下载速度优化
- **小米ROM**: 16线程+8MB分片，实测速度达到 **52 MB/s**
- **vivo ROM**: 4线程+16MB分片（固定最优配置）
- **OPPO ROM**: 16线程+8MB分片，实测速度 **54-60 MB/s**

### 🐛 核心Bug修复
- **Referer逻辑修复**: 只对cdnorg.d.miui.com和aliyuncs.com添加Referer
  - 修复bigota和hugeota节点因错误添加Referer导致的下载失败问题
- **状态栏污染修复**: ROM查询界面滚动时内容不再进入状态栏区域

### 🔧 技术改进
- ROM 页面顶部集成 YunX 多网盘分享解析入口，位于小米 ROM 查询卡片之前
- YunX 文件数据传输统一复用 Dsu Manager 已有 aria2c（多连接、断点续传）；Cookie、Referer、User-Agent 随任务传递
- OkHttp 仅用于网盘接口/登录与轻量大小探测，不作为文件数据传输后端
- 磁力/BT 与 HLS/m3u8 暂不支持，不会回退到 OkHttp 或 Gopeed 下载
- vivo文件名多字段提取（pkName/fileName/filename/downloadUrl）

### 📦 Download
- [Release APK (7.7 MB)](https://github.com/955xiaolan520/Dsu-Manager/releases/download/v3.6.0/Dsu-Manager-3.6.0.apk) - MD5: b415ea88f518436470ed071ebff736e3
- [Debug APK (9.6 MB)](https://github.com/955xiaolan520/Dsu-Manager/releases/download/v3.6.0/Dsu-Manager-3.6.0-debug.apk) - MD5: b216c05f67463b37fb1e932f80630c49

## Build

Prepared toolchain: Android SDK API 37.0, AGP 9.1.1, Gradle 9.3.1, Kotlin 2.2.10, KSP 2.3.12, Java 21, CMake 3.22.1, and NDK 28.2.13676358. The SDK path is stored locally in `local.properties`.

```bash
# Build the release APK
./gradlew assembleRelease
```

Release signing is configured to use the repository-root `release-keystore.jks` as requested. **This keystore is tracked in the GitHub repository and should be treated as publicly exposed; do not rely on it as a confidential production signing key.** Create/rotate a private signing key before official distribution, and never commit private signing credentials or publish build logs containing them.

## Requirements

- Android Studio or a compatible Android SDK and Gradle installation
- Android 10 or newer on the target device
- ROOT access for DSU operations
- A device with Dynamic System support

## License and acknowledgements

The ROM page includes an embedded YunX (云析) network-drive parser and downloader, adapted from [CYQawa/YunX](https://github.com/CYQawa/YunX) at commit `bdafe9ee056be47d9e1567203e07312046df679b`. The integrated source and this combined application are distributed under GNU AGPL-3.0-or-later; see [`LICENSE`](LICENSE), [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md), and the in-app license notice.

In the embedded feature, **all** HTTP(S) downloads from share parsing and logged-in cloud-drive browsing use Dsu Manager's native aria2c runner. The two surfaces reuse the same task-card UI and interactions, but tasks and history are source-partitioned: YunX tasks appear only in the YunX workspace Download tab, while ROM/direct-link tasks appear only in the standalone Dsu Download Manager; the lists and histories do not mix. Notifications open the matching surface. Completion notifications include the filename and formatted file size. Files default to `/storage/emulated/0/Download/DsuManager/Yunpan`, and Settings can pick another folder through Dsu Manager's built-in file browser. Platform request headers are encrypted by Android Keystore for pause/resume. OkHttp remains for YunX API/login and small metadata probes only. HLS/m3u8 and magnet/BT links are explicitly rejected rather than sent to a different transfer backend.

The Gopeed Java bridge classes are retained from the upstream snapshot with their separate GPL-3.0 notice, but the embedded UI does not launch Gopeed and its tasks are routed only to aria2c. The Gopeed native engine binary is not bundled. Other third-party dependencies retain their respective licenses.

The YunX source snapshot is under `yunx/`; its entry card is at the top of the ROM menu, before Xiaomi ROM lookup. The embedded workspace uses Dsu Manager's DNA-inspired blue-gray liquid-glass styling and safe system-bar insets. Its navigation retains the parser, cloud-account/login screens, bookmarks, clipboard parsing, proxy, credential import/export, About and license credits. The download-location setting and authentication import use Dsu Manager's built-in file browser. Standalone engine/thread/speed tuning and inert appearance controls are hidden because the host aria2c service owns transfers and the host controls the app appearance. Vertical scrolling is enabled; the bottom navigation floats above the scrollable content with no tab transition animation. The standalone Dsu Download Manager remains its own page while reusing YunX's glass task-list UI with an isolated host data source. YunX's standalone onboarding is skipped. This repository retains Dsu-Manager's own launcher, application class, and signing setup.
