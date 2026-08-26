plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.gms.google-services")
}

/**
 * The single installable APK.
 *
 * It bundles both halves of the bridge. Which one runs is decided at first
 * launch by the role picker and stored in the encrypted preferences — the
 * gateway-only components (SMS receiver, InCallService) are present on every
 * install but stay inert unless the role is GATEWAY.
 */
android {
    namespace = "com.relay.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.relay.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField(
            "String",
            "RELAY_SERVER_URL",
            "\"${project.findProperty("relayServerUrl") ?: "https://hatamidev.com"}\"",
        )
        // Authorises creating a room on your server. Only the gateway role uses
        // it, but it ships in every APK because there is only one APK — treat
        // the build as a secret and do not publish it.
        buildConfigField(
            "String",
            "BOOTSTRAP_SECRET",
            "\"${project.findProperty("relayBootstrapSecret") ?: ""}\"",
        )
    }

    signingConfigs {
        // Populated from -P flags or ~/.gradle/gradle.properties so the keystore
        // never has to live in the repository.
        val storePath = project.findProperty("relayKeystore") as String?
        if (storePath != null) {
            create("release") {
                storeFile = file(storePath)
                storePassword = project.findProperty("relayKeystorePassword") as String?
                keyAlias = project.findProperty("relayKeyAlias") as String?
                keyPassword = project.findProperty("relayKeyPassword") as String?
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            // Deliberately NO applicationIdSuffix.
            //
            // A ".debug" suffix would change the package to com.relay.app.debug,
            // which then has to be registered separately in Firebase or
            // `processDebugGoogleServices` fails outright. It would also mean a
            // debug install and a release install are two different apps that
            // cannot upgrade over each other — on a bridge where both phones
            // must run the *same* build, that is a trap, not a convenience.
            versionNameSuffix = "-debug"
        }
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
        // libwebrtc ships large .so files; keep them uncompressed so the loader
        // can mmap them instead of extracting to /data at install time.
        jniLibs.useLegacyPackaging = false
    }

    // One universal APK — the user side-loads a single file onto both phones.
    splits { abi { isEnable = false } }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":gateway"))
    implementation(project(":client"))

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    val compose = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(compose)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation(platform("com.google.firebase:firebase-bom:33.4.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")
}
