# Roadmap

Where Kiln stands, what is being worked on, and what has been deliberately
deferred. Nothing here is a dated commitment — Kiln is alpha and the order can
change.

For the detail behind active work, see `openspec/changes/<name>/` or run
`openspec list`.

## Where things stand

`1.0.0-alpha07` on Maven Central. Implemented and tested against real SQLite:

- CRUD, reactive queries (`observeAll` / `observeWhere`), type-safe query DSL
- Version-less auto-migration, including column add, remove, rename, and type change
- Composite primary keys, and `@Relation` on key columns for junction tables
- Transactions (`withTransaction`, `insertAll`, `notifyOrDefer`)
- Pagination (`orderBy` / `limit` / `offset`)
- One-call schema setup (`KilnSchema.createAll`)
- Own driver layer (`KilnDriver`) — no SQLDelight dependency

## Active

### `own-driver-layer`

Replace the SQLDelight `SqlDriver` dependency with Kiln's own `KilnDriver`
interface and platform-specific implementations. Removes the only external
runtime dependency and gives Kiln full control over its driver contract.

Full artifacts in `openspec/changes/own-driver-layer/`.

## Deferred

### Coexisting with an existing SQLDelight database

**Status: deferred. Nobody has asked for it.**

The idea: let a project already using SQLDelight adopt Kiln for new tables, both
sharing one database file.

Since Kiln now has its own driver layer (`KilnDriver`), it no longer depends on
SQLDelight at all. The two libraries use separate connections to the same database
file — there is no shared driver instance.

**Why deferred.** Two reasons, neither technical difficulty:

- No demand. It was explored speculatively, not in response to a request.
- It muddies the positioning. `docs/src/index.md` compares Kiln head-to-head
  against SQLDelight. "Replaces this" and "coexists with this" are different
  stories, and the second weakens the first.

**What works today.** Both libraries can point at the same `.db` file with
separate connections. Safe because they act on disjoint tables and Kiln abstains
from versioning entirely — `SchemaMigrator` is only ever handed one table name
and returns early when that table is absent, so it structurally cannot reach a
SQLDelight table. SQLite's WAL mode handles concurrent connections correctly.

**Edge cases found while exploring.** Items 1 and 2 were promoted into
`harden-migration-safety` because they are defects regardless. The rest are only
reachable in a coexistence scenario:

| Issue | Consequence |
|---|---|
| `PRAGMA table_info` reports columns for views, so the `existing.isEmpty()` guard does not mean "table absent" | A view sharing an entity's name gets `ALTER`/`DROP` attempted on it |
| Nothing enforces that Kiln and SQLDelight own disjoint tables | An entity pointed at an existing `.sq` table silently puts the diff engine in charge of a table SQLDelight believes it owns |
| `CREATE INDEX IF NOT EXISTS "idx_<table>_<column>"` | A pre-existing index of that name makes Kiln's index silently not exist — no error, no signal |

**What would have to be true to revisit.** Someone asking for it, and
`harden-migration-safety` having landed first — the transaction and foreign-key
fixes are what make sharing a connection safe at all.

### Adopting existing tables (`@DbEntity` over a table Kiln did not create)

Considered and rejected for now as the stronger version of the above. It needs
two migration authorities to agree about one table, which they cannot: another
library's column adapters (a `List<String>` stored as TEXT) are unreadable by
Kiln's type mapping, and an external migration that renames a column leaves the
entity pointing at a column that no longer exists — whereupon Kiln's own migrator
would "fix" the table to match the entity.

The safer bridge, if this is ever wanted, is a read-only entity: Kiln generates
finders and the query DSL but emits no DDL and never migrates. Most of the
benefit, none of the split-brain.
