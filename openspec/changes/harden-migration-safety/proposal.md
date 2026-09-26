## Why

`SchemaMigrator`'s table-rebuild path issues `DROP TABLE` without disabling
foreign-key enforcement first, and `withTransaction` opens transactions with raw
`BEGIN TRANSACTION` SQL rather than the driver's transaction API. Both are safe
only under an assumption Kiln never states: that nothing else on the connection
enables foreign keys or manages transactions.

That assumption already fails on a path the docs advertise. `docs/src/faq.md`
answers "Can Kiln work alongside raw SQL or Room?" with "Yes" — and Room enables
`PRAGMA foreign_keys = ON`. In that configuration, renaming a single property on
a `@DbEntity` triggers the slow-path rebuild, whose `DROP TABLE` performs an
implicit delete of every row and fires `ON DELETE CASCADE` on any table
referencing it. A property rename silently destroys rows in tables Kiln does not
own.

## What Changes

- **Foreign-key-safe table rebuilds.** `SchemaMigrator` reads the current
  `foreign_keys` pragma, disables it for the duration of a rebuild, and restores
  the prior value afterwards — the procedure SQLite documents for this operation.
  The pragma is a no-op inside a transaction, so it must bracket the transaction
  rather than sit within it.
- **One transaction authority.** `withTransaction` delegates to SQLDelight's
  `SuspendingTransacterImpl` instead of emitting raw `BEGIN`/`COMMIT`/`ROLLBACK`.
  Transaction nesting becomes correct — today `withTransaction { withTransaction { } }`
  fails, because a raw `BEGIN` inside an open transaction is an error.
- **Each transaction stays on one thread.** SQLDelight can only end a transaction
  on the thread that opened it, and a coroutine that suspends may resume
  elsewhere. A spike showed that this leaves the transaction open on the
  connection for good. The outermost `withTransaction` therefore pins its block
  to one thread for the transaction's lifetime.
- **Generated repositories run on the transaction's thread.** While a transaction
  is active, generated repository methods run on its thread instead of their own
  dispatcher, so their writes are part of it on every driver.
- **Commit-accurate notifications.** `notifyOrDefer` defers through SQLDelight's
  per-transaction pending-table set rather than inspecting a Kiln-specific
  coroutine-context element. Notifications then fire once per table after the
  real outermost commit, including when the enclosing transaction was not opened
  by Kiln.
- **Migration refuses to run inside an open transaction.** With a transaction
  already open, Kiln can neither toggle the pragma nor control the commit
  boundary, so `createTable()` fails with a clear message instead of proceeding
  unsafely.
- **Entities must map to tables, not views.** `PRAGMA table_info` returns columns
  for views as well, so the current `existing.isEmpty()` guard does not
  distinguish them. `sqlite_master.type` is checked before migrating.
- **BREAKING: `KilnTransactionContext` is removed.** Moving deferral into the
  transacter makes this class dead code. It is public and present in
  `runtime/api/runtime.klib.api`, so deleting it breaks binary compatibility. The
  maintainer chose removal over deprecation; see `design.md` — Decisions.

## Capabilities

### New Capabilities

- `schema-migration`: How Kiln reconciles a live SQLite table against its
  `@DbEntity` definition — when each migration path is taken, and the conditions
  a rebuild must preserve for data outside Kiln's ownership.
- `transactions`: Transaction boundaries, reactive-notification timing, and the
  thread a transaction's work runs on, including behavior when Kiln's
  transactions nest inside each other or inside a transaction opened by other
  code sharing the driver.

### Modified Capabilities

None. `openspec/specs/` is currently empty; this change introduces the first two
capabilities.

## Impact

**Code**
- `runtime/src/commonMain/kotlin/io/github/sufarook/kiln/runtime/SchemaMigrator.kt`
  — pragma handling, transaction acquisition, `sqlite_master` type check
- `runtime/src/commonMain/kotlin/io/github/sufarook/kiln/runtime/Transaction.kt`
  — `withTransaction`, thread pinning, the dispatcher helper, `notifyOrDefer`,
  fate of `KilnTransactionContext`
- `processor/src/main/kotlin/io/github/sufarook/kiln/processor/RepositoryGenerator.kt`
  — generated methods call the dispatcher helper; `createTable()` calls the
  migrator before creating the table

**Public API** — Breaking: `KilnTransactionContext` is removed. One additive
function: the dispatcher helper generated repositories call, documented as not
intended for direct use. `withTransaction` and `notifyOrDefer` keep their
signatures. `runtime/api/*.api` needs a deliberate `apiDump`.

**Generated code** — Changes. Repository methods call the dispatcher helper in
place of `withContext(context)`, and `createTable()` runs the migrator before
`CREATE TABLE IF NOT EXISTS`. Consumers see a codegen difference on rebuild but
make no source change. The Gradle plugin applies the processor and runtime at the
same version.

**Dependencies** — None added. `SuspendingTransacterImpl`, `TransacterImpl`, and
the protected `BaseTransacterImpl.notifyQueries` are public API in the
`app.cash.sqldelight:runtime` artifact Kiln already depends on; `runBlocking`
comes from `kotlinx-coroutines-core`, also already a dependency.

**Risk** — The rebuild path is the highest-consequence code in the library; it
runs unattended at app launch against user data. Every change here needs a test
that asserts against real SQLite, not a mock.
