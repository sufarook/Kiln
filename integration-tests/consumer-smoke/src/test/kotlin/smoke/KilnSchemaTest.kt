package smoke

import io.github.sufarook.kiln.runtime.JvmDatabaseDriverFactory
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `KilnSchema.createAll(driver)` should stand in for calling `createTable()` on
 * every generated repository — the point being that adding an entity never
 * requires editing startup code.
 */
class KilnSchemaTest {

    @Test
    fun `createAll creates every table in the module`() = runBlocking {
        val driver = JvmDatabaseDriverFactory().createInMemory()

        // The only schema call. No per-repository createTable().
        KilnSchema.createAll(driver)

        // Every entity in this module is usable immediately.
        NoteRepository(driver).insert(Note(title = "from createAll"))
        assertEquals(1, NoteRepository(driver).findAll().size)

        TaskTagRepository(driver).insert(TaskTag(taskId = 1, tagId = 2))
        assertEquals(1, TaskTagRepository(driver).findAll().size)
    }

    @Test
    fun `createAll is safe to call on every launch`() = runBlocking {
        val driver = JvmDatabaseDriverFactory().createInMemory()

        KilnSchema.createAll(driver)
        NoteRepository(driver).insert(Note(title = "survives"))

        // Simulates a second app launch: tables already exist, migrator finds no
        // schema drift, and existing rows must be left alone.
        KilnSchema.createAll(driver)
        KilnSchema.createAll(driver)

        val notes = NoteRepository(driver).findAll()
        assertEquals(1, notes.size)
        assertTrue(notes.single().title == "survives")
    }
}
