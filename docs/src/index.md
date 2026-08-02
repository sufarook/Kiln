# Kiln

**Annotate a data class. Rebuild. Get a complete, type-safe SQLite repository.**

No SQL to write. No version numbers to track. No migration files to maintain.

---

<div class="grid cards" markdown>

-   :material-code-tags:{ .lg .middle } **Compile-time generation**

    ---

    KSP reads your annotations and generates the full repository before your code compiles. Zero runtime reflection, zero overhead.

-   :material-refresh-auto:{ .lg .middle } **Version-less auto-migration**

    ---

    Add, rename, or remove a property. Just rebuild — Kiln diffs the live schema and migrates automatically.

-   :material-water-outline:{ .lg .middle } **Reactive by default**

    ---

    `observeAll()` and `observeWhere()` return `Flow<List<T>>` that re-emits after every write. No polling, no manual refresh.

-   :material-filter-outline:{ .lg .middle } **Type-safe DSL**

    ---

    `findWhere { TaskColumns.status eq "DONE" }` — queries are compile-time checked with no raw SQL strings.

-   :material-cellphone-link:{ .lg .middle } **Kotlin Multiplatform**

    ---

    Define entities in `commonMain`. Works on Android and iOS from a single source of truth.

-   :material-puzzle-outline:{ .lg .middle } **One-line Gradle setup**

    ---

    The `io.github.sufarook.kiln` plugin applies KSP and wires all source sets automatically.

-   :material-link-variant:{ .lg .middle } **Relations & transactions**

    ---

    `@Relation` generates typed FK helpers (`findByParent`, `deleteByParent`). `withTransaction` wraps multi-step writes in a single atomic commit.

</div>

## How it works

```kotlin
// You write this:
@DbEntity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @Column(name = "project_id", index = true) val projectId: Long,
    val title: String,
    val status: String = "TODO"
)
```

Kiln generates `TaskRepository` at compile time with:

| Method | Description |
|--------|-------------|
| `createTable()` | Creates the table and auto-migrates on every launch |
| `insert(task)` | `suspend` — inserts a row |
| `update(task)` | `suspend` — updates by primary key |
| `delete(id)` | `suspend` — deletes by primary key |
| `findById(id)` | `suspend` — returns `Task?` |
| `findAll()` | `suspend` — returns `List<Task>` |
| `findWhere { … }` | `suspend` — type-safe DSL filter |
| `observeAll()` | Returns `Flow<List<Task>>` |
| `observeWhere { … }` | Reactive + filtered `Flow<List<Task>>` |
| `deleteWhere { … }` | `suspend` — bulk delete by predicate |
| `count { … }` | `suspend` — returns `Long` |
| `insertAll(items)` | `suspend` — bulk insert inside a single transaction |
| `findBy<Parent>(id)` | `suspend` — returns `List<T>` for a FK parent (requires `@Relation`) |
| `observeBy<Parent>(id)` | Returns `Flow<List<T>>` filtered by FK parent |
| `deleteBy<Parent>(id)` | `suspend` — deletes all rows belonging to a FK parent |

## Why Kiln

| | Room | SQLDelight | Kiln |
|---|---|---|---|
| SQL you write | `@Query` annotations | `.sq` files | **None** |
| Migration | Version numbers + SQL files | Manual SQL | **Automatic** |
| Reactive Flow | Extra setup | Manual listener | **Built-in** |
| Type-safe queries | Partial | Yes | **Yes** |
| Kotlin Multiplatform | Android only | Yes | **Yes** |

## Quick start

```kotlin
// 1. Apply the plugin in build.gradle.kts
//    id("io.github.sufarook.kiln") version "1.0.0-alpha03"

// 2. Annotate a data class
@DbEntity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val body: String = ""
)

// 3. Initialize once (e.g. Application.onCreate)
val driver = AndroidDatabaseDriverFactory(context).create("app.db")
val noteRepo = NoteRepository(driver).also { it.createTable() }

// 4. Observe and write
lifecycleScope.launch {
    noteRepo.observeAll().collect { notes -> adapter.submitList(notes) }
}
lifecycleScope.launch {
    noteRepo.insert(Note(title = "First note"))
}
```

[Get started :material-arrow-right:](getting-started/quickstart.md){ .md-button .md-button--primary }
[View on GitHub :material-github:](https://github.com/sufarook/Kiln){ .md-button }
