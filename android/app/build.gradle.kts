plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val kokoroEndpoint = providers.environmentVariable("KOKORO_ENDPOINT").orNull
    ?: throw GradleException("Set KOKORO_ENDPOINT to the Kokoro server URL")

android {
    namespace = "io.github.fmguerreiro.koreaderkokoro"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.fmguerreiro.koreaderkokoro"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "KOKORO_ENDPOINT", "\"$kokoroEndpoint\"")
    }

    buildFeatures {
        buildConfig = true
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
