package io.github.sufarook.kiln.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy

object RepositoryGenerator {

    private val SQL_DRIVER = ClassName("app.cash.sqldelight.db", "SqlDriver")
    private val QUERY_RESULT = ClassName("app.cash.sqldelight.db", "QueryResult")
    private val SCHEMA_MIGRATOR = ClassName("io.github.sufarook.kiln.runtime", "SchemaMigrator")
    private val COLUMN_DEF = ClassName("io.github.sufarook.kiln.runtime", "ColumnDef")
    private val COROUTINE_CONTEXT = ClassName("kotlin.coroutines", "CoroutineContext")
    private val DISPATCHERS = ClassName("kotlinx.coroutines", "Dispatchers")
    private val FLOW = ClassName("kotlinx.coroutines.flow", "Flow")
    private val WITH_CONTEXT = MemberName("kotlinx.coroutines", "withContext")
    private val OBSERVE_QUERY = MemberName("io.github.sufarook.kiln.runtime", "observeQuery")
    private val COLUMN = ClassName("io.github.sufarook.kiln.runtime", "Column")
    private val SQL_ARG = ClassName("io.github.sufarook.kiln.runtime", "SqlArg")
    private val PREDICATE = ClassName("io.github.sufarook.kiln.runtime", "Predicate")
    private val BIND_ARG = MemberName("io.github.sufarook.kiln.runtime", "bindArg")
    private val ORDER_SPEC = ClassName("io.github.sufarook.kiln.runtime", "OrderSpec")
    private val BUILD_ORDER_SUFFIX = MemberName("io.github.sufarook.kiln.runtime", "buildOrderSuffix")
    private val EQ_FUN = MemberName("io.github.sufarook.kiln.runtime", "eq", isExtension = true)
    private val NOTIFY_OR_DEFER = MemberName("io.github.sufarook.kiln.runtime", "notifyOrDefer", isExtension = true)
    private val WITH_TX = MemberName("io.github.sufarook.kiln.runtime", "withTransaction", isExtension = true)

    fun generate(meta: EntityMetadata, codeGenerator: CodeGenerator) {
        val entityClass = ClassName(meta.packageName, meta.entityClassName)
        val repoClassName = "${meta.entityClassName}Repository"
        val tableObjectName = "${meta.entityClassName}Table"

        val fileBuilder = FileSpec.builder(meta.packageName, repoClassName)
            .addType(buildTableObject(meta, tableObjectName))
            .addType(buildColumnsObject(meta))

        if (meta.isCompositeKey) {
            fileBuilder.addType(buildCompositeKeyClass(meta))
        }

        fileBuilder.addType(buildRepository(meta, entityClass, repoClassName, tableObjectName))

        val output = codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating = true),
            packageName = meta.packageName,
            fileName = repoClassName
        )
        val file = fileBuilder.build()
        output.writer().use { file.writeTo(it) }
    }

    /**
     * The `ID` type for `CrudRepository<T, ID>`. A single `@PrimaryKey` uses its own
     * Kotlin type directly; two or more generate a dedicated `<Entity>Key` data class
     * (below) so findById/delete take one value instead of a raw tuple.
     */
    private fun idTypeName(meta: EntityMetadata): TypeName = if (meta.isCompositeKey) {
        ClassName(meta.packageName, "${meta.entityClassName}Key")
    } else {
        meta.primaryKeys.single().kotlinTypeName
    }

    /** `data class TaskKey(val projectId: Long, val taskId: Long)` */
    private fun buildCompositeKeyClass(meta: EntityMetadata): TypeSpec {
        val constructor = FunSpec.constructorBuilder()
        val classBuilder = TypeSpec.classBuilder("${meta.entityClassName}Key")
            .addModifiers(KModifier.DATA)
            .addKdoc(
                "Composite primary key for %L — combines %L.",
                meta.entityClassName,
                meta.primaryKeys.joinToString(", ") { it.propertyName }
            )
        meta.primaryKeys.forEach { pk ->
            constructor.addParameter(pk.propertyName, pk.kotlinTypeName)
            classBuilder.addProperty(
                PropertySpec.builder(pk.propertyName, pk.kotlinTypeName)
                    .initializer(pk.propertyName)
                    .build()
            )
        }
        return classBuilder.primaryConstructor(constructor.build()).build()
    }

    /**
     * The read expression for one PK column given the `id` parameter's runtime value.
     * A single key IS the id (`id` itself); a composite key destructures one field
     * off it (`id.projectId`).
     */
    private fun idFieldAccess(meta: EntityMetadata, idParamName: String, pk: ColumnMetadata): String = if (meta.isCompositeKey) "$idParamName.${pk.propertyName}" else idParamName

    private fun buildTableObject(meta: EntityMetadata, objectName: String): TypeSpec = TypeSpec.objectBuilder(objectName)
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
            col.isBoolean -> if (n) {
                CodeBlock.of("{ %T.LongArg(it?.let { b -> if (b) 1L else 0L }) }", SQL_ARG)
            } else {
                CodeBlock.of("{ %T.LongArg(if (it) 1L else 0L) }", SQL_ARG)
            }
            col.isEnum -> CodeBlock.of("{ %T.StringArg(it%L.name) }", SQL_ARG, if (n) "?" else "")
            col.isInt -> CodeBlock.of("{ %T.LongArg(it%L.toLong()) }", SQL_ARG, if (n) "?" else "")
            col.isFloat -> CodeBlock.of("{ %T.DoubleArg(it%L.toDouble()) }", SQL_ARG, if (n) "?" else "")
            else -> when (col.bindMethod) {
                "bindString" -> CodeBlock.of("{ %T.StringArg(it) }", SQL_ARG)
                "bindLong" -> CodeBlock.of("{ %T.LongArg(it) }", SQL_ARG)
                "bindDouble" -> CodeBlock.of("{ %T.DoubleArg(it) }", SQL_ARG)
                else -> CodeBlock.of("{ %T.BytesArg(it) }", SQL_ARG)
            }
        }
    }

    private fun buildRepository(
        meta: EntityMetadata,
        entityClass: ClassName,
        repoClassName: String,
        tableObjectName: String
    ): TypeSpec {
        val crudInterface = ClassName("io.github.sufarook.kiln.runtime", "CrudRepository")
            .parameterizedBy(entityClass, idTypeName(meta))

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
            .addFunction(buildCountWhere(meta))
            .addFunction(buildInsertAll(meta, entityClass))
            .addFunction(buildQueryById(meta, entityClass))
            .addFunction(buildQueryAll(meta, entityClass))
            .addFunction(buildQueryWhere(meta, entityClass))
            .also { builder ->
                meta.relations.forEach { rel ->
                    builder.addFunction(buildFindByRelation(meta, entityClass, rel))
                    builder.addFunction(buildObserveByRelation(meta, entityClass, rel))
                    builder.addFunction(buildDeleteByRelation(meta, rel))
                }
            }
            .build()
    }

    private fun columnsClass(meta: EntityMetadata) = ClassName(meta.packageName, "${meta.entityClassName}Columns")

    private fun predicateBlockType(meta: EntityMetadata) = LambdaTypeName.get(receiver = columnsClass(meta), returnType = PREDICATE)

    private fun orderByParam() = ParameterSpec.builder(
        "orderBy",
        List::class.asClassName().parameterizedBy(ORDER_SPEC)
    ).defaultValue("emptyList()").build()

    private fun limitParam() = ParameterSpec.builder("limit", Long::class.asTypeName().copy(nullable = true))
        .defaultValue("null").build()

    private fun offsetParam() = ParameterSpec.builder("offset", Long::class.asTypeName().copy(nullable = true))
        .defaultValue("null").build()

    private fun buildFindWhere(meta: EntityMetadata, entityClass: ClassName): FunSpec {
        val listType = List::class.asClassName().parameterizedBy(entityClass)
        return FunSpec.builder("findWhere")
            .addKdoc("Type-safe filtered query. Optionally sort and paginate:\n`findWhere(orderBy = listOf(Col.priority.desc()), limit = 20) { status eq \"DONE\" }`")
            .addModifiers(KModifier.SUSPEND)
            .addParameter(orderByParam())
            .addParameter(limitParam())
            .addParameter(offsetParam())
            .addParameter("block", predicateBlockType(meta))
            .returns(listType)
            .addStatement("return %M(context) { queryWhere(%T.block(), orderBy, limit, offset) }", WITH_CONTEXT, columnsClass(meta))
            .build()
    }

    private fun buildObserveWhere(meta: EntityMetadata, entityClass: ClassName, tableObjectName: String): FunSpec {
        val flowType = FLOW.parameterizedBy(List::class.asClassName().parameterizedBy(entityClass))
        return FunSpec.builder("observeWhere")
            .addKdoc("Reactive filtered query — re-emits after every write to this table. Supports ordering and pagination.")
            .addParameter(orderByParam())
            .addParameter(limitParam())
            .addParameter(offsetParam())
            .addParameter("block", predicateBlockType(meta))
            .returns(flowType)
            .addStatement("val predicate = %T.block()", columnsClass(meta))
            .addStatement(
                "return %M(driver, %N.TABLE_NAME, context) { queryWhere(predicate, orderBy, limit, offset) }",
                OBSERVE_QUERY,
                tableObjectName
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
                    .addStatement("driver.%M(%N.TABLE_NAME)", NOTIFY_OR_DEFER, tableObjectName)
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

    private fun buildCountWhere(meta: EntityMetadata): FunSpec {
        val baseSql = "SELECT COUNT(*) FROM \"${meta.tableName}\" WHERE "
        return FunSpec.builder("count")
            .addKdoc("Count rows matching the predicate: `count { isCompleted eq false }`")
            .addModifiers(KModifier.SUSPEND)
            .addParameter("block", predicateBlockType(meta))
            .returns(Long::class)
            .addCode(
                CodeBlock.builder()
                    .add("return %M(context) {\n", WITH_CONTEXT)
                    .indent()
                    .addStatement("val predicate = %T.block()", columnsClass(meta))
                    .addStatement("driver.executeQuery(null, %S + predicate.sql, { cursor ->", baseSql)
                    .indent()
                    .addStatement("cursor.next()")
                    .addStatement("%T.Value(cursor.getLong(0)!!)", QUERY_RESULT)
                    .unindent()
                    .addStatement("}, predicate.args.size) {")
                    .indent()
                    .addStatement("predicate.args.forEachIndexed { index, arg -> %M(index, arg) }", BIND_ARG)
                    .unindent()
                    .addStatement("}.value")
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
            .addParameter(orderByParam())
            .addParameter(limitParam())
            .addParameter(offsetParam())
            .returns(listType)
            .addCode(
                CodeBlock.builder()
                    .addStatement("val sql = %S + predicate.sql + %M(orderBy, limit, offset)", baseSql, BUILD_ORDER_SUFFIX)
                    .addStatement("return driver.executeQuery(null, sql, { cursor ->")
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
                col.autoGenerate, // autoIncrement
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
        val insertCols = if (!meta.isCompositeKey && meta.primaryKeys.single().autoGenerate) meta.columns else meta.allColumns

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
            .addStatement("driver.%M(%N.TABLE_NAME)", NOTIFY_OR_DEFER, tableObjectName)
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
        val paramCount = meta.columns.size + meta.primaryKeys.size

        val body = CodeBlock.builder()
            .add("%M(context) {\n", WITH_CONTEXT)
            .indent()
            .addStatement("driver.execute(null, %S, %L) {", sql, paramCount)
            .indent()
        meta.columns.forEachIndexed { i, col ->
            body.addStatement(bindStatement(col, "entity.${col.propertyName}", i))
        }
        meta.primaryKeys.forEachIndexed { i, pk ->
            body.addStatement(bindStatement(pk, "entity.${pk.propertyName}", meta.columns.size + i))
        }
        body.unindent()
            .addStatement("}")
            .addStatement("driver.%M(%N.TABLE_NAME)", NOTIFY_OR_DEFER, tableObjectName)
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
        return FunSpec.builder("delete")
            .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
            .addParameter("id", idTypeName(meta))
            .addCode(
                CodeBlock.builder()
                    .add("%M(context) {\n", WITH_CONTEXT)
                    .indent()
                    .addStatement("driver.execute(null, %S, %L) {", sql, meta.primaryKeys.size)
                    .indent()
                    .also { cb ->
                        meta.primaryKeys.forEachIndexed { i, pk ->
                            cb.addStatement(bindStatement(pk, idFieldAccess(meta, "id", pk), i))
                        }
                    }
                    .unindent()
                    .addStatement("}")
                    .addStatement("driver.%M(%N.TABLE_NAME)", NOTIFY_OR_DEFER, tableObjectName)
                    .unindent()
                    .add("}\n")
                    .build()
            )
            .build()
    }

    private fun buildFindById(meta: EntityMetadata, entityClass: ClassName): FunSpec = FunSpec.builder("findById")
        .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
        .addParameter("id", idTypeName(meta))
        .returns(entityClass.copy(nullable = true))
        .addStatement("return %M(context) { queryById(id) }", WITH_CONTEXT)
        .build()

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
                OBSERVE_QUERY,
                tableObjectName
            )
            .build()
    }

    // Blocking query internals shared by findById/findAll/observeAll
    private fun buildQueryById(meta: EntityMetadata, entityClass: ClassName): FunSpec {
        val sql = SqlStatementBuilder.findById(meta)
        return FunSpec.builder("queryById")
            .addModifiers(KModifier.PRIVATE)
            .addParameter("id", idTypeName(meta))
            .returns(entityClass.copy(nullable = true))
            .addCode(
                CodeBlock.builder()
                    .addStatement("return driver.executeQuery(null, %S, { cursor ->", sql)
                    .indent()
                    .add(buildCursorMapper(meta, entityClass, single = true))
                    .unindent()
                    .addStatement("}, %L) {", meta.primaryKeys.size)
                    .indent()
                    .also { cb ->
                        meta.primaryKeys.forEachIndexed { i, pk ->
                            cb.addStatement(bindStatement(pk, idFieldAccess(meta, "id", pk), i))
                        }
                    }
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

    // ── Batch operations ──────────────────────────────────────────────────────────

    /**
     * Generates `suspend fun insertAll(entities: List<Entity>)`.
     * Wraps the loop in a transaction so all rows are inserted atomically and
     * reactive flows receive exactly one notification after all inserts commit.
     */
    private fun buildInsertAll(meta: EntityMetadata, entityClass: ClassName): FunSpec {
        val listType = List::class.asClassName().parameterizedBy(entityClass)
        return FunSpec.builder("insertAll")
            .addKdoc("Inserts all entities in a single transaction. Reactive flows emit once after all rows commit.")
            .addModifiers(KModifier.SUSPEND)
            .addParameter("entities", listType)
            .addStatement("driver.%M(context) { entities.forEach { insert(it) } }", WITH_TX)
            .build()
    }

    // ── @Relation helpers ─────────────────────────────────────────────────────────

    /**
     * Generates `suspend fun findBy<Parent>(propertyName: T): List<Entity>`.
     * Captures the FK argument as `_fk` before entering the DSL lambda to avoid
     * name shadowing with the same-named column on the receiver (`<Entity>Columns`).
     */
    private fun buildFindByRelation(
        meta: EntityMetadata,
        entityClass: ClassName,
        rel: RelationMetadata
    ): FunSpec {
        val listType = List::class.asClassName().parameterizedBy(entityClass)
        val kdoc = "Returns all ${meta.entityClassName}s belonging to the given ${rel.parentEntityName}."
        return FunSpec.builder("findBy${rel.parentEntityName}")
            .addKdoc(kdoc)
            .addModifiers(KModifier.SUSPEND)
            .addParameter(rel.propertyName, rel.kotlinTypeName)
            .returns(listType)
            // 'this.propertyName' pins to the lambda receiver (Column<T>), avoiding shadowing
            // by the same-named parameter in the outer function.
            .addStatement("return findWhere { this.%N.%M(%N) }", rel.propertyName, EQ_FUN, rel.propertyName)
            .build()
    }

    /** Generates `fun observeBy<Parent>(propertyName: T): Flow<List<Entity>>`. */
    private fun buildObserveByRelation(
        meta: EntityMetadata,
        entityClass: ClassName,
        rel: RelationMetadata
    ): FunSpec {
        val flowType = FLOW.parameterizedBy(List::class.asClassName().parameterizedBy(entityClass))
        val cascadeNote = if (rel.cascade) {
            "\n\nThis is the cascade-delete companion — call before deleting the parent ${rel.parentEntityName}."
        } else {
            ""
        }
        return FunSpec.builder("observeBy${rel.parentEntityName}")
            .addKdoc("Reactive stream of ${meta.entityClassName}s for the given ${rel.parentEntityName}. Re-emits on every change.$cascadeNote")
            .addParameter(rel.propertyName, rel.kotlinTypeName)
            .returns(flowType)
            .addStatement("return observeWhere { this.%N.%M(%N) }", rel.propertyName, EQ_FUN, rel.propertyName)
            .build()
    }

    /** Generates `suspend fun deleteBy<Parent>(propertyName: T)`. */
    private fun buildDeleteByRelation(
        meta: EntityMetadata,
        rel: RelationMetadata
    ): FunSpec {
        val cascadeNote = if (rel.cascade) {
            " Call before `delete(parentId)` to cascade."
        } else {
            ""
        }
        return FunSpec.builder("deleteBy${rel.parentEntityName}")
            .addKdoc("Deletes all ${meta.entityClassName}s belonging to the given ${rel.parentEntityName}.$cascadeNote")
            .addModifiers(KModifier.SUSPEND)
            .addParameter(rel.propertyName, rel.kotlinTypeName)
            .addStatement("deleteWhere { this.%N.%M(%N) }", rel.propertyName, EQ_FUN, rel.propertyName)
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

    private fun bindStatement(col: ColumnMetadata, valueExpr: String, index: Int): String = when {
        col.isBoolean -> "${col.bindMethod}($index, if ($valueExpr) 1L else 0L)"
        col.isEnum -> "${col.bindMethod}($index, $valueExpr${if (col.isNullable) "?" else ""}.name)"
        col.isInt -> "${col.bindMethod}($index, $valueExpr${if (col.isNullable) "?.toLong()" else ".toLong()"})"
        col.isFloat -> "${col.bindMethod}($index, $valueExpr${if (col.isNullable) "?.toDouble()" else ".toDouble()"})"
        col.isNullable -> "${col.bindMethod}($index, $valueExpr)"
        else -> "${col.bindMethod}($index, $valueExpr)"
    }

    private fun cursorReadStatement(col: ColumnMetadata, index: Int): String = when {
        col.isBoolean -> "cursor.${col.cursorMethod}($index)${if (col.isNullable) "?.let { it == 1L }" else "!! == 1L"}"
        col.isEnum -> {
            val cast = "${col.enumClassName}.valueOf(cursor.${col.cursorMethod}($index)!!)"
            if (col.isNullable) {
                "cursor.${col.cursorMethod}($index)?.let { ${col.enumClassName}.valueOf(it) }"
            } else {
                cast
            }
        }
        col.isInt -> if (col.isNullable) {
            "cursor.${col.cursorMethod}($index)?.toInt()"
        } else {
            "cursor.${col.cursorMethod}($index)!!.toInt()"
        }
        col.isFloat -> if (col.isNullable) {
            "cursor.${col.cursorMethod}($index)?.toFloat()"
        } else {
            "cursor.${col.cursorMethod}($index)!!.toFloat()"
        }
        col.isNullable -> "cursor.${col.cursorMethod}($index)"
        else -> "cursor.${col.cursorMethod}($index)!!"
    }
}
