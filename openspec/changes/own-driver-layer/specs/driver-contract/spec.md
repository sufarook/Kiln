# Driver Contract

Specifies the behavioral contract of `KilnDriver`, `KilnPreparedStatement`,
`KilnCursor`, `KilnTransaction`, and `KilnListener`. Every platform
implementation must satisfy these requirements regardless of its backing
SQLite library.

## ADDED Requirements

### REQ-DRV-1: Execute returns affected row count

`KilnDriver.execute()` executes a non-query SQL statement (INSERT, UPDATE,
DELETE, DDL) and returns the number of rows affected as a `Long`. For DDL
statements (CREATE TABLE, ALTER TABLE, DROP TABLE) the return value is 0.

### REQ-DRV-2: ExecuteQuery runs mapper against cursor then closes it

`KilnDriver.executeQuery()` executes a query, passes the resulting
`KilnCursor` to the `mapper` function, closes the underlying cursor/statement
after the mapper returns, and returns the mapper's result. The cursor is
invalid after the mapper returns — implementations must not allow access to a
closed cursor.

### REQ-DRV-3: Execute and executeQuery accept binder lambdas

Both `execute` and `executeQuery` accept an optional `binders` lambda that
receives a `KilnPreparedStatement`. When non-null, `parameters` reflects the
number of bind slots in the SQL. The binder is called once before execution.

### REQ-DRV-4: Statement caching via identifier

When `identifier` is non-null, the implementation may cache the compiled
statement keyed by that integer and reuse it on subsequent calls with the same
identifier. When `identifier` is null, no caching occurs. Cached statements
are reset (bindings cleared) before each reuse. All cached statements are
finalized on `close()`.

### REQ-DRV-5: Transaction lifecycle

`newTransaction()` begins a database transaction (or a savepoint if a
transaction is already active) and returns a `KilnTransaction`.
`currentTransaction()` returns the innermost active transaction, or null if
none is active.

### REQ-DRV-6: Transaction commit and rollback

`KilnTransaction.endTransaction(true)` commits the transaction (or releases
the savepoint for nested transactions). `endTransaction(false)` rolls back
the transaction (or rolls back to the savepoint). Calling `endTransaction`
more than once on the same transaction instance is a no-op.

### REQ-DRV-7: Nested transactions use savepoints

When `newTransaction()` is called while a transaction is already active, the
implementation creates a named savepoint rather than issuing a nested `BEGIN`.
`childTransaction()` returns a `KilnTransaction` whose
`enclosingTransaction` is the parent. Commit releases the savepoint; rollback
rolls back to it. Rolling back a child does not roll back the parent.

### REQ-DRV-8: Parent rollback cascades to children

If a parent transaction is rolled back, all of its uncommitted children are
also rolled back. The database state reverts to before the parent's `BEGIN`.

### REQ-DRV-9: Listener notification

`addListener(queryKeys, listener)` registers a `KilnListener` for the given
query keys (typically table names). `removeListener(queryKeys, listener)`
unregisters it. `notifyListeners(queryKeys)` invokes
`queryResultsChanged()` on every listener registered for any of the given
keys. Notification is synchronous with respect to the caller.

### REQ-DRV-10: Close releases all resources

`KilnDriver.close()` finalizes all cached statements, closes the underlying
database connection, and releases any file locks. After `close()`, calling
any method other than `close()` itself throws `IllegalStateException`.
Calling `close()` on an already-closed driver is a no-op.

### REQ-DRV-11: Close with open transaction rolls back

If `close()` is called while a transaction is active, the implementation
rolls back the transaction before closing. No listener notifications fire
for the rollback.

### REQ-DRV-12: PreparedStatement bind semantics

`KilnPreparedStatement.bindString(index, null)`,
`bindLong(index, null)`, `bindDouble(index, null)`, and
`bindBytes(index, null)` all bind SQL NULL at the given index. Non-null
values bind the corresponding SQLite type (TEXT, INTEGER, REAL, BLOB).
Index is 1-based, matching SQLite's `sqlite3_bind_*` convention.

### REQ-DRV-13: Cursor column access semantics

`KilnCursor.getString(index)`, `getLong(index)`, `getDouble(index)`, and
`getBytes(index)` return `null` when the column value is SQL NULL. Index is
0-based. `next()` must be called before reading the first row; calling a
getter before `next()` or after `next()` returns `false` is undefined
behavior (implementations may throw or return null).

### REQ-DRV-14: Cursor type coercion follows SQLite rules

When a getter's requested type does not match the stored type, SQLite's
built-in type coercion applies (e.g., `getString` on an INTEGER column
returns the string representation). Implementations must not add their own
coercion layer — they delegate to SQLite's native behavior.

### REQ-DRV-15: KilnListener is a functional interface

`KilnListener` has a single abstract method `queryResultsChanged()`. It is
declared as `fun interface` so that lambda expressions can be used directly
when registering listeners.

### REQ-DRV-16: Empty byte array binding

`bindBytes(index, ByteArray(0))` binds a zero-length BLOB, not SQL NULL.
Zero-length BLOBs are valid SQLite values.

### REQ-DRV-17: SQL error propagation

If `execute()` or `executeQuery()` encounters a SQLite error (syntax error,
constraint violation, I/O error), the implementation throws an exception.
The exception type is platform-specific but must be an unchecked exception
(not a checked Java exception). The message must include the SQLite error
code and the original error message from SQLite.

### REQ-DRV-18: Mapper exception propagation

If the `mapper` function passed to `executeQuery()` throws an exception, the
cursor is closed and the exception propagates to the caller unchanged. The
implementation must not swallow or wrap mapper exceptions.

## ADDED Scenarios

### SCN-DRV-1: Insert returns row count of 1

Given an empty table with schema `(id INTEGER PRIMARY KEY, name TEXT)`,
when `execute(null, "INSERT INTO t (name) VALUES (?)", 1) { bindString(1, "a") }`
is called,
then the return value is 1.

### SCN-DRV-2: Delete returns affected count

Given a table with 3 rows,
when `execute(null, "DELETE FROM t WHERE id > ?", 1) { bindLong(1, 1) }` is called,
then the return value is 2 (rows with id 2 and 3 deleted).

### SCN-DRV-3: DDL returns 0

When `execute(null, "CREATE TABLE t (id INTEGER PRIMARY KEY)", 0)` is called,
then the return value is 0.

### SCN-DRV-4: Query maps rows through cursor

Given a table with rows `[(1, "a"), (2, "b")]`,
when `executeQuery(null, "SELECT id, name FROM t", mapper = { cursor -> buildList { while (cursor.next()) add(cursor.getString(1)) } }, 0)`
is called,
then the result is `["a", "b"]`.

### SCN-DRV-5: Bind null via nullable parameter

When `execute(null, "INSERT INTO t (name) VALUES (?)", 1) { bindString(1, null) }`
is called,
then the row's `name` column contains SQL NULL,
and `executeQuery(null, "SELECT name FROM t WHERE id = 1", { c -> c.next(); c.getString(0) }, 0)`
returns `null`.

### SCN-DRV-6: Empty byte array round-trips

When `bindBytes(1, ByteArray(0))` is used to insert a row,
then `getBytes(0)` on that row returns an empty `ByteArray` (not null).

### SCN-DRV-7: Transaction commits atomically

Given an empty table,
when a transaction inserts two rows and commits,
then both rows are visible after the transaction ends.

### SCN-DRV-8: Transaction rollback discards writes

Given an empty table,
when a transaction inserts two rows and then rolls back,
then the table is still empty.

### SCN-DRV-9: Nested transaction commits independently

Given a parent transaction that inserts row A,
when a child transaction inserts row B and commits,
and the parent also commits,
then both rows A and B are visible.

### SCN-DRV-10: Nested transaction rollback preserves parent

Given a parent transaction that inserts row A,
when a child transaction inserts row B and rolls back,
and the parent commits,
then only row A is visible (row B was discarded by the child rollback).

### SCN-DRV-11: Parent rollback discards committed child

Given a parent transaction,
when a child transaction inserts row B and commits,
and the parent rolls back,
then row B is not visible (parent rollback undoes everything including the
child's committed savepoint).

### SCN-DRV-12: Listener notified after write

Given a listener registered for key `"tasks"`,
when `notifyListeners("tasks")` is called,
then the listener's `queryResultsChanged()` fires exactly once.

### SCN-DRV-13: Listener not notified for unrelated key

Given a listener registered for key `"tasks"`,
when `notifyListeners("tags")` is called,
then the listener is not invoked.

### SCN-DRV-14: Removed listener not notified

Given a listener registered for `"tasks"` and then removed,
when `notifyListeners("tasks")` is called,
then the listener is not invoked.

### SCN-DRV-15: Close after open transaction rolls back

Given an open transaction that inserted a row,
when `close()` is called without committing,
then the row is not persisted (implicit rollback),
and subsequent calls to `execute()` throw `IllegalStateException`.

### SCN-DRV-16: Double close is no-op

Given a driver that has been closed,
when `close()` is called again,
then no exception is thrown.

### SCN-DRV-17: SQL syntax error throws

When `execute(null, "INVALID SQL", 0)` is called,
then an exception is thrown whose message contains the SQLite error.

### SCN-DRV-18: Constraint violation throws

Given a table with `UNIQUE` constraint on `name`,
when two rows with the same name are inserted,
then the second insert throws an exception whose message references the
constraint violation.

### SCN-DRV-19: Mapper exception closes cursor and propagates

Given a valid query,
when the mapper throws `IllegalArgumentException("test")`,
then the cursor is closed,
and the caller receives `IllegalArgumentException("test")` (not wrapped).

### SCN-DRV-20: Cached statement reuse

Given `execute(42, "INSERT INTO t (name) VALUES (?)", 1) { bindString(1, "a") }`
called once,
when `execute(42, "INSERT INTO t (name) VALUES (?)", 1) { bindString(1, "b") }`
is called again with the same identifier,
then both rows exist (the statement was reused, not re-compiled),
and the second call's bindings do not carry over from the first (reset
between uses).
