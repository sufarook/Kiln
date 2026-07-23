package com.farook.krate.processor

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.ksp.toTypeName

object EntityVisitor {

    fun extract(classDecl: KSClassDeclaration, logger: KSPLogger): EntityMetadata? {
        // Must be a data class
        if (Modifier.DATA !in classDecl.modifiers) {
            logger.error("Krate: @DbEntity must be applied to a data class", classDecl)
            return null
        }
        if (classDecl.classKind != ClassKind.CLASS) {
            logger.error("Krate: @DbEntity must be a concrete data class", classDecl)
            return null
        }

        val packageName = classDecl.packageName.asString()
        if (packageName.isEmpty()) {
            logger.error("Krate: @DbEntity class must be in a named package", classDecl)
            return null
        }

        val entityName = classDecl.simpleName.asString()
        val dbEntityAnnotation = classDecl.annotations
            .first { it.shortName.asString() == "DbEntity" }
        val tableNameArg = dbEntityAnnotation.arguments
            .firstOrNull { it.name?.asString() == "tableName" }?.value as? String ?: ""
        val tableName = if (tableNameArg.isBlank()) entityName.toSnakeCase() else tableNameArg

        // Only process constructor properties
        val constructorParams = classDecl.primaryConstructor?.parameters
            ?.map { it.name?.asString() }?.toSet() ?: emptySet()

        val properties = classDecl.getAllProperties()
            .filter { it.simpleName.asString() in constructorParams }
            .toList()

        // Filter out @Ignore
        val persistableProps = properties.filter { prop ->
            prop.annotations.none { it.shortName.asString() == "Ignore" }
        }

        // Find primary key(s)
        val primaryKeyProps = persistableProps.filter { prop ->
            prop.annotations.any { it.shortName.asString() == "PrimaryKey" }
        }

        if (primaryKeyProps.isEmpty()) {
            logger.error("Krate: '$entityName' must have exactly one @PrimaryKey property", classDecl)
            return null
        }
        if (primaryKeyProps.size > 1) {
            logger.error("Krate: '$entityName' has ${primaryKeyProps.size} @PrimaryKey properties — only one is allowed", classDecl)
            return null
        }

        val pkProp = primaryKeyProps.first()
        val pkAnnotation = pkProp.annotations.first { it.shortName.asString() == "PrimaryKey" }
        val autoGenerate = pkAnnotation.arguments
            .firstOrNull { it.name?.asString() == "autoGenerate" }?.value as? Boolean ?: false

        val pkType = pkProp.type.resolve()
        if (autoGenerate) {
            val fqn = pkType.declaration.qualifiedName?.asString()
            if (fqn != "kotlin.Long" && fqn != "kotlin.Int") {
                logger.error("Krate: autoGenerate=true requires Long or Int primary key type in '$entityName'", pkProp)
                return null
            }
        }

        val pkMapping = TypeMapper.map(pkType)
        val pkColumnName = resolveColumnName(pkProp)
        val pkMetadata = ColumnMetadata(
            propertyName = pkProp.simpleName.asString(),
            columnName = pkColumnName,
            kotlinTypeName = pkType.toTypeName(),
            sqliteType = pkMapping.sqliteType,
            bindMethod = pkMapping.bindMethod,
            cursorMethod = pkMapping.cursorMethod,
            isNullable = pkType.nullability == Nullability.NULLABLE,
            isUnique = false,
            hasIndex = false,
            isPrimaryKey = true,
            autoGenerate = autoGenerate,
            isBoolean = pkMapping.isBoolean,
            isEnum = pkMapping.isEnum,
            enumClassName = if (pkMapping.isEnum) pkType.declaration.qualifiedName?.asString() ?: "" else "",
            isInt = pkMapping.isInt,
            isFloat = pkMapping.isFloat,
            sqlDefaultValue = pkMapping.defaultValue,
            migrateFrom = ""  // primary keys are never renamed via migrateFrom
        )

        val nonPkColumns = mutableListOf<ColumnMetadata>()
        val relations    = mutableListOf<RelationMetadata>()

        for (prop in persistableProps.filter { it.simpleName.asString() != pkProp.simpleName.asString() }) {
            val resolvedType = prop.type.resolve()
            val mapping = try {
                TypeMapper.map(resolvedType)
            } catch (e: IllegalStateException) {
                logger.error(e.message ?: "Krate: unsupported type", prop)
                return null
            }
            val colAnnotation = prop.annotations.firstOrNull { it.shortName.asString() == "Column" }
            val isUnique    = colAnnotation?.arguments?.firstOrNull { it.name?.asString() == "unique" }?.value as? Boolean ?: false
            val hasIndex    = colAnnotation?.arguments?.firstOrNull { it.name?.asString() == "index" }?.value as? Boolean ?: false
            val migrateFrom = colAnnotation?.arguments?.firstOrNull { it.name?.asString() == "migrateFrom" }?.value as? String ?: ""

            val col = ColumnMetadata(
                propertyName = prop.simpleName.asString(),
                columnName = resolveColumnName(prop),
                kotlinTypeName = resolvedType.toTypeName(),
                sqliteType = mapping.sqliteType,
                bindMethod = mapping.bindMethod,
                cursorMethod = mapping.cursorMethod,
                isNullable = resolvedType.nullability == Nullability.NULLABLE,
                isUnique = isUnique,
                hasIndex = hasIndex,
                isPrimaryKey = false,
                autoGenerate = false,
                isBoolean = mapping.isBoolean,
                isEnum = mapping.isEnum,
                enumClassName = if (mapping.isEnum) resolvedType.declaration.qualifiedName?.asString() ?: "" else "",
                isInt = mapping.isInt,
                isFloat = mapping.isFloat,
                sqlDefaultValue = mapping.defaultValue,
                migrateFrom = migrateFrom
            )
            nonPkColumns.add(col)

            val relationAnnotation = prop.annotations.firstOrNull { it.shortName.asString() == "Relation" }
            if (relationAnnotation != null) {
                val cascade = relationAnnotation.arguments
                    .firstOrNull { it.name?.asString() == "cascade" }?.value as? Boolean ?: false
                val propName = prop.simpleName.asString()
                relations.add(RelationMetadata(
                    propertyName = propName,
                    columnName = col.columnName,
                    kotlinTypeName = resolvedType.toTypeName(),
                    parentEntityName = inferParentName(propName),
                    cascade = cascade
                ))
            }
        }

        return EntityMetadata(
            packageName = packageName,
            entityClassName = entityName,
            tableName = tableName,
            primaryKey = pkMetadata,
            columns = nonPkColumns,
            relations = relations
        )
    }

    private fun resolveColumnName(prop: KSPropertyDeclaration): String {
        val colAnnotation = prop.annotations.firstOrNull { it.shortName.asString() == "Column" }
        val nameArg = colAnnotation?.arguments?.firstOrNull { it.name?.asString() == "name" }?.value as? String ?: ""
        return if (nameArg.isBlank()) prop.simpleName.asString().toSnakeCase() else nameArg
    }

    /**
     * Infers the parent entity name from the FK property name.
     * `projectId` → `Project`, `authorId` → `Author`, `parentTaskId` → `ParentTask`.
     * Falls back to capitalising the full property name when no `Id` suffix is found.
     */
    private fun inferParentName(propertyName: String): String {
        val base = if (propertyName.endsWith("Id")) propertyName.dropLast(2) else propertyName
        return base.replaceFirstChar { it.uppercase() }
    }

    private fun String.toSnakeCase(): String = replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()
}
