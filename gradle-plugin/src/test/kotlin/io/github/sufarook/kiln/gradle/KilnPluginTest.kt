package io.github.sufarook.kiln.gradle

import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural tests for [KilnPlugin] using [ProjectBuilder], which configures a real
 * Gradle `Project` in-process. The plugin does all of its work inside
 * `plugins.withId { }` callbacks that fire at apply time, so no evaluation is needed
 * and the suite stays fast.
 */
class KilnPluginTest {

    private val expectedGroup: String = System.getProperty("kiln.expectedGroup")
    private val expectedVersion: String = System.getProperty("kiln.expectedVersion")

    private fun newProject(): Project = ProjectBuilder.builder().build()

    /** Coordinates the plugin added to [configuration], as "group:name:version" strings. */
    private fun Project.coordinatesIn(configuration: String): List<String> =
        configurations.getByName(configuration).dependencies
            .filterIsInstance<ExternalModuleDependency>()
            .map { "${it.group}:${it.name}:${it.version}" }

    // ── Embedded coordinates ──────────────────────────────────────────────────

    @Test
    fun `embedded coordinates match what the build publishes`() {
        // The 1.0.0-alpha01 regression: VERSION was hand-maintained and drifted to
        // 1.0.0-SNAPSHOT, so every consumer failed to resolve annotations/runtime.
        assertEquals(
            "plugin GROUP must match the publishing group",
            expectedGroup,
            KilnPlugin.GROUP,
        )
        assertEquals(
            "plugin VERSION must match the published version",
            expectedVersion,
            KilnPlugin.VERSION,
        )
    }

    @Test
    fun `embedded version is never a snapshot placeholder`() {
        assertFalse(
            "VERSION resolved to '${KilnPlugin.VERSION}' — a SNAPSHOT is never publishable to Maven Central",
            KilnPlugin.VERSION.endsWith("-SNAPSHOT"),
        )
        assertTrue("VERSION must not be blank", KilnPlugin.VERSION.isNotBlank())
    }

    // ── Plugin wiring ─────────────────────────────────────────────────────────

    @Test
    fun `applies KSP so consumers never have to`() {
        val project = newProject()
        project.plugins.apply(KilnPlugin::class.java)

        assertTrue(
            "the plugin's headline promise is one-line setup — KSP must be applied for us",
            project.plugins.hasPlugin("com.google.devtools.ksp"),
        )
    }

    @Test
    fun `is resolvable by its published plugin id`() {
        // Guards the gradlePlugin { } descriptor, not just the class. A broken id is
        // invisible in this repo but breaks every consumer's plugins { } block.
        val project = newProject()
        project.plugins.apply("io.github.sufarook.kiln")

        assertNotNull(project.plugins.findPlugin(KilnPlugin::class.java))
    }

    @Test
    fun `adds nothing until a Kotlin plugin is present`() {
        val project = newProject()
        project.plugins.apply(KilnPlugin::class.java)

        assertFalse(
            "without a Kotlin plugin there is no compilation to wire into",
            project.configurations.names.contains("implementation"),
        )
    }

    // ── JVM / single-target projects ──────────────────────────────────────────

    @Test
    fun `jvm project gets processor, annotations and runtime at the embedded version`() {
        val project = newProject()
        project.plugins.apply("org.jetbrains.kotlin.jvm")
        project.plugins.apply(KilnPlugin::class.java)

        assertEquals(
            listOf("$expectedGroup:processor:$expectedVersion"),
            project.coordinatesIn("ksp"),
        )
        assertTrue(
            "annotations must be on the consumer's compile classpath",
            project.coordinatesIn("implementation")
                .contains("$expectedGroup:annotations:$expectedVersion"),
        )
        assertTrue(
            "runtime carries CrudRepository and the driver factories",
            project.coordinatesIn("implementation")
                .contains("$expectedGroup:runtime:$expectedVersion"),
        )
    }

    @Test
    fun `wiring is order independent`() {
        // Real build scripts list plugins in either order; withId callbacks must cope.
        // NB: Project.apply(Action) shadows Kotlin's stdlib apply — keep these explicit.
        val kilnFirst = newProject()
        kilnFirst.plugins.apply(KilnPlugin::class.java)
        kilnFirst.plugins.apply("org.jetbrains.kotlin.jvm")

        val kotlinFirst = newProject()
        kotlinFirst.plugins.apply("org.jetbrains.kotlin.jvm")
        kotlinFirst.plugins.apply(KilnPlugin::class.java)

        assertEquals(
            kotlinFirst.coordinatesIn("ksp").sorted(),
            kilnFirst.coordinatesIn("ksp").sorted(),
        )
        assertEquals(
            kotlinFirst.coordinatesIn("implementation").sorted(),
            kilnFirst.coordinatesIn("implementation").sorted(),
        )
    }

    // ── Multiplatform projects ────────────────────────────────────────────────

    @Test
    fun `kmp project runs the processor once on common metadata`() {
        val project = newProject()
        project.plugins.apply("org.jetbrains.kotlin.multiplatform")
        project.plugins.apply(KilnPlugin::class.java)

        assertEquals(
            "one processor run on common metadata means one repository shared by all targets",
            listOf("$expectedGroup:processor:$expectedVersion"),
            project.coordinatesIn("kspCommonMainMetadata"),
        )
    }

    @Test
    fun `kmp project registers the generated-source sync task`() {
        val project = newProject()
        project.plugins.apply("org.jetbrains.kotlin.multiplatform")
        project.plugins.apply(KilnPlugin::class.java)

        val syncTask = project.tasks.findByName("syncKilnGeneratedSources")
        assertNotNull(
            "KSP filters its own output dirs out of Android compilations — the sync task is the workaround",
            syncTask,
        )
    }

    @Test
    fun `kmp commonMain sees the generated sources and Kiln api dependencies`() {
        val project = newProject()
        project.plugins.apply("org.jetbrains.kotlin.multiplatform")
        project.plugins.apply(KilnPlugin::class.java)

        val kmp = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
        val commonMain = kmp.sourceSets.getByName("commonMain")

        val generatedDir = project.layout.buildDirectory.dir(KilnPlugin.GENERATED_DIR).get().asFile
        assertTrue(
            "commonMain must include the synced generated dir or repositories are unresolvable",
            commonMain.kotlin.srcDirs.contains(generatedDir),
        )

        // api, not implementation: entities in commonMain are part of the consumer's
        // own public surface, so downstream modules need the annotations too.
        val apiCoordinates = project.coordinatesIn("commonMainApi")
        assertTrue(
            "expected annotations in commonMainApi but got $apiCoordinates",
            apiCoordinates.contains("$expectedGroup:annotations:$expectedVersion"),
        )
        assertTrue(
            "expected runtime in commonMainApi but got $apiCoordinates",
            apiCoordinates.contains("$expectedGroup:runtime:$expectedVersion"),
        )
    }
}
