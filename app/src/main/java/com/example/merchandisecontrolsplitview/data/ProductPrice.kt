package com.example.merchandisecontrolsplitview.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(
    tableName = "product_prices",
    foreignKeys = [ForeignKey(
        entity = Product::class,
        parentColumns = ["id"],
        childColumns = ["productId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["productId","type","effectiveAt"], unique = true),
        Index(value = ["productId","type","createdAt"])
    ]
)
data class ProductPrice(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val productId: Long,
    val type: String,             // "PURCHASE" | "RETAIL"
    val price: Double,
    val effectiveAt: String,      // "yyyy-MM-dd HH:mm:ss"
    val source: String? = null,   // "IMPORT" | "MANUAL" | "SYNC"
    val note: String? = null,
    val createdAt: String = effectiveAt
)