# Keep LiteRT-LM JNI surface
-keep class com.google.ai.edge.litertlm.** { *; }
# Keep ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
# Ktor / serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
# Room
-keep class * extends androidx.room.RoomDatabase
