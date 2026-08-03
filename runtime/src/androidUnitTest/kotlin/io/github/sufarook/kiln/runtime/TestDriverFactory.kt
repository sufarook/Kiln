package io.github.sufarook.kiln.runtime

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

// Local Android unit tests run on the host JVM, not a device — the JDBC driver
// works here for the same reason it works in jvmTest. This exercises the shared
// commonMain migrator/DSL/transaction logic on this target; it says nothing about
// AndroidDatabaseDriverFactory itself, which needs a real device/emulator to test.
actual fun createTestDriver(): SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
