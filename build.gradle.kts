plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.vanniktech.publish) apply false
}

allprojects {
    // Single source of truth for Kiln's coordinates. The Gradle plugin's embedded
    // VERSION is generated from this (see :gradle-plugin generateBuildConfig) so it
    // cannot drift from what actually gets published.
    group = "io.github.sufarook.kiln"
    version = "1.0.0-alpha03"
}

/** Lets CI read the version without parsing this file. */
tasks.register("printVersion") {
    val projectVersion = version.toString()
    doLast { println(projectVersion) }
}
