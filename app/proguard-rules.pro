# Keep reflection targets used on Android system builds.
-keep class android.gsid.** { *; }

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
