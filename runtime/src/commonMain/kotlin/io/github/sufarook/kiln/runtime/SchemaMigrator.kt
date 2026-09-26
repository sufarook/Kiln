package io.github.sufarook.kiln.runtime

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * Automatic schema migration — called inside every generated createTable().
 *
 * Decision logic:
 *  - No change           → no-op
 *  - Only new columns    → fast path: ALTER TABLE ADD COLUMN for each
 *  - Orphaned columns    → slow path: full 12-step table recreation (drops dead columns)
 *  - Renamed column      → add @Column(migrateFrom="old_name"); triggers slow path,
 *                          data copied from old column into new one
 *  - Type changed        → slow path; old data CAST into the new declared type
 *
 * Removed columns are always cleaned up — they never linger as orphans.
 *
 * The connection is borrowed, so reconciliation leaves it as it found it: a
 * rebuild never deletes rows in tables that reference this one, and
 * foreign-key enforcement ends up however it started.
 */
class SchemaMigrator(private val driver: SqlDriver) {

    fun sync(tableName: String, expectedColumns: List<ColumnDef>) {
        // Inside a transaction Kiln can neither switch off foreign-key enforcement
        // (the pragma is a no-op there) nor own the rebuild's commit — refuse
        // before touching anything.
        check(driver.currentTransaction() == null) {
            "Cannot reconcile table \"$tableName\" while a transaction is open on this connection. " +
                "Call createTable() / KilnSchema.createAll() outside any transaction."
        }

        when (val type = schemaObjectType(tableName)) {
            null -> return // absent — createTable() creates it next
            "table" -> {}
            else -> error(
                "Cannot reconcile \"$tableName\": the database has a $type by that name, not a table. " +
                    "Rename the $type or the entity's table."
            )
        }

        val existing = pragmaColumns(tableName) // name → declared type

        val existingNames = existing.keys
        val expectedNames = expectedColumns.map { it.name }.toSet()

        // Columns in the DB that have no place in the new schema
        // (migrateFrom sources are excluded — they're being consumed, not abandoned)
        val migrateFromSources = expectedColumns.mapNotNull { it.migrateFrom.takeIf { s -> s.isNotEmpty() } }.toSet()
        val orphaned = existingNames - expectedNames - migrateFromSources

        val hasRenames = expectedColumns.any { it.migrateFrom.isNotEmpty() && it.migrateFrom in existingNames }

        // A property changed its Kotlin type (e.g. Int → String): declared SQL type differs
        val hasTypeChanges = expectedColumns.any { col ->
            val liveType = existing[col.name]
            liveType != null && !liveType.equals(col.type, ignoreCase = true)
        }

        when {
            orphaned.isNotEmpty() || hasRenames || hasTypeChanges ->
                // Slow path: recreate the table cleanly — drops orphans, applies renames and type changes
                recreateTable(tableName, expectedColumns, existing)

            else ->
                // Fast path: only additions — ALTER TABLE ADD COLUMN for each new column
                expectedColumns
                    .filter { it.name !in existingNames }
                    .forEach { col -> addColumn(tableName, col) }
        }
    }

    // ── Slow path ────────────────────────────────────────────────────────────────

    private fun recreateTable(
        tableName: String,
        expectedColumns: List<ColumnDef>,
        // live column name → declared type
        existing: Map<String, String>
    ) {
        val tmpName = "__${tableName}_new"

        // For each expected column, resolve which old column (or literal) to SELECT
        val insertCols = expectedColumns.joinToString(", ") { "\"${it.name}\"" }
        val selectCols = expectedColumns.joinToString(", ") { col ->
            val sourceName = when {
                col.name in existing -> col.name // unchanged or type-changed
                col.migrateFrom.isNotEmpty() && col.migrateFrom in existing -> col.migrateFrom // renamed
                else -> null // brand new
            }
            when {
                sourceName == null -> col.defaultValue
                // Type changed → CAST old data into the new declared type
                !existing.getValue(sourceName).equals(col.type, ignoreCase = true) ->
                    "CAST(\"$sourceName\" AS ${col.type})"
                else -> "\"$sourceName\""
            }
        }

        // Under enforcement, DROP TABLE deletes every row first — firing ON DELETE
        // CASCADE in tables that reference this one. SQLite's documented rebuild
        // procedure switches enforcement off around it; the pragma is ignored inside
        // a transaction, so it brackets the transaction rather than sitting in it.
        val enforcing = foreignKeysEnabled()
        if (enforcing) driver.execute(null, "PRAGMA foreign_keys = OFF", 0)
        var failure: Throwable? = null
        try {
            RebuildTransacter(driver).transaction(noEnclosing = true) {
                driver.execute(null, "DROP TABLE IF EXISTS \"$tmpName\"", 0)
                driver.execute(null, buildSchemaSql(tmpName, expectedColumns), 0)
                driver.execute(
                    null,
                    "INSERT INTO \"$tmpName\" ($insertCols) SELECT $selectCols FROM \"$tableName\"",
                    0
                )
                driver.execute(null, "DROP TABLE \"$tableName\"", 0)
                driver.execute(null, "ALTER TABLE \"$tmpName\" RENAME TO \"$tableName\"", 0)
            }
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            if (enforcing) {
                try {
                    driver.execute(null, "PRAGMA foreign_keys = ON", 0)
                } catch (restoreFailure: Throwable) {
                    // Don't let the restore mask why the rebuild failed.
                    val original = failure
                    if (original != null) original.addSuppressed(restoreFailure) else throw restoreFailure
                }
            }
        }
    }

    /** Rebuilds a CREATE TABLE statement from ColumnDef metadata (used for the temp table). */
    private fun buildSchemaSql(tableName: String, columns: List<ColumnDef>): String {
        val primaryKeys = columns.filter { it.isPrimaryKey }
        // A composite key can't use an inline column-level PRIMARY KEY — SQLite
        // requires one table-level constraint naming every key column instead.
        val isComposite = primaryKeys.size > 1

        val sb = StringBuilder("CREATE TABLE \"$tableName\" (\n")
        val defs = columns.map { col ->
            buildString {
                append("    \"${col.name}\" ${col.type}")
                if (!col.nullable) append(" NOT NULL")
                when {
                    isComposite -> {} // constraint appended separately, below
                    col.isPrimaryKey && col.autoIncrement -> append(" PRIMARY KEY AUTOINCREMENT")
                    col.isPrimaryKey -> append(" PRIMARY KEY")
                }
                if (col.isUnique && !col.isPrimaryKey) append(" UNIQUE")
            }
        }.toMutableList()

        if (isComposite) {
            defs += "    PRIMARY KEY (${primaryKeys.joinToString(", ") { "\"${it.name}\"" }})"
        }

        sb.append(defs.joinToString(",\n"))
        sb.append("\n)")
        return sb.toString()
    }

    // ── Fast path ────────────────────────────────────────────────────────────────

    private fun addColumn(tableName: String, col: ColumnDef) {
        val notNull = if (!col.nullable) " NOT NULL" else ""
        val default = if (!col.nullable && col.defaultValue.isNotEmpty()) " DEFAULT ${col.defaultValue}" else ""
        driver.execute(
            null,
            "ALTER TABLE \"$tableName\" ADD COLUMN \"${col.name}\" ${col.type}$notNull$default",
            0
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private class RebuildTransacter(driver: SqlDriver) : TransacterImpl(driver)

    /** `"table"`, `"view"`, … for whatever [name] resolves to, or null if nothing does. */
    private fun schemaObjectType(name: String): String? = driver.executeQuery(
        identifier = null,
        // Identifiers are case-insensitive in SQLite; PRAGMA table_info matches the same way.
        sql = "SELECT type FROM sqlite_master WHERE name = ? COLLATE NOCASE",
        mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
        parameters = 1
    ) { bindString(0, name) }.value

    private fun foreignKeysEnabled(): Boolean = driver.executeQuery(
        identifier = null,
        sql = "PRAGMA foreign_keys",
        mapper = { cursor -> QueryResult.Value(cursor.next().value && cursor.getLong(0) == 1L) },
        parameters = 0
    ).value

    /** Live schema as name → declared type (e.g. "title" → "TEXT"). */
    private fun pragmaColumns(tableName: String): Map<String, String> = driver.executeQuery(
        identifier = null,
        sql = "PRAGMA table_info(\"$tableName\")",
        mapper = { cursor ->
            val columns = mutableMapOf<String, String>()
            while (cursor.next().value) {
                // column 1 = name, column 2 = declared type
                columns[cursor.getString(1)!!] = cursor.getString(2) ?: ""
            }
            QueryResult.Value(columns)
        },
        parameters = 0
    ).value
}
