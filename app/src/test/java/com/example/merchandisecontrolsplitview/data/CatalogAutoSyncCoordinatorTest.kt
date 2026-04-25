package com.example.merchandisecontrolsplitview.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogAutoSyncCoordinatorTest {

    @Test
    fun `043 auto push runs targeted repository lane when signed in and foreground`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043()
        val tracker = CatalogSyncStateTracker()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = Long.MAX_VALUE
        )

        coordinator.runPushCycle("test")

        assertEquals(1, repository.pushCalls)
        assertEquals(0, repository.bootstrapCalls)
        assertEquals(false, tracker.isSyncing.value)
        coordinator.shutdown()
    }

    @Test
    fun `043 auto push skips while a manual catalog flight owns the tracker`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043()
        val tracker = CatalogSyncStateTracker()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = Long.MAX_VALUE
        )

        tracker.tryBegin(CatalogSyncFlightOwner.MANUAL)
        coordinator.runPushCycle("manual_busy")

        assertEquals(0, repository.pushCalls)
        tracker.finish(CatalogSyncFlightOwner.MANUAL)
        coordinator.shutdown()
    }

    @Test
    fun `043 bootstrap pull is process scoped by user and staleness guard`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043()
        val tracker = CatalogSyncStateTracker()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = Long.MAX_VALUE,
            bootstrapStalenessMs = Long.MAX_VALUE
        )

        coordinator.runBootstrapCycle("first")
        coordinator.runBootstrapCycle("second")

        assertEquals(1, repository.bootstrapCalls)
        assertEquals(0, repository.pushCalls)
        coordinator.shutdown()
    }

    @Test
    fun `056 network available schedules catalog catch up push`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043()
        val tracker = CatalogSyncStateTracker()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = 1L,
            bootstrapStalenessMs = Long.MAX_VALUE
        )
        runCurrent()
        advanceTimeBy(2L)
        advanceUntilIdle()
        repository.pushCalls = 0
        repository.bootstrapCalls = 0

        coordinator.onNetworkAvailable()
        advanceTimeBy(2L)
        advanceUntilIdle()

        assertEquals(1, repository.pushCalls)
        assertTrue(repository.bootstrapCalls in 0..1)
        assertEquals(false, tracker.isSyncing.value)
        coordinator.shutdown()
    }

    @Test
    fun `056 generic local catalog change schedules catch up push`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043()
        val tracker = CatalogSyncStateTracker()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = 1L
        )
        runCurrent()
        advanceTimeBy(2L)
        advanceUntilIdle()
        repository.pushCalls = 0

        coordinator.onLocalCatalogChanged()
        advanceTimeBy(2L)
        advanceUntilIdle()

        assertEquals(1, repository.pushCalls)
        assertEquals(false, tracker.isSyncing.value)
        coordinator.shutdown()
    }

    private class FakeCatalogAutoSyncRepository043 : CatalogAutoSyncRepository {
        var pushCalls = 0
        var bootstrapCalls = 0

        override suspend fun pushDirtyCatalogDeltaToRemote(
            remote: CatalogRemoteDataSource,
            priceRemote: ProductPriceRemoteDataSource,
            ownerUserId: String,
            progressReporter: CatalogSyncProgressReporter
        ): Result<CatalogSyncSummary> {
            pushCalls++
            progressReporter.onProgress(CatalogSyncProgressState.running(CatalogSyncStage.PUSH_PRODUCTS))
            return Result.success(emptySummary(pushedProducts = 1, pushedProductPrices = 1))
        }

        override suspend fun pullCatalogBootstrapFromRemote(
            remote: CatalogRemoteDataSource,
            priceRemote: ProductPriceRemoteDataSource,
            progressReporter: CatalogSyncProgressReporter
        ): Result<CatalogSyncSummary> {
            bootstrapCalls++
            progressReporter.onProgress(CatalogSyncProgressState.running(CatalogSyncStage.PULL_CATALOG))
            return Result.success(emptySummary(pulledProducts = 1, pulledProductPrices = 1))
        }
    }

    private class FakeCatalogRemote043(
        override val isConfigured: Boolean = true
    ) : CatalogRemoteDataSource {
        override suspend fun upsertSuppliers(rows: List<InventorySupplierRow>): Result<Unit> = Result.success(Unit)
        override suspend fun upsertCategories(rows: List<InventoryCategoryRow>): Result<Unit> = Result.success(Unit)
        override suspend fun upsertProducts(rows: List<InventoryProductRow>): Result<Unit> = Result.success(Unit)
        override suspend fun fetchCatalog(): Result<InventoryCatalogFetchBundle> =
            Result.success(InventoryCatalogFetchBundle(emptyList(), emptyList(), emptyList()))
        override suspend fun fetchCatalogByIds(
            supplierIds: Set<String>,
            categoryIds: Set<String>,
            productIds: Set<String>
        ): Result<InventoryCatalogFetchBundle> =
            Result.success(InventoryCatalogFetchBundle(emptyList(), emptyList(), emptyList()))
        override suspend fun markSupplierTombstoned(patch: CatalogTombstonePatch): Result<Unit> = Result.success(Unit)
        override suspend fun markCategoryTombstoned(patch: CatalogTombstonePatch): Result<Unit> = Result.success(Unit)
        override suspend fun markProductTombstoned(patch: CatalogTombstonePatch): Result<Unit> = Result.success(Unit)
    }

    private class FakePriceRemote043(
        override val isConfigured: Boolean = true
    ) : ProductPriceRemoteDataSource {
        override suspend fun upsertProductPrices(rows: List<InventoryProductPriceRow>): Result<Unit> = Result.success(Unit)
        override suspend fun fetchProductPrices(): Result<List<InventoryProductPriceRow>> = Result.success(emptyList())
        override suspend fun fetchProductPricesByIds(remoteIds: Set<String>): Result<List<InventoryProductPriceRow>> =
            Result.success(emptyList())
    }

    private companion object {
        const val USER_ID = "00000000-0000-4000-8000-000000000043"

        fun emptySummary(
            pushedProducts: Int = 0,
            pulledProducts: Int = 0,
            pushedProductPrices: Int = 0,
            pulledProductPrices: Int = 0
        ): CatalogSyncSummary =
            CatalogSyncSummary(
                pushedSuppliers = 0,
                pushedCategories = 0,
                pushedProducts = pushedProducts,
                pulledSuppliers = 0,
                pulledCategories = 0,
                pulledProducts = pulledProducts,
                pushedProductPrices = pushedProductPrices,
                pulledProductPrices = pulledProductPrices,
                deferredProductPricesNoProductRef = 0,
                skippedProductPricesPullNoProductRef = 0,
                priceSyncFailed = false
            )
    }
}
