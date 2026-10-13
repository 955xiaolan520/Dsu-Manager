# User Instruction Memory

This file records user instructions, preferences, and teachings for reference in future interactions.

## Format

### User Instruction Entry
User instruction entries should follow this format:

[User Instruction Summary]
- Date: [YYYY-MM-DD]
- Context: [Mentioned scenario or time]
- Instructions:
  - [Content of user teaching or instruction, described line by line]

### Project Knowledge Entry
Entries discovered by the Agent during task execution should follow this format:

[Project Knowledge Summary]
- Date: [YYYY-MM-DD]
- Context: Discovered by Agent while performing [specific task description]
- Category: [Operations & Deployment|Build Methods|Testing Methods|Troubleshooting & Debugging|Workflow & Collaboration|Environment Configuration]
- Instructions:
  - [Specific knowledge points, described line by line]

## Deduplication Strategy
- Before adding a new entry, check for similar or identical instructions.
- If a duplicate is found, skip the new entry or merge it with the existing one.
- When merging, update the context or date information.
- This helps avoid redundant entries and keeps the memory file tidy.

## Entries

[Project Knowledge Summary]
- Date: 2026-10-13
- Context: Discovered by Agent while preparing the build environment for Dsu Manager (user requested environment prep, build on later instruction)
- Category: Environment Configuration
- Instructions:
  - JDK: Temurin 21.0.12.1 at /opt/jdk-21; JAVA_HOME/ANDROID_HOME exported via /etc/profile.d/android-build.sh, /root/.bashrc, /root/.zshrc
  - Android SDK at /root/android-sdk (per local.properties): platforms/android-37.0, build-tools/37.0.0, ndk/28.2.13676358, cmake/3.22.1, platform-tools
  - SDK package name quirk: compileSdk 37 corresponds to package `platforms;android-37.0` (with dot), plain `platforms;android-37` fails with "Failed to find package"
  - Gradle 9.3.1 distribution pre-fetched into /root/.gradle/wrapper/dists; wrapper validates with `./gradlew --version`
  - Release signing: repo-root release-keystore.jks, storePassword/keyPassword changeit, alias tianming-release, SHA-256 B0:15:6B:C1:84:64:55:3C:1B:76:14:11:D6:8A:D2:D2:F9:45:5F:EA:F7:E0:CD:F6:15:C7:74:FE:E2:23:9D:4E (matches 改动说明.md), valid until 2054
  - Native build required: app/src/main/cpp/CMakeLists.txt builds liblaunch.so via CMake 3.22.1 + NDK; jniLibs additionally packages prebuilt libpayload_dumper.so
  - Build command: `./gradlew assembleRelease` from /workspace (must run in background terminal per platform rules); output app/build/outputs/apk/release/app-release.apk
  - apt sources (Debian 12) only provide OpenJDK 17; JDK 21 must come from Adoptium tarball
  - Network to dl.google.com/services.gradle.org is slow (~200-700 KB/s); large downloads need --max-time plus `curl -C -` resume

[Project Knowledge Summary]
- Date: 2026-10-13
- Context: Discovered by Agent during the first release build (v3.10.60) in this environment
- Category: Build Methods
- Instructions:
  - This VM has a memory balloon: `free -m` available shrinks over time with no process growth; keep build footprint low
  - First build OOM-killed: background-terminal cgroup at memory_percent 50 (3.89G) was exhausted by Gradle daemon + default Kotlin daemon during :app:compileReleaseKotlin
  - Fix that worked: /root/.gradle/gradle.properties (user-level, overrides repo gradle.properties without dirtying the repo) with `kotlin.daemon.jvmargs=-Xmx1024m`; then memory_percent 50 + cpu_percent 200 succeeds (full build ~8 min)
  - AGP 9.1.1 emits V3-only signatures for minSdk 29 (enableV1Signing/enableV2Signing flags are deprecated no-ops); cert fingerprint stays identical to older V1+V2+V3 builds, so self-update/overwrite install remains compatible
  - Release APK ~40MB; verify with /root/android-sdk/build-tools/37.0.0/lib/apksigner.jar (java -jar); output metadata confirms versionCode/versionName
  - APK download links for the user: copy APK to /workspace/apk-dist/ as xiaoni-v<version>.apk, serve `python3 -m http.server 8000 --directory /workspace/apk-dist` in a background terminal (timeout 0), then request_preview(8000) → https://8000-<hash>.monkeycode-ai.online/<file>.apk
  - OTG/ADB page structure: MainActivity.buildOtgFastbootPanel builds the page; OtgFlashHelper.kt owns flash flows; FileBrowserDialog is the app-wide built-in glass file manager; RootfsFilesActivity is the legacy Linux-Dsu picker (still used by 单分区刷写/ADB 推送)
