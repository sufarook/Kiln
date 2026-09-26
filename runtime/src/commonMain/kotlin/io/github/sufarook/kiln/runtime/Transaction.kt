package io.github.sufarook.kiln.runtime

import app.cash.sqldelight.SuspendingTransacterImpl
import app.cash.sqldelight.db.SqlDriver
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Executes [block] inside a single SQLite transaction.
 *
 * All INSERT / UPDATE / DELETE operations performed by Kiln repositories
 * inside [block] have their [SqlDriver.notifyListeners] calls deferred. After
 * the outermost COMMIT, each dirty table is notified exactly once — reactive
 * flows therefore receive one emission per transaction rather than one per
 * operation. This holds whoever opened the outermost transaction: a block run
 * inside a transaction opened by other code on the same driver joins it, and
 * notifies only once that transaction commits.
 *
 * Blocks nest: a [withTransaction] inside another joins it, and only the
 * outermost one commits.
 *
 * On any exception the transaction is rolled back and the exception re-thrown.
 * No notifications are sent for a rolled-back transaction.
 *
 * The transaction holds one thread of [context] until it completes — SQLite
 * transactions belong to the thread that opened them, so a suspended block
 * resumes on that thread, and repository calls inside the block run there too.
 * Pass `Dispatchers.IO` for a transaction that waits on I/O, so it doesn't hold
 * one of `Dispatchers.Default`'s few threads.
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
    val enclosing = currentCoroutineContext()[TransactionThread]
    val joined = enclosing?.dispatcherFor(this)
    if (joined != null) {
        // Nested inside a Kiln transaction on this driver: join it on its own thread.
        withContext(joined) { runTransaction(block) }
        return
    }
    withContext(context) {
        // runBlocking pins every continuation of the block to this thread. The
        // caller's Job is passed on so cancelling the caller still cancels the block.
        runBlocking(coroutineContext[Job] ?: EmptyCoroutineContext) {
            val pinned = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
            withContext(TransactionThread(this@withTransaction, pinned, enclosing)) {
                runTransaction(block)
            }
        }
    }
}

/**
 * Runs [block] like `withContext(context, block)`, unless a transaction is open:
 * - inside a [withTransaction] on this driver, on that transaction's thread;
 * - inside a transaction opened by other code on the current thread, in place,
 *   so the work joins that transaction.
 *
 * Called by generated repository code — not intended for direct use.
 */
suspend fun <T> SqlDriver.withTransactionAwareContext(
    context: CoroutineContext,
    block: suspend CoroutineScope.() -> T
): T {
    val pinned = currentCoroutineContext()[TransactionThread]?.dispatcherFor(this)
    return when {
        pinned != null -> withContext(pinned, block)
        currentTransaction() != null -> coroutineScope(block)
        else -> withContext(context, block)
    }
}

/**
 * Inside a transaction, records [tableName] as dirty so its notification is sent
 * once, after the outermost commit — or not at all if the transaction rolls back.
 * Outside a transaction, calls [SqlDriver.notifyListeners] immediately
 * (preserving the existing per-operation behaviour).
 *
 * Called by generated repository code — not intended for direct use.
 */
suspend fun SqlDriver.notifyOrDefer(tableName: String) {
    KilnTransacter(this).notifyTable(tableName)
}

private suspend fun SqlDriver.runTransaction(block: suspend () -> Unit) {
    var blockFailure: Throwable? = null
    try {
        KilnTransacter(this).transaction {
            try {
                block()
            } catch (e: Throwable) {
                blockFailure = e
                throw e
            }
        }
    } catch (e: Throwable) {
        // SQLDelight ends the transaction in a finally block, so a failing ROLLBACK
        // replaces the block's exception. Surface the block's — it is the cause.
        val cause = blockFailure
        if (cause != null && cause !== e) {
            cause.addSuppressed(e)
            throw cause
        }
        throw e
    }
}

/** SQLDelight's transacter over a borrowed driver — the one transaction authority. */
private class KilnTransacter(driver: SqlDriver) : SuspendingTransacterImpl(driver) {
    fun notifyTable(tableName: String) = notifyQueries(TableIdentifiers.of(tableName)) { emit -> emit(tableName) }
}

/**
 * The thread each active Kiln transaction is pinned to, per driver. Nested
 * transactions and generated repository calls look it up to run where their
 * driver's transaction can be used and ended.
 */
private class TransactionThread(
    private val driver: SqlDriver,
    private val dispatcher: CoroutineDispatcher,
    private val enclosing: TransactionThread?
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TransactionThread>

    fun dispatcherFor(driver: SqlDriver): CoroutineDispatcher? = if (driver === this.driver) dispatcher else enclosing?.dispatcherFor(driver)
}

/**
 * A stable identifier per table name, for SQLDelight's per-transaction
 * deduplication. `hashCode()` won't do: two table names can share one, and the
 * second table's notification would be silently dropped.
 */
@OptIn(ExperimentalAtomicApi::class)
private object TableIdentifiers {
    // Arbitrary base, away from zero, where hand-written identifiers tend to sit.
    private const val BASE = 0x4B494C4E // "KILN"
    private val ids = AtomicReference(emptyMap<String, Int>())

    fun of(tableName: String): Int {
        while (true) {
            val current = ids.load()
            current[tableName]?.let { return it }
            val next = current + (tableName to BASE + current.size)
            if (ids.compareAndSet(current, next)) return next.getValue(tableName)
        }
    }
}
