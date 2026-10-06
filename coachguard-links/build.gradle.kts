plugins {
    alias(libs.plugins.android.library)
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

android {
    namespace = "io.github.linklow.coachguard.links"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        // Version-update checks are noise here: AGP is pinned to what the supported Android Studio understands.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        allWarningsAsErrors = true
    }
}

dependencies {
    testImplementation(libs.junit)
}
