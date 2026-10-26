// The download engine: plain Kotlin on the JVM, never Android (like sdm.engine never imports
// Qt), so `gradlew :engine:test` runs on the PC in seconds.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
