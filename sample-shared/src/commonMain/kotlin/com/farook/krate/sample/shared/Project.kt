package com.farook.krate.sample.shared

import com.farook.krate.annotations.DbEntity
import com.farook.krate.annotations.PrimaryKey

@DbEntity(tableName = "projects")
data class Project(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = ""
)
