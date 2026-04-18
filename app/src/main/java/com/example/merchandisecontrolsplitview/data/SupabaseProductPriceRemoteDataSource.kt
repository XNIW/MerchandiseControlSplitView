package com.example.merchandisecontrolsplitview.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest

private const val PRICE_UPSERT_CHUNK = 80

/**
 * Implementazione PostgREST per `inventory_product_prices` (task 016).
 * Batching confinato qui; full-select v1 (volume tipico inventario personale).
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
            requireClient().postgrest["inventory_product_prices"].select()
                .decodeList<InventoryProductPriceRow>()
        }
}
