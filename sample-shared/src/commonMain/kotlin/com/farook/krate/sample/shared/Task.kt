package com.farook.krate.sample.shared

import com.farook.krate.annotations.Column
import com.farook.krate.annotations.DbEntity
import com.farook.krate.annotations.PrimaryKey
import com.farook.krate.annotations.Relation

@DbEntity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    @Column(index = true)
    @Relation(cascade = true)
    val projectId: Long = 0,    // FK to Project
    val isDone: Boolean = false
)
