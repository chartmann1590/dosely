# Install Referrer is accessed via reflection so it stays optional for host apps.
-keep class com.android.installreferrer.api.** { *; }
-keep class com.hartmann.crosspromo.attribution.** { *; }

# kotlinx-serialization
-keepclassmembers class com.hartmann.crosspromo.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.hartmann.crosspromo.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
