group = "com.yousie.flutter"
version = "1.0.0"

buildscript {
    val kotlinVersion = "2.4.0"
    repositories {
        google()
        mavenCentral()
    }

    dependencies {
        classpath("com.android.tools.build:gradle:9.1.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("com.android.library")
}

android {
    namespace = "com.yousie.flutter"

    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            // The plugin's own class, and the Android SDK it wraps: the very
            // sources the standalone library is built from (../../android),
            // so the two can never be different versions.
            java.srcDirs("src/main/kotlin", "../../android/src/main/kotlin")
        }
    }

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("../../android/consumer-rules.pro")
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // The same two as ../../android/build.gradle.kts.
    implementation("com.android.installreferrer:installreferrer:2.2")
    compileOnly("com.android.billingclient:billing:8.0.0")
}
