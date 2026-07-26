package io.github.sufarook.kiln.annotations

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Column(
    val name: String = "",
    val unique: Boolean = false,
    val index: Boolean = false,
    val migrateFrom: String = ""  // old column name when renaming a property — migrator copies data
)
