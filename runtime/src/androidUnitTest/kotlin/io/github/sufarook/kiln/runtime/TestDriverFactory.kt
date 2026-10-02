package io.github.sufarook.kiln.runtime

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

actual fun createTestDriver(): KilnDriver = TestJdbcKilnDriver(DriverManager.getConnection("jdbc:sqlite::memory:"))

private class TestJdbcKilnDriver(private val connection: Connection) : BaseKilnDriver() {

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
        val stmt = connection.prepareStatement(sql)
        if (binders != null) Stmt(stmt).binders()
        return try {
            val t = sql.trimStart()
            if (t.startsWith("INSERT", true) || t.startsWith("UPDATE", true) || t.startsWith("DELETE", true)) {
                stmt.executeUpdate().toLong()
            } else {
                stmt.execute()
                0L
            }
        } finally {
            stmt.close()
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
        val stmt = connection.prepareStatement(sql)
        if (binders != null) Stmt(stmt).binders()
        val rs = stmt.executeQuery()
        return try {
            mapper(Cur(rs))
        } finally {
            rs.close()
            stmt.close()
        }
    }

    override fun newTransaction(): KilnTransaction {
        checkNotClosed()
        val enclosing = transactionStack
        return if (enclosing != null) {
            val name = "sp_${savepointId++}"
            connection.createStatement().use { it.execute("SAVEPOINT \"$name\"") }
            Tx(enclosing, name).also { transactionStack = it }
        } else {
            connection.autoCommit = false
            Tx(null, null).also { transactionStack = it }
        }
    }

    override fun doClose() {
        connection.close()
    }

    private inner class Tx(
        override val enclosingTransaction: KilnTransaction?,
        private val savepointName: String?
    ) : KilnTransaction {
        private var ended = false

        override fun endTransaction(successful: Boolean) {
            if (ended) return
            ended = true
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

        override fun childTransaction(): KilnTransaction = newTransaction()
    }
}

private class Stmt(private val s: PreparedStatement) : KilnPreparedStatement {
    override fun bindString(index: Int, value: String?) {
        if (value == null) s.setNull(index + 1, Types.VARCHAR) else s.setString(index + 1, value)
    }
    override fun bindLong(index: Int, value: Long?) {
        if (value == null) s.setNull(index + 1, Types.BIGINT) else s.setLong(index + 1, value)
    }
    override fun bindDouble(index: Int, value: Double?) {
        if (value == null) s.setNull(index + 1, Types.DOUBLE) else s.setDouble(index + 1, value)
    }
    override fun bindBytes(index: Int, value: ByteArray?) {
        if (value == null) s.setNull(index + 1, Types.BLOB) else s.setBytes(index + 1, value)
    }
}

private class Cur(private val rs: ResultSet) : KilnCursor {
    override fun next(): Boolean = rs.next()
    override fun getString(index: Int): String? = rs.getString(index + 1)
    override fun getLong(index: Int): Long? = rs.getLong(index + 1).let { if (rs.wasNull()) null else it }
    override fun getDouble(index: Int): Double? = rs.getDouble(index + 1).let { if (rs.wasNull()) null else it }
    override fun getBytes(index: Int): ByteArray? = rs.getBytes(index + 1)
}
