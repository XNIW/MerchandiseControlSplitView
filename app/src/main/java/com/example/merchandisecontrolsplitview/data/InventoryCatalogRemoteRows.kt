package com.example.merchandisecontrolsplitview.data

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class InventorySupplierRow(
    val id: String,
    @SerialName("owner_user_id") val ownerUserId: String,
    @SerialName("shop_id") val shopId: String? = null,
    val name: String,
    @SerialName("updated_at") val updatedAt: String? = null,
    /** Null = attivo; valorizzato = tombstone remoto (task 019). */
    @SerialName("deleted_at")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val deletedAt: String? = null
)

@Serializable
data class InventoryCategoryRow(
    val id: String,
    @SerialName("owner_user_id") val ownerUserId: String,
    @SerialName("shop_id") val shopId: String? = null,
    val name: String,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("deleted_at")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val deletedAt: String? = null
)

@Serializable
data class InventoryProductRow(
    val id: String,
    @SerialName("owner_user_id") val ownerUserId: String,
    @SerialName("shop_id") val shopId: String? = null,
    val barcode: String,
    @SerialName("item_number") val itemNumber: String? = null,
    @SerialName("product_name") val productName: String? = null,
    @SerialName("second_product_name") val secondProductName: String? = null,
    @SerialName("purchase_price") val purchasePrice: Double? = null,
    @SerialName("retail_price") val retailPrice: Double? = null,
    @SerialName("supplier_id") val supplierId: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("stock_quantity") val stockQuantity: Double? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("deleted_at")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val deletedAt: String? = null
)

data class InventoryProductPatch(
    val changedFields: Set<String>,
    val barcode: String? = null,
    val itemNumber: String? = null,
    val productName: String? = null,
    val secondProductName: String? = null,
    val purchasePrice: Double? = null,
    val retailPrice: Double? = null,
    val supplierId: String? = null,
    val categoryId: String? = null,
    val stockQuantity: Double? = null,
    val deletedAt: String? = null
) {
    fun includes(field: String): Boolean = changedFields.contains(field)
    val isEmpty: Boolean get() = changedFields.isEmpty()
}

/** Patch minimo per tombstone remoto (UPDATE `deleted_at` / `updated_at`). */
data class CatalogTombstonePatch(
    val id: String,
    val ownerUserId: String,
    val shopId: String? = null,
    val deletedAt: String,
    val updatedAt: String
)

data class InventoryCatalogFetchBundle(
    val suppliers: List<InventorySupplierRow>,
    val categories: List<InventoryCategoryRow>,
    val products: List<InventoryProductRow>,
    val isCompleteSnapshot: Boolean = true
)

@Serializable
data class InventoryProductPriceRow(
    val id: String,
    @SerialName("owner_user_id") val ownerUserId: String,
    @SerialName("shop_id") val shopId: String? = null,
    @SerialName("product_id") val productId: String,
    val type: String,
    val price: Double,
    @SerialName("effective_at") val effectiveAt: String,
    val source: String? = null,
    val note: String? = null,
    @SerialName("created_at") val createdAt: String
)

/**
 * Snapshot read-only del pending **tombstone catalogo**, **bridge catalogo mancanti** e
 * **prezzi** verso il cloud (task 030, esteso in task 032).
 *
 * Resta diagnostico: il gate operativo resta [InventoryRepository.hasCatalogCloudPendingWorkInclusive].
 * I bridge catalogo dirty (`localChangeRevision > lastSyncedLocalRevision`) restano coperti
 * solo dal booleano inclusivo.
 */
data class CatalogCloudPendingBreakdown(
    /** Righe in [pending_catalog_tombstones] da drenare. */
    val pendingCatalogTombstones: Int,
    /**
     * Righe `product_prices` con `product_remote_refs` ma senza `product_price_remote_refs`
     * (candidate push storico prezzo — stesso filtro di [ProductPriceDao.getAllForCloudPush]).
     */
    val productPricesPendingPriceBridge: Int,
    /**
     * Righe `product_prices` il cui prodotto non ha ancora bridge cloud (`product_remote_refs`).
     * Stesso conteggio di [ProductPriceDao.countPriceRowsWithoutProductRemote] (gating / DEC-029).
     */
    val productPricesBlockedWithoutProductRemote: Int,
    /** Righe `suppliers` locali prive di `supplier_remote_refs`. */
    val suppliersMissingRemoteRef: Int = 0,
    /** Righe `categories` locali prive di `category_remote_refs`. */
    val categoriesMissingRemoteRef: Int = 0,
    /** Righe `products` locali prive di `product_remote_refs`. */
    val productsMissingRemoteRef: Int = 0
) {
    val hasTombstoneOrPriceRelatedPending: Boolean
        get() = pendingCatalogTombstones > 0 ||
            productPricesPendingPriceBridge > 0 ||
            productPricesBlockedWithoutProductRemote > 0

    val hasCatalogBridgeGaps: Boolean
        get() = suppliersMissingRemoteRef > 0 ||
            categoriesMissingRemoteRef > 0 ||
            productsMissingRemoteRef > 0

    val hasAnyPendingBreakdown: Boolean
        get() = hasTombstoneOrPriceRelatedPending || hasCatalogBridgeGaps
}

/** Conteggi attivi osservati nell'ultimo bundle catalogo (TASK-114 reconciliation). */
data class RemoteInventoryActiveCounts(
    val suppliers: Int,
    val categories: Int,
    val products: Int
)

data class CatalogPruneCounts(
    val suppliers: Int = 0,
    val categories: Int = 0,
    val products: Int = 0
) {
    val total: Int get() = suppliers + categories + products
}

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
    val priceSyncFailed: Boolean = false,
    /**
     * Task 044A: true solo dopo [CatalogRemoteDataSource.fetchCatalog] nella stessa operazione (sync completa / bootstrap).
     * La quick lane ([pushDirtyCatalogDeltaToRemote]) resta sempre false.
     */
    val fullCatalogFetch: Boolean = false,
    /**
     * Task 044A: true quando è stato eseguito pull prezzi full-page ([ProductPriceRemoteDataSource.fetchProductPrices])
     * nella stessa operazione; false nella sola lane push prezzi della quick sync.
     */
    val fullPriceFetch: Boolean = false,
    /** Task 044A: righe prodotto remote richieste/considerate nel pull; 0 nella quick lane. */
    val remoteProductIdsRequested: Int = 0,
    /** Task 044A: righe prodotto remote effettivamente ricevute; distinto da [pulledProducts] che conta gli apply. */
    val remoteProductsFetched: Int = 0,
    /** Task 044A: righe prezzo remote richieste/considerate nel pull; 0 nella quick lane. */
    val remotePriceIdsRequested: Int = 0,
    /** Task 044A: righe prezzo remote effettivamente ricevute; distinto da [pulledProductPrices] che conta gli apply. */
    val remotePricesFetched: Int = 0,
    /**
     * Task 044A: false se il contratto remoto **non** consente subset/catch-up affidabile senza full pull;
     * la quick sync non deve fingere pull incrementale.
     */
    val incrementalRemoteSubsetVerifiable: Boolean = true,
    /** Task 044A: codici stabili per log/UI quando [incrementalRemoteSubsetVerifiable] è false. */
    val incrementalRemoteNotVerifiableReason: String? = null,
    /** Task 044A: riservato a catch-up a watermark con scope oltre soglia (non usato in MVP 044A corrente). */
    val incrementalCatchUpTooLarge: Boolean = false,
    val syncEventsAvailable: Boolean = false,
    val recordSyncEventAvailable: Boolean = false,
    val realtimeSyncEventsAvailable: Boolean = false,
    val syncEventsFallback044: Boolean = false,
    val syncEventsDisabled: Boolean = false,
    val syncEventOutboxPending: Int = 0,
    val syncEventOutboxRetried: Int = 0,
    val syncEventsFetched: Int = 0,
    val syncEventsProcessed: Int = 0,
    val syncEventsSkippedSelf: Int = 0,
    val syncEventsSkippedDirtyLocal: Int = 0,
    val syncEventsWatermarkBefore: Long = 0L,
    val syncEventsWatermarkAfter: Long = 0L,
    val syncEventsTooLarge: Boolean = false,
    val syncEventsGapDetected: Boolean = false,
    val targetedProductsFetched: Int = 0,
    val targetedPricesFetched: Int = 0,
    val targetedHistoryFetched: Int = 0,
    val remoteUpdatesApplied: Int = 0,
    val remoteHistoryUpdatesApplied: Int = 0,
    val manualFullSyncRequired: Boolean = false,
    /** TASK-114: righe attive nel bundle remoto dell'ultimo full catalog pull. */
    val remoteActiveSuppliers: Int = 0,
    val remoteActiveCategories: Int = 0,
    val remoteActiveProducts: Int = 0,
    val prunedSuppliers: Int = 0,
    val prunedCategories: Int = 0,
    val prunedProducts: Int = 0
) {
    fun hasCatalogCountDrift(local: LocalDatabaseStatusSnapshot): Boolean {
        if (!fullCatalogFetch) return false
        return local.suppliers != remoteActiveSuppliers ||
            local.categories != remoteActiveCategories ||
            local.products != remoteActiveProducts
    }

    fun hasPriceCountDrift(localPriceHistoryRows: Int): Boolean {
        if (!fullPriceFetch || priceSyncFailed) return false
        return remotePricesFetched > 0 && localPriceHistoryRows != remotePricesFetched
    }
}

/**
 * Audit 044A: motivi sintetici quando non si implementa pull incrementale (contratto non verificabile).
 */
object CatalogIncrementalRemoteContract044A {
    const val INCREMENTAL_SUBSET_NOT_VERIFIABLE_CODES: String =
        "no_realtime_inventory_publication;inventory_product_prices_no_updated_at;products_updated_at_untrusted"
}

internal fun fingerprintSupplierName(name: String): String = "s:" + name.trim().lowercase()

internal fun fingerprintCategoryName(name: String): String = "c:" + name.trim().lowercase()

internal fun fingerprintSupplierInbound(row: InventorySupplierRow): String =
    fingerprintSupplierName(row.name) + "|u:" + row.updatedAt + "|d:" + row.deletedAt

internal fun fingerprintCategoryInbound(row: InventoryCategoryRow): String =
    fingerprintCategoryName(row.name) + "|u:" + row.updatedAt + "|d:" + row.deletedAt

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
        append("|u:")
        append(row.updatedAt)
        append("|d:")
        append(row.deletedAt)
    }
