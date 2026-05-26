package com.example.merchandisecontrolsplitview.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order

private const val PRICE_UPSERT_CHUNK = 80

/**
 * Implementazione PostgREST per `inventory_product_prices` (task 016).
 * Batching confinato qui; task 022: fetch paginato completo (stesso schema catalogo).
 */
class SupabaseProductPriceRemoteDataSource(
    private val client: SupabaseClient?,
) : ProductPriceRemoteDataSource {

    override val isConfigured: Boolean get() = client != null

    private fun requireClient(): SupabaseClient =
        client ?: error("Supabase non configurato")

    override suspend fun upsertProductPrices(rows: List<InventoryProductPriceRow>): Result<Unit> =
        runCatching {
            if (rows.isEmpty()) return@runCatching
            val supabase = requireClient()
            for (chunk in rows.chunked(PRICE_UPSERT_CHUNK)) {
                supabase.postgrest["inventory_product_prices"].upsert(chunk) {
                    onConflict = "id"
                }
            }
        }

    override suspend fun fetchProductPrices(): Result<List<InventoryProductPriceRow>> =
        runCatching {
            requireClient().postgrest.fetchInventoryTableAllPagesOrderedById("inventory_product_prices")
        }

    override suspend fun fetchProductPricesPage(afterId: String?, limit: Long): Result<List<InventoryProductPriceRow>> =
        runCatching {
            val pageLimit = limit.coerceIn(1L, INVENTORY_REMOTE_PAGE_SIZE)
            requireClient().postgrest["inventory_product_prices"].select {
                if (!afterId.isNullOrBlank()) {
                    filter {
                        gt("id", afterId)
                    }
                }
                order("id", Order.ASCENDING)
                range(0, pageLimit - 1)
            }.decodeList()
        }

    override suspend fun fetchProductPricesByIds(remoteIds: Set<String>): Result<List<InventoryProductPriceRow>> =
        runCatching {
            if (remoteIds.isEmpty()) return@runCatching emptyList()
            val supabase = requireClient()
            val rows = mutableListOf<InventoryProductPriceRow>()
            for (chunk in remoteIds.chunked(PRICE_UPSERT_CHUNK)) {
                rows += supabase.postgrest["inventory_product_prices"].select {
                    filter {
                        isIn("id", chunk)
                    }
                }.decodeList()
            }
            rows
        }

    override suspend fun fetchProductPricesByProductIds(productRemoteIds: Set<String>): Result<List<InventoryProductPriceRow>> =
        runCatching {
            if (productRemoteIds.isEmpty()) return@runCatching emptyList()
            val supabase = requireClient()
            val rows = mutableListOf<InventoryProductPriceRow>()
            for (chunk in productRemoteIds.chunked(PRICE_UPSERT_CHUNK)) {
                rows += supabase.postgrest["inventory_product_prices"].select {
                    filter {
                        isIn("product_id", chunk)
                    }
                    order("id", Order.ASCENDING)
                }.decodeList()
            }
            rows
        }
}
