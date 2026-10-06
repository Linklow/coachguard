plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

mavenPublishing {
    // Uploads a deployment that is released by hand in the Central Portal after review.
    publishToMavenCentral(automaticRelease = false)
    // The signing key lives in ~/.gradle/gradle.properties. Without it, local publishing still works
    // and Maven Central rejects the unsigned upload during validation.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()

    pom {
        name = "CoachGuard"
        description = "On-device detection of coached payment scams for Android banking and fintech apps: " +
            "call, screen-capture and remote-access signals, a per-user behavioral model and an explainable risk score."
        inceptionYear = "2026"
        url = "https://github.com/Linklow/coachguard"
        licenses {
            license {
                name = "The Apache Software License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "Linklow"
                name = "Ivan Mishchenko"
                url = "https://github.com/Linklow"
            }
        }
        scm {
            url = "https://github.com/Linklow/coachguard"
            connection = "scm:git:https://github.com/Linklow/coachguard.git"
            developerConnection = "scm:git:ssh://git@github.com/Linklow/coachguard.git"
        }
    }
}

android {
    namespace = "io.github.linklow.coachguard"
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
