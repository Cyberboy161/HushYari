# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.hushyari.**$$serializer { *; }
-keepclassmembers class dev.hushyari.** {
    *** Companion;
}
-keepclasseswithmembers class dev.hushyari.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Moshi
-keep class com.squareup.moshi.** { *; }
-keep @com.squareup.moshi.JsonQualifier @interface *

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Room
-keep class * extends androidx.room.RoomDatabase

# MMKV
-keep class com.tencent.mmkv.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep model classes used in JSON serialization
-keepclassmembers class dev.hushyari.data.model.** { *; }
-keepclassmembers class dev.hushyari.skills.Skill { *; }
-keepclassmembers class dev.hushyari.skills.SkillStep { *; }
