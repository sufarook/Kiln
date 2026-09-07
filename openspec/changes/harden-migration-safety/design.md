## Context

See `proposal.md` — Why. Constraints that shape the approach:

- Kiln borrows a `SqlDriver` it does not own. Other code may share it, may hold a
  transaction open on it, and may have enabled foreign-key enforcement.
- `PRAGMA foreign_keys` is a no-op while a transaction is open. Any change to it
  must therefore bracket a transaction rather than sit inside one.
- SQLite permits one transaction per connection. Two independent transaction
  bookkeepers on one connection cannot both be correct.
- The rebuild path runs unattended at app launch against user data. Its failure
  mode is silent data loss, not a crash.

## Goals / Non-Goals

**Goals:**
- One transaction authority per connection, wherever the transaction originated
- A rebuild that cannot delete or alter rows outside the table being rebuilt
- The connection left exactly as Kiln found it

**Non-Goals:**
- Emitting or enforcing foreign-key constraints. Kiln still declares none; this
  change only stops Kiln from being *harmed* by enforcement other code enabled.
- Supporting reconciliation while a transaction is open. It is refused, not made
  to work.
- Any coexistence feature — shared-driver entry points, integration docs, or
  table-ownership policy. Deferred; see `proposal.md` — Impact.

## Decisions

### Delegate transactions to SQLDelight's transacter

Kiln subclasses `SuspendingTransacterImpl(driver)` and runs blocks through its
`transaction { }`. Nesting resolves through `driver.currentTransaction()`, which
is connection-level state — so a Kiln block nested inside any other transaction
on that connection is recognized as nested and issues no second `BEGIN`.

*Alternatives considered.* Keeping raw SQL and tracking depth in Kiln was
rejected: a second bookkeeper is the defect, not the cure. Driving
`driver.newTransaction()` directly was rejected on inspection — `Transaction.successful`
and `endTransaction()` are `internal` to SQLDelight and unreachable from Kiln.
`SuspendingTransacterImpl` is public, abstract, and takes the driver, which makes
it the intended extension point. It also matches Kiln's suspending API shape.

### Defer notifications through the transaction, not the coroutine context

Notification registers via the active transaction's `afterCommit` callback.

The current coroutine-context element can only observe transactions Kiln itself
opened. A write inside a transaction opened by other code is therefore notified
immediately — mid-transaction, and even if that transaction later rolls back.
`afterCommit` fires after the real outermost commit regardless of owner, which is
the behavior `specs/transactions/spec.md` requires.

### Remove `KilnTransactionContext` — BREAKING

The class becomes dead code once notification moves to `afterCommit`. It is
public and present in `runtime/api/runtime.klib.api`, so removal breaks binary
compatibility and requires a deliberate `apiDump`.

*Alternative considered:* deprecate and retain. Rejected — a retained element
that no longer influences behavior is worse than an absent one, because code
constructing it would silently stop working while still compiling. At alpha,
before any stability promise, removal is the honest option.

**This is the one decision here that is the maintainer's to make, not the
implementer's.** If the preference is to retain it, only the removal task and the
`apiDump` change; nothing else in this design depends on the outcome.

### Bracket the pragma outside the transaction

```
read    foreign_keys -> save
set     foreign_keys = OFF        (must precede BEGIN)
        +-- transaction: temp table, copy, drop, rename
restore foreign_keys = saved      (must follow COMMIT)
```

Restore belongs in a `finally` so an exception inside the rebuild cannot leave
enforcement disabled on a connection Kiln does not own.

### Refuse to reconcile inside an open transaction

With a transaction already open, Kiln can neither toggle the pragma nor own the
commit boundary — the two properties the rebuild's safety rests on. Detected via
`driver.currentTransaction()`, failing with a message that names the cause.
Proceeding unsafely, or silently skipping foreign-key protection, were both
rejected: they reintroduce the original defect under a narrower condition, which
is harder to find.

### Check `sqlite_master.type` before reconciling

The existing `if (existing.isEmpty()) return` guard reads as "table absent" but
actually means "no columns reported" — and `PRAGMA table_info` reports columns
for views too. Querying `sqlite_master.type` separates "absent" from "present but
not a table" and lets each produce its own outcome.

### Why this lives in the runtime, not the processor

Project convention prefers compile-time solutions. It does not apply here: every
condition being guarded — enforcement state, open transactions, whether a name
resolves to a view — is a property of the live database at launch, unknowable
when the processor runs. No codegen output changes.

## Risks / Trade-offs

- **`SuspendingTransacterImpl` may not nest correctly over the synchronous
  drivers Kiln ships against** (`AndroidSqliteDriver`, `NativeSqliteDriver`,
  `JdbcSqliteDriver`). `Transacter.kt:268` casts an enclosing transaction's
  transacter to `SuspendingTransacter`. → Verified first, as task 1; the design
  is unsound if this fails, so it gates everything else.
- **Behavior change for existing callers.** A write inside a transaction opened
  by other code now defers its notification instead of firing immediately. That
  is the fix, but any consumer depending on the old timing sees a change. → Note
  it in the release notes; no API signature changes.
- **A throwing pragma restore could mask the original exception.** → Restore
  swallows its own failure and re-raises the original, matching the existing
  rollback convention in `withTransaction`.
- **Highest-consequence code in the library.** → Every requirement in
  `specs/schema-migration/spec.md` gets a test against real SQLite, including the
  cascade scenario, which must be asserted by enabling enforcement and confirming
  referencing rows survive.

## Migration Plan

Ships in one release. `./gradlew apiDump` is required regardless of the
`KilnTransactionContext` outcome. Release notes call out the breaking removal and
the notification-timing change. No consumer action is needed unless code
references `KilnTransactionContext` directly, which has no purpose outside Kiln's
own internals.

## Open Questions

- Whether to run `PRAGMA foreign_key_check` after restoring enforcement. It would
  surface a rebuild that broke a reference, but can also report pre-existing
  violations Kiln did not cause — so it would have to warn rather than throw. No
  requirement depends on it; decidable during implementation.
