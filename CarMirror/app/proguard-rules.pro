# CarMirror ProGuard Rules

# Keep scrcpy bridge and decoder classes intact
-keep class com.carmirror.util.** { *; }
-keep class com.carmirror.service.** { *; }
-keep class com.carmirror.ui.** { *; }

# Keep MediaCodec-related classes
-keep class android.media.** { *; }

# Keep jmDNS for iOS service discovery
-keep class javax.jmdns.** { *; }
-dontwarn javax.jmdns.**

# Material Components
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**
