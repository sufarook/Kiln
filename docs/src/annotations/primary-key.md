# @PrimaryKey

Designates a property as (part of) the primary key. Every `@DbEntity` class must have at least one `@PrimaryKey` property — annotate two or more to form a composite key.

## Signature

```kotlin
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class PrimaryKey(
    val autoGenerate: Boolean = false
)
```

## Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `autoGenerate` | `Boolean` | `false` | When `true`, declares the column as `INTEGER PRIMARY KEY AUTOINCREMENT`. SQLite assigns the id on insert. The property type must be `Long` or `Int`. |

## Usage

=== "Auto-generated integer PK"

    The most common pattern. Pass `id = 0` on insert — SQLite assigns the real id.

    ```kotlin
    @DbEntity(tableName = "tasks")
    data class Task(
        @PrimaryKey(autoGenerate = true) val id: Long = 0,
        val title: String
    )

    // Insert — id is assigned by SQLite
    taskRepo.insert(Task(title = "Buy groceries"))

    // Read back with the generated id
    val tasks = taskRepo.findAll()  // tasks[0].id == 1L (or next autoincrement)
    ```

=== "Natural string PK"

    Use when the entity has a natural unique identifier (UUID, slug, barcode, etc.).

    ```kotlin
    @DbEntity(tableName = "products")
    data class Product(
        @PrimaryKey val sku: String,   // you assign the value
        val name: String,
        val price: Double
    )

    productRepo.insert(Product(sku = "WIDGET-001", name = "Widget", price = 9.99))
    val widget = productRepo.findById("WIDGET-001")
    ```

=== "Natural Long PK (no autoGenerate)"

    When you manage the id externally (e.g. from a server response).

    ```kotlin
    @DbEntity(tableName = "messages")
    data class Message(
        @PrimaryKey val id: Long,   // server-assigned, no default
        val body: String
    )
    ```

=== "Composite key"

    Annotate two or more properties to form a composite primary key — the usual
    case is a junction/join table, like an assignment of a user to a task.

    ```kotlin
    @DbEntity(tableName = "assignments")
    data class Assignment(
        @PrimaryKey val taskId: Long,
        @PrimaryKey val userId: Long,
        val assignedAt: String = ""
    )
    ```

    Kiln generates a `AssignmentKey(taskId, userId)` data class and uses it as the
    `ID` type everywhere a single-key entity would use its PK's own type directly:

    ```kotlin
    val key = AssignmentKey(taskId = 1L, userId = 2L)
    assignmentRepo.insert(Assignment(taskId = 1L, userId = 2L))
    val assignment = assignmentRepo.findById(key)
    assignmentRepo.delete(key)
    ```

    `update(entity)` is unaffected — it still takes the whole entity and matches
    on every `@PrimaryKey` property internally, same as a single key.

## Constraints

!!! warning "autoGenerate requires Long or Int"
    Setting `autoGenerate = true` on a property of any other type produces a compile-time error:
    ```
    Kiln: @PrimaryKey(autoGenerate = true) requires a Long or Int property
    ```

!!! warning "autoGenerate is not available on a composite key"
    `autoGenerate` only makes sense for a single integer PK. Setting it on any
    property once a class has two or more `@PrimaryKey` properties is a
    compile-time error:
    ```
    Kiln: '<Entity>' has a composite primary key — autoGenerate is not supported
    on any property of a composite key
    ```

!!! note "@Relation is not yet supported on a @PrimaryKey property"
    In a junction table like `Assignment` above, `taskId`/`userId` are also
    logically foreign keys, but `@Relation`'s `findBy<Parent>`/`observeBy<Parent>`/
    `deleteBy<Parent>` helpers aren't generated for PK properties yet. Filter on
    them directly instead: `assignmentRepo.findWhere { taskId eq id }` — the
    column is still generated normally on `AssignmentColumns`.
