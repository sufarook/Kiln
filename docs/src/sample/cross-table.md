# Cross-table Loading

DelightCRUD generates one repository per entity — it does not generate SQL JOIN queries. For data that spans multiple tables, create a store or service class that coordinates across repositories.

## Data models

Define aggregate types that hold related entities together:

```kotlin title="Models.kt"
data class ProjectWithTasks(
    val project: Project,
    val tasks: List<Task>
)

data class TaskWithChecklist(
    val task: Task,
    val items: List<ChecklistItem>
)
```

## TaskStore

A store class exposes both suspend (one-shot) and reactive (flow) methods:

```kotlin title="TaskStore.kt"
class TaskStore(
    private val projectRepo: ProjectRepository,
    private val taskRepo: TaskRepository,
    private val checklistRepo: ChecklistItemRepository
) {

    // ── One-shot loaders ──────────────────────────────────────────────────────

    suspend fun projectWithTasks(projectId: Long): ProjectWithTasks {
        val project = projectRepo.findById(projectId)
            ?: error("Project $projectId not found")
        val tasks = taskRepo.findWhere { TaskColumns.projectId eq projectId }
        return ProjectWithTasks(project, tasks)
    }

    suspend fun taskWithChecklist(taskId: Long): TaskWithChecklist {
        val task = taskRepo.findById(taskId)
            ?: error("Task $taskId not found")
        val items = checklistRepo.findWhere { ChecklistItemColumns.taskId eq taskId }
        return TaskWithChecklist(task, items)
    }

    // ── Reactive loaders ──────────────────────────────────────────────────────

    fun observeProjectTasks(projectId: Long): Flow<List<Task>> =
        taskRepo.observeWhere { TaskColumns.projectId eq projectId }

    fun observeChecklist(taskId: Long): Flow<List<ChecklistItem>> =
        checklistRepo.observeWhere { ChecklistItemColumns.taskId eq taskId }

    // ── Coordinated writes ────────────────────────────────────────────────────

    /** Mark a task done and tick off every unfinished checklist item. */
    suspend fun completeTask(task: Task) {
        taskRepo.update(task.copy(isCompleted = true, status = "DONE"))
        val items = checklistRepo.findWhere { ChecklistItemColumns.taskId eq task.id }
        items.filter { !it.isDone }.forEach {
            checklistRepo.update(it.copy(isDone = true))
        }
    }

    /**
     * Archive a project and delete all its child data.
     *
     * DelightCRUD does not enforce foreign key constraints automatically.
     * This method handles the cascade explicitly.
     */
    suspend fun archiveProject(project: Project) {
        // 1. Find and delete all checklist items for each task
        val tasks = taskRepo.findWhere { TaskColumns.projectId eq project.id }
        tasks.forEach { task ->
            checklistRepo.deleteWhere { ChecklistItemColumns.taskId eq task.id }
        }

        // 2. Delete all tasks for this project
        taskRepo.deleteWhere { TaskColumns.projectId eq project.id }

        // 3. Archive the project
        projectRepo.update(project.copy(isArchived = true))
    }
}
```

## Using TaskStore in a ViewModel

```kotlin title="ProjectDetailViewModel.kt"
class ProjectDetailViewModel(
    private val store: TaskStore,
    private val projectId: Long
) : ViewModel() {

    val tasks: StateFlow<List<Task>> = store
        .observeProjectTasks(projectId)
        .map { tasks -> tasks.sortedWith(compareByDescending { it.priority }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addTask(title: String, priority: Int) {
        viewModelScope.launch {
            taskRepo.insert(Task(projectId = projectId, title = title, priority = priority))
        }
    }

    fun completeTask(task: Task) {
        viewModelScope.launch { store.completeTask(task) }
    }

    fun archiveProject(project: Project) {
        viewModelScope.launch { store.archiveProject(project) }
    }
}
```

!!! warning "Foreign keys are not auto-enforced"
    SQLite foreign key constraints are not active by default. DelightCRUD does not emit `PRAGMA foreign_keys = ON`. Handle cascades explicitly as shown in `archiveProject` above. This keeps the behaviour visible and avoids silent data loss from unexpected deletions.
