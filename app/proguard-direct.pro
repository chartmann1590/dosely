# Direct (GitHub / charleshartmann.com) build only (-Pstore=direct), added 2026-11-07.
# All UI text is looked up by name through reflection on R.string (translate/AppLocalizer.kt L10n.ids), so R8 must keep
# the R$string fields (otherwise every label shows its raw key, e.g. "onb_welcome_title").
-keep class com.dosely.app.R$string { *; }
-keepclassmembers class **.R$string { public static <fields>; }
# Never rename or strip framework classes a dependency bundles under android.* (seen in the Amazon builds).
-keep class android.** { *; }
-keepnames class android.** { *; }
