package io.github.sufarook.kiln.runtime

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

    @Test
    fun `notInList expands placeholders with NOT IN`() {
        val p = priority notInList listOf(0, 1)
        assertEquals("\"priority\" NOT IN (?, ?)", p.sql)
        assertEquals(2, p.args.size)
    }

    @Test
    fun `notInList rejects empty collection`() {
        assertFailsWith<IllegalArgumentException> { priority notInList emptyList() }
    }

    @Test
    fun `between builds BETWEEN sql with two args`() {
        val p = priority.between(1, 3)
        assertEquals("\"priority\" BETWEEN ? AND ?", p.sql)
        assertEquals(listOf<SqlArg>(SqlArg.LongArg(1L), SqlArg.LongArg(3L)), p.args)
    }

    @Test
    fun `asc and desc produce correct OrderSpec`() {
        assertEquals(OrderSpec("priority", Order.ASC), priority.asc())
        assertEquals(OrderSpec("priority", Order.DESC), priority.desc())
    }

    @Test
    fun `buildOrderSuffix produces empty string when nothing specified`() {
        assertEquals("", buildOrderSuffix(emptyList(), null, null))
    }

    @Test
    fun `buildOrderSuffix with single column asc`() {
        assertEquals(""" ORDER BY "priority" ASC""", buildOrderSuffix(listOf(priority.asc()), null, null))
    }

    @Test
    fun `buildOrderSuffix with multiple columns and limit and offset`() {
        val suffix = buildOrderSuffix(listOf(priority.desc(), title.asc()), 20L, 40L)
        assertEquals(""" ORDER BY "priority" DESC, "title" ASC LIMIT 20 OFFSET 40""", suffix)
    }

    @Test
    fun `buildOrderSuffix with only limit`() {
        assertEquals(" LIMIT 10", buildOrderSuffix(emptyList(), 10L, null))
    }

    // ── End-to-end against a real database ─────────────────────────────────────

    private lateinit var driver: SqlDriver

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, """CREATE TABLE "todos" ("title" TEXT NOT NULL, "priority" INTEGER NOT NULL, "is_completed" INTEGER NOT NULL)""", 0)
        driver.execute(null, """INSERT INTO "todos" VALUES ('Buy milk', 1, 0)""", 0)
        driver.execute(null, """INSERT INTO "todos" VALUES ('Walk dog', 0, 0)""", 0)
        driver.execute(null, """INSERT INTO "todos" VALUES ('Big launch', 1, 1)""", 0)
        driver.execute(null, """INSERT INTO "todos" VALUES ('Read book', 2, 0)""", 0)
    }

    @AfterTest
    fun teardown() { driver.close() }

    private fun queryTitles(sql: String, predicate: Predicate): List<String> =
        driver.executeQuery(null, sql, { cursor ->
            val out = mutableListOf<String>()
            while (cursor.next().value) out.add(cursor.getString(0)!!)
            QueryResult.Value(out)
        }, predicate.args.size) {
            predicate.args.forEachIndexed { i, arg -> bindArg(i, arg) }
        }.value

    @Test
    fun `predicate executes correctly against sqlite`() {
        val predicate = (priority eq 1) and (isCompleted eq false)
        val titles = queryTitles("""SELECT "title" FROM "todos" WHERE """ + predicate.sql, predicate)
        assertEquals(listOf("Buy milk"), titles)
    }

    @Test
    fun `notInList executes correctly against sqlite`() {
        val predicate = priority notInList listOf(0, 1)
        val titles = queryTitles("""SELECT "title" FROM "todos" WHERE """ + predicate.sql, predicate)
        assertEquals(listOf("Read book"), titles)
    }

    @Test
    fun `between executes correctly against sqlite`() {
        val predicate = priority.between(1, 2)
        val titles = queryTitles("""SELECT "title" FROM "todos" WHERE """ + predicate.sql, predicate)
        assertEquals(setOf("Buy milk", "Big launch", "Read book"), titles.toSet())
    }

    @Test
    fun `ORDER BY suffix produces correct order`() {
        val predicate = isCompleted eq false
        val suffix = buildOrderSuffix(listOf(priority.desc()), null, null)
        val titles = queryTitles("""SELECT "title" FROM "todos" WHERE """ + predicate.sql + suffix, predicate)
        assertEquals("Read book", titles.first())
    }

    @Test
    fun `LIMIT and OFFSET paginate results`() {
        val predicate = isCompleted eq false
        val suffix = buildOrderSuffix(listOf(priority.asc()), 2L, 1L)
        val titles = queryTitles("""SELECT "title" FROM "todos" WHERE """ + predicate.sql + suffix, predicate)
        assertEquals(2, titles.size)
    }
}
