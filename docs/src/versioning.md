# Versioning & Stability

Kiln follows [Semantic Versioning](https://semver.org/) (`MAJOR.MINOR.PATCH`). This page defines what that means in practice — what's covered by the compatibility promise, what isn't, and how the promise changes before and after `1.0.0`.

## Before 1.0.0

Every `1.0.0-alphaNN` release may contain breaking changes without notice. Nothing is frozen yet. Pin an exact version rather than a range if you depend on a pre-1.0 build.

`1.0.0-betaNN` releases (once they start) narrow this: the public API is expected to be close to final, and breaking changes are called out explicitly in release notes rather than happening silently.

## What's covered once 1.0.0 ships

The compatibility promise applies to:

- **`annotations`** — `@DbEntity`, `@PrimaryKey`, `@Column`, `@Ignore`, `@Relation`, and their parameters
- **`runtime`** — `CrudRepository`, `SchemaMigrator`, the query DSL (`Predicate`, `Column<T>`, `eq`/`and`/`or`/`like`/etc.), `OrderSpec`, driver factories (`AndroidDatabaseDriverFactory`, `IosDatabaseDriverFactory`, `JvmDatabaseDriverFactory`)
- **The shape of generated code** — the public methods, class names, and types that `RepositoryGenerator` emits (`insert`, `update`, `delete`, `findById`, `findWhere`, `<Entity>Columns`, `<Entity>Key` for composite keys, and so on). This isn't a normal "published API" in the usual sense, but a Kotlin compile error in generated code the moment you upgrade is exactly as breaking as a removed method — so it's covered the same way.
- **Automatic migration behavior** — what triggers the fast path (`ALTER TABLE ADD COLUMN`) versus the slow path (table recreation), and the guarantee that existing data survives a migration. This is covered as *behavior*, not as exact SQL text (see below).

`annotations` and `runtime` are checked mechanically on every PR via [binary compatibility validation](https://github.com/Kotlin/binary-compatibility-validator) against a committed API snapshot — see [`CONTRIBUTING.md`](https://github.com/sufarook/Kiln/blob/main/CONTRIBUTING.md). The generated-code shape and migration behavior aren't (and can't easily be) checked by that same tool, so changes there get manual scrutiny before a release.

## What's *not* covered

- **`processor` and `gradle-plugin` internals.** Consumers apply the Gradle plugin and never import these modules' classes directly, so their internal Kotlin API can change freely between any two versions, including patch releases.
- **The exact SQL text Kiln generates or executes** — column ordering in a `CREATE TABLE`, the specific `PRAGMA` calls `SchemaMigrator` issues, internal temp-table naming during a rebuild. Only the *outcome* (correct schema, data preserved) is promised, not the literal statements used to get there.
- **Anything `internal` or `private`.** Not part of the public surface by definition.
- **Performance characteristics.** Kiln doesn't promise a query will keep the same big-O behavior across versions, only that it returns the same result.

## What counts as MAJOR / MINOR / PATCH

| Change | Version bump |
|---|---|
| Removing or renaming a public method, class, or annotation parameter | MAJOR |
| Changing a generated method's signature or return type for existing annotation usage | MAJOR |
| Changing migration behavior in a way that could alter or lose existing data | MAJOR |
| Dropping support for a Kotlin, AGP, Gradle, or JVM version previously supported | MAJOR |
| New annotation, new annotation parameter with a default value, new DSL operator, new generated method | MINOR |
| Expanding what a migration handles (e.g. a new kind of schema change becomes automatic) | MINOR |
| Bug fix that doesn't change any documented, intended behavior | PATCH |
| Internal refactor, dependency bump with no consumer-visible effect, doc fix | PATCH |

!!! note "A bug fix can still be a MAJOR bump"
    If fixing a bug means changing behavior someone could plausibly be relying on — even unintentionally — treat it as a MAJOR bump and call it out explicitly in the release notes, not as a silent PATCH.

## Deprecation policy

Once something is deprecated, it's marked `@Deprecated` with a message explaining the replacement, and kept working for at least one full MINOR release before being removed in the next MAJOR. Deprecation is never silent — it's listed in release notes.

## Toolchain minimums

Raising the minimum supported Kotlin, AGP, Gradle, or JVM version is a MAJOR bump under this policy, even though it doesn't change any Kotlin API signature — it can still break a consumer's build. [`requirements.md`](getting-started/requirements.md) always reflects the current floor; check it before upgrading Kiln in a project pinned to an older toolchain.
