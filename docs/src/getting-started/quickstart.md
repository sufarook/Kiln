# Quickstart

Get a working repository in under five minutes.

## 1. Apply the plugin

In your module's `build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android") version "2.3.20"
    id("com.farook.delightcrud") version "1.0.0-alpha01" // (1)!
}

dependencies {
    implementation("com.farook.delightcrud:annotations:1.0.0-alpha01")
    implementation("com.farook.delightcrud:runtime:1.0.0-alpha01")
    implementation("app.cash.sqldelight:android-driver:2.3.2")
}
```

1. The plugin automatically applies KSP and wires the generated sources. No manual KSP configuration needed.

## 2. Annotate a data class

```kotlin
@DbEntity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val body: String = "",
    @Column(name = "is_pinned") val isPinned: Boolean = false
)
```

**Rebuild the project.** DelightCRUD generates `NoteRepository` and `NoteColumns` in `build/generated/`.

!!! tip
    If the IDE shows an unresolved reference on `NoteRepository`, run **Build → Make Project** once. The class is generated during compilation — the IDE resolves it after the first successful build.

## 3. Initialize

Call `createTable()` once per repository at app startup. It is safe to call on every launch — it creates the table if it doesn't exist and auto-migrates the schema if the entity changed.

```kotlin
class MyApp : Application() {
    lateinit var noteRepo: NoteRepository

    override fun onCreate() {
        super.onCreate()
        val driver = AndroidDatabaseDriverFactory(this).create("myapp.db")
        noteRepo = NoteRepository(driver).also { it.createTable() }
    }
}
```

## 4. Write and observe

```kotlin
class NotesActivity : AppCompatActivity() {

    private val repo get() = (application as MyApp).noteRepo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Observe — re-emits automatically after every insert/update/delete
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.observeAll().collect { notes ->
                    adapter.submitList(notes)
                }
            }
        }

        // Insert
        btnAdd.setOnClickListener {
            lifecycleScope.launch {
                repo.insert(Note(title = etTitle.text.toString()))
            }
        }

        // Update (use data class copy for partial changes)
        fun togglePin(note: Note) {
            lifecycleScope.launch {
                repo.update(note.copy(isPinned = !note.isPinned))
            }
        }

        // Delete
        fun delete(note: Note) {
            lifecycleScope.launch { repo.delete(note.id) }
        }
    }
}
```

!!! success "That's it"
    You now have a fully reactive, type-safe, auto-migrating SQLite repository with no SQL written and no version numbers to track.

## Next steps

- [Full installation guide](installation.md) — KMP setup, Maven Central coordinates
- [Annotations reference](../annotations/index.md) — all annotation parameters explained
- [Sample app](../sample/index.md) — realistic multi-table example with DSL queries
