package io.github.sufarook.kiln.runtime

import java.io.File
import java.sql.DriverManager
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * A file-backed JDBC driver gives each thread its own connection, as Android's and
 * Native's drivers do. Unlike the in-memory driver the common suite runs on, a
 * write from the wrong thread here lands outside the transaction instead of
 * silently inside it — so these tests fail if a write is not routed to the
 * transaction's thread.
 */
class ThreadBoundDriverTest {

    private lateinit var file: File
    private lateinit var driver: KilnDriver
    private lateinit var elsewhere: ExecutorCoroutineDispatcher

    @BeforeTest
    fun setup() {
        file = File.createTempFile("kiln-thread-bound", ".db")
        driver = JvmKilnDriver(DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}"))
        driver.execute(null, """CREATE TABLE "items" ("value" TEXT NOT NULL)""", 0)
        elsewhere = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    }

    @AfterTest
    fun teardown() {
        elsewhere.close()
        driver.close()
        if (!file.delete()) file.deleteOnExit()
    }

    private fun insert(value: String) = driver.execute(null, """INSERT INTO "items" VALUES (?)""", 1) { bindString(0, value) }

    private fun rowCount(): Int = driver.executeQuery(null, """SELECT COUNT(*) FROM "items"""", { cursor ->
        cursor.next()
        cursor.getLong(0)!!.toInt()
    }, 0)

    @Test
    fun `write routed from another dispatcher commits with the Kiln transaction`() = runBlocking {
        driver.withTransaction {
            driver.withTransactionAwareContext(elsewhere) { insert("a") }
        }
        assertEquals(1, rowCount())
    }

    @Test
    fun `write routed from another dispatcher rolls back with the Kiln transaction`() = runBlocking {
        assertFailsWith<RuntimeException> {
            driver.withTransaction {
                driver.withTransactionAwareContext(elsewhere) { insert("a") }
                throw RuntimeException("abort")
            }
        }
        assertEquals(0, rowCount())
    }

    @Test
    fun `write rolls back with a transaction opened by other code`() {
        val tx = driver.newTransaction()
        try {
            runBlocking { driver.withTransactionAwareContext(elsewhere) { insert("a") } }
            tx.endTransaction(successful = false)
        } catch (e: Throwable) {
            tx.endTransaction(successful = false)
            throw e
        }
        assertEquals(0, rowCount())
    }

    @Test
    fun `writes either side of a suspension roll back together`() = runBlocking {
        assertFailsWith<RuntimeException> {
            driver.withTransaction {
                insert("before")
                withContext(elsewhere) { yield() }
                repeat(20) { yield() }
                insert("after")
                throw RuntimeException("abort")
            }
        }
        assertEquals(0, rowCount())
    }
}
