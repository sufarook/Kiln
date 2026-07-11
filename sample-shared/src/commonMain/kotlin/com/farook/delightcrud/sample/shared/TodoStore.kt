package com.farook.delightcrud.sample.shared

import app.cash.sqldelight.db.SqlDriver
import com.farook.delightcrud.runtime.eq
import com.farook.delightcrud.runtime.like
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Platform-agnostic todo logic — the whole app's data layer lives in commonMain.
 * Android passes AndroidDatabaseDriverFactory(context).create("todos.db"),
 * iOS passes IosDatabaseDriverFactory().create("todos.db").
 */
class TodoStore(driver: SqlDriver) {

    private val repo = TodoRepository(driver)

    init {
        repo.createTable()  // creates on first launch, auto-migrates on upgrades
    }

    /** Reactive stream — emits on every insert/update/delete, high-priority first. */
    fun observeAll(): Flow<List<Todo>> = repo.observeAll().map { list ->
        list.sortedWith(compareByDescending<Todo> { it.priority }.thenBy { it.id })
    }

    suspend fun add(title: String, highPriority: Boolean = false) {
        repo.insert(Todo(title = title, priority = if (highPriority) 1 else 0))
    }

    suspend fun toggle(todo: Todo) {
        repo.update(todo.copy(isCompleted = !todo.isCompleted))
    }

    suspend fun rename(todo: Todo, newTitle: String) {
        repo.update(todo.copy(title = newTitle))
    }

    suspend fun remove(id: Long) {
        repo.delete(id)
    }

    suspend fun all(): List<Todo> =
        repo.findAll().sortedWith(compareByDescending<Todo> { it.priority }.thenBy { it.id })

    // ── Type-safe query DSL in action — no SQL strings ──────────────────────────

    suspend fun clearCompleted() {
        repo.deleteWhere { isCompleted eq true }
    }

    suspend fun highPriorityOpen(): List<Todo> =
        repo.findWhere { (priority eq 1) and (isCompleted eq false) }

    suspend fun search(prefix: String): List<Todo> =
        repo.findWhere { title like "$prefix%" }

    suspend fun pendingCount(): Long = repo.count()
}
