plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mabu.faceoverlay"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.mabu.faceoverlay"
        minSdk = 24
        targetSdk = 28
        versionCode = 1
        versionName = "0.1"

        // RK3288 is armv7 only; ship only that ABI so the APK stays small
        // and we don't carry arm64 native libs we can't use.
        ndk {
            abiFilters += listOf("armeabi-v7a")
        }
        externalNativeBuild {
            cmake { cFlags("-O2", "-Wall") }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Camera1 (android.hardware.Camera) is what RK3288's HAL actually
    // exposes -- the device has a Camera1 API shim and no usable Camera2
    // enumeration, so CameraX fails the camera-validator stage. Camera1
    // is deprecated but stable and 100% reliable on this hardware.

    // Bundled ML Kit Face Detection (no Google Play Services required;
    // ships armeabi-v7a). ~6 MB. Returns landmarks, contours, and
    // optional eyes-open / smile probability.
    implementation("com.google.mlkit:face-detection:16.1.7")
}
