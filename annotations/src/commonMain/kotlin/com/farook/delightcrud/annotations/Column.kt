package com.farook.delightcrud.annotations

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Column(
    val name: String = "",
    val unique: Boolean = false,
    val index: Boolean = false
)
