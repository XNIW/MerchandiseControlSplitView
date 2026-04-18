package com.example.merchandisecontrolsplitview.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest

/**
 * Transport PostgREST per il catalogo (task 013). Nessun accesso a Room.
 */
class SupabaseCatalogRemoteDataSource(
    private val client: SupabaseClient?,
) : CatalogRemoteDataSource {

    override val isConfigured: Boolean get() = client != null

    private fun requireClient(): SupabaseClient =
        client ?: error("Supabase non configurato")

    override suspend fun upsertSuppliers(rows: List<InventorySupplierRow>): Result<Unit> =
        runCatching {
            if (rows.isEmpty()) return@runCatching
            requireClient().postgrest["inventory_suppliers"].upsert(rows) {
                onConflict = "id"
            }
        }

    override suspend fun upsertCategories(rows: List<InventoryCategoryRow>): Result<Unit> =
        runCatching {
            if (rows.isEmpty()) return@runCatching
            requireClient().postgrest["inventory_categories"].upsert(rows) {
                onConflict = "id"
            }
        }

    override suspend fun upsertProducts(rows: List<InventoryProductRow>): Result<Unit> =
        runCatching {
            if (rows.isEmpty()) return@runCatching
            requireClient().postgrest["inventory_products"].upsert(rows) {
                onConflict = "id"
            }
        }

    override suspend fun fetchCatalog(): Result<InventoryCatalogFetchBundle> =
        runCatching {
            val supabase = requireClient()
            val suppliers = supabase.postgrest["inventory_suppliers"].select()
                .decodeList<InventorySupplierRow>()
            val categories = supabase.postgrest["inventory_categories"].select()
                .decodeList<InventoryCategoryRow>()
            val products = supabase.postgrest["inventory_products"].select()
                .decodeList<InventoryProductRow>()
            InventoryCatalogFetchBundle(suppliers, categories, products)
        }
}
