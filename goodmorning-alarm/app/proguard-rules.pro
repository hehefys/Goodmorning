# 每日早安：Release 混淆规则（当前未开启 minify，规则先行保留）

# ---- kotlinx-serialization ----
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.goodmorning.alarm.**$$serializer { *; }
-keepclassmembers class com.goodmorning.alarm.** { *** Companion; }
-keepclasseswithmembers class com.goodmorning.alarm.** { kotlinx.serialization.KSerializer serializer(...); }

# ---- Media3 / ExoPlayer ----
-dontwarn org.checkerframework.**
-keep class androidx.media3.** { *; }

# ---- OkHttp ----
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
