package com.example.merchandisecontrolsplitview.data

/**
 * Adapter remoto catalogo (task 013). Implementazione fuori dal repository:
 * transport PostgREST / errori di rete restano qui; Room e bridge solo in [InventoryRepository].
 */
interface CatalogRemoteDataSource {

    val isConfigured: Boolean

    suspend fun upsertSuppliers(rows: List<InventorySupplierRow>): Result<Unit>

    suspend fun upsertCategories(rows: List<InventoryCategoryRow>): Result<Unit>

    suspend fun upsertProducts(rows: List<InventoryProductRow>): Result<Unit>

    suspend fun fetchCatalog(): Result<InventoryCatalogFetchBundle>

    suspend fun fetchCatalogByIds(
        supplierIds: Set<String>,
        categoryIds: Set<String>,
        productIds: Set<String>
    ): Result<InventoryCatalogFetchBundle>

    /** UPDATE tombstone: solo righe ancora attive (`deleted_at` null). Idempotente se già tombstonato. */
    suspend fun markSupplierTombstoned(patch: CatalogTombstonePatch): Result<Unit>

    suspend fun markCategoryTombstoned(patch: CatalogTombstonePatch): Result<Unit>

    suspend fun markProductTombstoned(patch: CatalogTombstonePatch): Result<Unit>
}
