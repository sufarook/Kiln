# Initialization

## Create driver and repositories

Create one `SqlDriver` and pass it to each repository. All repositories share the same driver — SQLite is a single-file database and a single `SqlDriver` instance manages the connection.

Kiln generates a `KilnSchema` object covering every `@DbEntity` in the module, so one call creates the whole schema:

```kotlin title="TaskTrackerApp.kt"
class TaskTrackerApp : Application() {

    lateinit var projectRepo: ProjectRepository
    lateinit var taskRepo: TaskRepository
    lateinit var checklistRepo: ChecklistItemRepository

    override fun onCreate() {
        super.onCreate()
        val driver = AndroidDatabaseDriverFactory(this).create("tasktracker.db")

        // Creates and auto-migrates every table. Add a new @DbEntity later and
        // this line does not change.
        KilnSchema.createAll(driver)

        projectRepo   = ProjectRepository(driver)
        taskRepo      = TaskRepository(driver)
        checklistRepo = ChecklistItemRepository(driver)
    }
}
```

`KilnSchema` is generated into the deepest package your entities share — if they all live in `com.example.data`, it is `com.example.data.KilnSchema`.

??? note "Creating tables individually"
    `createAll()` is a convenience, not a replacement. Every repository still has its own `createTable()`, which is what you want if a table should only exist in certain builds or you need to control exactly when a migration runs:

    ```kotlin
    projectRepo = ProjectRepository(driver).also { it.createTable() }
    ```

Register in `AndroidManifest.xml`:

```xml title="AndroidManifest.xml"
<application
    android:name=".TaskTrackerApp"
    ...>
```

## What table creation actually does

`KilnSchema.createAll()` calls `createTable()` on each repository, and `createTable()` does two things every time it runs:

1. Runs `CREATE TABLE IF NOT EXISTS` — safe to call repeatedly; no-op if the table exists.
2. Runs `SchemaMigrator.sync()` — diffs the live schema against the generated column list and migrates if needed.

Call it on every launch. It is fast when the schema hasn't changed (just a `PRAGMA table_info` read) and performs the necessary migration when it has.

!!! note "Creation order does not matter"
    Kiln does not emit `FOREIGN KEY` constraints, so SQLite has no notion that one of your tables references another — no table needs to exist before any other. `@Relation` generates query helpers (`findByProject`, `deleteByProject`, …), not database-level constraints. Referential integrity is handled in your own code; see [Cross-table Loading](cross-table.md).

## Access from Activities and ViewModels

```kotlin
// From an Activity
val app = application as TaskTrackerApp
val taskRepo = app.taskRepo

// From a ViewModel (inject via factory or DI)
class ProjectViewModel(private val projectRepo: ProjectRepository) : ViewModel()
```

## iOS (KMP)

On iOS, use `IosDatabaseDriverFactory` in place of the Android one:

```kotlin title="iosMain"
val driver = IosDatabaseDriverFactory().create("tasktracker.db")
val projectRepo = ProjectRepository(driver).also { it.createTable() }
```
