package io.github.sufarook.kiln.annotations

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class DbEntity(
    val tableName: String = ""
)
