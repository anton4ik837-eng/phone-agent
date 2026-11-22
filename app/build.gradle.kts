plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.phoneagent"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.phoneagent"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
}
