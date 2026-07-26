pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Kiln"

include(":annotations")
include(":processor")
include(":runtime")
include(":gradle-plugin")
include(":sample-android")
include(":sample-shared")
