package io.github.sufarook.kiln.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

/**
 * One-line setup for Kiln:
 *
 * ```kotlin
 * plugins {
 *     id("io.github.sufarook.kiln") version "<latest>"
 * }
 * ```
 *
 * Applies KSP, wires the processor, adds the annotations + runtime dependencies,
 * and (for KMP projects) works around KSP's source-dir filtering so generated
 * repositories are visible to every target including Android.
 */
class KilnPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.plugins.apply("com.google.devtools.ksp")

        project.plugins.withId("org.jetbrains.kotlin.multiplatform") {
            configureMultiplatform(project)
        }
        project.plugins.withId("org.jetbrains.kotlin.android") {
            configureSingleTarget(project)
        }
        project.plugins.withId("org.jetbrains.kotlin.jvm") {
            configureSingleTarget(project)
        }
    }

    /**
     * KMP: run the processor once on common metadata so one generated repository is
     * shared by all targets. KSP filters its own output dirs out of Android
     * compilations, so generated sources are synced to a neutral directory first.
     *
     * KSP 2.x propagates processors from `kspCommonMainMetadata` to every target
     * config, which would produce duplicate classes. The processor is excluded from
     * target configs so it only runs on the metadata compilation.
     *
     * Requires at least two platform targets so KSP creates the metadata
     * compilation. On hosts where some targets are unavailable (e.g. no K/N on
     * Windows), add a lightweight substitute target such as `jvm("desktop")`.
     */
    private fun configureMultiplatform(project: Project) {
        project.dependencies.add("kspCommonMainMetadata", "$GROUP:processor:$VERSION")

        val syncTask = project.tasks.register("syncKilnGeneratedSources", Sync::class.java) { task ->
            task.from(project.layout.buildDirectory.dir("generated/ksp/metadata/commonMain/kotlin"))
            task.into(project.layout.buildDirectory.dir(GENERATED_DIR))
        }

        project.tasks.matching { it.name == "kspCommonMainKotlinMetadata" }.all { kspTask ->
            syncTask.configure { it.dependsOn(kspTask) }
        }

        val kmp = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
        kmp.sourceSets.named("commonMain") { sourceSet ->
            sourceSet.kotlin.srcDir(project.layout.buildDirectory.dir(GENERATED_DIR))
            sourceSet.dependencies {
                api("$GROUP:annotations:$VERSION")
                api("$GROUP:runtime:$VERSION")
            }
        }

        project.tasks.withType(KotlinCompilationTask::class.java).configureEach { task ->
            if (task.name != "kspCommonMainKotlinMetadata") {
                task.dependsOn(syncTask)
            }
        }

        // KspAATask is not a KotlinCompilationTask, so wire it separately.
        // In multi-target mode the synced sources are needed by target KSP tasks;
        // in single-target mode the sync is NO-SOURCE (empty metadata dir) but
        // the dependency satisfies Gradle's implicit-dependency validation since
        // the sync output dir is part of the commonMain source set.
        project.tasks.matching {
            it.name.startsWith("ksp") && it.name != "kspCommonMainKotlinMetadata"
        }.all { task ->
            task.dependsOn(syncTask)
        }

        // KSP 2.x propagates kspCommonMainMetadata to every target config,
        // so the processor would run for each target compilation too, producing
        // duplicates. Exclude it from target configs so it only runs once on the
        // metadata compilation.
        project.afterEvaluate {
            kmp.targets.forEach { target ->
                val name = target.name
                if (name != "metadata") {
                    val kspConfig = "ksp${name.replaceFirstChar { it.uppercase() }}"
                    project.configurations.findByName(kspConfig)?.exclude(
                        mapOf("group" to GROUP, "module" to "processor")
                    )
                }
            }
        }
    }

    /** Plain Android or JVM project: KSP wires generated sources automatically. */
    private fun configureSingleTarget(project: Project) {
        project.dependencies.add("ksp", "$GROUP:processor:$VERSION")
        project.dependencies.add("implementation", "$GROUP:annotations:$VERSION")
        project.dependencies.add("implementation", "$GROUP:runtime:$VERSION")
    }

    companion object {
        /** Generated at build time from the project's own coordinates — never edit by hand. */
        val GROUP: String = KilnBuildConfig.GROUP
        val VERSION: String = KilnBuildConfig.VERSION
        const val GENERATED_DIR = "generated/kiln/commonMain/kotlin"
    }
}
