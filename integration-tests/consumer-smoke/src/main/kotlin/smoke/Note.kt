package smoke

import io.github.sufarook.kiln.annotations.Column
import io.github.sufarook.kiln.annotations.DbEntity
import io.github.sufarook.kiln.annotations.PrimaryKey

/**
 * A minimal entity exercising the annotations a first-time user reaches for.
 * The processor must turn this into `NoteRepository` + `NoteColumns`.
 */
@DbEntity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    @Column(name = "is_pinned", index = true) val isPinned: Boolean = false,
)
