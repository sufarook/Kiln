package com.farook.delightcrud.runtime

import app.cash.sqldelight.db.SqlDriver

interface TableSchema {
    fun createTable(driver: SqlDriver)
}
