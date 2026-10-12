# Dsu-Manager 3.60.08 Release

版本 `3.60.08`（versionCode `36008`），Android 17 / API 37，Release 使用仓库 JKS。

本次以用户提供的 `原脚本.zip` 为准，恢复原始 `del_vbmeta.sh` 与 `patch_selinux.sh` 内容；唯一脚本语义调整是将路径变量 `$DNA_DIR` 改为 `$PDNA_DIR`，不修改 AVB hexpatch 或宽容 2.0 的处理流程。

原脚本的输入/输出位置保持其原约定：vbmeta 输入 `/sdcard/PDNA/<img>`，结果 `/sdcard/PDNA/out/<img>`；宽容 2.0 工作目录 `/sdcard/PDNA/out/permissive`，合并结果 `/sdcard/PDNA/out/vendor_boot.img`。DNA runner 注入 `PDNA_DIR=/sdcard/PDNA`。脚本执行前会从 APK assets 覆盖旧私有副本，避免旧路径脚本继续运行。

构建命令：`./gradlew --no-daemon assembleRelease`
