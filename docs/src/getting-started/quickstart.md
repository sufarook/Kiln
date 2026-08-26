# Quickstart

Get a working repository in under five minutes.

## 1. Apply the plugin

In your module's `build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android") version "2.3.20"
    id("io.github.sufarook.kiln") version "1.0.0-alpha04" // (1)!
}

dependencies {
    implementation("io.github.sufarook.kiln:annotations:1.0.0-alpha04")
    implementation("io.github.sufarook.kiln:runtime:1.0.0-alpha04")
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

**Rebuild the project.** Kiln generates `NoteRepository` and `NoteColumns` in `build/generated/`.

!!! tip
    If the IDE shows an unresolved reference on `NoteRepository`, run **Build → Make Project** once. The class is generated during compilation — the IDE resolves it after the first successful build.

## 3. Initialize

Create the schema at app startup. It is safe to call on every launch — tables are created if missing, and the schema auto-migrates if the entity changed.

With a single entity, `createTable()` on the repository is the shortest thing that works. Once you have several, use the generated `KilnSchema.createAll(driver)` instead — it covers every `@DbEntity` in the module, so adding one later doesn't mean editing your startup code.

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
