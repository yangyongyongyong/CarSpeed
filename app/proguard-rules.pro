# Project specific ProGuard rules.

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# ---- 高德 3D 地图 / 导航 / 定位 / 搜索（官方建议规则）----
-keep class com.amap.api.** { *; }
-keep class com.autonavi.** { *; }
-keep class com.loc.** { *; }
-keep class com.amap.** { *; }
-keep class com.alibaba.idst.nls.** { *; }
-keep class com.ta.utdid2.** { *; }
-keep class com.ut.device.** { *; }
-dontwarn com.amap.**
-dontwarn com.autonavi.**
-dontwarn com.loc.**
-dontwarn com.alibaba.**
-dontwarn com.ta.**
-dontwarn com.ut.**

# ---- Vosk 离线语音（通过 JNA 调用 native）----
-keep class org.vosk.** { *; }
-dontwarn org.vosk.**

# ---- JNA：native 代码按名字反射访问这些类和成员 ----
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# ---- 原生方法 ----
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
