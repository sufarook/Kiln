package com.farook.delightcrud.annotations

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class PrimaryKey(
    val autoGenerate: Boolean = false
)
