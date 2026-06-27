package com.farook.delightcrud.annotations

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class DbEntity(
    val tableName: String = ""
)
