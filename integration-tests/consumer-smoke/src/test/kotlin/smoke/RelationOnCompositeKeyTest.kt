package smoke

import io.github.sufarook.kiln.runtime.JvmDatabaseDriverFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression coverage for `@Relation` on composite-primary-key columns. The generated
 * helpers exist only if the processor reads `@Relation` off primary-key properties, so
 * this test failing to compile is itself the primary signal — the assertions then
 * confirm the helpers behave correctly against real SQLite.
 */
class RelationOnCompositeKeyTest {

    @Test
    fun `relation helpers work on both composite-key FK columns`() = runBlocking {
        val driver = JvmDatabaseDriverFactory().createInMemory()
        val repo = TaskTagRepository(driver)
        repo.createTable()

        repo.insert(TaskTag(taskId = 1L, tagId = 10L))
        repo.insert(TaskTag(taskId = 1L, tagId = 20L))
        repo.insert(TaskTag(taskId = 2L, tagId = 10L))

        // findByTask / findByTag — generated from @Relation on each PK column
        assertEquals(2, repo.findByTask(1L).size)
        assertEquals(2, repo.findByTag(10L).size)
        assertEquals(1, repo.findByTask(2L).size)

        // observeByTask — reactive variant on a PK column
        assertEquals(2, repo.observeByTask(1L).first().size)

        // deleteByTag — cascade-style helper on a PK column
        repo.deleteByTag(10L)
        assertEquals(0, repo.findByTag(10L).size)
        assertEquals(1, repo.findByTask(1L).size) // only (1, 20) remains
        assertEquals(1, repo.findAll().size)
    }
}
