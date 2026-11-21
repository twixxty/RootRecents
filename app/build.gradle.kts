plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.rootrecents"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.rootrecents"
        minSdk = 29
        targetSdk = 34
        versionCode = 16
        versionName = "0.16"
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

// No third-party libraries: the physics, scrolling and layout are all in this project.
dependencies {
}
