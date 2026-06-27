package com.farook.delightcrud.sample

import com.farook.delightcrud.annotations.*

@DbEntity
data class Product(
    @PrimaryKey val id: String,
    val name: String,
    val price: Double,
    val inStock: Boolean
)

@DbEntity
data class Customer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fullName: String,
    @Column(name = "email_address", unique = true) val email: String,
    val loyaltyPoints: Int = 0
)

enum class OrderStatus { PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED }

@DbEntity
data class Order(
    @PrimaryKey val id: String,
    val customerId: Long,
    val status: OrderStatus,
    val totalAmount: Double,
    @Ignore val computedLabel: String = ""   // not persisted
)
