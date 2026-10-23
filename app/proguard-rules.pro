# Kotlin Serialization
-keepclassmembers class ** {
    @kotlinx.serialization.Serializable *;
}
-keepclassmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepattributes *Annotation*, EnclosingMethod, InnerClasses, Signature
-keep class kotlinx.serialization.json.** { *; }

# AWS SDK for Kotlin
-keep class aws.sdk.kotlin.services.s3.** { *; }
-keep class aws.smithy.kotlin.runtime.** { *; }
-keep class aws.sdk.kotlin.runtime.** { *; }
-dontwarn aws.sdk.kotlin.**
-dontwarn aws.smithy.kotlin.**

# Project Data Classes
-keep class com.tk.stranger.** { *; }
-keep class com.tk.stranger.ChatMessage { *; }
-keep class com.tk.stranger.MessageContent { *; }
-keep class com.tk.stranger.TextEntity { *; }
-keep class com.tk.stranger.ContactInformation { *; }
-keep class com.tk.stranger.LocationInformation { *; }
-keep class com.tk.stranger.SetupState { *; }

# OkHttp (Internal to AWS SDK likely)
-keepattributes Signature
-keepattributes *Annotation*
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepnames class kotlinx.coroutines.android.AndroidExceptionPreHandler {}
-keepnames class kotlinx.coroutines.android.AndroidDispatcherFactory {}
-keep class kotlinx.coroutines.android.** { *; }

# Paging
-keep class androidx.paging.PagingSource { *; }

# Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
