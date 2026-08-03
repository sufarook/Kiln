# Contributing to Kiln

Thanks for considering a contribution. This doc covers the module layout, how to
build and test locally, and the sharp edges most likely to trip up a first PR.

## Project layout

Kiln is a multi-module Gradle build:

| Module | What it is | Consumed by users via |
|---|---|---|
| [`annotations`](annotations) | `@DbEntity`, `@PrimaryKey`, `@Column`, `@Ignore`, `@Relation` | Direct import (KMP, `SOURCE` retention) |
| [`processor`](processor) | The KSP processor — reads annotated classes, generates `<Entity>Repository`/`<Entity>Columns` | Applied via the Gradle plugin, never imported directly |
| [`runtime`](runtime) | `CrudRepository`, `SchemaMigrator`, the query DSL, driver factories (`AndroidDatabaseDriverFactory`, `IosDatabaseDriverFactory`, `JvmDatabaseDriverFactory`) | Direct import |
| [`gradle-plugin`](gradle-plugin) | `id("io.github.sufarook.kiln")` — wires KSP, the processor, and generated sources into a consumer's build | Applied via `plugins {}` |
| [`sample-android`](sample-android), [`sample-shared`](sample-shared) | Reference apps exercising the full CRUD/DSL/relation/transaction surface | N/A — dev-only, use local `project(":...")` references |
| [`integration-tests/consumer-smoke`](integration-tests/consumer-smoke) | A standalone build (**not** in the root `settings.gradle.kts`) that resolves Kiln purely through the published plugin, the way a real user does | N/A — CI-only gate before a release publishes |

`annotations` and `runtime` are the only modules whose Kotlin API a consumer
imports directly — they're the ones binary-compatibility validation (below)
actually protects.

## Building and testing

```bash
./gradlew build                          # full build, all modules
./gradlew :processor:test                # processor unit tests
./gradlew :runtime:jvmTest                # runtime tests on JVM
./gradlew :runtime:iosSimulatorArm64Test  # same runtime suite on a real iOS simulator (Apple Silicon Mac required)
./gradlew :gradle-plugin:test             # Gradle plugin wiring tests
./gradlew apiCheck                        # binary compatibility check (see below)
```

The `runtime` test suite (`SchemaMigratorTest`, `QueryDslTest`, `TransactionTest`)
lives in `commonTest` and runs identically on JVM, Android's local unit tests, and
iOS — a change to migration or DSL logic is verified on all three before it ships.

### Consumer smoke test

`integration-tests/consumer-smoke` proves the *published* plugin actually works,
not just that the processor compiles in isolation:

```bash
./gradlew publishToMavenLocal
./gradlew -p integration-tests/consumer-smoke test -PkilnVersion=<version-in-root-build.gradle.kts>
```

Run this before any change to `gradle-plugin` or a module's publishing
coordinates — it's the check that would have caught (and since has caught)
regressions where the plugin resolves the wrong version.

### Binary compatibility

`annotations` and `runtime` are checked against a committed API snapshot
(`annotations/api/`, `runtime/api/`) using
[kotlinx-binary-compatibility-validator](https://github.com/Kotlin/binary-compatibility-validator).
If you add, remove, or change a public signature in either module:

```bash
./gradlew apiDump   # regenerates the snapshot — commit the result
./gradlew apiCheck  # what CI runs — fails if the snapshot is stale
```

Deleting or narrowing a public API is a breaking change — call it out explicitly
in your PR description.

## Adding or changing a feature

Most feature work touches the same handful of files, in this order:

1. **`annotations/`** — add the annotation or parameter, if any
2. **`processor/EntityVisitor.kt`** — extract the new metadata from the KSP
   symbol into `EntityMetadata`/`ColumnMetadata`, with validation (`logger.error(...)`
   for anything that should be a compile-time error)
2. **`processor/SqlStatementBuilder.kt`** — if the change affects generated SQL
   (CREATE TABLE, INSERT, WHERE clauses, …)
3. **`processor/RepositoryGenerator.kt`** — the KotlinPoet codegen that emits the
   actual `<Entity>Repository`/`<Entity>Columns` source
4. **`runtime/`** — if the change needs new runtime support (e.g. `SchemaMigrator`
   needs to understand a new `ColumnDef` field)
5. Tests in `processor/src/test` (SQL-string level, fast) and `runtime/src/commonTest`
   (behavioral, runs on every target)

For anything nontrivial, verify the **generated code** compiles and runs, not just
that the unit tests pass — the processor's unit tests exercise `SqlStatementBuilder`
directly, which doesn't prove the KotlinPoet-generated Kotlin actually compiles.
The most reliable way to check that is the consumer-smoke path above: publish
locally, add a throwaway entity to `integration-tests/consumer-smoke`, run its
tests, then remove the throwaway entity before committing — that project's
permanent scope is a minimal generic smoke test, not a home for feature-specific
fixtures.

## Known sharp edges

- **KSP incremental processing** can produce stale state after `clean` combined
  with the Gradle build cache — `ksp.incremental=false` is set in
  `gradle.properties` for this reason. If you see a KSP-generated class behaving
  like it didn't pick up a processor change, try `./gradlew clean` first.
- **DSL operators are top-level functions.** `eq`, `and`, `gte`, etc. live in
  `io.github.sufarook.kiln.runtime` — code outside that package needs an explicit
  import (`import io.github.sufarook.kiln.runtime.eq` or `.*`). This is a common
  first-PR "unresolved reference" surprise, not a bug.
- **Composite primary keys can't use an inline `PRIMARY KEY` per column.**
  SQLite rejects two column-level `PRIMARY KEY` constraints outright
  (`table has more than one primary key`). Both `SqlStatementBuilder.createTable`
  and `SchemaMigrator.buildSchemaSql` (used when the migrator rebuilds a table)
  need a single table-level `PRIMARY KEY (col1, col2)` constraint instead — if
  you touch either of those, check both.
- **iOS test binaries need `-lsqlite3` linked explicitly** (see `runtime/build.gradle.kts`).
  A real iOS app gets this for free via Xcode; our own Kotlin/Native test
  executable doesn't, and `native-driver`'s sqlite3 symbols fail to link without it.
- **Samples must stay pinned to the last *published* plugin version**, not the
  in-development one. Bumping `sample-android`/`sample-shared` to a version
  that hasn't been published yet breaks CI at Gradle's configuration phase
  (before any task even runs) for every job, since there's no `mavenLocal()` in
  the root `pluginManagement` block. Bump the samples only after a release
  actually lands on Maven Central.

## Commit and PR conventions

- Prefix commits with a type: `feat:`, `fix:`, `test:`, `docs:`, `ci:`, `chore:`
- One logical change per PR — a feature and an unrelated refactor should be
  separate PRs
- Fill in the PR template's test plan; "ran the tests" is less useful than
  naming which ones and what they'd have caught
- CI must pass before merge (`processor-tests`, `runtime-tests`,
  `runtime-ios-tests`, `gradle-plugin-tests`, `consumer-smoke`, `api-check`,
  `android-integration`)

## Reporting bugs / requesting features

Use the issue templates — they ask for the version, minimal repro, and expected
vs. actual behavior, which is almost always what's needed to act on a report
quickly.
