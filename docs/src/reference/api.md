# Generated Methods

Every `@DbEntity` class gets one generated repository. This page documents every method on the generated `<Entity>Repository` class.

## Repository signature

```kotlin
class ProductRepository(private val driver: KilnDriver)
```

The generated class is concrete (not an interface). Inject the `KilnDriver` directly — see [Initialization](../sample/initialization.md).

---

## `createTable()`

```kotlin
fun createTable()
```

Creates the table if it does not already exist. Call during app startup, **before** any other method on that repository.

- Uses `CREATE TABLE IF NOT EXISTS` — safe to call more than once.
- Delegates to `SchemaMigrator.sync()` — adds, renames, removes, or recreates columns when the entity changes. See [Auto-migration](../migration.md).
- Throws `IllegalStateException` if called while a transaction is open on the driver, before changing anything — a migration can't be done safely inside one.

To set up every table at once, prefer `KilnSchema.createAll(driver)` below.

---

## `KilnSchema.createAll(driver)`

```kotlin
object KilnSchema {
    fun createAll(driver: KilnDriver)
}
```

A module-level object generated alongside your repositories, covering **every** `@DbEntity` Kiln found. Calls `createTable()` on each one:

```kotlin
KilnSchema.createAll(driver)
```

- Add a new entity and this call does not change — the object is regenerated on the next build.
- Generated into the deepest package your entities share. Entities in `com.example.data` and `com.example.model` put it in `com.example`.
- Order is irrelevant: Kiln emits no `FOREIGN KEY` constraints, so no table needs to exist before another.
- Per-repository `createTable()` still exists for cases where you want a table created conditionally or at a specific moment.

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

Returns all rows. Returns an empty list (not null) when the table is empty.

!!! note
    SQLite does not guarantee row order without an `ORDER BY` clause. Use `findWhere` with `orderBy` if you need deterministic ordering.

```kotlin
val products: List<Product> = productRepo.findAll()
```

---

## `findWhere { predicate }: List<T>`

```kotlin
suspend fun findWhere(
    orderBy: List<OrderSpec> = emptyList(),
    limit: Long = 0,
    offset: Long = 0,
    predicate: ProductColumns.() -> Predicate
): List<T>
```

Returns all rows matching the DSL predicate. Returns an empty list when no rows match.

| Parameter | Default | Description |
|-----------|---------|-------------|
| `orderBy` | `emptyList()` | Sort columns — use `Column.asc()` or `Column.desc()` |
| `limit` | `0` (no limit) | Maximum rows to return |
| `offset` | `0` | Rows to skip before returning |
| `predicate` | *(required)* | DSL filter — see [DSL Operators](dsl-operators.md) |

```kotlin
// Basic filter
val inStock = productRepo.findWhere { ProductColumns.inStock eq true }

// Sorted and paginated
val page = productRepo.findWhere(
    orderBy = listOf(ProductColumns.price.desc()),
    limit = 20,
    offset = 40
) { ProductColumns.inStock eq true }
```

!!! tip "Imports for ordering"
    ```kotlin
    import io.github.sufarook.kiln.runtime.asc
    import io.github.sufarook.kiln.runtime.desc
    ```

---

## `observeAll(): Flow<List<T>>`

```kotlin
fun observeAll(): Flow<List<T>>
```

Returns a cold `Flow` that emits the full table on collection and re-emits on every subsequent write (`insert`, `update`, `delete`, `deleteWhere`) to the same table.

Uses `KilnListener` internally — no polling.

```kotlin
productRepo.observeAll()
    .collect { products -> adapter.submitList(products) }
```

---

## `observeWhere { predicate }: Flow<List<T>>`

```kotlin
fun observeWhere(
    orderBy: List<OrderSpec> = emptyList(),
    limit: Long = 0,
    offset: Long = 0,
    predicate: ProductColumns.() -> Predicate
): Flow<List<T>>
```

Like `observeAll()`, but filters by the DSL predicate on every emission. Accepts the same `orderBy` / `limit` / `offset` parameters as `findWhere`.

```kotlin
// Basic reactive filter
val activeTasks: Flow<List<Task>> = taskRepo.observeWhere {
    TaskColumns.status inList listOf("TODO", "IN_PROGRESS")
}

// Reactive paginated feed
val topProducts: Flow<List<Product>> = productRepo.observeWhere(
    orderBy = listOf(ProductColumns.price.desc()),
    limit = 10
) { ProductColumns.inStock eq true }
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

## `insertAll(entities: List<T>)`

```kotlin
suspend fun insertAll(entities: List<T>)
```

Batch-inserts a list of entities inside a single transaction. Reactive observers receive one `Flow` emission after all rows are committed rather than one per `insert` call.

```kotlin
taskRepo.insertAll(importedTasks)
```

Internally calls `driver.withTransaction { entities.forEach { insert(it) } }`. If any insert fails, the entire batch is rolled back.

---

## `findBy<Parent>(parentId: PK): List<T>`

Generated for each `@Relation`-annotated foreign-key property. Returns all child rows whose FK column equals `parentId`.

```kotlin
// Task has: @Relation val projectId: Long
val tasks: List<Task> = taskRepo.findByProject(projectId)
```

---

## `observeBy<Parent>(parentId: PK): Flow<List<T>>`

Reactive variant of `findBy<Parent>`. Re-emits whenever the child table changes.

```kotlin
val liveTasksFlow: Flow<List<Task>> = taskRepo.observeByProject(projectId)
```

---

## `deleteBy<Parent>(parentId: PK)`

Deletes all child rows whose FK column equals `parentId`. Used for cascade deletes.

```kotlin
// Remove all tasks before deleting the parent project
taskRepo.deleteByProject(projectId)
projectRepo.delete(projectId)
```

For cleaner cascade semantics, wrap both calls in `driver.withTransaction { … }` so both tables are notified in a single `Flow` emission.

---

## Transactions

### `driver.withTransaction { }`

```kotlin
suspend fun KilnDriver.withTransaction(
    context: CoroutineContext = Dispatchers.Default,
    block: suspend () -> Unit
)
```

Executes `block` inside a single SQLite transaction. On any exception the transaction is rolled back and the exception re-thrown.

Blocks nest: a `withTransaction` inside another joins it, and only the outermost one commits. A block run while a transaction is already open via `driver.newTransaction()` joins that transaction too. A transaction opened with raw `BEGIN` SQL is invisible to Kiln and can't be joined.

All Kiln repository write methods (`insert`, `update`, `delete`, `deleteWhere`, `insertAll`) defer their `Flow` listener notifications while a transaction is open. After the outermost commit each affected table is notified **exactly once**: reactive `Flow`s receive one emission for the whole transaction rather than one per operation.

No notifications are sent when a transaction is rolled back.

**Threading.** A transaction holds one thread of `context` until it completes. SQLite transactions belong to the thread that opened them, so the block — including after any suspension — runs on that thread, and repository calls inside it run there too, whatever dispatcher the repository was constructed with. For a transaction that waits on I/O, pass `Dispatchers.IO` so it doesn't occupy one of `Dispatchers.Default`'s few threads.

```kotlin
// Cascade delete — observers see one emission each, not four
driver.withTransaction {
    taskRepo.deleteByProject(projectId)   // deferred
    projectRepo.delete(projectId)         // deferred
}
// notifyListeners fires here, once per table

// Batch import — observers see all rows appear atomically
driver.withTransaction {
    taskRepo.insertAll(newTasks)
}
```

### `driver.notifyOrDefer(tableName)`

```kotlin
suspend fun KilnDriver.notifyOrDefer(tableName: String)
```

Called automatically by generated repository code. Outside a transaction it calls `KilnDriver.notifyListeners` immediately. Inside one it adds the table name to the open transaction's pending tables, deferring the notification to after the outermost commit. Not intended for direct use.

### `driver.withTransactionAwareContext(context) { }`

```kotlin
suspend fun <T> KilnDriver.withTransactionAwareContext(
    context: CoroutineContext,
    block: suspend CoroutineScope.() -> T
): T
```

Called automatically by generated repository code in place of `withContext(context)`. Inside a transaction it runs `block` on the transaction's thread instead of switching to `context`; otherwise it behaves exactly like `withContext`. Not intended for direct use.

---

## Raw SQL (`KilnDriver`)

When you need operations Kiln doesn't generate — aggregates, JOINs, GROUP BY, or one-off DDL — use the driver directly:

### `driver.execute()`

Executes a write statement (INSERT, UPDATE, DELETE, DDL):

```kotlin
driver.execute(null, "UPDATE products SET price = price * 1.1 WHERE category_id = ?", 1) {
    bindLong(0, categoryId)
}
```

### `driver.executeQuery()`

Executes a read statement and maps results from the cursor:

```kotlin
val totalRevenue = driver.executeQuery(
    null,
    "SELECT COALESCE(SUM(total_amount), 0) FROM orders WHERE status = ?",
    mapper = { cursor ->
        cursor.next()
        cursor.getDouble(0) ?: 0.0
    },
    parameters = 1
) {
    bindString(0, "COMPLETED")
}
```

A multi-row example with JOIN and GROUP BY:

```kotlin
data class CategorySales(val name: String, val total: Double)

val sales = driver.executeQuery(
    null,
    """
    SELECT c.name, SUM(oi.quantity * oi.unit_price) AS total
    FROM order_items oi
    INNER JOIN products p ON oi.product_id = p.id
    INNER JOIN categories c ON p.category_id = c.id
    GROUP BY c.name
    ORDER BY total DESC
    """.trimIndent(),
    mapper = { cursor ->
        buildList {
            while (cursor.next().value) {
                add(CategorySales(
                    name = cursor.getString(0) ?: "",
                    total = cursor.getDouble(1) ?: 0.0
                ))
            }
        }
    },
    parameters = 0
)
```

!!! warning
    Raw writes (`driver.execute`) do not trigger Kiln's `KilnListener`. Reactive flows (`observeAll`, `observeWhere`) won't re-emit for those changes. If you need reactivity after a raw write, call `driver.notifyListeners("<table_name>")`.

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
