plugins {
    id("com.android.application") version "9.1.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
}

android {
    namespace = "io.keyflow.remotekeyboard"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.keyflow.remotekeyboard"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3:1.3.2")
}
