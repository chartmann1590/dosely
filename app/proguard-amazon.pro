# Amazon build only (see app/build.gradle.kts): S()/L() resolve UI strings by name via R.string reflection.
-keepclassmembers class com.dosely.app.R$string { public static <fields>; }
-keep class com.dosely.app.R$string
