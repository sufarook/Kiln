plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    `maven-publish`
}

group = "com.farook.krate"
version = "1.0.0-SNAPSHOT"

android {
    namespace = "com.farook.krate.runtime"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}

kotlin {
    applyDefaultHierarchyTemplate()   // creates iosMain umbrella source set
    androidTarget {
        publishLibraryVariants("release")
    }
    jvm()
    iosArm64()
    iosX64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.sqldelight.runtime)           // SqlDriver visible to consumers
                api(libs.kotlinx.coroutines.core)      // Flow/suspend in CrudRepository
            }
        }
        val androidMain by getting {
            dependencies {
                api(libs.sqldelight.android.driver)    // AndroidDatabaseDriverFactory visible to consumers
            }
        }
        val iosMain by getting {
            dependencies {
                api(libs.sqldelight.native.driver)     // IosDatabaseDriverFactory visible to consumers
            }
        }
        val jvmMain by getting {
            dependencies {
                api(libs.sqldelight.sqlite.driver)  // JvmDatabaseDriverFactory
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(libs.junit)
                implementation(kotlin("test"))
            }
        }
    }
}
