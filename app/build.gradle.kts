plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.keepalarm.todo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.keepalarm.todo"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
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

dependencies {
    // On-device text recognition (model bundled, works offline) for screenshot verification.
    implementation("com.google.mlkit:text-recognition:16.0.1")
}

// Only ML Kit is used as a library; the rest is the Android framework: the app uses only the Android framework,
// which keeps the build simple and avoids dependency problems.
