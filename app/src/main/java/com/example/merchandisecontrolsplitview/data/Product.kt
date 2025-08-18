package com.example.merchandisecontrolsplitview.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(
    tableName = "products",
    indices = [
        Index(value = ["barcode"], unique = true),
        Index(value = ["supplierId"]),
        Index(value = ["categoryId"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = Supplier::class,
            parentColumns = ["id"],
            childColumns = ["supplierId"],
            onDelete = ForeignKey.SET_NULL // Se un fornitore viene cancellato, il campo sul prodotto diventa null
        ),
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL // Se una categoria viene cancellata, il campo sul prodotto diventa null
        )
    ]
)
data class Product(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val barcode: String,
    val itemNumber: String? = null,
    val productName: String?,
    val secondProductName: String? = null,
    val purchasePrice: Double? = null,
    val retailPrice: Double? = null,
    val oldPurchasePrice: Double? = null,
    val oldRetailPrice: Double? = null,
    val supplierId: Long? = null,
    val categoryId: Long? = null,
    val stockQuantity: Double? = 0.0
)