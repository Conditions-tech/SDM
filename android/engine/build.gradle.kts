// The download engine: plain Kotlin on the JVM, never Android (like sdm.engine never imports
// Qt), so `gradlew :engine:test` runs on the PC in seconds.
plugins {
    `java-library` // for `api`: the app uses OkHttp and coroutines types the engine exposes
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.kotlinx.coroutines.test)
}
