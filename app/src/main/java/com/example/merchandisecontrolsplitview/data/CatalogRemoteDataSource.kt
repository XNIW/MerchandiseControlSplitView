package com.example.merchandisecontrolsplitview.data

/**
 * Adapter remoto catalogo (task 013). Implementazione fuori dal repository:
 * transport PostgREST / errori di rete restano qui; Room e bridge solo in [InventoryRepository].
 */
interface CatalogRemoteDataSource {

    val isConfigured: Boolean

    suspend fun upsertSuppliers(rows: List<InventorySupplierRow>): Result<Unit>

    suspend fun upsertSuppliers(rows: List<InventorySupplierRow>, shopId: String?): Result<Unit> =
        upsertSuppliers(rows.map { it.copy(shopId = shopId) })

    suspend fun upsertCategories(rows: List<InventoryCategoryRow>): Result<Unit>

    suspend fun upsertCategories(rows: List<InventoryCategoryRow>, shopId: String?): Result<Unit> =
        upsertCategories(rows.map { it.copy(shopId = shopId) })

    suspend fun upsertProducts(rows: List<InventoryProductRow>): Result<Unit>

    suspend fun upsertProducts(rows: List<InventoryProductRow>, shopId: String?): Result<Unit> =
        upsertProducts(rows.map { it.copy(shopId = shopId) })

    suspend fun patchProduct(id: String, ownerUserId: String, patch: InventoryProductPatch): Result<Unit> =
        Result.failure(UnsupportedOperationException("patchProduct not implemented"))

    suspend fun patchProduct(
        id: String,
        ownerUserId: String,
        shopId: String?,
        patch: InventoryProductPatch
    ): Result<Unit> =
        patchProduct(id, ownerUserId, patch)

    suspend fun fetchCatalog(): Result<InventoryCatalogFetchBundle>

    suspend fun fetchCatalog(shopId: String?): Result<InventoryCatalogFetchBundle> =
        fetchCatalog()

    suspend fun fetchCatalogByIds(
        supplierIds: Set<String>,
        categoryIds: Set<String>,
        productIds: Set<String>
    ): Result<InventoryCatalogFetchBundle>

    suspend fun fetchCatalogByIds(
        supplierIds: Set<String>,
        categoryIds: Set<String>,
        productIds: Set<String>,
        shopId: String?
    ): Result<InventoryCatalogFetchBundle> =
        fetchCatalogByIds(supplierIds, categoryIds, productIds)

    /** UPDATE tombstone: solo righe ancora attive (`deleted_at` null). Idempotente se già tombstonato. */
    suspend fun markSupplierTombstoned(patch: CatalogTombstonePatch): Result<Unit>

    suspend fun markSupplierTombstoned(patch: CatalogTombstonePatch, shopId: String?): Result<Unit> =
        markSupplierTombstoned(patch.copy(shopId = shopId))

    suspend fun markCategoryTombstoned(patch: CatalogTombstonePatch): Result<Unit>

    suspend fun markCategoryTombstoned(patch: CatalogTombstonePatch, shopId: String?): Result<Unit> =
        markCategoryTombstoned(patch.copy(shopId = shopId))

    suspend fun markProductTombstoned(patch: CatalogTombstonePatch): Result<Unit>

    suspend fun markProductTombstoned(patch: CatalogTombstonePatch, shopId: String?): Result<Unit> =
        markProductTombstoned(patch.copy(shopId = shopId))
}
