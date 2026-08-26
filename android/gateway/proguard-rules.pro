# WebRTC is JNI-heavy — stripping it breaks the native bridge at runtime.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

-keep class io.socket.** { *; }
-dontwarn io.socket.**

# Telecom entry points are instantiated reflectively by the framework.
-keep class com.relay.gateway.call.RelayInCallService { *; }
-keep class com.relay.gateway.call.DialerStubActivity { *; }
-keep class com.relay.gateway.sms.SmsBroadcastReceiver { *; }
-keep class com.relay.gateway.sms.SmsResultReceiver { *; }
-keep class com.relay.gateway.service.** { *; }
-keep class com.relay.gateway.fcm.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.relay.**$$serializer { *; }
-keepclassmembers class com.relay.** { *** Companion; }
-keepclasseswithmembers class com.relay.** { kotlinx.serialization.KSerializer serializer(...); }

-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
