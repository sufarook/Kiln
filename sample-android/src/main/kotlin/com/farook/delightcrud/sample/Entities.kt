package com.farook.delightcrud.sample

import com.farook.delightcrud.annotations.Column
import com.farook.delightcrud.annotations.DbEntity
import com.farook.delightcrud.annotations.PrimaryKey

/**
 * This is all you write — DelightCRUD generates TodoRepository at compile time.
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
    // "v2 app update": priority renamed to urgency (data migrates automatically),
    // notes added (existing rows get the default)
    @Column(index = true, migrateFrom = "priority") val urgency: Int = 0,
    val notes: String = ""
)
