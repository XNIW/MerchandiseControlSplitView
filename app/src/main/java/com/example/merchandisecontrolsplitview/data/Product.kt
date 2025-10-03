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
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class Product(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val barcode: String = "",                 // <- default aggiunto
    val itemNumber: String? = null,
    val productName: String? = null,          // <- default aggiunto
    val secondProductName: String? = null,
    val purchasePrice: Double? = null,
    val retailPrice: Double? = null,
    val oldPurchasePrice: Double? = null,
    val oldRetailPrice: Double? = null,
    val supplierId: Long? = null,
    val categoryId: Long? = null,
    val stockQuantity: Double? = 0.0
) {
    // costruttore vuoto esplicito per Firestore
    constructor() : this(
        id = 0L,
        barcode = "",
        itemNumber = null,
        productName = null,
        secondProductName = null,
        purchasePrice = null,
        retailPrice = null,
        oldPurchasePrice = null,
        oldRetailPrice = null,
        supplierId = null,
        categoryId = null,
        stockQuantity = 0.0
    )
}