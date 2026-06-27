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

rootProject.name = "DelightCRUD"

include(":annotations")
include(":processor")
include(":runtime")
include(":gradle-plugin")
include(":sample-android")
