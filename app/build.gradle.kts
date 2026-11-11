plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aicamerapro"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aicamerapro"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2.0"

        // OnePlus 8 (Snapdragon 865) is arm64-v8a. One single APK, no ABI splits,
        // so there is exactly one file to install.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // A FIXED signing key committed in /keystore.
    // Previously every GitHub Actions run generated a brand-new random debug key, so
    // each new build had a different signature and Android refused to install it
    // over the previous one ("App not installed"). With a fixed key, every build
    // is signed identically and updates install cleanly.
    // NOTE: this is a personal test key (public in the repo). For Play Store
    // publishing, create and keep a private keystore instead.
    signingConfigs {
        create("fixed") {
            storeFile = rootProject.file("keystore/aicamerapro.p12")
            storePassword = "android"
            keyAlias = "aicamerapro"
            keyPassword = "android"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
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

    lint {
        // Lint warnings must never block producing an installable APK.
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    val cameraX = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    // Bundled ML Kit models remain available offline after installation.
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
