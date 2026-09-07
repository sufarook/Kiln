## 1. Verify the approach

- [ ] 1.1 Spike `SuspendingTransacterImpl` over the synchronous drivers Kiln ships
  against. Write a throwaway `runtime` JVM test that nests a Kiln transaction
  inside another, and nests one inside a transaction opened directly on the
  driver. Verify both complete and commit once. If the
  `Transacter.kt:268` cast to `SuspendingTransacter` throws, **stop** — the
  approach in `design.md` is unsound and must be revised before any further task.

## 2. Transaction authority

- [ ] 2.1 Replace the raw `BEGIN`/`COMMIT`/`ROLLBACK` in `withTransaction` with a
  `SuspendingTransacterImpl` subclass. Verify with a JVM test that
  `withTransaction { withTransaction { … } }` commits once and completes without
  error — it throws today, so the test fails before the change and passes after.
- [ ] 2.2 Move `notifyOrDefer` onto the active transaction's `afterCommit`
  callback. Verify with tests for each scenario in
  `specs/transactions/spec.md` — "Reactive queries re-emit only after a
  successful commit": one notification per table per committed transaction, none
  on rollback, deferral inside a transaction opened directly on the driver, and
  immediate notification with no transaction open.
- [ ] 2.3 Delete `KilnTransactionContext` and its remaining references. Verify
  `./gradlew :runtime:compileKotlinJvm` succeeds and `apiCheck` fails reporting
  exactly this removal and nothing else. **Confirm the remove-vs-deprecate call
  with the maintainer before starting** — see `design.md` — Decisions.

## 3. Migration safety

- [ ] 3.1 Route the table rebuild through the same transacter rather than raw
  `BEGIN`/`COMMIT`. Verify with a test that forces a failure partway through a
  rebuild and asserts the table keeps its original rows and structure
  (`specs/schema-migration/spec.md` — "Rebuild fails partway").
- [ ] 3.2 Save, disable, and restore `foreign_keys` around the rebuild, with the
  pragma statements outside the transaction and the restore in a `finally`.
  Verify with a test that enables enforcement, defines a second table
  referencing the Kiln table with `ON DELETE CASCADE`, triggers a rebuild via a
  property rename, and asserts every referencing row survives.
- [ ] 3.3 Verify enforcement is restored to its prior value in both directions —
  enabled before / enabled after, disabled before / disabled after — including
  when the rebuild throws.
- [ ] 3.4 Refuse to reconcile when a transaction is already open on the
  connection, failing with a message naming the cause. Verify with a test that
  opens a transaction, invokes table setup, and asserts both the error and that
  no schema statement ran.
- [ ] 3.5 Check `sqlite_master.type` before reconciling and reject a name that
  resolves to anything other than a table. Verify with a test that creates a view
  named after an entity and asserts a clear error and an unchanged view.

## 4. Integration verification

- [ ] 4.1 Add a `consumer-smoke` test covering transaction and notification
  behavior through generated repositories, so the new timing is proven through
  the real KSP pipeline rather than only in `runtime` unit tests. Verify with
  `./gradlew publishToMavenLocal` then
  `./gradlew -p integration-tests/consumer-smoke test --rerun-tasks`.
- [ ] 4.2 Run the full gate:
  `./gradlew :processor:test :runtime:jvmTest :gradle-plugin:test ktlintCheck apiCheck`.
  Verify it passes with no failures other than the intended `apiCheck` diff from
  task 2.3.
- [ ] 4.3 Run `:runtime:iosSimulatorArm64Test` and verify the migration and
  transaction tests pass on Kotlin/Native, not only the JVM — the pragma and
  transaction behavior is driver-specific.

## 5. API surface and docs

- [ ] 5.1 Run `./gradlew apiDump` and verify the resulting diff in
  `runtime/api/*.api` contains only the intended `KilnTransactionContext` removal.
- [ ] 5.2 Update `docs/src/faq.md` — the "Can Kiln work alongside raw SQL or
  Room?" answer predates this fix and should state that Kiln now preserves rows in
  referencing tables and restores the connection's foreign-key setting. Verify by
  reading the rendered answer for accuracy against
  `specs/schema-migration/spec.md`.
- [ ] 5.3 Document the notification-timing change where transactions are
  described in `docs/src/`. Verify the docs build passes with
  `mkdocs build --config-file docs/mkdocs.yml --strict`.
