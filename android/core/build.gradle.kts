plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.relay.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 29                       // Note 10+ shipped with Android 9/10
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField(
            "String",
            "RELAY_SERVER_URL",
            "\"${project.findProperty("relayServerUrl") ?: "https://hatamidev.com"}\"",
        )
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Socket.IO client — control plane
    api("io.socket:socket.io-client:2.1.1") {
        exclude(group = "org.json", module = "json")
    }
    api("com.squareup.okhttp3:okhttp:4.12.0")

    // WebRTC — media plane. Exposes the canonical `org.webrtc` package.
    api("io.github.webrtc-sdk:android:125.6422.07")

    api("androidx.core:core-ktx:1.13.1")
    api("androidx.security:security-crypto:1.1.0-alpha06")
    api("androidx.datastore:datastore-preferences:1.1.1")

    implementation("com.google.firebase:firebase-messaging-ktx:24.0.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
