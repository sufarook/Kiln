package io.github.sufarook.kiln.runtime

import app.cash.sqldelight.db.SqlDriver
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * Coroutine context element that carries the set of table names written during
 * an active [withTransaction] block. Repository methods detect this via
 * [kotlin.coroutines.coroutineContext] and defer [SqlDriver.notifyListeners]
 * until the transaction commits successfully.
 */
class KilnTransactionContext(
    internal val dirtyTables: MutableSet<String> = mutableSetOf()
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<KilnTransactionContext>
    override val key: CoroutineContext.Key<*> = Key
}

/**
 * Executes [block] inside a single SQLite transaction.
 *
 * All INSERT / UPDATE / DELETE operations performed by Kiln repositories
 * inside [block] have their [SqlDriver.notifyListeners] calls deferred. After
 * the COMMIT, each dirty table is notified exactly once — reactive flows
 * therefore receive one emission per transaction rather than one per operation.
 *
 * On any exception the transaction is rolled back and the exception re-thrown.
 * No notifications are sent for a rolled-back transaction.
 *
 * ```kotlin
 * // Cascade delete with a single Flow emission per affected table:
 * driver.withTransaction {
 *     taskRepo.deleteByProject(projectId)
 *     projectRepo.delete(projectId)
 * }
 *
 * // Batch insert — observers see all rows appear at once:
 * driver.withTransaction {
 *     tasks.forEach { taskRepo.insert(it) }
 * }
 * ```
 */
suspend fun SqlDriver.withTransaction(
    context: CoroutineContext = Dispatchers.Default,
    block: suspend () -> Unit
) {
    val txCtx = KilnTransactionContext()
    withContext(context + txCtx) {
        execute(null, "BEGIN TRANSACTION", 0)
        var committed = false
        try {
            block()
            execute(null, "COMMIT", 0)
            committed = true
        } finally {
            if (!committed) {
                // Swallow rollback errors so the original exception propagates cleanly.
                runCatching { execute(null, "ROLLBACK", 0) }
            }
        }
        if (committed && txCtx.dirtyTables.isNotEmpty()) {
            notifyListeners(*txCtx.dirtyTables.toTypedArray())
        }
    }
}

/**
 * If the current coroutine is executing inside a [withTransaction] block,
 * records [tableName] as dirty so the notification is deferred to after commit.
 * Outside a transaction, calls [SqlDriver.notifyListeners] immediately
 * (preserving the existing per-operation behaviour).
 *
 * Called by generated repository code — not intended for direct use.
 */
suspend fun SqlDriver.notifyOrDefer(tableName: String) {
    val txCtx = currentCoroutineContext()[KilnTransactionContext]
    if (txCtx != null) {
        txCtx.dirtyTables.add(tableName)
    } else {
        notifyListeners(tableName)
    }
}
