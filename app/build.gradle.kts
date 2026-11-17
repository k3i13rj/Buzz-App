plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tipbridge.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tipbridge.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    // On GitHub, sign with the fixed key from the DEBUG_KEYSTORE_B64 secret
    // (the workflow saves it and sets TIPBRIDGE_KEYSTORE), so every build has
    // the same signature and updates install over older versions.
    signingConfigs {
        getByName("debug") {
            System.getenv("TIPBRIDGE_KEYSTORE")?.let {
                storeFile = file(it)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
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
