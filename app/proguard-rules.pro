# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*

# Preserve domain models against serialization, bundle reflection, and state serialization
-keep class com.example.model.** { *; }
-keepclassmembers class com.example.model.** { *; }
-keepclassmembers enum com.example.model.** { *; }

# Preserve ViewModel state classes
-keep class com.example.viewmodel.** { *; }
-keepclassmembers class com.example.viewmodel.** { *; }

# Keep data and UI components
-keep class com.example.ui.** { *; }
