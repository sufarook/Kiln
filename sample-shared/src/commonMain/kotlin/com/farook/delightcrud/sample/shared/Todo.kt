package com.farook.delightcrud.sample.shared

import com.farook.delightcrud.annotations.Column
import com.farook.delightcrud.annotations.DbEntity
import com.farook.delightcrud.annotations.PrimaryKey

/**
 * One entity, written once in commonMain — TodoRepository is generated here too,
 * so Android and iOS share the exact same type-safe CRUD code.
 */
@DbEntity(tableName = "todos")
data class Todo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    @Column(name = "is_completed") val isCompleted: Boolean = false,
    @Column(index = true) val priority: Int = 0   // 0 = Normal, 1 = High — indexed for sorted queries
)
