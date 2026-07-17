# Initialization

## Create driver and repositories

Create one `SqlDriver` and pass it to each repository. All repositories share the same driver — SQLite is a single-file database and a single `SqlDriver` instance manages the connection.

```kotlin title="TaskTrackerApp.kt"
class TaskTrackerApp : Application() {

    lateinit var projectRepo: ProjectRepository
    lateinit var taskRepo: TaskRepository
    lateinit var checklistRepo: ChecklistItemRepository

    override fun onCreate() {
        super.onCreate()
        val driver = AndroidDatabaseDriverFactory(this).create("tasktracker.db")

        projectRepo   = ProjectRepository(driver).also  { it.createTable() }
        taskRepo      = TaskRepository(driver).also     { it.createTable() }
        checklistRepo = ChecklistItemRepository(driver).also { it.createTable() }
    }
}
```

Register in `AndroidManifest.xml`:

```xml title="AndroidManifest.xml"
<application
    android:name=".TaskTrackerApp"
    ...>
```

## About `createTable()`

`createTable()` does two things every time it is called:

1. Runs `CREATE TABLE IF NOT EXISTS` — safe to call repeatedly; no-op if the table exists.
2. Runs `SchemaMigrator.sync()` — diffs the live schema against the generated column list and migrates if needed.

Call it on every launch. It is fast when the schema hasn't changed (just a `PRAGMA table_info` read) and performs the necessary migration when it has.

!!! warning "Call order matters for foreign key integrity"
    Call `createTable()` on the parent table before the child table if you are enforcing foreign key relationships manually. In this sample: `projectRepo.createTable()` before `taskRepo.createTable()`.

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
