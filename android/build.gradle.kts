plugins {
    alias(libs.plugins.android.application) apply false
    // Not applied: only puts the newer Kotlin Gradle plugin on the classpath for AGP's built-in Kotlin.
    alias(libs.plugins.kotlin.android) apply false
}
