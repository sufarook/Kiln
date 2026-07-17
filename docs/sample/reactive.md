# Reactive Queries

## How it works

Every generated repository exposes `observeAll()` returning `Flow<List<T>>`. Internally it wraps SQLDelight's `Query.Listener` — when any write operation (`insert`, `update`, `delete`, `deleteWhere`) completes, the listener is notified and the flow re-emits the updated list.

No polling. No manual `invalidate()` calls. No `postValue()`.

## Basic observation

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        projectRepo.observeAll().collect { projects ->
            adapter.submitList(projects)
        }
    }
}
```

!!! tip "Always use `repeatOnLifecycle`"
    Collecting inside `repeatOnLifecycle(STARTED)` cancels the collection when the UI goes to background and restarts it when it returns. Plain `launch { collect { } }` keeps collecting even when the UI is not visible.

## ViewModel pattern

The recommended pattern is to convert the flow to `StateFlow` inside a `ViewModel` using `stateIn`:

```kotlin title="ProjectListViewModel.kt"
class ProjectListViewModel(
    private val projectRepo: ProjectRepository
) : ViewModel() {

    // Filter out archived projects; re-emits whenever the projects table changes
    val activeProjects: StateFlow<List<Project>> = projectRepo
        .observeAll()
        .map { list -> list.filter { !it.isArchived } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    fun createProject(name: String, color: String) {
        viewModelScope.launch {
            projectRepo.insert(Project(name = name, color = color))
            // activeProjects updates automatically — no manual refresh needed
        }
    }

    fun archiveProject(project: Project) {
        viewModelScope.launch {
            projectRepo.update(project.copy(isArchived = true))
        }
    }
}
```

Collect in the Activity or Fragment:

```kotlin title="ProjectListActivity.kt"
private val viewModel: ProjectListViewModel by viewModels()

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.activeProjects.collect { projects ->
                adapter.submitList(projects)
            }
        }
    }
}
```

## Reactive filtered queries

`observeWhere { }` applies a DSL predicate and re-emits on every table change:

```kotlin title="TaskDetailViewModel.kt"
class TaskDetailViewModel(
    private val checklistRepo: ChecklistItemRepository,
    private val taskId: Long
) : ViewModel() {

    // Re-emits whenever any checklist item is added, toggled, or deleted
    val checklist: StateFlow<List<ChecklistItem>> = checklistRepo
        .observeWhere { ChecklistItemColumns.taskId eq taskId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Derived flow — progress percentage from 0.0 to 1.0
    val progress: StateFlow<Float> = checklist
        .map { items ->
            if (items.isEmpty()) 0f
            else items.count { it.isDone }.toFloat() / items.size
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0f)

    fun toggleItem(item: ChecklistItem) {
        viewModelScope.launch {
            checklistRepo.update(item.copy(isDone = !item.isDone))
            // checklist and progress both re-emit automatically
        }
    }
}
```

## Cross-table reactivity

All repositories that share the same `SqlDriver` instance share the same listener channel. A write to `taskRepo` triggers re-emission on all active `taskRepo.observeAll()` and `taskRepo.observeWhere()` collectors — it does **not** trigger collectors on `projectRepo` or `checklistRepo`.

To react to changes across tables, combine flows:

```kotlin
// React when either tasks or checklist items change
combine(
    taskRepo.observeWhere { TaskColumns.projectId eq projectId },
    checklistRepo.observeAll()
) { tasks, allItems ->
    tasks.map { task ->
        val items = allItems.filter { it.taskId == task.id }
        TaskWithChecklist(task, items)
    }
}.collect { ... }
```
