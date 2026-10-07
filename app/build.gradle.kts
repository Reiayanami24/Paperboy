plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.paperboy.android"
    compileSdk = 30

    defaultConfig {
        applicationId = "com.paperboy.android"
        minSdk = 30
        targetSdk = 30
        versionCode = 1
        versionName = "1.0"

        ndk {
            // arm64-v8a: the BOOX Tab Ultra C and nearly every other current device.
            // armeabi-v7a: older 32-bit devices. x86_64: the Android Studio emulator.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// No third-party dependencies on purpose: plain framework APIs only, so
// compileSdk 30 never conflicts with a newer AndroidX artifact.
