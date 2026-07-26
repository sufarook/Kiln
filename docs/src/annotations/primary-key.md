# @PrimaryKey

Designates a property as the primary key column. Every `@DbEntity` class must have exactly one `@PrimaryKey` property.

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

## Constraints

!!! warning "autoGenerate requires Long or Int"
    Setting `autoGenerate = true` on a property of any other type produces a compile-time error:
    ```
    Kiln: @PrimaryKey(autoGenerate = true) requires a Long or Int property
    ```

!!! warning "Only one @PrimaryKey per entity"
    Composite primary keys are not supported. Each `@DbEntity` class must have exactly one `@PrimaryKey` property.
