package com.farook.delightcrud.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.AbstractKotlinCompileTool

class DelightCrudPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        // 1. Apply KSP plugin if not already present
        if (!project.plugins.hasPlugin("com.google.devtools.ksp")) {
            project.plugins.apply("com.google.devtools.ksp")
        }

        // 2. Add processor as kspCommonMainMetadata dependency
        project.dependencies.add(
            "kspCommonMainMetadata",
            "com.farook.delightcrud:processor:$PLUGIN_VERSION"
        )

        project.afterEvaluate {
            // 3. All Kotlin compile tasks must wait for KSP to finish generating code
            project.tasks
                .withType(AbstractKotlinCompileTool::class.java)
                .configureEach {
                    if (name != "kspCommonMainKotlinMetadata") {
                        dependsOn("kspCommonMainKotlinMetadata")
                    }
                }

            // 4. Add KSP-generated sources into commonMain so all targets see them
            project.extensions
                .findByType(KotlinMultiplatformExtension::class.java)
                ?.sourceSets
                ?.findByName("commonMain")
                ?.kotlin
                ?.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
        }
    }

    companion object {
        const val PLUGIN_VERSION = "1.0.0-SNAPSHOT"
    }
}
