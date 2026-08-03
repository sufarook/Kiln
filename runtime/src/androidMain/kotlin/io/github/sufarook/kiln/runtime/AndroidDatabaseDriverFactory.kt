package io.github.sufarook.kiln.runtime

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

class AndroidDatabaseDriverFactory(private val context: Context) {
    fun create(dbName: String): SqlDriver = AndroidSqliteDriver(
        schema = EmptySchema,
        context = context,
        name = dbName
    )
}
