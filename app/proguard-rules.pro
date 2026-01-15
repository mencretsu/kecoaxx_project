# ========== KONFIGURASI AMAN ==========

# Hapus log hanya untuk release build tertentu
# -assumenosideeffects class android.util.Log {
#     public static *** d(...);
#     public static *** v(...);
# }

# Optimasi moderate
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 3
-allowaccessmodification

# Keep penting
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# Keep Kotlin
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }

# Keep coroutines
-keepclassmembers class kotlinx.coroutines.** { *; }

# Keep aplikasi utama
-keep class com.example.ngontol.** { *; }
-keepclassmembers class com.example.ngontol.** { *; }

# Keep data classes (Persona, dll)
-keep class com.example.ngontol.** {
    public <fields>;
    public <methods>;
}

# Keep Moshi
-keep class com.squareup.moshi.** { *; }
-keepclassmembers class ** {
    @com.squareup.moshi.* <methods>;
}

# Keep Gson
-keep class com.google.gson.** { *; }

# Keep Firebase
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }

# Ktor
-keep class io.ktor.** { *; }

# OkHttp
-keep class okhttp3.** { *; }

# Android
-keep class androidx.** { *; }
-keep public class * extends android.app.Application
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service

# Jangan tampilkan warning
-dontwarn **