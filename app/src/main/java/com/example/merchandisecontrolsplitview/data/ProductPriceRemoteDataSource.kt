package com.example.merchandisecontrolsplitview.data

/**
 * Transport PostgREST per lo storico prezzi (task 016). Separato da [CatalogRemoteDataSource].
 */
interface ProductPriceRemoteDataSource {

    val isConfigured: Boolean

    suspend fun upsertProductPrices(rows: List<InventoryProductPriceRow>): Result<Unit>

    suspend fun upsertProductPrices(rows: List<InventoryProductPriceRow>, shopId: String?): Result<Unit> =
        upsertProductPrices(rows.map { it.copy(shopId = shopId) })

    suspend fun fetchProductPrices(): Result<List<InventoryProductPriceRow>>

    suspend fun fetchProductPrices(shopId: String?): Result<List<InventoryProductPriceRow>> =
        fetchProductPrices()

    suspend fun fetchProductPricesPage(afterId: String?, limit: Long): Result<List<InventoryProductPriceRow>> =
        fetchProductPrices().map { rows ->
            rows.asSequence()
                .sortedBy { it.id }
                .filter { afterId == null || it.id > afterId }
                .take(limit.coerceAtLeast(1L).toInt())
                .toList()
        }

    suspend fun fetchProductPricesPage(
        afterId: String?,
        limit: Long,
        shopId: String?
    ): Result<List<InventoryProductPriceRow>> =
        fetchProductPricesPage(afterId, limit)

    suspend fun fetchProductPricesByIds(remoteIds: Set<String>): Result<List<InventoryProductPriceRow>>

    suspend fun fetchProductPricesByIds(
        remoteIds: Set<String>,
        shopId: String?
    ): Result<List<InventoryProductPriceRow>> =
        fetchProductPricesByIds(remoteIds)

    suspend fun fetchProductPricesByProductIds(productRemoteIds: Set<String>): Result<List<InventoryProductPriceRow>> =
        Result.success(emptyList())

    suspend fun fetchProductPricesByProductIds(
        productRemoteIds: Set<String>,
        shopId: String?
    ): Result<List<InventoryProductPriceRow>> =
        fetchProductPricesByProductIds(productRemoteIds)
}
