package com.farook.krate.runtime

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Runs SchemaMigrator against a real in-memory SQLite database via the JDBC driver.
 * Mirrors the manual upgrade checklist: install v1 of an entity, "ship" v2, sync, verify.
 */
class SchemaMigratorTest {

    private lateinit var driver: SqlDriver
    private lateinit var migrator: SchemaMigrator

    // v1 schema used as the "already installed" baseline in most tests
    private val v1Columns = listOf(
        ColumnDef("id", "INTEGER", false, "0", "", isPrimaryKey = true, autoIncrement = true),
        ColumnDef("title", "TEXT", false, "''"),
        ColumnDef("priority", "INTEGER", false, "0")
    )

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        migrator = SchemaMigrator(driver)
        exec(
            """CREATE TABLE IF NOT EXISTS "todos" (
                "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                "title" TEXT NOT NULL,
                "priority" INTEGER NOT NULL
            )"""
        )
        insertTodo("Buy milk", 1)
        insertTodo("Walk dog", 0)
    }

    @AfterTest
    fun teardown() {
        driver.close()
    }

    // ── Scenarios ───────────────────────────────────────────────────────────────

    @Test
    fun `no-op when schema unchanged`() {
        migrator.sync("todos", v1Columns)

        assertEquals(listOf("id", "title", "priority"), columnNames("todos"))
        assertEquals(2, count("todos"))
        assertEquals("Buy milk", queryString("""SELECT "title" FROM "todos" WHERE "priority" = 1"""))
    }

    @Test
    fun `nonexistent table is a safe no-op`() {
        migrator.sync("does_not_exist", v1Columns)  // must not throw
    }

    @Test
    fun `added column takes fast path and backfills default`() {
        migrator.sync("todos", v1Columns + ColumnDef("notes", "TEXT", false, "''"))

        assertEquals(listOf("id", "title", "priority", "notes"), columnNames("todos"))
        assertEquals(2, count("todos"))
        assertEquals("", queryString("""SELECT "notes" FROM "todos" WHERE "title" = 'Buy milk'"""))
    }

    @Test
    fun `added nullable column defaults to null`() {
        migrator.sync("todos", v1Columns + ColumnDef("due_date", "TEXT", true, ""))

        assertTrue("due_date" in columnNames("todos"))
        val isNull = driver.executeQuery(
            null,
            """SELECT "due_date" IS NULL FROM "todos" LIMIT 1""",
            { c -> c.next(); QueryResult.Value(c.getLong(0) == 1L) },
            0
        ).value
        assertTrue(isNull)
    }

    @Test
    fun `renamed column migrates data and drops old column`() {
        val v2 = listOf(
            v1Columns[0],
            v1Columns[1],
            ColumnDef("urgency", "INTEGER", false, "0", migrateFrom = "priority")
        )
        migrator.sync("todos", v2)

        val cols = columnNames("todos")
        assertTrue("urgency" in cols, "new column must exist")
        assertTrue("priority" !in cols, "old column must be gone, not orphaned")
        assertEquals(2, count("todos"))
        // Buy milk had priority=1 — value must survive the rename
        assertEquals(1L, queryLong("""SELECT "urgency" FROM "todos" WHERE "title" = 'Buy milk'"""))
    }

    @Test
    fun `removed column is dropped with remaining data intact`() {
        migrator.sync("todos", listOf(v1Columns[0], v1Columns[1]))  // priority removed

        assertEquals(listOf("id", "title"), columnNames("todos"))
        assertEquals(2, count("todos"))
        assertEquals("Buy milk", queryString("""SELECT "title" FROM "todos" WHERE "id" = 1"""))
    }

    @Test
    fun `primary key values survive table recreation`() {
        val idsBefore = queryLongs("""SELECT "id" FROM "todos" ORDER BY "id"""")
        migrator.sync("todos", listOf(v1Columns[0], v1Columns[1]))  // triggers slow path

        assertEquals(idsBefore, queryLongs("""SELECT "id" FROM "todos" ORDER BY "id""""))
    }

    @Test
    fun `unique constraint survives table recreation`() {
        val v2 = listOf(
            v1Columns[0],
            ColumnDef("title", "TEXT", false, "''", isUnique = true),
            // dropping priority forces the slow path so the table is rebuilt with UNIQUE
        )
        migrator.sync("todos", v2)

        exec("""INSERT INTO "todos" ("title") VALUES ('Unique task')""")
        assertFails("duplicate insert must violate UNIQUE") {
            exec("""INSERT INTO "todos" ("title") VALUES ('Unique task')""")
        }
    }

    @Test
    fun `add and rename in the same sync`() {
        val v2 = listOf(
            v1Columns[0],
            v1Columns[1],
            ColumnDef("urgency", "INTEGER", false, "0", migrateFrom = "priority"),
            ColumnDef("notes", "TEXT", false, "''")
        )
        migrator.sync("todos", v2)

        val cols = columnNames("todos")
        assertEquals(listOf("id", "title", "urgency", "notes"), cols)
        assertEquals(1L, queryLong("""SELECT "urgency" FROM "todos" WHERE "title" = 'Buy milk'"""))
        assertEquals("", queryString("""SELECT "notes" FROM "todos" WHERE "title" = 'Buy milk'"""))
    }

    @Test
    fun `type change triggers recreation and casts data`() {
        // priority was Int (INTEGER) — developer changes the property to String (TEXT)
        val v2 = listOf(
            v1Columns[0],
            v1Columns[1],
            ColumnDef("priority", "TEXT", false, "''")
        )
        migrator.sync("todos", v2)

        val types = columnTypes("todos")
        assertEquals("TEXT", types["priority"])
        assertEquals(2, count("todos"))
        // Buy milk had priority=1 (INTEGER) — must survive as the string "1"
        assertEquals("1", queryString("""SELECT "priority" FROM "todos" WHERE "title" = 'Buy milk'"""))
    }

    @Test
    fun `text to integer type change casts numeric strings`() {
        exec("""CREATE TABLE "settings" ("key" TEXT NOT NULL PRIMARY KEY, "value" TEXT NOT NULL)""")
        exec("""INSERT INTO "settings" VALUES ('retries', '5')""")

        migrator.sync(
            "settings",
            listOf(
                ColumnDef("key", "TEXT", false, "''", isPrimaryKey = true),
                ColumnDef("value", "INTEGER", false, "0")
            )
        )

        assertEquals("INTEGER", columnTypes("settings")["value"])
        assertEquals(5L, queryLong("""SELECT "value" FROM "settings" WHERE "key" = 'retries'"""))
    }

    @Test
    fun `sync is idempotent across repeated launches`() {
        val v2 = v1Columns + ColumnDef("notes", "TEXT", false, "''")
        migrator.sync("todos", v2)
        migrator.sync("todos", v2)  // second app launch
        migrator.sync("todos", v2)  // third app launch

        assertEquals(listOf("id", "title", "priority", "notes"), columnNames("todos"))
        assertEquals(2, count("todos"))
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private fun exec(sql: String) = driver.execute(null, sql, 0)

    private fun insertTodo(title: String, priority: Long) =
        exec("""INSERT INTO "todos" ("title", "priority") VALUES ('$title', $priority)""")

    private fun columnNames(table: String): List<String> =
        driver.executeQuery(
            null, """PRAGMA table_info("$table")""",
            { cursor ->
                val names = mutableListOf<String>()
                while (cursor.next().value) names.add(cursor.getString(1)!!)
                QueryResult.Value(names)
            }, 0
        ).value

    private fun columnTypes(table: String): Map<String, String> =
        driver.executeQuery(
            null, """PRAGMA table_info("$table")""",
            { cursor ->
                val types = mutableMapOf<String, String>()
                while (cursor.next().value) types[cursor.getString(1)!!] = cursor.getString(2) ?: ""
                QueryResult.Value(types)
            }, 0
        ).value

    private fun count(table: String): Int =
        queryLong("""SELECT COUNT(*) FROM "$table"""").toInt()

    private fun queryLong(sql: String): Long =
        driver.executeQuery(null, sql, { c -> c.next(); QueryResult.Value(c.getLong(0)!!) }, 0).value

    private fun queryLongs(sql: String): List<Long> =
        driver.executeQuery(
            null, sql,
            { c ->
                val out = mutableListOf<Long>()
                while (c.next().value) out.add(c.getLong(0)!!)
                QueryResult.Value(out)
            }, 0
        ).value

    private fun queryString(sql: String): String =
        driver.executeQuery(null, sql, { c -> c.next(); QueryResult.Value(c.getString(0)!!) }, 0).value
}
