plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.relay.gateway"
    compileSdk = 35

    defaultConfig {
        minSdk = 29

        buildConfigField(
            "String",
            "RELAY_SERVER_URL",
            "\"${project.findProperty("relayServerUrl") ?: "https://hatamidev.com"}\"",
        )
        // Gateway-only: allows this device to create rooms on your server.
        buildConfigField(
            "String",
            "BOOTSTRAP_SECRET",
            "\"${project.findProperty("relayBootstrapSecret") ?: ""}\"",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/INDEX.LIST",
            "/META-INF/DEPENDENCIES",
        )
    }
}

dependencies {
    api(project(":core"))
    // The glass design system (theme + primitives) lives in :client. The
    // gateway console reuses it so both roles look like one app. There is no
    // cycle: :client never references :gateway.
    implementation(project(":client"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    val compose = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(compose)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // QR generation for the pairing screen
    implementation("com.google.zxing:core:3.5.3")

    implementation(platform("com.google.firebase:firebase-bom:33.4.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")

    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
}
