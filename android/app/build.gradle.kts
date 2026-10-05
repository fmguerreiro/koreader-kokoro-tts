plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val piperEndpoint = providers.environmentVariable("PIPER_ENDPOINT").orNull
    ?: throw GradleException("Set PIPER_ENDPOINT to the Piper server URL")

android {
    namespace = "io.github.fmguerreiro.koreaderpiper"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.fmguerreiro.koreaderpiper"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "PIPER_ENDPOINT", "\"$piperEndpoint\"")
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
