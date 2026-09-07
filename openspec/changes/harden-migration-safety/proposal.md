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
- **Commit-accurate notifications.** `notifyOrDefer` registers via the
  transaction's `afterCommit` callback rather than inspecting a Kiln-specific
  coroutine-context element. Notifications then fire after the real outermost
  commit, including when the enclosing transaction was not opened by Kiln.
- **Migration refuses to run inside an open transaction.** With a transaction
  already open, Kiln can neither toggle the pragma nor control the commit
  boundary, so `createTable()` fails with a clear message instead of proceeding
  unsafely.
- **Entities must map to tables, not views.** `PRAGMA table_info` returns columns
  for views as well, so the current `existing.isEmpty()` guard does not
  distinguish them. `sqlite_master.type` is checked before migrating.
- **BREAKING (decision pending): `KilnTransactionContext`.** The `afterCommit`
  approach makes this class dead code. It is public and present in
  `runtime/api/runtime.klib.api`, so deleting it breaks binary compatibility.
  Removing it versus deprecating it is recorded as an open question in
  `design.md` and must be settled before implementation.

## Capabilities

### New Capabilities

- `schema-migration`: How Kiln reconciles a live SQLite table against its
  `@DbEntity` definition — when each migration path is taken, and the conditions
  a rebuild must preserve for data outside Kiln's ownership.
- `transactions`: Transaction boundaries and reactive-notification timing,
  including behavior when Kiln's transactions nest inside each other or inside a
  transaction opened by other code sharing the driver.

### Modified Capabilities

None. `openspec/specs/` is currently empty; this change introduces the first two
capabilities.

## Impact

**Code**
- `runtime/src/commonMain/kotlin/io/github/sufarook/kiln/runtime/SchemaMigrator.kt`
  — pragma handling, transaction acquisition, `sqlite_master` type check
- `runtime/src/commonMain/kotlin/io/github/sufarook/kiln/runtime/Transaction.kt`
  — `withTransaction`, `notifyOrDefer`, fate of `KilnTransactionContext`

**Public API** — Breaking only if `KilnTransactionContext` is removed; every
other change is behavioral. `withTransaction` and `notifyOrDefer` keep their
signatures. `runtime/api/*.api` needs a deliberate `apiDump` either way.

**Generated code** — Output shape is unchanged. Generated repositories call
`notifyOrDefer(tableName)`, whose signature is untouched, so no consumer sees a
codegen difference on rebuild.

**Dependencies** — None added. `SuspendingTransacterImpl` and
`TransactionCallbacks.afterCommit` are public API in the
`app.cash.sqldelight:runtime` artifact Kiln already depends on.

**Risk** — The rebuild path is the highest-consequence code in the library; it
runs unattended at app launch against user data. Every change here needs a test
that asserts against real SQLite, not a mock.
