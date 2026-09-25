# Rust JNI 方法为原生注册，保留 native 方法与其声明类。
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.otaku.poet.jni.** { *; }
