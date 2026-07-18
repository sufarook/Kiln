# Auto-migration

Krate migrates your SQLite schema automatically whenever `createTable()` is called after an entity change. You never write a migration file or bump a version number.

## How it works

`createTable()` calls `SchemaMigrator.sync()`, which:

1. Reads `PRAGMA table_info("<table>")` to get the current live schema (column names and types).
2. Compares it against the generated `ColumnDef` list (derived from your annotated data class).
3. Applies the necessary changes.

## Migration paths

| Change | Path | What happens |
|--------|------|-------------|
| New property added | **Fast** | `ALTER TABLE ADD COLUMN … DEFAULT …` — existing rows get the Kotlin default value |
| Property renamed | **Slow** | Table recreated; data copied from old column name to new column name |
| Property removed | **Slow** | Table recreated without the orphaned column |
| Type changed | **Slow** | Table recreated with `CAST` applied during the data copy |
| No change | **No-op** | Only a `PRAGMA table_info` read; returns immediately |

!!! note "The slow path is transactional"
    Table recreation runs inside a single `BEGIN TRANSACTION … COMMIT`. If any step fails, the transaction is rolled back and the original table is left unchanged.

---

## Adding a column

Just add a property with a default value and rebuild:

```kotlin
// Before
@DbEntity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val status: String = "TODO"
)

// After — rebuild and launch; existing rows get notes = "" and dueDate = ""
@DbEntity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val status: String = "TODO",
    val notes: String = "",                          // new — fast path
    @Column(name = "due_date") val dueDate: String = ""  // new — fast path
)
```

!!! warning "New columns must have a default value"
    SQLite requires a default value when adding a `NOT NULL` column via `ALTER TABLE`. Every new non-nullable property must have a Kotlin default. If it doesn't, the migration throws a `SQLiteException`.

---

## Renaming a column

Set `@Column(migrateFrom = "old_name")` on the renamed property:

```kotlin
// Before
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val priority: Int = 1     // will be renamed
)

// After
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @Column(migrateFrom = "priority") val urgency: Int = 1  // data preserved
)
```

**Without `migrateFrom`**: Krate sees `priority` as an orphaned column (dropped) and `urgency` as a new column (all rows get the default value `1`). Existing data is lost.

**With `migrateFrom = "priority"`**: The slow path recreates the table and copies `priority` values into `urgency`. No data loss.

---

## Removing a column

Simply delete the property. The slow path recreates the table without it:

```kotlin
// Before
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val legacyField: String = ""   // no longer needed
)

// After — rebuild; "legacyField" column is dropped on next launch
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String
)
```

---

## Changing a type

Changing a property type triggers the slow path with a `CAST`:

```kotlin
// Before — priority stored as String
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val priority: String = "LOW"
)

// After — priority stored as Int
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val priority: Int = 1   // "LOW" → CAST("LOW" AS INTEGER) → 0
)
```

!!! warning
    Type changes use SQLite `CAST`, which may produce unexpected values (`"LOW"` cast to `INTEGER` is `0`). Convert your data to the target type before shipping the schema change, or handle the conversion in a post-migration step.

---

## Combined changes

All migration paths compose correctly. In a single entity update you can add columns (fast path), rename a column (slow path), and remove a column (slow path) — Krate performs them in the correct order in a single transaction.

```kotlin
// v1 (installed on devices)
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val priority: Int = 1,     // will be renamed
    val status: String = "TODO"
)

// v2 — rebuild and release; no migration file, no version number
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    @Column(migrateFrom = "priority") val urgency: Int = 1, // renamed — data preserved
    val status: String = "TODO",
    val notes: String = "",                                  // new — default ""
    @Column(name = "is_completed") val isCompleted: Boolean = false  // new
)
```
