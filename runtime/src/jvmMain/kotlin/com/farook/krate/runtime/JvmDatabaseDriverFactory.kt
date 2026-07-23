package com.farook.krate.runtime

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

/**
 * Creates a SQLite driver for JVM (desktop / server) targets.
 *
 * ```kotlin
 * // File-based database
 * val driver = JvmDatabaseDriverFactory().create("myapp.db")
 *
 * // In-memory database (useful for tests)
 * val driver = JvmDatabaseDriverFactory().createInMemory()
 * ```
 */
class JvmDatabaseDriverFactory {
    fun create(dbPath: String): SqlDriver =
        JdbcSqliteDriver("jdbc:sqlite:$dbPath")

    fun createInMemory(): SqlDriver =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
}
