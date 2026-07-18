package com.farook.krate.annotations

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class PrimaryKey(
    val autoGenerate: Boolean = false
)
