package com.farook.delightcrud.runtime

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
 */
class SchemaMigrator(private val driver: SqlDriver) {

    fun sync(tableName: String, expectedColumns: List<ColumnDef>) {
        val existing = pragmaColumns(tableName)          // name → declared type
        if (existing.isEmpty()) return  // table was just created — nothing to migrate

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
        existing: Map<String, String>   // live column name → declared type
    ) {
        val tmpName = "__${tableName}_new"

        // For each expected column, resolve which old column (or literal) to SELECT
        val insertCols = expectedColumns.joinToString(", ") { "\"${it.name}\"" }
        val selectCols = expectedColumns.joinToString(", ") { col ->
            val sourceName = when {
                col.name in existing -> col.name                                        // unchanged or type-changed
                col.migrateFrom.isNotEmpty() && col.migrateFrom in existing -> col.migrateFrom  // renamed
                else -> null                                                            // brand new
            }
            when {
                sourceName == null -> col.defaultValue
                // Type changed → CAST old data into the new declared type
                !existing.getValue(sourceName).equals(col.type, ignoreCase = true) ->
                    "CAST(\"$sourceName\" AS ${col.type})"
                else -> "\"$sourceName\""
            }
        }

        driver.execute(null, "DROP TABLE IF EXISTS \"$tmpName\"", 0)
        driver.execute(null, "BEGIN TRANSACTION", 0)
        try {
            driver.execute(null, buildSchemaSql(tmpName, expectedColumns), 0)
            driver.execute(null,
                "INSERT INTO \"$tmpName\" ($insertCols) SELECT $selectCols FROM \"$tableName\"", 0)
            driver.execute(null, "DROP TABLE \"$tableName\"", 0)
            driver.execute(null, "ALTER TABLE \"$tmpName\" RENAME TO \"$tableName\"", 0)
            driver.execute(null, "COMMIT", 0)
        } catch (e: Exception) {
            driver.execute(null, "ROLLBACK", 0)
            throw e
        }
    }

    /** Rebuilds a CREATE TABLE statement from ColumnDef metadata (used for the temp table). */
    private fun buildSchemaSql(tableName: String, columns: List<ColumnDef>): String {
        val sb = StringBuilder("CREATE TABLE \"$tableName\" (\n")
        columns.forEachIndexed { i, col ->
            sb.append("    \"${col.name}\" ${col.type}")
            if (!col.nullable) sb.append(" NOT NULL")
            when {
                col.isPrimaryKey && col.autoIncrement -> sb.append(" PRIMARY KEY AUTOINCREMENT")
                col.isPrimaryKey                      -> sb.append(" PRIMARY KEY")
            }
            if (col.isUnique && !col.isPrimaryKey) sb.append(" UNIQUE")
            if (i < columns.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append(")")
        return sb.toString()
    }

    // ── Fast path ────────────────────────────────────────────────────────────────

    private fun addColumn(tableName: String, col: ColumnDef) {
        val notNull = if (!col.nullable) " NOT NULL" else ""
        val default = if (!col.nullable && col.defaultValue.isNotEmpty()) " DEFAULT ${col.defaultValue}" else ""
        driver.execute(null,
            "ALTER TABLE \"$tableName\" ADD COLUMN \"${col.name}\" ${col.type}$notNull$default", 0)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /** Live schema as name → declared type (e.g. "title" → "TEXT"). */
    private fun pragmaColumns(tableName: String): Map<String, String> =
        driver.executeQuery(
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
