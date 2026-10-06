buildscript {
    dependencies {
        // AGP 9 compiles Kotlin with its built-in Kotlin support. Pinning the Kotlin Gradle plugin
        // keeps the Kotlin compiler and the Compose compiler plugin on the same version.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.dokka) apply false
}
