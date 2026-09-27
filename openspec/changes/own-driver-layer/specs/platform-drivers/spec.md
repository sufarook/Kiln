# Platform Drivers

Specifies platform-specific behavior for the three `KilnDriver`
implementations: Android (framework API), JVM (sqlite-jdbc), and iOS
(SQLiter). Each implementation satisfies the full driver-contract spec; this
spec captures platform-specific defaults, threading models, and configuration.

## ADDED Requirements

### REQ-PLT-1: Android driver enables WAL

`AndroidKilnDriver` enables Write-Ahead Logging on the database via
`SQLiteDatabase.enableWriteAheadLogging()`. This allows concurrent read
access while a write transaction is in progress.

### REQ-PLT-2: Android driver does not manage user_version

`AndroidKilnDriver` does not set, increment, or read `PRAGMA user_version`.
The `SQLiteOpenHelper` is configured with version 1 (the minimum Android
allows) but `onCreate`/`onUpgrade` are no-ops. Kiln's migration is
version-less and must not conflict with other version-based migration systems
sharing the same database file.

### REQ-PLT-3: Android driver database path

`AndroidDatabaseDriverFactory(context).create(dbName)` stores the database
at the default location returned by `context.getDatabasePath(dbName)`. The
factory does not accept arbitrary paths — this matches Android conventions
and avoids file-permission issues.

### REQ-PLT-4: JVM driver enables WAL

`JvmKilnDriver` executes `PRAGMA journal_mode=WAL` immediately after opening
the connection.

### REQ-PLT-5: JVM driver sets busy timeout

`JvmKilnDriver` executes `PRAGMA busy_timeout=5000` after opening the
connection. This prevents `SQLITE_BUSY` errors during moderate contention.

### REQ-PLT-6: JVM driver supports file and in-memory databases

`JvmDatabaseDriverFactory.create(dbPath)` opens a file-backed database at
the given path (creating the file if it does not exist).
`JvmDatabaseDriverFactory.createInMemory()` opens a private in-memory
database that is destroyed when the driver is closed.

### REQ-PLT-7: JVM driver thread safety

`JvmKilnDriver` serializes all database access through a single JDBC
`Connection`. Concurrent calls to `execute`/`executeQuery` from different
threads are safe — the implementation synchronizes internally. This matches
the single-connection model of SQLDelight's `JdbcSqliteDriver`.

### REQ-PLT-8: iOS driver uses SQLiter with default configuration

`IosKilnDriver` wraps SQLiter's `DatabaseConnection` obtained from a
`DatabaseManager` with `DatabaseConfiguration`. SQLiter's defaults are
preserved: WAL mode enabled, 5-second busy timeout, single connection with
mutex serialization.

### REQ-PLT-9: iOS driver database path

`IosDatabaseDriverFactory().create(dbName)` stores the database in the
app's default documents directory (or the platform-appropriate location that
SQLiter uses by default). The factory accepts a database name, not a full
path — SQLiter resolves the path based on the platform convention.

### REQ-PLT-10: iOS driver thread safety

`IosKilnDriver` delegates thread safety to SQLiter's internal mutex. All
operations are serialized at the connection level. Concurrent Kotlin
coroutines accessing the same driver are safe — SQLiter's mutex ensures
mutual exclusion.

### REQ-PLT-11: SQLiter is an implementation dependency

The `co.touchlab:sqliter` dependency is declared as `implementation` (not
`api`) in the `iosMain` source set. It does not appear on consumers'
classpaths. No SQLiter type appears in any public API surface of `runtime`.

### REQ-PLT-12: sqlite-jdbc is an implementation dependency

The `org.xerial:sqlite-jdbc` dependency is declared as `implementation`
(not `api`) in the `jvmMain` source set. It does not appear on consumers'
classpaths. No JDBC type appears in any public API surface of `runtime`.

### REQ-PLT-13: No SQLDelight artifacts in dependency graph

After this change, no `app.cash.sqldelight:*` artifact appears as a direct
or transitive dependency of any published Kiln module (`annotations`,
`runtime`, `processor`, Gradle plugin). The version catalog entry for
`sqldelight` is removed.

### REQ-PLT-14: Android unit tests use JDBC driver

Android `androidUnitTest` tests run on the host JVM and cannot use
`android.database.sqlite`. These tests use `JvmKilnDriver` (backed by
sqlite-jdbc) as a test fixture, matching the current approach.

### REQ-PLT-15: iOS linker flags preserved

The `linkerOpts("-lsqlite3")` on iOS targets remains. SQLiter's cinterop
links against the system `libsqlite3.dylib`, and Kiln's test binaries
(standalone Kotlin/Native executables without an Xcode project) need the
explicit link flag.

### REQ-PLT-16: Factory return types are KilnDriver

All three factories return `KilnDriver` (not a platform-specific subtype).
Consumers program against the interface. The concrete implementation class
is internal.

### REQ-PLT-17: Android WAL allows concurrent reads

While a write transaction is active on `AndroidKilnDriver`, read queries
from other threads succeed without blocking (WAL mode). Write-write
contention is serialized by Android's internal locking.

### REQ-PLT-18: JVM connection pragmas execute before any user SQL

WAL mode and busy timeout PRAGMAs execute during driver construction,
before `create()` returns. Any subsequent `execute()` or `executeQuery()`
call sees WAL mode and the configured busy timeout already active.

## ADDED Scenarios

### SCN-PLT-1: Android database file created on first use

Given a fresh app install with no existing database,
when `AndroidDatabaseDriverFactory(context).create("test.db")` is called,
then a database file exists at `context.getDatabasePath("test.db")`,
and `PRAGMA journal_mode` returns `wal`.

### SCN-PLT-2: Android does not stamp user_version

Given a new database created by `AndroidDatabaseDriverFactory`,
when `PRAGMA user_version` is queried,
then the result is 0 (SQLite default, not 1).

### SCN-PLT-3: JVM file database persists across driver instances

Given `JvmDatabaseDriverFactory().create("test.db")` with a table and data,
when the driver is closed and a new driver is created for `"test.db"`,
then the table and data are present.

### SCN-PLT-4: JVM in-memory database is private

Given two drivers created by `JvmDatabaseDriverFactory().createInMemory()`,
when a table is created in the first driver,
then querying that table in the second driver fails (separate databases).

### SCN-PLT-5: JVM WAL mode active

Given a driver created by `JvmDatabaseDriverFactory().create("test.db")`,
when `PRAGMA journal_mode` is queried,
then the result is `wal`.

### SCN-PLT-6: JVM busy timeout set

Given a driver created by `JvmDatabaseDriverFactory`,
when `PRAGMA busy_timeout` is queried,
then the result is `5000`.

### SCN-PLT-7: iOS database created with WAL

Given a fresh app with no existing database,
when `IosDatabaseDriverFactory().create("test.db")` is called,
then a database file is created,
and `PRAGMA journal_mode` returns `wal`.

### SCN-PLT-8: iOS concurrent access serialized

Given an `IosKilnDriver` instance,
when two coroutines concurrently execute `INSERT` statements,
then both inserts succeed (no `SQLITE_BUSY`, no data corruption).

### SCN-PLT-9: Consumer classpath has no SQLDelight

Given a consumer project using `plugins { id("io.github.sufarook.kiln") }`,
when its dependency tree is resolved,
then no artifact with group `app.cash.sqldelight` appears in the compile or
runtime classpath.

### SCN-PLT-10: Android read during write succeeds

Given an `AndroidKilnDriver` with WAL enabled,
when thread A holds a write transaction,
and thread B executes a SELECT query,
then thread B's query succeeds and returns data from before the uncommitted
transaction (snapshot isolation).

### SCN-PLT-11: JVM statement cache cleared on close

Given a `JvmKilnDriver` that has executed queries with non-null identifiers,
when `close()` is called,
then all cached `PreparedStatement` objects are finalized,
and the JDBC `Connection` is closed.

### SCN-PLT-12: Android unit test uses JVM driver

Given the `:runtime:androidUnitTest` test suite,
when tests run on the host JVM,
then `TestDriverFactory` returns a `JvmKilnDriver` (backed by sqlite-jdbc),
and all tests pass.

### SCN-PLT-13: Factory returns interface type

Given any factory's `create()` method,
when the return type is inspected,
then it is declared as `KilnDriver` (not `AndroidKilnDriver`,
`JvmKilnDriver`, or `IosKilnDriver`).
