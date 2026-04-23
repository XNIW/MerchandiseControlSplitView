package com.example.merchandisecontrolsplitview.data

import androidx.room.ColumnInfo
import androidx.room.Embedded

data class ProductWithDetails(
    @Embedded val product: Product,
    @ColumnInfo(name = "supplier_name") val supplierName: String?,
    @ColumnInfo(name = "category_name") val categoryName: String?,
    val lastPurchase: Double?,
    val prevPurchase: Double?,
    val lastRetail: Double?,
    val prevRetail: Double?
) {
    val currentPurchasePrice: Double?
        get() = lastPurchase ?: product.purchasePrice

    val currentRetailPrice: Double?
        get() = lastRetail ?: product.retailPrice

    fun productWithCurrentPrices(): Product =
        product.copy(
            purchasePrice = currentPurchasePrice,
            retailPrice = currentRetailPrice
        )
}
