package com.farook.delightcrud.sample

import android.app.Application
import app.cash.sqldelight.db.SqlDriver
import com.farook.delightcrud.runtime.AndroidDatabaseDriverFactory

class SampleApplication : Application() {

    lateinit var driver: SqlDriver
        private set

    override fun onCreate() {
        super.onCreate()
        driver = AndroidDatabaseDriverFactory(this).create("delightcrud_sample.db")

        // Each generated repository exposes createTable() — call once at startup
        ProductRepository(driver).createTable()
        CustomerRepository(driver).createTable()
        OrderRepository(driver).createTable()
    }
}
