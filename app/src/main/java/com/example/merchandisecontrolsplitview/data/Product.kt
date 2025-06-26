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
    val itemNumber: String? = null, // <-- MODIFICATO
    val productName: String?,
    val newPurchasePrice: Double? = null, // <-- MODIFICATO
    val newRetailPrice: Double? = null, // <-- MODIFICATO
    val oldPurchasePrice: Double? = null, // <-- MODIFICATO
    val oldRetailPrice: Double? = null, // <-- MODIFICATO
    val supplier: String? = null // <-- MODIFICATO
)