# BankNotify ProGuard Rules

-keep class com.banknotify.** { *; }
-keep class com.banknotify.db.** { *; }
-keep class com.banknotify.ui.** { *; }
-keepclassmembers class com.banknotify.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Room
-keep class androidx.room.** { *; }
-keepclassmembers class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers @androidx.room.Entity class * { *; }

# Coroutines
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Gson
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Security Crypto
-keep class androidx.security.crypto.** { *; }
