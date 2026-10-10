# Keep reflection targets used on Android system builds.
-keep class android.gsid.** { *; }

# YunX is an independent library and creates the host DNA navigation View by class name.
# Keep its class name and public constructor stable in minified release builds.
-keep class com.probiotics.xiaoni.DsuEmbeddedBottomNavigation { *; }
-keep class com.probiotics.xiaoni.DsuEmbeddedBottomNavigation$OnTabSelectedListener { *; }

# ---- v3.41.11：R8 压缩保留规则 ----
# JNI 桥接（so 内符号 Java_native_PayloadExtractNative_* 按类名/方法名绑定，不可改名）
-keep class native.PayloadExtractNative { *; }
# vivo 密钥 SDK（native 层按字段名读写 NativeRequest / NativeResponse）
-keep class com.vivo.seckeysdk.** { *; }
# 所有含 native 方法的类：方法名与 so 符号表绑定
-keepclasseswithmembernames class * {
    native <methods>;
}
# libsu RootService 经反射实例化（Manifest / bindService）
-keep class * extends com.topjohnwu.superuser.ipc.RootService {
    public <init>(...);
}
# AndroidManifest 引入的组件由 AAPT 规则保留，此处无需重复

# YunX Settings calls these host-owned modal picker entry points by reflection.
-keepclassmembers class com.probiotics.xiaoni.DsuDirectoryPickerActivity {
    public static void showDirectoryDialog(android.app.Activity, java.lang.String, java.util.function.Consumer);
    public static void showAuthBackupDialog(android.app.Activity, java.util.function.Consumer);
}
