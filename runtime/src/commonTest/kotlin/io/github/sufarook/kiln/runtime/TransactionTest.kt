package io.github.sufarook.kiln.runtime

import app.cash.sqldelight.Query
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TransactionTest {

    private lateinit var driver: SqlDriver

    @BeforeTest
    fun setup() {
        driver = createTestDriver()
        driver.execute(null, """CREATE TABLE "items" ("value" TEXT NOT NULL)""", 0)
    }

    @AfterTest
    fun teardown() { driver.close() }

    private fun rowCount(): Int =
        driver.executeQuery(null, """SELECT COUNT(*) FROM "items"""", { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0)!!.toInt())
        }, 0).value

    private fun allValues(): List<String> =
        driver.executeQuery(null, """SELECT "value" FROM "items"""", { cursor ->
            val out = mutableListOf<String>()
            while (cursor.next().value) out.add(cursor.getString(0)!!)
            QueryResult.Value(out)
        }, 0).value

    private fun insert(value: String) =
        driver.execute(null, """INSERT INTO "items" VALUES (?)""", 1) { bindString(0, value) }

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
            driver.notifyOrDefer("items")   // should be deferred
            insert("b")
            driver.notifyOrDefer("items")   // same table — still only one deferred entry
            insert("c")
            driver.notifyOrDefer("items")   // idem
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
            driver.notifyOrDefer("items")  // deduplication: still only one "items" notification
        }

        driver.removeListener("items", listener = itemsListener)
        driver.removeListener("other", listener = otherListener)
        assertEquals(1, itemsCount, "items notified once")
        assertEquals(1, otherCount, "other notified once")
    }

    // ── KilnTransactionContext ───────────────────────────────────────────────

    @Test
    fun `KilnTransactionContext carries dirty tables`() {
        val ctx = KilnTransactionContext()
        assertTrue(ctx.dirtyTables.isEmpty())
        ctx.dirtyTables.add("todos")
        ctx.dirtyTables.add("todos")   // de-dup via Set
        ctx.dirtyTables.add("projects")
        assertEquals(2, ctx.dirtyTables.size)
        assertTrue(ctx.dirtyTables.contains("todos"))
        assertTrue(ctx.dirtyTables.contains("projects"))
    }
}
