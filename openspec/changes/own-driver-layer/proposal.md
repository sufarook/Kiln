# Own Driver Layer

## Summary

Replace Kiln's dependency on SQLDelight's `SqlDriver`, `SqlPreparedStatement`,
and all platform driver artifacts with Kiln's own `KilnDriver` interface and
three platform implementations. After this change, consumers see **zero
SQLDelight artifacts** on their classpath. Kiln becomes a self-contained KMP
SQLite stack — annotations, codegen, runtime, and driver — with no third-party
database dependency in its public API.

## Motivation

Kiln currently wraps SQLDelight at the driver level: `SqlDriver` appears in
every generated repository constructor, in `withTransaction`, in
`SchemaMigrator`, and in every factory return type. This has three consequences:

1. **Positioning.** Kiln aims to compete with SQLDelight and Room as a
   standalone KMP SQLite library. Depending on SQLDelight in the public API
   contradicts that: consumers must add SQLDelight's driver artifact, and their
   build resolves two database libraries for one database.

2. **Coupling.** A SQLDelight major bump (the upcoming 3.x) forces Kiln to
   bump too, even though Kiln uses none of SQLDelight's SQL generation,
   caching, or async query machinery. Kiln calls 8 methods on `SqlDriver` and
   uses 4 bind/4 cursor operations — a surface small enough to own.

3. **Unnecessary indirection.** `SqlDriver` exposes `QueryResult` (sync and
   async variants), `SqlSchema`, `AfterVersion`, and
   `SuspendingTransacterImpl` — none of which Kiln needs. Kiln never uses
   `QueryResult.AsyncValue`. Owning the driver lets the interface match what
   Kiln actually does: synchronous bind/cursor, synchronous execute, Kiln's own
   transaction manager.

## What changes for consumers

### Before (alpha06)

```kotlin
// build.gradle.kts
plugins { id("io.github.sufarook.kiln") version "1.0.0-alpha06" }
dependencies {
    implementation("app.cash.sqldelight:android-driver:2.3.2") // consumer must add
}
```

```kotlin
// Application code
import app.cash.sqldelight.db.SqlDriver
val driver: SqlDriver = AndroidDatabaseDriverFactory(context).create("app.db")
```

### After

```kotlin
// build.gradle.kts
plugins { id("io.github.sufarook.kiln") version "..." }
// no SQLDelight dependency — Kiln handles the driver internally
```

```kotlin
// Application code
import io.github.sufarook.kiln.runtime.KilnDriver
val driver: KilnDriver = AndroidDatabaseDriverFactory(context).create("app.db")
```

Everything else — repository usage, `withTransaction`, `observeAll` — stays the
same. The only consumer-visible type change is `SqlDriver` → `KilnDriver` in
the handful of places consumers hold a driver reference.

## Public API impact

**Breaking.** Every public signature that currently mentions `SqlDriver` or
`SqlPreparedStatement` changes to the Kiln-owned equivalent. Specifically:

| Current type | Replacement |
|---|---|
| `app.cash.sqldelight.db.SqlDriver` | `io.github.sufarook.kiln.runtime.KilnDriver` |
| `app.cash.sqldelight.db.SqlPreparedStatement` | `io.github.sufarook.kiln.runtime.KilnPreparedStatement` |

Affected public API surfaces:

- `AndroidDatabaseDriverFactory.create(): SqlDriver` → `KilnDriver`
- `JvmDatabaseDriverFactory.create(): SqlDriver` → `KilnDriver`
- `JvmDatabaseDriverFactory.createInMemory(): SqlDriver` → `KilnDriver`
- `IosDatabaseDriverFactory.create(): SqlDriver` → `KilnDriver`
- `SchemaMigrator(driver: SqlDriver)` → `SchemaMigrator(driver: KilnDriver)`
- `SqlDriver.withTransaction {}` → `KilnDriver.withTransaction {}`
- `SqlDriver.notifyOrDefer()` → `KilnDriver.notifyOrDefer()`
- `observeQuery(driver: SqlDriver, ...)` → `observeQuery(driver: KilnDriver, ...)`
- `SqlPreparedStatement.bindArg()` → `KilnPreparedStatement.bindArg()`

Requires `./gradlew apiDump`.

## Generated code impact

**Shape changes.** The processor emits different code:

- Constructor: `class FooRepository(private val driver: KilnDriver)` (was `SqlDriver`)
- Mapper lambdas: return `T` directly (was `QueryResult.Value(T)`)
- Schema: `KilnSchema.createAll(driver: KilnDriver)` (was `SqlDriver`)

Every consumer rebuilds on upgrade; no manual migration of generated code is
needed since KSP regenerates everything.

## Phased approach

This proposal covers **Phase 1 only**: define the `KilnDriver` interface and
ship three platform implementations. Phase 2 (replace SQLiter with Kiln's own
iOS cinterop) is a separate, future change — scoped out deliberately so the
driver interface is validated in production before the backing implementation
changes.

| Phase | Scope | iOS backing |
|---|---|---|
| **1 (this change)** | Own `KilnDriver` interface, three platform impls, remove SQLDelight | SQLiter (hidden `implementation` dep) |
| 2 (future) | Replace SQLiter with own cinterop | Own `sqlite3.h` cinterop |

## Dependency graph

### Before

```
Consumer classpath:
  io.github.sufarook.kiln:annotations
  io.github.sufarook.kiln:runtime
  app.cash.sqldelight:runtime            ← leaked via api()
  app.cash.sqldelight:android-driver     ← leaked via api()  (or native-/sqlite-driver)
  co.touchlab:sqliter                    ← transitive of native-driver
  org.xerial:sqlite-jdbc                 ← transitive of sqlite-driver
```

### After

```
Consumer classpath:
  io.github.sufarook.kiln:annotations
  io.github.sufarook.kiln:runtime
  (nothing else — platform drivers use framework APIs or implementation-scoped deps)

Inside kiln-runtime (not on consumer classpath):
  Android: android.database.sqlite (framework, no dep)
  JVM:     org.xerial:sqlite-jdbc (implementation)
  iOS:     co.touchlab:sqliter (implementation)
```

## Risks

1. **Breaking change in alpha.** Acceptable — the library is pre-1.0 and
   this is the right time for it. The migration is mechanical (rename one type).

2. **SQLiter as a hidden dependency on iOS.** Mitigated by Phase 2 (own
   cinterop). SQLiter is Apache-2.0, maintained, and used by thousands of
   SQLDelight iOS apps. It is a safe intermediate step.

3. **Subtle driver behavior differences.** Each platform's SQLite has minor
   behavioral differences (journal mode defaults, busy timeout, locking
   granularity). The spec below defines the contract Kiln guarantees regardless
   of platform, and the platform-drivers spec captures per-platform defaults.

4. **Consumer-smoke tests.** The integration test project will need its driver
   dependency removed and factory usage updated. This is a hard gate.
