# Sample — Task Tracker

This sample demonstrates Krate in a realistic multi-table scenario. It covers entity relationships, reactive observation, cross-table data loading, and DSL queries.

## Scenario

A project management app with three tables and two one-to-many relationships:

```
Project (1) ──── (N) Task (1) ──── (N) ChecklistItem
```

- A **Project** is a top-level container.
- A **Task** belongs to a project. Tasks have a priority, status, and an optional due date.
- A **ChecklistItem** belongs to a task. A task can have any number of checklist items.

## What this sample covers

| Page | Demonstrates |
|------|-------------|
| [Entity Definitions](entities.md) | Three `@DbEntity` classes with `@Column(index = true)` on FK columns |
| [Initialization](initialization.md) | One driver, three repositories, `createTable()` at startup |
| [CRUD Operations](crud.md) | `insert`, `update`, `delete`, `findById`, `findAll` |
| [Reactive Queries](reactive.md) | `observeAll()` wired into `StateFlow` via `stateIn` |
| [Cross-table Loading](cross-table.md) | Store class coordinating multiple repositories |
| [DSL Queries](dsl.md) | Compound predicates, `inList`, `count`, `deleteWhere` |

[Start with entities :material-arrow-right:](entities.md){ .md-button .md-button--primary }
