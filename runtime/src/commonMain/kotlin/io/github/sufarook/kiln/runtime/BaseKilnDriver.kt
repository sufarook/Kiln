package io.github.sufarook.kiln.runtime

import kotlin.concurrent.Volatile

abstract class BaseKilnDriver : KilnDriver {

    @Volatile
    private var listeners = emptyMap<String, Set<KilnListener>>()

    @Volatile private var closed = false

    protected var transactionStack: KilnTransaction? = null

    protected fun checkNotClosed() {
        check(!closed) { "Driver is closed." }
    }

    override fun currentTransaction(): KilnTransaction? = transactionStack

    override fun addListener(vararg queryKeys: String, listener: KilnListener) {
        val current = listeners.toMutableMap()
        queryKeys.forEach { key ->
            current[key] = (current[key] ?: emptySet()) + listener
        }
        listeners = current
    }

    override fun removeListener(vararg queryKeys: String, listener: KilnListener) {
        val current = listeners.toMutableMap()
        queryKeys.forEach { key ->
            current[key]?.let { set ->
                val updated = set - listener
                if (updated.isEmpty()) current.remove(key) else current[key] = updated
            }
        }
        listeners = current
    }

    override fun notifyListeners(vararg queryKeys: String) {
        val snapshot = listeners
        val toNotify = queryKeys.flatMapTo(mutableSetOf()) { key ->
            snapshot[key] ?: emptySet()
        }
        toNotify.forEach { it.queryResultsChanged() }
    }

    override fun close() {
        if (closed) return
        closed = true
        val openTx = transactionStack
        if (openTx != null) {
            openTx.endTransaction(successful = false)
            transactionStack = null
        }
        doClose()
    }

    protected abstract fun doClose()
}
