// Versions are pinned: AGP 8.5.2 + Kotlin 1.9.24, with Gradle 8.7 via the
// committed wrapper. Decline Android Studio's AGP/Gradle upgrade prompts
// until the on-watch deploy loop is proven.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
