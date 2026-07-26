package io.github.sufarook.kiln.runtime

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema

/**
 * No-op schema passed to platform drivers. Kiln does not use SQLDelight's
 * version-based schema management — tables are created and migrated by the
 * generated createTable() + SchemaMigrator on every launch.
 */
internal object EmptySchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 1L
    override fun create(driver: SqlDriver): QueryResult.Value<Unit> = QueryResult.Value(Unit)
    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion
    ): QueryResult.Value<Unit> = QueryResult.Value(Unit)
}
