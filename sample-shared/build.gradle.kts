plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

group = "io.github.sufarook.kiln.sample"
version = "1.0.0-SNAPSHOT"

android {
    namespace = "io.github.sufarook.kiln.sample.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}

kotlin {
    applyDefaultHierarchyTemplate()
    androidTarget()
    listOf(iosArm64(), iosX64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "SampleShared"   // import SampleShared in Swift
            isStatic = true
        }
    }

    sourceSets {
        val commonMain by getting {
            // KSP generates once into the metadata output dir; adding it directly here
            // makes the generated repositories visible to all targets (Android + iOS).
            kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
            dependencies {
                api(project(":annotations"))
                api(project(":runtime"))
            }
        }
    }
}

dependencies {
    // One processor run on common metadata → one repository shared by all platforms
    add("kspCommonMainMetadata", project(":processor"))
}

// Every compilation that needs the generated sources must wait for KSP to finish.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    if (name != "kspCommonMainKotlinMetadata") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}
