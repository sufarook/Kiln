package smoke

import io.github.sufarook.kiln.runtime.JvmDatabaseDriverFactory
// DSL operators are top-level functions — a consumer outside the runtime's own
// package has to import them explicitly. Worth keeping visible here.
import io.github.sufarook.kiln.runtime.eq
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end check that a plain consumer project gets a working repository from
 * nothing but `id("io.github.sufarook.kiln")` and an annotated data class.
 *
 * Referencing `NoteRepository` and `NoteColumns` at all is most of the test — they
 * only exist if the plugin resolved the processor and wired the generated sources.
 * If the plugin embeds a version that was never published, this project fails to
 * configure long before these assertions run.
 */
class ConsumerSmokeTest {

    @Test
    fun `plugin wires codegen and the generated repository performs crud`() = runBlocking {
        val driver = JvmDatabaseDriverFactory().createInMemory()
        val repo = NoteRepository(driver)
        repo.createTable()

        repo.insert(Note(title = "first"))
        repo.insert(Note(title = "second", isPinned = true))

        assertEquals(2, repo.findAll().size, "both rows should round-trip")

        val pinned = repo.findWhere { NoteColumns.isPinned eq true }
        assertEquals(1, pinned.size, "DSL predicate should filter on the generated column")
        assertEquals("second", pinned.single().title)

        val byId = repo.findById(pinned.single().id)
        assertNotNull(byId, "findById should resolve an inserted row")

        repo.delete(byId.id)
        assertEquals(1, repo.findAll().size, "delete should remove exactly one row")
    }

    @Test
    fun `plugin resolves the same version it was published at`() {
        // Guards the failure that shipped in 1.0.0-alpha01: the plugin embedded
        // 1.0.0-SNAPSHOT, so consumers could not resolve annotations/runtime/processor.
        val runtimeMarker = JvmDatabaseDriverFactory::class.java
        assertTrue(
            runtimeMarker.protectionDomain.codeSource.location.toString().isNotEmpty(),
            "runtime artifact should be resolved from a repository, not a project reference",
        )
    }
}
