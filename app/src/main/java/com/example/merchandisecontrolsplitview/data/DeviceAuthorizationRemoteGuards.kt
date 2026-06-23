package com.example.merchandisecontrolsplitview.data

private suspend fun <T> guardedCloudWrite(
    authorization: ShopDeviceAuthorizationRepository,
    reason: String,
    shopId: String? = null,
    write: suspend () -> Result<T>
): Result<T> {
    val gate = authorization.ensureActiveForCloudWrite(reason, shopId)
    return gate.fold(
        onSuccess = { write() },
        onFailure = { Result.failure(it) }
    )
}

class DeviceGuardedCatalogRemoteDataSource(
    private val delegate: CatalogRemoteDataSource,
    private val authorization: ShopDeviceAuthorizationRepository
) : CatalogRemoteDataSource {
    override val isConfigured: Boolean get() = delegate.isConfigured

    override suspend fun upsertSuppliers(rows: List<InventorySupplierRow>): Result<Unit> =
        guardedCloudWrite(authorization, "catalog_upsert_suppliers") {
            delegate.upsertSuppliers(rows)
        }

    override suspend fun upsertSuppliers(rows: List<InventorySupplierRow>, shopId: String?): Result<Unit> =
        guardedCloudWrite(authorization, "catalog_upsert_suppliers", shopId) {
            delegate.upsertSuppliers(rows, shopId)
        }

    override suspend fun upsertCategories(rows: List<InventoryCategoryRow>): Result<Unit> =
        guardedCloudWrite(authorization, "catalog_upsert_categories") {
            delegate.upsertCategories(rows)
        }

    override suspend fun upsertCategories(rows: List<InventoryCategoryRow>, shopId: String?): Result<Unit> =
        guardedCloudWrite(authorization, "catalog_upsert_categories", shopId) {
            delegate.upsertCategories(rows, shopId)
        }

    override suspend fun upsertProducts(rows: List<InventoryProductRow>): Result<Unit> =
        guardedCloudWrite(authorization, "catalog_upsert_products") {
            delegate.upsertProducts(rows)
        }

    override suspend fun upsertProducts(rows: List<InventoryProductRow>, shopId: String?): Result<Unit> =
        guardedCloudWrite(authorization, "catalog_upsert_products", shopId) {
            delegate.upsertProducts(rows, shopId)
        }

    override suspend fun patchProduct(
        id: String,
        ownerUserId: String,
        patch: InventoryProductPatch
    ): Result<Unit> =
        guardedCloudWrite(authorization, "catalog_patch_product") {
            delegate.patchProduct(id, ownerUserId, patch)
        }

    override suspend fun patchProduct(
        id: String,
        ownerUserId: String,
        shopId: String?,
        patch: InventoryProductPatch
    ): Result<Unit> =
        guardedCloudWrite(authorization, "catalog_patch_product", shopId) {
            delegate.patchProduct(id, ownerUserId, shopId, patch)
        }

    override suspend fun fetchCatalog(): Result<InventoryCatalogFetchBundle> =
        delegate.fetchCatalog()

    override suspend fun fetchCatalog(shopId: String?): Result<InventoryCatalogFetchBundle> =
        delegate.fetchCatalog(shopId)

    override suspend fun fetchCatalogByIds(
        supplierIds: Set<String>,
        categoryIds: Set<String>,
        productIds: Set<String>
    ): Result<InventoryCatalogFetchBundle> =
        delegate.fetchCatalogByIds(supplierIds, categoryIds, productIds)

    override suspend fun fetchCatalogByIds(
        supplierIds: Set<String>,
        categoryIds: Set<String>,
        productIds: Set<String>,
        shopId: String?
    ): Result<InventoryCatalogFetchBundle> =
        delegate.fetchCatalogByIds(supplierIds, categoryIds, productIds, shopId)

    override suspend fun markSupplierTombstoned(patch: CatalogTombstonePatch): Result<Unit> =
        guardedCloudWrite(authorization, "catalog_tombstone_supplier") {
            delegate.markSupplierTombstoned(patch)
        }

    override suspend fun markSupplierTombstoned(patch: CatalogTombstonePatch, shopId: String?): Result<Unit> =
        guardedCloudWrite(authorization, "catalog_tombstone_supplier", shopId) {
            delegate.markSupplierTombstoned(patch, shopId)
        }

    override suspend fun markCategoryTombstoned(patch: CatalogTombstonePatch): Result<Unit> =
        guardedCloudWrite(authorization, "catalog_tombstone_category") {
            delegate.markCategoryTombstoned(patch)
        }

    override suspend fun markCategoryTombstoned(patch: CatalogTombstonePatch, shopId: String?): Result<Unit> =
        guardedCloudWrite(authorization, "catalog_tombstone_category", shopId) {
            delegate.markCategoryTombstoned(patch, shopId)
        }

    override suspend fun markProductTombstoned(patch: CatalogTombstonePatch): Result<Unit> =
        guardedCloudWrite(authorization, "catalog_tombstone_product") {
            delegate.markProductTombstoned(patch)
        }

    override suspend fun markProductTombstoned(patch: CatalogTombstonePatch, shopId: String?): Result<Unit> =
        guardedCloudWrite(authorization, "catalog_tombstone_product", shopId) {
            delegate.markProductTombstoned(patch, shopId)
        }
}

class DeviceGuardedProductPriceRemoteDataSource(
    private val delegate: ProductPriceRemoteDataSource,
    private val authorization: ShopDeviceAuthorizationRepository
) : ProductPriceRemoteDataSource {
    override val isConfigured: Boolean get() = delegate.isConfigured

    override suspend fun upsertProductPrices(rows: List<InventoryProductPriceRow>): Result<Unit> =
        guardedCloudWrite(authorization, "price_upsert_product_prices") {
            delegate.upsertProductPrices(rows)
        }

    override suspend fun upsertProductPrices(
        rows: List<InventoryProductPriceRow>,
        shopId: String?
    ): Result<Unit> =
        guardedCloudWrite(authorization, "price_upsert_product_prices", shopId) {
            delegate.upsertProductPrices(rows, shopId)
        }

    override suspend fun fetchProductPrices(): Result<List<InventoryProductPriceRow>> =
        delegate.fetchProductPrices()

    override suspend fun fetchProductPrices(shopId: String?): Result<List<InventoryProductPriceRow>> =
        delegate.fetchProductPrices(shopId)

    override suspend fun fetchProductPricesPage(
        afterId: String?,
        limit: Long
    ): Result<List<InventoryProductPriceRow>> =
        delegate.fetchProductPricesPage(afterId, limit)

    override suspend fun fetchProductPricesPage(
        afterId: String?,
        limit: Long,
        shopId: String?
    ): Result<List<InventoryProductPriceRow>> =
        delegate.fetchProductPricesPage(afterId, limit, shopId)

    override suspend fun fetchProductPricesByIds(
        remoteIds: Set<String>
    ): Result<List<InventoryProductPriceRow>> =
        delegate.fetchProductPricesByIds(remoteIds)

    override suspend fun fetchProductPricesByIds(
        remoteIds: Set<String>,
        shopId: String?
    ): Result<List<InventoryProductPriceRow>> =
        delegate.fetchProductPricesByIds(remoteIds, shopId)

    override suspend fun fetchProductPricesByProductIds(
        productRemoteIds: Set<String>
    ): Result<List<InventoryProductPriceRow>> =
        delegate.fetchProductPricesByProductIds(productRemoteIds)

    override suspend fun fetchProductPricesByProductIds(
        productRemoteIds: Set<String>,
        shopId: String?
    ): Result<List<InventoryProductPriceRow>> =
        delegate.fetchProductPricesByProductIds(productRemoteIds, shopId)
}

class DeviceGuardedSyncEventRemoteDataSource(
    private val delegate: SyncEventRemoteDataSource,
    private val authorization: ShopDeviceAuthorizationRepository
) : SyncEventRemoteDataSource {
    override val isConfigured: Boolean get() = delegate.isConfigured

    override suspend fun checkCapabilities(ownerUserId: String): Result<SyncEventRemoteCapabilities> =
        delegate.checkCapabilities(ownerUserId)

    override suspend fun recordSyncEvent(params: SyncEventRecordRpcParams): Result<SyncEventRemoteRow> =
        guardedCloudWrite(authorization, "sync_event_record", params.shopId) {
            delegate.recordSyncEvent(params)
        }

    override suspend fun fetchSyncEventsAfter(
        ownerUserId: String,
        storeId: String?,
        afterId: Long,
        limit: Long
    ): Result<List<SyncEventRemoteRow>> =
        delegate.fetchSyncEventsAfter(ownerUserId, storeId, afterId, limit)

    override suspend fun fetchSyncEventsAfter(
        ownerUserId: String,
        storeId: String?,
        shopId: String?,
        afterId: Long,
        limit: Long
    ): Result<List<SyncEventRemoteRow>> =
        delegate.fetchSyncEventsAfter(ownerUserId, storeId, shopId, afterId, limit)
}

class DeviceGuardedSessionBackupRemoteDataSource(
    private val delegate: SessionBackupRemoteDataSource,
    private val authorization: ShopDeviceAuthorizationRepository
) : SessionBackupRemoteDataSource {
    override val isConfigured: Boolean get() = delegate.isConfigured

    override suspend fun fetchAllSessionsForOwner(): Result<List<SharedSheetSessionRecord>> =
        delegate.fetchAllSessionsForOwner()

    override suspend fun fetchAllSessionsForOwner(shopId: String?): Result<List<SharedSheetSessionRecord>> =
        delegate.fetchAllSessionsForOwner(shopId)

    override suspend fun fetchSessionsByRemoteIds(
        remoteIds: Set<String>
    ): Result<List<SharedSheetSessionRecord>> =
        delegate.fetchSessionsByRemoteIds(remoteIds)

    override suspend fun fetchSessionsByRemoteIds(
        remoteIds: Set<String>,
        shopId: String?
    ): Result<List<SharedSheetSessionRecord>> =
        delegate.fetchSessionsByRemoteIds(remoteIds, shopId)

    override suspend fun upsertSessions(rows: List<SharedSheetSessionUpsertRow>): Result<Unit> =
        guardedCloudWrite(authorization, "history_session_upsert") {
            delegate.upsertSessions(rows)
        }

    override suspend fun upsertSessions(rows: List<SharedSheetSessionUpsertRow>, shopId: String?): Result<Unit> =
        guardedCloudWrite(authorization, "history_session_upsert", shopId) {
            delegate.upsertSessions(rows, shopId)
        }
}
