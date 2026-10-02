package io.github.sufarook.kiln.runtime

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
 * inside [block] have their [KilnDriver.notifyListeners] calls deferred. After
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
 */
suspend fun KilnDriver.withTransaction(
    context: CoroutineContext = Dispatchers.Default,
    block: suspend () -> Unit
) {
    val enclosing = currentCoroutineContext()[TransactionThread]
    val joined = enclosing?.dispatcherFor(this)
    if (joined != null) {
        withContext(joined) { runTransaction(block) }
        return
    }
    withContext(context) {
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
suspend fun <T> KilnDriver.withTransactionAwareContext(
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
 * Outside a transaction, calls [KilnDriver.notifyListeners] immediately
 * (preserving the existing per-operation behaviour).
 *
 * Called by generated repository code — not intended for direct use.
 */
suspend fun KilnDriver.notifyOrDefer(tableName: String) {
    val txCtx = currentCoroutineContext()[TransactionThread]
    if (txCtx != null && txCtx.ownsDriver(this)) {
        txCtx.defer(tableName)
    } else if (currentTransaction() != null) {
        // Inside an external transaction — cannot defer, best effort notify.
        notifyListeners(tableName)
    } else {
        notifyListeners(tableName)
    }
}

private suspend fun KilnDriver.runTransaction(block: suspend () -> Unit) {
    val txCtx = currentCoroutineContext()[TransactionThread]!!
    val tx = newTransaction()
    try {
        block()
        tx.endTransaction(successful = true)
        if (tx.enclosingTransaction == null) txCtx.flushIfOutermost(this)
    } catch (e: Throwable) {
        try {
            tx.endTransaction(successful = false)
        } catch (rollbackFailure: Throwable) {
            e.addSuppressed(rollbackFailure)
        }
        txCtx.clearDeferred()
        throw e
    }
}

private class TransactionThread(
    private val driver: KilnDriver,
    private val dispatcher: CoroutineDispatcher,
    private val enclosing: TransactionThread?
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TransactionThread>

    private val deferred = mutableSetOf<String>()
    private var depth = 0

    init {
        depth = (enclosing?.depth ?: 0) + 1
    }

    fun dispatcherFor(driver: KilnDriver): CoroutineDispatcher? = if (driver === this.driver) dispatcher else enclosing?.dispatcherFor(driver)

    fun ownsDriver(driver: KilnDriver): Boolean = driver === this.driver

    fun defer(tableName: String) {
        if (enclosing?.ownsDriver(driver) == true) {
            enclosing.defer(tableName)
        } else {
            deferred.add(tableName)
        }
    }

    fun flushIfOutermost(driver: KilnDriver) {
        if (enclosing?.ownsDriver(driver) == true) return
        val tables = deferred.toSet()
        deferred.clear()
        tables.forEach { driver.notifyListeners(it) }
    }

    fun clearDeferred() {
        deferred.clear()
    }
}
