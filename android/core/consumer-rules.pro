# WebRTC uses JNI reflection extensively — never obfuscate or strip it.
-keep class org.webrtc.** { *; }
-keepclassmembers class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Socket.IO / Engine.IO
-keep class io.socket.** { *; }
-dontwarn io.socket.**

# kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.relay.**$$serializer { *; }
-keepclassmembers class com.relay.** { *** Companion; }
-keepclasseswithmembers class com.relay.** { kotlinx.serialization.KSerializer serializer(...); }
