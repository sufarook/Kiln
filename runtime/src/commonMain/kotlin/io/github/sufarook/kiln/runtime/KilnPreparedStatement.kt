package io.github.sufarook.kiln.runtime

interface KilnPreparedStatement {
    fun bindString(index: Int, value: String?)
    fun bindLong(index: Int, value: Long?)
    fun bindDouble(index: Int, value: Double?)
    fun bindBytes(index: Int, value: ByteArray?)
}
