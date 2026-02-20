# FFmpeg Kit (moizhassan fork keeps com.arthenica package namespace)
-keep class com.arthenica.ffmpegkit.** { *; }

# AVIF/HEIF encoding via avif-coder (JNI native calls)
-keep class com.radzivon.bartoshyk.avif.** { *; }

# Jsoup HTML parser (uses reflection for tag selection)
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# Coil image loading
-dontwarn coil.**
-keep class coil.** { *; }

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**
