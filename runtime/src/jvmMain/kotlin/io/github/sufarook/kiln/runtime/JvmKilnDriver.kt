package io.github.sufarook.kiln.runtime

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

internal class JvmKilnDriver(private val connection: Connection) : BaseKilnDriver() {

    private val statementCache = mutableMapOf<Int, PreparedStatement>()
    private var savepointId = 0

    init {
        connection.autoCommit = true
        connection.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
        connection.createStatement().use { it.execute("PRAGMA busy_timeout=5000") }
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (KilnPreparedStatement.() -> Unit)?
    ): Long {
        checkNotClosed()
        synchronized(connection) {
            val stmt = prepareStatement(identifier, sql)
            if (binders != null) JvmPreparedStatement(stmt).binders()
            return try {
                if (sql.isInsertUpdateDelete()) {
                    stmt.executeUpdate().toLong()
                } else {
                    stmt.execute()
                    0L
                }
            } finally {
                if (identifier == null) {
                    stmt.close()
                } else {
                    stmt.clearParameters()
                }
            }
        }
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (KilnCursor) -> R,
        parameters: Int,
        binders: (KilnPreparedStatement.() -> Unit)?
    ): R {
        checkNotClosed()
        synchronized(connection) {
            val stmt = prepareStatement(identifier, sql)
            if (binders != null) JvmPreparedStatement(stmt).binders()
            val rs = stmt.executeQuery()
            return try {
                mapper(JvmCursor(rs))
            } finally {
                rs.close()
                if (identifier == null) {
                    stmt.close()
                } else {
                    stmt.clearParameters()
                }
            }
        }
    }

    override fun newTransaction(): KilnTransaction {
        checkNotClosed()
        synchronized(connection) {
            val enclosing = transactionStack
            return if (enclosing != null) {
                val name = "sp_${savepointId++}"
                connection.createStatement().use { it.execute("SAVEPOINT \"$name\"") }
                val child = JvmTransaction(enclosing, name)
                transactionStack = child
                child
            } else {
                connection.autoCommit = false
                val tx = JvmTransaction(null, null)
                transactionStack = tx
                tx
            }
        }
    }

    override fun doClose() {
        synchronized(connection) {
            statementCache.values.forEach { runCatching { it.close() } }
            statementCache.clear()
            connection.close()
        }
    }

    private fun prepareStatement(identifier: Int?, sql: String): PreparedStatement {
        if (identifier != null) {
            return statementCache.getOrPut(identifier) { connection.prepareStatement(sql) }
        }
        return connection.prepareStatement(sql)
    }

    private fun String.isInsertUpdateDelete(): Boolean {
        val trimmed = trimStart()
        return trimmed.startsWith("INSERT", ignoreCase = true) ||
            trimmed.startsWith("UPDATE", ignoreCase = true) ||
            trimmed.startsWith("DELETE", ignoreCase = true)
    }

    private inner class JvmTransaction(
        override val enclosingTransaction: KilnTransaction?,
        private val savepointName: String?
    ) : KilnTransaction {
        private var ended = false

        override fun endTransaction(successful: Boolean) {
            if (ended) return
            ended = true
            synchronized(connection) {
                if (savepointName != null) {
                    if (successful) {
                        connection.createStatement().use { it.execute("RELEASE \"$savepointName\"") }
                    } else {
                        connection.createStatement().use { it.execute("ROLLBACK TO \"$savepointName\"") }
                        connection.createStatement().use { it.execute("RELEASE \"$savepointName\"") }
                    }
                } else {
                    if (successful) connection.commit() else connection.rollback()
                    connection.autoCommit = true
                }
                transactionStack = enclosingTransaction
            }
        }

        override fun childTransaction(): KilnTransaction = newTransaction()
    }
}

private class JvmPreparedStatement(private val stmt: PreparedStatement) : KilnPreparedStatement {
    override fun bindString(index: Int, value: String?) {
        if (value == null) stmt.setNull(index + 1, Types.VARCHAR) else stmt.setString(index + 1, value)
    }

    override fun bindLong(index: Int, value: Long?) {
        if (value == null) stmt.setNull(index + 1, Types.BIGINT) else stmt.setLong(index + 1, value)
    }

    override fun bindDouble(index: Int, value: Double?) {
        if (value == null) stmt.setNull(index + 1, Types.DOUBLE) else stmt.setDouble(index + 1, value)
    }

    override fun bindBytes(index: Int, value: ByteArray?) {
        if (value == null) stmt.setNull(index + 1, Types.BLOB) else stmt.setBytes(index + 1, value)
    }
}

private class JvmCursor(private val rs: ResultSet) : KilnCursor {
    override fun next(): Boolean = rs.next()
    override fun getString(index: Int): String? = rs.getString(index + 1)
    override fun getLong(index: Int): Long? = rs.getLong(index + 1).let { if (rs.wasNull()) null else it }
    override fun getDouble(index: Int): Double? = rs.getDouble(index + 1).let { if (rs.wasNull()) null else it }
    override fun getBytes(index: Int): ByteArray? = rs.getBytes(index + 1)
}
