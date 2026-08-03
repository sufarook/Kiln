package io.github.sufarook.kiln.runtime

import app.cash.sqldelight.db.SqlDriver

/**
 * A fresh in-memory SQLite driver for a single test. Test-only — not part of the
 * published API, which is why this lives in commonTest rather than commonMain.
 *
 * SchemaMigrator/QueryDsl/Transaction tests run this same suite against whichever
 * driver each platform provides, so a regression on iOS (or Android's JVM-hosted
 * unit tests) is caught here instead of only ever being exercised on the JVM.
 */
expect fun createTestDriver(): SqlDriver
