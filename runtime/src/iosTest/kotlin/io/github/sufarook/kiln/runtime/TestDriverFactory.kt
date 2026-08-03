package io.github.sufarook.kiln.runtime

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

actual fun createTestDriver(): SqlDriver = NativeSqliteDriver(
    schema = EmptySchema,
    name = "test",
    onConfiguration = { config -> config.copy(name = null, inMemory = true) }
)
