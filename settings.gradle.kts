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

rootProject.name = "yousie-sdk"

// The Android library. The Flutter plugin (flutter/) is not a Gradle
// subproject of this build: Flutter builds it from the app that uses it.
include(":yousie")
project(":yousie").projectDir = file("android")
