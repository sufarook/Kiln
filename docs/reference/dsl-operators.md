# DSL Operators

All operators are used inside a lambda passed to `findWhere`, `observeWhere`, `deleteWhere`, or `count`. The lambda receiver is the generated `<Entity>Columns` object.

All values are bound as `?` parameters — SQL injection is structurally impossible.

---

## Equality

| Operator | SQL equivalent | Example |
|----------|---------------|---------|
| `col eq value` | `col = ?` | `TaskColumns.status eq "DONE"` |
| `col neq value` | `col != ?` | `TaskColumns.priority neq 0` |

---

## Comparison

| Operator | SQL equivalent | Example |
|----------|---------------|---------|
| `col lt value` | `col < ?` | `TaskColumns.priority lt 3` |
| `col lte value` | `col <= ?` | `TaskColumns.priority lte 3` |
| `col gt value` | `col > ?` | `TaskColumns.priority gt 1` |
| `col gte value` | `col >= ?` | `TaskColumns.priority gte 2` |

---

## Range

| Operator | SQL equivalent | Example |
|----------|---------------|---------|
| `col between lo and hi` | `col BETWEEN ? AND ?` | `TaskColumns.priority between 1 and 3` |

---

## Collection

| Operator | SQL equivalent | Example |
|----------|---------------|---------|
| `col inList list` | `col IN (?, ?, …)` | `TaskColumns.status inList listOf("TODO","IN_PROGRESS")` |
| `col notInList list` | `col NOT IN (?, ?, …)` | `TaskColumns.status notInList listOf("DONE","CANCELLED")` |

!!! warning
    Passing an empty list to `inList` or `notInList` throws `IllegalArgumentException`. Guard with `if (list.isNotEmpty())`.

---

## Null checks

| Operator | SQL equivalent | Example |
|----------|---------------|---------|
| `col.isNull()` | `col IS NULL` | `TaskColumns.dueDate.isNull()` |
| `col.isNotNull()` | `col IS NOT NULL` | `TaskColumns.dueDate.isNotNull()` |

!!! note
    `isNull()` and `isNotNull()` only make sense on nullable columns (property declared as `String?`, `Long?`, etc.). Using them on a non-nullable column is legal but always evaluates to `IS NOT NULL = true` and `IS NULL = false`.

---

## Text

| Operator | SQL equivalent | Example |
|----------|---------------|---------|
| `col like pattern` | `col LIKE ?` | `TaskColumns.title like "%design%"` |
| `col notLike pattern` | `col NOT LIKE ?` | `TaskColumns.title notLike "%archived%"` |

SQLite `LIKE` is case-insensitive for ASCII letters by default. Percent `%` matches any sequence; underscore `_` matches any single character.

---

## Logical combinators

| Operator | SQL equivalent | Example |
|----------|---------------|---------|
| `a and b` | `(a) AND (b)` | `(TaskColumns.projectId eq id) and (TaskColumns.isCompleted eq false)` |
| `a or b` | `(a) OR (b)` | `(TaskColumns.priority gt 2) or (TaskColumns.dueDate.isNull())` |
| `not(predicate)` | `NOT (predicate)` | `not(TaskColumns.isCompleted eq true)` |

Combinators generate fully parenthesised SQL, so precedence is always explicit regardless of nesting depth.

---

## Full example

```kotlin
// Tasks in a project that are either high-priority or overdue, and not cancelled
val tasks = taskRepo.findWhere {
    (TaskColumns.projectId eq projectId) and
    (
        (TaskColumns.priority gte 3) or
        TaskColumns.dueDate.isNotNull()
    ) and
    not(TaskColumns.status eq "CANCELLED")
}
```

Generated SQL (values bound separately):
```sql
SELECT * FROM tasks
WHERE (project_id = ?)
  AND ((priority >= ?) OR (due_date IS NOT NULL))
  AND NOT (status = ?)
```
