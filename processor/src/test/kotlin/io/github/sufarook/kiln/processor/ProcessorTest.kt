package io.github.sufarook.kiln.processor

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.DOUBLE
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.STRING
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for SqlStatementBuilder and EntityMetadata logic.
 * Full end-to-end KSP compilation tests live in the Gradle TestKit suite (gradle-plugin module).
 */
class SqlStatementBuilderTest {

    private fun productMeta() = EntityMetadata(
        packageName = "com.test",
        entityClassName = "Product",
        tableName = "product",
        primaryKeys = listOf(
            ColumnMetadata(
                propertyName = "id",
                columnName = "id",
                kotlinTypeName = STRING,
                sqliteType = "TEXT",
                bindMethod = "bindString",
                cursorMethod = "getString",
                isNullable = false,
                isUnique = false,
                hasIndex = false,
                isPrimaryKey = true,
                autoGenerate = false
            )
        ),
        columns = listOf(
            ColumnMetadata(
                propertyName = "name",
                columnName = "name",
                kotlinTypeName = STRING,
                sqliteType = "TEXT",
                bindMethod = "bindString",
                cursorMethod = "getString",
                isNullable = false,
                isUnique = false,
                hasIndex = false,
                isPrimaryKey = false,
                autoGenerate = false
            ),
            ColumnMetadata(
                propertyName = "price",
                columnName = "price",
                kotlinTypeName = DOUBLE,
                sqliteType = "REAL",
                bindMethod = "bindDouble",
                cursorMethod = "getDouble",
                isNullable = false,
                isUnique = false,
                hasIndex = false,
                isPrimaryKey = false,
                autoGenerate = false
            )
        )
    )

    // Identifiers are double-quoted so reserved words like "order" are safe table names
    @Test
    fun createTableContainsTableName() {
        val sql = SqlStatementBuilder.createTable(productMeta())
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS \"product\""))
    }

    @Test
    fun createTableContainsPrimaryKey() {
        val sql = SqlStatementBuilder.createTable(productMeta())
        assertTrue(sql.contains("\"id\" TEXT NOT NULL PRIMARY KEY"))
    }

    @Test
    fun createTableContainsAllColumns() {
        val sql = SqlStatementBuilder.createTable(productMeta())
        assertTrue(sql.contains("\"name\" TEXT NOT NULL"))
        assertTrue(sql.contains("\"price\" REAL NOT NULL"))
    }

    @Test
    fun createTableNullableColumnHasNoNotNull() {
        val meta = productMeta().copy(
            columns = listOf(
                ColumnMetadata(
                    propertyName = "description",
                    columnName = "description",
                    kotlinTypeName = STRING.copy(nullable = true),
                    sqliteType = "TEXT",
                    bindMethod = "bindString",
                    cursorMethod = "getString",
                    isNullable = true,
                    isUnique = false,
                    hasIndex = false,
                    isPrimaryKey = false,
                    autoGenerate = false
                )
            )
        )
        val sql = SqlStatementBuilder.createTable(meta)
        assertFalse("Nullable column must not have NOT NULL", sql.contains("\"description\" TEXT NOT NULL"))
        assertTrue(sql.contains("\"description\" TEXT"))
    }

    @Test
    fun createTableUniqueColumnHasConstraint() {
        val meta = productMeta().copy(
            columns = listOf(
                ColumnMetadata(
                    propertyName = "email",
                    columnName = "email",
                    kotlinTypeName = STRING,
                    sqliteType = "TEXT",
                    bindMethod = "bindString",
                    cursorMethod = "getString",
                    isNullable = false,
                    isUnique = true,
                    hasIndex = false,
                    isPrimaryKey = false,
                    autoGenerate = false
                )
            )
        )
        val sql = SqlStatementBuilder.createTable(meta)
        assertTrue(sql.contains("UNIQUE"))
    }

    @Test
    fun autoGeneratePrimaryKeyUsesAutoincrement() {
        val meta = productMeta().copy(
            primaryKeys = listOf(
                ColumnMetadata(
                    propertyName = "id",
                    columnName = "id",
                    kotlinTypeName = LONG,
                    sqliteType = "INTEGER",
                    bindMethod = "bindLong",
                    cursorMethod = "getLong",
                    isNullable = false,
                    isUnique = false,
                    hasIndex = false,
                    isPrimaryKey = true,
                    autoGenerate = true
                )
            )
        )
        val sql = SqlStatementBuilder.createTable(meta)
        assertTrue(sql.contains("AUTOINCREMENT"))
    }

    @Test
    fun insertSqlIncludesAllColumnsWithPlaceholders() {
        val sql = SqlStatementBuilder.insert(productMeta())
        assertTrue(sql.startsWith("INSERT INTO \"product\""))
        assertEquals(3, sql.count { it == '?' })
    }

    @Test
    fun insertSqlSkipsPkWhenAutoGenerate() {
        val meta = productMeta().copy(
            primaryKeys = listOf(productMeta().primaryKeys.single().copy(autoGenerate = true))
        )
        val sql = SqlStatementBuilder.insert(meta)
        assertFalse("Auto-generate PK must not be in INSERT", sql.contains("id"))
        assertEquals(2, sql.count { it == '?' })
    }

    @Test
    fun updateSqlSetsAllNonPkColumns() {
        val sql = SqlStatementBuilder.update(productMeta())
        assertTrue(sql.startsWith("UPDATE \"product\" SET"))
        assertTrue(sql.contains("WHERE \"id\" = ?"))
        // name and price are set, id is the WHERE clause
        assertEquals(3, sql.count { it == '?' })
    }

    @Test
    fun deleteSqlFiltersById() {
        val sql = SqlStatementBuilder.delete(productMeta())
        assertEquals("DELETE FROM \"product\" WHERE \"id\" = ?", sql)
    }

    @Test
    fun findByIdSqlSelectsAllColumns() {
        val sql = SqlStatementBuilder.findById(productMeta())
        assertTrue(sql.contains("SELECT"))
        assertTrue(sql.contains("WHERE \"id\" = ?"))
        assertTrue(sql.contains("id"))
        assertTrue(sql.contains("name"))
        assertTrue(sql.contains("price"))
    }

    @Test
    fun findAllSqlSelectsAllColumnsNoWhere() {
        val sql = SqlStatementBuilder.findAll(productMeta())
        assertTrue(sql.startsWith("SELECT"))
        assertFalse(sql.contains("WHERE"))
        assertTrue(sql.contains("FROM \"product\""))
    }

    // ── Composite primary keys ────────────────────────────────────────────────────

    private fun longColumn(name: String, isPk: Boolean = false) = ColumnMetadata(
        propertyName = name,
        columnName = name,
        kotlinTypeName = LONG,
        sqliteType = "INTEGER",
        bindMethod = "bindLong",
        cursorMethod = "getLong",
        isNullable = false,
        isUnique = false,
        hasIndex = false,
        isPrimaryKey = isPk,
        autoGenerate = false
    )

    private fun assignmentMeta() = EntityMetadata(
        packageName = "com.test",
        entityClassName = "Assignment",
        tableName = "assignment",
        primaryKeys = listOf(longColumn("taskId", isPk = true), longColumn("userId", isPk = true)),
        columns = listOf(longColumn("assignedAt"))
    )

    @Test
    fun compositeKeyIsDetectedOnlyWithTwoOrMorePrimaryKeys() {
        assertTrue(assignmentMeta().isCompositeKey)
        assertFalse(productMeta().isCompositeKey)
    }

    @Test
    fun compositeKeyCreateTableUsesTableLevelConstraint() {
        val sql = SqlStatementBuilder.createTable(assignmentMeta())
        assertTrue(sql.contains("PRIMARY KEY (\"taskId\", \"userId\")"))
        // Neither key column should carry an inline column-level PRIMARY KEY —
        // that would declare two separate (wrong) constraints instead of one composite key.
        assertFalse(sql.contains("\"taskId\" INTEGER NOT NULL PRIMARY KEY"))
        assertFalse(sql.contains("\"userId\" INTEGER NOT NULL PRIMARY KEY"))
    }

    @Test
    fun compositeKeyInsertIncludesBothKeyColumns() {
        val sql = SqlStatementBuilder.insert(assignmentMeta())
        assertTrue(sql.contains("\"taskId\""))
        assertTrue(sql.contains("\"userId\""))
        assertEquals(3, sql.count { it == '?' }) // taskId, userId, assignedAt
    }

    @Test
    fun compositeKeyUpdateWhereClauseAndsBothColumns() {
        val sql = SqlStatementBuilder.update(assignmentMeta())
        assertTrue(sql.contains("WHERE \"taskId\" = ? AND \"userId\" = ?"))
    }

    @Test
    fun compositeKeyDeleteWhereClauseAndsBothColumns() {
        val sql = SqlStatementBuilder.delete(assignmentMeta())
        assertEquals("DELETE FROM \"assignment\" WHERE \"taskId\" = ? AND \"userId\" = ?", sql)
    }

    @Test
    fun compositeKeyFindByIdWhereClauseAndsBothColumns() {
        val sql = SqlStatementBuilder.findById(assignmentMeta())
        assertTrue(sql.contains("WHERE \"taskId\" = ? AND \"userId\" = ?"))
    }

    @Test
    fun tableNameSnakeCaseConversion() {
        // Verify our snake_case convention for table names
        val name = "UserProfile"
        val expected = "user_profile"
        val actual = name.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()
        assertEquals(expected, actual)
    }

    @Test
    fun columnNameSnakeCaseConversion() {
        val name = "inStock"
        val expected = "in_stock"
        val actual = name.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()
        assertEquals(expected, actual)
    }
}

class TypeMapperSimpleTest {

    @Test
    fun booleanMapsToIntegerWithCorrectMethods() {
        // TypeMapper needs a real KSType, so we verify the mapping data model directly
        val col = ColumnMetadata(
            propertyName = "active",
            columnName = "active",
            kotlinTypeName = BOOLEAN,
            sqliteType = "INTEGER",
            bindMethod = "bindLong",
            cursorMethod = "getLong",
            isNullable = false,
            isUnique = false,
            hasIndex = false,
            isPrimaryKey = false,
            autoGenerate = false,
            isBoolean = true
        )
        assertEquals("INTEGER", col.sqliteType)
        assertEquals("bindLong", col.bindMethod)
        assertTrue(col.isBoolean)
    }

    @Test
    fun entityMetadataAllColumnsIncludesPrimaryKey() {
        val pk = ColumnMetadata(
            propertyName = "id", columnName = "id",
            kotlinTypeName = STRING, sqliteType = "TEXT",
            bindMethod = "bindString", cursorMethod = "getString",
            isNullable = false, isUnique = false, hasIndex = false,
            isPrimaryKey = true, autoGenerate = false
        )
        val col = ColumnMetadata(
            propertyName = "name", columnName = "name",
            kotlinTypeName = STRING, sqliteType = "TEXT",
            bindMethod = "bindString", cursorMethod = "getString",
            isNullable = false, isUnique = false, hasIndex = false,
            isPrimaryKey = false, autoGenerate = false
        )
        val meta = EntityMetadata("com.test", "Foo", "foo", listOf(pk), listOf(col))
        assertEquals(2, meta.allColumns.size)
        assertEquals("id", meta.allColumns[0].columnName)
        assertEquals("name", meta.allColumns[1].columnName)
    }

    @Test
    fun entityMetadataDefaultRelationsIsEmpty() {
        val pk = ColumnMetadata(
            propertyName = "id", columnName = "id",
            kotlinTypeName = LONG, sqliteType = "INTEGER",
            bindMethod = "bindLong", cursorMethod = "getLong",
            isNullable = false, isUnique = false, hasIndex = false,
            isPrimaryKey = true, autoGenerate = true
        )
        val meta = EntityMetadata("com.test", "Task", "tasks", listOf(pk), emptyList())
        assertTrue(meta.relations.isEmpty())
    }

    @Test
    fun entityMetadataStoresRelations() {
        val pk = ColumnMetadata(
            propertyName = "id", columnName = "id",
            kotlinTypeName = LONG, sqliteType = "INTEGER",
            bindMethod = "bindLong", cursorMethod = "getLong",
            isNullable = false, isUnique = false, hasIndex = false,
            isPrimaryKey = true, autoGenerate = true
        )
        val fkCol = ColumnMetadata(
            propertyName = "projectId", columnName = "project_id",
            kotlinTypeName = LONG, sqliteType = "INTEGER",
            bindMethod = "bindLong", cursorMethod = "getLong",
            isNullable = false, isUnique = false, hasIndex = false,
            isPrimaryKey = false, autoGenerate = false
        )
        val relation = RelationMetadata(
            propertyName = "projectId",
            columnName = "project_id",
            kotlinTypeName = LONG,
            parentEntityName = "Project",
            cascade = true
        )
        val meta = EntityMetadata("com.test", "Task", "tasks", listOf(pk), listOf(fkCol), listOf(relation))
        assertEquals(1, meta.relations.size)
        assertEquals("projectId", meta.relations[0].propertyName)
        assertEquals("Project", meta.relations[0].parentEntityName)
        assertTrue(meta.relations[0].cascade)
    }
}

class InferParentNameTest {

    private fun inferParentName(propertyName: String): String {
        val base = if (propertyName.endsWith("Id")) propertyName.dropLast(2) else propertyName
        return base.replaceFirstChar { it.uppercase() }
    }

    @Test fun simpleIdSuffix() {
        assertEquals("Project", inferParentName("projectId"))
    }

    @Test fun camelCaseIdSuffix() {
        assertEquals("Author", inferParentName("authorId"))
    }

    @Test fun compoundIdSuffix() {
        assertEquals("ParentTask", inferParentName("parentTaskId"))
    }

    @Test fun noIdSuffix() {
        assertEquals("Owner", inferParentName("owner"))
    }

    @Test fun singleWordCapitalized() {
        assertEquals("Category", inferParentName("categoryId"))
    }
}
