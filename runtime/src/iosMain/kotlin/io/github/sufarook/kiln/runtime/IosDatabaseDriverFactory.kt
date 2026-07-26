package io.github.sufarook.kiln.runtime

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

class IosDatabaseDriverFactory {
    fun create(dbName: String): SqlDriver =
        NativeSqliteDriver(
            schema = EmptySchema,
            name = dbName
        )
}
