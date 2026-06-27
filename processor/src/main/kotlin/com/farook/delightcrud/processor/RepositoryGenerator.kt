package com.farook.delightcrud.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy

object RepositoryGenerator {

    private val SQL_DRIVER = ClassName("app.cash.sqldelight.db", "SqlDriver")
    private val QUERY_RESULT = ClassName("app.cash.sqldelight.db", "QueryResult")

    fun generate(meta: EntityMetadata, codeGenerator: CodeGenerator) {
        val entityClass = ClassName(meta.packageName, meta.entityClassName)
        val repoClassName = "${meta.entityClassName}Repository"
        val tableObjectName = "${meta.entityClassName}Table"

        val file = FileSpec.builder(meta.packageName, repoClassName)
            .addType(buildTableObject(meta, tableObjectName))
            .addType(buildRepository(meta, entityClass, repoClassName, tableObjectName))
            .build()

        val output = codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating = false),
            packageName = meta.packageName,
            fileName = repoClassName
        )
        output.writer().use { file.writeTo(it) }
    }

    private fun buildTableObject(meta: EntityMetadata, objectName: String): TypeSpec {
        return TypeSpec.objectBuilder(objectName)
            .addProperty(
                PropertySpec.builder("TABLE_NAME", String::class)
                    .addModifiers(KModifier.CONST)
                    .initializer("%S", meta.tableName)
                    .build()
            )
            .addProperty(
                PropertySpec.builder("CREATE_TABLE", String::class)
                    .addModifiers(KModifier.CONST)
                    .initializer("%S", SqlStatementBuilder.createTable(meta))
                    .build()
            )
            .build()
    }

    private fun buildRepository(
        meta: EntityMetadata,
        entityClass: ClassName,
        repoClassName: String,
        tableObjectName: String
    ): TypeSpec {
        val crudInterface = ClassName("com.farook.delightcrud.runtime", "CrudRepository")
            .parameterizedBy(entityClass, meta.primaryKey.kotlinTypeName)

        return TypeSpec.classBuilder(repoClassName)
            .addSuperinterface(crudInterface)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("driver", SQL_DRIVER)
                    .build()
            )
            .addProperty(
                PropertySpec.builder("driver", SQL_DRIVER, KModifier.PRIVATE)
                    .initializer("driver")
                    .build()
            )
            .addFunction(buildCreateTable(tableObjectName))
            .addFunction(buildInsert(meta, entityClass))
            .addFunction(buildUpdate(meta, entityClass))
            .addFunction(buildDelete(meta))
            .addFunction(buildFindById(meta, entityClass))
            .addFunction(buildFindAll(meta, entityClass))
            .build()
    }

    private fun buildCreateTable(tableObjectName: String): FunSpec {
        return FunSpec.builder("createTable")
            .addStatement("driver.execute(null, %N.CREATE_TABLE, 0)", tableObjectName)
            .build()
    }

    private fun buildInsert(meta: EntityMetadata, entityClass: ClassName): FunSpec {
        val sql = SqlStatementBuilder.insert(meta)
        val insertCols = if (meta.primaryKey.autoGenerate) meta.columns else meta.allColumns
        val paramCount = insertCols.size

        val body = CodeBlock.builder()
            .addStatement("driver.execute(null, %S, %L) {", sql, paramCount)
            .indent()

        insertCols.forEachIndexed { i, col ->
            body.addStatement(bindStatement(col, "entity.${col.propertyName}", i))
        }

        body.unindent().addStatement("}")

        return FunSpec.builder("insert")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("entity", entityClass)
            .addCode(body.build())
            .build()
    }

    private fun buildUpdate(meta: EntityMetadata, entityClass: ClassName): FunSpec {
        if (meta.columns.isEmpty()) {
            return FunSpec.builder("update")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("entity", entityClass)
                .build()
        }

        val sql = SqlStatementBuilder.update(meta)
        val paramCount = meta.columns.size + 1

        val body = CodeBlock.builder()
            .addStatement("driver.execute(null, %S, %L) {", sql, paramCount)
            .indent()

        meta.columns.forEachIndexed { i, col ->
            body.addStatement(bindStatement(col, "entity.${col.propertyName}", i))
        }
        // WHERE clause binds PK last
        body.addStatement(bindStatement(meta.primaryKey, "entity.${meta.primaryKey.propertyName}", meta.columns.size))
        body.unindent().addStatement("}")

        return FunSpec.builder("update")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("entity", entityClass)
            .addCode(body.build())
            .build()
    }

    private fun buildDelete(meta: EntityMetadata): FunSpec {
        val sql = SqlStatementBuilder.delete(meta)
        val pk = meta.primaryKey
        return FunSpec.builder("delete")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("id", pk.kotlinTypeName)
            .addCode(
                CodeBlock.builder()
                    .addStatement("driver.execute(null, %S, 1) {", sql)
                    .indent()
                    .addStatement(bindStatement(pk, "id", 0))
                    .unindent()
                    .addStatement("}")
                    .build()
            )
            .build()
    }

    private fun buildFindById(meta: EntityMetadata, entityClass: ClassName): FunSpec {
        val sql = SqlStatementBuilder.findById(meta)
        val pk = meta.primaryKey
        val nullableEntity = entityClass.copy(nullable = true)

        val cursorBlock = buildCursorMapper(meta, entityClass, single = true)

        return FunSpec.builder("findById")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("id", pk.kotlinTypeName)
            .returns(nullableEntity)
            .addCode(
                CodeBlock.builder()
                    .addStatement("return driver.executeQuery(null, %S, { cursor ->", sql)
                    .indent()
                    .add(cursorBlock)
                    .unindent()
                    .addStatement("}, 1) {")
                    .indent()
                    .addStatement(bindStatement(pk, "id", 0))
                    .unindent()
                    .addStatement("}.value")
                    .build()
            )
            .build()
    }

    private fun buildFindAll(meta: EntityMetadata, entityClass: ClassName): FunSpec {
        val sql = SqlStatementBuilder.findAll(meta)
        val listType = List::class.asClassName().parameterizedBy(entityClass)
        val cursorBlock = buildCursorMapper(meta, entityClass, single = false)

        return FunSpec.builder("findAll")
            .addModifiers(KModifier.OVERRIDE)
            .returns(listType)
            .addCode(
                CodeBlock.builder()
                    .addStatement("return driver.executeQuery(null, %S, { cursor ->", sql)
                    .indent()
                    .add(cursorBlock)
                    .unindent()
                    .addStatement("}, 0).value")
                    .build()
            )
            .build()
    }

    private fun buildCursorMapper(meta: EntityMetadata, entityClass: ClassName, single: Boolean): CodeBlock {
        val b = CodeBlock.builder()
        if (single) {
            b.addStatement("if (cursor.next().value) {")
            b.indent()
            b.add(buildEntityConstruction(meta, entityClass))
            b.addStatement("%T.Value(entity)", QUERY_RESULT)
            b.unindent()
            b.addStatement("} else %T.Value(null)", QUERY_RESULT)
        } else {
            b.addStatement("val result = mutableListOf<%T>()", entityClass)
            b.addStatement("while (cursor.next().value) {")
            b.indent()
            b.add(buildEntityConstruction(meta, entityClass))
            b.addStatement("result.add(entity)")
            b.unindent()
            b.addStatement("}")
            b.addStatement("%T.Value(result)", QUERY_RESULT)
        }
        return b.build()
    }

    private fun buildEntityConstruction(meta: EntityMetadata, entityClass: ClassName): CodeBlock {
        val b = CodeBlock.builder()
        b.add("val entity = %T(\n", entityClass)
        meta.allColumns.forEachIndexed { i, col ->
            val read = cursorReadStatement(col, i)
            b.add("    %N = %L", col.propertyName, read)
            if (i < meta.allColumns.size - 1) b.add(",")
            b.add("\n")
        }
        b.add(")\n")
        return b.build()
    }

    private fun bindStatement(col: ColumnMetadata, valueExpr: String, index: Int): String {
        return when {
            col.isBoolean -> "${col.bindMethod}($index, if ($valueExpr) 1L else 0L)"
            col.isEnum    -> "${col.bindMethod}($index, $valueExpr${if (col.isNullable) "?" else ""}.name)"
            col.isInt     -> "${col.bindMethod}($index, $valueExpr${if (col.isNullable) "?.toLong()" else ".toLong()"})"
            col.isFloat   -> "${col.bindMethod}($index, $valueExpr${if (col.isNullable) "?.toDouble()" else ".toDouble()"})"
            col.isNullable -> "${col.bindMethod}($index, $valueExpr)"
            else           -> "${col.bindMethod}($index, $valueExpr)"
        }
    }

    private fun cursorReadStatement(col: ColumnMetadata, index: Int): String {
        return when {
            col.isBoolean -> "cursor.${col.cursorMethod}($index)${if (col.isNullable) "?.let { it == 1L }" else "!! == 1L"}"
            col.isEnum    -> {
                val cast = "${col.enumClassName}.valueOf(cursor.${col.cursorMethod}($index)!!)"
                if (col.isNullable) "cursor.${col.cursorMethod}($index)?.let { ${col.enumClassName}.valueOf(it) }"
                else cast
            }
            col.isInt     -> if (col.isNullable) "cursor.${col.cursorMethod}($index)?.toInt()"
                             else "cursor.${col.cursorMethod}($index)!!.toInt()"
            col.isFloat   -> if (col.isNullable) "cursor.${col.cursorMethod}($index)?.toFloat()"
                             else "cursor.${col.cursorMethod}($index)!!.toFloat()"
            col.isNullable -> "cursor.${col.cursorMethod}($index)"
            else           -> "cursor.${col.cursorMethod}($index)!!"
        }
    }
}
