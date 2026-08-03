plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.vanniktech.publish) apply false
    alias(libs.plugins.binary.compatibility.validator)
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

apiValidation {
    // `annotations` and `runtime` are the only modules whose Kotlin symbols a
    // consumer imports directly — that's the API this tool exists to protect.
    // `processor` is consumed only through KSP and `gradle-plugin` only through
    // the `plugins {}` DSL; neither is used as a library dependency, so a
    // signature change there isn't the kind of breakage this check catches.
    ignoredProjects += listOf("processor", "gradle-plugin", "sample-android", "sample-shared")

    klib {
        // Off by default upstream. Kiln's entities live in commonMain and its
        // headline claim is Android + iOS parity, so the iOS ABI needs the same
        // guard as the JVM/Android one — a break there is invisible otherwise.
        enabled = true
    }
}
