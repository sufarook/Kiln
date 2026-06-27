package com.farook.delightcrud.runtime

interface CrudRepository<T, ID> {
    fun insert(entity: T)
    fun update(entity: T)
    fun delete(id: ID)
    fun findById(id: ID): T?
    fun findAll(): List<T>
}
