// Versions are pinned in gradle/libs.versions.toml: AGP 8.5.2 + Kotlin 1.9.24 +
// Room 2.6.1 (the last Room line for Kotlin 1.9 — do NOT upgrade Room without
// upgrading Kotlin) with Gradle 8.7 via the committed wrapper. Decline Android
// Studio's upgrade prompts.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false
}
