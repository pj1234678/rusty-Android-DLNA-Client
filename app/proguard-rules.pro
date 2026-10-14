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

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# The codebase uses no reflection/serialization of its own; libraries
# (Media3, AppCompat, RecyclerView, Navigation) ship consumer rules.
# Manifest-referenced Activities and nav-graph Fragments are kept by AGP.
# Belt-and-braces keeps for app state R8 must not rename/strip:
-keep class com.example.rustydlnaclient.VideoProgressManager { *; }
-keep class com.example.rustydlnaclient.VideoProgressManager$VideoProgress { *; }
-keep class com.example.rustydlnaclient.MainActivity$DisplayItem* { *; }
-keep class com.example.rustydlnaclient.PlayerHolder { *; }
-keep class com.example.rustydlnaclient.perf.** { *; }

# Max-perf release: Log.d has no side effects, so R8 deletes the calls
# outright — including the string building in hot paths (row bind, scroll,
# browse). Deliberately scoped to `d` only: `v` feeds the PerfBenchmark
# harness, `w/e` stay for production diagnostics, and `isLoggable` keeps
# working so guarded blocks behave. All app Log.d calls are bare (return
# value unused), so this is sound.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
}

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile