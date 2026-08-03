package io.github.sufarook.kiln.runtime

import app.cash.sqldelight.db.SqlPreparedStatement

/**
 * Type-safe WHERE clauses without SQL strings. The processor generates a
 * `<Entity>Columns` object per entity; predicates compose with infix operators:
 *
 * ```kotlin
 * repo.findWhere { (priority eq 1) and (isCompleted eq false) }
 * repo.observeWhere { title like "Buy%" }
 * repo.deleteWhere { isCompleted eq true }
 * ```
 *
 * Column names are compile-time constants and every value is bound with `?` —
 * SQL injection is structurally impossible.
 */

/** A bound query argument, tagged with how it must be bound to the statement. */
sealed interface SqlArg {
    data class StringArg(val value: String?) : SqlArg
    data class LongArg(val value: Long?) : SqlArg
    data class DoubleArg(val value: Double?) : SqlArg
    data class BytesArg(val value: ByteArray?) : SqlArg
}

/** A typed reference to one table column. Instances are generated — do not construct by hand. */
class Column<T>(
    val name: String,
    val toArg: (T) -> SqlArg
)

/** A composed WHERE fragment plus its bound arguments. */
class Predicate internal constructor(
    val sql: String,
    val args: List<SqlArg>
) {
    infix fun and(other: Predicate): Predicate = Predicate("($sql AND ${other.sql})", args + other.args)

    infix fun or(other: Predicate): Predicate = Predicate("($sql OR ${other.sql})", args + other.args)
}

fun not(predicate: Predicate): Predicate = Predicate("(NOT ${predicate.sql})", predicate.args)

// ── Comparison operators ────────────────────────────────────────────────────────

infix fun <T> Column<T>.eq(value: T): Predicate = Predicate("\"$name\" = ?", listOf(toArg(value)))

infix fun <T> Column<T>.neq(value: T): Predicate = Predicate("\"$name\" != ?", listOf(toArg(value)))

infix fun <T> Column<T>.gt(value: T): Predicate = Predicate("\"$name\" > ?", listOf(toArg(value)))

infix fun <T> Column<T>.gte(value: T): Predicate = Predicate("\"$name\" >= ?", listOf(toArg(value)))

infix fun <T> Column<T>.lt(value: T): Predicate = Predicate("\"$name\" < ?", listOf(toArg(value)))

infix fun <T> Column<T>.lte(value: T): Predicate = Predicate("\"$name\" <= ?", listOf(toArg(value)))

/** SQL LIKE — use % and _ wildcards. Works on String and String? columns. */
infix fun Column<out String?>.like(pattern: String): Predicate = Predicate("\"$name\" LIKE ?", listOf(SqlArg.StringArg(pattern)))

fun Column<*>.isNull(): Predicate = Predicate("\"$name\" IS NULL", emptyList())

fun Column<*>.isNotNull(): Predicate = Predicate("\"$name\" IS NOT NULL", emptyList())

infix fun <T> Column<T>.inList(values: Collection<T>): Predicate {
    require(values.isNotEmpty()) { "inList requires at least one value" }
    val placeholders = values.joinToString(", ") { "?" }
    return Predicate("\"$name\" IN ($placeholders)", values.map(toArg))
}

infix fun <T> Column<T>.notInList(values: Collection<T>): Predicate {
    require(values.isNotEmpty()) { "notInList requires at least one value" }
    val placeholders = values.joinToString(", ") { "?" }
    return Predicate("\"$name\" NOT IN ($placeholders)", values.map(toArg))
}

fun <T> Column<T>.between(low: T, high: T): Predicate = Predicate("\"$name\" BETWEEN ? AND ?", listOf(toArg(low), toArg(high)))

// ── Ordering & pagination ───────────────────────────────────────────────────────

enum class Order { ASC, DESC }

data class OrderSpec(val columnName: String, val direction: Order)

fun <T> Column<T>.asc(): OrderSpec = OrderSpec(name, Order.ASC)
fun <T> Column<T>.desc(): OrderSpec = OrderSpec(name, Order.DESC)

/** Builds the ORDER BY / LIMIT / OFFSET suffix appended to WHERE queries. Used by generated code. */
fun buildOrderSuffix(orderBy: List<OrderSpec>, limit: Long?, offset: Long?): String = buildString {
    if (orderBy.isNotEmpty()) {
        append(" ORDER BY ")
        append(orderBy.joinToString(", ") { "\"${it.columnName}\" ${it.direction.name}" })
    }
    limit?.let { append(" LIMIT $it") }
    offset?.let { append(" OFFSET $it") }
}

// ── Statement binding (used by generated code) ─────────────────────────────────

fun SqlPreparedStatement.bindArg(index: Int, arg: SqlArg) {
    when (arg) {
        is SqlArg.StringArg -> bindString(index, arg.value)
        is SqlArg.LongArg -> bindLong(index, arg.value)
        is SqlArg.DoubleArg -> bindDouble(index, arg.value)
        is SqlArg.BytesArg -> bindBytes(index, arg.value)
    }
}
