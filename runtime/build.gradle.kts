plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.kover)
    alias(libs.plugins.dokka)
}

android {
    namespace = "io.github.sufarook.kiln.runtime"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}

kotlin {
    // Pinned so published class-file version is reproducible across build machines
    // rather than following whichever JDK happened to run the build.
    jvmToolchain(17)

    androidTarget {
        publishLibraryVariants("release")
    }
    jvm()
    listOf(iosArm64(), iosX64(), iosSimulatorArm64()).forEach { target ->
        target.compilations.getByName("main") {
            cinterops {
                create("sqlite3") {
                    definitionFile.set(project.file("src/nativeInterop/cinterop/sqlite3.def"))
                }
            }
        }
        target.binaries.all { linkerOpts("-lsqlite3") }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.kotlinx.coroutines.core) // Flow/suspend in CrudRepository
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.xerial.sqlite.jdbc)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(libs.junit)
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(libs.xerial.sqlite.jdbc)
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
