package com.farook.krate.processor

object SqlStatementBuilder {

    // Quote identifiers to avoid conflicts with SQL reserved keywords (e.g. "order", "group")
    private fun String.q() = "\"$this\""

    fun createTable(meta: EntityMetadata): String {
        val sb = StringBuilder("CREATE TABLE IF NOT EXISTS ${meta.tableName.q()} (\n")
        val pk = meta.primaryKey
        val pkDef = buildString {
            append("    ${pk.columnName.q()} ${pk.sqliteType}")
            if (!pk.isNullable) append(" NOT NULL")
            if (pk.autoGenerate) append(" PRIMARY KEY AUTOINCREMENT")
            else append(" PRIMARY KEY")
        }
        sb.append(pkDef)

        meta.columns.forEach { col ->
            sb.append(",\n    ${col.columnName.q()} ${col.sqliteType}")
            if (!col.isNullable) sb.append(" NOT NULL")
            if (col.isUnique) sb.append(" UNIQUE")
        }
        sb.append("\n)")
        return sb.toString()
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
        val insertCols = if (meta.primaryKey.autoGenerate)
            meta.columns else meta.allColumns
        val cols = insertCols.joinToString(", ") { it.columnName.q() }
        val placeholders = insertCols.joinToString(", ") { "?" }
        return "INSERT INTO ${meta.tableName.q()} ($cols) VALUES ($placeholders)"
    }

    fun update(meta: EntityMetadata): String {
        val setClauses = meta.columns.joinToString(", ") { "${it.columnName.q()} = ?" }
        return "UPDATE ${meta.tableName.q()} SET $setClauses WHERE ${meta.primaryKey.columnName.q()} = ?"
    }

    fun delete(meta: EntityMetadata): String =
        "DELETE FROM ${meta.tableName.q()} WHERE ${meta.primaryKey.columnName.q()} = ?"

    fun findById(meta: EntityMetadata): String {
        val cols = meta.allColumns.joinToString(", ") { it.columnName.q() }
        return "SELECT $cols FROM ${meta.tableName.q()} WHERE ${meta.primaryKey.columnName.q()} = ?"
    }

    fun findAll(meta: EntityMetadata): String {
        val cols = meta.allColumns.joinToString(", ") { it.columnName.q() }
        return "SELECT $cols FROM ${meta.tableName.q()}"
    }
}
