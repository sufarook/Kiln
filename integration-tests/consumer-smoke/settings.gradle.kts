// Standalone build — deliberately NOT included in the root settings.gradle.kts.
//
// The point of this project is to consume Kiln the way a real user does: by
// resolving the plugin and its artifacts from a repository, not via project(":")
// references. It runs against mavenLocal() so CI can verify the exact artifacts
// `publishToMavenLocal` produced before they are pushed to Maven Central.

pluginManagement {
    val kilnVersion: String by settings

    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
    }

    plugins {
        id("io.github.sufarook.kiln") version kilnVersion
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}

rootProject.name = "consumer-smoke"
