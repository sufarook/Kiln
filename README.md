# Kiln

**Compile-time CRUD generation for Kotlin Multiplatform SQLite. No SQL. No mappers. No migrations.**

Annotate a data class. Get a fully typed, coroutine-native repository — on Android **and** iOS, generated at compile time with zero reflection.

```kotlin
@DbEntity(tableName = "todos")
data class Todo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    @Column(name = "is_completed") val isCompleted: Boolean = false,
    @Column(index = true) val priority: Int = 0
)
```

That's all you write. This is what you get:

```kotlin
val repo = TodoRepository(driver)
repo.createTable()                                        // creates + auto-migrates

repo.insert(Todo(title = "Ship it"))                      // suspend
repo.update(todo.copy(isCompleted = true))                // suspend
repo.delete(todo.id)                                      // suspend
repo.findById(1L)                                         // suspend, Todo?
repo.findAll()                                            // suspend, List<Todo>

repo.observeAll()                                         // Flow<List<Todo>> — reactive
repo.findWhere { (priority eq 1) and (isCompleted eq false) }   // type-safe queries
repo.observeWhere { title like "Ship%" }                  // reactive + filtered
repo.deleteWhere { isCompleted eq true }
repo.count()
```

## Why Kiln over Room?

| | Room | Kiln |
|---|---|---|
| Platforms | Android (KMP support partial) | Android + iOS from one `commonMain` entity |
| Migrations | Manual: bump version, write `Migration(1, 2)` | **Automatic** — no version numbers exist |
| Rename a column | Hand-written SQL migration | `@Column(migrateFrom = "old_name")` |
| Queries | `@Query("SELECT * FROM …")` — SQL strings | `findWhere { priority eq 1 }` — compile-checked |
| Runtime | Reflection-free (KSP) | Reflection-free (KSP) |

## Setup

One line with the Gradle plugin:

```kotlin
plugins {
    kotlin("multiplatform")                        // or com.android.application + kotlin-android
    id("io.github.sufarook.kiln") version "1.0.0-alpha03"
}
```

The plugin applies KSP, wires the processor, and adds the `annotations` + `runtime`
dependencies. In KMP projects the processor runs once on common metadata, so one
generated repository is shared by every target.

<details>
<summary>Manual setup (without the plugin)</summary>

```kotlin
plugins {
    kotlin("multiplatform")
    id("com.google.devtools.ksp")
}

kotlin.sourceSets.commonMain {
    kotlin.srcDir(layout.buildDirectory.dir("generated/kiln/commonMain/kotlin"))
    dependencies {
        api("io.github.sufarook.kiln:annotations:1.0.0-alpha03")
        api("io.github.sufarook.kiln:runtime:1.0.0-alpha03")
    }
}

dependencies { add("kspCommonMainMetadata", "io.github.sufarook.kiln:processor:1.0.0-alpha03") }

// KSP filters its own output dirs out of Android compilations — sync to a neutral dir
val sync = tasks.register<Sync>("syncKilnGeneratedSources") {
    dependsOn("kspCommonMainKotlinMetadata")
    from(layout.buildDirectory.dir("generated/ksp/metadata/commonMain/kotlin"))
    into(layout.buildDirectory.dir("generated/kiln/commonMain/kotlin"))
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    if (name != "kspCommonMainKotlinMetadata") dependsOn(sync)
}
```
</details>

### Opening the database

```kotlin
// commonMain — all your logic
class TodoStore(driver: SqlDriver) {
    private val repo = TodoRepository(driver)
    init { repo.createTable() }
    fun observeAll(): Flow<List<Todo>> = repo.observeAll()
}

// Android
val driver = AndroidDatabaseDriverFactory(context).create("app.db")

// iOS
val driver = IosDatabaseDriverFactory().create("app.db")
```

## Automatic migrations — no version numbers

Every launch, `createTable()` diffs the live SQLite schema against your data class
and reconciles. You never write a migration, never bump a version.

| You change the data class | What happens on next launch |
|---|---|
| Add a property | `ALTER TABLE ADD COLUMN`, existing rows get the type default |
| Remove a property | Table rebuilt without it — old column fully removed, data preserved |
| Rename: `@Column(migrateFrom = "old")` | Table rebuilt, data copied from old column |
| Change a property's type | Table rebuilt, values `CAST` to the new type |
| No change | No-op (schema diff finds nothing) |

Rebuilds run inside a transaction — an upgrade failure rolls back to the previous state.

## Type-safe queries

Each entity gets a generated `<Entity>Columns` object. Operators: `eq`, `neq`,
`gt`, `gte`, `lt`, `lte`, `like`, `isNull`, `isNotNull`, `inList`, combined with
`and`, `or`, `not`. Column names are compile-time constants and every value is
bound with `?` — injection is structurally impossible.

```kotlin
repo.findWhere { (priority gte 1) and (title like "Urgent%") or (dueDate.isNull()) }
```

## Supported property types

`String`, `Int`, `Long`, `Short`, `Byte`, `Float`, `Double`, `Boolean`,
`ByteArray`, enums (stored as `TEXT` by name), `kotlinx.datetime`
`LocalDate` / `LocalDateTime` / `Instant` (stored as ISO-8601 `TEXT`).
Nullable variants of all of the above. Skip a property with `@Ignore`.

## Annotations

| Annotation | Purpose |
|---|---|
| `@DbEntity(tableName = "")` | Marks a data class as a table (name defaults to snake_case) |
| `@PrimaryKey(autoGenerate = false)` | Exactly one per entity; `autoGenerate` needs `Long`/`Int` |
| `@Column(name, unique, index, migrateFrom)` | Column overrides and constraints |
| `@Relation(foreignKey = "")` | Declares a FK property; generates `findBy<Parent>`, `observeBy<Parent>`, `deleteBy<Parent>` |
| `@Ignore` | Property is not persisted |

## Modules

| Coordinate | What it is |
|---|---|
| `io.github.sufarook.kiln:annotations` | The four annotations (all KMP targets) |
| `io.github.sufarook.kiln:runtime` | `CrudRepository`, `SchemaMigrator`, query DSL, driver factories |
| `io.github.sufarook.kiln:processor` | KSP processor (compile-time only) |
| `io.github.sufarook.kiln` (plugin) | One-line Gradle setup |

## Samples

- [`sample-android`](sample-android) — plain Android Todo app: reactive list via `observeAll()`, full CRUD UI
- [`sample-shared`](sample-shared) — KMP module: entity + store in `commonMain`, builds an iOS framework

## Status

Alpha. CRUD + reactive queries + auto-migration + `@Relation` FK helpers + transactions
(`withTransaction`, `insertAll`, `notifyOrDefer`) are fully implemented and tested against
real SQLite. Not yet supported: compound primary keys, pagination.

## License

Apache-2.0
