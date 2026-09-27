# Design: Own Driver Layer

## Overview

Four new interfaces in `runtime/commonMain` define Kiln's driver contract.
Three `expect`/`actual` implementations back them with platform-native SQLite
access. The processor switches its generated code to reference Kiln types
instead of SQLDelight types. All SQLDelight dependencies are then removed.

## Interfaces

All interfaces live in `io.github.sufarook.kiln.runtime` in commonMain.

### KilnDriver

The top-level driver. One instance per database file (or in-memory database).
Thread-safety guarantees are implementation-defined per platform.

```kotlin
interface KilnDriver : Closeable {
    fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (KilnPreparedStatement.() -> Unit)? = null,
    ): Long

    fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (KilnCursor) -> R,
        parameters: Int,
        binders: (KilnPreparedStatement.() -> Unit)? = null,
    ): R

    fun newTransaction(): KilnTransaction

    fun currentTransaction(): KilnTransaction?

    fun addListener(vararg queryKeys: String, listener: KilnListener)

    fun removeListener(vararg queryKeys: String, listener: KilnListener)

    fun notifyListeners(vararg queryKeys: String)

    override fun close()
}
```

Design decisions:

- **`execute` returns `Long`** — the number of rows affected. SQLDelight's
  `execute` returns `QueryResult<Long>` wrapping sync/async; Kiln is always
  synchronous, so the wrapper is unnecessary.

- **`executeQuery` returns `R` directly** — the mapper runs inside the call
  and the cursor is closed before returning. No `QueryResult` wrapper. This
  matches how Kiln actually uses it today (always `QueryResult.Value`).

- **`identifier: Int?`** — opaque cache key for prepared-statement reuse.
  Kiln's codegen assigns stable identifiers per query. Implementations may
  use this for statement caching; passing `null` means no caching.

- **`KilnListener` replaces `Query.Listener`** — a functional interface with a
  single `queryResultsChanged()` method. Identical contract, Kiln-owned type.

- **`Closeable`** — extends `kotlin.io.Closeable` (available in common since
  Kotlin 1.8) so drivers work with `use {}`.

### KilnPreparedStatement

Receives bound parameters before execution. The implementation wraps a
platform-specific prepared statement.

```kotlin
interface KilnPreparedStatement {
    fun bindString(index: Int, value: String?)
    fun bindLong(index: Int, value: Long?)
    fun bindDouble(index: Int, value: Double?)
    fun bindBytes(index: Int, value: ByteArray?)
}
```

Design decisions:

- **Nullable values** — passing `null` binds SQL NULL. This replaces the need
  for a separate `bindNull(index)` method and matches how Kiln generates bind
  calls today (the `SqlArg` sealed class already carries nullability).

- **No `bindBoolean`** — Kiln stores booleans as `Long` (0/1). The processor
  generates the conversion at codegen time. This keeps the driver contract
  minimal.

- **1-based indexing** — matches SQLite's native parameter indexing and
  SQLDelight's convention.

### KilnCursor

Iterates over query results. Callers must call `next()` before reading the
first row. The cursor is only valid inside the `executeQuery` mapper — the
implementation closes the underlying statement when the mapper returns.

```kotlin
interface KilnCursor {
    fun next(): Boolean
    fun getString(index: Int): String?
    fun getLong(index: Int): Long?
    fun getDouble(index: Int): Double?
    fun getBytes(index: Int): ByteArray?
}
```

Design decisions:

- **All getters return nullable** — a column may contain SQL NULL regardless
  of the entity's Kotlin nullability. The processor generates the non-null
  assertion (`!!`) in the mapper when the column is declared non-nullable.

- **No `getBoolean`** — same rationale as `bindBoolean`. The processor
  generates `getLong(i) == 1L` (or `getLong(i)?.let { it == 1L }` for
  nullable booleans).

- **0-based column indexing** — matches JDBC's `ResultSet` and SQLDelight's
  cursor convention. (SQLite's C API is 0-based for columns, 1-based for
  parameters.)

### KilnTransaction

Represents an open transaction. Nested transactions use savepoints.

```kotlin
interface KilnTransaction {
    val enclosingTransaction: KilnTransaction?

    fun endTransaction(successful: Boolean)

    fun childTransaction(): KilnTransaction
}
```

Design decisions:

- **`endTransaction(successful)`** — combines commit/rollback into one call.
  `true` commits (or releases the savepoint); `false` rolls back. The
  existing `KilnTransacter` in Transaction.kt already manages the
  commit/rollback lifecycle via `SuspendingTransacterImpl` — it will move to
  calling `endTransaction` on this interface instead.

- **`childTransaction()`** — creates a savepoint for nesting. Returns a new
  `KilnTransaction` whose `enclosingTransaction` points to the parent.

- **`enclosingTransaction`** — allows the transaction manager to walk the
  chain and detect whether a transaction is currently open.

### KilnListener

```kotlin
fun interface KilnListener {
    fun queryResultsChanged()
}
```

Drop-in replacement for `Query.Listener`. Functional interface so lambdas
work directly.

## Platform Implementations

### Android: `AndroidKilnDriver`

**Backing:** `android.database.sqlite.SQLiteDatabase` via
`android.database.sqlite.SQLiteOpenHelper`.

```
AndroidDatabaseDriverFactory(context: Context)
  └─ create(dbName: String): KilnDriver
       └─ AndroidKilnDriver(helper: SQLiteOpenHelper)
            ├─ execute()    → helper.writableDatabase.compileStatement(sql)
            ├─ executeQuery() → helper.readableDatabase.rawQuery(sql, args)
            └─ newTransaction() → helper.writableDatabase.beginTransaction()
```

Key behaviors:
- **WAL mode** — enabled explicitly via
  `SQLiteDatabase.enableWriteAheadLogging()` in `onConfigure()`. WAL allows
  concurrent reads during writes, matching SQLDelight's `AndroidSqliteDriver`
  behavior.
- **No schema callback** — `onCreate`/`onUpgrade` are empty. Kiln does not
  use `user_version` for migration; `SchemaMigrator` reconciles via
  `PRAGMA table_info`. The helper exists only for file management and WAL
  setup.
- **`user_version` left at 0** — new databases get SQLite's default (0).
  Unlike the current `EmptySchema` which stamps version 1, the own driver
  does not touch `user_version`. This fixes the coexistence problem documented
  in the roadmap.
- **Thread safety** — `SQLiteDatabase` is thread-safe with its own locking.
  WAL mode allows one writer + multiple readers concurrently.
- **Busy timeout** — Android's default is sufficient (system-managed via
  `SQLiteDatabase`).

No new dependency. `android.database.sqlite` is framework API.

### JVM: `JvmKilnDriver`

**Backing:** `java.sql.Connection` from `org.xerial:sqlite-jdbc`.

```
JvmDatabaseDriverFactory()
  ├─ create(dbPath: String): KilnDriver
  │    └─ JvmKilnDriver(DriverManager.getConnection("jdbc:sqlite:$dbPath"))
  └─ createInMemory(): KilnDriver
       └─ JvmKilnDriver(DriverManager.getConnection("jdbc:sqlite::memory:"))
```

Key behaviors:
- **Single connection** — matches SQLDelight's `JdbcSqliteDriver` model. The
  driver holds one `Connection` and serializes access. Thread safety is
  provided by the driver's own synchronization.
- **WAL mode** — enabled via `PRAGMA journal_mode=WAL` after connection open.
- **Busy timeout** — set via `PRAGMA busy_timeout=5000` (5 seconds, matching
  SQLiter's default).
- **Transactions** — `Connection.setAutoCommit(false)` for begin,
  `Connection.commit()`/`Connection.rollback()` for end. Savepoints via
  `Connection.setSavepoint()`/`Connection.releaseSavepoint()`.
- **Statement caching** — when `identifier` is non-null, cache the
  `PreparedStatement` keyed by identifier. Clear cache on close.

Dependency: `org.xerial:sqlite-jdbc` as `implementation` (hidden from
consumers). Currently version 3.49.1.0 (latest stable).

### iOS: `IosKilnDriver` (SQLiter-backed)

**Backing:** `co.touchlab:sqliter` — the core module, not `sqliter-driver`
(which is the SQLDelight adapter).

```
IosDatabaseDriverFactory()
  └─ create(dbName: String): KilnDriver
       └─ IosKilnDriver(DatabaseManager(DatabaseConfiguration(...)))
            ├─ execute()    → connection.withStatement(sql) { ... }
            ├─ executeQuery() → connection.withStatement(sql) { ... }
            └─ newTransaction() → connection.withStatement("BEGIN") / savepoint
```

Key behaviors:
- **WAL mode** — enabled by default in SQLiter's `DatabaseConfiguration`.
  Kiln preserves this default.
- **Busy timeout** — 5 seconds, SQLiter's default. Kiln preserves this.
- **Threading** — SQLiter uses a single-connection model with a mutex. All
  operations are serialized. This is safe for Kiln's usage pattern (one writer
  at a time, reads wait for the lock).
- **No `user_version` stamping** — SQLiter does not require a schema object.
  Kiln opens the database file directly via `DatabaseConfiguration`.
- **Memory safety** — handled by SQLiter. No manual cinterop memory management
  needed in Phase 1. This is the primary motivation for using SQLiter as the
  intermediate step.

Dependency: `co.touchlab:sqliter` as `implementation` (hidden from consumers).

## Runtime changes

### Transaction.kt

- `KilnTransacter` currently extends `SuspendingTransacterImpl` (SQLDelight).
  Replace with Kiln's own implementation that calls
  `KilnDriver.newTransaction()` and `KilnTransaction.endTransaction()`.
- `withTransaction` extension moves from `SqlDriver` to `KilnDriver`.
- `notifyOrDefer` extension moves from `SqlDriver` to `KilnDriver`.
- `withTransactionAwareContext` extension moves from `SqlDriver` to
  `KilnDriver`.
- `TransactionThread` coroutine-context element and `TableIdentifiers`
  notification deferral logic are unchanged — they are Kiln-owned already.

### SchemaMigrator.kt

- Constructor: `SchemaMigrator(driver: KilnDriver)` (was `SqlDriver`).
- `RebuildTransacter` currently extends `TransacterImpl` (SQLDelight). Replace
  with direct `KilnDriver.newTransaction()` calls for the
  foreign-key-safe table rebuild.
- All `driver.execute()` and `driver.executeQuery()` calls change from
  returning `QueryResult.Value(...)` to returning the value directly.

### QueryObservation.kt

- `observeQuery(driver: KilnDriver, ...)` (was `SqlDriver`).
- `KilnListener` replaces `Query.Listener`. The `callbackFlow` that installs
  and removes a listener is unchanged in structure.

### QueryDsl.kt

- `KilnPreparedStatement.bindArg(index, arg)` (was `SqlPreparedStatement`).
  Implementation unchanged — dispatches to `bindString`/`bindLong`/
  `bindDouble`/`bindBytes` based on the `SqlArg` sealed class.

### EmptySchema.kt

- **Deleted.** `EmptySchema` implements `SqlSchema` to satisfy
  `AndroidSqliteDriver` and `NativeSqliteDriver` constructors. With own
  drivers, no schema object is needed. `user_version` is not managed by Kiln.

### Driver factories

- `AndroidDatabaseDriverFactory.create()`: returns `KilnDriver` instead of
  `SqlDriver`. Constructs `AndroidKilnDriver` internally.
- `JvmDatabaseDriverFactory.create()` / `createInMemory()`: returns
  `KilnDriver`. Constructs `JvmKilnDriver` internally.
- `IosDatabaseDriverFactory.create()`: returns `KilnDriver`. Constructs
  `IosKilnDriver` backed by SQLiter internally.

## Processor changes

### RepositoryGenerator.kt

Two `ClassName` constants change:

```kotlin
// Before
private val SQL_DRIVER = ClassName("app.cash.sqldelight.db", "SqlDriver")
private val QUERY_RESULT = ClassName("app.cash.sqldelight.db", "QueryResult")

// After
private val KILN_DRIVER = ClassName("io.github.sufarook.kiln.runtime", "KilnDriver")
// QUERY_RESULT removed — no longer needed
```

Generated repository constructor: `driver: KilnDriver`.

Generated mapper lambdas: return `value` directly instead of
`QueryResult.Value(value)`.

### SchemaGenerator.kt

```kotlin
// Before
private val SQL_DRIVER = ClassName("app.cash.sqldelight.db", "SqlDriver")

// After
private val KILN_DRIVER = ClassName("io.github.sufarook.kiln.runtime", "KilnDriver")
```

`KilnSchema.createAll(driver: KilnDriver)`.

## Gradle / dependency changes

### runtime/build.gradle.kts

```kotlin
// Before
commonMain {
    dependencies {
        api(libs.sqldelight.runtime)         // leaked to consumers
        api(libs.kotlinx.coroutines.core)
    }
}
androidMain {
    dependencies {
        api(libs.sqldelight.android.driver)  // leaked to consumers
    }
}
iosMain {
    dependencies {
        api(libs.sqldelight.native.driver)   // leaked to consumers
    }
}
jvmMain {
    dependencies {
        api(libs.sqldelight.sqlite.driver)   // leaked to consumers
    }
}

// After
commonMain {
    dependencies {
        api(libs.kotlinx.coroutines.core)    // only public dep remaining
    }
}
androidMain {
    dependencies {
        // no external dependency — android.database.sqlite is framework
    }
}
iosMain {
    dependencies {
        implementation(libs.sqliter)         // hidden from consumers
    }
}
jvmMain {
    dependencies {
        implementation(libs.xerial.sqlite.jdbc)  // hidden from consumers
    }
}
```

### gradle/libs.versions.toml

Remove:
- `sqldelight` version
- `sqldelight-runtime`, `sqldelight-android-driver`, `sqldelight-native-driver`,
  `sqldelight-sqlite-driver` libraries

Add:
- `sqliter` version and library (`co.touchlab:sqliter:<version>`)
- `xerial-sqlite-jdbc` library (`org.xerial:sqlite-jdbc:<version>`)

### iOS linker flags

The current `linkerOpts("-lsqlite3")` on iOS targets remains — SQLiter's
cinterop links against the system `libsqlite3.dylib`, and Kiln's test binaries
still need it explicitly.

## Test changes

- All test files importing SQLDelight types update to Kiln equivalents.
- `TestDriverFactory` on each platform creates a `KilnDriver` instead of
  `SqlDriver` (in-memory databases for tests).
- `TransactionTest`, `SchemaMigratorTest`, `QueryDslTest`,
  `ThreadBoundDriverTest` — import changes only; test logic is unchanged
  since the behavioral contracts are preserved.
- Integration test (`consumer-smoke`) — remove `sqldelight-android-driver`
  dependency, update factory usage.

## What is explicitly NOT in scope

- **Phase 2 (own iOS cinterop)** — deferred. SQLiter is the iOS backing for
  now.
- **Migration bridge (`SqlDriver.asKilnDriver()`)** — not provided. Kiln is
  pre-1.0; consumers are expected to switch. This is a clean break.
- **Async query support** — Kiln has never used `QueryResult.AsyncValue`.
  `KilnDriver` is synchronous-only. Async may be revisited post-1.0 if
  demand arises.
- **Connection pooling** — each platform driver uses a single connection (or
  the platform's own pooling, as with Android's `SQLiteDatabase`). This
  matches the current behavior.
