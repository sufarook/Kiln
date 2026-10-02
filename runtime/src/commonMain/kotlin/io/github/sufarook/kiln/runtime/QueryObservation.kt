package io.github.sufarook.kiln.runtime

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Runs [query] once immediately, then again every time the driver is notified that
 * [tableName] changed (generated repositories call notifyListeners after each write).
 *
 * Used by generated observeAll() — not intended to be called directly.
 */
fun <T> observeQuery(
    driver: KilnDriver,
    tableName: String,
    context: CoroutineContext,
    query: () -> T
): Flow<T> = callbackFlow {
    val listener = KilnListener { trySend(Unit) }
    driver.addListener(tableName, listener = listener)
    trySend(Unit) // initial emission
    awaitClose { driver.removeListener(tableName, listener = listener) }
}
    .conflate()
    .map { query() }
    .flowOn(context)
