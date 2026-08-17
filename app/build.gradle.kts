plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.shotscorer.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shotscorer.app"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

        ndk {
            // Modern Android tablets are arm64-v8a. Skipping other ABIs cuts
            // the OpenCV AAR from ~90 MB (all four) to ~30 MB.
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // shiyinghan / UVCAndroid
    implementation("com.herohan:UVCAndroid:1.0.13")

    // OpenCV — used for bull detection / hold-trace CV
    implementation("org.opencv:opencv:4.11.0")
}
