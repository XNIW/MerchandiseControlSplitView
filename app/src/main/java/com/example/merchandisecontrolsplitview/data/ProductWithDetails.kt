package com.example.merchandisecontrolsplitview.data

import androidx.room.ColumnInfo
import androidx.room.Embedded

data class ProductWithDetails(
    @Embedded val product: Product,
    @ColumnInfo(name = "supplier_name") val supplierName: String?,
    @ColumnInfo(name = "category_name") val categoryName: String?
)