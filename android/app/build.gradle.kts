// The Android app. Kotlin is built into the Android Gradle Plugin 9, so only the Compose
// compiler plugin is added here.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.chaquopy) // Python 3.13 inside the app, for yt-dlp and LAME (ADR-0024)
}

android {
    namespace = "io.github.conditionstech.sdm"
    compileSdk = 37

    defaultConfig {
        // Permanent once released (ADR-0023).
        applicationId = "io.github.conditionstech.sdm"
        minSdk = 29 // Android 10: Download/SDM through MediaStore without a storage permission.
        targetSdk = 37
        // The Android app has its own versions, released with tags like android-v1.0.0 (M14f).
        versionCode = 1
        versionName = "1.0.0"
        // Tests that need a phone (the real media converter): gradlew connectedDebugAndroidTest
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Chaquopy's Python 3.12+ exists for 64-bit phones only (ADR-0024).
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // The release key never enters Git (ADR-0023): GitHub Actions decodes it from a secret and
    // passes its path and passwords in SDM_KEYSTORE* variables. Without them (a test build on
    // a PC) the release build is signed with the debug key, so it can't be mistaken for SDM's.
    val releaseKey = System.getenv("SDM_KEYSTORE")
    signingConfigs {
        create("release") {
            if (releaseKey != null) {
                storeFile = file(releaseKey)
                storePassword = System.getenv("SDM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SDM_KEY_ALIAS")
                keyPassword = System.getenv("SDM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName(if (releaseKey != null) "release" else "debug")
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
        version = "3.13" // lameenc's Android wheels stop at Python 3.13 (ADR-0024)
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
    implementation(libs.androidx.media3.exoplayer) // the players (M17, ADR-0025; Apache-2.0)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui) // the video surface and subtitles (M17c)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.okhttp.mockwebserver)
}
