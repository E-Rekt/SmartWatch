plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.cztask"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cztask"
        minSdk = 26          // rotary source constant + native java.time; device is 28
        targetSdk = 28       // matches device; exact alarms are permission-free here
        versionCode = 1
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Clock floor: real time can never be earlier than the moment this APK was
        // built. Quantized to UTC-day so incremental builds aren't invalidated
        // more than once per day.
        buildConfigField(
            "long", "BUILD_FLOOR_UTC_MILLIS",
            "${(System.currentTimeMillis() / 86_400_000L) * 86_400_000L}L"
        )
    }

    buildFeatures { buildConfig = true }

    // Exposes committed schema JSON to MigrationTestHelper (used from v2 onward).
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.activity.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.recyclerview)
    implementation(libs.wear)
    implementation(libs.wear.input)
    // Classic Wear OS 2 watch face API (CanvasWatchFaceService) — the androidx
    // watchface library targets Wear 3; this is the proven path on API 28.
    implementation(libs.wearable.support)
    compileOnly(libs.wearable.platform)
    // Tiles v1 schema — pre-protolayout artifact; the renderer in this watch's
    // 2.66 home binds androidx.wear.tiles.action.BIND_TILE_PROVIDER (verified:
    // Spotify's tiles resolve on-device).
    implementation(libs.wear.tiles)
    implementation(libs.concurrent.futures)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.coroutines.android)
    ksp(libs.room.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.coroutines.test)
}
