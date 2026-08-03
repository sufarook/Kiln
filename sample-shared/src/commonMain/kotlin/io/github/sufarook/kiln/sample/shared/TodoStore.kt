package io.github.sufarook.kiln.sample.shared

import app.cash.sqldelight.db.SqlDriver
import io.github.sufarook.kiln.runtime.eq
import io.github.sufarook.kiln.runtime.like
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Platform-agnostic todo logic — the whole app's data layer lives in commonMain.
 * Android passes AndroidDatabaseDriverFactory(context).create("todos.db"),
 * iOS passes IosDatabaseDriverFactory().create("todos.db").
 */
class TodoStore(driver: SqlDriver) {

    private val repo = TodoSharedRepository(driver)

    init {
        repo.createTable() // creates on first launch, auto-migrates on upgrades
    }

    /** Reactive stream — emits on every insert/update/delete, high-priority first. */
    fun observeAll(): Flow<List<TodoShared>> = repo.observeAll().map { list ->
        list.sortedWith(compareByDescending<TodoShared> { it.priority }.thenBy { it.id })
    }

    suspend fun add(title: String, highPriority: Boolean = false) {
        repo.insert(TodoShared(title = title, priority = if (highPriority) 1 else 0))
    }

    suspend fun toggle(todo: TodoShared) {
        repo.update(todo.copy(isCompleted = !todo.isCompleted))
    }

    suspend fun rename(todo: TodoShared, newTitle: String) {
        repo.update(todo.copy(title = newTitle))
    }

    suspend fun remove(id: Long) {
        repo.delete(id)
    }

    suspend fun all(): List<TodoShared> = repo.findAll().sortedWith(compareByDescending<TodoShared> { it.priority }.thenBy { it.id })

    // ── Type-safe query DSL in action — no SQL strings ──────────────────────────

    suspend fun clearCompleted() {
        repo.deleteWhere { isCompleted eq true }
    }

    suspend fun highPriorityOpen(): List<TodoShared> = repo.findWhere { (priority eq 1) and (isCompleted eq false) }

    suspend fun search(prefix: String): List<TodoShared> = repo.findWhere { title like "$prefix%" }

    suspend fun pendingCount(): Long = repo.count { isCompleted eq false }
}
