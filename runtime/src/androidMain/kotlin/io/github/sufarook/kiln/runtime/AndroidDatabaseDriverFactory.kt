package io.github.sufarook.kiln.runtime

import android.content.Context

class AndroidDatabaseDriverFactory(private val context: Context) {
    fun create(dbName: String): KilnDriver = AndroidKilnDriver(context, dbName)
}
