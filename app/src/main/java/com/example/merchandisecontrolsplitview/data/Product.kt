package com.example.merchandisecontrolsplitview.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(
    tableName = "products",
    indices = [Index(value = ["barcode"], unique = true)]
)
data class Product(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val barcode: String,
    val itemNumber: String?,
    val productName: String?,
    val newPurchasePrice: Double?,
    val newRetailPrice: Double?,
    val oldPurchasePrice: Double?,
    val oldRetailPrice: Double?,
    val supplier: String?
)