package com.example.merchandisecontrolsplitview.data

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class InventorySupplierRow(
    val id: String,
    @SerialName("owner_user_id") val ownerUserId: String,
    val name: String,
    /** Null = attivo; valorizzato = tombstone remoto (task 019). */
    @SerialName("deleted_at")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val deletedAt: String? = null
)

@Serializable
data class InventoryCategoryRow(
    val id: String,
    @SerialName("owner_user_id") val ownerUserId: String,
    val name: String,
    @SerialName("deleted_at")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val deletedAt: String? = null
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
    @SerialName("stock_quantity") val stockQuantity: Double? = null,
    @SerialName("deleted_at")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val deletedAt: String? = null
)

/** Patch minimo per tombstone remoto (UPDATE `deleted_at` / `updated_at`). */
data class CatalogTombstonePatch(
    val id: String,
    val ownerUserId: String,
    val deletedAt: String,
    val updatedAt: String
)

data class InventoryCatalogFetchBundle(
    val suppliers: List<InventorySupplierRow>,
    val categories: List<InventoryCategoryRow>,
    val products: List<InventoryProductRow>
)

@Serializable
data class InventoryProductPriceRow(
    val id: String,
    @SerialName("owner_user_id") val ownerUserId: String,
    @SerialName("product_id") val productId: String,
    val type: String,
    val price: Double,
    @SerialName("effective_at") val effectiveAt: String,
    val source: String? = null,
    val note: String? = null,
    @SerialName("created_at") val createdAt: String
)

data class CatalogSyncSummary(
    val pushedSuppliers: Int,
    val pushedCategories: Int,
    val pushedProducts: Int,
    val pulledSuppliers: Int,
    val pulledCategories: Int,
    val pulledProducts: Int,
    /** Task 016: conteggi storico prezzi (0 se transport non configurato o errore prima del blocco prezzi). */
    val pushedProductPrices: Int = 0,
    val pulledProductPrices: Int = 0,
    /** Righe `product_prices` il cui prodotto non ha ancora `product_remote_refs`. */
    val deferredProductPricesNoProductRef: Int = 0,
    /** Righe remote il cui `product_id` non risolve un bridge locale (catalogo non allineato). */
    val skippedProductPricesPullNoProductRef: Int = 0,
    /** true se il blocco sync prezzi ha fallito dopo un catalogo considerato applicato. */
    val priceSyncFailed: Boolean = false
)

internal fun fingerprintSupplierName(name: String): String = "s:" + name.trim().lowercase()

internal fun fingerprintCategoryName(name: String): String = "c:" + name.trim().lowercase()

internal fun fingerprintSupplierInbound(row: InventorySupplierRow): String =
    fingerprintSupplierName(row.name) + "|d:" + row.deletedAt

internal fun fingerprintCategoryInbound(row: InventoryCategoryRow): String =
    fingerprintCategoryName(row.name) + "|d:" + row.deletedAt

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
        append("|d:")
        append(row.deletedAt)
    }
