## Purpose

Defines when Kiln's writes become durable and when reactive queries re-emit,
including the cases where Kiln's transactions nest inside one another or inside a
transaction opened by other code sharing the same database connection.

## ADDED Requirements

### Requirement: A transaction commits once, at the outermost boundary
Kiln SHALL treat transaction blocks as nestable. Only the outermost block
commits; inner blocks join the transaction already in progress rather than
starting a second one.

#### Scenario: Transaction blocks are nested
- **WHEN** a Kiln transaction block is entered while another is already active
- **THEN** both complete without error and the writes from both commit together
  exactly once

#### Scenario: An inner block fails
- **WHEN** an inner nested block raises an exception
- **THEN** no write from any level of the nesting is committed, and the original
  exception is propagated to the caller

### Requirement: Kiln joins a transaction opened by other code
The connection may be shared with code that manages its own transactions. When a
transaction is already open on the connection, Kiln writes SHALL participate in
it rather than committing independently.

#### Scenario: A Kiln write runs inside an externally opened transaction
- **WHEN** other code opens a transaction on the connection and a Kiln write
  executes inside it
- **THEN** the write is not committed independently, and it becomes durable only
  when the enclosing transaction commits

#### Scenario: The enclosing transaction rolls back
- **WHEN** other code opens a transaction, a Kiln write executes inside it, and
  the enclosing transaction rolls back
- **THEN** the Kiln write is discarded along with the rest of the transaction

### Requirement: Reactive queries re-emit only after a successful commit
Observers SHALL NOT be notified of a write until that write is durable. Deferral
applies to any enclosing transaction, whether Kiln opened it or not.

#### Scenario: Transaction commits
- **WHEN** a transaction containing writes to a table commits successfully
- **THEN** observers of that table are notified exactly once, regardless of how
  many writes to it the transaction contained

#### Scenario: Transaction rolls back
- **WHEN** a transaction containing writes rolls back
- **THEN** no observer is notified for any table written inside it

#### Scenario: Write inside a transaction opened by other code
- **WHEN** a Kiln write executes inside a transaction opened by other code
- **THEN** observers are not notified while that transaction is open, and are
  notified once it commits

#### Scenario: Write outside any transaction
- **WHEN** a write executes with no transaction open
- **THEN** observers of that table are notified once the write completes

### Requirement: A failed transaction rolls back and surfaces its cause
Kiln SHALL roll back on any exception escaping a transaction block, and SHALL
propagate the original exception rather than an error raised during rollback.

#### Scenario: The block raises
- **WHEN** an exception escapes a transaction block
- **THEN** no write inside it is committed and that exception reaches the caller

#### Scenario: Rollback itself fails
- **WHEN** an exception escapes the block and the rollback also fails
- **THEN** the exception from the block is the one propagated
