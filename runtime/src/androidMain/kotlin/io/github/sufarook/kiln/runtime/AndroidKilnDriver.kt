package io.github.sufarook.kiln.runtime

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement

internal class AndroidKilnDriver(context: Context, dbName: String) : BaseKilnDriver() {

    private val helper = object : SQLiteOpenHelper(context, dbName, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {}
        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}
        override fun onConfigure(db: SQLiteDatabase) {
            db.enableWriteAheadLogging()
        }
    }

    private var savepointId = 0

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (KilnPreparedStatement.() -> Unit)?
    ): Long {
        checkNotClosed()
        val db = helper.writableDatabase
        if (binders == null && parameters == 0) {
            db.execSQL(sql)
            return 0L
        }
        val stmt = db.compileStatement(sql)
        return try {
            if (binders != null) AndroidPreparedStatement(stmt).binders()
            if (sql.isInsertUpdateDelete()) {
                stmt.executeUpdateDelete().toLong()
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
        val bindArgs = if (binders != null) {
            val collector = AndroidBindCollector(parameters)
            collector.binders()
            collector.toStringArray()
        } else {
            null
        }
        val cursor = helper.readableDatabase.rawQuery(sql, bindArgs)
        return try {
            mapper(AndroidCursor(cursor))
        } finally {
            cursor.close()
        }
    }

    override fun newTransaction(): KilnTransaction {
        checkNotClosed()
        val enclosing = transactionStack
        val db = helper.writableDatabase
        return if (enclosing != null) {
            val name = "sp_${savepointId++}"
            db.execSQL("SAVEPOINT \"$name\"")
            val child = AndroidTransaction(db, enclosing, name)
            transactionStack = child
            child
        } else {
            db.beginTransactionNonExclusive()
            val tx = AndroidTransaction(db, null, null)
            transactionStack = tx
            tx
        }
    }

    override fun doClose() {
        helper.close()
    }

    private fun String.isInsertUpdateDelete(): Boolean {
        val trimmed = trimStart()
        return trimmed.startsWith("INSERT", ignoreCase = true) ||
            trimmed.startsWith("UPDATE", ignoreCase = true) ||
            trimmed.startsWith("DELETE", ignoreCase = true)
    }

    private inner class AndroidTransaction(
        private val db: SQLiteDatabase,
        override val enclosingTransaction: KilnTransaction?,
        private val savepointName: String?
    ) : KilnTransaction {
        private var ended = false

        override fun endTransaction(successful: Boolean) {
            if (ended) return
            ended = true
            if (savepointName != null) {
                if (successful) {
                    db.execSQL("RELEASE \"$savepointName\"")
                } else {
                    db.execSQL("ROLLBACK TO \"$savepointName\"")
                    db.execSQL("RELEASE \"$savepointName\"")
                }
            } else {
                if (successful) db.setTransactionSuccessful()
                db.endTransaction()
            }
            transactionStack = enclosingTransaction
        }

        override fun childTransaction(): KilnTransaction = newTransaction()
    }
}

private class AndroidPreparedStatement(private val stmt: SQLiteStatement) : KilnPreparedStatement {
    override fun bindString(index: Int, value: String?) {
        if (value == null) stmt.bindNull(index + 1) else stmt.bindString(index + 1, value)
    }

    override fun bindLong(index: Int, value: Long?) {
        if (value == null) stmt.bindNull(index + 1) else stmt.bindLong(index + 1, value)
    }

    override fun bindDouble(index: Int, value: Double?) {
        if (value == null) stmt.bindNull(index + 1) else stmt.bindDouble(index + 1, value)
    }

    override fun bindBytes(index: Int, value: ByteArray?) {
        if (value == null) stmt.bindNull(index + 1) else stmt.bindBlob(index + 1, value)
    }
}

private class AndroidBindCollector(size: Int) : KilnPreparedStatement {
    private val args = arrayOfNulls<String>(size)

    override fun bindString(index: Int, value: String?) {
        args[index] = value
    }
    override fun bindLong(index: Int, value: Long?) {
        args[index] = value?.toString()
    }
    override fun bindDouble(index: Int, value: Double?) {
        args[index] = value?.toString()
    }
    override fun bindBytes(index: Int, value: ByteArray?) {
        args[index] = value?.let { String(it, Charsets.ISO_8859_1) }
    }

    fun toStringArray(): Array<String?> = args
}

private class AndroidCursor(private val cursor: Cursor) : KilnCursor {
    override fun next(): Boolean = cursor.moveToNext()

    override fun getString(index: Int): String? = if (cursor.isNull(index)) null else cursor.getString(index)

    override fun getLong(index: Int): Long? = if (cursor.isNull(index)) null else cursor.getLong(index)

    override fun getDouble(index: Int): Double? = if (cursor.isNull(index)) null else cursor.getDouble(index)

    override fun getBytes(index: Int): ByteArray? = if (cursor.isNull(index)) null else cursor.getBlob(index)
}
