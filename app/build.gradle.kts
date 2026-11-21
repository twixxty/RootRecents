plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.twixxty.rootrecents"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.twixxty.rootrecents"
        minSdk = 29
        targetSdk = 34
        versionCode = 18
        versionName = "0.18"
    }

    // One fixed signing key for every build (debug and release), so each new APK installs as an
    // update over the previous one instead of needing an uninstall first.
    signingConfigs {
        create("fixed") {
            storeFile = file("rootrecents.jks")
            storePassword = "rootrecents"
            keyAlias = "twixxty"
            keyPassword = "rootrecents"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
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

// The physics, scrolling and layout are all in this project; Shizuku is only the bridge that
// lets the app run its shell commands on phones without root.
dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
