# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}
# 1. CRITICAL: Preserve generic signatures for Reflection
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses

# 2. Protect your specific data models
-keep class com.m00seInc.gBars.PokeEntry { *; }
-keep class com.m00seInc.gBars.NetworkLog { *; }
-keep class com.m00seInc.gBars.AppMode { *; }

# 3. Protect Gson's internal types
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep public class * implements java.lang.reflect.Type { *; }

# 1. Protect internal vendor hooks for GC and Scroll optimization
-keep class com.oplus.** { *; }
-keep class com.coloros.** { *; }
-keep class com.oppo.** { *; }

# 2. Prevent R8 from stripping reflection-based method hooks
-keepclassmembers class * {
    *** callGcSupression(...);
    *** setWindowStopped(...);
}

# 3. Allow system server to access your package info for thermal stats
-keepattributes *Annotation*, Signature, EnclosingMethod

# Strip out all Debug and Verbose logging statements in the release build
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static boolean isLoggable(java.lang.String, int);
}