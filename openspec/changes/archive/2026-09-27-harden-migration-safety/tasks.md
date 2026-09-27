## 1. Verify the approach

- [x] 1.1 Spike `SuspendingTransacterImpl` over the synchronous drivers Kiln ships
  against. Write a throwaway `runtime` JVM test that nests a Kiln transaction
  inside another, and nests one inside a transaction opened directly on the
  driver. Verify both complete and commit once. If the
  `Transacter.kt:268` cast to `SuspendingTransacter` throws, **stop** â€” the
  approach in `design.md` is unsound and must be revised before any further task.
  Result: nesting verified and the cast never reached; the spike also found the
  thread-confinement failure recorded in `design.md` â€” Spike results, addressed by
  tasks 2.2 and 2.3.

## 2. Transaction authority

- [x] 2.1 Replace the raw `BEGIN`/`COMMIT`/`ROLLBACK` in `withTransaction` with a
  `SuspendingTransacterImpl` subclass. Verify with a JVM test that
  `withTransaction { withTransaction { â€¦ } }` commits once and completes without
  error â€” it throws today, so the test fails before the change and passes after.
  Also verify both scenarios of "A failed transaction rolls back and surfaces its
  cause", using a driver wrapper whose rollback throws for the second.
- [x] 2.2 Pin the outermost transaction to one thread and publish it through an
  internal context element (`design.md` â€” Pin each transaction to one thread).
  Verify by making the spike's thread-hop probe a permanent test: a block that
  switches dispatcher and returns, run 30 times, commits every time and leaves no
  transaction open on the connection. Then delete `TransacterSpikeTest.kt`.
- [x] 2.3 Add the runtime dispatcher helper and switch generated repository
  methods from `withContext(context)` to it (`design.md` â€” Route generated
  repository calls through the transaction's thread). Verify with a runtime test
  that a write through the helper, from a caller on a different dispatcher,
  commits and rolls back with the enclosing transaction â€” both one Kiln opened
  and one opened through a blocking `TransacterImpl`. Update the processor tests
  for the new generated output.
- [x] 2.4 Move `notifyOrDefer` onto the transacter's pending-table set, with a
  stable per-table identifier. Verify with tests for each scenario in
  `specs/transactions/spec.md` â€” "Reactive queries re-emit only after a
  successful commit": one notification per table per committed transaction
  (including a table written at two nesting levels), none on rollback, deferral
  inside a transaction opened through a blocking `TransacterImpl`, and immediate
  notification with no transaction open.
- [x] 2.5 Delete `KilnTransactionContext` and its remaining references. Verify
  `./gradlew :runtime:compileKotlinJvm` succeeds and `apiCheck` fails reporting
  this removal and the helper added in 2.3, nothing else. **Confirm the
  remove-vs-deprecate call with the maintainer before starting** â€” see
  `design.md` â€” Decisions.

## 3. Migration safety

- [x] 3.1 Route the table rebuild through a blocking `TransacterImpl` subclass
  rather than raw `BEGIN`/`COMMIT` â€” `createTable()` is not suspending. Verify
  with a test that forces a failure partway through a rebuild and asserts the
  table keeps its original rows and structure (`specs/schema-migration/spec.md` â€”
  "Rebuild fails partway").
- [x] 3.2 Save, disable, and restore `foreign_keys` around the rebuild, with the
  pragma statements outside the transaction and the restore in a `finally`.
  Verify with a test that enables enforcement, defines a second table
  referencing the Kiln table with `ON DELETE CASCADE`, triggers a rebuild via a
  property rename, and asserts every referencing row survives.
- [x] 3.3 Verify enforcement is restored to its prior value in both directions â€”
  enabled before / enabled after, disabled before / disabled after â€” including
  when the rebuild throws.
- [x] 3.4 Refuse to reconcile when a transaction is already open on the
  connection, failing with a message naming the cause, and make generated
  `createTable()` call the migrator before `CREATE TABLE IF NOT EXISTS`. Verify
  with a `runtime` test that opens a transaction, invokes `sync`, and asserts both
  the error and that no schema statement ran; and in the processor tests that
  generated `createTable()` calls `sync` before its `CREATE TABLE`.
- [x] 3.5 Check `sqlite_master.type` before reconciling and reject a name that
  resolves to anything other than a table. Verify with a test that creates a view
  named after an entity and asserts a clear error and an unchanged view.

## 4. Integration verification

- [x] 4.1 Add a `consumer-smoke` test covering transaction and notification
  behavior through generated repositories â€” including a repository constructed
  with a dispatcher different from the transaction's â€” so the new timing and
  routing are proven through the real KSP pipeline rather than only in `runtime`
  unit tests. Verify with `./gradlew publishToMavenLocal` then
  `./gradlew -p integration-tests/consumer-smoke test --rerun-tasks`.
- [x] 4.2 Run the full gate:
  `./gradlew :processor:test :runtime:jvmTest :gradle-plugin:test ktlintCheck apiCheck`.
  Verify it passes with no failures other than the intended `apiCheck` diff from
  tasks 2.3 and 2.5.
- [ ] 4.3 Run `:runtime:iosSimulatorArm64Test` and verify the migration and
  transaction tests pass on Kotlin/Native, not only the JVM â€” the pragma and
  transaction behavior is driver-specific.

## 5. API surface and docs

- [x] 5.1 Run `./gradlew apiDump` and verify the resulting diff in
  `runtime/api/*.api` contains only the dispatcher helper added in 2.3 and the
  `KilnTransactionContext` removal from 2.5.
- [x] 5.2 Update `docs/src/faq.md` â€” the "Can Kiln work alongside raw SQL or
  Room?" answer predates this fix and should state that Kiln now preserves rows in
  referencing tables and restores the connection's foreign-key setting. Verify by
  reading the rendered answer for accuracy against
  `specs/schema-migration/spec.md`.
- [x] 5.3 Document the notification-timing change where transactions are
  described in `docs/src/`, along with the threading model: a transaction holds
  one thread for its duration (pass `Dispatchers.IO` as `context` when it waits on
  I/O), and moving a Kiln call to another dispatcher inside a transaction opened
  by other code is unsupported. Verify the docs build passes with
  `mkdocs build --config-file docs/mkdocs.yml --strict`.
