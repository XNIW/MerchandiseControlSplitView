package com.example.merchandisecontrolsplitview.data

/**
 * Transport PostgREST per lo storico prezzi (task 016). Separato da [CatalogRemoteDataSource].
 */
interface ProductPriceRemoteDataSource {

    val isConfigured: Boolean

    suspend fun upsertProductPrices(rows: List<InventoryProductPriceRow>): Result<Unit>

    suspend fun fetchProductPrices(): Result<List<InventoryProductPriceRow>>

    suspend fun fetchProductPricesPage(afterId: String?, limit: Long): Result<List<InventoryProductPriceRow>> =
        fetchProductPrices().map { rows ->
            rows.asSequence()
                .sortedBy { it.id }
                .filter { afterId == null || it.id > afterId }
                .take(limit.coerceAtLeast(1L).toInt())
                .toList()
        }

    suspend fun fetchProductPricesByIds(remoteIds: Set<String>): Result<List<InventoryProductPriceRow>>
}
