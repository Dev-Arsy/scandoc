plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.scandoc.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.scandoc.app"
        minSdk = 26          // Android 8.0+, covers every MIUI 12 device
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the APK installs directly (no keystore needed).
            signingConfig = signingConfigs.getByName("debug")
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
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.1")

    // Document scanner: edge detection, perspective crop, shadow removal, filters, multi-page, PDF.
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    // On-device OCR (bundled model, works offline) used for the Word export.
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
