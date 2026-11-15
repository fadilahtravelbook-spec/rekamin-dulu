# R8 / ProGuard rules for Rekamin Dulu

# Keep data models used for reflection/serialization or unit tests
-keep class com.bangdillah.rekamindulu.backend.** { *; }
-keep class com.bangdillah.rekamindulu.service.ScreenRecordingService$CaptureTelemetry { *; }

# Keep Glide integration classes
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
    <init>(...);
}
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}

# Preserve line numbers for readable crash stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
