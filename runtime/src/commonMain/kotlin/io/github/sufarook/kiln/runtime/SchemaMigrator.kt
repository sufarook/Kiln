package io.github.sufarook.kiln.runtime

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
class SchemaMigrator(private val driver: KilnDriver) {

    fun sync(tableName: String, expectedColumns: List<ColumnDef>) {
        check(driver.currentTransaction() == null) {
            "Cannot reconcile table \"$tableName\" while a transaction is open on this connection. " +
                "Call createTable() / KilnSchema.createAll() outside any transaction."
        }

        when (val type = schemaObjectType(tableName)) {
            null -> return
            "table" -> {}
            else -> error(
                "Cannot reconcile \"$tableName\": the database has a $type by that name, not a table. " +
                    "Rename the $type or the entity's table."
            )
        }

        val existing = pragmaColumns(tableName)

        val existingNames = existing.keys
        val expectedNames = expectedColumns.map { it.name }.toSet()

        val migrateFromSources = expectedColumns.mapNotNull { it.migrateFrom.takeIf { s -> s.isNotEmpty() } }.toSet()
        val orphaned = existingNames - expectedNames - migrateFromSources

        val hasRenames = expectedColumns.any { it.migrateFrom.isNotEmpty() && it.migrateFrom in existingNames }

        val hasTypeChanges = expectedColumns.any { col ->
            val liveType = existing[col.name]
            liveType != null && !liveType.equals(col.type, ignoreCase = true)
        }

        when {
            orphaned.isNotEmpty() || hasRenames || hasTypeChanges ->
                recreateTable(tableName, expectedColumns, existing)

            else ->
                expectedColumns
                    .filter { it.name !in existingNames }
                    .forEach { col -> addColumn(tableName, col) }
        }
    }

    // ── Slow path ────────────────────────────────────────────────────────────────

    private fun recreateTable(
        tableName: String,
        expectedColumns: List<ColumnDef>,
        existing: Map<String, String>
    ) {
        val tmpName = "__${tableName}_new"

        val insertCols = expectedColumns.joinToString(", ") { "\"${it.name}\"" }
        val selectCols = expectedColumns.joinToString(", ") { col ->
            val sourceName = when {
                col.name in existing -> col.name
                col.migrateFrom.isNotEmpty() && col.migrateFrom in existing -> col.migrateFrom
                else -> null
            }
            when {
                sourceName == null -> col.defaultValue
                !existing.getValue(sourceName).equals(col.type, ignoreCase = true) ->
                    "CAST(\"$sourceName\" AS ${col.type})"
                else -> "\"$sourceName\""
            }
        }

        val enforcing = foreignKeysEnabled()
        if (enforcing) driver.execute(null, "PRAGMA foreign_keys = OFF", 0)
        var failure: Throwable? = null
        try {
            val tx = driver.newTransaction()
            try {
                driver.execute(null, "DROP TABLE IF EXISTS \"$tmpName\"", 0)
                driver.execute(null, buildSchemaSql(tmpName, expectedColumns), 0)
                driver.execute(
                    null,
                    "INSERT INTO \"$tmpName\" ($insertCols) SELECT $selectCols FROM \"$tableName\"",
                    0
                )
                driver.execute(null, "DROP TABLE \"$tableName\"", 0)
                driver.execute(null, "ALTER TABLE \"$tmpName\" RENAME TO \"$tableName\"", 0)
                tx.endTransaction(successful = true)
            } catch (e: Throwable) {
                tx.endTransaction(successful = false)
                throw e
            }
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            if (enforcing) {
                try {
                    driver.execute(null, "PRAGMA foreign_keys = ON", 0)
                } catch (restoreFailure: Throwable) {
                    val original = failure
                    if (original != null) original.addSuppressed(restoreFailure) else throw restoreFailure
                }
            }
        }
    }

    private fun buildSchemaSql(tableName: String, columns: List<ColumnDef>): String {
        val primaryKeys = columns.filter { it.isPrimaryKey }
        val isComposite = primaryKeys.size > 1

        val sb = StringBuilder("CREATE TABLE \"$tableName\" (\n")
        val defs = columns.map { col ->
            buildString {
                append("    \"${col.name}\" ${col.type}")
                if (!col.nullable) append(" NOT NULL")
                when {
                    isComposite -> {}
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

    private fun schemaObjectType(name: String): String? = driver.executeQuery(
        identifier = null,
        sql = "SELECT type FROM sqlite_master WHERE name = ? COLLATE NOCASE",
        mapper = { cursor -> if (cursor.next()) cursor.getString(0) else null },
        parameters = 1
    ) { bindString(0, name) }

    private fun foreignKeysEnabled(): Boolean = driver.executeQuery(
        identifier = null,
        sql = "PRAGMA foreign_keys",
        mapper = { cursor -> cursor.next() && cursor.getLong(0) == 1L },
        parameters = 0
    )

    private fun pragmaColumns(tableName: String): Map<String, String> = driver.executeQuery(
        identifier = null,
        sql = "PRAGMA table_info(\"$tableName\")",
        mapper = { cursor ->
            val columns = mutableMapOf<String, String>()
            while (cursor.next()) {
                columns[cursor.getString(1)!!] = cursor.getString(2) ?: ""
            }
            columns
        },
        parameters = 0
    )
}
