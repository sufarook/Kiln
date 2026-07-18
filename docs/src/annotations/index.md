# Annotations

Krate provides four annotations. All have `SOURCE` retention — they are consumed entirely at compile time and produce no runtime overhead.

| Annotation | Target | Purpose |
|-----------|--------|---------|
| [`@DbEntity`](db-entity.md) | Class | Marks a data class as a database table |
| [`@PrimaryKey`](primary-key.md) | Property | Designates the primary key column |
| [`@Column`](column.md) | Property | Overrides column name, adds constraints, hints migration |
| [`@Ignore`](ignore.md) | Property | Excludes a property from the schema |

## Minimal example

```kotlin
@DbEntity(tableName = "products")
data class Product(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,  // (1)!
    val name: String,                                    // (2)!
    @Column(name = "price_cents") val price: Long,       // (3)!
    @Column(unique = true) val sku: String,              // (4)!
    @Ignore val displayPrice: String = ""                // (5)!
)
```

1. Auto-generated integer PK — pass `id = 0` on insert, SQLite assigns the real id.
2. No `@Column` needed — property name is used as column name as-is.
3. `@Column(name = …)` overrides the column name in SQLite.
4. `@Column(unique = true)` adds a `UNIQUE` constraint.
5. `@Ignore` — not stored, not read back from the database.

## What gets generated

For `Product` above, Krate generates:

- `ProductRepository` — all CRUD, reactive, and DSL methods
- `ProductColumns` — typed `Column<T>` references for use in DSL queries:

```kotlin
val expensiveProducts = productRepo.findWhere {
    ProductColumns.price gt 10_000L
}
```
