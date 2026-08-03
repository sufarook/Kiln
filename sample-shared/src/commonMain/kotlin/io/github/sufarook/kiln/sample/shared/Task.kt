package io.github.sufarook.kiln.sample.shared

import io.github.sufarook.kiln.annotations.Column
import io.github.sufarook.kiln.annotations.DbEntity
import io.github.sufarook.kiln.annotations.PrimaryKey
import io.github.sufarook.kiln.annotations.Relation

@DbEntity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    @Column(index = true)
    @Relation(cascade = true)
    val projectId: Long = 0, // FK to Project
    val isDone: Boolean = false
)
