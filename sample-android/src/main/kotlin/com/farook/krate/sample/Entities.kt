package com.farook.krate.sample

import com.farook.krate.annotations.Column
import com.farook.krate.annotations.DbEntity
import com.farook.krate.annotations.PrimaryKey

/**
 * This is all you write — Krate generates TodoRepository at compile time.
 *
 * Generated API:
 *   repo.createTable()
 *   repo.insert(todo)
 *   repo.update(todo)
 *   repo.delete(id)
 *   repo.findById(id)
 *   repo.findAll()
 */
@DbEntity(tableName = "todos")
data class Todo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    @Column(name = "is_completed") val isCompleted: Boolean = false,
    @Column(index = true) val priority: Int = 0
)
