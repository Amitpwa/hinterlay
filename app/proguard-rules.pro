# Google Mobile Ads, UMP and ML Kit ship their own consumer R8 rules.
# Keep line numbers so Play Console crash reports stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- AR stack: SceneView + Filament + ARCore (1.1) ---
# Filament and ARCore are JNI-heavy and resolve many classes by name from
# native code. If R8 renames/strips them the signed (minified) build crashes
# even though debug (no minify) runs fine. Keep the whole AR stack.
-keep class com.google.android.filament.** { *; }
-keep class com.google.ar.** { *; }
-keep class io.github.sceneview.** { *; }
# Preserve every JNI entry point app-wide (native method bindings).
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-dontwarn com.google.android.filament.**
-dontwarn com.google.ar.**
-dontwarn io.github.sceneview.**
