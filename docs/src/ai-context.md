# AI Context File

Copy the template below into your project root (as `KILN.md`, `CLAUDE.md`, `.cursorrules`, or whatever your AI tool reads) so your coding assistant understands Kiln and gives you correct guidance.

---

## Template

````markdown
# Kiln — AI Context

This project uses [Kiln](https://github.com/sufarook/Kiln) for compile-time
SQLite CRUD generation via KSP.

Full AI reference: https://sufarook.github.io/Kiln/llms.txt

## Quick rules for AI agents

- Kiln generates a `<Entity>Repository` class for each `@DbEntity` data class.
  Never write repository code manually — annotate the entity and rebuild.
- Never write SQL for single-table CRUD. Use the generated repository methods.
- Use `KilnSchema.createAll(driver)` to initialize all tables — not individual
  `createTable()` calls. Call it once at app startup, outside any transaction.
- `and` / `or` are member functions on `Predicate`. Do NOT import them.
  Only `not` needs an import (`import io.github.sufarook.kiln.runtime.not`).
- All other DSL operators need explicit imports:
  `import io.github.sufarook.kiln.runtime.eq` (or `.*` for all).
- `insert()` does not return the generated id.
- New non-nullable columns MUST have a Kotlin default value for migration.
- Kiln emits NO foreign key constraints. `@Relation` generates helper methods
  only. Handle referential integrity in your code with transactions.
- Use `driver.withTransaction { }` for multi-table writes. Flows get one
  emission after commit.
- For aggregates (SUM, AVG), JOINs, or GROUP BY, use `driver.executeQuery()`
  with raw SQL.
- Use `findWhere(orderBy, limit, offset) { predicate }` for pagination.
- Migration is automatic and version-less — no migration files exist.
  Use `@Column(migrateFrom = "old_name")` for column renames.
- All repos MUST share ONE `KilnDriver` instance for reactive observation to
  work.
````

For Claude Code specifically, save this as `CLAUDE.md` in your project root.
For Cursor, save as `.cursorrules`. For other tools, consult their documentation.
