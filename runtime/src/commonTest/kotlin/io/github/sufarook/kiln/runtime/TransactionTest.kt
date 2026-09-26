package io.github.sufarook.kiln.runtime

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

class TransactionTest {

    private lateinit var driver: SqlDriver

    @BeforeTest
    fun setup() {
        driver = createTestDriver()
        driver.execute(null, """CREATE TABLE "items" ("value" TEXT NOT NULL)""", 0)
    }

    @AfterTest
    fun teardown() {
        driver.close()
    }

    private fun rowCount(): Int = driver.executeQuery(null, """SELECT COUNT(*) FROM "items"""", { cursor ->
        cursor.next()
        QueryResult.Value(cursor.getLong(0)!!.toInt())
    }, 0).value

    private fun allValues(): List<String> = driver.executeQuery(null, """SELECT "value" FROM "items"""", { cursor ->
        val out = mutableListOf<String>()
        while (cursor.next().value) out.add(cursor.getString(0)!!)
        QueryResult.Value(out)
    }, 0).value

    private fun insert(value: String) = driver.execute(null, """INSERT INTO "items" VALUES (?)""", 1) { bindString(0, value) }

    // ── Commit ────────────────────────────────────────────────────────────────

    @Test
    fun `withTransaction commits all inserts`() = runBlocking {
        driver.withTransaction {
            insert("a")
            insert("b")
            insert("c")
        }
        assertEquals(3, rowCount())
        assertEquals(listOf("a", "b", "c"), allValues())
    }

    @Test
    fun `withTransaction is atomic — all or nothing`() = runBlocking {
        assertFailsWith<RuntimeException> {
            driver.withTransaction {
                insert("x")
                insert("y")
                throw RuntimeException("boom")
            }
        }
        assertEquals(0, rowCount(), "rolled-back rows must not persist")
    }

    @Test
    fun `withTransaction rollback leaves prior data intact`() = runBlocking {
        insert("pre-existing")
        assertFailsWith<RuntimeException> {
            driver.withTransaction {
                insert("inside-tx")
                throw RuntimeException("abort")
            }
        }
        assertEquals(1, rowCount())
        assertEquals(listOf("pre-existing"), allValues())
    }

    // ── Nesting ───────────────────────────────────────────────────────────────

    @Test
    fun `nested withTransaction joins the outer transaction and commits once`() = runBlocking {
        driver.withTransaction {
            insert("outer")
            driver.withTransaction { insert("inner") }
            assertNotNull(driver.currentTransaction(), "the inner block must not commit the outer transaction")
        }
        assertNull(driver.currentTransaction())
        assertEquals(listOf("outer", "inner"), allValues())
    }

    @Test
    fun `failing inner block rolls back every level and surfaces its exception`() = runBlocking {
        val thrown = assertFailsWith<IllegalStateException> {
            driver.withTransaction {
                insert("outer")
                driver.withTransaction {
                    insert("inner")
                    throw IllegalStateException("inner failed")
                }
            }
        }
        assertEquals("inner failed", thrown.message)
        assertEquals(0, rowCount())
    }

    @Test
    fun `block exception is surfaced when the rollback itself fails`() = runBlocking {
        val failing = RollbackFailsDriver(driver)
        val thrown = assertFailsWith<RuntimeException> {
            failing.withTransaction {
                insert("x")
                throw RuntimeException("boom")
            }
        }
        assertEquals("boom", thrown.message)
        // Coroutine stack-trace recovery (JVM) may rethrow a copy with the original
        // as its cause; the rollback failure is attached to the original.
        val original = generateSequence<Throwable>(thrown) { it.cause }.last()
        assertEquals("rollback failed", original.suppressedExceptions.single().message)
        assertEquals(0, rowCount())
    }

    // ── Thread pinning ────────────────────────────────────────────────────────

    @Test
    fun `block that suspends and resumes still commits and leaves no transaction open`() = runBlocking {
        val elsewhere = Dispatchers.Default.limitedParallelism(1)
        repeat(30) { run ->
            driver.withTransaction {
                insert("run-$run")
                withContext(elsewhere) { yield() }
                repeat(20) { yield() }
            }
            assertNull(driver.currentTransaction(), "run $run left a transaction open")
        }
        assertEquals(30, rowCount())
    }

    @Test
    fun `transaction-aware context runs on the transaction's dispatcher`() = runBlocking {
        val elsewhere = Dispatchers.Default.limitedParallelism(1)
        driver.withTransaction {
            val transactionDispatcher = currentCoroutineContext()[ContinuationInterceptor]
            val used = driver.withTransactionAwareContext(elsewhere) { currentCoroutineContext()[ContinuationInterceptor] }
            assertSame(transactionDispatcher, used)
        }
    }

    @Test
    fun `transaction-aware context switches normally with no transaction open`() = runBlocking {
        val elsewhere = Dispatchers.Default.limitedParallelism(1)
        val used = driver.withTransactionAwareContext(elsewhere) { currentCoroutineContext()[ContinuationInterceptor] }
        assertSame(elsewhere, used)
    }

    @Test
    fun `transaction-aware write is discarded when the Kiln transaction rolls back`() = runBlocking {
        val elsewhere = Dispatchers.Default.limitedParallelism(1)
        assertFailsWith<RuntimeException> {
            driver.withTransaction {
                driver.withTransactionAwareContext(elsewhere) { insert("a") }
                throw RuntimeException("abort")
            }
        }
        assertEquals(0, rowCount())
    }

    @Test
    fun `transaction-aware write joins a transaction opened by other code`() {
        val external = ExternalTransacter(driver)
        external.transaction {
            runBlocking {
                driver.withTransactionAwareContext(Dispatchers.Default) {
                    assertNotNull(driver.currentTransaction(), "must run in place, on the transaction's thread")
                    insert("a")
                }
            }
            rollback()
        }
        assertEquals(0, rowCount())
    }

    // ── Notification deferral ─────────────────────────────────────────────────

    @Test
    fun `notifyOrDefer fires immediately outside a transaction`() = runBlocking {
        var notifyCount = 0
        val listener = Query.Listener { notifyCount++ }
        driver.addListener("items", listener = listener)

        driver.notifyOrDefer("items")
        driver.notifyOrDefer("items")

        driver.removeListener("items", listener = listener)
        assertEquals(2, notifyCount, "each notifyOrDefer call outside a tx should notify once")
    }

    @Test
    fun `notifyOrDefer defers inside transaction and fires once on commit`() = runBlocking {
        var notifyCount = 0
        val listener = Query.Listener { notifyCount++ }
        driver.addListener("items", listener = listener)

        driver.withTransaction {
            insert("a")
            driver.notifyOrDefer("items") // should be deferred
            insert("b")
            driver.notifyOrDefer("items") // same table — still only one deferred entry
            insert("c")
            driver.notifyOrDefer("items") // idem
            assertEquals(0, notifyCount, "no notification should fire before commit")
        }

        driver.removeListener("items", listener = listener)
        assertEquals(1, notifyCount, "exactly one notification after commit")
    }

    @Test
    fun `notifyOrDefer sends no notification when transaction is rolled back`() = runBlocking {
        var notifyCount = 0
        val listener = Query.Listener { notifyCount++ }
        driver.addListener("items", listener = listener)

        runCatching {
            driver.withTransaction {
                insert("will-be-rolled-back")
                driver.notifyOrDefer("items")
                throw RuntimeException("abort")
            }
        }

        driver.removeListener("items", listener = listener)
        assertEquals(0, notifyCount, "no notification on rollback")
    }

    @Test
    fun `multiple dirty tables each notified once on commit`() = runBlocking {
        driver.execute(null, """CREATE TABLE "other" ("v" TEXT NOT NULL)""", 0)

        var itemsCount = 0
        var otherCount = 0
        val itemsListener = Query.Listener { itemsCount++ }
        val otherListener = Query.Listener { otherCount++ }
        driver.addListener("items", listener = itemsListener)
        driver.addListener("other", listener = otherListener)

        driver.withTransaction {
            insert("a")
            driver.notifyOrDefer("items")
            driver.execute(null, """INSERT INTO "other" VALUES ('x')""", 0)
            driver.notifyOrDefer("other")
            driver.notifyOrDefer("items") // deduplication: still only one "items" notification
        }

        driver.removeListener("items", listener = itemsListener)
        driver.removeListener("other", listener = otherListener)
        assertEquals(1, itemsCount, "items notified once")
        assertEquals(1, otherCount, "other notified once")
    }

    @Test
    fun `table written at two nesting levels is notified once`() = runBlocking {
        var notifyCount = 0
        val listener = Query.Listener { notifyCount++ }
        driver.addListener("items", listener = listener)

        driver.withTransaction {
            insert("outer")
            driver.notifyOrDefer("items")
            driver.withTransaction {
                insert("inner")
                driver.notifyOrDefer("items")
            }
            driver.notifyOrDefer("items")
        }

        driver.removeListener("items", listener = listener)
        assertEquals(1, notifyCount)
    }

    @Test
    fun `notifyOrDefer inside a transaction opened by other code waits for its commit`() {
        var notifyCount = 0
        val listener = Query.Listener { notifyCount++ }
        driver.addListener("items", listener = listener)

        ExternalTransacter(driver).transaction {
            insert("a")
            runBlocking { driver.notifyOrDefer("items") }
            assertEquals(0, notifyCount, "no notification while the external transaction is open")
        }

        driver.removeListener("items", listener = listener)
        assertEquals(1, notifyCount)
    }

    @Test
    fun `notifyOrDefer inside a transaction opened by other code is dropped on its rollback`() {
        var notifyCount = 0
        val listener = Query.Listener { notifyCount++ }
        driver.addListener("items", listener = listener)

        ExternalTransacter(driver).transaction {
            insert("a")
            runBlocking { driver.notifyOrDefer("items") }
            rollback()
        }

        driver.removeListener("items", listener = listener)
        assertEquals(0, notifyCount)
    }
}

/** Stands in for other code sharing the driver — SQLDelight-generated databases use the same base. */
private class ExternalTransacter(driver: SqlDriver) : TransacterImpl(driver)

/**
 * Delegates everything to [delegate] except transactions, which it runs itself
 * with raw SQL so it can make ROLLBACK fail — after rolling back, so the
 * connection stays usable.
 */
private class RollbackFailsDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
    private var current: Tx? = null

    private inner class Tx(override val enclosingTransaction: Tx?) : Transacter.Transaction() {
        override fun endTransaction(successful: Boolean): QueryResult<Unit> {
            current = enclosingTransaction
            if (enclosingTransaction == null) {
                if (successful) {
                    delegate.execute(null, "COMMIT", 0)
                } else {
                    delegate.execute(null, "ROLLBACK", 0)
                    throw IllegalStateException("rollback failed")
                }
            }
            return QueryResult.Unit
        }
    }

    override fun newTransaction(): QueryResult<Transacter.Transaction> {
        val enclosing = current
        if (enclosing == null) delegate.execute(null, "BEGIN", 0)
        return QueryResult.Value(Tx(enclosing).also { current = it })
    }

    override fun currentTransaction(): Transacter.Transaction? = current
}
