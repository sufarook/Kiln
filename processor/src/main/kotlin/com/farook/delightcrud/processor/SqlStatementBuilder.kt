package com.farook.delightcrud.processor

object SqlStatementBuilder {

    fun createTable(meta: EntityMetadata): String {
        val sb = StringBuilder("CREATE TABLE IF NOT EXISTS ${meta.tableName} (\n")
        val pk = meta.primaryKey
        val pkDef = buildString {
            append("    ${pk.columnName} ${pk.sqliteType}")
            if (!pk.isNullable) append(" NOT NULL")
            if (pk.autoGenerate) append(" PRIMARY KEY AUTOINCREMENT")
            else append(" PRIMARY KEY")
        }
        sb.append(pkDef)

        meta.columns.forEach { col ->
            sb.append(",\n    ${col.columnName} ${col.sqliteType}")
            if (!col.isNullable) sb.append(" NOT NULL")
            if (col.isUnique) sb.append(" UNIQUE")
        }
        sb.append("\n)")
        return sb.toString()
    }

    fun insert(meta: EntityMetadata): String {
        val insertCols = if (meta.primaryKey.autoGenerate)
            meta.columns else meta.allColumns
        val cols = insertCols.joinToString(", ") { it.columnName }
        val placeholders = insertCols.joinToString(", ") { "?" }
        return "INSERT INTO ${meta.tableName} ($cols) VALUES ($placeholders)"
    }

    fun update(meta: EntityMetadata): String {
        val setClauses = meta.columns.joinToString(", ") { "${it.columnName} = ?" }
        return "UPDATE ${meta.tableName} SET $setClauses WHERE ${meta.primaryKey.columnName} = ?"
    }

    fun delete(meta: EntityMetadata): String =
        "DELETE FROM ${meta.tableName} WHERE ${meta.primaryKey.columnName} = ?"

    fun findById(meta: EntityMetadata): String {
        val cols = meta.allColumns.joinToString(", ") { it.columnName }
        return "SELECT $cols FROM ${meta.tableName} WHERE ${meta.primaryKey.columnName} = ?"
    }

    fun findAll(meta: EntityMetadata): String {
        val cols = meta.allColumns.joinToString(", ") { it.columnName }
        return "SELECT $cols FROM ${meta.tableName}"
    }
}
