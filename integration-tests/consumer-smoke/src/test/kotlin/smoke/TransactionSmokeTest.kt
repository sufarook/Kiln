package smoke

import app.cash.sqldelight.Query
import app.cash.sqldelight.db.SqlDriver
import io.github.sufarook.kiln.runtime.JvmDatabaseDriverFactory
import io.github.sufarook.kiln.runtime.withTransaction
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Transactions through generated repositories, via the real KSP pipeline.
 *
 * File-backed on purpose: this driver gives each thread its own connection, as
 * Android's and iOS's do, so a repository write that ran off the transaction's
 * thread would land outside the transaction — and these assertions would see it.
 */
class TransactionSmokeTest {

    private lateinit var file: File
    private lateinit var driver: SqlDriver
    private lateinit var elsewhere: ExecutorCoroutineDispatcher
    private lateinit var repo: NoteRepository

    @BeforeTest
    fun setup() {
        file = File.createTempFile("kiln-smoke", ".db")
        driver = JvmDatabaseDriverFactory().create(file.absolutePath)
        // A dispatcher the transaction does not run on — the repository must still
        // write inside the transaction.
        elsewhere = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        repo = NoteRepository(driver, elsewhere)
        repo.createTable()
    }

    @AfterTest
    fun teardown() {
        elsewhere.close()
        driver.close()
        if (!file.delete()) file.deleteOnExit()
    }

    private fun countNotifications(block: () -> Unit): Int {
        var count = 0
        val listener = Query.Listener { count++ }
        driver.addListener("notes", listener = listener)
        try {
            block()
        } finally {
            driver.removeListener("notes", listener = listener)
        }
        return count
    }

    @Test
    fun `repository writes inside withTransaction commit together and notify once`() = runBlocking {
        val notifications = countNotifications {
            runBlocking {
                driver.withTransaction {
                    repo.insert(Note(title = "a"))
                    repo.insert(Note(title = "b"))
                    repo.insertAll(listOf(Note(title = "c"), Note(title = "d"))) // nests
                }
            }
        }
        assertEquals(4, repo.findAll().size)
        assertEquals(1, notifications, "one notification per table per transaction")
    }

    @Test
    fun `repository writes roll back with the transaction and notify nothing`() = runBlocking {
        val notifications = countNotifications {
            runBlocking {
                assertFailsWith<IllegalStateException> {
                    driver.withTransaction {
                        repo.insert(Note(title = "a"))
                        error("abort")
                    }
                }
            }
        }
        assertEquals(0, repo.findAll().size, "a write on the repository's own dispatcher must still roll back")
        assertEquals(0, notifications)
    }

    @Test
    fun `createTable refuses to run inside a transaction`() = runBlocking {
        driver.withTransaction {
            val error = assertFailsWith<IllegalStateException> { repo.createTable() }
            assertEquals(true, error.message?.contains("transaction is open"), error.message)
        }
    }
}
