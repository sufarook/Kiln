package com.farook.delightcrud.runtime

import app.cash.sqldelight.db.SqlDriver

class DatabaseManager(
    val driver: SqlDriver,
    private val tables: List<TableSchema>
) {
    fun initialize() {
        tables.forEach { it.createTable(driver) }
    }
}
