package io.github.sufarook.kiln.runtime

interface KilnTransaction {
    val enclosingTransaction: KilnTransaction?

    fun endTransaction(successful: Boolean)

    fun childTransaction(): KilnTransaction
}
