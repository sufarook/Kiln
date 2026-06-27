package com.farook.delightcrud.sample

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val app = application as SampleApplication
        val productRepo = ProductRepository(app.driver)
        val customerRepo = CustomerRepository(app.driver)

        productRepo.insert(Product("p1", "Laptop", 999.99, true))
        productRepo.insert(Product("p2", "Mouse", 29.99, true))

        customerRepo.insert(Customer(fullName = "Alice", email = "alice@example.com"))

        val products = productRepo.findAll()
        val customers = customerRepo.findAll()

        findViewById<TextView>(R.id.tvStatus).text =
            "Products: ${products.size}, Customers: ${customers.size}"
    }
}
