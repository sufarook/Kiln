# FAQ & Troubleshooting

## Build errors

### "Unresolved reference: TaskRepository"

The generated `TaskRepository` class doesn't exist yet, or KSP didn't run.

**Common causes and fixes:**

1. **You haven't built yet.** Run `./gradlew assembleDebug` (or `Build → Make Project` in Android Studio). The class is generated at compile time — it doesn't exist until you build.

2. **KSP isn't wired to the right source set.** In a KMP project, add the KSP dependency for the `commonMainMetadata` configuration:
   ```kotlin
   // build.gradle.kts
   dependencies {
       add("kspCommonMainMetadata", "io.github.sufarook.kiln:processor:1.0.0-alpha05")
   }
   ```

3. **Generated sources aren't on the compile classpath.** If you're not using the Gradle plugin, add the generated directory manually:
   ```kotlin
   kotlin {
       sourceSets.commonMain {
           kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
       }
   }
   ```

4. **Stale KSP output.** Run `./gradlew clean` then rebuild.

---

### "The KSP Gradle plugin should be applied to the project"

You applied the Kiln plugin but KSP is missing. The plugin declares KSP as a required dependency — make sure you've added the KSP plugin to the plugins block:

```kotlin
plugins {
    id("com.google.devtools.ksp") version "2.3.20-1.0.31"
    id("io.github.sufarook.kiln") version "1.0.0-alpha05"
}
```

---

### "Kiln: '<Entity>' must have at least one @PrimaryKey property"

Every `@DbEntity` class needs at least one property annotated with `@PrimaryKey`. Check that:

- The annotation is on a property inside the primary constructor, not the class body.
- You haven't forgotten it entirely.

Annotating two or more properties is fine — it forms a [composite primary key](annotations/primary-key.md), not an error. `autoGenerate = true` is the one thing that isn't allowed once a class has more than one `@PrimaryKey`.

---

### "Kiln: type 'X' is not supported"

The property type isn't in the [supported types list](reference/supported-types.md). Options:

- Use a supported type (e.g. `kotlinx.datetime.Instant` instead of `java.util.Date`).
- Annotate the property with `@Ignore` and manage persistence yourself.
- For complex objects, serialize to `String` and store as a `String` column.

---

## Migration issues

### I renamed a property but existing data is gone

Without `@Column(migrateFrom = "old_name")`, Kiln treats the old column as dropped and the new column as added. All data in the old column defaults to the Kotlin default value.

**Fix for future renames:** always add `migrateFrom`:
```kotlin
@Column(migrateFrom = "old_column_name")
val newPropertyName: String = ""
```

**Recovering existing data:** If the data is still on device and you haven't uninstalled, you can write a one-time migration using the raw `SqlDriver`:
```kotlin
driver.execute(null, "UPDATE tasks SET new_name = old_name", 0)
```
Run this once before calling `createTable()`, then remove it.

---

### I added a new non-nullable column but app crashes on launch

SQLite requires a `DEFAULT` value when adding a `NOT NULL` column via `ALTER TABLE`. Kiln uses the Kotlin default value for this.

**If the property has no Kotlin default**, the migration throws `SQLiteException`. Add a default:
```kotlin
val newField: String = ""         // ← default required for migration
val newCount: Int = 0
val newFlag: Boolean = false
```

---

### `observeAll()` isn't updating after insert

The flow won't re-emit if the write and the observer use different `SqlDriver` instances. All repositories that share the same driver instance share the same `Query.Listener` channel.

**Make sure you pass the same driver to every repository:**
```kotlin
// Correct — all repos share one driver
val driver = createDriver()
val taskRepo = TaskRepository(driver)
val checklistRepo = ChecklistItemRepository(driver)

// Wrong — separate drivers don't share listeners
val taskRepo = TaskRepository(createDriver())
val checklistRepo = ChecklistItemRepository(createDriver())
```

---

### `observeAll()` emits once and then stops

You're collecting the flow without `repeatOnLifecycle`, and the lifecycle owner has moved to background. Use:

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        repo.observeAll().collect { ... }
    }
}
```

Or use `stateIn` in a `ViewModel` with `SharingStarted.WhileSubscribed(5_000)`.

---

## Using with an existing database

### I have an existing SQLite database — can Kiln manage it?

Yes. Point the driver at your existing database file and call `createTable()`. The migrator reads `PRAGMA table_info` and only applies changes that differ from the entity definition. Existing columns that match are left untouched.

**Tables Kiln doesn't know about** (no corresponding `@DbEntity`) are never touched.

---

### Can Kiln work alongside raw SQL or Room?

Yes. Kiln uses the `SqlDriver` directly — it's the same driver you'd pass to SQLDelight. You can call raw SQL through `driver.execute(…)` at any point. Just be aware that raw writes won't trigger Kiln's `Query.Listener`, so reactive flows won't re-emit for those changes.

---

## Performance

### Is `observeAll()` efficient for large tables?

`observeAll()` re-fetches the full table on every write to that table. For tables with thousands of rows, prefer `observeWhere { … }` with a predicate that limits the result set, and use the built-in `orderBy` / `limit` / `offset` parameters to page through results instead of loading everything:

```kotlin
repo.observeWhere(orderBy = listOf(TaskColumns.priority.desc()), limit = 50, offset = page * 50) {
    status eq "OPEN"
}
```

For read-heavy screens that don't need live updates, `findAll()` and `findWhere()` are one-shot suspend functions with no listener overhead — and `findWhere` takes the same `orderBy` / `limit` / `offset`.

---

## General

### Does Kiln support transactions?

Yes. Wrap multiple writes in `SqlDriver.withTransaction { }` — everything inside commits atomically, and reactive flows receive a **single** notification after the commit (or none at all if it rolls back):

```kotlin
driver.withTransaction {
    taskRepo.insert(task)
    checklistRepo.insert(item)
}
```

If the block throws, the transaction rolls back and no `observeAll()` / `observeWhere()` flow re-emits for those writes. Generated repositories also expose `insertAll(items)`, which wraps a bulk insert in a single transaction for you.

You can still drop down to raw `driver.execute(null, "BEGIN TRANSACTION", 0)` if you need finer control, but `withTransaction` is the recommended path — it handles rollback and the deferred notification correctly.

---

### Does it support foreign keys?

Two separate things, and the answer differs for each:

**FK relationship helpers — yes.** Annotate a foreign-key property with `@Relation` and Kiln generates `findByParent(id)`, `observeByParent(id)`, and `deleteByParent(id)` on the repository. This works on composite-key columns too, so a junction table gets FK helpers on each key column. See the [`@Relation` reference](annotations/relation.md).

**FK constraint enforcement — no.** SQLite foreign keys are off by default and Kiln does not emit `PRAGMA foreign_keys = ON`, so the database won't reject an orphaned row or cascade a delete on its own. Handle referential integrity in your code — call `deleteByParent(id)` before deleting a parent (annotate the relation `@Relation(cascade = true)` as a reminder that you're responsible for the cascade), and see [Cross-table Loading](sample/cross-table.md) for the recommended pattern.

---

### Can I use Kiln in a pure JVM project (no Android)?

Yes. Pass a `JdbcSqliteDriver` from SQLDelight's `sqlite-driver` artifact:

```kotlin
val driver: SqlDriver = JdbcSqliteDriver("jdbc:sqlite:myapp.db")
val repo = ProductRepository(driver)
repo.createTable()
```

The generated code only depends on `app.cash.sqldelight:runtime` — it has no Android dependency.
