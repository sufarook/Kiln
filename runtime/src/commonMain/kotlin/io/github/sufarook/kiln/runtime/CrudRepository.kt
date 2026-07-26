package io.github.sufarook.kiln.runtime

import kotlinx.coroutines.flow.Flow

interface CrudRepository<T, ID> {
    suspend fun insert(entity: T)
    suspend fun update(entity: T)
    suspend fun delete(id: ID)
    suspend fun findById(id: ID): T?
    suspend fun findAll(): List<T>

    /** Emits the full table contents immediately and again after every insert/update/delete. */
    fun observeAll(): Flow<List<T>>
}
