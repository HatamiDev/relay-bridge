pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // webrtc-sdk (io.github.webrtc-sdk:android) — maintained prebuilt of
        // libwebrtc that still exposes the canonical `org.webrtc` package.
        maven("https://jitpack.io")
    }
}

rootProject.name = "SmsCallRelay"

// One installable APK. `gateway` and `client` are libraries whose components
// are merged into it; the role picker at first launch decides which half of the
// bridge this install behaves as.
include(":app")
include(":core")
include(":gateway")
include(":client")
