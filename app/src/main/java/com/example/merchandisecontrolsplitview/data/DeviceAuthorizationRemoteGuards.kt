package com.example.merchandisecontrolsplitview.data

private suspend fun <T> guardedCloudWrite(
    authorization: ShopDeviceAuthorizationRepository,
    reason: String,
    write: suspend () -> Result<T>
): Result<T> {
    val gate = authorization.ensureActiveForCloudWrite(reason)
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

    override suspend fun upsertCategories(rows: List<InventoryCategoryRow>): Result<Unit> =
        guardedCloudWrite(authorization, "catalog_upsert_categories") {
            delegate.upsertCategories(rows)
        }

    override suspend fun upsertProducts(rows: List<InventoryProductRow>): Result<Unit> =
        guardedCloudWrite(authorization, "catalog_upsert_products") {
            delegate.upsertProducts(rows)
        }

    override suspend fun patchProduct(
        id: String,
        ownerUserId: String,
        patch: InventoryProductPatch
    ): Result<Unit> =
        guardedCloudWrite(authorization, "catalog_patch_product") {
            delegate.patchProduct(id, ownerUserId, patch)
        }

    override suspend fun fetchCatalog(): Result<InventoryCatalogFetchBundle> =
        delegate.fetchCatalog()

    override suspend fun fetchCatalogByIds(
        supplierIds: Set<String>,
        categoryIds: Set<String>,
        productIds: Set<String>
    ): Result<InventoryCatalogFetchBundle> =
        delegate.fetchCatalogByIds(supplierIds, categoryIds, productIds)

    override suspend fun markSupplierTombstoned(patch: CatalogTombstonePatch): Result<Unit> =
        guardedCloudWrite(authorization, "catalog_tombstone_supplier") {
            delegate.markSupplierTombstoned(patch)
        }

    override suspend fun markCategoryTombstoned(patch: CatalogTombstonePatch): Result<Unit> =
        guardedCloudWrite(authorization, "catalog_tombstone_category") {
            delegate.markCategoryTombstoned(patch)
        }

    override suspend fun markProductTombstoned(patch: CatalogTombstonePatch): Result<Unit> =
        guardedCloudWrite(authorization, "catalog_tombstone_product") {
            delegate.markProductTombstoned(patch)
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

    override suspend fun fetchProductPrices(): Result<List<InventoryProductPriceRow>> =
        delegate.fetchProductPrices()

    override suspend fun fetchProductPricesPage(
        afterId: String?,
        limit: Long
    ): Result<List<InventoryProductPriceRow>> =
        delegate.fetchProductPricesPage(afterId, limit)

    override suspend fun fetchProductPricesByIds(
        remoteIds: Set<String>
    ): Result<List<InventoryProductPriceRow>> =
        delegate.fetchProductPricesByIds(remoteIds)

    override suspend fun fetchProductPricesByProductIds(
        productRemoteIds: Set<String>
    ): Result<List<InventoryProductPriceRow>> =
        delegate.fetchProductPricesByProductIds(productRemoteIds)
}

class DeviceGuardedSyncEventRemoteDataSource(
    private val delegate: SyncEventRemoteDataSource,
    private val authorization: ShopDeviceAuthorizationRepository
) : SyncEventRemoteDataSource {
    override val isConfigured: Boolean get() = delegate.isConfigured

    override suspend fun checkCapabilities(ownerUserId: String): Result<SyncEventRemoteCapabilities> =
        delegate.checkCapabilities(ownerUserId)

    override suspend fun recordSyncEvent(params: SyncEventRecordRpcParams): Result<SyncEventRemoteRow> =
        guardedCloudWrite(authorization, "sync_event_record") {
            delegate.recordSyncEvent(params)
        }

    override suspend fun fetchSyncEventsAfter(
        ownerUserId: String,
        storeId: String?,
        afterId: Long,
        limit: Long
    ): Result<List<SyncEventRemoteRow>> =
        delegate.fetchSyncEventsAfter(ownerUserId, storeId, afterId, limit)
}

class DeviceGuardedSessionBackupRemoteDataSource(
    private val delegate: SessionBackupRemoteDataSource,
    private val authorization: ShopDeviceAuthorizationRepository
) : SessionBackupRemoteDataSource {
    override val isConfigured: Boolean get() = delegate.isConfigured

    override suspend fun fetchAllSessionsForOwner(): Result<List<SharedSheetSessionRecord>> =
        delegate.fetchAllSessionsForOwner()

    override suspend fun fetchSessionsByRemoteIds(
        remoteIds: Set<String>
    ): Result<List<SharedSheetSessionRecord>> =
        delegate.fetchSessionsByRemoteIds(remoteIds)

    override suspend fun upsertSessions(rows: List<SharedSheetSessionUpsertRow>): Result<Unit> =
        guardedCloudWrite(authorization, "history_session_upsert") {
            delegate.upsertSessions(rows)
        }
}
