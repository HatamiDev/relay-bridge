# WebRTC is JNI-heavy — stripping it breaks the native bridge at runtime.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

-keep class io.socket.** { *; }
-dontwarn io.socket.**

# Framework-instantiated entry points across all three modules.
-keep class com.relay.app.** { *; }
-keep class com.relay.gateway.call.RelayInCallService { *; }
-keep class com.relay.gateway.call.DialerStubActivity { *; }
-keep class com.relay.gateway.sms.** { *; }
-keep class com.relay.gateway.service.** { *; }
-keep class com.relay.gateway.ui.GatewayActivity { *; }
-keep class com.relay.client.ReceiverActivity { *; }
-keep class com.relay.client.ui.call.CallActivity { *; }
-keep class com.relay.client.service.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses, Signature
-keep,includedescriptorclasses class com.relay.**$$serializer { *; }
-keepclassmembers class com.relay.** { *** Companion; }
-keepclasseswithmembers class com.relay.** { kotlinx.serialization.KSerializer serializer(...); }

# CameraX + ML Kit (QR pairing fast path)
-keep class androidx.camera.** { *; }
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# JCE / Keystore reflection used by the ECDH pairing exchange.
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }
