package io.github.sufarook.kiln.processor

object SqlStatementBuilder {

    // Quote identifiers to avoid conflicts with SQL reserved keywords (e.g. "order", "group")
    private fun String.q() = "\"$this\""

    fun createTable(meta: EntityMetadata): String {
        val defs = mutableListOf<String>()

        if (meta.isCompositeKey) {
            // A composite key can't use an inline column-level PRIMARY KEY — SQLite
            // requires a single table-level constraint naming every key column.
            meta.primaryKeys.forEach { pk ->
                defs += buildString {
                    append("    ${pk.columnName.q()} ${pk.sqliteType}")
                    if (!pk.isNullable) append(" NOT NULL")
                }
            }
        } else {
            val pk = meta.primaryKeys.single()
            defs += buildString {
                append("    ${pk.columnName.q()} ${pk.sqliteType}")
                if (!pk.isNullable) append(" NOT NULL")
                if (pk.autoGenerate) append(" PRIMARY KEY AUTOINCREMENT")
                else append(" PRIMARY KEY")
            }
        }

        meta.columns.forEach { col ->
            defs += buildString {
                append("    ${col.columnName.q()} ${col.sqliteType}")
                if (!col.isNullable) append(" NOT NULL")
                if (col.isUnique) append(" UNIQUE")
            }
        }

        if (meta.isCompositeKey) {
            defs += "    PRIMARY KEY (${meta.primaryKeys.joinToString(", ") { it.columnName.q() }})"
        }

        return "CREATE TABLE IF NOT EXISTS ${meta.tableName.q()} (\n" + defs.joinToString(",\n") + "\n)"
    }

    /** One CREATE INDEX statement per @Column(index = true) property. */
    fun createIndexes(meta: EntityMetadata): List<String> =
        meta.columns
            .filter { it.hasIndex }
            .map { col ->
                "CREATE INDEX IF NOT EXISTS ${"idx_${meta.tableName}_${col.columnName}".q()} " +
                    "ON ${meta.tableName.q()} (${col.columnName.q()})"
            }

    fun insert(meta: EntityMetadata): String {
        val insertCols = if (!meta.isCompositeKey && meta.primaryKeys.single().autoGenerate)
            meta.columns else meta.allColumns
        val cols = insertCols.joinToString(", ") { it.columnName.q() }
        val placeholders = insertCols.joinToString(", ") { "?" }
        return "INSERT INTO ${meta.tableName.q()} ($cols) VALUES ($placeholders)"
    }

    private fun pkWhereClause(meta: EntityMetadata): String =
        meta.primaryKeys.joinToString(" AND ") { "${it.columnName.q()} = ?" }

    fun update(meta: EntityMetadata): String {
        val setClauses = meta.columns.joinToString(", ") { "${it.columnName.q()} = ?" }
        return "UPDATE ${meta.tableName.q()} SET $setClauses WHERE ${pkWhereClause(meta)}"
    }

    fun delete(meta: EntityMetadata): String =
        "DELETE FROM ${meta.tableName.q()} WHERE ${pkWhereClause(meta)}"

    fun findById(meta: EntityMetadata): String {
        val cols = meta.allColumns.joinToString(", ") { it.columnName.q() }
        return "SELECT $cols FROM ${meta.tableName.q()} WHERE ${pkWhereClause(meta)}"
    }

    fun findAll(meta: EntityMetadata): String {
        val cols = meta.allColumns.joinToString(", ") { it.columnName.q() }
        return "SELECT $cols FROM ${meta.tableName.q()}"
    }
}
