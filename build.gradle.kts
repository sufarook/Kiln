plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.vanniktech.publish) apply false
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.dokka)
}

dependencies {
    // Kover instruments JVM bytecode only — the modules that actually execute a
    // JVM test task. runtime's iOS/Android targets and the Gradle-plugin-DSL-only
    // consumption model of processor/gradle-plugin aren't something Kover measures
    // separately; jvmTest already exercises the same commonMain logic those run.
    kover(project(":processor"))
    kover(project(":runtime"))
    kover(project(":gradle-plugin"))

    // Same scoping as the binary compatibility check: annotations/runtime are the
    // only modules whose public Kotlin API a consumer reads directly, so they're
    // the only ones worth generating an API reference for.
    dokka(project(":annotations"))
    dokka(project(":runtime"))
}

dokka {
    moduleName.set("Kiln")
    // Left at Dokka's default (build/dokka/html) rather than writing into docs/ —
    // docs/ is hand-authored MkDocs source under version control; generated API
    // reference gets copied alongside the built MkDocs site in CI instead (see
    // .github/workflows/docs.yml), not committed.
}

allprojects {
    // Single source of truth for Kiln's coordinates. The Gradle plugin's embedded
    // VERSION is generated from this (see :gradle-plugin generateBuildConfig) so it
    // cannot drift from what actually gets published.
    group = "io.github.sufarook.kiln"
    version = "1.0.0-alpha03"

    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    plugins.withId("org.jlleitschuh.gradle.ktlint") {
        extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
            // KSP-generated repositories/columns objects live under build/generated —
            // they're emitted by RepositoryGenerator, not hand-authored, so linting
            // (or worse, reformatting) them is meaningless and would just be undone
            // the next time the processor runs.
            filter {
                exclude { entry -> entry.file.path.contains("${layout.buildDirectory.get()}/generated/") }
            }
        }
    }

    // sample-shared adds its KSP-generated sources directory to commonMain via a
    // custom Sync task (see KilnPlugin.configureMultiplatform) rather than KSP's
    // own automatic wiring, so Gradle can't infer the ordering on its own — every
    // ktlint check task needs to explicitly run after anything that could still be
    // populating that directory.
    tasks.matching { it.name.startsWith("ktlint") || it.name.startsWith("runKtlint") }.configureEach {
        mustRunAfter(tasks.matching { it.name == "syncKilnGeneratedSources" })
    }
}

/** Lets CI read the version without parsing this file. */
tasks.register("printVersion") {
    val projectVersion = version.toString()
    doLast { println(projectVersion) }
}

@OptIn(kotlinx.validation.ExperimentalBCVApi::class)
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
