# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep ML Kit classes
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }

# Keep PDFBox classes
-keep class com.tom_roush.pdfbox.** { *; }
-keep class org.apache.** { *; }

# Keep data classes
-keep class com.karabagoo.pdfquestionextractor.data.** { *; }
