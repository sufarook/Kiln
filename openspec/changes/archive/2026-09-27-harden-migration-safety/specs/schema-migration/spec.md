## Purpose

Defines how Kiln reconciles a live SQLite table against its entity definition on
every launch, and the guarantees that reconciliation makes about data it does not
own — rows in other tables, and the state of the connection it borrows.

## ADDED Requirements

### Requirement: Version-less reconciliation
Kiln SHALL determine the migration to apply by comparing the live table's columns
against the entity definition. It SHALL NOT read, write, or depend on any schema
version counter.

#### Scenario: Schema already matches
- **WHEN** reconciliation runs and the live columns match the entity
- **THEN** no schema statement is executed and existing rows are unchanged

#### Scenario: A property is added
- **WHEN** the entity gains a property absent from the live table
- **THEN** the column is added and existing rows retain their values, taking the
  declared default for the new column

### Requirement: Tables without an entity are never modified
Kiln SHALL only act on tables named by an entity it generated. A table with no
corresponding entity SHALL NOT be created, altered, rebuilt, or dropped.

#### Scenario: Unrelated table present in the same database
- **WHEN** reconciliation runs on a database containing tables Kiln has no entity for
- **THEN** those tables and their rows are left byte-for-byte unchanged

### Requirement: Rebuilding a table preserves rows in tables that reference it
A rebuild recreates the table, which under enabled foreign-key enforcement would
perform an implicit delete of every row and trigger referential actions on
referencing tables. Kiln SHALL prevent a rebuild from deleting or modifying rows
in any other table.

#### Scenario: A referencing table declares ON DELETE CASCADE
- **WHEN** foreign-key enforcement is enabled on the connection, another table
  references the Kiln table with `ON DELETE CASCADE`, and a change to the entity
  triggers a rebuild
- **THEN** the rebuild completes and every row in the referencing table survives

#### Scenario: Rebuild fails partway
- **WHEN** a rebuild raises an error after the table has been recreated
- **THEN** the table is left with its original rows and structure, and the error
  is propagated

### Requirement: Connection foreign-key setting is restored
Kiln borrows a connection it does not own. Any change it makes to foreign-key
enforcement in order to migrate safely SHALL be reverted before returning
control, so that behavior after migration matches behavior before it.

#### Scenario: Enforcement was enabled by other code
- **WHEN** foreign-key enforcement is enabled before reconciliation and a rebuild occurs
- **THEN** enforcement is enabled again once reconciliation returns

#### Scenario: Enforcement was disabled
- **WHEN** foreign-key enforcement is disabled before reconciliation and a rebuild occurs
- **THEN** enforcement remains disabled once reconciliation returns

### Requirement: Reconciliation does not run inside an open transaction
Foreign-key enforcement cannot be changed inside a transaction, and a rebuild
must control its own commit boundary. Kiln SHALL refuse to reconcile when a
transaction is already open on the connection.

#### Scenario: Table setup attempted inside a transaction
- **WHEN** table setup is invoked while a transaction is open on the connection
- **THEN** it fails with an error naming the cause, and no schema statement is executed

### Requirement: An entity must map to a table
An entity name that resolves to a database object other than a table cannot be
reconciled. Kiln SHALL detect this and fail rather than attempt to alter it.

#### Scenario: A view shares the entity's table name
- **WHEN** reconciliation runs and the entity's name resolves to a view
- **THEN** it fails with an error identifying the name and the conflict, and the
  view is left unchanged
