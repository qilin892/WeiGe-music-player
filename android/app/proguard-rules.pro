# QuickJS 通过 JNI/反射调用，符号不能混淆，否则运行时直接找不到方法
-keep class com.whl.quickjs.** { *; }
-dontwarn com.whl.quickjs.**

# 音源脚本桥接层：native 函数名和反射用得比较多，整体保留
-keep class com.weige.music.script.** { *; }

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Media3
-dontwarn androidx.media3.**

# Compose
-dontwarn androidx.compose.**

# 数据模型会被 JSON 反射构造
-keep class com.weige.music.model.** { *; }
