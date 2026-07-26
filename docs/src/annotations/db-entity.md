# @DbEntity

Marks a `data class` as a database entity. Kiln generates a repository and a columns object for every class annotated with `@DbEntity`.

## Signature

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class DbEntity(
    val tableName: String = ""
)
```

## Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `tableName` | `String` | `""` | The SQLite table name. If empty, the class name is used as-is (no automatic snake_case conversion). Set this explicitly to control the table name. |

!!! tip "Always set `tableName` explicitly"
    Relying on the class name as the table name means renaming the class changes the table name — which looks like a dropped table to the migrator. Set `tableName` to a stable string.

## Examples

```kotlin
@DbEntity(tableName = "users")
data class User(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val email: String
)
// Generated: UserRepository, UserColumns
// SQLite table: "users"
```

```kotlin
@DbEntity  // tableName defaults to "UserProfile"
data class UserProfile(
    @PrimaryKey val userId: Long,
    val bio: String = ""
)
// Generated: UserProfileRepository, UserProfileColumns
// SQLite table: "UserProfile"
```

## Constraints

!!! warning "Must be a data class"
    `@DbEntity` must be applied to a `data class`. Applying it to an abstract class, sealed class, interface, or regular class produces a compile-time error:
    ```
    Kiln: @DbEntity must be applied to a data class
    ```

!!! warning "Exactly one @PrimaryKey required"
    Every `@DbEntity` class must have exactly one property annotated with `@PrimaryKey`. Zero or multiple `@PrimaryKey` properties produce compile-time errors.

## Generated output

For `@DbEntity(tableName = "users") data class User(...)`:

- **`UserRepository`** — the full repository with `createTable()`, CRUD, reactive, and DSL methods.
- **`UserColumns`** — a companion object with a `Column<T>` property for every non-ignored field, used inside `findWhere { }`, `observeWhere { }`, `deleteWhere { }`, and `count { }`.
