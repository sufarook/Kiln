# Working on Kiln

Kiln generates type-safe SQLite CRUD repositories at compile time via KSP.
Annotate a data class, rebuild, get a full repository — no SQL, no mappers, no
versioned migration files.

**Read these first, rather than duplicating them here:**

| For | Read |
|---|---|
| Module layout, build/test commands, sharp edges, PR conventions | [CONTRIBUTING.md](CONTRIBUTING.md) |
| Project context and constraints, in the form planning tools consume | [openspec/config.yaml](openspec/config.yaml) |
| What the library does, from a consumer's side | [README.md](README.md), [docs/src/](docs/src) |
| Active and deferred work | [ROADMAP.md](ROADMAP.md) |

## Before claiming a change is done

```bash
./gradlew :processor:test :runtime:jvmTest :gradle-plugin:test ktlintCheck apiCheck
```

Anything touching generated output also needs the consumer-smoke path — it runs
the real KSP pipeline against locally published artifacts, which unit tests do
not:

```bash
./gradlew publishToMavenLocal
./gradlew -p integration-tests/consumer-smoke test --rerun-tasks
```

Migration and transaction changes need `:runtime:iosSimulatorArm64Test` too.
That code is driver-specific and a JVM-only pass proves less than it looks.

## Non-negotiables

- **Nothing lands on `main` by direct push.** Branch protection has
  `enforce_admins=true` and 7 required checks. Every change goes through a PR the
  maintainer merges — including trivial ones, including admin.
- **A public API change in `annotations` or `runtime` needs a deliberate
  `./gradlew apiDump`.** Binary compatibility is validated, iOS KLib ABI
  included. State additive vs breaking explicitly in the PR.
- **The version lives in one place:** `allprojects { version = … }` in the root
  `build.gradle.kts`. The Gradle plugin's embedded `VERSION` is generated from
  it, so it cannot drift.
- **Never commit `.claude/settings.local.json`** — gitignored, per-developer.

## Invariants that are easy to get wrong

These have each produced an incorrect doc or assumption before:

- **Kiln emits no `FOREIGN KEY` constraints.** `@Relation` generates typed helper
  methods (`findByParent`, `deleteByParent`) and nothing else. SQLite has no idea
  the tables relate. Never write docs implying referential integrity is enforced,
  or that `createTable()` call order matters — it does not.
- **Migration is version-less.** `SchemaMigrator` diffs the live schema via
  `PRAGMA table_info` against the entity. There are no migration files and no
  version numbers to bump, ever. Do not introduce one.
- **`KilnSchema.createAll(driver)`** covers every `@DbEntity` in a module. Prefer
  it over per-repository `createTable()` in examples with more than one entity.
- **Samples live in a separate repo**
  ([sufarook/kiln-samples](https://github.com/sufarook/kiln-samples)) and consume
  Kiln from Maven Central, so they can only demonstrate *published* features.
  Bump their pinned version after a release lands, not before.

## Planning

Non-trivial work is planned with [OpenSpec](https://openspec.dev/) before it is
implemented — `/opsx:propose`, then `/opsx:apply`. Artifacts live in
`openspec/changes/<name>/` and are reviewed as a PR before any code is written.
`openspec list` shows what is active.

Requires the CLI: `npm install -g @fission-ai/openspec@latest`.

## Releasing

Bump the root `build.gradle.kts` version and every doc-embedded coordinate, PR
it, then tag `v<version>` — the tag push triggers the publish job. Draft the
GitHub release from the previous one's shape. Artifacts take up to a few hours to
appear on `repo1.maven.org` after CI reports success; that delay is normal and
not a failure.
