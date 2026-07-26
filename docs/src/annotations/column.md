# @Column

Customizes a property's column mapping. All parameters are optional — a property without `@Column` is mapped to a column using its Kotlin property name with no extra constraints.

## Signature

```kotlin
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Column(
    val name: String = "",
    val unique: Boolean = false,
    val index: Boolean = false,
    val migrateFrom: String = ""
)
```

## Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `name` | `String` | `""` | Overrides the SQLite column name. If empty, the Kotlin property name is used as-is. |
| `unique` | `Boolean` | `false` | Adds a `UNIQUE` constraint on this column. |
| `index` | `Boolean` | `false` | Generates `CREATE INDEX IF NOT EXISTS` for this column. |
| `migrateFrom` | `String` | `""` | Migration rename hint. See [Auto-migration](../migration.md). |

---

## `name` — Override the column name

Use this to set snake_case column names (SQLite convention) for camelCase Kotlin properties.

```kotlin
@DbEntity(tableName = "users")
data class User(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fullName: String,                               // column: "fullName"
    @Column(name = "full_name") val fullName2: String,  // column: "full_name"
    @Column(name = "created_at") val createdAt: String  // column: "created_at"
)
```

---

## `unique` — Unique constraint

Generates `UNIQUE` in the `CREATE TABLE` statement. Inserting a duplicate value throws a `SQLiteConstraintException`.

```kotlin
@DbEntity(tableName = "users")
data class User(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @Column(unique = true) val email: String   // UNIQUE
)
```

---

## `index` — Create an index

Generates `CREATE INDEX IF NOT EXISTS idx_<table>_<column> ON <table>("<column>")` when `createTable()` is called. Use this on:

- **Foreign key columns** — any column used to filter by a parent entity id.
- **Frequently queried columns** — columns you regularly use in `findWhere` or `observeWhere`.

```kotlin
@DbEntity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @Column(name = "project_id", index = true) val projectId: Long,  // FK index
    val title: String,
    @Column(index = true) val status: String = "TODO"                 // filter index
)
```

!!! tip
    Indexes on foreign key columns significantly speed up `findWhere { TaskColumns.projectId eq someId }`. Without an index, SQLite performs a full table scan for every such query.

---

## `migrateFrom` — Column rename hint

When you rename a property, Kiln would normally treat it as "old column removed, new column added" — losing all existing data. Set `migrateFrom` to preserve the data across the rename.

```kotlin
// Before
@DbEntity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val priority: Int = 1       // <-- will be renamed
)

// After — existing "priority" data is carried into "urgency"
@DbEntity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @Column(migrateFrom = "priority") val urgency: Int = 1
)
```

See [Auto-migration](../migration.md) for full details on how the migration is performed.

!!! warning "Remove `migrateFrom` after the migration is deployed"
    Once all users have upgraded past the version that carried the rename, remove the `migrateFrom` hint. Leaving it in place has no effect on a schema that has already been migrated, but it is misleading documentation.
