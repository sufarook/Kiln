@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.sufarook.kiln.runtime

import cnames.structs.sqlite3
import cnames.structs.sqlite3_stmt
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.utf8
import kotlinx.cinterop.value
import sqlite3.SQLITE_DONE
import sqlite3.SQLITE_NULL
import sqlite3.SQLITE_OK
import sqlite3.SQLITE_OPEN_CREATE
import sqlite3.SQLITE_OPEN_FULLMUTEX
import sqlite3.SQLITE_OPEN_READWRITE
import sqlite3.SQLITE_ROW
import sqlite3.SQLITE_TRANSIENT
import sqlite3.sqlite3_bind_blob
import sqlite3.sqlite3_bind_double
import sqlite3.sqlite3_bind_int64
import sqlite3.sqlite3_bind_null
import sqlite3.sqlite3_bind_text
import sqlite3.sqlite3_busy_timeout
import sqlite3.sqlite3_changes
import sqlite3.sqlite3_close_v2
import sqlite3.sqlite3_column_blob
import sqlite3.sqlite3_column_bytes
import sqlite3.sqlite3_column_double
import sqlite3.sqlite3_column_int64
import sqlite3.sqlite3_column_text
import sqlite3.sqlite3_column_type
import sqlite3.sqlite3_errmsg
import sqlite3.sqlite3_exec
import sqlite3.sqlite3_finalize
import sqlite3.sqlite3_open_v2
import sqlite3.sqlite3_prepare_v2
import sqlite3.sqlite3_reset
import sqlite3.sqlite3_step

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosKilnDriver(dbPath: String) : BaseKilnDriver() {

    private val db: CPointer<sqlite3>
    private val statementCache = mutableMapOf<Int, CPointer<sqlite3_stmt>>()
    private var savepointId = 0

    init {
        db = memScoped {
            val dbPtr = alloc<CPointerVar<sqlite3>>()
            val flags = SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE or SQLITE_OPEN_FULLMUTEX
            val result = sqlite3_open_v2(dbPath, dbPtr.ptr, flags, null)
            check(result == SQLITE_OK) {
                val msg = sqlite3_errmsg(dbPtr.value)?.toKString() ?: "unknown error"
                sqlite3_close_v2(dbPtr.value)
                "Failed to open database '$dbPath': $msg"
            }
            dbPtr.value!!
        }
        sqlite3_busy_timeout(db, 5000)
        execRaw("PRAGMA journal_mode=WAL")
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (KilnPreparedStatement.() -> Unit)?
    ): Long {
        checkNotClosed()
        if (binders == null && parameters == 0) {
            execRaw(sql)
            return 0L
        }
        val stmt = getOrPrepare(identifier, sql)
        try {
            if (binders != null) IosPreparedStatement(stmt).binders()
            step(stmt, sql)
            return sqlite3_changes(db).toLong()
        } finally {
            if (identifier == null) sqlite3_finalize(stmt) else sqlite3_reset(stmt)
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
        val stmt = getOrPrepare(identifier, sql)
        if (binders != null) IosPreparedStatement(stmt).binders()
        return try {
            mapper(IosCursor(stmt))
        } finally {
            if (identifier == null) sqlite3_finalize(stmt) else sqlite3_reset(stmt)
        }
    }

    override fun newTransaction(): KilnTransaction {
        checkNotClosed()
        val enclosing = transactionStack
        return if (enclosing != null) {
            val name = "kiln_sp_${savepointId++}"
            execRaw("SAVEPOINT \"$name\"")
            val child = IosTransaction(enclosing, name)
            transactionStack = child
            child
        } else {
            execRaw("BEGIN IMMEDIATE")
            val tx = IosTransaction(null, null)
            transactionStack = tx
            tx
        }
    }

    override fun doClose() {
        statementCache.values.forEach { sqlite3_finalize(it) }
        statementCache.clear()
        sqlite3_close_v2(db)
    }

    private fun execRaw(sql: String) {
        val result = sqlite3_exec(db, sql, null, null, null)
        if (result != SQLITE_OK) {
            error("SQL error ($result) executing '$sql': ${sqlite3_errmsg(db)?.toKString()}")
        }
    }

    private fun getOrPrepare(identifier: Int?, sql: String): CPointer<sqlite3_stmt> {
        if (identifier != null) {
            statementCache[identifier]?.let {
                sqlite3_reset(it)
                return it
            }
        }
        val stmt = memScoped {
            val stmtPtr = alloc<CPointerVar<sqlite3_stmt>>()
            val result = sqlite3_prepare_v2(db, sql.utf8, -1, stmtPtr.ptr, null)
            check(result == SQLITE_OK) {
                "Failed to prepare '$sql': ${sqlite3_errmsg(db)?.toKString()}"
            }
            stmtPtr.value!!
        }
        if (identifier != null) statementCache[identifier] = stmt
        return stmt
    }

    private fun step(stmt: CPointer<sqlite3_stmt>, sql: String) {
        val result = sqlite3_step(stmt)
        if (result != SQLITE_DONE && result != SQLITE_ROW) {
            error("SQL step error ($result) executing '$sql': ${sqlite3_errmsg(db)?.toKString()}")
        }
    }

    private inner class IosTransaction(
        override val enclosingTransaction: KilnTransaction?,
        private val savepointName: String?
    ) : KilnTransaction {
        private var ended = false

        override fun endTransaction(successful: Boolean) {
            if (ended) return
            ended = true
            if (savepointName != null) {
                if (successful) {
                    execRaw("RELEASE \"$savepointName\"")
                } else {
                    execRaw("ROLLBACK TO \"$savepointName\"")
                    execRaw("RELEASE \"$savepointName\"")
                }
            } else {
                if (successful) execRaw("COMMIT") else execRaw("ROLLBACK")
            }
            transactionStack = enclosingTransaction
        }

        override fun childTransaction(): KilnTransaction = newTransaction()
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosPreparedStatement(
    private val stmt: CPointer<sqlite3_stmt>
) : KilnPreparedStatement {

    override fun bindString(index: Int, value: String?) {
        if (value == null) {
            sqlite3_bind_null(stmt, index + 1)
        } else {
            sqlite3_bind_text(stmt, index + 1, value, -1, SQLITE_TRANSIENT)
        }
    }

    override fun bindLong(index: Int, value: Long?) {
        if (value == null) {
            sqlite3_bind_null(stmt, index + 1)
        } else {
            sqlite3_bind_int64(stmt, index + 1, value)
        }
    }

    override fun bindDouble(index: Int, value: Double?) {
        if (value == null) {
            sqlite3_bind_null(stmt, index + 1)
        } else {
            sqlite3_bind_double(stmt, index + 1, value)
        }
    }

    override fun bindBytes(index: Int, value: ByteArray?) {
        if (value == null) {
            sqlite3_bind_null(stmt, index + 1)
        } else if (value.isEmpty()) {
            sqlite3_bind_blob(stmt, index + 1, null, 0, SQLITE_TRANSIENT)
        } else {
            sqlite3_bind_blob(stmt, index + 1, value.refTo(0), value.size, SQLITE_TRANSIENT)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosCursor(
    private val stmt: CPointer<sqlite3_stmt>
) : KilnCursor {

    override fun next(): Boolean = sqlite3_step(stmt) == SQLITE_ROW

    override fun getString(index: Int): String? {
        if (sqlite3_column_type(stmt, index) == SQLITE_NULL) return null
        return sqlite3_column_text(stmt, index)?.reinterpret<ByteVar>()?.toKString()
    }

    override fun getLong(index: Int): Long? {
        if (sqlite3_column_type(stmt, index) == SQLITE_NULL) return null
        return sqlite3_column_int64(stmt, index)
    }

    override fun getDouble(index: Int): Double? {
        if (sqlite3_column_type(stmt, index) == SQLITE_NULL) return null
        return sqlite3_column_double(stmt, index)
    }

    override fun getBytes(index: Int): ByteArray? {
        if (sqlite3_column_type(stmt, index) == SQLITE_NULL) return null
        val size = sqlite3_column_bytes(stmt, index)
        if (size == 0) return ByteArray(0)
        val blob = sqlite3_column_blob(stmt, index) ?: return ByteArray(0)
        return blob.reinterpret<ByteVar>().readBytes(size)
    }
}
