package com.farook.krate.annotations

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class DbEntity(
    val tableName: String = ""
)
