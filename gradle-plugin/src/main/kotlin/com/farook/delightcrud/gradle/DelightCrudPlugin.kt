package com.farook.delightcrud.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

/**
 * One-line setup for DelightCRUD:
 *
 * ```kotlin
 * plugins {
 *     id("com.farook.delightcrud") version "1.0.0"
 * }
 * ```
 *
 * Applies KSP, wires the processor, adds the annotations + runtime dependencies,
 * and (for KMP projects) works around KSP's source-dir filtering so generated
 * repositories are visible to every target including Android.
 */
class DelightCrudPlugin : Plugin<Project> {

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
     */
    private fun configureMultiplatform(project: Project) {
        project.dependencies.add("kspCommonMainMetadata", "$GROUP:processor:$VERSION")

        val syncTask = project.tasks.register("syncDelightCrudGeneratedSources", Sync::class.java) { task ->
            task.dependsOn("kspCommonMainKotlinMetadata")
            task.from(project.layout.buildDirectory.dir("generated/ksp/metadata/commonMain/kotlin"))
            task.into(project.layout.buildDirectory.dir(GENERATED_DIR))
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
    }

    /** Plain Android or JVM project: KSP wires generated sources automatically. */
    private fun configureSingleTarget(project: Project) {
        project.dependencies.add("ksp", "$GROUP:processor:$VERSION")
        project.dependencies.add("implementation", "$GROUP:annotations:$VERSION")
        project.dependencies.add("implementation", "$GROUP:runtime:$VERSION")
    }

    companion object {
        const val GROUP = "com.farook.delightcrud"
        const val VERSION = "1.0.0-SNAPSHOT"
        const val GENERATED_DIR = "generated/delightcrud/commonMain/kotlin"
    }
}
