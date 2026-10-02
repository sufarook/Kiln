package io.github.sufarook.kiln.runtime

interface KilnDriver : AutoCloseable {

    fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (KilnPreparedStatement.() -> Unit)? = null
    ): Long

    fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (KilnCursor) -> R,
        parameters: Int,
        binders: (KilnPreparedStatement.() -> Unit)? = null
    ): R

    fun newTransaction(): KilnTransaction

    fun currentTransaction(): KilnTransaction?

    fun addListener(vararg queryKeys: String, listener: KilnListener)

    fun removeListener(vararg queryKeys: String, listener: KilnListener)

    fun notifyListeners(vararg queryKeys: String)

    override fun close()
}
