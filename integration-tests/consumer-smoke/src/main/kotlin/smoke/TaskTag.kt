package smoke

import io.github.sufarook.kiln.annotations.DbEntity
import io.github.sufarook.kiln.annotations.PrimaryKey
import io.github.sufarook.kiln.annotations.Relation

/**
 * A junction table whose composite primary key `(taskId, tagId)` is also two foreign
 * keys. Exercises `@Relation` on composite-key columns end-to-end through the real KSP
 * pipeline — the generated `findByTask`/`findByTag`/`deleteByTask`/`deleteByTag` helpers
 * only exist if the processor extracts `@Relation` from primary-key properties.
 *
 * Tested here against the locally-published processor for CI verification.
 */
@DbEntity(tableName = "task_tags")
data class TaskTag(
    @PrimaryKey @Relation(cascade = true) val taskId: Long,
    @PrimaryKey @Relation(cascade = true) val tagId: Long
)
