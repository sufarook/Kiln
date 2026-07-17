# DSL Queries

Use the generated `<Entity>Columns` object inside `findWhere`, `observeWhere`, `deleteWhere`, and `count`. All values are bound as `?` parameters — SQL injection is structurally impossible.

## Basic filter

```kotlin
// All tasks for a project
val tasks = taskRepo.findWhere { TaskColumns.projectId eq projectId }
```

## Compound predicates

```kotlin
// High-priority incomplete tasks
val urgent = taskRepo.findWhere {
    (TaskColumns.priority eq 3) and (TaskColumns.isCompleted eq false)
}

// Tasks that are done OR have no due date
val relaxed = taskRepo.findWhere {
    (TaskColumns.isCompleted eq true) or TaskColumns.dueDate.isNull()
}

// Tasks that are NOT done
val open = taskRepo.findWhere {
    not(TaskColumns.isCompleted eq true)
}
```

## `inList`

```kotlin
val activeTasks = taskRepo.findWhere {
    TaskColumns.status inList listOf("TODO", "IN_PROGRESS")
}
```

## Null checks

```kotlin
// Tasks with a due date set
val scheduled = taskRepo.findWhere { TaskColumns.dueDate.isNotNull() }

// Tasks with no due date
val unscheduled = taskRepo.findWhere { TaskColumns.dueDate.isNull() }
```

## Text search

```kotlin
// Case-sensitive LIKE search
val results = taskRepo.findWhere { TaskColumns.title like "%$query%" }
```

!!! note
    SQLite `LIKE` is case-insensitive for ASCII characters by default. For full Unicode case-insensitive search you'll need a custom collation, which is beyond the scope of the DSL.

## `observeWhere` — reactive filter

```kotlin
// Reactive incomplete checklist items for a task — re-emits on every write
val pendingItems: Flow<List<ChecklistItem>> = checklistRepo.observeWhere {
    (ChecklistItemColumns.taskId eq taskId) and
    (ChecklistItemColumns.isDone eq false)
}
```

## `count`

```kotlin
// Count incomplete tasks in a project (e.g. for a badge)
val openCount: Long = taskRepo.count {
    (TaskColumns.projectId eq projectId) and
    (TaskColumns.isCompleted eq false)
}

// Total row count (no predicate)
val totalProjects: Long = projectRepo.count()
```

## `deleteWhere`

```kotlin
// Bulk delete all done checklist items for a task
checklistRepo.deleteWhere {
    (ChecklistItemColumns.taskId eq taskId) and
    (ChecklistItemColumns.isDone eq true)
}

// Delete all tasks for a project
taskRepo.deleteWhere { TaskColumns.projectId eq projectId }
```

## Full ViewModel example

```kotlin title="TaskDetailViewModel.kt"
class TaskDetailViewModel(
    private val taskRepo: TaskRepository,
    private val checklistRepo: ChecklistItemRepository,
    private val taskId: Long
) : ViewModel() {

    val checklist: StateFlow<List<ChecklistItem>> = checklistRepo
        .observeWhere { ChecklistItemColumns.taskId eq taskId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val progress: StateFlow<Float> = checklist
        .map { items ->
            if (items.isEmpty()) 0f
            else items.count { it.isDone }.toFloat() / items.size
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0f)

    fun toggleItem(item: ChecklistItem) {
        viewModelScope.launch {
            checklistRepo.update(item.copy(isDone = !item.isDone))
        }
    }

    fun addItem(text: String) {
        viewModelScope.launch {
            checklistRepo.insert(ChecklistItem(taskId = taskId, text = text))
        }
    }

    fun clearCompleted() {
        viewModelScope.launch {
            checklistRepo.deleteWhere {
                (ChecklistItemColumns.taskId eq taskId) and
                (ChecklistItemColumns.isDone eq true)
            }
        }
    }
}
```

See [DSL Operators](../reference/dsl-operators.md) for the complete operator reference.
