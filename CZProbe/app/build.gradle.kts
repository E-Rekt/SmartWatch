plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.czprobe"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.czprobe"
        // 26, not 25: SOURCE_ROTARY_ENCODER and the Tiles API both need 26,
        // and the watch is API 28 anyway. Costs us nothing, kills lint noise.
        minSdk = 26
        // 28 matches the device (Wear OS 2.45 / Android 9). Targeting higher
        // buys nothing on an API 28 platform.
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
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

// Deliberately zero dependencies.
dependencies { }
