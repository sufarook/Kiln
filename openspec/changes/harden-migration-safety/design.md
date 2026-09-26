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
- SQLDelight binds a transaction to the thread that opened it, and Kiln's
  transactions are coroutines that may resume on any thread of their dispatcher.
  See Spike results.

## Spike results (task 1.1)

Run on SQLDelight 2.3.2 with `JdbcSqliteDriver`, against a
`SuspendingTransacterImpl` subclass.

- **Nesting works.** A Kiln block nested inside another commits once, at the
  outermost boundary. A Kiln block nested inside a transaction opened through a
  blocking `TransacterImpl` joins it, commits once, and is discarded when that
  transaction rolls back. The cast to `SuspendingTransacter` is only reached
  through the transaction wrapper's own nested `transaction { }`; Kiln calls its
  transacter directly and never reaches it.
- **Thread confinement breaks it.** SQLDelight records the thread a
  `Transaction` was opened on and refuses to end it from any other
  (`checkThreadConfinement`). A block that suspends on `Dispatchers.Default` can
  resume on a different thread. Of 30 runs of a block that suspended, 24 failed,
  and in all 24 the refusal came before COMMIT or ROLLBACK: the transaction stayed
  open on the connection, and every later `BEGIN` on it failed.

The raw-SQL implementation being replaced has the same weakness — on
thread-bound drivers (Android, Native, file-backed JDBC) a thread switch
mid-transaction hangs or writes outside the transaction — but fails silently.
Adopting the transacter without addressing it would turn that into a permanently
stuck connection. Hence the two thread-pinning decisions below.

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

The schema rebuild uses the blocking `TransacterImpl` instead. `createTable()` is
not suspending, and reconciliation refuses to run inside an open transaction, so
the rebuild is always outermost and never nests with a suspending block.

### Pin each transaction to one thread

The outermost `withTransaction` runs its block on a single thread for the
transaction's whole lifetime: it switches to `context`, then runs the block in a
`runBlocking` event loop on the thread it landed on. Every continuation inside
the block — after a `delay`, a network call, or a `withContext` that returns —
resumes on that thread, so the transaction is opened, used, and ended by the
thread that owns it. This is Room's model: a transaction holds one thread, and
DAO calls inside it are routed there.

An internal coroutine-context element carries the owning driver and that
thread's dispatcher. A nested `withTransaction` on the same driver finds it and
joins the running transaction on that dispatcher instead of pinning again.

*Alternatives considered.*
- *Document "don't switch threads" and fail on violation.* Rejected: SQLDelight can
  only end a transaction from its owning thread, so a detected violation still
  leaves the connection stuck — the spike's failure mode, with a better message.
- *A new thread per transaction* (`newSingleThreadContext`). Rejected: every
  `insertAll` would create and destroy a thread.
- *One dedicated thread per driver.* Rejected: Kiln borrows the driver and has no
  hook into its lifecycle, so it could never shut the thread down.

### Route generated repository calls through the transaction's thread

Pinning the block is not enough on its own. Every generated repository method
wraps its work in `withContext(context)`, and a repository whose `context` differs
from the transaction's would dispatch off the pinned thread. Generated methods
therefore call a runtime helper in place of `withContext`. It resolves, in order:

1. A Kiln transaction is active on this driver → run on its pinned dispatcher.
2. A transaction opened by other code is open on the current thread → run in
   place, without dispatching, so the write joins that transaction.
3. Otherwise → `withContext(context)`, as today.

The helper is public, because generated code in the consumer's module calls it,
and documented as not intended for direct use — the status `notifyOrDefer` already
has. The context element it reads stays internal.

Case 2 cannot rescue code that itself moves a Kiln call to another dispatcher
while inside a transaction opened by other code: Kiln has no dispatcher for that
transaction's thread to return to. That case is documented as unsupported.

### Defer notifications through the transacter's pending-table set

`notifyOrDefer` hands the table to SQLDelight's own deferral: Kiln's transacter
subclass exposes `BaseTransacterImpl.notifyQueries`, which is protected API. With
a transaction open on the connection, the table joins that transaction's pending
set, which merges upward through nesting and is notified once, in a single
`notifyListeners` call, after the outermost commit — whoever opened it. On
rollback it is discarded. With no transaction open it notifies immediately.

The current coroutine-context element can only observe transactions Kiln itself
opened. A write inside a transaction opened by other code is therefore notified
immediately — mid-transaction, and even if that transaction later rolls back. The
pending set belongs to the connection's transaction, so it defers regardless of
owner, which is the behavior `specs/transactions/spec.md` requires.

*Alternative considered:* an `afterCommit` hook per call, as originally planned.
Rejected: hooks registered at different nesting levels merge into the enclosing
transaction separately, so a table written at two levels would be notified
twice — contrary to "exactly once". Deduplicating them would need SQLDelight
internals. `notifyQueries` deduplicates by an integer identifier, so Kiln passes a
stable per-table identifier from its own registry rather than `hashCode()`, which
could collide between two Kiln tables and silently drop one.

### Remove `KilnTransactionContext` — BREAKING

The class becomes dead code once deferral moves into the transacter. The
thread-pinning element does not change that: it is internal and carries
different data. `KilnTransactionContext` is public and present in
`runtime/api/runtime.klib.api`, so removal breaks binary compatibility and
requires a deliberate `apiDump`.

*Alternative considered:* deprecate and retain. Rejected — a retained element
that no longer influences behavior is worse than an absent one, because code
constructing it would silently stop working while still compiling. At alpha,
before any stability promise, removal is the honest option.

**Decided by the maintainer: remove.** This was the one decision here that was the
maintainer's to make, not the implementer's; nothing else in the design depended
on it.

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

Generated `createTable()` currently issues `CREATE TABLE IF NOT EXISTS` before it
calls the migrator, so a refusal inside `sync` would arrive after a schema
statement had already run. Generated `createTable()` now calls `sync` first.
`sync` on an absent table is a no-op, so the resulting schema is identical, and
every refusal precedes every schema statement.

### Check `sqlite_master.type` before reconciling

The existing `if (existing.isEmpty()) return` guard reads as "table absent" but
actually means "no columns reported" — and `PRAGMA table_info` reports columns
for views too. Querying `sqlite_master.type` separates "absent" from "present but
not a table" and lets each produce its own outcome.

### Why this lives in the runtime, not the processor

Project convention prefers compile-time solutions. It does not apply here: every
condition being guarded — enforcement state, open transactions, whether a name
resolves to a view — is a property of the live database at launch, unknowable
when the processor runs. The processor changes only to route calls: generated
methods call the dispatcher helper, and `createTable()` calls the migrator before
creating the table.

## Risks / Trade-offs

- **A transaction holds a thread for its whole duration.** On
  `Dispatchers.Default`, the default `context`, a transaction that waits on slow
  I/O holds one thread of a small pool. → Documented: pass `Dispatchers.IO` as
  `context` for transactions that wait. Room makes the same trade with its
  transaction executor.
- **`runBlocking` inside a coroutine.** It deadlocks only if the block waits for
  work that something else must dispatch onto the pinned thread; nothing but the
  block and the helper's case 1 dispatches there. → The pinned-thread tests
  include a block that switches dispatcher and returns, repeated.
- **Generated output changes.** Consumers see a codegen difference on rebuild,
  though no source change. The Gradle plugin applies the processor and runtime at
  one version, so a mismatched pair is not a supported configuration. → Called out
  in the release notes.
- **Identifier collision in a shared transaction.** SQLDelight-generated queries
  sharing a transaction register their own integer identifiers in the same set;
  one equal to a Kiln table's identifier would drop that table's pending
  notification. SQLDelight's identifiers are spread across the full integer
  range, so this is improbable rather than impossible. → Noted; revisit if Kiln
  ever supports sharing a driver with SQLDelight-generated code.
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
`KilnTransactionContext` outcome, because the dispatcher helper is an additive
public function. Release notes call out the breaking removal, the
notification-timing change, the thread a transaction now holds, and that
consumers must rebuild to pick up the new generated code. No consumer action is needed unless code
references `KilnTransactionContext` directly, which has no purpose outside Kiln's
own internals.

## Open Questions

- Whether to run `PRAGMA foreign_key_check` after restoring enforcement. It would
  surface a rebuild that broke a reference, but can also report pre-existing
  violations Kiln did not cause — so it would have to warn rather than throw. No
  requirement depends on it; decidable during implementation.
