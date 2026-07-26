plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.publish)
}

android {
    namespace = "io.github.sufarook.kiln.runtime"
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

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    if (!System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey").isNullOrEmpty()) signAllPublications()

    coordinates(artifactId = "runtime")

    pom {
        name.set("Kiln Runtime")
        description.set("Runtime library for Kiln — base interfaces, DSL predicates, transaction API, and platform driver factories.")
        url.set("https://github.com/sufarook/Kiln")
        licenses {
            license {
                name.set("Apache-2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }
        developers {
            developer {
                id.set("sufarook")
                name.set("Syed Ummer Farook")
                email.set("syedfarook1798@gmail.com")
            }
        }
        scm {
            connection.set("scm:git:github.com/sufarook/Kiln.git")
            developerConnection.set("scm:git:ssh://github.com/sufarook/Kiln.git")
            url.set("https://github.com/sufarook/Kiln")
        }
    }
}
