package io.github.sufarook.kiln.annotations

/**
 * Marks a FK property as a relationship to another `@DbEntity`.
 *
 * ```kotlin
 * @DbEntity(tableName = "tasks")
 * data class Task(
 *     @PrimaryKey(autoGenerate = true) val id: Long = 0,
 *     val title: String,
 *     @Relation val projectId: Long = 0   // FK to Project
 * )
 * ```
 *
 * The processor infers the parent entity name from the property name by stripping
 * the trailing `Id` suffix (e.g. `projectId` → `Project`).
 *
 * Generated methods on `TaskRepository`:
 * - `findByProject(projectId: Long): List<Task>`
 * - `observeByProject(projectId: Long): Flow<List<Task>>`
 * - `deleteByProject(projectId: Long)`
 *
 * @param cascade When `true`, a KDoc hint is added to `deleteByProject` noting that
 *   callers should invoke it before deleting the parent. Full automatic cascade
 *   (transactional) is provided in Phase 5b.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Relation(
    val cascade: Boolean = false
)
