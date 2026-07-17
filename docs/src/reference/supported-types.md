# Supported Types

The table below shows every Kotlin type DelightCRUD can persist, the SQLite column type used, and the bind/read method the generated code uses internally.

## Primitive types

| Kotlin type | SQLite type | Notes |
|-------------|-------------|-------|
| `String` | `TEXT` | Stored as-is |
| `Int` | `INTEGER` | Upcast to `Long` for SQLite bind; read back and downcast |
| `Long` | `INTEGER` | Native SQLite integer type |
| `Short` | `INTEGER` | Upcast to `Long` for bind; downcast on read |
| `Double` | `REAL` | Native SQLite real type |
| `Float` | `REAL` | Upcast to `Double` for bind; downcast on read |
| `Boolean` | `INTEGER` | `true` → `1`, `false` → `0`; read back via `getLong() == 1L` |
| `ByteArray` | `BLOB` | Stored as raw bytes |

## Date / time types

| Kotlin type | SQLite type | Format stored | Notes |
|-------------|-------------|--------------|-------|
| `kotlinx.datetime.LocalDate` | `TEXT` | ISO-8601 (`YYYY-MM-DD`) | Serialized via `.toString()`, parsed via `LocalDate.parse()` |
| `kotlinx.datetime.LocalDateTime` | `TEXT` | ISO-8601 (`YYYY-MM-DDTHH:MM:SS`) | Serialized via `.toString()`, parsed via `LocalDateTime.parse()` |
| `kotlinx.datetime.Instant` | `TEXT` | ISO-8601 with UTC offset | Serialized via `.toString()`, parsed via `Instant.parse()` |

!!! tip "Sorting by date works naturally"
    ISO-8601 strings sort lexicographically in the same order as their chronological order, so `ORDER BY` and range predicates on date columns work correctly without any special handling.

## Enum classes

| Kotlin type | SQLite type | Stored as | Read back via |
|-------------|-------------|-----------|--------------|
| Any `enum class` | `TEXT` | `enumValue.name` | `MyEnum.valueOf(getString())` |

```kotlin
enum class Status { TODO, IN_PROGRESS, DONE, CANCELLED }

@DbEntity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val status: Status = Status.TODO   // stored as "TODO", "IN_PROGRESS", etc.
)
```

!!! warning "Renaming enum values is a breaking change"
    If you rename a `Status.IN_PROGRESS` to `Status.ACTIVE`, rows with `"IN_PROGRESS"` stored will throw `IllegalArgumentException` when read. Migrate existing rows (update the text value in the database) before renaming.

## Nullable types

Any of the types above can be made nullable by appending `?`:

| Kotlin type | SQLite type | Behaviour |
|-------------|-------------|-----------|
| `String?` | `TEXT` | Column allows `NULL`; `null` Kotlin value stored as SQL `NULL` |
| `Long?` | `INTEGER` | Column allows `NULL` |
| `Boolean?` | `INTEGER` | `null` stored as SQL `NULL`; `true` → `1`, `false` → `0` |
| (any type)`?` | same base type | `NULL` allowed, `null` written and read correctly |

## Unsupported types

If a property type is not in the tables above and is not annotated with `@Ignore`, the KSP processor emits a build error:

```
e: DelightCRUD: type 'List<String>' is not supported.
   Annotate the property with @Ignore or use a supported type.
   File: com/example/MyEntity.kt:12
```

Common workarounds:

| Unsupported type | Workaround |
|-----------------|------------|
| `List<T>`, `Set<T>` | Serialize to JSON string; store as `String` with `@Ignore` on the typed property |
| `Map<K,V>` | Serialize to JSON string |
| Custom class | Store each field as a separate `@Column`, or serialize to `String` |
| `java.util.Date` | Migrate to `kotlinx.datetime.Instant` (natively supported) |
| `java.time.LocalDate` | Use `kotlinx.datetime.LocalDate` instead |
