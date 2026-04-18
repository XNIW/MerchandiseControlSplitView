package com.example.merchandisecontrolsplitview.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class InventorySupplierRow(
    val id: String,
    @SerialName("owner_user_id") val ownerUserId: String,
    val name: String
)

@Serializable
data class InventoryCategoryRow(
    val id: String,
    @SerialName("owner_user_id") val ownerUserId: String,
    val name: String
)

@Serializable
data class InventoryProductRow(
    val id: String,
    @SerialName("owner_user_id") val ownerUserId: String,
    val barcode: String,
    @SerialName("item_number") val itemNumber: String? = null,
    @SerialName("product_name") val productName: String? = null,
    @SerialName("second_product_name") val secondProductName: String? = null,
    @SerialName("purchase_price") val purchasePrice: Double? = null,
    @SerialName("retail_price") val retailPrice: Double? = null,
    @SerialName("supplier_id") val supplierId: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("stock_quantity") val stockQuantity: Double? = null
)

data class InventoryCatalogFetchBundle(
    val suppliers: List<InventorySupplierRow>,
    val categories: List<InventoryCategoryRow>,
    val products: List<InventoryProductRow>
)

data class CatalogSyncSummary(
    val pushedSuppliers: Int,
    val pushedCategories: Int,
    val pushedProducts: Int,
    val pulledSuppliers: Int,
    val pulledCategories: Int,
    val pulledProducts: Int
)

internal fun fingerprintSupplierName(name: String): String = "s:" + name.trim().lowercase()

internal fun fingerprintCategoryName(name: String): String = "c:" + name.trim().lowercase()

internal fun fingerprintProductRow(p: Product, supplierRemoteId: String?, categoryRemoteId: String?): String =
    buildString {
        append("p:")
        append(p.barcode)
        append('|')
        append(p.itemNumber)
        append('|')
        append(p.productName)
        append('|')
        append(p.secondProductName)
        append('|')
        append(p.purchasePrice)
        append('|')
        append(p.retailPrice)
        append('|')
        append(supplierRemoteId)
        append('|')
        append(categoryRemoteId)
        append('|')
        append(p.stockQuantity)
    }

internal fun fingerprintProductInbound(row: InventoryProductRow): String =
    buildString {
        append("p:")
        append(row.barcode)
        append('|')
        append(row.itemNumber)
        append('|')
        append(row.productName)
        append('|')
        append(row.secondProductName)
        append('|')
        append(row.purchasePrice)
        append('|')
        append(row.retailPrice)
        append('|')
        append(row.supplierId)
        append('|')
        append(row.categoryId)
        append('|')
        append(row.stockQuantity)
    }
