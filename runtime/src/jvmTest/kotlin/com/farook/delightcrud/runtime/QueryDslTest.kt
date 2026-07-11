package com.farook.delightcrud.runtime

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QueryDslTest {

    // Hand-built column refs — mirrors what the processor generates
    private val title = Column<String>("title") { SqlArg.StringArg(it) }
    private val priority = Column<Int>("priority") { SqlArg.LongArg(it.toLong()) }
    private val isCompleted = Column<Boolean>("is_completed") { SqlArg.LongArg(if (it) 1L else 0L) }
    private val dueDate = Column<String?>("due_date") { SqlArg.StringArg(it) }

    // ── Predicate SQL composition ───────────────────────────────────────────────

    @Test
    fun `eq builds bound equality`() {
        val p = priority eq 1
        assertEquals("\"priority\" = ?", p.sql)
        assertEquals(listOf<SqlArg>(SqlArg.LongArg(1L)), p.args)
    }

    @Test
    fun `and composes with parentheses and preserves arg order`() {
        val p = (priority eq 1) and (isCompleted eq false)
        assertEquals("(\"priority\" = ? AND \"is_completed\" = ?)", p.sql)
        assertEquals(listOf<SqlArg>(SqlArg.LongArg(1L), SqlArg.LongArg(0L)), p.args)
    }

    @Test
    fun `or and not compose`() {
        val p = not((title eq "a") or (title eq "b"))
        assertEquals("(NOT (\"title\" = ? OR \"title\" = ?))", p.sql)
        assertEquals(2, p.args.size)
    }

    @Test
    fun `comparison operators emit correct sql`() {
        assertEquals("\"priority\" > ?", (priority gt 0).sql)
        assertEquals("\"priority\" >= ?", (priority gte 0).sql)
        assertEquals("\"priority\" < ?", (priority lt 2).sql)
        assertEquals("\"priority\" <= ?", (priority lte 2).sql)
        assertEquals("\"priority\" != ?", (priority neq 1).sql)
    }

    @Test
    fun `like works on nullable string columns`() {
        val p = dueDate like "2026-%"
        assertEquals("\"due_date\" LIKE ?", p.sql)
    }

    @Test
    fun `isNull and isNotNull have no args`() {
        assertEquals("\"due_date\" IS NULL", dueDate.isNull().sql)
        assertEquals("\"due_date\" IS NOT NULL", dueDate.isNotNull().sql)
        assertEquals(0, dueDate.isNull().args.size)
    }

    @Test
    fun `inList expands placeholders`() {
        val p = priority inList listOf(0, 1, 2)
        assertEquals("\"priority\" IN (?, ?, ?)", p.sql)
        assertEquals(3, p.args.size)
    }

    @Test
    fun `inList rejects empty collection`() {
        assertFailsWith<IllegalArgumentException> { priority inList emptyList() }
    }

    // ── End-to-end against a real database ─────────────────────────────────────

    @Test
    fun `predicate executes correctly against sqlite`() {
        val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            driver.execute(null, """CREATE TABLE "todos" ("title" TEXT NOT NULL, "priority" INTEGER NOT NULL, "is_completed" INTEGER NOT NULL)""", 0)
            driver.execute(null, """INSERT INTO "todos" VALUES ('Buy milk', 1, 0), ('Walk dog', 0, 0), ('Big launch', 1, 1)""", 0)

            val predicate = (priority eq 1) and (isCompleted eq false)
            val titles = driver.executeQuery(
                null,
                """SELECT "title" FROM "todos" WHERE """ + predicate.sql,
                { cursor ->
                    val out = mutableListOf<String>()
                    while (cursor.next().value) out.add(cursor.getString(0)!!)
                    QueryResult.Value(out)
                },
                predicate.args.size
            ) {
                predicate.args.forEachIndexed { index, arg -> bindArg(index, arg) }
            }.value

            assertEquals(listOf("Buy milk"), titles)
        } finally {
            driver.close()
        }
    }
}
