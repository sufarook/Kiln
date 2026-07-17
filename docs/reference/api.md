# Generated Methods

Every `@DbEntity` class gets one generated repository. This page documents every method on the generated `<Entity>Repository` class.

## Repository signature

```kotlin
class ProductRepository(private val driver: SqlDriver)
```

The generated class is concrete (not an interface). Inject the `SqlDriver` directly — see [Initialization](../sample/initialization.md).

---

## `createTable()`

```kotlin
fun createTable()
```

Creates the table if it does not already exist. Call once per repository during app startup, **before** any other method.

- Uses `CREATE TABLE IF NOT EXISTS` — safe to call more than once.
- Delegates to `SchemaMigrator.sync()` — adds, renames, removes, or recreates columns when the entity changes. See [Auto-migration](../migration.md).

---

## `insert(entity: T)`

```kotlin
suspend fun insert(entity: T)
```

Inserts a single row. Binds all non-`@Ignore` properties as `?` parameters.

**Auto-generated primary key**: pass `id = 0` (or whatever the Kotlin default is). SQLite assigns the real id.

```kotlin
productRepo.insert(Product(name = "Widget", price = 9.99))
```

!!! note
    `insert` does not return the generated id. Query by a unique field if you need the assigned id immediately.

---

## `update(entity: T)`

```kotlin
suspend fun update(entity: T)
```

Updates the row whose primary key matches `entity.<pkProperty>`. All non-PK, non-`@Ignore` columns are set to the entity's current values.

```kotlin
val p = productRepo.findById("abc") ?: return
productRepo.update(p.copy(price = 12.99))
```

If no row with that primary key exists, the statement is a no-op (zero rows affected, no error).

---

## `delete(id: ID)`

```kotlin
suspend fun delete(id: ID)
```

Deletes the row with the given primary key. `ID` is the Kotlin type of the `@PrimaryKey` property.

```kotlin
productRepo.delete("abc")         // String PK
taskRepo.delete(42L)              // Long PK
```

If no row exists with that id, the statement is a no-op.

---

## `deleteWhere { predicate }`

```kotlin
suspend fun deleteWhere(predicate: ProductColumns.() -> Predicate): Int
```

Deletes all rows matching the DSL predicate. Returns the number of rows deleted.

```kotlin
val deleted = taskRepo.deleteWhere {
    (TaskColumns.projectId eq projectId) and
    (TaskColumns.isCompleted eq true)
}
```

See [DSL Operators](dsl-operators.md) for the full predicate DSL.

---

## `findById(id: ID): T?`

```kotlin
suspend fun findById(id: ID): T?
```

Returns the matching row, or `null` if not found. Never throws for a missing row.

```kotlin
val product: Product? = productRepo.findById("abc")
```

---

## `findAll(): List<T>`

```kotlin
suspend fun findAll(): List<T>
```

Returns all rows in insertion order. Returns an empty list (not null) when the table is empty.

```kotlin
val products: List<Product> = productRepo.findAll()
```

---

## `findWhere { predicate }: List<T>`

```kotlin
suspend fun findWhere(predicate: ProductColumns.() -> Predicate): List<T>
```

Returns all rows matching the DSL predicate. Returns an empty list when no rows match.

```kotlin
val inStock = productRepo.findWhere { ProductColumns.inStock eq true }
```

---

## `observeAll(): Flow<List<T>>`

```kotlin
fun observeAll(): Flow<List<T>>
```

Returns a cold `Flow` that emits the full table on collection and re-emits on every subsequent write (`insert`, `update`, `delete`, `deleteWhere`) to the same table.

Uses SQLDelight `Query.Listener` internally — no polling.

```kotlin
productRepo.observeAll()
    .collect { products -> adapter.submitList(products) }
```

---

## `observeWhere { predicate }: Flow<List<T>>`

```kotlin
fun observeWhere(predicate: ProductColumns.() -> Predicate): Flow<List<T>>
```

Like `observeAll()`, but filters by the DSL predicate on every emission.

```kotlin
val activeTasks: Flow<List<Task>> = taskRepo.observeWhere {
    TaskColumns.status inList listOf("TODO", "IN_PROGRESS")
}
```

!!! note
    The predicate is re-evaluated on every emission — not just the first. Changes to the underlying data that affect the predicate result in an updated list.

---

## `count(): Long`

```kotlin
suspend fun count(): Long
```

Returns the total number of rows in the table.

---

## `count { predicate }: Long`

```kotlin
suspend fun count(predicate: ProductColumns.() -> Predicate): Long
```

Returns the number of rows matching the predicate.

```kotlin
val openCount: Long = taskRepo.count {
    (TaskColumns.projectId eq projectId) and
    (TaskColumns.isCompleted eq false)
}
```

---

## Companion: `<Entity>Columns`

Each entity also generates a companion `<Entity>Columns` object used inside DSL lambdas:

```kotlin
object ProductColumns {
    val id: Column<String>
    val name: Column<String>
    val price: Column<Double>
    val inStock: Column<Boolean>
}
```

Column names reflect the actual SQL column names (accounting for `@Column(name = …)` overrides). Use these inside `findWhere`, `observeWhere`, `deleteWhere`, and `count` lambdas.
