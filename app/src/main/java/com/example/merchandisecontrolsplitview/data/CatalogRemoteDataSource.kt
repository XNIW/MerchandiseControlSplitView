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
}
