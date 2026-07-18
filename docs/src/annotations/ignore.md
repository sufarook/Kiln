# @Ignore

Excludes a property from the database schema entirely. An ignored property is not present in `CREATE TABLE`, not bound on insert/update, and not read from the cursor on queries.

## Signature

```kotlin
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Ignore
```

## When to use it

- **Computed properties** — values derived from other fields at runtime.
- **Transient UI state** — selection state, expansion state, etc.
- **Unsupported types** — when a property holds a type that Krate cannot map to SQLite (e.g. `List<String>`). Serialize it yourself and store the serialized form in a supported column separately.

## Example

```kotlin
@DbEntity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val priority: Int = 1,
    @Ignore val isSelected: Boolean = false,    // UI state, not persisted
    @Ignore val displayPriority: String = ""    // computed label, not persisted
)
```

When `Task` rows are read from the database, `isSelected` and `displayPriority` receive their Kotlin default values. You set them in your UI layer after loading:

```kotlin
val tasks = taskRepo.findAll().map { task ->
    task.copy(
        displayPriority = when (task.priority) {
            3 -> "High"
            2 -> "Medium"
            else -> "Low"
        }
    )
}
```

!!! note
    `@Ignore` works with Kotlin's default parameter values. As long as the ignored property has a default, the data class remains constructable from cursor data without providing that value.
