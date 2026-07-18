package com.farook.krate.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy

object RepositoryGenerator {

    private val SQL_DRIVER        = ClassName("app.cash.sqldelight.db", "SqlDriver")
    private val QUERY_RESULT      = ClassName("app.cash.sqldelight.db", "QueryResult")
    private val SCHEMA_MIGRATOR   = ClassName("com.farook.krate.runtime", "SchemaMigrator")
    private val COLUMN_DEF        = ClassName("com.farook.krate.runtime", "ColumnDef")
    private val COROUTINE_CONTEXT = ClassName("kotlin.coroutines", "CoroutineContext")
    private val DISPATCHERS       = ClassName("kotlinx.coroutines", "Dispatchers")
    private val FLOW              = ClassName("kotlinx.coroutines.flow", "Flow")
    private val WITH_CONTEXT      = MemberName("kotlinx.coroutines", "withContext")
    private val OBSERVE_QUERY     = MemberName("com.farook.krate.runtime", "observeQuery")
    private val COLUMN            = ClassName("com.farook.krate.runtime", "Column")
    private val SQL_ARG           = ClassName("com.farook.krate.runtime", "SqlArg")
    private val PREDICATE         = ClassName("com.farook.krate.runtime", "Predicate")
    private val BIND_ARG          = MemberName("com.farook.krate.runtime", "bindArg")

    fun generate(meta: EntityMetadata, codeGenerator: CodeGenerator) {
        val entityClass = ClassName(meta.packageName, meta.entityClassName)
        val repoClassName = "${meta.entityClassName}Repository"
        val tableObjectName = "${meta.entityClassName}Table"

        val file = FileSpec.builder(meta.packageName, repoClassName)
            .addType(buildTableObject(meta, tableObjectName))
            .addType(buildColumnsObject(meta))
            .addType(buildRepository(meta, entityClass, repoClassName, tableObjectName))
            .build()

        val output = codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating = true),
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

    /** `object TodoColumns { val priority: Column<Int> = Column("priority") { ... } }` */
    private fun buildColumnsObject(meta: EntityMetadata): TypeSpec {
        val builder = TypeSpec.objectBuilder("${meta.entityClassName}Columns")
            .addKdoc("Typed column references for findWhere / observeWhere / deleteWhere.")
        meta.allColumns.forEach { col ->
            builder.addProperty(
                PropertySpec.builder(col.propertyName, COLUMN.parameterizedBy(col.kotlinTypeName))
                    .initializer(CodeBlock.of("%T(%S) %L", COLUMN, col.columnName, toArgLambda(col)))
                    .build()
            )
        }
        return builder.build()
    }

    /** Converts a Kotlin value of this column's type into the SqlArg the driver can bind. */
    private fun toArgLambda(col: ColumnMetadata): CodeBlock {
        val n = col.isNullable
        return when {
            col.isBoolean -> if (n) CodeBlock.of("{ %T.LongArg(it?.let { b -> if (b) 1L else 0L }) }", SQL_ARG)
                             else   CodeBlock.of("{ %T.LongArg(if (it) 1L else 0L) }", SQL_ARG)
            col.isEnum    -> CodeBlock.of("{ %T.StringArg(it%L.name) }", SQL_ARG, if (n) "?" else "")
            col.isInt     -> CodeBlock.of("{ %T.LongArg(it%L.toLong()) }", SQL_ARG, if (n) "?" else "")
            col.isFloat   -> CodeBlock.of("{ %T.DoubleArg(it%L.toDouble()) }", SQL_ARG, if (n) "?" else "")
            else -> when (col.bindMethod) {
                "bindString" -> CodeBlock.of("{ %T.StringArg(it) }", SQL_ARG)
                "bindLong"   -> CodeBlock.of("{ %T.LongArg(it) }", SQL_ARG)
                "bindDouble" -> CodeBlock.of("{ %T.DoubleArg(it) }", SQL_ARG)
                else         -> CodeBlock.of("{ %T.BytesArg(it) }", SQL_ARG)
            }
        }
    }

    private fun buildRepository(
        meta: EntityMetadata,
        entityClass: ClassName,
        repoClassName: String,
        tableObjectName: String
    ): TypeSpec {
        val crudInterface = ClassName("com.farook.krate.runtime", "CrudRepository")
            .parameterizedBy(entityClass, meta.primaryKey.kotlinTypeName)

        return TypeSpec.classBuilder(repoClassName)
            .addSuperinterface(crudInterface)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("driver", SQL_DRIVER)
                    .addParameter(
                        ParameterSpec.builder("context", COROUTINE_CONTEXT)
                            .defaultValue("%T.Default", DISPATCHERS)
                            .build()
                    )
                    .build()
            )
            .addProperty(
                PropertySpec.builder("driver", SQL_DRIVER, KModifier.PRIVATE)
                    .initializer("driver")
                    .build()
            )
            .addProperty(
                PropertySpec.builder("context", COROUTINE_CONTEXT, KModifier.PRIVATE)
                    .initializer("context")
                    .build()
            )
            .addFunction(buildCreateTable(tableObjectName, meta))
            .addFunction(buildInsert(meta, entityClass, tableObjectName))
            .addFunction(buildUpdate(meta, entityClass, tableObjectName))
            .addFunction(buildDelete(meta, tableObjectName))
            .addFunction(buildFindById(meta, entityClass))
            .addFunction(buildFindAll(meta, entityClass))
            .addFunction(buildObserveAll(meta, entityClass, tableObjectName))
            .addFunction(buildFindWhere(meta, entityClass))
            .addFunction(buildObserveWhere(meta, entityClass, tableObjectName))
            .addFunction(buildDeleteWhere(meta, tableObjectName))
            .addFunction(buildCount(meta))
            .addFunction(buildQueryById(meta, entityClass))
            .addFunction(buildQueryAll(meta, entityClass))
            .addFunction(buildQueryWhere(meta, entityClass))
            .build()
    }

    private fun columnsClass(meta: EntityMetadata) =
        ClassName(meta.packageName, "${meta.entityClassName}Columns")

    private fun predicateBlockType(meta: EntityMetadata) =
        LambdaTypeName.get(receiver = columnsClass(meta), returnType = PREDICATE)

    private fun buildFindWhere(meta: EntityMetadata, entityClass: ClassName): FunSpec {
        val listType = List::class.asClassName().parameterizedBy(entityClass)
        return FunSpec.builder("findWhere")
            .addKdoc("Type-safe filtered query: `findWhere { (priority eq 1) and (title like \"A%%\") }`")
            .addModifiers(KModifier.SUSPEND)
            .addParameter("block", predicateBlockType(meta))
            .returns(listType)
            .addStatement("return %M(context) { queryWhere(%T.block()) }", WITH_CONTEXT, columnsClass(meta))
            .build()
    }

    private fun buildObserveWhere(meta: EntityMetadata, entityClass: ClassName, tableObjectName: String): FunSpec {
        val flowType = FLOW.parameterizedBy(List::class.asClassName().parameterizedBy(entityClass))
        return FunSpec.builder("observeWhere")
            .addKdoc("Reactive filtered query — re-emits after every write to this table.")
            .addParameter("block", predicateBlockType(meta))
            .returns(flowType)
            .addStatement("val predicate = %T.block()", columnsClass(meta))
            .addStatement(
                "return %M(driver, %N.TABLE_NAME, context) { queryWhere(predicate) }",
                OBSERVE_QUERY, tableObjectName
            )
            .build()
    }

    private fun buildDeleteWhere(meta: EntityMetadata, tableObjectName: String): FunSpec {
        val sql = "DELETE FROM \"${meta.tableName}\" WHERE "
        return FunSpec.builder("deleteWhere")
            .addKdoc("Deletes every row matching the predicate.")
            .addModifiers(KModifier.SUSPEND)
            .addParameter("block", predicateBlockType(meta))
            .addCode(
                CodeBlock.builder()
                    .add("%M(context) {\n", WITH_CONTEXT)
                    .indent()
                    .addStatement("val predicate = %T.block()", columnsClass(meta))
                    .addStatement("driver.execute(null, %S + predicate.sql, predicate.args.size) {", sql)
                    .indent()
                    .addStatement("predicate.args.forEachIndexed { index, arg -> %M(index, arg) }", BIND_ARG)
                    .unindent()
                    .addStatement("}")
                    .addStatement("driver.notifyListeners(%N.TABLE_NAME)", tableObjectName)
                    .unindent()
                    .add("}\n")
                    .build()
            )
            .build()
    }

    private fun buildCount(meta: EntityMetadata): FunSpec {
        val sql = "SELECT COUNT(*) FROM \"${meta.tableName}\""
        return FunSpec.builder("count")
            .addModifiers(KModifier.SUSPEND)
            .returns(Long::class)
            .addCode(
                CodeBlock.builder()
                    .add("return %M(context) {\n", WITH_CONTEXT)
                    .indent()
                    .addStatement("driver.executeQuery(null, %S, { cursor ->", sql)
                    .indent()
                    .addStatement("cursor.next()")
                    .addStatement("%T.Value(cursor.getLong(0)!!)", QUERY_RESULT)
                    .unindent()
                    .addStatement("}, 0).value")
                    .unindent()
                    .add("}\n")
                    .build()
            )
            .build()
    }

    private fun buildQueryWhere(meta: EntityMetadata, entityClass: ClassName): FunSpec {
        val baseSql = SqlStatementBuilder.findAll(meta) + " WHERE "
        val listType = List::class.asClassName().parameterizedBy(entityClass)
        return FunSpec.builder("queryWhere")
            .addModifiers(KModifier.PRIVATE)
            .addParameter("predicate", PREDICATE)
            .returns(listType)
            .addCode(
                CodeBlock.builder()
                    .addStatement("return driver.executeQuery(null, %S + predicate.sql, { cursor ->", baseSql)
                    .indent()
                    .add(buildCursorMapper(meta, entityClass, single = false))
                    .unindent()
                    .addStatement("}, predicate.args.size) {")
                    .indent()
                    .addStatement("predicate.args.forEachIndexed { index, arg -> %M(index, arg) }", BIND_ARG)
                    .unindent()
                    .addStatement("}.value")
                    .build()
            )
            .build()
    }

    private fun buildCreateTable(tableObjectName: String, meta: EntityMetadata): FunSpec {
        val cb = CodeBlock.builder()
            // First install: create the table
            .addStatement("driver.execute(null, %N.CREATE_TABLE, 0)", tableObjectName)
            // Upgrades: diff live schema against the data class and migrate
            .add("%T(driver).sync(\n", SCHEMA_MIGRATOR)
            .indent()
            .addStatement("%S,", meta.tableName)
            .add("listOf(\n")
            .indent()

        meta.allColumns.forEach { col ->
            cb.addStatement(
                "%T(%S, %S, %L, %S, %S, %L, %L, %L),",
                COLUMN_DEF,
                col.columnName,
                col.sqliteType,
                col.isNullable,
                col.sqlDefaultValue,
                col.migrateFrom,
                col.isPrimaryKey,
                col.autoGenerate,   // autoIncrement
                col.isUnique
            )
        }

        cb.unindent()
            .add(")\n")
            .unindent()
            .add(")\n")

        // Indexes last — recreated automatically if the migrator rebuilt the table
        SqlStatementBuilder.createIndexes(meta).forEach { indexSql ->
            cb.addStatement("driver.execute(null, %S, 0)", indexSql)
        }

        return FunSpec.builder("createTable")
            .addCode(cb.build())
            .build()
    }

    private fun buildInsert(meta: EntityMetadata, entityClass: ClassName, tableObjectName: String): FunSpec {
        val sql = SqlStatementBuilder.insert(meta)
        val insertCols = if (meta.primaryKey.autoGenerate) meta.columns else meta.allColumns

        val body = CodeBlock.builder()
            .add("%M(context) {\n", WITH_CONTEXT)
            .indent()
            .addStatement("driver.execute(null, %S, %L) {", sql, insertCols.size)
            .indent()
        insertCols.forEachIndexed { i, col ->
            body.addStatement(bindStatement(col, "entity.${col.propertyName}", i))
        }
        body.unindent()
            .addStatement("}")
            .addStatement("driver.notifyListeners(%N.TABLE_NAME)", tableObjectName)
            .unindent()
            .add("}\n")

        return FunSpec.builder("insert")
            .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
            .addParameter("entity", entityClass)
            .addCode(body.build())
            .build()
    }

    private fun buildUpdate(meta: EntityMetadata, entityClass: ClassName, tableObjectName: String): FunSpec {
        if (meta.columns.isEmpty()) {
            return FunSpec.builder("update")
                .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
                .addParameter("entity", entityClass)
                .build()
        }

        val sql = SqlStatementBuilder.update(meta)
        val paramCount = meta.columns.size + 1

        val body = CodeBlock.builder()
            .add("%M(context) {\n", WITH_CONTEXT)
            .indent()
            .addStatement("driver.execute(null, %S, %L) {", sql, paramCount)
            .indent()
        meta.columns.forEachIndexed { i, col ->
            body.addStatement(bindStatement(col, "entity.${col.propertyName}", i))
        }
        body.addStatement(bindStatement(meta.primaryKey, "entity.${meta.primaryKey.propertyName}", meta.columns.size))
        body.unindent()
            .addStatement("}")
            .addStatement("driver.notifyListeners(%N.TABLE_NAME)", tableObjectName)
            .unindent()
            .add("}\n")

        return FunSpec.builder("update")
            .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
            .addParameter("entity", entityClass)
            .addCode(body.build())
            .build()
    }

    private fun buildDelete(meta: EntityMetadata, tableObjectName: String): FunSpec {
        val sql = SqlStatementBuilder.delete(meta)
        val pk = meta.primaryKey
        return FunSpec.builder("delete")
            .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
            .addParameter("id", pk.kotlinTypeName)
            .addCode(
                CodeBlock.builder()
                    .add("%M(context) {\n", WITH_CONTEXT)
                    .indent()
                    .addStatement("driver.execute(null, %S, 1) {", sql)
                    .indent()
                    .addStatement(bindStatement(pk, "id", 0))
                    .unindent()
                    .addStatement("}")
                    .addStatement("driver.notifyListeners(%N.TABLE_NAME)", tableObjectName)
                    .unindent()
                    .add("}\n")
                    .build()
            )
            .build()
    }

    private fun buildFindById(meta: EntityMetadata, entityClass: ClassName): FunSpec {
        val pk = meta.primaryKey
        return FunSpec.builder("findById")
            .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
            .addParameter("id", pk.kotlinTypeName)
            .returns(entityClass.copy(nullable = true))
            .addStatement("return %M(context) { queryById(id) }", WITH_CONTEXT)
            .build()
    }

    private fun buildFindAll(meta: EntityMetadata, entityClass: ClassName): FunSpec {
        val listType = List::class.asClassName().parameterizedBy(entityClass)
        return FunSpec.builder("findAll")
            .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
            .returns(listType)
            .addStatement("return %M(context) { queryAll() }", WITH_CONTEXT)
            .build()
    }

    private fun buildObserveAll(meta: EntityMetadata, entityClass: ClassName, tableObjectName: String): FunSpec {
        val flowType = FLOW.parameterizedBy(List::class.asClassName().parameterizedBy(entityClass))
        return FunSpec.builder("observeAll")
            .addModifiers(KModifier.OVERRIDE)
            .returns(flowType)
            .addStatement(
                "return %M(driver, %N.TABLE_NAME, context) { queryAll() }",
                OBSERVE_QUERY, tableObjectName
            )
            .build()
    }

    // Blocking query internals shared by findById/findAll/observeAll
    private fun buildQueryById(meta: EntityMetadata, entityClass: ClassName): FunSpec {
        val sql = SqlStatementBuilder.findById(meta)
        val pk = meta.primaryKey
        return FunSpec.builder("queryById")
            .addModifiers(KModifier.PRIVATE)
            .addParameter("id", pk.kotlinTypeName)
            .returns(entityClass.copy(nullable = true))
            .addCode(
                CodeBlock.builder()
                    .addStatement("return driver.executeQuery(null, %S, { cursor ->", sql)
                    .indent()
                    .add(buildCursorMapper(meta, entityClass, single = true))
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

    private fun buildQueryAll(meta: EntityMetadata, entityClass: ClassName): FunSpec {
        val sql = SqlStatementBuilder.findAll(meta)
        val listType = List::class.asClassName().parameterizedBy(entityClass)
        return FunSpec.builder("queryAll")
            .addModifiers(KModifier.PRIVATE)
            .returns(listType)
            .addCode(
                CodeBlock.builder()
                    .addStatement("return driver.executeQuery(null, %S, { cursor ->", sql)
                    .indent()
                    .add(buildCursorMapper(meta, entityClass, single = false))
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
