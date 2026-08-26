# WebRTC is JNI-heavy — stripping it breaks the native bridge at runtime.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

-keep class io.socket.** { *; }
-dontwarn io.socket.**

# Framework-instantiated entry points
-keep class com.relay.client.ClientApp { *; }
-keep class com.relay.client.MainActivity { *; }
-keep class com.relay.client.ui.call.CallActivity { *; }
-keep class com.relay.client.service.** { *; }
-keep class com.relay.client.fcm.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.relay.**$$serializer { *; }
-keepclassmembers class com.relay.** { *** Companion; }
-keepclasseswithmembers class com.relay.** { kotlinx.serialization.KSerializer serializer(...); }

# CameraX + ML Kit
-keep class androidx.camera.** { *; }
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
