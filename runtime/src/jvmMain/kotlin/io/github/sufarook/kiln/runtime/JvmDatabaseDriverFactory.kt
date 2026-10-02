package io.github.sufarook.kiln.runtime

import java.sql.DriverManager

class JvmDatabaseDriverFactory {
    fun create(dbPath: String): KilnDriver = JvmKilnDriver(DriverManager.getConnection("jdbc:sqlite:$dbPath"))

    fun createInMemory(): KilnDriver = JvmKilnDriver(DriverManager.getConnection("jdbc:sqlite::memory:"))
}
