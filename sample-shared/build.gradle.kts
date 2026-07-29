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
            baseName = "SampleShared"
            isStatic = true
        }
    }

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
            dependencies {
                api(project(":annotations"))
                api(project(":runtime"))
            }
        }
    }
}

dependencies {
    add("kspCommonMainMetadata", project(":processor"))
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    if (name != "kspCommonMainKotlinMetadata") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}
