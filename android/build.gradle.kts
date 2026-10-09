plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

// The one place the version is written for Gradle. Yousie.VERSION (the
// User-Agent of every request) and flutter/pubspec.yaml carry the same one.
val sdkVersion = "1.0.0"

android {
    namespace = "com.yousie.sdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    testOptions {
        unitTests.all { test ->
            // LocalServerTest runs only when it is told where a test server
            // is: ./gradlew :yousie:testDebugUnitTest -Pyousie.e2e.url=…
            project.properties
                .filterKeys { it.startsWith("yousie.e2e.") }
                .forEach { (name, value) -> test.systemProperty(name, value.toString()) }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        // An app on an older Kotlin can still read this library's metadata.
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9)
    }
}

dependencies {
    // Matches languageVersion/apiVersion above; an app's newer one wins.
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
    // Reads the Play install referrer: the click id of a creator's link.
    implementation("com.android.installreferrer:installreferrer:2.2")
    // Play Billing is the app's own dependency, never ours: automatic
    // purchase tracking uses it when the app has version 7 or newer, and is
    // off otherwise (internal/PlayPurchases.kt).
    compileOnly("com.android.billingclient:billing:8.0.0")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub on the JVM; the tests need a real one.
    testImplementation("org.json:json:20240303")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.yojimbo45"
                artifactId = "yousie-sdk"
                version = sdkVersion
            }
        }
    }
}
