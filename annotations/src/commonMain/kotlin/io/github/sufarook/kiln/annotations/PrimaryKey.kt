package io.github.sufarook.kiln.annotations

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class PrimaryKey(
    val autoGenerate: Boolean = false
)
