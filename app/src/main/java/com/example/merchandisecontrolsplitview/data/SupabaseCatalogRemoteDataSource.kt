package com.example.merchandisecontrolsplitview.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.filter.FilterOperator

/**
 * Transport PostgREST per il catalogo (task 013). Nessun accesso a Room.
 * Task 022: fetch catalogo paginato (`order id` + range) per superare `max_rows` PostgREST.
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
            val pg = requireClient().postgrest
            val suppliers = pg.fetchInventoryTableAllPagesOrderedById<InventorySupplierRow>("inventory_suppliers")
            val categories = pg.fetchInventoryTableAllPagesOrderedById<InventoryCategoryRow>("inventory_categories")
            val products = pg.fetchInventoryTableAllPagesOrderedById<InventoryProductRow>("inventory_products")
            InventoryCatalogFetchBundle(suppliers, categories, products)
        }

    override suspend fun markSupplierTombstoned(patch: CatalogTombstonePatch): Result<Unit> =
        patchTombstone("inventory_suppliers", patch)

    override suspend fun markCategoryTombstoned(patch: CatalogTombstonePatch): Result<Unit> =
        patchTombstone("inventory_categories", patch)

    override suspend fun markProductTombstoned(patch: CatalogTombstonePatch): Result<Unit> =
        patchTombstone("inventory_products", patch)

    private suspend fun patchTombstone(table: String, patch: CatalogTombstonePatch): Result<Unit> =
        runCatching {
            requireClient().postgrest[table].update(
                update = {
                    set("deleted_at", patch.deletedAt)
                    set("updated_at", patch.updatedAt)
                },
                request = {
                    filter {
                        eq("id", patch.id)
                        eq("owner_user_id", patch.ownerUserId)
                        filter("deleted_at", FilterOperator.IS, "null")
                    }
                }
            )
        }
}
