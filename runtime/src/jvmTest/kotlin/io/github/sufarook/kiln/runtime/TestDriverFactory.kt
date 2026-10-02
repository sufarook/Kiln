package io.github.sufarook.kiln.runtime

import java.sql.DriverManager

actual fun createTestDriver(): KilnDriver = JvmKilnDriver(DriverManager.getConnection("jdbc:sqlite::memory:"))
