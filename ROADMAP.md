# Roadmap

Where Kiln stands, what is being worked on, and what has been deliberately
deferred. Nothing here is a dated commitment — Kiln is alpha and the order can
change.

For the detail behind active work, see `openspec/changes/<name>/` or run
`openspec list`.

## Where things stand

`1.0.0-alpha05` on Maven Central. Implemented and tested against real SQLite:

- CRUD, reactive queries (`observeAll` / `observeWhere`), type-safe query DSL
- Version-less auto-migration, including column add, remove, rename, and type change
- Composite primary keys, and `@Relation` on key columns for junction tables
- Transactions (`withTransaction`, `insertAll`, `notifyOrDefer`)
- Pagination (`orderBy` / `limit` / `offset`)
- One-call schema setup (`KilnSchema.createAll`)

## Active

### `harden-migration-safety`

Two correctness defects in code that runs unattended at launch against user data.
Both are reachable today without any new feature.

1. **The table-rebuild path can delete rows in tables Kiln does not own.**
   `SchemaMigrator.recreateTable` issues `DROP TABLE` without first disabling
   foreign-key enforcement — a step SQLite's documented 12-step procedure
   requires. Under enforcement, `DROP TABLE` performs an implicit delete of every
   row and fires `ON DELETE CASCADE` on referencing tables. Renaming one property
   is enough to trigger the rebuild. Reachable via Room, which enables foreign
   keys and which `docs/src/faq.md` advertises as supported.

2. **Two transaction authorities on one connection.** `withTransaction` emits raw
   `BEGIN`/`COMMIT` rather than using the driver's transaction API, so nested
   transactions fail. Relatedly, `notifyOrDefer` detects transactions through a
   Kiln-specific coroutine-context element, so a write inside a transaction
   opened elsewhere notifies observers mid-transaction — and still notifies if
   that transaction later rolls back.

Full artifacts in `openspec/changes/harden-migration-safety/`.

## Deferred

### Coexisting with an existing SQLDelight database

**Status: deferred. Nobody has asked for it.**

The idea: let a project already using SQLDelight adopt Kiln for new tables, both
sharing one database file.

**Why deferred.** Three reasons, none of them technical difficulty:

- No demand. It was explored speculatively, not in response to a request.
- It couples Kiln's roadmap to SQLDelight's. Today SQLDelight is an
  implementation detail — `SqlDriver` is a transport, and it could be swapped.
  Making coexistence a supported feature turns SQLDelight's transaction
  semantics, versioning model, and eventual 3.x breaking changes into Kiln's
  compatibility surface.
- It muddies the positioning. `docs/src/index.md` compares Kiln head-to-head
  against SQLDelight. "Replaces this" and "coexists with this" are different
  stories, and the second weakens the first.

**What already works, unchanged.** Kiln repositories take a `SqlDriver`; the
factories are convenience only. So this compiles today:

```kotlin
val driver = AndroidSqliteDriver(Database.Schema, context, "app.db")
val db = Database(driver)          // SQLDelight keeps its tables and user_version
KilnSchema.createAll(driver)       // Kiln's tables, same file, same connection
```

Safe because the two act on disjoint tables and Kiln abstains from versioning
entirely — `SchemaMigrator` is only ever handed one table name and returns early
when that table is absent, so it structurally cannot reach a SQLDelight table.

**What breaks, and the direction matters.** `EmptySchema.version = 1L`, and
`SQLiteOpenHelper` compares the file's `user_version` to the requested one:

- *SQLDelight-first, then add Kiln* — the factory (`AndroidDatabaseDriverFactory`)
  passes `EmptySchema` (v1) against a file at vN, and the open fails with
  `Can't downgrade database from version N to 1`. Using the app's own driver
  instead of the factory avoids this entirely.
- *Kiln-first, then add SQLDelight* — worse. Standalone Kiln stamps
  `user_version = 1`; a fresh SQLDelight schema is also version 1. The helper
  sees `1 == 1`, so neither `onCreate` nor `onUpgrade` fires and SQLDelight's
  tables are never created. `EmptySchema.version = 0` is not an escape — Android
  rejects a version below 1.

**Edge cases found while exploring.** Items 1 and 2 were promoted into
`harden-migration-safety` because they are defects regardless. The rest are only
reachable in a coexistence scenario:

| Issue | Consequence |
|---|---|
| `PRAGMA table_info` reports columns for views, so the `existing.isEmpty()` guard does not mean "table absent" | A view sharing an entity's name gets `ALTER`/`DROP` attempted on it |
| Nothing enforces that Kiln and SQLDelight own disjoint tables | An entity pointed at an existing `.sq` table silently puts the diff engine in charge of a table SQLDelight believes it owns |
| `CREATE INDEX IF NOT EXISTS "idx_<table>_<column>"` | A pre-existing index of that name makes Kiln's index silently not exist — no error, no signal |
| `JvmDatabaseDriverFactory` passes no schema at all | Desktop/server integration needs a different signature shape than Android/iOS |

**What would have to be true to revisit.** Someone asking for it, and
`harden-migration-safety` having landed first — the transaction and foreign-key
fixes are what make sharing a connection safe at all.

### Adopting existing tables (`@DbEntity` over a table Kiln did not create)

Considered and rejected for now as the stronger version of the above. It needs
two migration authorities to agree about one table, which they cannot: SQLDelight
adapters (a `List<String>` stored as TEXT) are unreadable by Kiln's type mapping,
and a `.sq` migration that renames a column leaves the entity pointing at a
column that no longer exists — whereupon Kiln's own migrator would "fix" the
table to match the entity.

The safer bridge, if this is ever wanted, is a read-only entity: Kiln generates
finders and the query DSL but emits no DDL and never migrates. Most of the
benefit, none of the split-brain.
