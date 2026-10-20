# Android 上游解析器集成说明

- 上游：<https://github.com/wujunwei928/parse-video>
- 固定提交：`57590df94b49aafc827eb98e85c845eaa1cb3daf`
- 许可证：MIT，见 `../parse-video/LICENSE`
- 已纳入上游 `parser/`、`utils/` 与模块依赖文件；Go bridge 将 `ParseVideoShareUrl` 结果编码为 JSON。
- `app/libs/parse-video-mobile.aar` 包含 `armeabi-v7a` 与 `arm64-v8a`。AAR 生成后会把 Go Mobile 的通用 Java runtime 包 `go.*` 重定位为 `g2.*`，并将 JNI 符号及 `libgojni.so` / `System.loadLibrary("gojni")` 改为独立的 `libdsuparsevideo.so` / `System.loadLibrary("dsuparsevideo")`，避免与 Dsu 已使用的 Gopeed `go.Seq` 和 `libgojni.so` 重名冲突。
- Dsu 优先本地运行上游 parser；不支持、无媒体结果、网络异常或 JNI 不可用时，回退到原 `api.bugpk.com/api/short_videos`。旧接口地址保存在 `ShortVideoActivity.LEGACY_API_BASE`。汽水音乐由 Dsu 独立的分享页解析逻辑处理，上游仓库不含汽水音乐解析。
- 上游共享 Resty 客户端配置 12 秒请求超时，避免网络请求无限等待。

## 已知限制

上游支持平台按 `parser/vars.go` 的真实域名映射实现；结果仍受平台接口、地区、限流、作品权限和网络环境影响，不能保证每个链接都能得到直链。上游抖音网页详情后备路径需要部署者提供 `PARSE_VIDEO_DOUYIN_COOKIE`；Dsu 不内置账号 Cookie，因此该路径不可用时会回退原解析接口。AAR 不含 x86/x86_64 ABI，这些设备会直接使用旧接口。

## 重新生成 AAR

需要 Go 1.26+、Android SDK 37、NDK 28.2、`gomobile`/`gobind`。设置本机环境变量后，在仓库根目录运行：

```sh
export ANDROID_HOME="$HOME/Android/Sdk"   # 按本机 SDK 路径调整
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
export PATH="$HOME/go/bin:$PATH"
export GOTOOLCHAIN=go1.26.10+auto
./third_party/parse-video-mobile/build-aar.sh
```

构建脚本会执行 `go mod tidy`、`gomobile bind` 与 JNI 库改名。常规 Android Gradle Release 构建使用已纳入源码的 AAR，不要求每次构建 APK 都重新运行 Go Mobile。
