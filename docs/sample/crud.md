# CRUD Operations

All write and read methods are `suspend`. Call them from a `CoroutineScope`.

## Insert

Pass `id = 0` for auto-generated primary keys — SQLite assigns the real id.

```kotlin
lifecycleScope.launch {
    // Insert a project
    projectRepo.insert(
        Project(name = "Website Redesign", color = "#1976D2")
    )

    // Insert a task under project id 1
    taskRepo.insert(
        Task(projectId = 1L, title = "Write copy", priority = 2)
    )

    // Insert checklist items for task id 1
    checklistRepo.insert(ChecklistItem(taskId = 1L, text = "Hero section"))
    checklistRepo.insert(ChecklistItem(taskId = 1L, text = "Features section"))
    checklistRepo.insert(ChecklistItem(taskId = 1L, text = "Pricing table"))
}
```

## Read

```kotlin
lifecycleScope.launch {
    val project: Project?      = projectRepo.findById(1L)   // null if not found
    val allTasks: List<Task>   = taskRepo.findAll()
    val item: ChecklistItem?   = checklistRepo.findById(3L)
}
```

## Update

Use `data class copy()` to describe only what changed. All other fields keep their current values.

```kotlin
lifecycleScope.launch {
    val task = taskRepo.findById(1L) ?: return@launch

    // Change status and priority, leave everything else unchanged
    taskRepo.update(task.copy(status = "IN_PROGRESS", priority = 3))
}
```

## Delete

```kotlin
lifecycleScope.launch {
    checklistRepo.delete(itemId)   // delete by primary key
    taskRepo.delete(task.id)
}
```

!!! note "No return value from insert"
    `insert` does not return the generated id. If you need the id immediately after insert, use `findAll().last().id` or query by a unique field. This limitation will be addressed in a future release.
