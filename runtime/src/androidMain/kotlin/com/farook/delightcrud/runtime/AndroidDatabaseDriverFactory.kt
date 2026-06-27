package com.farook.delightcrud.runtime

import android.content.Context
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

class AndroidDatabaseDriverFactory(private val context: Context) {
    fun create(dbName: String): SqlDriver =
        AndroidSqliteDriver(
            schema = EmptySchema,
            context = context,
            name = dbName
        )
}

private object EmptySchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 1L
    override fun create(driver: SqlDriver): QueryResult.Value<Unit> = QueryResult.Value(Unit)
    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion
    ): QueryResult.Value<Unit> = QueryResult.Value(Unit)
}
