plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    `maven-publish`
}

group = "com.farook.krate"
version = "1.0.0-SNAPSHOT"

android {
    namespace = "com.farook.krate.annotations"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}

kotlin {
    androidTarget {
        publishLibraryVariants("release")
    }
    jvm()
    iosArm64()
    iosX64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain by getting
    }
}
