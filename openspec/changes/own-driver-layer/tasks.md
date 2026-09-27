# Tasks: Own Driver Layer

## Task 1: Define KilnDriver interfaces

**Status:** pending

Add the four interfaces and the listener type to
`runtime/src/commonMain/kotlin/io/github/sufarook/kiln/runtime/`:

- `KilnDriver.kt` — `KilnDriver` interface (execute, executeQuery,
  transaction, listener, close)
- `KilnPreparedStatement.kt` — bind methods (string, long, double, bytes)
- `KilnCursor.kt` — next + column getters
- `KilnTransaction.kt` — endTransaction, childTransaction, enclosingTransaction
- `KilnListener.kt` — `fun interface` with `queryResultsChanged()`

These are pure interface files with no implementation. They compile on all
targets.

**Depends on:** nothing

---

## Task 2: Implement AndroidKilnDriver

**Status:** pending

Add `AndroidKilnDriver` as an `internal class` in
`runtime/src/androidMain/`. Backed by `SQLiteOpenHelper` +
`SQLiteDatabase`.

Key implementation details:
- `SQLiteOpenHelper` with version 1, empty `onCreate`/`onUpgrade`
- Enable WAL in `onConfigure()`
- `execute()` → `compileStatement(sql).execute()` and
  `SQLiteDatabase.rawQuery` for affected-row-count queries
- `executeQuery()` → `rawQuery(sql, bindArgs)` wrapped in a
  `KilnCursor` adapter, mapper called, cursor closed
- Transaction management via `beginTransaction()`/
  `setTransactionSuccessful()`/`endTransaction()` and savepoints for
  nesting
- Statement caching keyed by `identifier`
- Listener registry (in-memory map of query keys to listener sets)

Update `AndroidDatabaseDriverFactory` to return `KilnDriver` (construct
`AndroidKilnDriver` internally).

**Depends on:** Task 1

---

## Task 3: Implement JvmKilnDriver

**Status:** pending

Add `JvmKilnDriver` as an `internal class` in
`runtime/src/jvmMain/`. Backed by JDBC `Connection` from sqlite-jdbc.

Key implementation details:
- Open connection with `DriverManager.getConnection("jdbc:sqlite:...")`
- Execute `PRAGMA journal_mode=WAL` and `PRAGMA busy_timeout=5000` on
  construction
- `execute()` → `PreparedStatement.executeUpdate()` for DML,
  `execute()` for DDL; return `connection.prepareStatement(sql).also {
  bind(it) }.executeUpdate().toLong()`
- `executeQuery()` → `PreparedStatement.executeQuery()` → wrap
  `ResultSet` in `KilnCursor` adapter → call mapper → close
- Transactions via `setAutoCommit(false)`/`commit()`/`rollback()` and
  `setSavepoint()`/`releaseSavepoint()` for nesting
- Statement caching keyed by `identifier`
- Thread-safe via `synchronized` on the connection
- Listener registry (same pattern as Android)

Update `JvmDatabaseDriverFactory` to return `KilnDriver`.

**Depends on:** Task 1

---

## Task 4: Implement IosKilnDriver (SQLiter-backed)

**Status:** pending

Add `IosKilnDriver` as an `internal class` in
`runtime/src/iosMain/`. Backed by SQLiter's `DatabaseConnection`.

Key implementation details:
- Obtain `DatabaseConnection` from `DatabaseManager` with default
  `DatabaseConfiguration` (WAL, 5s busy timeout)
- `execute()` → `connection.withStatement(sql) { bindAndExecute() }`
- `executeQuery()` → `connection.withStatement(sql) { bind; query();
  wrapCursorAndCallMapper() }`
- Transactions via `connection.withStatement("BEGIN IMMEDIATE") { execute() }`
  and `COMMIT`/`ROLLBACK`; savepoints via `SAVEPOINT name`/
  `RELEASE name`/`ROLLBACK TO name`
- Listener registry (same pattern)
- Thread safety via SQLiter's internal mutex

Update `IosDatabaseDriverFactory` to return `KilnDriver`.

Add `co.touchlab:sqliter` as `implementation` dependency in iosMain.

**Depends on:** Task 1

---

## Task 5: Migrate Transaction.kt to KilnDriver

**Status:** pending

- Remove `SuspendingTransacterImpl` dependency
- Rewrite `KilnTransacter` (private class) to use `KilnDriver.newTransaction()`
  and `KilnTransaction.endTransaction()`
- Change extension receiver: `KilnDriver.withTransaction {}` (was `SqlDriver`)
- Change extension receiver: `KilnDriver.notifyOrDefer()` (was `SqlDriver`)
- Change extension receiver: `KilnDriver.withTransactionAwareContext()` (was
  `SqlDriver`)
- `TransactionThread` and `TableIdentifiers` stay unchanged

Preserve all behavioral guarantees from the `transactions` spec (nesting,
thread pinning, notification deferral, rollback surfacing).

**Depends on:** Task 1

---

## Task 6: Migrate SchemaMigrator.kt to KilnDriver

**Status:** pending

- Constructor: `SchemaMigrator(driver: KilnDriver)` (was `SqlDriver`)
- Remove `TransacterImpl` and `RebuildTransacter` — use
  `KilnDriver.newTransaction()` directly for the table-rebuild transaction
- Remove `QueryResult.Value` wrappers from all `executeQuery` calls (return
  values directly)
- Preserve all guarantees from the `schema-migration` spec (foreign-key-safe
  rebuild, cascade survival, view rejection)

**Depends on:** Task 1

---

## Task 7: Migrate QueryObservation.kt and QueryDsl.kt

**Status:** pending

QueryObservation.kt:
- `observeQuery(driver: KilnDriver, ...)` (was `SqlDriver`)
- Replace `Query.Listener` with `KilnListener`
- `callbackFlow` structure unchanged

QueryDsl.kt:
- `KilnPreparedStatement.bindArg(index, arg)` (was `SqlPreparedStatement`)

**Depends on:** Task 1

---

## Task 8: Delete EmptySchema.kt

**Status:** pending

Remove `runtime/src/commonMain/.../EmptySchema.kt`. This object implemented
`SqlSchema` solely to satisfy `AndroidSqliteDriver` and `NativeSqliteDriver`
constructors. With own drivers, it is unused.

**Depends on:** Tasks 2, 3, 4 (all factories migrated)

---

## Task 9: Update processor codegen

**Status:** pending

RepositoryGenerator.kt:
- Replace `SQL_DRIVER` ClassName with `KILN_DRIVER`
  (`io.github.sufarook.kiln.runtime.KilnDriver`)
- Remove `QUERY_RESULT` ClassName — no longer needed
- Generated constructors: `driver: KilnDriver`
- Generated mapper lambdas: return value directly (not
  `QueryResult.Value(value)`)

SchemaGenerator.kt:
- Replace `SQL_DRIVER` ClassName with `KILN_DRIVER`
- `KilnSchema.createAll(driver: KilnDriver)`

Verify that `KilnPreparedStatement` is referenced (not `SqlPreparedStatement`)
in generated bind call sites.

**Depends on:** Task 1

---

## Task 10: Update Gradle dependencies

**Status:** pending

gradle/libs.versions.toml:
- Remove `sqldelight` version entry
- Remove `sqldelight-runtime`, `sqldelight-android-driver`,
  `sqldelight-native-driver`, `sqldelight-sqlite-driver` library entries
- Add `sqliter` version and library entry (`co.touchlab:sqliter`)
- Add `xerial-sqlite-jdbc` library entry (`org.xerial:sqlite-jdbc`) — or
  rename existing `sqldelight-sqlite-driver` to `xerial-sqlite-jdbc` pointing
  at the xerial artifact

runtime/build.gradle.kts:
- commonMain: remove `api(libs.sqldelight.runtime)`
- androidMain: remove `api(libs.sqldelight.android.driver)`
- iosMain: replace `api(libs.sqldelight.native.driver)` with
  `implementation(libs.sqliter)`
- jvmMain: replace `api(libs.sqldelight.sqlite.driver)` with
  `implementation(libs.xerial.sqlite.jdbc)`
- androidUnitTest: replace `implementation(libs.sqldelight.sqlite.driver)`
  with `implementation(libs.xerial.sqlite.jdbc)`

**Depends on:** nothing (can run in parallel with other tasks)

---

## Task 11: Update all tests

**Status:** pending

- `TestDriverFactory` on all platforms: return `KilnDriver` instead of
  `SqlDriver`
- `TransactionTest.kt`: replace SQLDelight imports with Kiln types
- `SchemaMigratorTest.kt`: replace SQLDelight imports with Kiln types
- `QueryDslTest.kt`: replace SQLDelight imports with Kiln types
- `ThreadBoundDriverTest.kt`: replace SQLDelight imports with Kiln types
- Add new tests for `KilnDriver` contract validation (scenarios from
  driver-contract spec)
- Add platform-specific tests (scenarios from platform-drivers spec)

Run: `./gradlew :runtime:jvmTest :runtime:iosSimulatorArm64Test`

**Depends on:** Tasks 2, 3, 4, 5, 6, 7

---

## Task 12: Update processor tests

**Status:** pending

Update compile-testing tests in `processor/src/test/` to verify that
generated code references `KilnDriver` and `KilnPreparedStatement` instead
of SQLDelight types.

Run: `./gradlew :processor:test`

**Depends on:** Task 9

---

## Task 13: Run apiDump

**Status:** pending

```
./gradlew apiDump
```

Verify that:
- No `app.cash.sqldelight` type appears in any `.api` file
- `KilnDriver` appears where `SqlDriver` was
- `KilnPreparedStatement` appears where `SqlPreparedStatement` was
- New types (`KilnDriver`, `KilnPreparedStatement`, `KilnCursor`,
  `KilnTransaction`, `KilnListener`) appear in the JVM and Android `.api`
  files
- iOS KLib ABI is clean

**Depends on:** Tasks 5, 6, 7, 8, 9

---

## Task 14: Update consumer-smoke integration tests

**Status:** pending

In `integration-tests/consumer-smoke/`:
- Remove any `sqldelight` driver dependency from `build.gradle.kts`
- Update test code to use `KilnDriver` / `KilnDatabaseDriverFactory`
- Verify the test project compiles and passes with `--rerun-tasks`

```
./gradlew publishToMavenLocal
./gradlew -p integration-tests/consumer-smoke test --rerun-tasks
```

**Depends on:** Tasks 9, 10, 13

---

## Task 15: Update Gradle plugin tests

**Status:** pending

Verify that the Gradle plugin (`gradle-plugin/`) still wires dependencies
correctly. The plugin adds `runtime` as an `api`/`implementation` dep — it
does not reference SQLDelight directly. Tests should confirm the consumer
project builds without SQLDelight.

Run: `./gradlew :gradle-plugin:test`

**Depends on:** Task 10

---

## Task 16: Full verification gate

**Status:** pending

Run the complete project gate:

```
./gradlew :processor:test :runtime:jvmTest :gradle-plugin:test ktlintCheck apiCheck
./gradlew :runtime:iosSimulatorArm64Test
./gradlew publishToMavenLocal
./gradlew -p integration-tests/consumer-smoke test --rerun-tasks
```

All must pass.

**Depends on:** all previous tasks

---

## Task 17: Update MkDocs documentation

**Status:** pending

Update `docs/src/` to reflect the own-driver change:

- `index.md` — remove mention of SQLDelight as a dependency in the
  comparison table. Update the "Getting started" code snippet.
- `getting-started/installation.md` — remove the "add a SQLDelight driver"
  step. Show that `plugins { id("io.github.sufarook.kiln") }` is all that's
  needed.
- `getting-started/quickstart.md` — update factory usage to show
  `KilnDriver` return type.
- `faq.md` — update or remove FAQ entries about SQLDelight. Add an entry
  about Kiln's own driver layer.
- Any other page referencing `SqlDriver` or SQLDelight driver artifacts.

**Depends on:** Tasks 1–16 (after code is finalized)

---

## Task 18: Update ROADMAP.md

**Status:** pending

- Move "coexisting with an existing SQLDelight database" deferred item —
  it needs to be rewritten since Kiln no longer depends on SQLDelight at all.
  The coexistence story changes: consumers can still pass the same database
  file path, but there is no shared driver type.
- Add the completed `own-driver-layer` to "Where things stand."
- Add Phase 2 (own iOS cinterop) as a deferred item.

**Depends on:** Task 16
