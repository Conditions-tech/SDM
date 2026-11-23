// The Android app. Kotlin is built into the Android Gradle Plugin 9, so only the Compose
// compiler plugin is added here.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.chaquopy) // Python 3.13 inside the app, for yt-dlp and LAME (ADR-0023)
}

android {
    namespace = "io.github.conditionstech.sdm"
    compileSdk = 37

    defaultConfig {
        // Permanent once released (ADR-0022).
        applicationId = "io.github.conditionstech.sdm"
        minSdk = 29 // Android 10: Download/SDM through MediaStore without a storage permission.
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        // Tests that need a phone (the real media converter): gradlew connectedDebugAndroidTest
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Chaquopy's Python 3.12+ exists for 64-bit phones only (ADR-0023).
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true // BuildConfig.VERSION_NAME for the User-Agent
    }
}

kotlin {
    jvmToolchain(17)
}

chaquopy {
    defaultConfig {
        version = "3.13" // lameenc's Android wheels stop at Python 3.13 (ADR-0023)
        // The PC needs the same Python to prepare the packages: SDM_BUILD_PYTHON points at it
        // (uv python install 3.13); otherwise Chaquopy looks for `py -3.13`.
        System.getenv("SDM_BUILD_PYTHON")?.let { buildPython(it) }
        pip {
            install("yt-dlp==2026.10.26") // pinned: an update is a new SDM release
            install("lameenc==1.7.0")
        }
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.okhttp.mockwebserver)
}
