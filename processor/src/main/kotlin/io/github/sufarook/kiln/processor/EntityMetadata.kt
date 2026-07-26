package io.github.sufarook.kiln.processor

import com.squareup.kotlinpoet.TypeName

data class EntityMetadata(
    val packageName: String,
    val entityClassName: String,
    val tableName: String,
    val primaryKey: ColumnMetadata,
    val columns: List<ColumnMetadata>,
    val relations: List<RelationMetadata> = emptyList()
) {
    val allColumns: List<ColumnMetadata> get() = listOf(primaryKey) + columns
}

/**
 * Extracted from a `@Relation`-annotated FK property.
 * [parentEntityName] is inferred by stripping the `Id` suffix from [propertyName].
 */
data class RelationMetadata(
    val propertyName: String,
    val columnName: String,
    val kotlinTypeName: TypeName,
    val parentEntityName: String,
    val cascade: Boolean
)

data class ColumnMetadata(
    val propertyName: String,
    val columnName: String,
    val kotlinTypeName: TypeName,
    val sqliteType: String,
    val bindMethod: String,
    val cursorMethod: String,
    val isNullable: Boolean,
    val isUnique: Boolean,
    val hasIndex: Boolean,
    val isPrimaryKey: Boolean,
    val autoGenerate: Boolean,
    val isBoolean: Boolean = false,
    val isEnum: Boolean = false,
    val enumClassName: String = "",
    val isInt: Boolean = false,
    val isFloat: Boolean = false,
    val sqlDefaultValue: String = "",  // SQL literal, e.g. "0", "''" — for ALTER TABLE ADD COLUMN
    val migrateFrom: String = ""       // old column name when property was renamed
)
