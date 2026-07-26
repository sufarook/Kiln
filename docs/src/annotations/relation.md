# @Relation

Marks a foreign-key property and triggers generation of `findBy<Parent>`, `observeBy<Parent>`, and `deleteBy<Parent>` convenience methods on the child repository.

## Signature

```kotlin
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Relation(
    val cascade: Boolean = false
)
```

## Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `cascade` | `Boolean` | `false` | Documents that this FK participates in a cascade delete. Has no runtime effect — the cascade is performed manually by calling `deleteBy<Parent>` before `delete(parentId)`. |

---

## Naming convention

Kiln infers the parent entity name from the property name by stripping a trailing `Id` suffix and capitalising the result:

| Property name | Inferred parent | Generated methods |
|---------------|----------------|-------------------|
| `projectId` | `Project` | `findByProject`, `observeByProject`, `deleteByProject` |
| `userId` | `User` | `findByUser`, `observeByUser`, `deleteByUser` |
| `categoryId` | `Category` | `findByCategory`, `observeByCategory`, `deleteByCategory` |
| `ownerId` (no entity named `Owner`) | `Owner` (used verbatim) | `findByOwner`, `observeByOwner`, `deleteByOwner` |

If the property name does not end in `Id`, the whole name is capitalised:

| Property name | Inferred parent |
|---------------|----------------|
| `parent` | `Parent` |
| `author` | `Author` |

---

## Example

```kotlin
@DbEntity(tableName = "projects")
data class Project(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)

@DbEntity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    @Relation(cascade = true) val projectId: Long = 0
)
```

Kiln generates these extra methods on `TaskRepository`:

```kotlin
suspend fun findByProject(projectId: Long): List<Task>
fun observeByProject(projectId: Long): Flow<List<Task>>
suspend fun deleteByProject(projectId: Long)
```

---

## Cascade delete pattern

`cascade = true` is documentation only. Perform the cascade explicitly before deleting the parent:

```kotlin
// Wrap in a transaction so both tables notify their observers atomically
driver.withTransaction {
    taskRepo.deleteByProject(projectId)   // removes all child rows
    projectRepo.delete(projectId)         // removes the parent row
}
```

See [`driver.withTransaction`](../reference/api.md#transactions) for the transaction API.

---

## Reactive join

Combine `observeBy<Parent>` with `observeAll()` on the parent to build a live `Pair<Project?, List<Task>>` stream:

```kotlin
combine(
    projectRepo.observeAll(),
    taskRepo.observeByProject(projectId)
) { projects, tasks ->
    projects.find { it.id == projectId } to tasks
}.collect { (project, tasks) ->
    // re-emits whenever either table changes
}
```
