// SDM for Android (M14, ADR-0023). Two modules:
// - engine: plain Kotlin/JVM, no Android imports; its tests run on the PC.
// - app: the Android app (Compose screens, service, notifications, history).
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SDM"
include(":engine", ":app")
