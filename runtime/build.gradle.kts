plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    `maven-publish`
}

group = "com.farook.delightcrud"
version = "1.0.0-SNAPSHOT"

android {
    namespace = "com.farook.delightcrud.runtime"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}

kotlin {
    androidTarget()
    jvm()
    iosArm64()
    iosX64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.sqldelight.runtime)           // SqlDriver visible to consumers
            }
        }
        val androidMain by getting {
            dependencies {
                api(libs.sqldelight.android.driver)    // AndroidDatabaseDriverFactory visible to consumers
            }
        }
    }
}
