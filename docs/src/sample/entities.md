# Entity Definitions

Three annotated data classes — this is all the schema code you write.

```kotlin title="Entities.kt"
@DbEntity(tableName = "projects")
data class Project(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    @Column(name = "hex_color") val color: String = "#4CAF50",
    @Column(name = "is_archived", index = true) val isArchived: Boolean = false,
    @Column(name = "created_at") val createdAt: String = ""
)

@DbEntity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @Column(name = "project_id", index = true) val projectId: Long,  // (1)!
    val title: String,
    val notes: String = "",
    val priority: Int = 1,        // 1 = low  2 = medium  3 = high
    val status: String = "TODO",  // TODO | IN_PROGRESS | DONE
    @Column(name = "is_completed") val isCompleted: Boolean = false,
    @Column(name = "due_date") val dueDate: String = ""
)

@DbEntity(tableName = "checklist_items")
data class ChecklistItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @Column(name = "task_id", index = true) val taskId: Long,  // (1)!
    val text: String,
    @Column(name = "is_done") val isDone: Boolean = false
)
```

1. `index = true` on foreign key columns generates `CREATE INDEX` — makes `findWhere { TaskColumns.projectId eq id }` fast without a full table scan.

## Design notes

**Indexes on FK columns** — `project_id` and `task_id` both have `index = true`. Without indexes, every `findWhere` on these columns performs a full table scan. With indexes, lookups are O(log n).

**Dates as `String`** — `created_at` and `due_date` are stored as ISO-8601 strings (e.g. `"2024-09-15T10:30:00Z"`). This keeps the schema portable and avoids type-change migrations when date handling changes.

**Status as `String`** — `TODO | IN_PROGRESS | DONE` is stored as plain text. You validate the set of valid values in your UI or domain layer, not in the schema.

**All fields have defaults** — this makes `copy()` ergonomic for partial updates:

```kotlin
// Update only the status — all other fields unchanged
taskRepo.update(task.copy(status = "IN_PROGRESS"))
```

## Generated output

After a build, DelightCRUD generates:

- `ProjectRepository` + `ProjectColumns`
- `TaskRepository` + `TaskColumns`
- `ChecklistItemRepository` + `ChecklistItemColumns`
