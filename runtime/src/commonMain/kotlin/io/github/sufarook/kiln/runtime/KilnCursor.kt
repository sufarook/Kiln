package io.github.sufarook.kiln.runtime

interface KilnCursor {
    fun next(): Boolean
    fun getString(index: Int): String?
    fun getLong(index: Int): Long?
    fun getDouble(index: Int): Double?
    fun getBytes(index: Int): ByteArray?
}
