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
       add("kspCommonMainMetadata", "io.github.sufarook.kiln:processor:1.0.0-alpha02")
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
    id("io.github.sufarook.kiln") version "1.0.0-alpha02"
}
```

---

### "Kiln: @DbEntity must have exactly one @PrimaryKey property"

Every `@DbEntity` class needs exactly one property annotated with `@PrimaryKey`. Check that:

- The annotation is on a property inside the primary constructor, not the class body.
- You haven't accidentally annotated two properties.
- You haven't forgotten it entirely.

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

`observeAll()` re-fetches the full table on every write to that table. For tables with thousands of rows, prefer `observeWhere { … }` with a predicate that limits the result set, and handle pagination in the UI.

For read-heavy screens that don't need live updates, `findAll()` and `findWhere()` are one-shot suspend functions with no listener overhead.

---

## General

### Does Kiln support transactions?

Not via a generated API. Use the `SqlDriver` directly:

```kotlin
driver.execute(null, "BEGIN TRANSACTION", 0)
try {
    taskRepo.insert(task)
    checklistRepo.insert(item)
    driver.execute(null, "COMMIT", 0)
} catch (e: Exception) {
    driver.execute(null, "ROLLBACK", 0)
    throw e
}
```

---

### Does it support foreign keys?

SQLite foreign keys are not enabled by default. Kiln does not emit `PRAGMA foreign_keys = ON`. Handle referential integrity manually — see [Cross-table Loading](sample/cross-table.md) for the recommended pattern.

---

### Can I use Kiln in a pure JVM project (no Android)?

Yes. Pass a `JdbcSqliteDriver` from SQLDelight's `sqlite-driver` artifact:

```kotlin
val driver: SqlDriver = JdbcSqliteDriver("jdbc:sqlite:myapp.db")
val repo = ProductRepository(driver)
repo.createTable()
```

The generated code only depends on `app.cash.sqldelight:runtime` — it has no Android dependency.
