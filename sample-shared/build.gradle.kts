plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

group = "com.farook.delightcrud.sample"
version = "1.0.0-SNAPSHOT"

android {
    namespace = "com.farook.delightcrud.sample.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}

// KSP runs once on common metadata; its output is copied to a neutral directory
// because the KSP plugin filters build/generated/ksp/** out of Android compilations.
val syncGeneratedSources = tasks.register<Sync>("syncDelightCrudGeneratedSources") {
    dependsOn("kspCommonMainKotlinMetadata")
    from(layout.buildDirectory.dir("generated/ksp/metadata/commonMain/kotlin"))
    into(layout.buildDirectory.dir("generated/delightcrud/commonMain/kotlin"))
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
            kotlin.srcDir(layout.buildDirectory.dir("generated/delightcrud/commonMain/kotlin"))
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

// Every compilation waits for the synced generated sources
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    if (name != "kspCommonMainKotlinMetadata") {
        dependsOn(syncGeneratedSources)
    }
}
